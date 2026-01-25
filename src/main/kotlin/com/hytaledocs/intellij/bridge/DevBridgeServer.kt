package com.hytaledocs.intellij.bridge

import com.hytaledocs.intellij.bridge.protocol.HytaleBridgeProto.AgentMessage
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket server that accepts connections from the Hytale Dev Bridge running
 * in the server process. Handles structured log forwarding, command registry
 * exchange, and asset path synchronization.
 */
@Service(Service.Level.PROJECT)
class DevBridgeServer(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(DevBridgeServer::class.java)
        private const val PATH = "/hytale-dev-bridge"

        fun getInstance(project: Project): DevBridgeServer {
            return project.getService(DevBridgeServer::class.java)
        }
    }

    private var server: BridgeWebSocketServer? = null
    private val isRunning = AtomicBoolean(false)

    private var _port: Int = 0
    private var _authToken: String = ""

    val port: Int get() = _port
    val authToken: String get() = _authToken

    private val connections = ConcurrentHashMap<WebSocket, DevBridgeConnection>()
    private val connectionListeners = CopyOnWriteArrayList<ConnectionListener>()

    /**
     * Listener for bridge connection events.
     */
    interface ConnectionListener {
        fun onConnectionEstablished(connection: DevBridgeConnection)
        fun onConnectionClosed(connection: DevBridgeConnection)
    }

    /**
     * Ensures the bridge server is started. Idempotent - safe to call multiple times.
     * @return true if the server is running after this call
     */
    @Synchronized
    fun ensureStarted(): Boolean {
        if (isRunning.get()) return true

        return try {
            // Allocate port atomically using ServerSocket(0)
            val tempSocket = ServerSocket(0)
            _port = tempSocket.localPort
            tempSocket.close()

            // Generate secure random token (64 hex characters = 256 bits)
            _authToken = generateToken()

            // Start WebSocket server on localhost only
            server = BridgeWebSocketServer(InetSocketAddress("127.0.0.1", _port))
            server?.start()

            isRunning.set(true)
            LOG.info("DevBridgeServer started on port $_port")
            true
        } catch (e: Exception) {
            LOG.error("Failed to start DevBridgeServer", e)
            false
        }
    }

    private fun generateToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun addConnectionListener(listener: ConnectionListener) {
        connectionListeners.add(listener)
    }

    fun removeConnectionListener(listener: ConnectionListener) {
        connectionListeners.remove(listener)
    }

    /**
     * Get the first active connection, if any.
     */
    fun getActiveConnection(): DevBridgeConnection? {
        return connections.values.firstOrNull { it.isHandshakeComplete() }
    }

    /**
     * Check if there is an active bridge connection.
     */
    fun isConnected(): Boolean {
        return connections.values.any { it.isHandshakeComplete() }
    }

    override fun dispose() {
        isRunning.set(false)

        connections.values.forEach { it.close() }
        connections.clear()

        try {
            server?.stop(1000)
        } catch (e: Exception) {
            LOG.warn("Error stopping WebSocket server", e)
        }
        server = null

        LOG.info("DevBridgeServer stopped")
    }

    /**
     * Inner WebSocket server implementation.
     */
    private inner class BridgeWebSocketServer(
        address: InetSocketAddress
    ) : WebSocketServer(address) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            // Validate path
            val resourceDesc = handshake.resourceDescriptor
            if (!resourceDesc.startsWith(PATH)) {
                LOG.warn("Rejected connection: invalid path $resourceDesc")
                conn.close(4000, "Invalid path")
                return
            }

            // Validate auth token from Authorization header
            val authHeader = handshake.getFieldValue("Authorization")
            if (authHeader.isNullOrBlank()) {
                LOG.warn("Rejected connection: missing Authorization header")
                conn.close(4001, "Missing authorization")
                return
            }

            val token = authHeader.removePrefix("Bearer ").trim()
            if (token != _authToken) {
                LOG.warn("Rejected connection: invalid token")
                conn.close(4002, "Invalid token")
                return
            }

            // Create connection handler
            val bridgeConnection = DevBridgeConnection(conn, this@DevBridgeServer)
            this@DevBridgeServer.connections[conn] = bridgeConnection

            LOG.info("Bridge client connected from ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            val bridgeConnection = this@DevBridgeServer.connections.remove(conn)
            if (bridgeConnection != null) {
                bridgeConnection.onDisconnect()
                this@DevBridgeServer.connectionListeners.forEach { it.onConnectionClosed(bridgeConnection) }
            }
            LOG.info("Bridge client disconnected: $reason (code=$code)")
        }

        override fun onMessage(conn: WebSocket, message: String) {
            // Text messages not used - protocol is binary protobuf
            LOG.debug("Ignoring text message from bridge client")
        }

        override fun onMessage(conn: WebSocket, bytes: ByteBuffer) {
            val bridgeConnection = this@DevBridgeServer.connections[conn] ?: return

            try {
                val data = ByteArray(bytes.remaining())
                bytes.get(data)
                val agentMessage = AgentMessage.parseFrom(data)
                bridgeConnection.handleMessage(agentMessage)
            } catch (e: Exception) {
                LOG.error("Failed to parse AgentMessage", e)
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn != null) {
                LOG.warn("WebSocket error on connection ${conn.remoteSocketAddress}", ex)
            } else {
                LOG.error("WebSocket server error", ex)
            }
        }

        override fun onStart() {
            LOG.info("BridgeWebSocketServer listening on port $port")
        }
    }

    internal fun notifyConnectionEstablished(connection: DevBridgeConnection) {
        connectionListeners.forEach { it.onConnectionEstablished(connection) }
    }
}
