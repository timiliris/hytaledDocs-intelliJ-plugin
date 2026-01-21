package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.assets.SyncTreeCellRenderer
import com.hytaledocs.intellij.assets.SyncTreeNode
import com.hytaledocs.intellij.services.AssetSyncService
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Panel for synchronizing assets between project and server directories.
 * Provides a tree view with checkboxes for selecting files to sync.
 */
class AssetSyncPanel(
    private val project: Project,
    private val onBack: () -> Unit
) : JBPanel<AssetSyncPanel>(BorderLayout()) {

    private val syncService = AssetSyncService.getInstance(project)

    // UI Components
    private val syncTree = Tree()
    private val statusLabel = JBLabel("Ready")
    private val statsLabel = JBLabel("")
    private val pullButton = JButton("Pull Selected", AllIcons.Actions.Download)
    private val pushButton = JButton("Push Selected", AllIcons.Actions.Upload)
    private val scanButton = JButton("Scan", AllIcons.Actions.Refresh)
    private val selectAllButton = JButton("Select All")
    private val deselectAllButton = JButton("Deselect All")

    // Preview panel
    private val previewPanel = JPanel(BorderLayout())
    private val serverPreviewLabel = JBLabel("Server Version")
    private val projectPreviewLabel = JBLabel("Project Version")
    private val serverInfoPanel = JPanel(BorderLayout())
    private val projectInfoPanel = JPanel(BorderLayout())

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.empty()

        // Main layout
        add(createToolbar(), BorderLayout.NORTH)

        // Split pane for tree and preview
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.dividerLocation = JBUI.scale(350)
        splitPane.dividerSize = JBUI.scale(4)
        splitPane.isContinuousLayout = true
        splitPane.border = null

        splitPane.leftComponent = createTreePanel()
        splitPane.rightComponent = createPreviewPanel()

        add(splitPane, BorderLayout.CENTER)
        add(createStatusBar(), BorderLayout.SOUTH)

        // Initial scan
        scanForChanges()
    }

    /**
     * Create the toolbar with back button, scan, and sync controls.
     */
    private fun createToolbar(): JPanel {
        val toolbar = JPanel(BorderLayout())
        toolbar.isOpaque = false
        toolbar.border = JBUI.Borders.empty(8)

        // Left: Back button
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        leftPanel.isOpaque = false

        val backButton = JButton(AllIcons.Actions.Back)
        backButton.toolTipText = "Back to Assets Explorer"
        backButton.addActionListener { onBack() }
        leftPanel.add(backButton)

        leftPanel.add(Box.createHorizontalStrut(JBUI.scale(8)))

        scanButton.toolTipText = "Scan for changes"
        scanButton.addActionListener { scanForChanges() }
        leftPanel.add(scanButton)

        toolbar.add(leftPanel, BorderLayout.WEST)

        // Center: Selection buttons
        val centerPanel = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(4), 0))
        centerPanel.isOpaque = false

        selectAllButton.addActionListener { selectAll(true) }
        centerPanel.add(selectAllButton)

        deselectAllButton.addActionListener { selectAll(false) }
        centerPanel.add(deselectAllButton)

        toolbar.add(centerPanel, BorderLayout.CENTER)

        // Right: Sync buttons
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        rightPanel.isOpaque = false

        pullButton.toolTipText = "Pull selected from server to project"
        pullButton.addActionListener { syncSelected(AssetSyncService.SyncDirection.PULL) }
        rightPanel.add(pullButton)

        pushButton.toolTipText = "Push selected from project to server"
        pushButton.addActionListener { syncSelected(AssetSyncService.SyncDirection.PUSH) }
        rightPanel.add(pushButton)

        toolbar.add(rightPanel, BorderLayout.EAST)

        return toolbar
    }

    /**
     * Create the tree panel with the sync tree.
     */
    private fun createTreePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = null
        panel.minimumSize = Dimension(JBUI.scale(250), 0)

        // Setup tree
        syncTree.isRootVisible = false
        syncTree.showsRootHandles = true
        syncTree.background = UIUtil.getPanelBackground()
        syncTree.isOpaque = false
        syncTree.cellRenderer = SyncTreeCellRenderer()
        syncTree.rowHeight = JBUI.scale(28)

        // Handle checkbox clicks
        syncTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = syncTree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return

                // Check if click was in the checkbox area (approximately first 24 pixels)
                val bounds = syncTree.getPathBounds(path) ?: return
                val checkboxWidth = JBUI.scale(24)

                if (e.x < bounds.x + checkboxWidth) {
                    toggleNodeSelection(node)
                    syncTree.repaint()
                }

                // Update preview on selection
                showPreview(node)
            }
        })

        syncTree.addTreeSelectionListener {
            val node = syncTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            if (node != null) {
                showPreview(node)
            }
        }

        val scrollPane = JBScrollPane(syncTree)
        scrollPane.border = null
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    /**
     * Create the preview panel showing server vs project comparison.
     */
    private fun createPreviewPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.empty(8)

        // Title
        val titleLabel = JBLabel("File Comparison")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(14f))
        titleLabel.border = JBUI.Borders.emptyBottom(8)
        panel.add(titleLabel, BorderLayout.NORTH)

        // Comparison panels
        val comparisonPanel = JPanel(GridLayout(1, 2, JBUI.scale(8), 0))
        comparisonPanel.isOpaque = false

        // Server side
        val serverCard = createComparisonCard("Server Version", serverInfoPanel)
        comparisonPanel.add(serverCard)

        // Project side
        val projectCard = createComparisonCard("Project Version", projectInfoPanel)
        comparisonPanel.add(projectCard)

        panel.add(comparisonPanel, BorderLayout.CENTER)

        // Show initial state
        showNoSelection()

        return panel
    }

    /**
     * Create a comparison card for server or project side.
     */
    private fun createComparisonCard(title: String, contentPanel: JPanel): JPanel {
        val card = JPanel(BorderLayout())
        card.background = HytaleTheme.cardBackground
        card.border = RoundedLineBorder(HytaleTheme.cardBorder, 8, 1)

        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        titleLabel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, HytaleTheme.cardBorder),
            JBUI.Borders.empty(8)
        )
        card.add(titleLabel, BorderLayout.NORTH)

        contentPanel.isOpaque = false
        contentPanel.border = JBUI.Borders.empty(8)
        card.add(contentPanel, BorderLayout.CENTER)

        return card
    }

    /**
     * Create the status bar.
     */
    private fun createStatusBar(): JPanel {
        val statusBar = JPanel(BorderLayout())
        statusBar.isOpaque = false
        statusBar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, HytaleTheme.cardBorder),
            JBUI.Borders.empty(4, 8)
        )

        statusLabel.foreground = HytaleTheme.mutedText
        statusLabel.font = statusLabel.font.deriveFont(JBUI.scaleFontSize(11f))
        statusBar.add(statusLabel, BorderLayout.WEST)

        statsLabel.foreground = HytaleTheme.mutedText
        statsLabel.font = statsLabel.font.deriveFont(JBUI.scaleFontSize(11f))
        statusBar.add(statsLabel, BorderLayout.EAST)

        return statusBar
    }

    /**
     * Scan for changes between project and server.
     */
    private fun scanForChanges() {
        statusLabel.text = "Scanning..."
        statusLabel.foreground = HytaleTheme.warningColor
        scanButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            syncService.scanForChanges { progress, message ->
                SwingUtilities.invokeLater {
                    statusLabel.text = message
                }
            }.thenAccept { assets ->
                SwingUtilities.invokeLater {
                    buildTree(assets)
                    updateStats()
                    statusLabel.text = "Ready"
                    statusLabel.foreground = HytaleTheme.successColor
                    scanButton.isEnabled = true
                }
            }
        }
    }

    /**
     * Build the tree from the list of syncable assets.
     */
    private fun buildTree(assets: List<AssetSyncService.SyncableAsset>) {
        val root = DefaultMutableTreeNode(SyncTreeNode.FolderNode("Root", ""))
        val folderMap = mutableMapOf<String, SyncTreeNode.FolderNode>()

        // Filter to only show changed files
        val changedAssets = assets.filter { it.syncStatus != AssetSyncService.SyncStatus.IN_SYNC }

        for (asset in changedAssets) {
            val pathParts = asset.relativePath.split("/", "\\").dropLast(1)

            var currentPath = ""
            var currentParent: SyncTreeNode.FolderNode = root.userObject as SyncTreeNode.FolderNode

            // Create folder hierarchy
            for (part in pathParts) {
                val newPath = if (currentPath.isEmpty()) part else "$currentPath/$part"

                val folderNode = folderMap.getOrPut(newPath) {
                    SyncTreeNode.FolderNode(part, newPath).also { newNode ->
                        currentParent.children.add(newNode)
                    }
                }

                currentPath = newPath
                currentParent = folderNode
            }

            // Add asset node
            val assetNode = SyncTreeNode.AssetNode(asset, selected = false)
            currentParent.children.add(assetNode)
        }

        // Sort children recursively
        sortFolderChildren(root.userObject as SyncTreeNode.FolderNode)

        // Build tree model
        val treeRoot = DefaultMutableTreeNode(root.userObject)
        buildTreeNodes(treeRoot, (root.userObject as SyncTreeNode.FolderNode).children)

        syncTree.model = DefaultTreeModel(treeRoot)

        // Expand first level
        for (i in 0 until minOf(syncTree.rowCount, 10)) {
            syncTree.expandRow(i)
        }
    }

    /**
     * Recursively build tree nodes.
     */
    private fun buildTreeNodes(parent: DefaultMutableTreeNode, children: List<SyncTreeNode>) {
        for (child in children) {
            val node = DefaultMutableTreeNode(child)
            parent.add(node)

            if (child is SyncTreeNode.FolderNode) {
                buildTreeNodes(node, child.children)
            }
        }
    }

    /**
     * Recursively sort folder children.
     */
    private fun sortFolderChildren(folder: SyncTreeNode.FolderNode) {
        folder.children.sortBy { it.sortKey }
        for (child in folder.children) {
            if (child is SyncTreeNode.FolderNode) {
                sortFolderChildren(child)
            }
        }
    }

    /**
     * Toggle the selection state of a node.
     */
    private fun toggleNodeSelection(treeNode: DefaultMutableTreeNode) {
        when (val data = treeNode.userObject) {
            is SyncTreeNode.FolderNode -> {
                val newState = !data.selected
                data.setAllChildrenSelected(newState)

                // Update tree model for child nodes
                updateChildrenInTree(treeNode, newState)
            }
            is SyncTreeNode.AssetNode -> {
                if (data.asset.syncStatus != AssetSyncService.SyncStatus.IN_SYNC) {
                    data.selected = !data.selected

                    // Update parent folder selection state
                    updateParentSelection(treeNode)
                }
            }
        }
    }

    /**
     * Update children in tree after folder selection change.
     */
    private fun updateChildrenInTree(treeNode: DefaultMutableTreeNode, selected: Boolean) {
        for (i in 0 until treeNode.childCount) {
            val child = treeNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            when (val data = child.userObject) {
                is SyncTreeNode.FolderNode -> {
                    data.selected = selected
                    updateChildrenInTree(child, selected)
                }
                is SyncTreeNode.AssetNode -> {
                    if (data.asset.syncStatus != AssetSyncService.SyncStatus.IN_SYNC) {
                        data.selected = selected
                    }
                }
            }
        }
    }

    /**
     * Update parent folder selection state based on children.
     */
    private fun updateParentSelection(node: DefaultMutableTreeNode) {
        var parent = node.parent as? DefaultMutableTreeNode
        while (parent != null) {
            val data = parent.userObject as? SyncTreeNode.FolderNode ?: break
            data.updateSelectionFromChildren()
            parent = parent.parent as? DefaultMutableTreeNode
        }
    }

    /**
     * Select or deselect all items.
     */
    private fun selectAll(selected: Boolean) {
        val root = syncTree.model.root as? DefaultMutableTreeNode ?: return
        val data = root.userObject as? SyncTreeNode.FolderNode ?: return
        data.setAllChildrenSelected(selected)
        updateChildrenInTree(root, selected)
        syncTree.repaint()
    }

    /**
     * Sync selected assets in the specified direction.
     */
    private fun syncSelected(direction: AssetSyncService.SyncDirection) {
        val selectedAssets = collectSelectedAssets()

        if (selectedAssets.isEmpty()) {
            notify("No files selected", NotificationType.WARNING)
            return
        }

        statusLabel.text = "Syncing..."
        statusLabel.foreground = HytaleTheme.warningColor
        pullButton.isEnabled = false
        pushButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            syncService.syncAssets(selectedAssets, direction) { progress, message ->
                SwingUtilities.invokeLater {
                    statusLabel.text = message
                }
            }.thenAccept { result ->
                SwingUtilities.invokeLater {
                    pullButton.isEnabled = true
                    pushButton.isEnabled = true

                    if (result.success) {
                        notify("Synced ${result.syncedCount} files", NotificationType.INFORMATION)
                    } else {
                        notify("Sync completed with ${result.errorCount} errors", NotificationType.WARNING)
                    }

                    // Refresh the scan
                    scanForChanges()
                }
            }
        }
    }

    /**
     * Collect all selected assets from the tree.
     */
    private fun collectSelectedAssets(): List<AssetSyncService.SyncableAsset> {
        val selected = mutableListOf<AssetSyncService.SyncableAsset>()

        fun collectFromNode(node: DefaultMutableTreeNode) {
            when (val data = node.userObject) {
                is SyncTreeNode.FolderNode -> {
                    for (i in 0 until node.childCount) {
                        val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                        collectFromNode(child)
                    }
                }
                is SyncTreeNode.AssetNode -> {
                    if (data.selected) {
                        selected.add(data.asset)
                    }
                }
            }
        }

        val root = syncTree.model.root as? DefaultMutableTreeNode ?: return selected
        collectFromNode(root)

        return selected
    }

    /**
     * Show preview for the selected node.
     */
    private fun showPreview(node: DefaultMutableTreeNode) {
        when (val data = node.userObject) {
            is SyncTreeNode.AssetNode -> showAssetPreview(data.asset)
            is SyncTreeNode.FolderNode -> showFolderPreview(data)
            else -> showNoSelection()
        }
    }

    /**
     * Show preview for an asset.
     */
    private fun showAssetPreview(asset: AssetSyncService.SyncableAsset) {
        // Server info
        serverInfoPanel.removeAll()
        if (asset.serverFile != null) {
            serverInfoPanel.add(createInfoLabel("Path: ${asset.serverFile.absolutePath}"), BorderLayout.NORTH)
            serverInfoPanel.add(createInfoLabel("Size: ${asset.fileSizeFormatted}"), BorderLayout.CENTER)
            serverInfoPanel.add(createInfoLabel("Modified: ${formatTimestamp(asset.serverLastModified)}"), BorderLayout.SOUTH)
        } else {
            serverInfoPanel.add(createInfoLabel("File does not exist on server"), BorderLayout.CENTER)
        }

        // Project info
        projectInfoPanel.removeAll()
        if (asset.projectFile != null) {
            projectInfoPanel.add(createInfoLabel("Path: ${asset.projectFile.absolutePath}"), BorderLayout.NORTH)
            projectInfoPanel.add(createInfoLabel("Size: ${asset.fileSizeFormatted}"), BorderLayout.CENTER)
            projectInfoPanel.add(createInfoLabel("Modified: ${formatTimestamp(asset.projectLastModified)}"), BorderLayout.SOUTH)
        } else {
            projectInfoPanel.add(createInfoLabel("File does not exist in project"), BorderLayout.CENTER)
        }

        serverInfoPanel.revalidate()
        projectInfoPanel.revalidate()
        serverInfoPanel.repaint()
        projectInfoPanel.repaint()
    }

    /**
     * Show preview for a folder.
     */
    private fun showFolderPreview(folder: SyncTreeNode.FolderNode) {
        serverInfoPanel.removeAll()
        serverInfoPanel.add(createInfoLabel("Folder: ${folder.displayName}"), BorderLayout.NORTH)
        serverInfoPanel.add(createInfoLabel("Changes: ${folder.changesCount}"), BorderLayout.CENTER)

        projectInfoPanel.removeAll()
        projectInfoPanel.add(createInfoLabel("Path: ${folder.path}"), BorderLayout.CENTER)

        serverInfoPanel.revalidate()
        projectInfoPanel.revalidate()
        serverInfoPanel.repaint()
        projectInfoPanel.repaint()
    }

    /**
     * Show empty preview state.
     */
    private fun showNoSelection() {
        serverInfoPanel.removeAll()
        serverInfoPanel.add(createInfoLabel("Select a file to compare"), BorderLayout.CENTER)

        projectInfoPanel.removeAll()
        projectInfoPanel.add(createInfoLabel("Select a file to compare"), BorderLayout.CENTER)

        serverInfoPanel.revalidate()
        projectInfoPanel.revalidate()
        serverInfoPanel.repaint()
        projectInfoPanel.repaint()
    }

    /**
     * Create an info label for the preview panel.
     */
    private fun createInfoLabel(text: String): JBLabel {
        val label = JBLabel(text)
        label.foreground = HytaleTheme.mutedText
        label.font = label.font.deriveFont(JBUI.scaleFontSize(12f))
        return label
    }

    /**
     * Format a timestamp for display.
     */
    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return "N/A"
        val date = java.util.Date(timestamp)
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return format.format(date)
    }

    /**
     * Update the stats label.
     */
    private fun updateStats() {
        val stats = syncService.getSyncStats()
        statsLabel.text = "${stats.totalChanges} changes | ${stats.totalFiles} total files"
    }

    /**
     * Show a notification.
     */
    private fun notify(message: String, type: NotificationType) =
        PanelUtils.notify(project, "Asset Sync", message, type)
}
