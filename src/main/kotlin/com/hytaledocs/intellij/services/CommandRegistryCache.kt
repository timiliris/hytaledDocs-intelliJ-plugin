package com.hytaledocs.intellij.services

import com.hytaledocs.intellij.bridge.DevBridgeConnection
import com.hytaledocs.intellij.bridge.DevBridgeServer
import com.hytaledocs.intellij.bridge.protocol.HytaleBridgeProto.CommandInfo
import com.hytaledocs.intellij.bridge.protocol.HytaleBridgeProto.CommandRegistryResponse
import com.hytaledocs.intellij.bridge.protocol.HytaleBridgeProto.SuggestionsResponse
import com.hytaledocs.intellij.bridge.protocol.HytaleBridgeProto.TranslateResponse
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.schedule

/**
 * Caches command registry from the dev bridge and provides command completion.
 * Manages debounced suggestion requests for efficient autocomplete.
 */
@Service(Service.Level.PROJECT)
class CommandRegistryCache(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(CommandRegistryCache::class.java)
        private const val DEBOUNCE_MS = 50L

        fun getInstance(project: Project): CommandRegistryCache {
            return project.getService(CommandRegistryCache::class.java)
        }
    }

    /**
     * Represents a command suggestion for the autocomplete popup.
     */
    data class CommandSuggestion(
        val text: String,
        val displayText: String,
        val description: String,
        val isCommand: Boolean,
        val aliases: List<String> = emptyList(),
        val argumentType: String? = null
    )

    // Cached command registry from bridge
    private val commandRegistry = AtomicReference<CommandRegistryResponse?>(null)

    // Pending suggestion callbacks
    private val pendingSuggestionCallbacks = ConcurrentHashMap<String, (List<String>, Int) -> Unit>()

    // Debounce timer
    private var suggestionDebounceTimer: Timer? = null
    private val timerLock = Object()

    // Registry update listeners
    private val registryListeners = CopyOnWriteArrayList<(CommandRegistryResponse) -> Unit>()

    // Translated description cache (translation key -> translated text)
    private val translatedDescriptions = ConcurrentHashMap<String, String>()

    // Active connection
    private var activeConnection: DevBridgeConnection? = null

    // Connection listener
    private val connectionListener = object : DevBridgeServer.ConnectionListener {
        override fun onConnectionEstablished(connection: DevBridgeConnection) {
            LOG.info("Bridge connected, requesting command registry")
            activeConnection = connection
            connection.addCommandRegistryListener(commandRegistryListener)
            connection.addSuggestionListener(suggestionListener)
            connection.addTranslateListener(translateListener)
            // Request command registry
            connection.requestCommands()
        }

        override fun onConnectionClosed(connection: DevBridgeConnection) {
            LOG.info("Bridge disconnected, clearing command registry")
            if (activeConnection == connection) {
                activeConnection = null
                commandRegistry.set(null)
                translatedDescriptions.clear()
                pendingSuggestionCallbacks.clear()
            }
        }
    }

    // Command registry listener
    private val commandRegistryListener: (CommandRegistryResponse) -> Unit = { registry ->
        LOG.info("Received command registry with ${registry.commandsCount} commands")
        commandRegistry.set(registry)
        registryListeners.forEach { listener ->
            try {
                listener(registry)
            } catch (e: Exception) {
                LOG.warn("Error in registry listener", e)
            }
        }
        // Request translations for command descriptions
        requestDescriptionTranslations(registry)
    }

    // Suggestion response listener
    private val suggestionListener: (SuggestionsResponse) -> Unit = { response ->
        // Find and invoke the pending callback
        val iterator = pendingSuggestionCallbacks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            try {
                entry.value(response.suggestionsList, response.startPosition)
            } catch (e: Exception) {
                LOG.warn("Error in suggestion callback", e)
            }
            iterator.remove()
            break // Only call the first pending callback
        }
    }

    // Translate response listener
    private val translateListener: (TranslateResponse) -> Unit = { response ->
        LOG.info("Received ${response.translationsCount} translations")
        translatedDescriptions.putAll(response.translationsMap)
        // Notify registry listeners so UI can update with translated descriptions
        commandRegistry.get()?.let { registry ->
            registryListeners.forEach { listener ->
                try {
                    listener(registry)
                } catch (e: Exception) {
                    LOG.warn("Error in registry listener after translation", e)
                }
            }
        }
    }

    init {
        // Register with DevBridgeServer for connection events
        val bridgeServer = DevBridgeServer.getInstance(project)
        bridgeServer.addConnectionListener(connectionListener)

        // If already connected, request commands
        bridgeServer.getActiveConnection()?.let { connection ->
            activeConnection = connection
            connection.addCommandRegistryListener(commandRegistryListener)
            connection.addSuggestionListener(suggestionListener)
            connection.addTranslateListener(translateListener)
            connection.requestCommands()
        }
    }

    /**
     * Request translations for all description keys in the registry.
     */
    private fun requestDescriptionTranslations(registry: CommandRegistryResponse) {
        val keys = mutableSetOf<String>()
        collectDescriptionKeys(registry.commandsList, keys)

        if (keys.isEmpty()) return

        // Filter out keys we already have
        val newKeys = keys.filter { !translatedDescriptions.containsKey(it) }
        if (newKeys.isEmpty()) return

        LOG.info("Requesting ${newKeys.size} translations")
        activeConnection?.requestTranslation(newKeys)
    }

    /**
     * Recursively collect all description keys from commands and subcommands.
     */
    private fun collectDescriptionKeys(commands: List<CommandInfo>, keys: MutableSet<String>) {
        for (cmd in commands) {
            if (cmd.description.isNotEmpty()) {
                keys.add(cmd.description)
            }
            collectDescriptionKeys(cmd.subCommandsList, keys)
        }
    }

    /**
     * Get translated text for a description key.
     * Returns the translated text if available, otherwise the original key.
     */
    fun getTranslatedDescription(key: String): String {
        return translatedDescriptions[key] ?: key
    }

    /**
     * Get cached command registry.
     */
    fun getCommandRegistry(): CommandRegistryResponse? = commandRegistry.get()

    /**
     * Check if command registry is available.
     */
    fun hasCommandRegistry(): Boolean = commandRegistry.get() != null

    /**
     * Get instant completions from cached registry.
     * Used for fast local completions without bridge round-trip.
     */
    fun getLocalCompletions(partialCommand: String): List<CommandSuggestion> {
        val registry = commandRegistry.get() ?: return emptyList()
        // Input should already have / stripped, but handle it gracefully
        val prefix = partialCommand.removePrefix("/").lowercase()

        if (prefix.isEmpty()) {
            // Return all top-level commands (without / prefix)
            return registry.commandsList.map { cmd ->
                CommandSuggestion(
                    text = cmd.name,
                    displayText = cmd.name,
                    description = getTranslatedDescription(cmd.description),
                    isCommand = true,
                    aliases = cmd.aliasesList.toList()
                )
            }.sortedBy { it.text }
        }

        val parts = prefix.split(" ")

        // Navigate to the appropriate level in the command tree
        var currentCommands = registry.commandsList
        var consumedParts = 0

        for (i in parts.indices) {
            if (i == parts.lastIndex) break // Don't consume the part we're completing

            val part = parts[i]
            val matchedCommand = currentCommands.find { cmd ->
                cmd.name == part || part in cmd.aliasesList
            }

            if (matchedCommand != null) {
                currentCommands = matchedCommand.subCommandsList
                consumedParts = i + 1
            } else {
                break
            }
        }

        // Get the partial text we're completing
        val completingPart = if (consumedParts < parts.size) parts[consumedParts].lowercase() else ""

        // Find matching commands at this level
        val matchingCommands = currentCommands.filter { cmd ->
            cmd.name.startsWith(completingPart) ||
                    cmd.aliasesList.any { it.startsWith(completingPart) }
        }

        // Build the prefix for suggestions (path of parent commands, no leading /)
        val prefixPath = if (consumedParts > 0) {
            parts.take(consumedParts).joinToString(" ") + " "
        } else {
            ""
        }

        return matchingCommands.map { cmd ->
            CommandSuggestion(
                text = "$prefixPath${cmd.name}",
                displayText = cmd.name,
                description = getTranslatedDescription(cmd.description),
                isCommand = true,
                aliases = cmd.aliasesList.toList()
            )
        }.sortedBy { it.text }
    }

    /**
     * Request dynamic suggestions from bridge (debounced).
     */
    fun requestSuggestions(
        partialCommand: String,
        cursorPosition: Int,
        callback: (List<String>, Int) -> Unit
    ) {
        val connection = activeConnection ?: run {
            callback(emptyList(), 0)
            return
        }

        // Strip leading slash - it's a UI convention, not part of the command
        val cleanCommand = partialCommand.removePrefix("/")
        val adjustedCursorPos = if (partialCommand.startsWith("/")) {
            (cursorPosition - 1).coerceAtLeast(0)
        } else {
            cursorPosition
        }

        synchronized(timerLock) {
            // Cancel previous timer
            suggestionDebounceTimer?.cancel()

            // Create new debounced request
            suggestionDebounceTimer = Timer().apply {
                schedule(DEBOUNCE_MS) {
                    val requestKey = "$cleanCommand:$adjustedCursorPos:${System.currentTimeMillis()}"
                    pendingSuggestionCallbacks[requestKey] = callback
                    connection.requestSuggestions(cleanCommand, adjustedCursorPos)
                }
            }
        }
    }

    /**
     * Find a command by path (e.g., "give" or "player stats get").
     */
    fun findCommand(commandPath: String): CommandInfo? {
        val registry = commandRegistry.get() ?: return null
        val parts = commandPath.removePrefix("/").split(" ")

        var current: CommandInfo? = registry.commandsList.find {
            it.name == parts[0] || parts[0] in it.aliasesList
        }

        for (i in 1 until parts.size) {
            current = current?.subCommandsList?.find {
                it.name == parts[i] || parts[i] in it.aliasesList
            } ?: return null
        }

        return current
    }

    /**
     * Register a callback to receive registry updates.
     */
    fun addRegistryListener(listener: (CommandRegistryResponse) -> Unit) {
        registryListeners.add(listener)
        // Immediately notify with current registry if available
        commandRegistry.get()?.let { listener(it) }
    }

    /**
     * Unregister a registry listener.
     */
    fun removeRegistryListener(listener: (CommandRegistryResponse) -> Unit) {
        registryListeners.remove(listener)
    }

    override fun dispose() {
        synchronized(timerLock) {
            suggestionDebounceTimer?.cancel()
            suggestionDebounceTimer = null
        }

        try {
            val bridgeServer = DevBridgeServer.getInstance(project)
            bridgeServer.removeConnectionListener(connectionListener)
        } catch (e: Exception) {
            LOG.warn("Error unregistering from bridge server", e)
        }

        activeConnection?.removeCommandRegistryListener(commandRegistryListener)
        activeConnection?.removeSuggestionListener(suggestionListener)
        activeConnection?.removeTranslateListener(translateListener)
        activeConnection = null

        pendingSuggestionCallbacks.clear()
        translatedDescriptions.clear()
        registryListeners.clear()
    }
}
