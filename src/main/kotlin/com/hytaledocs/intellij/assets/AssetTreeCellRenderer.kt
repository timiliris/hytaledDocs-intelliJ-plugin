package com.hytaledocs.intellij.assets

import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer

/**
 * Custom tree cell renderer for the asset tree.
 * Displays icons, names, and metadata for each node type.
 */
class AssetTreeCellRenderer : DefaultTreeCellRenderer() {

    private val folderIcon = AllIcons.Nodes.Folder
    private val folderOpenIcon = AllIcons.Actions.ProjectDirectory
    private val zipIcon = AllIcons.FileTypes.Archive

    init {
        // Make renderer transparent to fix color mismatch
        isOpaque = false
        backgroundNonSelectionColor = null
        backgroundSelectionColor = UIUtil.getTreeSelectionBackground(true)
        borderSelectionColor = null

        // Improve spacing
        iconTextGap = JBUI.scale(6)
    }

    override fun getTreeCellRendererComponent(
        tree: JTree?,
        value: Any?,
        sel: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): Component {
        val node = value as? DefaultMutableTreeNode
        val data = node?.userObject

        when (data) {
            is AssetNode.RootNode -> {
                text = data.displayName
                icon = folderOpenIcon
                toolTipText = null
                font = tree?.font?.deriveFont(Font.BOLD)
            }

            is AssetNode.CategoryNode -> {
                val countText = "${data.fileCount} files"
                val sizeText = AssetNode.FileNode.formatFileSize(data.totalSize)
                text = "${data.displayName} ($countText, $sizeText)"
                icon = data.assetType.icon
                toolTipText = "${data.displayName}: $countText, $sizeText"
                font = tree?.font?.deriveFont(Font.BOLD)
            }

            is AssetNode.FolderNode -> {
                val countText = "${data.fileCount} files"
                text = data.displayName
                icon = if (expanded) folderOpenIcon else folderIcon
                toolTipText = "${data.path}: $countText"
                font = tree?.font?.deriveFont(Font.PLAIN)
            }

            is AssetNode.ZipNode -> {
                val countText = "${data.fileCount} files"
                val sizeText = AssetNode.FileNode.formatFileSize(data.totalSize)
                text = "${data.displayName} ($countText, $sizeText)"
                icon = zipIcon
                toolTipText = "${data.displayName}: $countText, $sizeText"
                font = tree?.font?.deriveFont(Font.BOLD)
            }

            is AssetNode.FileNode -> {
                text = data.displayName
                icon = data.assetType.icon
                toolTipText = "${data.relativePath} (${data.sizeFormatted})"
                font = tree?.font?.deriveFont(Font.PLAIN)
            }

            else -> {
                text = value?.toString() ?: ""
                icon = folderIcon
                toolTipText = null
                font = tree?.font?.deriveFont(Font.PLAIN)
            }
        }

        // Set colors based on selection
        if (sel) {
            foreground = UIUtil.getTreeSelectionForeground(true)
            background = UIUtil.getTreeSelectionBackground(true)
            isOpaque = true
        } else {
            foreground = when (data) {
                is AssetNode.CategoryNode -> HytaleTheme.accentColor
                is AssetNode.ZipNode -> HytaleTheme.warningColor
                is AssetNode.FolderNode -> HytaleTheme.textPrimary
                is AssetNode.FileNode -> getFileColor(data)
                else -> HytaleTheme.textPrimary
            }
            background = null
            isOpaque = false
        }

        return this
    }

    /**
     * Get the appropriate color for a file node based on its type.
     */
    private fun getFileColor(file: AssetNode.FileNode): java.awt.Color {
        return when (file.assetType) {
            AssetType.TEXTURE -> HytaleTheme.successColor
            AssetType.SOUND -> HytaleTheme.warningColor
            AssetType.MODEL, AssetType.ANIMATION -> HytaleTheme.purpleAccent
            AssetType.DATA, AssetType.CONFIG -> HytaleTheme.accentColor
            else -> HytaleTheme.textPrimary
        }
    }
}
