package com.hytaledocs.intellij.assets

import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Sealed class hierarchy representing nodes in the asset tree.
 * Supports two view modes: by type (category) and by folder structure.
 */
sealed class AssetNode {
    abstract val displayName: String
    abstract val sortKey: String

    /**
     * Root node containing all assets.
     */
    data class RootNode(
        override val displayName: String = "Assets",
        val children: MutableList<AssetNode> = mutableListOf()
    ) : AssetNode() {
        override val sortKey: String = ""
    }

    /**
     * Category node grouping assets by type (e.g., Textures, Models, Sounds).
     * Used in "By Type" view mode.
     */
    data class CategoryNode(
        val assetType: AssetType,
        override val displayName: String = assetType.displayName,
        val children: MutableList<AssetNode> = mutableListOf(),
        var fileCount: Int = 0,
        var totalSize: Long = 0
    ) : AssetNode() {
        override val sortKey: String = displayName.lowercase()
    }

    /**
     * Folder node representing a directory in the file system.
     * Used in "By Folder" view mode.
     */
    data class FolderNode(
        override val displayName: String,
        val path: String,
        val virtualFile: VirtualFile? = null,
        val children: MutableList<AssetNode> = mutableListOf(),
        var fileCount: Int = 0,
        var totalSize: Long = 0
    ) : AssetNode() {
        override val sortKey: String = "0_$displayName".lowercase() // Sort folders before files
    }

    /**
     * File node representing an individual asset file.
     */
    data class FileNode(
        override val displayName: String,
        val file: File?,
        val virtualFile: VirtualFile? = null,
        val assetType: AssetType,
        val size: Long,
        val relativePath: String,
        val zipSource: ZipSource? = null
    ) : AssetNode() {
        override val sortKey: String = "1_$displayName".lowercase() // Sort files after folders

        val extension: String
            get() = displayName.substringAfterLast('.', "").lowercase()

        val sizeFormatted: String
            get() = formatFileSize(size)

        val isInZip: Boolean
            get() = zipSource != null

        companion object {
            fun formatFileSize(bytes: Long): String {
                return when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
                    else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
                }
            }
        }
    }

    /**
     * ZIP archive node representing a ZIP file containing assets.
     */
    data class ZipNode(
        override val displayName: String,
        val zipFile: File,
        val virtualFile: VirtualFile? = null,
        val children: MutableList<AssetNode> = mutableListOf(),
        var fileCount: Int = 0,
        var totalSize: Long = 0
    ) : AssetNode() {
        override val sortKey: String = "0_$displayName".lowercase() // Sort ZIP files like folders
    }
}

/**
 * Information about a file's source ZIP archive.
 */
data class ZipSource(
    val zipFile: File,
    val entryPath: String
)

/**
 * Enum representing the view mode for the asset tree.
 */
enum class AssetViewMode(val displayName: String, val displayNameFr: String) {
    BY_TYPE("By Type", "Par type"),
    BY_FOLDER("By Folder", "Par dossier");

    fun toggle(): AssetViewMode = when (this) {
        BY_TYPE -> BY_FOLDER
        BY_FOLDER -> BY_TYPE
    }
}

/**
 * Data class for asset statistics.
 */
data class AssetStats(
    val totalFiles: Int = 0,
    val totalSize: Long = 0,
    val byType: Map<AssetType, Int> = emptyMap()
) {
    val totalSizeFormatted: String
        get() = AssetNode.FileNode.formatFileSize(totalSize)

    companion object {
        val EMPTY = AssetStats()
    }
}
