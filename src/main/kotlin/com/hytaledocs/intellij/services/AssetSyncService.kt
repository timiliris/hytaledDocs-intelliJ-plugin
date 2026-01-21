package com.hytaledocs.intellij.services

import com.hytaledocs.intellij.assets.AssetType
import com.hytaledocs.intellij.settings.HytaleServerSettings
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

/**
 * Service for synchronizing assets between the project and server directories.
 * Supports bidirectional sync with conflict detection.
 */
@Service(Service.Level.PROJECT)
class AssetSyncService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(AssetSyncService::class.java)

        fun getInstance(project: Project): AssetSyncService {
            return project.getService(AssetSyncService::class.java)
        }
    }

    private val settings = HytaleServerSettings.getInstance(project)

    @Volatile
    private var isScanning = false

    /**
     * Modification counter for cache invalidation.
     * Incrementing this value invalidates the cached assets.
     */
    @Volatile
    private var cacheModificationCount = 0L

    /**
     * Simple modification tracker that tracks the cache modification count.
     */
    private val cacheModificationTracker = object : ModificationTracker {
        override fun getModificationCount(): Long = cacheModificationCount
    }

    /**
     * Cached syncable assets using IntelliJ's CachedValue for efficient caching.
     * The cache is invalidated when clearCache() is called or after sync operations.
     */
    private val cachedAssetsValue: CachedValue<List<SyncableAsset>> = CachedValuesManager.getManager(project).createCachedValue {
        val assets = performSynchronousScan()
        CachedValueProvider.Result.create(assets, cacheModificationTracker)
    }

    /**
     * Represents an asset that can be synchronized between project and server.
     */
    data class SyncableAsset(
        val relativePath: String,
        val serverFile: File?,
        val projectFile: File?,
        val serverHash: String?,
        val projectHash: String?,
        val serverLastModified: Long,
        val projectLastModified: Long,
        val syncStatus: SyncStatus,
        val assetType: AssetType,
        val fileSize: Long
    ) {
        val displayName: String
            get() = relativePath.substringAfterLast('/')

        val fileSizeFormatted: String
            get() = formatFileSize(fileSize)

        companion object {
            fun formatFileSize(bytes: Long): String {
                return when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                }
            }
        }
    }

    /**
     * Status of an asset during synchronization.
     */
    enum class SyncStatus(val displayName: String, val description: String) {
        NEW_ON_SERVER("New (Server)", "File exists only on the server"),
        NEW_IN_PROJECT("New (Project)", "File exists only in the project"),
        MODIFIED_ON_SERVER("Modified (Server)", "Server version is newer"),
        MODIFIED_IN_PROJECT("Modified (Project)", "Project version is newer"),
        CONFLICT("Conflict", "Both versions modified - manual resolution required"),
        IN_SYNC("In Sync", "Files are identical")
    }

    /**
     * Direction of synchronization.
     */
    enum class SyncDirection {
        PULL,  // Server -> Project
        PUSH   // Project -> Server
    }

    /**
     * Result of a sync operation.
     */
    data class SyncResult(
        val success: Boolean,
        val syncedCount: Int,
        val skippedCount: Int,
        val errorCount: Int,
        val errors: List<String>
    )

    /**
     * Check if a scan is currently in progress.
     */
    fun isScanning(): Boolean = isScanning

    /**
     * Get the cached list of syncable assets.
     * Uses CachedValue for automatic cache management.
     */
    fun getCachedAssets(): List<SyncableAsset> = cachedAssetsValue.value

    /**
     * Get the project's assets directory.
     */
    fun getProjectAssetsDir(): File? {
        val basePath = project.basePath ?: return null
        val paths = listOf(
            "src/main/resources",
            "resources",
            "assets"
        )
        for (path in paths) {
            val dir = File(basePath, path)
            if (dir.exists() && dir.isDirectory) {
                return dir
            }
        }
        return null
    }

    /**
     * Get the server's assets directory.
     */
    fun getServerAssetsDir(): File? {
        val basePath = project.basePath ?: return null
        val serverPath = settings.serverPath
        val assetPath = settings.serverAssetPath

        val serverAssetsDir = File(basePath, "$serverPath/$assetPath")
        if (serverAssetsDir.exists() && serverAssetsDir.isDirectory) {
            return serverAssetsDir
        }

        // Also check common alternative paths
        val alternatives = listOf(
            File(basePath, "server/assets"),
            File(basePath, "run/assets"),
            File(basePath, "$serverPath/Assets")
        )

        return alternatives.find { it.exists() && it.isDirectory }
    }

    /**
     * Scan for differences between project and server assets.
     * Uses CachedValue for efficient caching with progress reporting for async operations.
     */
    fun scanForChanges(
        onProgress: ((Int, String) -> Unit)? = null
    ): CompletableFuture<List<SyncableAsset>> {
        if (isScanning) {
            return CompletableFuture.completedFuture(cachedAssetsValue.value)
        }

        // If we have cached results and no force refresh, return cached
        val cached = cachedAssetsValue.value
        if (cached.isNotEmpty()) {
            val changedCount = cached.count { it.syncStatus != SyncStatus.IN_SYNC }
            onProgress?.invoke(100, "Found $changedCount changes in ${cached.size} files (cached)")
            return CompletableFuture.completedFuture(cached)
        }

        isScanning = true

        return CompletableFuture.supplyAsync {
            try {
                val assets = scanAssetsWithProgress(onProgress)
                // Invalidate cache to store new results
                cacheModificationCount++
                assets
            } catch (e: Exception) {
                LOG.error("Error scanning assets for sync", e)
                onProgress?.invoke(100, "Error: ${e.message}")
                emptyList()
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * Perform a synchronous scan without progress reporting.
     * Called by the CachedValue provider.
     */
    @RequiresReadLock
    private fun performSynchronousScan(): List<SyncableAsset> {
        return scanAssetsWithProgress(null)
    }

    /**
     * Internal scan implementation with optional progress reporting.
     */
    private fun scanAssetsWithProgress(onProgress: ((Int, String) -> Unit)?): List<SyncableAsset> {
        val projectDir = getProjectAssetsDir()
        val serverDir = getServerAssetsDir()

        if (projectDir == null) {
            LOG.info("No project assets directory found")
            onProgress?.invoke(100, "No project assets directory found")
            return emptyList()
        }

        if (serverDir == null) {
            LOG.info("No server assets directory found")
            onProgress?.invoke(100, "No server assets directory found")
            return emptyList()
        }

        onProgress?.invoke(10, "Scanning project assets...")

        // Collect all files from both directories
        val projectFiles = collectFiles(projectDir, projectDir)
        onProgress?.invoke(30, "Scanning server assets...")

        val serverFiles = collectFiles(serverDir, serverDir)
        onProgress?.invoke(50, "Comparing files...")

        // Build list of all unique relative paths
        val allPaths = (projectFiles.keys + serverFiles.keys).toSet()

        // Get exclude patterns
        val excludePatterns = parseExcludePatterns(settings.syncExcludePatterns)

        // Build syncable assets list
        val assets = mutableListOf<SyncableAsset>()
        var processed = 0

        for (relativePath in allPaths) {
            // Check exclude patterns
            if (matchesExcludePattern(relativePath, excludePatterns)) {
                continue
            }

            val projectFile = projectFiles[relativePath]
            val serverFile = serverFiles[relativePath]

            val projectHash = projectFile?.let { calculateHash(it) }
            val serverHash = serverFile?.let { calculateHash(it) }

            val projectLastModified = projectFile?.lastModified() ?: 0L
            val serverLastModified = serverFile?.lastModified() ?: 0L

            val syncStatus = determineSyncStatus(
                projectFile, serverFile,
                projectHash, serverHash,
                projectLastModified, serverLastModified
            )

            val assetType = AssetType.fromFileName(relativePath)
            val fileSize = (projectFile?.length() ?: 0L).coerceAtLeast(serverFile?.length() ?: 0L)

            assets.add(
                SyncableAsset(
                    relativePath = relativePath,
                    serverFile = serverFile,
                    projectFile = projectFile,
                    serverHash = serverHash,
                    projectHash = projectHash,
                    serverLastModified = serverLastModified,
                    projectLastModified = projectLastModified,
                    syncStatus = syncStatus,
                    assetType = assetType,
                    fileSize = fileSize
                )
            )

            processed++
            if (processed % 50 == 0) {
                onProgress?.invoke(50 + (processed * 40 / allPaths.size), "Processing $processed files...")
            }
        }

        // Sort by path
        assets.sortBy { it.relativePath }

        val changedCount = assets.count { it.syncStatus != SyncStatus.IN_SYNC }
        onProgress?.invoke(100, "Found $changedCount changes in ${assets.size} files")

        LOG.info("Asset sync scan complete: ${assets.size} files, $changedCount changes")
        return assets
    }

    /**
     * Synchronize selected assets in the specified direction.
     */
    fun syncAssets(
        assets: List<SyncableAsset>,
        direction: SyncDirection,
        onProgress: ((Int, String) -> Unit)? = null
    ): CompletableFuture<SyncResult> {
        return CompletableFuture.supplyAsync {
            val errors = mutableListOf<String>()
            var syncedCount = 0
            var skippedCount = 0

            val projectDir = getProjectAssetsDir()
            val serverDir = getServerAssetsDir()

            if (projectDir == null || serverDir == null) {
                return@supplyAsync SyncResult(false, 0, 0, 1, listOf("Directories not found"))
            }

            for ((index, asset) in assets.withIndex()) {
                try {
                    onProgress?.invoke(
                        (index * 100) / assets.size,
                        "Syncing ${asset.displayName}..."
                    )

                    when (direction) {
                        SyncDirection.PULL -> {
                            // Server -> Project
                            if (asset.serverFile != null) {
                                val destFile = File(projectDir, asset.relativePath)
                                copyFile(asset.serverFile, destFile)
                                syncedCount++
                            } else {
                                skippedCount++
                            }
                        }
                        SyncDirection.PUSH -> {
                            // Project -> Server
                            if (asset.projectFile != null) {
                                val destFile = File(serverDir, asset.relativePath)
                                copyFile(asset.projectFile, destFile)
                                syncedCount++
                            } else {
                                skippedCount++
                            }
                        }
                    }
                } catch (e: Exception) {
                    errors.add("${asset.relativePath}: ${e.message}")
                    LOG.warn("Failed to sync ${asset.relativePath}", e)
                }
            }

            // Update last sync timestamp
            settings.lastSyncTimestamp = System.currentTimeMillis()

            // Invalidate cache after sync operation
            cacheModificationCount++

            // Refresh VFS
            LocalFileSystem.getInstance().refresh(true)

            onProgress?.invoke(100, "Synced $syncedCount files")

            SyncResult(
                success = errors.isEmpty(),
                syncedCount = syncedCount,
                skippedCount = skippedCount,
                errorCount = errors.size,
                errors = errors
            )
        }
    }

    /**
     * Collect all files from a directory into a map of relative path -> File.
     */
    private fun collectFiles(dir: File, baseDir: File): Map<String, File> {
        val files = mutableMapOf<String, File>()

        fun collectRecursive(currentDir: File) {
            val children = currentDir.listFiles() ?: return
            for (file in children) {
                if (file.isDirectory) {
                    if (!file.name.startsWith(".")) {
                        collectRecursive(file)
                    }
                } else {
                    // Only include recognized asset types
                    val assetType = AssetType.fromFileName(file.name)
                    if (assetType != AssetType.OTHER) {
                        val relativePath = file.absolutePath
                            .removePrefix(baseDir.absolutePath)
                            .removePrefix(File.separator)
                            .replace(File.separator, "/")
                        files[relativePath] = file
                    }
                }
            }
        }

        collectRecursive(dir)
        return files
    }

    /**
     * Calculate SHA-256 hash of a file.
     */
    fun calculateHash(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            LOG.warn("Failed to calculate hash for ${file.absolutePath}", e)
            ""
        }
    }

    /**
     * Determine the sync status based on file existence and hashes.
     */
    private fun determineSyncStatus(
        projectFile: File?,
        serverFile: File?,
        projectHash: String?,
        serverHash: String?,
        projectLastModified: Long,
        serverLastModified: Long
    ): SyncStatus {
        return when {
            projectFile == null && serverFile != null -> SyncStatus.NEW_ON_SERVER
            projectFile != null && serverFile == null -> SyncStatus.NEW_IN_PROJECT
            projectHash == serverHash -> SyncStatus.IN_SYNC
            else -> {
                // Both files exist but are different
                val lastSync = settings.lastSyncTimestamp
                val projectModifiedAfterSync = projectLastModified > lastSync
                val serverModifiedAfterSync = serverLastModified > lastSync

                when {
                    projectModifiedAfterSync && serverModifiedAfterSync -> SyncStatus.CONFLICT
                    serverModifiedAfterSync -> SyncStatus.MODIFIED_ON_SERVER
                    projectModifiedAfterSync -> SyncStatus.MODIFIED_IN_PROJECT
                    serverLastModified > projectLastModified -> SyncStatus.MODIFIED_ON_SERVER
                    else -> SyncStatus.MODIFIED_IN_PROJECT
                }
            }
        }
    }

    /**
     * Copy a file to a destination, creating parent directories if needed.
     */
    private fun copyFile(source: File, dest: File) {
        WriteAction.run<Throwable> {
            dest.parentFile?.mkdirs()
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Parse exclude patterns from comma-separated string.
     */
    private fun parseExcludePatterns(patterns: String): List<Regex> {
        return patterns.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { pattern ->
                try {
                    // Convert glob pattern to regex
                    val regexPattern = pattern
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", ".")
                    Regex(regexPattern, RegexOption.IGNORE_CASE)
                } catch (e: Exception) {
                    LOG.warn("Invalid exclude pattern: $pattern")
                    null
                }
            }
    }

    /**
     * Check if a path matches any exclude pattern.
     */
    private fun matchesExcludePattern(path: String, patterns: List<Regex>): Boolean {
        return patterns.any { it.containsMatchIn(path) }
    }

    /**
     * Clear the cached scan results by incrementing the modification counter.
     * This invalidates the CachedValue and forces a fresh scan on next access.
     */
    fun clearCache() {
        cacheModificationCount++
    }

    /**
     * Get statistics about the current sync state.
     * Uses the cached assets from CachedValue.
     */
    fun getSyncStats(): SyncStats {
        val assets = cachedAssetsValue.value
        return SyncStats(
            totalFiles = assets.size,
            newOnServer = assets.count { it.syncStatus == SyncStatus.NEW_ON_SERVER },
            newInProject = assets.count { it.syncStatus == SyncStatus.NEW_IN_PROJECT },
            modifiedOnServer = assets.count { it.syncStatus == SyncStatus.MODIFIED_ON_SERVER },
            modifiedInProject = assets.count { it.syncStatus == SyncStatus.MODIFIED_IN_PROJECT },
            conflicts = assets.count { it.syncStatus == SyncStatus.CONFLICT },
            inSync = assets.count { it.syncStatus == SyncStatus.IN_SYNC }
        )
    }

    /**
     * Statistics about sync state.
     */
    data class SyncStats(
        val totalFiles: Int,
        val newOnServer: Int,
        val newInProject: Int,
        val modifiedOnServer: Int,
        val modifiedInProject: Int,
        val conflicts: Int,
        val inSync: Int
    ) {
        val totalChanges: Int
            get() = totalFiles - inSync
    }
}
