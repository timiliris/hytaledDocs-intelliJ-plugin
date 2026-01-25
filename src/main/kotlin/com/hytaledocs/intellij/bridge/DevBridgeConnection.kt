package com.hytaledocs.intellij.bridge

import com.hytaledocs.intellij.bridge.protocol.HytaleBridgeProto.*
import com.intellij.openapi.diagnostic.Logger
import org.java_websocket.WebSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles a single bridge client connection from a Hytale server process.
 */
class DevBridgeConnection(
    private val socket: WebSocket,
    private val server: DevBridgeServer
) {
    companion object {
        private val LOG = Logger.getInstance(DevBridgeConnection::class.java)
        private const val PROTOCOL_VERSION = 1
        private const val PLUGIN_VERSION = "1.4.0"
    }

    private val handshakeComplete = AtomicBoolean(false)

    private var agentCapabilities: List<String> = emptyList()
    private var agentVersion: String = ""
    private var serverVersion: String = ""

    private val logListeners = CopyOnWriteArrayList<(LogEvent) -> Unit>()
    private val serverStateListeners = CopyOnWriteArrayList<(ServerState) -> Unit>()
    private val commandRegistryListeners = CopyOnWriteArrayList<(CommandRegistryResponse) -> Unit>()
    private val suggestionListeners = CopyOnWriteArrayList<(SuggestionsResponse) -> Unit>()
    private val translateListeners = CopyOnWriteArrayList<(TranslateResponse) -> Unit>()

    fun handleMessage(message: AgentMessage) {
        when (message.payloadCase) {
            AgentMessage.PayloadCase.HELLO -> handleHello(message.hello)
            AgentMessage.PayloadCase.LOG_EVENT -> handleLogEvent(message.logEvent)
            AgentMessage.PayloadCase.SERVER_STATE -> handleServerState(message.serverState)
            AgentMessage.PayloadCase.COMMAND_REGISTRY -> handleCommandRegistry(message.commandRegistry)
            AgentMessage.PayloadCase.SUGGESTIONS -> handleSuggestions(message.suggestions)
            AgentMessage.PayloadCase.ASSET_PATHS -> handleAssetPaths(message.assetPaths)
            AgentMessage.PayloadCase.TRANSLATE_RESPONSE -> handleTranslateResponse(message.translateResponse)
            else -> LOG.warn("Unknown message type: ${message.payloadCase}")
        }
    }

    private fun handleHello(hello: AgentHello) {
        LOG.info("Agent hello: version=${hello.agentVersion}, protocol=${hello.protocolVersion}, capabilities=${hello.capabilitiesList}")

        agentVersion = hello.agentVersion
        agentCapabilities = hello.capabilitiesList.toList()
        serverVersion = hello.serverVersion

        // Respond with IdeHello
        val response = IdeHello.newBuilder()
            .setProtocolVersion(PROTOCOL_VERSION)
            .setPluginVersion(PLUGIN_VERSION)
            .addRequestedCapabilities("logs")
            .addRequestedCapabilities("commands")
            .build()

        val message = IdeMessage.newBuilder()
            .setHello(response)
            .build()

        socket.send(message.toByteArray())
        handshakeComplete.set(true)

        LOG.info("Handshake complete with agent $agentVersion")
        server.notifyConnectionEstablished(this)
    }

    private fun handleLogEvent(event: LogEvent) {
        if (!handshakeComplete.get()) {
            LOG.warn("Received log event before handshake")
            return
        }
        logListeners.forEach { it(event) }
    }

    private fun handleServerState(event: ServerStateEvent) {
        if (!handshakeComplete.get()) return
        serverStateListeners.forEach { it(event.state) }
    }

    private fun handleCommandRegistry(registry: CommandRegistryResponse) {
        if (!handshakeComplete.get()) return
        LOG.info("Received command registry with ${registry.commandsCount} commands")
        commandRegistryListeners.forEach { it(registry) }
    }

    private fun handleSuggestions(suggestions: SuggestionsResponse) {
        if (!handshakeComplete.get()) return
        LOG.debug("Received ${suggestions.suggestionsCount} suggestions")
        suggestionListeners.forEach { it(suggestions) }
    }

    private fun handleAssetPaths(paths: AssetPathsEvent) {
        if (!handshakeComplete.get()) return
        LOG.info("Received ${paths.pathsCount} asset paths from bridge")
        val assetScanner = com.hytaledocs.intellij.services.AssetScannerService.getInstance(server.getProject())
        assetScanner.setBridgeAssetPaths(paths.pathsList)
    }

    private fun handleTranslateResponse(response: TranslateResponse) {
        if (!handshakeComplete.get()) return
        LOG.debug("Received ${response.translationsCount} translations")
        translateListeners.forEach { it(response) }
    }

    /**
     * Request the full command registry from the bridge.
     */
    fun requestCommands() {
        if (!handshakeComplete.get()) return

        val request = GetCommandsRequest.newBuilder().build()
        val message = IdeMessage.newBuilder()
            .setGetCommands(request)
            .build()

        socket.send(message.toByteArray())
    }

    /**
     * Request command suggestions for the given partial command.
     */
    fun requestSuggestions(partialCommand: String, cursorPosition: Int) {
        if (!handshakeComplete.get()) return

        val request = GetSuggestionsRequest.newBuilder()
            .setPartialCommand(partialCommand)
            .setCursorPosition(cursorPosition)
            .build()

        val message = IdeMessage.newBuilder()
            .setGetSuggestions(request)
            .build()

        socket.send(message.toByteArray())
    }

    /**
     * Execute a command on the server.
     */
    fun executeCommand(command: String) {
        if (!handshakeComplete.get()) return

        val request = ExecuteCommandRequest.newBuilder()
            .setCommand(command)
            .build()

        val message = IdeMessage.newBuilder()
            .setExecuteCommand(request)
            .build()

        socket.send(message.toByteArray())
    }

    /**
     * Request translation of keys from the server.
     * @param keys List of translation keys to resolve
     * @param language Optional language code (defaults to "en" on server)
     */
    fun requestTranslation(keys: List<String>, language: String? = null) {
        if (!handshakeComplete.get()) return

        val requestBuilder = TranslateRequest.newBuilder()
            .addAllKeys(keys)

        if (language != null) {
            requestBuilder.language = language
        }

        val message = IdeMessage.newBuilder()
            .setTranslate(requestBuilder.build())
            .build()

        socket.send(message.toByteArray())
    }

    fun addLogListener(listener: (LogEvent) -> Unit) {
        logListeners.add(listener)
    }

    fun removeLogListener(listener: (LogEvent) -> Unit) {
        logListeners.remove(listener)
    }

    fun addServerStateListener(listener: (ServerState) -> Unit) {
        serverStateListeners.add(listener)
    }

    fun removeServerStateListener(listener: (ServerState) -> Unit) {
        serverStateListeners.remove(listener)
    }

    fun addCommandRegistryListener(listener: (CommandRegistryResponse) -> Unit) {
        commandRegistryListeners.add(listener)
    }

    fun removeCommandRegistryListener(listener: (CommandRegistryResponse) -> Unit) {
        commandRegistryListeners.remove(listener)
    }

    fun addSuggestionListener(listener: (SuggestionsResponse) -> Unit) {
        suggestionListeners.add(listener)
    }

    fun removeSuggestionListener(listener: (SuggestionsResponse) -> Unit) {
        suggestionListeners.remove(listener)
    }

    fun addTranslateListener(listener: (TranslateResponse) -> Unit) {
        translateListeners.add(listener)
    }

    fun removeTranslateListener(listener: (TranslateResponse) -> Unit) {
        translateListeners.remove(listener)
    }

    fun isHandshakeComplete(): Boolean = handshakeComplete.get()

    fun getAgentVersion(): String = agentVersion

    fun getServerVersion(): String = serverVersion

    fun getCapabilities(): List<String> = agentCapabilities

    fun close() {
        if (socket.isOpen) {
            socket.close()
        }
    }

    fun onDisconnect() {
        handshakeComplete.set(false)
    }
}
