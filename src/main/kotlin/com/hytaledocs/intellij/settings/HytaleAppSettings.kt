package com.hytaledocs.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level settings for Hytale plugin.
 * These settings are global and available before project creation.
 *
 * Accessible via Settings > Tools > Hytale
 */
@State(
    name = "HytaleAppSettings",
    storages = [Storage("hytaleApp.xml")]
)
class HytaleAppSettings : PersistentStateComponent<HytaleAppSettings.State> {

    data class State(
        /**
         * Path to the Hytale game installation directory.
         * This is the folder containing the Server/ directory with HytaleServer.jar
         *
         * Example paths:
         * - Windows: C:\Users\Username\AppData\Roaming\Hytale\install\release\package\game\latest
         * - macOS: ~/Library/Application Support/Hytale/install/release/package/game/latest
         * - Linux: ~/.local/share/Hytale/install/release/package/game/latest
         */
        var hytaleInstallationPath: String = "",

        /**
         * Whether to auto-detect the installation path on startup
         */
        var autoDetectInstallation: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    var hytaleInstallationPath: String
        get() = myState.hytaleInstallationPath
        set(value) { myState.hytaleInstallationPath = value }

    var autoDetectInstallation: Boolean
        get() = myState.autoDetectInstallation
        set(value) { myState.autoDetectInstallation = value }

    /**
     * Check if a custom installation path is configured
     */
    fun hasCustomInstallationPath(): Boolean {
        return hytaleInstallationPath.isNotBlank()
    }

    companion object {
        fun getInstance(): HytaleAppSettings {
            return ApplicationManager.getApplication().getService(HytaleAppSettings::class.java)
        }
    }
}
