package com.hytaledocs.intellij.services

import com.hytaledocs.intellij.assets.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipFile

/**
 * Service for scanning and indexing project assets.
 * Scans src/main/resources directory and ZIP archives for game assets.
 */
@Service(Service.Level.PROJECT)
class AssetScannerService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(AssetScannerService::class.java)

        fun getInstance(project: Project): AssetScannerService {
            return project.getService(AssetScannerService::class.java)
        }

        // Default resource paths to scan
        private val RESOURCE_PATHS = listOf(
            "src/main/resources",
            "resources",
            "assets"
        )

        // Additional paths to scan for asset ZIP files
        private val ZIP_SEARCH_PATHS = listOf(
            "server",
            "server/assets",
            "run",
            "run/assets",
            "."
        )

        // ZIP file names to scan for assets
        private val ASSET_ZIP_NAMES = listOf(
            "Assets.zip",
            "assets.zip",
            "Resources.zip",
            "resources.zip"
        )
    }

    @Volatile
    private var isScanning = false

    @Volatile
    private var cachedStats: AssetStats = AssetStats.EMPTY

    /**
     * Data class to hold both tree views and stats from a single scan operation.
     */
    private data class ScanResult(
        val byType: AssetNode.RootNode,
        val byFolder: AssetNode.RootNode,
        val stats: AssetStats,
        val allFiles: List<AssetNode.FileNode>
    )

    /**
     * Cached scan result using IntelliJ's CachedValue for automatic invalidation.
     * The cache is invalidated when clearCache() is called by setting a new modification tracker.
     */
    @Volatile
    private var cacheModificationCount = 0L

    private val cachedScanResult: CachedValue<ScanResult?> = CachedValuesManager.getManager(project).createCachedValue {
        val result = performSynchronousScan()
        if (result != null) {
            cachedStats = result.stats
        }
        CachedValueProvider.Result.create(result, ModificationTracker { cacheModificationCount })
    }

    /**
     * Simple modification tracker that can be incremented to invalidate the cache.
     */
    private fun interface ModificationTracker : com.intellij.openapi.util.ModificationTracker {
        override fun getModificationCount(): Long
    }

    /**
     * Check if a scan is currently in progress.
     */
    fun isScanning(): Boolean = isScanning

    /**
     * Get the cached asset statistics.
     */
    fun getStats(): AssetStats = cachedStats

    /**
     * Get the resources directory for the project.
     */
    fun getResourcesDirectory(): VirtualFile? {
        val basePath = project.basePath ?: return null

        for (resourcePath in RESOURCE_PATHS) {
            val dir = LocalFileSystem.getInstance().findFileByPath("$basePath/$resourcePath")
            if (dir != null && dir.isDirectory) {
                return dir
            }
        }

        return null
    }

    /**
     * Scan assets and return a tree organized by type.
     * Uses CachedValue for efficient caching with automatic invalidation.
     */
    fun scanByType(
        forceRefresh: Boolean = false,
        onProgress: ((Int, String) -> Unit)? = null
    ): CompletableFuture<AssetNode.RootNode> {
        if (forceRefresh) {
            clearCache()
        }

        // Check if we have a cached result
        val cached = cachedScanResult.value
        if (cached != null) {
            onProgress?.invoke(100, "Found ${cached.allFiles.size} assets (cached)")
            return CompletableFuture.completedFuture(cached.byType)
        }

        // Perform async scan with progress reporting
        return scanAssetsAsync(onProgress).thenApply { result ->
            result?.byType ?: AssetNode.RootNode()
        }
    }

    /**
     * Scan assets and return a tree organized by folder.
     * Uses CachedValue for efficient caching with automatic invalidation.
     */
    fun scanByFolder(
        forceRefresh: Boolean = false,
        onProgress: ((Int, String) -> Unit)? = null
    ): CompletableFuture<AssetNode.RootNode> {
        if (forceRefresh) {
            clearCache()
        }

        // Check if we have a cached result
        val cached = cachedScanResult.value
        if (cached != null) {
            onProgress?.invoke(100, "Found ${cached.allFiles.size} assets (cached)")
            return CompletableFuture.completedFuture(cached.byFolder)
        }

        // Perform async scan with progress reporting
        return scanAssetsAsync(onProgress).thenApply { result ->
            result?.byFolder ?: AssetNode.RootNode()
        }
    }

    /**
     * Clear the cached scan results by incrementing the modification counter.
     */
    fun clearCache() {
        cacheModificationCount++
        cachedStats = AssetStats.EMPTY
    }

    /**
     * Perform a synchronous scan of assets. Called by the CachedValue provider.
     */
    @RequiresReadLock
    private fun performSynchronousScan(): ScanResult? {
        val resourcesDir = getResourcesDirectory() ?: return null

        val allFiles = mutableListOf<AssetNode.FileNode>()

        // Collect all asset files from directory
        collectAssetFiles(resourcesDir, resourcesDir, allFiles, null)

        // Scan ZIP files for assets
        val zipFiles = findAssetZipFiles(resourcesDir)
        for (zipFile in zipFiles) {
            collectAssetsFromZip(zipFile, allFiles, null)
        }

        // Build both trees
        val byTypeRoot = AssetNode.RootNode()
        val byFolderRoot = AssetNode.RootNode()

        buildByTypeTree(byTypeRoot, allFiles)
        buildByFolderTree(byFolderRoot, allFiles, resourcesDir)

        // Calculate statistics
        val byType = allFiles.groupBy { it.assetType }.mapValues { it.value.size }
        val stats = AssetStats(
            totalFiles = allFiles.size,
            totalSize = allFiles.sumOf { it.size },
            byType = byType
        )

        LOG.info("Scanned ${allFiles.size} assets in ${resourcesDir.path}")

        return ScanResult(byTypeRoot, byFolderRoot, stats, allFiles)
    }

    /**
     * Perform an asynchronous scan with progress reporting.
     * This populates the CachedValue and returns the result.
     */
    private fun scanAssetsAsync(
        onProgress: ((Int, String) -> Unit)?
    ): CompletableFuture<ScanResult?> {
        if (isScanning) {
            return CompletableFuture.completedFuture(cachedScanResult.value)
        }

        isScanning = true

        return CompletableFuture.supplyAsync {
            try {
                val resourcesDir = getResourcesDirectory()
                if (resourcesDir == null) {
                    LOG.info("No resources directory found")
                    onProgress?.invoke(100, "No resources directory found")
                    return@supplyAsync null
                }

                onProgress?.invoke(0, "Scanning ${resourcesDir.name}...")

                val allFiles = mutableListOf<AssetNode.FileNode>()

                // Collect all asset files from directory
                collectAssetFiles(resourcesDir, resourcesDir, allFiles, onProgress)

                // Scan ZIP files for assets
                onProgress?.invoke(30, "Scanning ZIP archives...")
                val zipFiles = findAssetZipFiles(resourcesDir)
                for (zipFile in zipFiles) {
                    onProgress?.invoke(40, "Scanning ${zipFile.name}...")
                    collectAssetsFromZip(zipFile, allFiles, onProgress)
                }

                onProgress?.invoke(70, "Building tree...")

                // Build both trees
                val byTypeRoot = AssetNode.RootNode()
                val byFolderRoot = AssetNode.RootNode()

                buildByTypeTree(byTypeRoot, allFiles)
                buildByFolderTree(byFolderRoot, allFiles, resourcesDir)

                // Calculate statistics
                val byType = allFiles.groupBy { it.assetType }.mapValues { it.value.size }
                val stats = AssetStats(
                    totalFiles = allFiles.size,
                    totalSize = allFiles.sumOf { it.size },
                    byType = byType
                )
                cachedStats = stats

                onProgress?.invoke(100, "Found ${allFiles.size} assets")

                LOG.info("Scanned ${allFiles.size} assets in ${resourcesDir.path}")

                val result = ScanResult(byTypeRoot, byFolderRoot, stats, allFiles)
                // Force cache refresh by incrementing and then allowing CachedValue to recompute
                cacheModificationCount++
                result
            } catch (e: Exception) {
                LOG.error("Error scanning assets", e)
                onProgress?.invoke(100, "Error: ${e.message}")
                null
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * Find ZIP files containing assets in the resources directory and other common locations.
     */
    private fun findAssetZipFiles(resourcesDir: VirtualFile): List<File> {
        val zipFiles = mutableSetOf<String>() // Use canonical paths to avoid duplicates
        val result = mutableListOf<File>()
        val basePath = project.basePath ?: return result

        fun addZipIfNew(file: File) {
            val canonicalPath = file.canonicalPath
            if (canonicalPath !in zipFiles && file.exists()) {
                zipFiles.add(canonicalPath)
                result.add(file)
            }
        }

        // Check for known asset ZIP names in resources directory
        for (zipName in ASSET_ZIP_NAMES) {
            val zipVirtualFile = resourcesDir.findChild(zipName)
            if (zipVirtualFile != null && !zipVirtualFile.isDirectory) {
                addZipIfNew(File(zipVirtualFile.path))
            }
        }

        // Also find any .zip files in the resources directory
        for (child in resourcesDir.children) {
            if (!child.isDirectory && child.extension?.lowercase() == "zip") {
                addZipIfNew(File(child.path))
            }
        }

        // Search in additional paths (server/, run/, etc.)
        for (searchPath in ZIP_SEARCH_PATHS) {
            val searchDir = LocalFileSystem.getInstance().findFileByPath("$basePath/$searchPath")
            if (searchDir != null && searchDir.isDirectory) {
                // Check for known asset ZIP names
                for (zipName in ASSET_ZIP_NAMES) {
                    val zipVirtualFile = searchDir.findChild(zipName)
                    if (zipVirtualFile != null && !zipVirtualFile.isDirectory) {
                        addZipIfNew(File(zipVirtualFile.path))
                    }
                }

                // Also find any .zip files in this directory
                for (child in searchDir.children) {
                    if (!child.isDirectory && child.extension?.lowercase() == "zip") {
                        addZipIfNew(File(child.path))
                    }
                }
            }
        }

        return result
    }

    /**
     * Collect assets from inside a ZIP file.
     */
    private fun collectAssetsFromZip(
        zipFile: File,
        files: MutableList<AssetNode.FileNode>,
        onProgress: ((Int, String) -> Unit)?
    ) {
        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()

                    // Skip directories
                    if (entry.isDirectory) continue

                    val entryName = entry.name
                    val fileName = entryName.substringAfterLast('/')
                    val assetType = AssetType.fromFileName(fileName)

                    // Include recognized asset types
                    if (assetType != AssetType.OTHER) {
                        files.add(
                            AssetNode.FileNode(
                                displayName = fileName,
                                file = null, // No direct file access for ZIP entries
                                virtualFile = null,
                                assetType = assetType,
                                size = entry.size,
                                relativePath = "${zipFile.name}/$entryName",
                                zipSource = ZipSource(zipFile, entryName)
                            )
                        )
                    }

                    // Report progress periodically
                    if (files.size % 100 == 0) {
                        onProgress?.invoke(50, "Found ${files.size} files...")
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Error scanning ZIP file ${zipFile.name}", e)
        }
    }

    /**
     * Recursively collect all asset files from a directory.
     */
    private fun collectAssetFiles(
        dir: VirtualFile,
        baseDir: VirtualFile,
        files: MutableList<AssetNode.FileNode>,
        onProgress: ((Int, String) -> Unit)?
    ) {
        for (child in dir.children) {
            if (child.isDirectory) {
                // Skip hidden directories
                if (!child.name.startsWith(".")) {
                    collectAssetFiles(child, baseDir, files, onProgress)
                }
            } else {
                val assetType = AssetType.fromFileName(child.name)
                // Include all recognized asset types (not OTHER, unless it has a known extension)
                if (assetType != AssetType.OTHER || child.extension?.lowercase() in AssetType.allExtensions()) {
                    val file = File(child.path)
                    val relativePath = child.path.removePrefix(baseDir.path).removePrefix("/").removePrefix("\\")

                    files.add(
                        AssetNode.FileNode(
                            displayName = child.name,
                            file = file,
                            virtualFile = child,
                            assetType = assetType,
                            size = child.length,
                            relativePath = relativePath
                        )
                    )
                }

                // Report progress periodically
                if (files.size % 50 == 0) {
                    onProgress?.invoke(25, "Found ${files.size} files...")
                }
            }
        }
    }

    /**
     * Build tree organized by asset type, with folder structure preserved.
     */
    private fun buildByTypeTree(root: AssetNode.RootNode, files: List<AssetNode.FileNode>) {
        // Group files by type
        val byType = files.groupBy { it.assetType }

        // Create category nodes for each type that has files
        for (type in AssetType.entries) {
            val typeFiles = byType[type] ?: continue
            if (typeFiles.isEmpty()) continue

            val categoryNode = AssetNode.CategoryNode(
                assetType = type,
                fileCount = typeFiles.size,
                totalSize = typeFiles.sumOf { it.size }
            )

            // Group files by their parent folder path to create folder structure
            val folderMap = mutableMapOf<String, AssetNode.FolderNode>()

            for (file in typeFiles) {
                // Get the folder path from the relative path
                val pathParts = file.relativePath.split("/", "\\").dropLast(1)

                if (pathParts.isEmpty()) {
                    // File is at root level of category
                    categoryNode.children.add(file)
                } else {
                    // Build folder hierarchy
                    var currentPath = ""
                    var currentParent: AssetNode = categoryNode

                    for (part in pathParts) {
                        val newPath = if (currentPath.isEmpty()) part else "$currentPath/$part"

                        val folderNode = folderMap.getOrPut(newPath) {
                            AssetNode.FolderNode(
                                displayName = part,
                                path = newPath,
                                virtualFile = null
                            ).also { newNode ->
                                // Add to parent
                                when (currentParent) {
                                    is AssetNode.CategoryNode -> currentParent.children.add(newNode)
                                    is AssetNode.FolderNode -> currentParent.children.add(newNode)
                                    else -> {}
                                }
                            }
                        }

                        // Update folder stats
                        folderNode.fileCount++
                        folderNode.totalSize += file.size

                        currentPath = newPath
                        currentParent = folderNode
                    }

                    // Add file to its parent folder
                    when (currentParent) {
                        is AssetNode.FolderNode -> currentParent.children.add(file)
                        is AssetNode.CategoryNode -> currentParent.children.add(file)
                        else -> {}
                    }
                }
            }

            // Sort all children recursively
            sortCategoryChildren(categoryNode)
            root.children.add(categoryNode)
        }

        // Sort categories by display name
        root.children.sortBy { it.sortKey }
    }

    /**
     * Recursively sort children of a category node.
     */
    private fun sortCategoryChildren(node: AssetNode.CategoryNode) {
        node.children.sortBy { it.sortKey }
        for (child in node.children) {
            if (child is AssetNode.FolderNode) {
                child.children.sortBy { it.sortKey }
                sortFolderChildrenRecursive(child)
            }
        }
    }

    /**
     * Recursively sort children of a folder node.
     */
    private fun sortFolderChildrenRecursive(node: AssetNode.FolderNode) {
        node.children.sortBy { it.sortKey }
        for (child in node.children) {
            if (child is AssetNode.FolderNode) {
                sortFolderChildrenRecursive(child)
            }
        }
    }

    /**
     * Build tree mirroring folder structure.
     */
    private fun buildByFolderTree(
        root: AssetNode.RootNode,
        files: List<AssetNode.FileNode>,
        baseDir: VirtualFile
    ) {
        // Create a map for quick folder lookup
        val folderMap = mutableMapOf<String, AssetNode.FolderNode>()

        for (file in files) {
            val pathParts = file.relativePath.split("/", "\\").dropLast(1)

            var currentPath = ""
            var currentParent: AssetNode = root

            // Create folder nodes for each path segment
            for (part in pathParts) {
                val newPath = if (currentPath.isEmpty()) part else "$currentPath/$part"

                val folderNode = folderMap.getOrPut(newPath) {
                    val virtualFile = LocalFileSystem.getInstance()
                        .findFileByPath("${baseDir.path}/$newPath")

                    AssetNode.FolderNode(
                        displayName = part,
                        path = newPath,
                        virtualFile = virtualFile
                    ).also { newNode ->
                        // Add to parent
                        when (currentParent) {
                            is AssetNode.RootNode -> currentParent.children.add(newNode)
                            is AssetNode.FolderNode -> currentParent.children.add(newNode)
                            else -> {}
                        }
                    }
                }

                // Update folder stats
                folderNode.fileCount++
                folderNode.totalSize += file.size

                currentPath = newPath
                currentParent = folderNode
            }

            // Add file to its parent folder
            when (currentParent) {
                is AssetNode.RootNode -> currentParent.children.add(file)
                is AssetNode.FolderNode -> currentParent.children.add(file)
                else -> {}
            }
        }

        // Sort all children recursively
        sortFolderChildren(root)
    }

    /**
     * Recursively sort children of folder nodes.
     */
    private fun sortFolderChildren(node: AssetNode) {
        when (node) {
            is AssetNode.RootNode -> {
                node.children.sortBy { it.sortKey }
                node.children.forEach { sortFolderChildren(it) }
            }
            is AssetNode.FolderNode -> {
                node.children.sortBy { it.sortKey }
                node.children.forEach { sortFolderChildren(it) }
            }
            else -> {}
        }
    }
}
