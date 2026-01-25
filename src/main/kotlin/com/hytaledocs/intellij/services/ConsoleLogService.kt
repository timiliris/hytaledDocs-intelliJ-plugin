package com.hytaledocs.intellij.services

import com.hytaledocs.intellij.bridge.DevBridgeConnection
import com.hytaledocs.intellij.bridge.DevBridgeServer
import com.hytaledocs.intellij.bridge.protocol.HytaleBridgeProto.LogEvent
import com.hytaledocs.intellij.bridge.protocol.HytaleBridgeProto.LogLevel
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Mediates between log sources (stdout parsing and bridge events) and the console UI.
 * Manages mode switching and provides a unified log event interface.
 */
@Service(Service.Level.PROJECT)
class ConsoleLogService(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(ConsoleLogService::class.java)
        private val FALLBACK_LOG_REGEX = Regex("""^\[[\d/]+\s+[\d:]+\s+(\w+)\]\s+(.*)$""")

        fun getInstance(project: Project): ConsoleLogService {
            return project.getService(ConsoleLogService::class.java)
        }
    }

    /**
     * Console log mode - fallback (stdout parsing) or bridge (structured events).
     */
    enum class ConsoleLogMode {
        FALLBACK_PARSING,
        BRIDGE_CONNECTED
    }

    /**
     * Log level for unified events.
     */
    enum class UnifiedLogLevel {
        UNKNOWN,
        TRACE,
        DEBUG,
        INFO,
        WARNING,
        ERROR,
        FATAL
    }

    /**
     * Unified log event that can come from either source.
     */
    data class UnifiedLogEvent(
        val timestamp: Long,
        val level: UnifiedLogLevel,
        val loggerName: String?,
        val message: String,
        val throwable: String?,
        val threadName: String?,
        val isSystemMessage: Boolean = false
    )

    // Current mode
    private val currentMode = AtomicReference(ConsoleLogMode.FALLBACK_PARSING)

    // Active bridge connection (when in BRIDGE_CONNECTED mode)
    private var activeConnection: DevBridgeConnection? = null

    // Callbacks
    private val logCallbacks = CopyOnWriteArrayList<(UnifiedLogEvent) -> Unit>()
    private val modeCallbacks = CopyOnWriteArrayList<(ConsoleLogMode) -> Unit>()

    // Bridge connection listener
    private val connectionListener = object : DevBridgeServer.ConnectionListener {
        override fun onConnectionEstablished(connection: DevBridgeConnection) {
            LOG.info("Bridge connection established, switching to BRIDGE_CONNECTED mode")
            activeConnection = connection
            connection.addLogListener(bridgeLogListener)
            setMode(ConsoleLogMode.BRIDGE_CONNECTED)
        }

        override fun onConnectionClosed(connection: DevBridgeConnection) {
            LOG.info("Bridge connection closed, switching to FALLBACK_PARSING mode")
            if (activeConnection == connection) {
                activeConnection = null
                setMode(ConsoleLogMode.FALLBACK_PARSING)
                // Clear bridge asset paths since the connection is gone
                AssetScannerService.getInstance(project).clearBridgeAssetPaths()
            }
        }
    }

    // Bridge log listener
    private val bridgeLogListener: (LogEvent) -> Unit = { event ->
        val unifiedEvent = UnifiedLogEvent(
            timestamp = event.timestamp,
            level = convertLogLevel(event.level),
            loggerName = event.loggerName.takeIf { it.isNotEmpty() },
            message = event.message,
            throwable = event.throwable.takeIf { it.isNotEmpty() },
            threadName = event.threadName.takeIf { it.isNotEmpty() },
            isSystemMessage = false
        )
        fireLogEvent(unifiedEvent)
    }

    init {
        // Register with DevBridgeServer for connection events
        val bridgeServer = DevBridgeServer.getInstance(project)
        bridgeServer.addConnectionListener(connectionListener)

        // If there's already an active connection, switch to bridge mode
        bridgeServer.getActiveConnection()?.let { connection ->
            activeConnection = connection
            connection.addLogListener(bridgeLogListener)
            currentMode.set(ConsoleLogMode.BRIDGE_CONNECTED)
        }
    }

    /**
     * Get current console log mode.
     */
    fun getMode(): ConsoleLogMode = currentMode.get()

    /**
     * Check if bridge is currently connected.
     */
    fun isBridgeConnected(): Boolean = currentMode.get() == ConsoleLogMode.BRIDGE_CONNECTED

    /**
     * Get active bridge connection, if any.
     */
    fun getActiveConnection(): DevBridgeConnection? = activeConnection

    /**
     * Register a callback to receive log events.
     */
    fun registerLogCallback(callback: (UnifiedLogEvent) -> Unit) {
        logCallbacks.add(callback)
    }

    /**
     * Unregister a log callback.
     */
    fun unregisterLogCallback(callback: (UnifiedLogEvent) -> Unit) {
        logCallbacks.remove(callback)
    }

    /**
     * Register a callback to receive mode change notifications.
     */
    fun registerModeCallback(callback: (ConsoleLogMode) -> Unit) {
        modeCallbacks.add(callback)
    }

    /**
     * Unregister a mode callback.
     */
    fun unregisterModeCallback(callback: (ConsoleLogMode) -> Unit) {
        modeCallbacks.remove(callback)
    }

    /**
     * Process a fallback log line from stdout.
     * Parses the line and fires a unified log event.
     */
    fun onFallbackLog(line: String) {
        // Skip if we're in bridge mode (bridge provides structured logs)
        if (currentMode.get() == ConsoleLogMode.BRIDGE_CONNECTED) {
            return
        }

        val event = parseFallbackLog(line)
        fireLogEvent(event)
    }

    /**
     * Log a system message (not from the server, but from the IDE).
     */
    fun logSystemMessage(message: String) {
        val event = UnifiedLogEvent(
            timestamp = System.currentTimeMillis(),
            level = UnifiedLogLevel.INFO,
            loggerName = null,
            message = message,
            throwable = null,
            threadName = null,
            isSystemMessage = true
        )
        fireLogEvent(event)
    }

    /**
     * Reset the service state (e.g., when server stops).
     */
    fun reset() {
        // Note: we don't change mode here - the bridge connection may still be active
        // Mode will change when the connection actually closes
    }

    private fun setMode(mode: ConsoleLogMode) {
        val previous = currentMode.getAndSet(mode)
        if (previous != mode) {
            LOG.info("Console log mode changed: $previous -> $mode")
            modeCallbacks.forEach { callback ->
                try {
                    callback(mode)
                } catch (e: Exception) {
                    LOG.warn("Error in mode callback", e)
                }
            }
        }
    }

    private fun fireLogEvent(event: UnifiedLogEvent) {
        logCallbacks.forEach { callback ->
            try {
                callback(event)
            } catch (e: Exception) {
                LOG.warn("Error in log callback", e)
            }
        }
    }

    private fun parseFallbackLog(line: String): UnifiedLogEvent {
        val match = FALLBACK_LOG_REGEX.find(line)
        return if (match != null) {
            val levelStr = match.groupValues[1]
            val message = match.groupValues[2].trim()
            UnifiedLogEvent(
                timestamp = System.currentTimeMillis(),
                level = parseLogLevel(levelStr),
                loggerName = null,
                message = message,
                throwable = null,
                threadName = null,
                isSystemMessage = false
            )
        } else {
            // Line doesn't match expected format, treat as INFO
            UnifiedLogEvent(
                timestamp = System.currentTimeMillis(),
                level = UnifiedLogLevel.INFO,
                loggerName = null,
                message = line,
                throwable = null,
                threadName = null,
                isSystemMessage = false
            )
        }
    }

    private fun parseLogLevel(level: String): UnifiedLogLevel {
        return when (level.uppercase()) {
            "TRACE" -> UnifiedLogLevel.TRACE
            "DEBUG", "FINE", "FINER", "FINEST" -> UnifiedLogLevel.DEBUG
            "INFO" -> UnifiedLogLevel.INFO
            "WARN", "WARNING" -> UnifiedLogLevel.WARNING
            "ERROR", "SEVERE" -> UnifiedLogLevel.ERROR
            "FATAL" -> UnifiedLogLevel.FATAL
            else -> UnifiedLogLevel.INFO
        }
    }

    private fun convertLogLevel(protoLevel: LogLevel): UnifiedLogLevel {
        return when (protoLevel) {
            LogLevel.LOG_LEVEL_TRACE -> UnifiedLogLevel.TRACE
            LogLevel.LOG_LEVEL_DEBUG -> UnifiedLogLevel.DEBUG
            LogLevel.LOG_LEVEL_INFO -> UnifiedLogLevel.INFO
            LogLevel.LOG_LEVEL_WARNING -> UnifiedLogLevel.WARNING
            LogLevel.LOG_LEVEL_ERROR -> UnifiedLogLevel.ERROR
            LogLevel.LOG_LEVEL_FATAL -> UnifiedLogLevel.FATAL
            else -> UnifiedLogLevel.UNKNOWN
        }
    }

    override fun dispose() {
        // Unregister from bridge server
        try {
            val bridgeServer = DevBridgeServer.getInstance(project)
            bridgeServer.removeConnectionListener(connectionListener)
        } catch (e: Exception) {
            LOG.warn("Error unregistering from bridge server", e)
        }

        // Remove log listener from active connection
        activeConnection?.removeLogListener(bridgeLogListener)
        activeConnection = null

        // Clear callbacks
        logCallbacks.clear()
        modeCallbacks.clear()
    }
}
