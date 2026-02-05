package com.hytaledocs.intellij.services

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.hytaledocs.intellij.settings.AuthMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A server profile containing all configuration for a Hytale server instance.
 *
 * @property id Unique identifier (UUID) for the profile
 * @property name Display name for the profile
 * @property path Path to the server directory
 * @property javaPath Path to the Java executable (empty for auto-detect)
 * @property port Server port (default: 5520)
 * @property minMemory Minimum heap memory (e.g., "2G")
 * @property maxMemory Maximum heap memory (e.g., "8G")
 * @property authMode Authentication mode ("authenticated" or "offline")
 * @property allowOp Whether to allow operator privileges
 * @property acceptEarlyPlugins Whether to accept early/experimental plugins
 * @property jvmArgs Additional JVM arguments
 * @property serverArgs Additional server arguments
 * @property lastUsed Timestamp of last use (epoch millis)
 * @property createdAt Timestamp of creation (epoch millis)
 */
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Default Server",
    var path: String = "",
    var javaPath: String = "",
    var port: Int = 5520,
    var minMemory: String = "2G",
    var maxMemory: String = "8G",
    var authMode: String = AuthMode.AUTHENTICATED.value,
    var allowOp: Boolean = true,
    var acceptEarlyPlugins: Boolean = true,
    var jvmArgs: String = "",
    var serverArgs: String = "",
    var lastUsed: Long = 0,
    var createdAt: Long = Instant.now().toEpochMilli()
) {
    /**
     * Returns the auth mode as an enum value.
     */
    fun getAuthModeEnum(): AuthMode {
        return AuthMode.entries.find { it.value == authMode } ?: AuthMode.AUTHENTICATED
    }

    /**
     * Sets the auth mode from an enum value.
     */
    fun setAuthModeEnum(mode: AuthMode) {
        authMode = mode.value
    }

    /**
     * Returns a copy of this profile with updated lastUsed timestamp.
     */
    fun withLastUsed(): ServerProfile {
        return copy(lastUsed = Instant.now().toEpochMilli())
    }

    /**
     * Returns a short display path for the profile.
     */
    fun getDisplayPath(): String {
        return if (path.length > 40) {
            "..." + path.takeLast(37)
        } else {
            path
        }
    }

    /**
     * Validates the profile configuration.
     * @return A list of validation errors, empty if valid.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (name.isBlank()) {
            errors.add("Profile name cannot be empty")
        }

        if (path.isBlank()) {
            errors.add("Server path cannot be empty")
        } else if (!File(path).exists()) {
            errors.add("Server path does not exist: $path")
        }

        if (port !in 1024..65535) {
            errors.add("Port must be between 1024 and 65535")
        }

        if (!isValidMemoryFormat(minMemory)) {
            errors.add("Invalid minimum memory format: $minMemory (expected format: 2G, 512M)")
        }

        if (!isValidMemoryFormat(maxMemory)) {
            errors.add("Invalid maximum memory format: $maxMemory (expected format: 2G, 512M)")
        }

        return errors
    }

    private fun isValidMemoryFormat(value: String): Boolean {
        return value.matches(Regex("^\\d+[MG]$"))
    }
}

/**
 * Root JSON structure for storing server profiles.
 *
 * @property version Schema version for migration support
 * @property activeProfileId ID of the currently active profile
 * @property profiles List of all server profiles
 */
data class ServerProfilesState(
    val version: Int = CURRENT_VERSION,
    var activeProfileId: String? = null,
    val profiles: MutableList<ServerProfile> = mutableListOf()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Listener interface for profile change events.
 */
interface ServerProfileListener {
    /** Called when a profile is added. */
    fun onProfileAdded(profile: ServerProfile) {}

    /** Called when a profile is updated. */
    fun onProfileUpdated(profile: ServerProfile) {}

    /** Called when a profile is deleted. */
    fun onProfileDeleted(profileId: String) {}

    /** Called when the active profile changes. */
    fun onActiveProfileChanged(profile: ServerProfile?) {}
}

/**
 * Application-level service for managing server profiles.
 *
 * Stores profiles in ~/.hytale-intellij/servers.json and provides
 * CRUD operations for managing server configurations.
 *
 * Usage:
 * ```kotlin
 * val manager = ServerProfileManager.getInstance()
 *
 * // Create a new profile
 * val profile = ServerProfile(name = "Dev Server", path = "/path/to/server")
 * manager.addProfile(profile)
 *
 * // Set as active
 * manager.setActiveProfile(profile.id)
 *
 * // Get active profile
 * val active = manager.getActiveProfile()
 * ```
 */
@Service(Service.Level.APP)
class ServerProfileManager {

    companion object {
        private val LOG = Logger.getInstance(ServerProfileManager::class.java)
        private val GSON: Gson = GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create()

        private const val CONFIG_DIR_NAME = ".hytale-intellij"
        private const val PROFILES_FILE_NAME = "servers.json"

        fun getInstance(): ServerProfileManager {
            return ApplicationManager.getApplication().getService(ServerProfileManager::class.java)
        }
    }

    private var state: ServerProfilesState = ServerProfilesState()
    private val listeners = CopyOnWriteArrayList<ServerProfileListener>()
    private var initialized = false

    /**
     * Gets the directory where profiles are stored.
     */
    fun getConfigDirectory(): Path {
        val userHome = System.getProperty("user.home")
        return Path.of(userHome, CONFIG_DIR_NAME)
    }

    /**
     * Gets the path to the profiles JSON file.
     */
    fun getProfilesFilePath(): Path {
        return getConfigDirectory().resolve(PROFILES_FILE_NAME)
    }

    // ==================== Profile CRUD Operations ====================

    /**
     * Adds a new server profile.
     *
     * @param profile The profile to add
     * @return true if added successfully, false if a profile with the same ID exists
     */
    fun addProfile(profile: ServerProfile): Boolean {
        ensureLoaded()

        // Check for duplicate ID
        if (state.profiles.any { it.id == profile.id }) {
            LOG.warn("Profile with ID ${profile.id} already exists")
            return false
        }

        state.profiles.add(profile)
        save()

        listeners.forEach { it.onProfileAdded(profile) }
        LOG.info("Added profile: ${profile.name} (${profile.id})")

        return true
    }

    /**
     * Updates an existing server profile.
     *
     * @param profile The profile with updated values
     * @return true if updated successfully, false if profile not found
     */
    fun updateProfile(profile: ServerProfile): Boolean {
        ensureLoaded()

        val index = state.profiles.indexOfFirst { it.id == profile.id }
        if (index < 0) {
            LOG.warn("Profile not found for update: ${profile.id}")
            return false
        }

        state.profiles[index] = profile
        save()

        listeners.forEach { it.onProfileUpdated(profile) }
        LOG.info("Updated profile: ${profile.name} (${profile.id})")

        return true
    }

    /**
     * Deletes a server profile by ID.
     *
     * @param profileId The ID of the profile to delete
     * @return true if deleted successfully, false if profile not found
     */
    fun deleteProfile(profileId: String): Boolean {
        ensureLoaded()

        val removed = state.profiles.removeIf { it.id == profileId }
        if (!removed) {
            LOG.warn("Profile not found for deletion: $profileId")
            return false
        }

        // Clear active profile if it was deleted
        if (state.activeProfileId == profileId) {
            state.activeProfileId = state.profiles.firstOrNull()?.id
            listeners.forEach { it.onActiveProfileChanged(getActiveProfile()) }
        }

        save()

        listeners.forEach { it.onProfileDeleted(profileId) }
        LOG.info("Deleted profile: $profileId")

        return true
    }

    /**
     * Gets a profile by ID.
     *
     * @param profileId The ID of the profile to retrieve
     * @return The profile, or null if not found
     */
    fun getProfile(profileId: String): ServerProfile? {
        ensureLoaded()
        return state.profiles.find { it.id == profileId }
    }

    /**
     * Gets all profiles.
     *
     * @return List of all profiles (defensive copy)
     */
    fun getAllProfiles(): List<ServerProfile> {
        ensureLoaded()
        return state.profiles.toList()
    }

    /**
     * Gets all profiles sorted by last used (most recent first).
     */
    fun getProfilesByLastUsed(): List<ServerProfile> {
        ensureLoaded()
        return state.profiles.sortedByDescending { it.lastUsed }
    }

    /**
     * Gets the number of profiles.
     */
    fun getProfileCount(): Int {
        ensureLoaded()
        return state.profiles.size
    }

    // ==================== Active Profile Management ====================

    /**
     * Sets the active profile by ID.
     *
     * @param profileId The ID of the profile to set as active, or null to clear
     * @return true if set successfully, false if profile not found
     */
    fun setActiveProfile(profileId: String?): Boolean {
        ensureLoaded()

        if (profileId != null && state.profiles.none { it.id == profileId }) {
            LOG.warn("Cannot set active profile - not found: $profileId")
            return false
        }

        state.activeProfileId = profileId
        save()

        val activeProfile = getActiveProfile()
        listeners.forEach { it.onActiveProfileChanged(activeProfile) }
        LOG.info("Set active profile: ${activeProfile?.name ?: "none"}")

        return true
    }

    /**
     * Gets the currently active profile.
     *
     * @return The active profile, or null if none is set
     */
    fun getActiveProfile(): ServerProfile? {
        ensureLoaded()
        return state.activeProfileId?.let { id -> state.profiles.find { it.id == id } }
    }

    /**
     * Gets the active profile ID.
     */
    fun getActiveProfileId(): String? {
        ensureLoaded()
        return state.activeProfileId
    }

    /**
     * Marks a profile as recently used (updates lastUsed timestamp).
     *
     * @param profileId The ID of the profile to mark
     */
    fun markAsUsed(profileId: String) {
        ensureLoaded()

        val profile = state.profiles.find { it.id == profileId } ?: return
        profile.lastUsed = Instant.now().toEpochMilli()
        save()

        listeners.forEach { it.onProfileUpdated(profile) }
    }

    // ==================== Import/Export ====================

    /**
     * Imports a profile from an existing server directory by auto-detecting settings.
     *
     * @param serverPath Path to the server directory
     * @param name Optional name for the profile (defaults to directory name)
     * @return The created profile, or null if import failed
     */
    fun importFromDirectory(serverPath: String, name: String? = null): ServerProfile? {
        val serverDir = File(serverPath)
        if (!serverDir.exists() || !serverDir.isDirectory) {
            LOG.warn("Invalid server directory: $serverPath")
            return null
        }

        // Auto-detect profile name from directory
        val profileName = name ?: serverDir.name.ifBlank { "Imported Server" }

        // Create profile with defaults
        val profile = ServerProfile(
            name = profileName,
            path = serverPath,
            createdAt = Instant.now().toEpochMilli()
        )

        // Try to detect settings from existing files
        detectSettingsFromServer(serverDir, profile)

        // Add the profile
        if (addProfile(profile)) {
            LOG.info("Imported server from: $serverPath")
            return profile
        }

        return null
    }

    /**
     * Attempts to detect server settings from existing configuration files.
     */
    private fun detectSettingsFromServer(serverDir: File, profile: ServerProfile) {
        // Check for server.properties or similar config files
        val propertiesFile = File(serverDir, "server.properties")
        if (propertiesFile.exists()) {
            try {
                val properties = java.util.Properties()
                propertiesFile.inputStream().use { properties.load(it) }

                // Extract port if present
                properties.getProperty("port")?.toIntOrNull()?.let {
                    if (it in 1024..65535) profile.port = it
                }

                // Extract auth mode if present
                properties.getProperty("auth-mode")?.let { mode ->
                    if (mode == "offline" || mode == "authenticated") {
                        profile.authMode = mode
                    }
                }

                LOG.info("Detected settings from server.properties")
            } catch (e: Exception) {
                LOG.debug("Could not parse server.properties: ${e.message}")
            }
        }

        // Check for HytaleServer.jar to confirm it's a valid server directory
        val serverJar = File(serverDir, "HytaleServer.jar")
        if (!serverJar.exists()) {
            // Also check in Server subdirectory
            val altServerJar = File(serverDir, "Server/HytaleServer.jar")
            if (altServerJar.exists()) {
                profile.path = File(serverDir, "Server").absolutePath
            }
        }
    }

    /**
     * Creates a duplicate of an existing profile with a new ID and name.
     *
     * @param profileId The ID of the profile to duplicate
     * @param newName The name for the new profile
     * @return The new profile, or null if the source profile was not found
     */
    fun duplicateProfile(profileId: String, newName: String): ServerProfile? {
        val source = getProfile(profileId) ?: return null

        val duplicate = source.copy(
            id = UUID.randomUUID().toString(),
            name = newName,
            createdAt = Instant.now().toEpochMilli(),
            lastUsed = 0
        )

        return if (addProfile(duplicate)) duplicate else null
    }

    // ==================== Listener Management ====================

    /**
     * Adds a listener for profile change events.
     */
    fun addListener(listener: ServerProfileListener) {
        listeners.add(listener)
    }

    /**
     * Removes a listener.
     */
    fun removeListener(listener: ServerProfileListener) {
        listeners.remove(listener)
    }

    // ==================== Persistence ====================

    /**
     * Ensures profiles are loaded from disk.
     */
    private fun ensureLoaded() {
        if (!initialized) {
            load()
            initialized = true
        }
    }

    /**
     * Loads profiles from the JSON file.
     */
    fun load() {
        val filePath = getProfilesFilePath()

        if (!Files.exists(filePath)) {
            LOG.info("No profiles file found, starting with empty state")
            state = ServerProfilesState()
            return
        }

        try {
            val json = Files.readString(filePath)
            if (json.isBlank()) {
                LOG.warn("Profiles file is empty, starting with empty state")
                state = ServerProfilesState()
                return
            }

            val loaded = GSON.fromJson(json, ServerProfilesState::class.java)
            if (loaded != null) {
                // Handle potential null profiles list from Gson (can happen with malformed JSON)
                @Suppress("SENSELESS_COMPARISON")
                val safeProfiles = if (loaded.profiles == null) mutableListOf() else loaded.profiles
                state = loaded.copy(profiles = safeProfiles)

                LOG.info("Loaded ${state.profiles.size} profiles from $filePath")

                // Migration logic for future versions
                if (loaded.version < ServerProfilesState.CURRENT_VERSION) {
                    migrateState(loaded.version)
                }
            } else {
                LOG.warn("Failed to deserialize profiles, starting with empty state")
                state = ServerProfilesState()
            }
        } catch (e: JsonSyntaxException) {
            LOG.error("Corrupted profiles file, backing up and starting fresh", e)
            backupCorruptedFile(filePath)
            state = ServerProfilesState()
        } catch (e: Exception) {
            LOG.error("Failed to load profiles", e)
            state = ServerProfilesState()
        }
    }

    /**
     * Saves profiles to the JSON file.
     */
    fun save() {
        try {
            val configDir = getConfigDirectory()
            Files.createDirectories(configDir)

            val filePath = getProfilesFilePath()
            val json = GSON.toJson(state)
            Files.writeString(filePath, json)

            LOG.debug("Saved ${state.profiles.size} profiles to $filePath")
        } catch (e: Exception) {
            LOG.error("Failed to save profiles", e)
        }
    }

    /**
     * Reloads profiles from disk, discarding any unsaved changes.
     */
    fun reload() {
        initialized = false
        ensureLoaded()
    }

    /**
     * Backs up a corrupted file before overwriting.
     */
    private fun backupCorruptedFile(filePath: Path) {
        try {
            val backupPath = filePath.resolveSibling("${PROFILES_FILE_NAME}.corrupted.${System.currentTimeMillis()}")
            Files.copy(filePath, backupPath)
            LOG.info("Backed up corrupted file to: $backupPath")
        } catch (e: Exception) {
            LOG.warn("Failed to backup corrupted file", e)
        }
    }

    /**
     * Migrates state from an older version.
     */
    private fun migrateState(fromVersion: Int) {
        LOG.info("Migrating profiles from version $fromVersion to ${ServerProfilesState.CURRENT_VERSION}")

        // Add migration logic here when schema changes
        // Example:
        // if (fromVersion < 2) {
        //     state.profiles.forEach { profile ->
        //         // Apply v2 migration
        //     }
        // }

        state = state.copy(version = ServerProfilesState.CURRENT_VERSION)
        save()
    }

    // ==================== Utility Methods ====================

    /**
     * Finds profiles by name (case-insensitive partial match).
     */
    fun findProfilesByName(query: String): List<ServerProfile> {
        ensureLoaded()
        val queryLower = query.lowercase()
        return state.profiles.filter { it.name.lowercase().contains(queryLower) }
    }

    /**
     * Checks if a profile with the given name exists.
     */
    fun hasProfileWithName(name: String): Boolean {
        ensureLoaded()
        return state.profiles.any { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Generates a unique profile name by appending a number if needed.
     */
    fun generateUniqueName(baseName: String): String {
        ensureLoaded()

        if (!hasProfileWithName(baseName)) {
            return baseName
        }

        var counter = 2
        var candidateName = "$baseName ($counter)"
        while (hasProfileWithName(candidateName)) {
            counter++
            candidateName = "$baseName ($counter)"
        }

        return candidateName
    }

    /**
     * Validates all profiles and returns a map of profile ID to validation errors.
     */
    fun validateAllProfiles(): Map<String, List<String>> {
        ensureLoaded()
        return state.profiles
            .mapNotNull { profile ->
                val errors = profile.validate()
                if (errors.isNotEmpty()) profile.id to errors else null
            }
            .toMap()
    }

    /**
     * Checks if a path is a valid Hytale server directory.
     */
    fun isValidServerDirectory(path: Path): Boolean {
        val serverJar = path.resolve("HytaleServer.jar")
        return Files.exists(serverJar)
    }

    /**
     * Creates a default profile if none exist.
     */
    fun ensureDefaultProfile(): ServerProfile {
        ensureLoaded()

        if (state.profiles.isEmpty()) {
            val defaultProfile = ServerProfile(
                name = "Default Server",
                path = "",
                createdAt = Instant.now().toEpochMilli()
            )
            addProfile(defaultProfile)
            setActiveProfile(defaultProfile.id)
            return defaultProfile
        }

        return state.profiles.first()
    }
}
