package com.hytaledocs.intellij.assets

import com.hytaledocs.intellij.services.AssetSyncService
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Font
import javax.swing.Icon
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JLabel

/**
 * Custom tree cell renderer for the asset sync tree.
 * Displays checkboxes, status icons, names, and sync status for each asset.
 */
class SyncTreeCellRenderer : TreeCellRenderer {

    private val panel = JPanel(BorderLayout(JBUI.scale(4), 0))
    private val checkBox = JCheckBox()
    private val iconLabel = JLabel()
    private val nameLabel = JLabel()
    private val statusLabel = JLabel()

    init {
        panel.isOpaque = false
        checkBox.isOpaque = false

        val contentPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        contentPanel.isOpaque = false
        contentPanel.add(iconLabel)
        contentPanel.add(nameLabel)
        contentPanel.add(statusLabel)

        panel.add(checkBox, BorderLayout.WEST)
        panel.add(contentPanel, BorderLayout.CENTER)
    }

    override fun getTreeCellRendererComponent(
        tree: JTree?,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): Component {
        val node = value as? DefaultMutableTreeNode
        val data = node?.userObject

        when (data) {
            is SyncTreeNode.FolderNode -> {
                checkBox.isVisible = true
                checkBox.isSelected = data.selected
                checkBox.isEnabled = data.children.isNotEmpty()

                iconLabel.icon = if (expanded) AllIcons.Actions.ProjectDirectory else AllIcons.Nodes.Folder
                nameLabel.text = data.displayName
                nameLabel.font = tree?.font?.deriveFont(Font.BOLD)

                val changesCount = data.changesCount
                if (changesCount > 0) {
                    statusLabel.text = "($changesCount changes)"
                    statusLabel.foreground = HytaleTheme.warningColor
                } else {
                    statusLabel.text = ""
                }
                statusLabel.font = tree?.font?.deriveFont(JBUI.scaleFontSize(11f))
            }

            is SyncTreeNode.AssetNode -> {
                checkBox.isVisible = true
                checkBox.isSelected = data.selected
                checkBox.isEnabled = data.asset.syncStatus != AssetSyncService.SyncStatus.IN_SYNC

                iconLabel.icon = getStatusIcon(data.asset.syncStatus)
                nameLabel.text = data.asset.displayName
                nameLabel.font = tree?.font?.deriveFont(Font.PLAIN)

                statusLabel.text = "(${data.asset.fileSizeFormatted})"
                statusLabel.font = tree?.font?.deriveFont(JBUI.scaleFontSize(11f))
            }

            else -> {
                checkBox.isVisible = false
                iconLabel.icon = AllIcons.Nodes.Folder
                nameLabel.text = value?.toString() ?: ""
                nameLabel.font = tree?.font?.deriveFont(Font.PLAIN)
                statusLabel.text = ""
            }
        }

        // Set colors based on selection and sync status
        if (selected) {
            panel.isOpaque = true
            panel.background = UIUtil.getTreeSelectionBackground(true)
            nameLabel.foreground = UIUtil.getTreeSelectionForeground(true)
            statusLabel.foreground = UIUtil.getTreeSelectionForeground(true)
        } else {
            panel.isOpaque = false
            when (data) {
                is SyncTreeNode.AssetNode -> {
                    nameLabel.foreground = getStatusColor(data.asset.syncStatus)
                    statusLabel.foreground = HytaleTheme.mutedText
                }
                is SyncTreeNode.FolderNode -> {
                    nameLabel.foreground = HytaleTheme.textPrimary
                    statusLabel.foreground = HytaleTheme.mutedText
                }
                else -> {
                    nameLabel.foreground = HytaleTheme.textPrimary
                    statusLabel.foreground = HytaleTheme.mutedText
                }
            }
        }

        return panel
    }

    /**
     * Get the appropriate icon for a sync status.
     */
    private fun getStatusIcon(status: AssetSyncService.SyncStatus): Icon {
        return when (status) {
            AssetSyncService.SyncStatus.NEW_ON_SERVER -> AllIcons.General.Add
            AssetSyncService.SyncStatus.NEW_IN_PROJECT -> AllIcons.General.Add
            AssetSyncService.SyncStatus.MODIFIED_ON_SERVER -> AllIcons.Actions.Download
            AssetSyncService.SyncStatus.MODIFIED_IN_PROJECT -> AllIcons.Actions.Upload
            AssetSyncService.SyncStatus.CONFLICT -> AllIcons.General.Warning
            AssetSyncService.SyncStatus.IN_SYNC -> AllIcons.Actions.Checked
        }
    }

    /**
     * Get the appropriate color for a sync status.
     */
    private fun getStatusColor(status: AssetSyncService.SyncStatus): java.awt.Color {
        return when (status) {
            AssetSyncService.SyncStatus.NEW_ON_SERVER -> HytaleTheme.successColor
            AssetSyncService.SyncStatus.NEW_IN_PROJECT -> HytaleTheme.accentColor
            AssetSyncService.SyncStatus.MODIFIED_ON_SERVER -> HytaleTheme.warningColor
            AssetSyncService.SyncStatus.MODIFIED_IN_PROJECT -> HytaleTheme.purpleAccent
            AssetSyncService.SyncStatus.CONFLICT -> HytaleTheme.errorColor
            AssetSyncService.SyncStatus.IN_SYNC -> HytaleTheme.mutedText
        }
    }
}

/**
 * Tree node types for the sync tree.
 */
sealed class SyncTreeNode {

    abstract val sortKey: String

    /**
     * Folder node containing child nodes.
     */
    data class FolderNode(
        val displayName: String,
        val path: String,
        var selected: Boolean = false,
        val children: MutableList<SyncTreeNode> = mutableListOf()
    ) : SyncTreeNode() {
        override val sortKey: String
            get() = "0_$displayName" // Folders sort first

        val changesCount: Int
            get() = countChanges()

        private fun countChanges(): Int {
            var count = 0
            for (child in children) {
                when (child) {
                    is FolderNode -> count += child.changesCount
                    is AssetNode -> if (child.asset.syncStatus != AssetSyncService.SyncStatus.IN_SYNC) count++
                }
            }
            return count
        }

        fun updateSelectionFromChildren() {
            selected = children.any { child ->
                when (child) {
                    is FolderNode -> child.selected
                    is AssetNode -> child.selected
                }
            }
        }

        fun setAllChildrenSelected(value: Boolean) {
            selected = value
            for (child in children) {
                when (child) {
                    is FolderNode -> child.setAllChildrenSelected(value)
                    is AssetNode -> {
                        if (child.asset.syncStatus != AssetSyncService.SyncStatus.IN_SYNC) {
                            child.selected = value
                        }
                    }
                }
            }
        }
    }

    /**
     * Asset node representing a syncable file.
     */
    data class AssetNode(
        val asset: AssetSyncService.SyncableAsset,
        var selected: Boolean = false
    ) : SyncTreeNode() {
        override val sortKey: String
            get() = "1_${asset.displayName}" // Files sort after folders
    }
}
