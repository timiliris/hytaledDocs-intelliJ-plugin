package com.hytaledocs.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
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
         * Plugin UI language.
         * Supported values: "en" (English), "fr" (French)
         * Default is English regardless of system locale.
         */
        var language: String = "en",

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
        var autoDetectInstallation: Boolean = true,

        // ==================== Maven Repository Settings ====================

        /**
         * Base URL for the Hytale Maven repository.
         * Default: https://maven.hytale.com/release
         */
        var mavenRepositoryUrl: String = "https://maven.hytale.com/release",

        /**
         * Preferred server version to use.
         * Empty string means "use latest version".
         */
        var preferredServerVersion: String = "",

        /**
         * Whether to automatically update to the latest server version.
         */
        var autoUpdateServerVersion: Boolean = true,

        /**
         * Last selected/used server version.
         */
        var lastUsedServerVersion: String = "",

        /**
         * Timestamp of last version check (epoch milliseconds).
         */
        var lastVersionCheckTimestamp: Long = 0,

        /**
         * How often to check for new versions (in hours).
         */
        var versionCheckIntervalHours: Int = 24,

        // ==================== Code Completion Settings ====================

        /**
         * Whether to enable Hytale-specific code completion.
         */
        var enableCodeCompletion: Boolean = true,

        /**
         * Priority of Hytale completions in the completion list.
         * Higher values = higher in the list. Default IntelliJ priority is ~100.
         * - High (200): Hytale completions appear at the top
         * - Normal (100): Hytale completions appear with other suggestions
         * - Low (50): Hytale completions appear at the bottom
         */
        var completionPriority: Int = 100
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    var language: String
        get() = myState.language
        set(value) {
            require(value in SUPPORTED_LANGUAGES) {
                "Language must be one of: ${SUPPORTED_LANGUAGES.keys.joinToString()}, got: $value"
            }
            myState.language = value
        }

    var hytaleInstallationPath: String
        get() = myState.hytaleInstallationPath
        set(value) { myState.hytaleInstallationPath = value }

    var autoDetectInstallation: Boolean
        get() = myState.autoDetectInstallation
        set(value) { myState.autoDetectInstallation = value }

    var mavenRepositoryUrl: String
        get() = myState.mavenRepositoryUrl
        set(value) {
            require(isValidUrl(value)) {
                "Maven repository URL must be a valid URL starting with http:// or https://, got: $value"
            }
            myState.mavenRepositoryUrl = value
        }

    var preferredServerVersion: String
        get() = myState.preferredServerVersion
        set(value) { myState.preferredServerVersion = value }

    var autoUpdateServerVersion: Boolean
        get() = myState.autoUpdateServerVersion
        set(value) { myState.autoUpdateServerVersion = value }

    var lastUsedServerVersion: String
        get() = myState.lastUsedServerVersion
        set(value) { myState.lastUsedServerVersion = value }

    var lastVersionCheckTimestamp: Long
        get() = myState.lastVersionCheckTimestamp
        set(value) { myState.lastVersionCheckTimestamp = value }

    var versionCheckIntervalHours: Int
        get() = myState.versionCheckIntervalHours
        set(value) {
            require(value > 0) {
                "Version check interval must be greater than 0 hours, got: $value"
            }
            myState.versionCheckIntervalHours = value
        }

    var enableCodeCompletion: Boolean
        get() = myState.enableCodeCompletion
        set(value) { myState.enableCodeCompletion = value }

    var completionPriority: Int
        get() = myState.completionPriority
        set(value) {
            require(value in 0..1000) {
                "Completion priority must be between 0 and 1000, got: $value"
            }
            myState.completionPriority = value
        }

    /**
     * Check if a custom installation path is configured
     */
    fun hasCustomInstallationPath(): Boolean {
        return hytaleInstallationPath.isNotBlank()
    }

    /**
     * Check if a specific server version is preferred
     */
    fun hasPreferredVersion(): Boolean {
        return preferredServerVersion.isNotBlank()
    }

    /**
     * Get the version to use (preferred or latest)
     */
    fun getEffectiveVersion(): String? {
        return if (hasPreferredVersion()) preferredServerVersion else null
    }

    /**
     * Validates URL format (must start with http:// or https://).
     * @param value The URL string to validate
     * @return true if valid, false otherwise
     */
    private fun isValidUrl(value: String): Boolean {
        return value.startsWith("http://") || value.startsWith("https://")
    }

    companion object {
        /**
         * Supported languages with their display names.
         */
        val SUPPORTED_LANGUAGES = mapOf(
            "en" to "English",
            "fr" to "Français",
            "de" to "Deutsch",
            "es" to "Español",
            "it" to "Italiano",
            "pt_BR" to "Português (Brasil)",
            "pl" to "Polski",
            "ru" to "Русский"
        )

        fun getInstance(): HytaleAppSettings {
            return ApplicationManager.getApplication().getService(HytaleAppSettings::class.java)
        }
    }
}
