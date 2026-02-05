package com.hytaledocs.intellij.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

@Service(Service.Level.PROJECT)
class ServerLaunchService(private val project: Project) : Disposable {

    /**
     * ExecutorService with daemon threads for background server monitoring tasks.
     * Using a cached thread pool since we may have multiple servers with 2 threads each.
     */
    private val executorService: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "HytaleServer-${threadCount.incrementAndGet()}"
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ServerLaunchService::class.java)
        private val threadCount = AtomicInteger(0)

        const val DEFAULT_MIN_MEMORY = "2G"
        const val DEFAULT_MAX_MEMORY = "8G"
        const val DEFAULT_PORT = 5520
        const val DEFAULT_PROFILE_ID = "default"

        private val JOIN_PATTERNS = listOf(
            Regex("""Player\s+(\S+)\s+(?:joined|connected)""", RegexOption.IGNORE_CASE),
            Regex("""(\S+)\s+joined the game""", RegexOption.IGNORE_CASE),
            Regex("""\[ServerConnectionManager\].*?(\S+).*?connected""", RegexOption.IGNORE_CASE)
        )

        private val LEAVE_PATTERNS = listOf(
            Regex("""Player\s+(\S+)\s+(?:left|disconnected)""", RegexOption.IGNORE_CASE),
            Regex("""(\S+)\s+left the game""", RegexOption.IGNORE_CASE),
            Regex("""\[ServerConnectionManager\].*?(\S+).*?disconnected""", RegexOption.IGNORE_CASE)
        )

        fun getInstance(project: Project): ServerLaunchService {
            return project.getService(ServerLaunchService::class.java)
        }
    }

    data class ServerConfig(
        val serverPath: Path,
        val javaPath: Path,
        val minMemory: String = DEFAULT_MIN_MEMORY,
        val maxMemory: String = DEFAULT_MAX_MEMORY,
        val port: Int = DEFAULT_PORT,
        val authMode: AuthMode = AuthMode.OFFLINE,
        val allowOp: Boolean = true,
        val acceptEarlyPlugins: Boolean = true,
        val additionalJvmArgs: List<String> = emptyList(),
        val additionalServerArgs: List<String> = emptyList()
    )

    enum class AuthMode(val value: String) {
        AUTHENTICATED("authenticated"),
        OFFLINE("offline")
    }

    enum class ServerStatus {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }

    data class ServerStats(
        val status: ServerStatus,
        val uptime: Duration?,
        val playerCount: Int,
        val memoryUsageMB: Long?,
        val cpuUsagePercent: Double?
    )

    /**
     * Represents a single running server instance with all its state.
     */
    data class ServerInstance(
        val profileId: String,
        val config: ServerConfig,
        @Volatile var process: Process?,
        @Volatile var status: ServerStatus = ServerStatus.STOPPED,
        @Volatile var startTime: Instant? = null,
        val playerCount: AtomicInteger = AtomicInteger(0),
        val connectedPlayers: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val logCallback: Consumer<String>? = null,
        val statusCallback: Consumer<ServerStatus>? = null
    ) {
        fun getStats(): ServerStats {
            return ServerStats(
                status = status,
                uptime = startTime?.let { Duration.between(it, Instant.now()) },
                playerCount = playerCount.get(),
                memoryUsageMB = null,
                cpuUsagePercent = null
            )
        }

        fun isRunning(): Boolean = status == ServerStatus.RUNNING || status == ServerStatus.STARTING
    }

    /**
     * Map of profile ID to server instance. Thread-safe for concurrent access.
     */
    private val servers: ConcurrentHashMap<String, ServerInstance> = ConcurrentHashMap()

    /**
     * Lock object for synchronizing status updates across multiple servers.
     */
    private val statusLock = Any()

    // ===================================================================================
    // Multi-Server Methods
    // ===================================================================================

    /**
     * Gets the status of a specific server by profile ID.
     */
    fun getStatus(profileId: String): ServerStatus {
        return servers[profileId]?.status ?: ServerStatus.STOPPED
    }

    /**
     * Checks if a specific server is running.
     */
    fun isServerRunning(profileId: String): Boolean {
        return servers[profileId]?.isRunning() ?: false
    }

    /**
     * Gets the player count for a specific server.
     */
    fun getPlayerCount(profileId: String): Int {
        return servers[profileId]?.playerCount?.get() ?: 0
    }

    /**
     * Gets the connected players for a specific server.
     */
    fun getConnectedPlayers(profileId: String): Set<String> {
        return servers[profileId]?.connectedPlayers?.toSet() ?: emptySet()
    }

    /**
     * Gets stats for a specific server.
     */
    fun getServerStats(profileId: String): ServerStats {
        return servers[profileId]?.getStats() ?: ServerStats(
            status = ServerStatus.STOPPED,
            uptime = null,
            playerCount = 0,
            memoryUsageMB = null,
            cpuUsagePercent = null
        )
    }

    /**
     * Gets a list of all running server profile IDs.
     */
    fun getRunningServers(): List<String> {
        return servers.entries
            .filter { it.value.isRunning() }
            .map { it.key }
    }

    /**
     * Gets all server instances (both running and stopped that haven't been cleaned up).
     */
    fun getAllServers(): Map<String, ServerInstance> {
        return servers.toMap()
    }

    /**
     * Gets a specific server instance by profile ID.
     */
    fun getServerInstance(profileId: String): ServerInstance? {
        return servers[profileId]
    }

    /**
     * Checks if any server needs authentication.
     */
    fun needsAuthentication(profileId: String): Boolean {
        if (!isServerRunning(profileId)) return false
        val authService = AuthenticationService.getInstance()
        return authService.isAuthenticating()
    }

    /**
     * Triggers authentication for a specific server.
     */
    fun triggerAuth(profileId: String) {
        if (isServerRunning(profileId)) {
            val authService = AuthenticationService.getInstance()
            authService.triggerServerAuth(project)
        }
    }

    /**
     * Parses a log line for player join/leave events for a specific server instance.
     */
    private fun parseLogForPlayers(instance: ServerInstance, line: String) {
        // Delegate auth parsing to centralized service
        val authService = AuthenticationService.getInstance()
        authService.parseServerLogLine(line, project)

        for (pattern in JOIN_PATTERNS) {
            pattern.find(line)?.let { match ->
                val playerName = match.groupValues[1]
                if (instance.connectedPlayers.add(playerName)) {
                    instance.playerCount.incrementAndGet()
                }
                return
            }
        }

        for (pattern in LEAVE_PATTERNS) {
            pattern.find(line)?.let { match ->
                val playerName = match.groupValues[1]
                if (instance.connectedPlayers.remove(playerName)) {
                    instance.playerCount.decrementAndGet()
                }
                return
            }
        }
    }

    /**
     * Validates that all required files exist for launching the server.
     */
    fun validateServerFiles(serverPath: Path): ValidationResult {
        val errors = mutableListOf<String>()

        val serverJar = serverPath.resolve(ServerDownloadService.SERVER_JAR_NAME)
        if (!Files.exists(serverJar)) {
            errors.add("HytaleServer.jar not found")
        }

        val assetsZip = serverPath.resolve(ServerDownloadService.ASSETS_NAME)
        if (!Files.exists(assetsZip)) {
            errors.add("Assets.zip not found (server may still work in some modes)")
        }

        return ValidationResult(errors.isEmpty(), errors)
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>
    )

    /**
     * Starts a server with the given profile ID and configuration.
     *
     * @param profileId Unique identifier for this server instance
     * @param config Server configuration
     * @param logCallback Callback for log output
     * @param statusCallback Callback for status changes
     * @return CompletableFuture that resolves to true if the server started successfully
     */
    fun startServer(
        profileId: String,
        config: ServerConfig,
        logCallback: Consumer<String>? = null,
        statusCallback: Consumer<ServerStatus>? = null
    ): CompletableFuture<Boolean> {
        // Check if this profile already has a running server
        val existingInstance = servers[profileId]
        if (existingInstance != null && existingInstance.isRunning()) {
            logCallback?.accept("Server with profile ID '$profileId' is already running")
            return CompletableFuture.completedFuture(false)
        }

        // Check for port conflicts with other running servers
        for ((otherId, otherInstance) in servers) {
            if (otherId != profileId && otherInstance.isRunning() && otherInstance.config.port == config.port) {
                logCallback?.accept("Port ${config.port} is already in use by server '$otherId'")
                return CompletableFuture.completedFuture(false)
            }
        }

        return CompletableFuture.supplyAsync {
            try {
                // Create the server instance
                val instance = ServerInstance(
                    profileId = profileId,
                    config = config,
                    process = null,
                    status = ServerStatus.STARTING,
                    logCallback = logCallback,
                    statusCallback = statusCallback
                )

                servers[profileId] = instance

                synchronized(statusLock) {
                    instance.status = ServerStatus.STARTING
                }
                statusCallback?.accept(ServerStatus.STARTING)

                val command = buildCommand(config)

                logCallback?.accept("[$profileId] Starting server with command:")
                logCallback?.accept(command.joinToString(" "))
                logCallback?.accept("")

                val processBuilder = ProcessBuilder(command)
                    .directory(config.serverPath.toFile())
                    .redirectErrorStream(true)

                val process = processBuilder.start()
                instance.process = process
                instance.startTime = Instant.now()
                instance.playerCount.set(0)
                instance.connectedPlayers.clear()

                // Start log reader task
                executorService.submit {
                    try {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val currentLine = line ?: continue
                                logCallback?.accept(currentLine)
                                parseLogForPlayers(instance, currentLine)

                                // Detect server boot
                                if (currentLine.contains("Hytale Server Booted!") ||
                                    currentLine.contains("Server started")) {
                                    synchronized(statusLock) {
                                        instance.status = ServerStatus.RUNNING
                                    }
                                    statusCallback?.accept(ServerStatus.RUNNING)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (instance.isRunning()) {
                            logCallback?.accept("[$profileId] Error reading logs: ${e.message}")
                        }
                    }
                }

                // Wait for process to complete (in background)
                executorService.submit {
                    try {
                        val exitCode = process.waitFor()
                        synchronized(statusLock) {
                            instance.status = ServerStatus.STOPPED
                            instance.startTime = null
                            instance.playerCount.set(0)
                            instance.connectedPlayers.clear()
                        }
                        statusCallback?.accept(ServerStatus.STOPPED)
                        logCallback?.accept("")
                        logCallback?.accept("[$profileId] Server stopped with exit code: $exitCode")
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        synchronized(statusLock) {
                            instance.status = ServerStatus.STOPPED
                            instance.startTime = null
                        }
                    } catch (e: Exception) {
                        synchronized(statusLock) {
                            instance.status = ServerStatus.ERROR
                            instance.startTime = null
                        }
                        statusCallback?.accept(ServerStatus.ERROR)
                        logCallback?.accept("[$profileId] Server error: ${e.message}")
                    }
                }

                true
            } catch (e: Exception) {
                val instance = servers[profileId]
                if (instance != null) {
                    synchronized(statusLock) {
                        instance.status = ServerStatus.ERROR
                    }
                }
                statusCallback?.accept(ServerStatus.ERROR)
                logCallback?.accept("[$profileId] Failed to start server: ${e.message}")
                false
            }
        }
    }

    /**
     * Stops a specific server by profile ID.
     *
     * @param profileId The profile ID of the server to stop
     * @param logCallback Callback for log output
     * @param statusCallback Callback for status changes
     * @return CompletableFuture that resolves to true if the server was stopped successfully
     */
    fun stopServer(
        profileId: String,
        logCallback: Consumer<String>? = null,
        statusCallback: Consumer<ServerStatus>? = null
    ): CompletableFuture<Boolean> {
        val instance = servers[profileId]
        if (instance == null || !instance.isRunning()) {
            logCallback?.accept("[$profileId] Server is not running")
            return CompletableFuture.completedFuture(false)
        }

        return CompletableFuture.supplyAsync {
            try {
                synchronized(statusLock) {
                    instance.status = ServerStatus.STOPPING
                }
                statusCallback?.accept(ServerStatus.STOPPING)
                logCallback?.accept("[$profileId] Stopping server...")

                instance.process?.let { process ->
                    // Try graceful shutdown first
                    try {
                        process.outputStream.write("stop\n".toByteArray())
                        process.outputStream.flush()
                    } catch (e: Exception) {
                        LOG.warn("[$profileId] Failed to send stop command, will force kill", e)
                    }

                    // Wait up to 30 seconds for graceful shutdown
                    val exited = process.waitFor(30, TimeUnit.SECONDS)

                    if (!exited) {
                        logCallback?.accept("[$profileId] Server did not stop gracefully, forcing...")
                        process.destroyForcibly()
                        if (!process.waitFor(10, TimeUnit.SECONDS)) {
                            LOG.error("[$profileId] Failed to kill server process after force destroy")
                        }
                    }
                }

                synchronized(statusLock) {
                    instance.process = null
                    instance.status = ServerStatus.STOPPED
                    instance.startTime = null
                    instance.playerCount.set(0)
                    instance.connectedPlayers.clear()
                }
                statusCallback?.accept(ServerStatus.STOPPED)
                logCallback?.accept("[$profileId] Server stopped.")
                true
            } catch (e: Exception) {
                LOG.error("[$profileId] Error stopping server", e)
                logCallback?.accept("[$profileId] Error stopping server: ${e.message}")
                // Force kill
                instance.process?.destroyForcibly()
                synchronized(statusLock) {
                    instance.process = null
                    instance.status = ServerStatus.STOPPED
                    instance.startTime = null
                }
                statusCallback?.accept(ServerStatus.STOPPED)
                false
            }
        }
    }

    /**
     * Stops all running servers.
     *
     * @param logCallback Callback for log output
     * @return CompletableFuture that resolves when all servers are stopped
     */
    fun stopAllServers(logCallback: Consumer<String>? = null): CompletableFuture<Void> {
        val runningServers = getRunningServers()
        if (runningServers.isEmpty()) {
            logCallback?.accept("No servers are running")
            return CompletableFuture.completedFuture(null)
        }

        logCallback?.accept("Stopping ${runningServers.size} server(s)...")

        val futures = runningServers.map { profileId ->
            stopServer(profileId, logCallback, null)
        }

        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    /**
     * Sends a command to a specific server.
     *
     * @param profileId The profile ID of the server
     * @param command The command to send
     * @return true if the command was sent successfully
     */
    fun sendCommand(profileId: String, command: String): Boolean {
        val instance = servers[profileId]
        if (instance == null || !instance.isRunning()) {
            LOG.warn("[$profileId] Cannot send command '$command': server is not running")
            return false
        }

        return try {
            instance.process?.outputStream?.let { stream ->
                stream.write("$command\n".toByteArray())
                stream.flush()
                true
            } ?: run {
                LOG.warn("[$profileId] Cannot send command '$command': server process output stream is null")
                false
            }
        } catch (e: Exception) {
            LOG.warn("[$profileId] Failed to send command '$command' to server", e)
            false
        }
    }

    /**
     * Removes a stopped server instance from the map.
     * This can be used to clean up old server instances.
     *
     * @param profileId The profile ID of the server to remove
     * @return true if the server was removed, false if it was still running or didn't exist
     */
    fun removeServer(profileId: String): Boolean {
        val instance = servers[profileId] ?: return false
        if (instance.isRunning()) {
            LOG.warn("[$profileId] Cannot remove server: still running")
            return false
        }
        servers.remove(profileId)
        return true
    }

    // ===================================================================================
    // Backward Compatibility Methods (using DEFAULT_PROFILE_ID)
    // ===================================================================================

    /**
     * Gets the status of the default server.
     * @deprecated Use getStatus(profileId) instead
     */
    @Deprecated("Use getStatus(profileId) instead", ReplaceWith("getStatus(DEFAULT_PROFILE_ID)"))
    fun getStatus(): ServerStatus = getStatus(DEFAULT_PROFILE_ID)

    /**
     * Checks if the default server is running.
     * @deprecated Use isServerRunning(profileId) instead
     */
    @Deprecated("Use isServerRunning(profileId) instead", ReplaceWith("isServerRunning(DEFAULT_PROFILE_ID)"))
    fun isServerRunning(): Boolean = isServerRunning(DEFAULT_PROFILE_ID)

    /**
     * Gets the player count for the default server.
     * @deprecated Use getPlayerCount(profileId) instead
     */
    @Deprecated("Use getPlayerCount(profileId) instead", ReplaceWith("getPlayerCount(DEFAULT_PROFILE_ID)"))
    fun getPlayerCount(): Int = getPlayerCount(DEFAULT_PROFILE_ID)

    /**
     * Gets the connected players for the default server.
     * @deprecated Use getConnectedPlayers(profileId) instead
     */
    @Deprecated("Use getConnectedPlayers(profileId) instead", ReplaceWith("getConnectedPlayers(DEFAULT_PROFILE_ID)"))
    fun getConnectedPlayers(): Set<String> = getConnectedPlayers(DEFAULT_PROFILE_ID)

    /**
     * Gets stats for the default server.
     * @deprecated Use getServerStats(profileId) instead
     */
    @Deprecated("Use getServerStats(profileId) instead", ReplaceWith("getServerStats(DEFAULT_PROFILE_ID)"))
    fun getStats(): ServerStats = getServerStats(DEFAULT_PROFILE_ID)

    /**
     * Checks if authentication is needed for the default server.
     * @deprecated Use needsAuthentication(profileId) instead
     */
    @Deprecated("Use needsAuthentication(profileId) instead", ReplaceWith("needsAuthentication(DEFAULT_PROFILE_ID)"))
    fun needsAuthentication(): Boolean = needsAuthentication(DEFAULT_PROFILE_ID)

    /**
     * Triggers authentication for the default server.
     * @deprecated Use triggerAuth(profileId) instead
     */
    @Deprecated("Use triggerAuth(profileId) instead", ReplaceWith("triggerAuth(DEFAULT_PROFILE_ID)"))
    fun triggerAuth() = triggerAuth(DEFAULT_PROFILE_ID)

    /**
     * Starts the default server with the given configuration.
     * @deprecated Use startServer(profileId, config, logCallback, statusCallback) instead
     */
    @Deprecated(
        "Use startServer(profileId, config, logCallback, statusCallback) instead",
        ReplaceWith("startServer(DEFAULT_PROFILE_ID, config, logCallback, statusCallback)")
    )
    fun startServer(
        config: ServerConfig,
        logCallback: Consumer<String>? = null,
        statusCallback: Consumer<ServerStatus>? = null
    ): CompletableFuture<Boolean> = startServer(DEFAULT_PROFILE_ID, config, logCallback, statusCallback)

    /**
     * Stops the default server.
     * @deprecated Use stopServer(profileId, logCallback, statusCallback) instead
     */
    @Deprecated(
        "Use stopServer(profileId, logCallback, statusCallback) instead",
        ReplaceWith("stopServer(DEFAULT_PROFILE_ID, logCallback, statusCallback)")
    )
    fun stopServer(
        logCallback: Consumer<String>? = null,
        statusCallback: Consumer<ServerStatus>? = null
    ): CompletableFuture<Boolean> = stopServer(DEFAULT_PROFILE_ID, logCallback, statusCallback)

    /**
     * Sends a command to the default server.
     * @deprecated Use sendCommand(profileId, command) instead
     */
    @Deprecated(
        "Use sendCommand(profileId, command) instead",
        ReplaceWith("sendCommand(DEFAULT_PROFILE_ID, command)")
    )
    fun sendCommand(command: String): Boolean = sendCommand(DEFAULT_PROFILE_ID, command)

    // ===================================================================================
    // Helper Methods
    // ===================================================================================

    /**
     * Builds the command line arguments for launching the server.
     */
    private fun buildCommand(config: ServerConfig): List<String> {
        val command = mutableListOf<String>()

        // Java executable
        command.add(config.javaPath.toString())

        // Memory settings
        command.add("-Xms${config.minMemory}")
        command.add("-Xmx${config.maxMemory}")

        // Additional JVM args
        command.addAll(config.additionalJvmArgs)

        // JAR
        command.add("-jar")
        command.add(ServerDownloadService.SERVER_JAR_NAME)

        // Assets
        val assetsPath = config.serverPath.resolve(ServerDownloadService.ASSETS_NAME)
        if (Files.exists(assetsPath)) {
            command.add("--assets")
            command.add(ServerDownloadService.ASSETS_NAME)
        }

        // Bind address
        command.add("-b")
        command.add("0.0.0.0:${config.port}")

        // Auth mode
        command.add("--auth-mode")
        command.add(config.authMode.value)

        // Optional flags
        if (config.allowOp) {
            command.add("--allow-op")
        }

        if (config.acceptEarlyPlugins) {
            command.add("--accept-early-plugins")
        }

        // Additional server args
        command.addAll(config.additionalServerArgs)

        return command
    }

    /**
     * Creates a default server configuration for the project.
     */
    fun createDefaultConfig(
        serverPath: Path,
        javaInstallation: JavaInstallService.JavaInstallation
    ): ServerConfig {
        val javaService = JavaInstallService.getInstance()
        val javaPath = javaService.getJavaExecutable(javaInstallation)

        return ServerConfig(
            serverPath = serverPath,
            javaPath = javaPath
        )
    }

    /**
     * Cleans up resources when the service is disposed.
     * Stops all servers and shuts down the executor service.
     */
    override fun dispose() {
        // Stop all running servers
        for ((profileId, instance) in servers) {
            if (instance.isRunning()) {
                instance.process?.let { process ->
                    try {
                        LOG.info("[$profileId] Disposing: sending stop command")
                        process.outputStream.write("stop\n".toByteArray())
                        process.outputStream.flush()
                        if (!process.waitFor(5, TimeUnit.SECONDS)) {
                            LOG.info("[$profileId] Disposing: force killing")
                            process.destroyForcibly()
                        }
                    } catch (e: Exception) {
                        LOG.warn("[$profileId] Disposing: error during shutdown, force killing", e)
                        process.destroyForcibly()
                    }
                }
                synchronized(statusLock) {
                    instance.status = ServerStatus.STOPPED
                }
            }
        }

        servers.clear()

        // Shutdown the executor service gracefully
        executorService.shutdown()
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
                executorService.awaitTermination(2, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            executorService.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
