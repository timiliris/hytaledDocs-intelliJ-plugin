package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.assets.*
import com.hytaledocs.intellij.assets.preview.AssetPreviewPanel
import com.hytaledocs.intellij.services.AssetScannerService
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.dnd.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.event.TreeSelectionEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Main panel for the Assets Explorer tab.
 * Provides a tree-based file browser for game assets with preview capabilities.
 */
class AssetsExplorerPanel(private val project: Project) : JBPanel<AssetsExplorerPanel>(BorderLayout()) {

    private val scannerService = AssetScannerService.getInstance(project)

    private var currentViewMode = AssetViewMode.BY_TYPE
    private var currentFilter: AssetType? = null
    private var searchQuery = ""

    // UI Components
    private val assetTree = Tree()
    private val previewPanel = AssetPreviewPanel()
    private val searchField = JBTextField()
    private val statusLabel = JBLabel("Ready")
    private val statsLabel = JBLabel("")
    private val viewToggleButton = JButton(AllIcons.Actions.GroupByPackage)
    private val refreshButton = JButton(AllIcons.Actions.Refresh)
    private val collapseButton = JButton(AllIcons.Actions.Collapseall)
    private val expandButton = JButton(AllIcons.Actions.Expandall)
    private val filterCombo = JComboBox<String>()

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.empty()

        // Main split pane
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.dividerLocation = JBUI.scale(280)
        splitPane.dividerSize = JBUI.scale(4)
        splitPane.isContinuousLayout = true
        splitPane.border = null

        // Left panel (tree)
        splitPane.leftComponent = createTreePanel()

        // Right panel (preview)
        splitPane.rightComponent = createPreviewPanel()

        add(splitPane, BorderLayout.CENTER)

        // Status bar
        add(createStatusBar(), BorderLayout.SOUTH)

        // Initial scan
        refreshAssets()
    }

    /**
     * Create the left panel containing the toolbar, search, and tree.
     */
    private fun createTreePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = null
        panel.minimumSize = Dimension(JBUI.scale(200), 0)
        panel.preferredSize = Dimension(JBUI.scale(280), 0)

        // Toolbar
        val toolbar = createToolbar()
        panel.add(toolbar, BorderLayout.NORTH)

        // Tree
        setupTree()
        val treeScroll = JBScrollPane(assetTree)
        treeScroll.border = null
        panel.add(treeScroll, BorderLayout.CENTER)

        // Setup drag and drop
        setupDragAndDrop()

        return panel
    }

    /**
     * Create the toolbar with buttons and search field.
     */
    private fun createToolbar(): JPanel {
        val toolbar = JPanel(BorderLayout())
        toolbar.isOpaque = false
        toolbar.border = JBUI.Borders.empty(8)

        // Button row
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        buttonRow.isOpaque = false

        // Refresh button
        refreshButton.toolTipText = "Refresh"
        refreshButton.addActionListener { refreshAssets(forceRefresh = true) }
        buttonRow.add(refreshButton)

        // Collapse all
        collapseButton.toolTipText = "Collapse All"
        collapseButton.addActionListener { collapseAll() }
        buttonRow.add(collapseButton)

        // Expand all
        expandButton.toolTipText = "Expand All"
        expandButton.addActionListener { expandAll() }
        buttonRow.add(expandButton)

        // View toggle
        viewToggleButton.toolTipText = "Toggle View Mode (By Type / By Folder)"
        viewToggleButton.addActionListener { toggleViewMode() }
        buttonRow.add(viewToggleButton)

        // Filter dropdown
        filterCombo.toolTipText = "Filter by Type"
        filterCombo.addItem("All Types")
        AssetType.entries.filter { it != AssetType.OTHER }.forEach {
            filterCombo.addItem(it.displayName)
        }
        filterCombo.addActionListener {
            val selected = filterCombo.selectedIndex
            currentFilter = if (selected == 0) null else AssetType.entries[selected - 1]
            applyFilter()
        }
        buttonRow.add(filterCombo)

        toolbar.add(buttonRow, BorderLayout.NORTH)

        // Search field
        val searchPanel = JPanel(BorderLayout())
        searchPanel.isOpaque = false
        searchPanel.border = JBUI.Borders.emptyTop(8)

        searchField.putClientProperty("JTextField.placeholderText", "Search assets...")
        searchField.border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(HytaleTheme.cardBorder, 6, 1),
            JBUI.Borders.empty(6, 8)
        )
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                searchQuery = searchField.text.trim().lowercase()
                applyFilter()
            }
        })
        searchPanel.add(searchField, BorderLayout.CENTER)

        toolbar.add(searchPanel, BorderLayout.SOUTH)

        return toolbar
    }

    /**
     * Setup the tree component.
     */
    private fun setupTree() {
        assetTree.isRootVisible = false
        assetTree.showsRootHandles = true
        assetTree.background = UIUtil.getPanelBackground()
        assetTree.isOpaque = false
        assetTree.cellRenderer = AssetTreeCellRenderer()
        assetTree.rowHeight = JBUI.scale(24)

        // Enable tooltips
        ToolTipManager.sharedInstance().registerComponent(assetTree)

        // Selection listener
        assetTree.addTreeSelectionListener { e: TreeSelectionEvent ->
            val node = assetTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val data = node?.userObject

            when (data) {
                is AssetNode.FileNode -> previewPanel.previewFile(data)
                else -> previewPanel.showNoSelection()
            }
        }

        // Double click to open
        assetTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = assetTree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val data = node.userObject as? AssetNode.FileNode ?: return

                    if (data.isInZip) {
                        // For ZIP files, show a message or extract
                        notify("File is inside ZIP. Right-click to extract.", NotificationType.INFORMATION)
                    } else {
                        openInEditor(data)
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })
    }

    /**
     * Setup drag and drop for importing assets.
     */
    private fun setupDragAndDrop() {
        DropTarget(assetTree, object : DropTargetAdapter() {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                if (dtde.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun drop(dtde: DropTargetDropEvent) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY)
                    val files = dtde.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>
                    importFiles(files.filterIsInstance<File>())
                    dtde.dropComplete(true)
                } catch (e: Exception) {
                    dtde.dropComplete(false)
                    notify("Failed to import files: ${e.message}", NotificationType.ERROR)
                }
            }
        })
    }

    /**
     * Create the preview panel container.
     */
    private fun createPreviewPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = null

        panel.add(previewPanel, BorderLayout.CENTER)

        return panel
    }

    /**
     * Create the status bar at the bottom.
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
     * Refresh the asset tree.
     */
    private fun refreshAssets(forceRefresh: Boolean = false) {
        statusLabel.text = "Scanning..."
        statusLabel.foreground = HytaleTheme.warningColor

        val scanFuture = when (currentViewMode) {
            AssetViewMode.BY_TYPE -> scannerService.scanByType(forceRefresh) { progress, message ->
                SwingUtilities.invokeLater {
                    statusLabel.text = message
                }
            }
            AssetViewMode.BY_FOLDER -> scannerService.scanByFolder(forceRefresh) { progress, message ->
                SwingUtilities.invokeLater {
                    statusLabel.text = message
                }
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val root = scanFuture.get()
            SwingUtilities.invokeLater {
                buildTree(root)
                updateStats()
                statusLabel.text = "Ready"
                statusLabel.foreground = HytaleTheme.successColor
            }
        }
    }

    /**
     * Build the tree model from the asset node hierarchy.
     */
    private fun buildTree(root: AssetNode.RootNode) {
        val treeRoot = DefaultMutableTreeNode(root)
        buildTreeNodes(treeRoot, root.children)
        assetTree.model = DefaultTreeModel(treeRoot)

        // Expand first level
        for (i in 0 until minOf(assetTree.rowCount, 8)) {
            assetTree.expandRow(i)
        }
    }

    /**
     * Recursively build tree nodes.
     */
    private fun buildTreeNodes(parent: DefaultMutableTreeNode, children: List<AssetNode>) {
        for (child in children) {
            // Apply filter
            if (!matchesFilter(child)) continue

            val node = DefaultMutableTreeNode(child)

            when (child) {
                is AssetNode.CategoryNode -> {
                    buildTreeNodes(node, child.children)
                    // Only add if it has visible children
                    if (node.childCount > 0 || (currentFilter == null && searchQuery.isEmpty())) {
                        parent.add(node)
                    }
                }
                is AssetNode.FolderNode -> {
                    buildTreeNodes(node, child.children)
                    // Only add if it has visible children
                    if (node.childCount > 0) {
                        parent.add(node)
                    }
                }
                is AssetNode.FileNode -> {
                    parent.add(node)
                }
                else -> parent.add(node)
            }
        }
    }

    /**
     * Check if a node matches the current filter and search query.
     */
    private fun matchesFilter(node: AssetNode): Boolean {
        return when (node) {
            is AssetNode.FileNode -> {
                val matchesType = currentFilter == null || node.assetType == currentFilter
                val matchesSearch = searchQuery.isEmpty() ||
                        node.displayName.lowercase().contains(searchQuery) ||
                        node.relativePath.lowercase().contains(searchQuery)
                matchesType && matchesSearch
            }
            is AssetNode.CategoryNode -> {
                currentFilter == null || node.assetType == currentFilter
            }
            else -> true
        }
    }

    /**
     * Apply filter and rebuild tree.
     */
    private fun applyFilter() {
        // Re-scan with cached data and apply filter
        val root = when (currentViewMode) {
            AssetViewMode.BY_TYPE -> scannerService.scanByType(false).get()
            AssetViewMode.BY_FOLDER -> scannerService.scanByFolder(false).get()
        }
        buildTree(root)
        updateStats()
    }

    /**
     * Update the stats label.
     */
    private fun updateStats() {
        val stats = scannerService.getStats()
        statsLabel.text = "${stats.totalFiles} files | ${stats.totalSizeFormatted}"
    }

    /**
     * Toggle between view modes.
     */
    private fun toggleViewMode() {
        currentViewMode = currentViewMode.toggle()
        viewToggleButton.toolTipText = "View: ${currentViewMode.displayName}"
        viewToggleButton.icon = when (currentViewMode) {
            AssetViewMode.BY_TYPE -> AllIcons.Actions.GroupByPackage
            AssetViewMode.BY_FOLDER -> AllIcons.Nodes.Folder
        }
        refreshAssets()
    }

    /**
     * Collapse all tree nodes.
     */
    private fun collapseAll() {
        for (i in assetTree.rowCount - 1 downTo 0) {
            assetTree.collapseRow(i)
        }
    }

    /**
     * Expand all tree nodes.
     */
    private fun expandAll() {
        for (i in 0 until assetTree.rowCount) {
            assetTree.expandRow(i)
        }
    }

    /**
     * Show context menu for tree items.
     */
    private fun showContextMenu(e: MouseEvent) {
        val path = assetTree.getPathForLocation(e.x, e.y) ?: return
        assetTree.selectionPath = path
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject

        val menu = JPopupMenu()

        when (data) {
            is AssetNode.FileNode -> {
                if (!data.isInZip) {
                    // Only show these options for non-ZIP files
                    menu.add(JMenuItem("Open in Editor").apply {
                        icon = AllIcons.Actions.MenuOpen
                        addActionListener { openInEditor(data) }
                    })
                    menu.add(JMenuItem("Reveal in Project").apply {
                        icon = AllIcons.Actions.ProjectDirectory
                        addActionListener { revealInProject(data) }
                    })
                    menu.addSeparator()
                }
                menu.add(JMenuItem("Copy Path").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener { copyPath(data) }
                })
                menu.add(JMenuItem("Copy Relative Path").apply {
                    addActionListener { copyRelativePath(data) }
                })
                if (data.isInZip) {
                    menu.addSeparator()
                    menu.add(JMenuItem("Extract to Resources").apply {
                        icon = AllIcons.Actions.Download
                        addActionListener { extractFromZip(data) }
                    })
                }
            }
            is AssetNode.FolderNode -> {
                menu.add(JMenuItem("Reveal in Project").apply {
                    icon = AllIcons.Actions.ProjectDirectory
                    addActionListener { revealFolderInProject(data) }
                })
            }
            is AssetNode.ZipNode -> {
                menu.add(JMenuItem("Reveal in Project").apply {
                    icon = AllIcons.Actions.ProjectDirectory
                    addActionListener { revealZipInProject(data) }
                })
            }
        }

        if (menu.componentCount > 0) {
            menu.show(assetTree, e.x, e.y)
        }
    }

    /**
     * Open a file in the editor.
     */
    private fun openInEditor(file: AssetNode.FileNode) {
        val virtualFile = file.virtualFile
            ?: file.file?.let { LocalFileSystem.getInstance().findFileByIoFile(it) }
            ?: return

        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    /**
     * Reveal a file in the project view.
     */
    private fun revealInProject(file: AssetNode.FileNode) {
        val virtualFile = file.virtualFile
            ?: file.file?.let { LocalFileSystem.getInstance().findFileByIoFile(it) }
            ?: return

        com.intellij.ide.projectView.impl.ProjectViewImpl.getInstance(project)
            .selectCB(virtualFile, virtualFile, true)
    }

    /**
     * Reveal a folder in the project view.
     */
    private fun revealFolderInProject(folder: AssetNode.FolderNode) {
        val virtualFile = folder.virtualFile
            ?: LocalFileSystem.getInstance().findFileByPath(folder.path)
            ?: return

        com.intellij.ide.projectView.impl.ProjectViewImpl.getInstance(project)
            .selectCB(virtualFile, virtualFile, true)
    }

    /**
     * Copy the absolute path to clipboard.
     */
    private fun copyPath(file: AssetNode.FileNode) {
        val path = if (file.isInZip && file.zipSource != null) {
            "${file.zipSource!!.zipFile.absolutePath}!/${file.zipSource!!.entryPath}"
        } else {
            file.file?.absolutePath ?: file.relativePath
        }
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(path), null)
        notify("Path copied to clipboard", NotificationType.INFORMATION)
    }

    /**
     * Copy the relative path to clipboard.
     */
    private fun copyRelativePath(file: AssetNode.FileNode) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(file.relativePath), null)
        notify("Relative path copied to clipboard", NotificationType.INFORMATION)
    }

    /**
     * Reveal a ZIP file in the project view.
     */
    private fun revealZipInProject(zip: AssetNode.ZipNode) {
        val virtualFile = zip.virtualFile
            ?: LocalFileSystem.getInstance().findFileByIoFile(zip.zipFile)
            ?: return

        com.intellij.ide.projectView.impl.ProjectViewImpl.getInstance(project)
            .selectCB(virtualFile, virtualFile, true)
    }

    /**
     * Extract a file from a ZIP archive to the resources directory.
     */
    private fun extractFromZip(file: AssetNode.FileNode) {
        if (!file.isInZip || file.zipSource == null) return

        val resourcesDir = scannerService.getResourcesDirectory() ?: run {
            notify("No resources directory found", NotificationType.WARNING)
            return
        }

        try {
            java.util.zip.ZipFile(file.zipSource!!.zipFile).use { zip ->
                val entry = zip.getEntry(file.zipSource!!.entryPath) ?: run {
                    notify("Entry not found in ZIP", NotificationType.ERROR)
                    return
                }

                // Determine destination path (preserve folder structure)
                val destPath = file.zipSource!!.entryPath
                val destFile = File(resourcesDir.path, destPath)

                // Create parent directories if needed
                destFile.parentFile?.mkdirs()

                // Extract file
                zip.getInputStream(entry).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                notify("Extracted to ${destFile.name}", NotificationType.INFORMATION)

                // Refresh to show the extracted file
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(destFile)
                refreshAssets(forceRefresh = true)
            }
        } catch (e: Exception) {
            notify("Failed to extract: ${e.message}", NotificationType.ERROR)
        }
    }

    /**
     * Import files to the resources directory.
     */
    private fun importFiles(files: List<File>) {
        val resourcesDir = scannerService.getResourcesDirectory() ?: run {
            notify("No resources directory found", NotificationType.WARNING)
            return
        }

        var imported = 0
        for (file in files) {
            try {
                val dest = File(resourcesDir.path, file.name)
                if (!dest.exists()) {
                    file.copyTo(dest)
                    imported++
                }
            } catch (e: Exception) {
                // Skip failed files
            }
        }

        if (imported > 0) {
            notify("Imported $imported file(s)", NotificationType.INFORMATION)
            refreshAssets(forceRefresh = true)
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hytale Plugin")
            .createNotification("Assets Explorer", message, type)
            .notify(project)
    }
}
