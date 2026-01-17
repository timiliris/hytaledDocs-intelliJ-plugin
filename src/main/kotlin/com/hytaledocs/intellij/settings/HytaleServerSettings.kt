package com.hytaledocs.intellij.settings

import com.intellij.openapi.components.*
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

        // Backup settings
        var backupEnabled: Boolean = false,
        var backupFrequency: Int = 30,

        // Additional JVM arguments
        var jvmArgs: String = "",

        // Additional server arguments
        var serverArgs: String = "",

        // Console settings
        var maxLogLines: Int = 5000,
        var autoScroll: Boolean = true,
        var showTimestamps: Boolean = true
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
        set(value) { myState.minMemory = value }

    var maxMemory: String
        get() = myState.maxMemory
        set(value) { myState.maxMemory = value }

    var port: Int
        get() = myState.port
        set(value) { myState.port = value }

    var authMode: AuthMode
        get() = AuthMode.entries.find { it.value == myState.authMode } ?: AuthMode.AUTHENTICATED
        set(value) { myState.authMode = value.value }

    var allowOp: Boolean
        get() = myState.allowOp
        set(value) { myState.allowOp = value }

    var acceptEarlyPlugins: Boolean
        get() = myState.acceptEarlyPlugins
        set(value) { myState.acceptEarlyPlugins = value }

    var backupEnabled: Boolean
        get() = myState.backupEnabled
        set(value) { myState.backupEnabled = value }

    var backupFrequency: Int
        get() = myState.backupFrequency
        set(value) { myState.backupFrequency = value }

    var jvmArgs: String
        get() = myState.jvmArgs
        set(value) { myState.jvmArgs = value }

    var serverArgs: String
        get() = myState.serverArgs
        set(value) { myState.serverArgs = value }

    var maxLogLines: Int
        get() = myState.maxLogLines
        set(value) { myState.maxLogLines = value }

    var autoScroll: Boolean
        get() = myState.autoScroll
        set(value) { myState.autoScroll = value }

    var showTimestamps: Boolean
        get() = myState.showTimestamps
        set(value) { myState.showTimestamps = value }

    companion object {
        fun getInstance(project: Project): HytaleServerSettings {
            return project.getService(HytaleServerSettings::class.java)
        }
    }
}
