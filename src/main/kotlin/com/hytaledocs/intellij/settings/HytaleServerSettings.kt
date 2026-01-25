package com.hytaledocs.intellij.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Authentication mode for the Hytale server.
 */
enum class AuthMode(val value: String, val displayName: String) {
    AUTHENTICATED("authenticated", "Authenticated (Hytale account required)"),
    OFFLINE("offline", "Offline (no account required)")
}

/**
 * Server configuration state that gets persisted.
 */
@State(
    name = "HytaleServerSettings",
    storages = [Storage("hytaleServer.xml")]
)
class HytaleServerSettings : PersistentStateComponent<HytaleServerSettings.State> {

    data class State(
        // Server paths
        var serverPath: String = "server",
        var javaPath: String = "",

        // Memory settings
        var minMemory: String = "2G",
        var maxMemory: String = "8G",

        // Server settings
        var port: Int = 5520,
        var authMode: String = AuthMode.AUTHENTICATED.value,
        var allowOp: Boolean = true,
        var acceptEarlyPlugins: Boolean = true,

        // Additional JVM arguments
        var jvmArgs: String = "",

        // Additional server arguments
        var serverArgs: String = "",

        // Console settings
        var maxLogLines: Int = 5000,
        var autoScroll: Boolean = true,
        var showTimestamps: Boolean = true,

        // Asset sync settings
        var serverAssetPath: String = "assets",      // Relative path to server assets folder
        var syncExcludePatterns: String = "",        // Glob patterns to exclude (comma-separated)
        var lastSyncTimestamp: Long = 0,             // Timestamp of last sync

        // Feature toggles
        var uiFileSupportEnabled: Boolean = true     // Enable/disable .ui file support (syntax highlighting, completion, etc.)
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    // Convenience accessors
    var serverPath: String
        get() = myState.serverPath
        set(value) { myState.serverPath = value }

    var javaPath: String
        get() = myState.javaPath
        set(value) { myState.javaPath = value }

    var minMemory: String
        get() = myState.minMemory
        set(value) {
            require(isValidMemoryFormat(value)) {
                "Invalid memory format: $value. Must match pattern like '2G', '512M', '4096M'"
            }
            myState.minMemory = value
        }

    var maxMemory: String
        get() = myState.maxMemory
        set(value) {
            require(isValidMemoryFormat(value)) {
                "Invalid memory format: $value. Must match pattern like '2G', '512M', '4096M'"
            }
            myState.maxMemory = value
        }

    var port: Int
        get() = myState.port
        set(value) {
            require(value in 1024..65535) {
                "Port must be between 1024 and 65535, got: $value"
            }
            myState.port = value
        }

    var authMode: AuthMode
        get() = AuthMode.entries.find { it.value == myState.authMode } ?: AuthMode.AUTHENTICATED
        set(value) { myState.authMode = value.value }

    var allowOp: Boolean
        get() = myState.allowOp
        set(value) { myState.allowOp = value }

    var acceptEarlyPlugins: Boolean
        get() = myState.acceptEarlyPlugins
        set(value) { myState.acceptEarlyPlugins = value }

    var jvmArgs: String
        get() = myState.jvmArgs
        set(value) { myState.jvmArgs = value }

    var serverArgs: String
        get() = myState.serverArgs
        set(value) { myState.serverArgs = value }

    var maxLogLines: Int
        get() = myState.maxLogLines
        set(value) {
            require(value > 0) {
                "Max log lines must be greater than 0, got: $value"
            }
            myState.maxLogLines = value
        }

    var autoScroll: Boolean
        get() = myState.autoScroll
        set(value) { myState.autoScroll = value }

    var showTimestamps: Boolean
        get() = myState.showTimestamps
        set(value) { myState.showTimestamps = value }

    var serverAssetPath: String
        get() = myState.serverAssetPath
        set(value) { myState.serverAssetPath = value }

    var syncExcludePatterns: String
        get() = myState.syncExcludePatterns
        set(value) { myState.syncExcludePatterns = value }

    var lastSyncTimestamp: Long
        get() = myState.lastSyncTimestamp
        set(value) { myState.lastSyncTimestamp = value }

    var uiFileSupportEnabled: Boolean
        get() = myState.uiFileSupportEnabled
        set(value) { myState.uiFileSupportEnabled = value }

    /**
     * Validates memory format (e.g., "2G", "512M", "4096M").
     * @param value The memory string to validate
     * @return true if valid, false otherwise
     */
    private fun isValidMemoryFormat(value: String): Boolean {
        return value.matches(Regex("^\\d+[MG]$"))
    }

    companion object {
        fun getInstance(project: Project): HytaleServerSettings {
            return project.getService(HytaleServerSettings::class.java)
        }
    }
}
