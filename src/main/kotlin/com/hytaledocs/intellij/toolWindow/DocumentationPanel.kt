package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.services.DocumentationApiClient
import com.hytaledocs.intellij.services.DocumentationService
import com.hytaledocs.intellij.services.OfflineDocsService
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Documentation panel with sidebar navigation and content rendering.
 * Fetches documentation from hytale-docs.com API and renders it inline.
 */
class DocumentationPanel(private val project: Project) : JBPanel<DocumentationPanel>(BorderLayout()) {

    private val apiClient = DocumentationApiClient.getInstance()
    private val docService = DocumentationService.getInstance()
    private val offlineService = OfflineDocsService.getInstance()

    private val searchField = JBTextField()
    private val sidebarTree = Tree()
    private val contentPane = JEditorPane()
    private val titleLabel = JBLabel("Documentation")
    private val loadingLabel = JBLabel("Loading...")
    private val breadcrumbLabel = JBLabel("")
    private val offlineModeLabel = JBLabel()
    private val offlineModeToggle = JCheckBox("Offline Mode")

    private var currentPath: String? = null
    private var useOfflineMode: Boolean = false

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.empty()

        // Main split pane
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.dividerLocation = JBUI.scale(220)
        splitPane.dividerSize = JBUI.scale(4)

        // Left sidebar
        splitPane.leftComponent = createSidebar()

        // Right content area
        splitPane.rightComponent = createContentArea()

        add(splitPane, BorderLayout.CENTER)

        // Check if offline docs are available
        val offlineStatus = offlineService.getCacheStatus()
        useOfflineMode = offlineStatus.available && offlineStatus.totalDocs > 0
        offlineModeToggle.isSelected = useOfflineMode
        updateOfflineModeLabel()

        // Load sidebar on init
        loadSidebar()
    }

    private fun updateOfflineModeLabel() {
        val status = offlineService.getCacheStatus()
        if (status.available && status.totalDocs > 0) {
            offlineModeLabel.text = "${status.totalDocs} docs"
            offlineModeLabel.foreground = HytaleTheme.successColor
            offlineModeToggle.isEnabled = true
        } else {
            offlineModeLabel.text = "No offline docs"
            offlineModeLabel.foreground = HytaleTheme.mutedText
            offlineModeToggle.isEnabled = false
            offlineModeToggle.isSelected = false
            useOfflineMode = false
        }
    }

    private fun createSidebar(): JPanel {
        val sidebarPanel = JPanel(BorderLayout())
        sidebarPanel.background = HytaleTheme.cardBackground
        sidebarPanel.border = BorderFactory.createMatteBorder(0, 0, 0, 1, HytaleTheme.cardBorder)
        sidebarPanel.minimumSize = Dimension(JBUI.scale(180), 0)

        // Search header
        val searchPanel = JPanel(BorderLayout())
        searchPanel.isOpaque = false
        searchPanel.border = JBUI.Borders.empty(8)

        searchField.putClientProperty("JTextField.placeholderText", "Search docs...")
        searchField.border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(HytaleTheme.cardBorder, 6, 1),
            JBUI.Borders.empty(6, 8)
        )
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    performSearch(searchField.text.trim())
                }
            }
        })
        searchPanel.add(searchField, BorderLayout.CENTER)

        sidebarPanel.add(searchPanel, BorderLayout.NORTH)

        // Tree
        sidebarTree.isRootVisible = false
        sidebarTree.showsRootHandles = true
        sidebarTree.background = HytaleTheme.cardBackground
        sidebarTree.isOpaque = true
        sidebarTree.cellRenderer = DocTreeCellRenderer()
        sidebarTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 || (e.clickCount == 1 && !sidebarTree.isCollapsed(sidebarTree.selectionPath))) {
                    val node = sidebarTree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    val item = node?.userObject as? SidebarNodeData
                    if (item?.href != null) {
                        loadDoc(item.href)
                    }
                }
            }
        })

        val treeScroll = JBScrollPane(sidebarTree)
        treeScroll.border = null
        sidebarPanel.add(treeScroll, BorderLayout.CENTER)

        // Footer with offline toggle and external link
        val footerPanel = JPanel(BorderLayout())
        footerPanel.isOpaque = false
        footerPanel.border = BorderFactory.createMatteBorder(1, 0, 0, 0, HytaleTheme.cardBorder)

        // Offline mode toggle
        val offlinePanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        offlinePanel.isOpaque = false

        offlineModeToggle.font = offlineModeToggle.font.deriveFont(JBUI.scaleFontSize(11f))
        offlineModeToggle.isOpaque = false
        offlineModeToggle.addActionListener {
            useOfflineMode = offlineModeToggle.isSelected
            loadSidebar() // Reload sidebar for new mode
        }
        offlinePanel.add(offlineModeToggle)

        offlineModeLabel.font = offlineModeLabel.font.deriveFont(JBUI.scaleFontSize(10f))
        offlinePanel.add(offlineModeLabel)

        footerPanel.add(offlinePanel, BorderLayout.NORTH)

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)))
        buttonsPanel.isOpaque = false

        val externalButton = JButton("Browser", AllIcons.Ide.External_link_arrow)
        externalButton.isFocusPainted = false
        externalButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        externalButton.font = externalButton.font.deriveFont(JBUI.scaleFontSize(11f))
        externalButton.addActionListener {
            val path = currentPath ?: ""
            docService.openInBrowser("${DocumentationService.BASE_URL}/docs/$path")
        }
        buttonsPanel.add(externalButton)

        footerPanel.add(buttonsPanel, BorderLayout.SOUTH)

        sidebarPanel.add(footerPanel, BorderLayout.SOUTH)

        return sidebarPanel
    }

    private fun createContentArea(): JPanel {
        val contentPanel = JPanel(BorderLayout())
        contentPanel.background = UIUtil.getPanelBackground()
        contentPanel.border = JBUI.Borders.empty()

        // Header with title and breadcrumb
        val headerPanel = JPanel(BorderLayout())
        headerPanel.isOpaque = false
        headerPanel.border = JBUI.Borders.empty(12, 16, 8, 16)

        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(18f))
        titleLabel.foreground = HytaleTheme.textPrimary
        headerPanel.add(titleLabel, BorderLayout.NORTH)

        breadcrumbLabel.foreground = HytaleTheme.mutedText
        breadcrumbLabel.font = breadcrumbLabel.font.deriveFont(JBUI.scaleFontSize(11f))
        breadcrumbLabel.border = JBUI.Borders.emptyTop(4)
        headerPanel.add(breadcrumbLabel, BorderLayout.SOUTH)

        contentPanel.add(headerPanel, BorderLayout.NORTH)

        // Content pane - HTML rendering
        // Set up HTMLEditorKit with custom stylesheet BEFORE setting contentType
        val editorKit = javax.swing.text.html.HTMLEditorKit()
        val styleSheet = javax.swing.text.html.StyleSheet()
        styleSheet.addRule(getCustomCss())
        editorKit.styleSheet = styleSheet
        contentPane.editorKit = editorKit
        contentPane.isEditable = false
        contentPane.background = UIUtil.getPanelBackground()
        contentPane.border = JBUI.Borders.empty(0, 16, 16, 16)

        // Handle links
        contentPane.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val url = e.url?.toString() ?: e.description ?: return@addHyperlinkListener

                if (url.startsWith("/docs/") || url.startsWith("docs/")) {
                    // Internal link - load in panel
                    loadDoc(url)
                } else if (url.startsWith("http")) {
                    // External link - open in browser
                    docService.openInBrowser(url)
                } else if (url.startsWith("#")) {
                    // Anchor - scroll to section
                    contentPane.scrollToReference(url.removePrefix("#"))
                }
            }
        }

        val contentScroll = JBScrollPane(contentPane)
        contentScroll.border = null
        contentScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        contentPanel.add(contentScroll, BorderLayout.CENTER)

        // Navigation footer
        contentPanel.add(createNavigationFooter(), BorderLayout.SOUTH)

        // Show welcome message initially
        showWelcome()

        return contentPanel
    }

    private fun createNavigationFooter(): JPanel {
        val navPanel = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(16), JBUI.scale(8)))
        navPanel.isOpaque = false
        navPanel.border = BorderFactory.createMatteBorder(1, 0, 0, 0, HytaleTheme.cardBorder)

        loadingLabel.foreground = HytaleTheme.mutedText
        loadingLabel.isVisible = false
        navPanel.add(loadingLabel)

        return navPanel
    }

    private fun getCustomCss(): String {
        val isDark = !JBColor.isBright()
        val textColor = if (isDark) "#bcbec4" else "#1f1f1f"
        val textMuted = if (isDark) "#8c8c8c" else "#6b7280"
        val linkColor = if (isDark) "#589df6" else "#3574f0"
        val codeBackground = if (isDark) "#1e1f22" else "#f6f8fa"
        val codeBorder = if (isDark) "#393b40" else "#d0d7de"
        val codeText = if (isDark) "#a9b7c6" else "#24292f"
        val headingColor = if (isDark) "#dfe1e5" else "#1f2328"
        val blockquoteBg = if (isDark) "#252629" else "#f0f4f8"
        val blockquoteBorder = if (isDark) "#589df6" else "#3574f0"
        val tableHeaderBg = if (isDark) "#1e1f22" else "#f6f8fa"

        // Java Swing HTML/CSS only supports a limited subset of CSS
        // Avoid: border-radius, line-height without unit, overflow, text-transform, letter-spacing, ::pseudo-elements
        return """
            body {
                font-family: sans-serif;
                font-size: 14pt;
                color: $textColor;
                margin: 0;
                padding: 8px;
            }
            h1, h2, h3, h4, h5, h6 {
                color: $headingColor;
                font-weight: bold;
                margin-top: 24px;
                margin-bottom: 12px;
            }
            h1 {
                font-size: 22pt;
                border-bottom-width: 2px;
                border-bottom-style: solid;
                border-bottom-color: $codeBorder;
                padding-bottom: 8px;
                margin-top: 0;
            }
            h2 {
                font-size: 18pt;
                border-bottom-width: 1px;
                border-bottom-style: solid;
                border-bottom-color: $codeBorder;
                padding-bottom: 6px;
            }
            h3 { font-size: 16pt; }
            h4 { font-size: 14pt; }
            h5, h6 { font-size: 12pt; color: $textMuted; }

            a { color: $linkColor; }

            p { margin: 12px 0; }

            strong, b { font-weight: bold; }

            em, i { font-style: italic; }

            code {
                background-color: $codeBackground;
                color: $codeText;
                padding: 2px 4px;
                font-family: monospace;
                font-size: 12pt;
            }

            pre {
                background-color: $codeBackground;
                border-width: 1px;
                border-style: solid;
                border-color: $codeBorder;
                padding: 12px;
                font-family: monospace;
                font-size: 12pt;
                margin: 16px 0;
            }

            blockquote {
                border-left-width: 4px;
                border-left-style: solid;
                border-left-color: $blockquoteBorder;
                margin: 16px 0;
                padding: 8px 16px;
                background-color: $blockquoteBg;
                color: $textMuted;
                font-style: italic;
            }

            table {
                border-collapse: collapse;
                width: 100%;
                margin: 16px 0;
                border-width: 1px;
                border-style: solid;
                border-color: $codeBorder;
            }

            th, td {
                padding: 8px 12px;
                text-align: left;
                border-bottom-width: 1px;
                border-bottom-style: solid;
                border-bottom-color: $codeBorder;
            }

            th {
                background-color: $tableHeaderBg;
                font-weight: bold;
                color: $headingColor;
            }

            ul, ol {
                padding-left: 24px;
                margin: 12px 0;
            }

            li { margin: 6px 0; }

            hr {
                border-width: 0;
                border-top-width: 1px;
                border-top-style: solid;
                border-top-color: $codeBorder;
                margin: 24px 0;
            }

            img { margin: 12px 0; }
        """.trimIndent()
    }

    private fun showWelcome() {
        val isDark = !JBColor.isBright()
        val bgColor = if (isDark) "#1e1e1e" else "#ffffff"

        titleLabel.text = "Hytale Documentation"
        breadcrumbLabel.text = ""

        contentPane.text = """
            <html>
            <head><style>${getCustomCss()}</style></head>
            <body style="background: $bgColor;">
                <h1>Welcome to Hytale Docs</h1>
                <p>Browse the documentation using the sidebar on the left, or search for specific topics.</p>

                <h2>Quick Links</h2>
                <ul>
                    <li><a href="/docs/getting-started/introduction">Getting Started</a></li>
                    <li><a href="/docs/modding/plugins/overview">Plugin Development</a></li>
                    <li><a href="/docs/modding/plugins/events/overview">Events System</a></li>
                    <li><a href="/docs/api/server-internals/custom-ui">Custom UI</a></li>
                    <li><a href="/docs/servers/overview">Server Setup</a></li>
                </ul>

                <h2>Features</h2>
                <ul>
                    <li>Browse documentation directly in your IDE</li>
                    <li>Search across all docs</li>
                    <li>Click links to navigate between pages</li>
                    <li>Press F1 on Hytale classes for context help</li>
                </ul>
            </body>
            </html>
        """.trimIndent()
    }

    private fun loadSidebar() {
        if (useOfflineMode) {
            // Load from offline cache
            ApplicationManager.getApplication().executeOnPooledThread {
                val categories = offlineService.getCategories()
                val docsByCategory = categories.associateWith { offlineService.listDocs(it) }

                SwingUtilities.invokeLater {
                    if (docsByCategory.isNotEmpty()) {
                        buildTreeFromOffline(docsByCategory)
                    } else {
                        buildStaticTree()
                    }
                }
            }
        } else {
            // Load from API
            ApplicationManager.getApplication().executeOnPooledThread {
                apiClient.fetchSidebar("en").thenAccept { response ->
                    SwingUtilities.invokeLater {
                        if (response != null) {
                            buildTree(response.sidebar)
                        } else {
                            // Fallback to static sidebar
                            buildStaticTree()
                        }
                    }
                }
            }
        }
    }

    private fun buildTreeFromOffline(docsByCategory: Map<String, List<OfflineDocsService.DocEntry>>) {
        val root = DefaultMutableTreeNode("Documentation")

        for ((category, docs) in docsByCategory.toSortedMap()) {
            val categoryNode = DefaultMutableTreeNode(SidebarNodeData(category, null))

            // Group by subcategory (first path segment after category)
            val grouped = docs.groupBy { doc ->
                val parts = doc.slug.split("/")
                if (parts.size > 1) parts[1] else ""
            }

            for ((subcat, subdocs) in grouped.toSortedMap()) {
                if (subcat.isEmpty()) {
                    // Root level docs in category
                    for (doc in subdocs.sortedBy { it.title }) {
                        categoryNode.add(DefaultMutableTreeNode(
                            SidebarNodeData(doc.title, "/docs/${doc.slug}")
                        ))
                    }
                } else {
                    // Subcategory
                    val subcatName = subcat.replaceFirstChar { it.uppercase() }.replace("-", " ")
                    val subcatNode = DefaultMutableTreeNode(SidebarNodeData(subcatName, null))

                    for (doc in subdocs.sortedBy { it.title }) {
                        subcatNode.add(DefaultMutableTreeNode(
                            SidebarNodeData(doc.title, "/docs/${doc.slug}")
                        ))
                    }

                    if (subcatNode.childCount > 0) {
                        categoryNode.add(subcatNode)
                    }
                }
            }

            if (categoryNode.childCount > 0) {
                root.add(categoryNode)
            }
        }

        sidebarTree.model = DefaultTreeModel(root)

        // Expand first level
        for (i in 0 until sidebarTree.rowCount.coerceAtMost(5)) {
            sidebarTree.expandRow(i)
        }
    }

    private fun buildTree(items: List<DocumentationApiClient.SidebarItem>) {
        val root = DefaultMutableTreeNode("Documentation")

        fun addItems(parent: DefaultMutableTreeNode, sidebarItems: List<DocumentationApiClient.SidebarItem>) {
            for (item in sidebarItems) {
                val node = DefaultMutableTreeNode(SidebarNodeData(item.title, item.href, item.verified))
                parent.add(node)
                if (item.items != null) {
                    addItems(node, item.items)
                }
            }
        }

        addItems(root, items)
        sidebarTree.model = DefaultTreeModel(root)

        // Expand first level
        for (i in 0 until sidebarTree.rowCount.coerceAtMost(5)) {
            sidebarTree.expandRow(i)
        }
    }

    private fun buildStaticTree() {
        // Fallback static tree if API fails
        val root = DefaultMutableTreeNode("Documentation")

        val gettingStarted = DefaultMutableTreeNode(SidebarNodeData("Getting Started", null))
        gettingStarted.add(DefaultMutableTreeNode(SidebarNodeData("Introduction", "/docs/getting-started/introduction")))
        gettingStarted.add(DefaultMutableTreeNode(SidebarNodeData("Prerequisites", "/docs/getting-started/prerequisites")))
        gettingStarted.add(DefaultMutableTreeNode(SidebarNodeData("First Mod", "/docs/getting-started/first-mod")))
        root.add(gettingStarted)

        val modding = DefaultMutableTreeNode(SidebarNodeData("Modding", null))
        val plugins = DefaultMutableTreeNode(SidebarNodeData("Plugins", null))
        plugins.add(DefaultMutableTreeNode(SidebarNodeData("Overview", "/docs/modding/plugins/overview")))
        plugins.add(DefaultMutableTreeNode(SidebarNodeData("Project Setup", "/docs/modding/plugins/project-setup")))
        plugins.add(DefaultMutableTreeNode(SidebarNodeData("Events", "/docs/modding/plugins/events/overview")))
        plugins.add(DefaultMutableTreeNode(SidebarNodeData("Commands", "/docs/modding/plugins/commands")))
        modding.add(plugins)
        root.add(modding)

        val servers = DefaultMutableTreeNode(SidebarNodeData("Servers", null))
        servers.add(DefaultMutableTreeNode(SidebarNodeData("Overview", "/docs/servers/overview")))
        servers.add(DefaultMutableTreeNode(SidebarNodeData("Setup", "/docs/servers/setup/installation")))
        root.add(servers)

        sidebarTree.model = DefaultTreeModel(root)
        sidebarTree.expandRow(0)
        sidebarTree.expandRow(1)
    }

    private fun loadDoc(path: String) {
        val normalizedPath = path.removePrefix("/docs/").removePrefix("docs/")
        currentPath = normalizedPath

        loadingLabel.text = "Loading..."
        loadingLabel.isVisible = true
        titleLabel.text = "Loading..."
        breadcrumbLabel.text = normalizedPath.replace("/", " > ")

        if (useOfflineMode) {
            // Load from offline cache
            ApplicationManager.getApplication().executeOnPooledThread {
                val doc = offlineService.getDoc(normalizedPath)

                SwingUtilities.invokeLater {
                    loadingLabel.isVisible = false

                    if (doc != null) {
                        titleLabel.text = doc.title
                        breadcrumbLabel.text = doc.slug.replace("/", " > ")

                        // Convert markdown to HTML
                        val htmlContent = markdownToHtml(doc.content)

                        val isDark = !JBColor.isBright()
                        val bgColor = if (isDark) "#1e1e1e" else "#ffffff"

                        contentPane.text = """
                            <html>
                            <head><style>${getCustomCss()}</style></head>
                            <body style="background: $bgColor;">
                            <h1>${doc.title}</h1>
                            ${if (doc.description != null) "<p class=\"description\">${doc.description}</p>" else ""}
                            $htmlContent
                            </body>
                            </html>
                        """.trimIndent()

                        contentPane.caretPosition = 0
                    } else {
                        showError("Document not found in offline cache", normalizedPath)
                    }
                }
            }
        } else {
            // Load from API
            ApplicationManager.getApplication().executeOnPooledThread {
                apiClient.fetchDoc(normalizedPath, "en").thenAccept { response ->
                    SwingUtilities.invokeLater {
                        loadingLabel.isVisible = false

                        if (response != null) {
                            titleLabel.text = response.meta.title
                            breadcrumbLabel.text = response.slug.replace("/", " > ")

                            val isDark = !JBColor.isBright()
                            val bgColor = if (isDark) "#1e1e1e" else "#ffffff"

                            contentPane.text = """
                                <html>
                                <head><style>${getCustomCss()}</style></head>
                                <body style="background: $bgColor;">
                                ${response.content}
                                </body>
                                </html>
                            """.trimIndent()

                            contentPane.caretPosition = 0
                        } else {
                            showError("Failed to load documentation", normalizedPath)
                        }
                    }
                }
            }
        }
    }

    /**
     * Simple markdown to HTML converter.
     * Note: Java Swing's JEditorPane only supports HTML 3.2 and limited CSS.
     */
    private fun markdownToHtml(markdown: String): String {
        val lines = markdown.lines()
        val result = StringBuilder()
        var inCodeBlock = false
        var codeBlockContent = StringBuilder()
        var listType: String? = null  // "ul" or "ol" or null
        var inBlockquote = false

        fun closeList() {
            if (listType != null) {
                result.append("</$listType>\n")
                listType = null
            }
        }

        fun closeBlockquote() {
            if (inBlockquote) {
                result.append("</blockquote>\n")
                inBlockquote = false
            }
        }

        for (line in lines) {
            // Handle code blocks
            if (line.startsWith("```")) {
                if (inCodeBlock) {
                    // End code block
                    val code = codeBlockContent.toString()
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                    result.append("<pre><code>$code</code></pre>\n")
                    inCodeBlock = false
                    codeBlockContent = StringBuilder()
                } else {
                    // Start code block - close any open blocks first
                    closeList()
                    closeBlockquote()
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeBlockContent.append(line).append("\n")
                continue
            }

            var processedLine = line

            // Inline code (before other processing)
            processedLine = processedLine.replace(Regex("`([^`]+)`")) { match ->
                val code = match.groupValues[1]
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                "<code>$code</code>"
            }

            // Bold and italic (order matters: *** before ** before *)
            processedLine = processedLine.replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*")) {
                "<b><i>${it.groupValues[1]}</i></b>"
            }
            processedLine = processedLine.replace(Regex("\\*\\*(.+?)\\*\\*")) {
                "<b>${it.groupValues[1]}</b>"
            }
            processedLine = processedLine.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")) {
                "<i>${it.groupValues[1]}</i>"
            }

            // Links
            processedLine = processedLine.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")) { match ->
                val text = match.groupValues[1]
                val url = match.groupValues[2]
                "<a href=\"$url\">$text</a>"
            }

            // Check line type
            val trimmedLine = processedLine.trim()

            // Headers
            when {
                trimmedLine.startsWith("######") -> {
                    closeList()
                    closeBlockquote()
                    result.append("<h6>${trimmedLine.removePrefix("######").trim()}</h6>\n")
                }
                trimmedLine.startsWith("#####") -> {
                    closeList()
                    closeBlockquote()
                    result.append("<h5>${trimmedLine.removePrefix("#####").trim()}</h5>\n")
                }
                trimmedLine.startsWith("####") -> {
                    closeList()
                    closeBlockquote()
                    result.append("<h4>${trimmedLine.removePrefix("####").trim()}</h4>\n")
                }
                trimmedLine.startsWith("###") -> {
                    closeList()
                    closeBlockquote()
                    result.append("<h3>${trimmedLine.removePrefix("###").trim()}</h3>\n")
                }
                trimmedLine.startsWith("##") -> {
                    closeList()
                    closeBlockquote()
                    result.append("<h2>${trimmedLine.removePrefix("##").trim()}</h2>\n")
                }
                trimmedLine.startsWith("#") -> {
                    closeList()
                    closeBlockquote()
                    result.append("<h1>${trimmedLine.removePrefix("#").trim()}</h1>\n")
                }
                // Unordered list items
                trimmedLine.matches(Regex("^[-*+]\\s+.*")) -> {
                    closeBlockquote()
                    // If switching from ordered to unordered, close the ordered list
                    if (listType == "ol") {
                        closeList()
                    }
                    if (listType == null) {
                        result.append("<ul>\n")
                        listType = "ul"
                    }
                    val content = trimmedLine.replaceFirst(Regex("^[-*+]\\s+"), "")
                    result.append("<li>$content</li>\n")
                }
                // Ordered list items
                trimmedLine.matches(Regex("^\\d+\\.\\s+.*")) -> {
                    closeBlockquote()
                    // If switching from unordered to ordered, close the unordered list
                    if (listType == "ul") {
                        closeList()
                    }
                    if (listType == null) {
                        result.append("<ol>\n")
                        listType = "ol"
                    }
                    val content = trimmedLine.replaceFirst(Regex("^\\d+\\.\\s+"), "")
                    result.append("<li>$content</li>\n")
                }
                // Blockquotes
                trimmedLine.startsWith(">") -> {
                    closeList()
                    if (!inBlockquote) {
                        result.append("<blockquote>\n")
                        inBlockquote = true
                    }
                    val content = trimmedLine.removePrefix(">").trim()
                    result.append("$content ")
                }
                // Horizontal rule
                trimmedLine.matches(Regex("^[-*_]{3,}$")) -> {
                    closeList()
                    closeBlockquote()
                    result.append("<hr>\n")
                }
                // Empty line
                trimmedLine.isEmpty() -> {
                    closeList()
                    closeBlockquote()
                }
                // Regular paragraph text
                else -> {
                    closeList()
                    closeBlockquote()
                    result.append("<p>$processedLine</p>\n")
                }
            }
        }

        // Close any remaining open blocks
        closeList()
        closeBlockquote()
        if (inCodeBlock) {
            val code = codeBlockContent.toString()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            result.append("<pre><code>$code</code></pre>\n")
        }

        return result.toString()
    }

    private fun showError(message: String, path: String) {
        titleLabel.text = "Error"
        breadcrumbLabel.text = path

        val isDark = !JBColor.isBright()
        val bgColor = if (isDark) "#1e1e1e" else "#ffffff"

        contentPane.text = """
            <html>
            <head><style>${getCustomCss()}</style></head>
            <body style="background: $bgColor;">
                <h1>Error</h1>
                <p>$message</p>
                <p>Path: $path</p>
                <p>Try:</p>
                <ul>
                    <li>Check your internet connection</li>
                    <li>Make sure hytale-docs.com is accessible</li>
                    <li><a href="https://hytale-docs.com/docs/$path">Open in browser</a></li>
                </ul>
            </body>
            </html>
        """.trimIndent()
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return

        loadingLabel.text = "Searching..."
        loadingLabel.isVisible = true
        titleLabel.text = "Search Results"
        breadcrumbLabel.text = "Search: $query"

        if (useOfflineMode) {
            // Search offline cache
            ApplicationManager.getApplication().executeOnPooledThread {
                val results = offlineService.searchDocs(query)

                SwingUtilities.invokeLater {
                    loadingLabel.isVisible = false
                    showOfflineSearchResults(query, results)
                }
            }
        } else {
            // Search via API
            ApplicationManager.getApplication().executeOnPooledThread {
                apiClient.searchDocs(query, "en").thenAccept { results ->
                    SwingUtilities.invokeLater {
                        loadingLabel.isVisible = false
                        showSearchResults(query, results)
                    }
                }
            }
        }
    }

    private fun showOfflineSearchResults(query: String, results: List<OfflineDocsService.DocEntry>) {
        val isDark = !JBColor.isBright()
        val bgColor = if (isDark) "#1e1e1e" else "#ffffff"

        val resultsHtml = if (results.isEmpty()) {
            "<p>No results found for \"$query\"</p>"
        } else {
            results.joinToString("\n") { result ->
                """
                <div style="margin-bottom: 16px; padding: 12px; background: ${if (isDark) "#2d2d2d" else "#f5f5f5"}; border-radius: 6px;">
                    <a href="/docs/${result.slug}" style="font-weight: 600; font-size: 14px;">${result.title}</a>
                    <span style="color: #888; margin-left: 8px;">${result.category}</span>
                    ${if (result.description != null) "<p style=\"margin: 8px 0 0 0; color: #888;\">${result.description}</p>" else ""}
                </div>
                """.trimIndent()
            }
        }

        contentPane.text = """
            <html>
            <head><style>${getCustomCss()}</style></head>
            <body style="background: $bgColor;">
                <h1>Search Results (Offline)</h1>
                <p>Found ${results.size} result(s) for "<b>$query</b>"</p>
                <hr/>
                $resultsHtml
            </body>
            </html>
        """.trimIndent()
    }

    private fun showSearchResults(query: String, results: List<DocumentationApiClient.SearchResult>) {
        val isDark = !JBColor.isBright()
        val bgColor = if (isDark) "#1e1e1e" else "#ffffff"

        val resultsHtml = if (results.isEmpty()) {
            "<p>No results found for \"$query\"</p>"
        } else {
            results.joinToString("\n") { result ->
                """
                <div style="margin-bottom: 16px; padding: 12px; background: ${if (isDark) "#2d2d2d" else "#f5f5f5"}; border-radius: 6px;">
                    <a href="${result.href}" style="font-weight: 600; font-size: 14px;">${result.title}</a>
                    ${if (result.category != null) "<span style=\"color: #888; margin-left: 8px;\">${result.category}</span>" else ""}
                    ${if (result.excerpt != null) "<p style=\"margin: 8px 0 0 0; color: #888;\">${result.excerpt}</p>" else ""}
                </div>
                """.trimIndent()
            }
        }

        contentPane.text = """
            <html>
            <head><style>${getCustomCss()}</style></head>
            <body style="background: $bgColor;">
                <h1>Search Results</h1>
                <p>Found ${results.size} result(s) for "<b>$query</b>"</p>
                <hr/>
                $resultsHtml
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Load documentation for a specific class (used by F1 help).
     */
    fun loadDocForClass(className: String) {
        val url = docService.getDocUrlForClass(className)
        if (url != null) {
            val path = url.removePrefix(DocumentationService.BASE_URL).removePrefix("/")
            loadDoc(path)
        }
    }

    // Data class for tree nodes
    data class SidebarNodeData(
        val title: String,
        val href: String?,
        val verified: Boolean = false
    ) {
        override fun toString(): String = title
    }

    // Custom tree cell renderer - fixes background color issue
    inner class DocTreeCellRenderer : DefaultTreeCellRenderer() {

        init {
            // Make renderer transparent to fix color mismatch
            isOpaque = false
            backgroundNonSelectionColor = null
            backgroundSelectionColor = UIUtil.getTreeSelectionBackground(true)
            borderSelectionColor = null
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
            // Don't call super to avoid default background behavior
            val node = value as? DefaultMutableTreeNode
            val data = node?.userObject as? SidebarNodeData

            if (data != null) {
                text = data.title
                icon = when {
                    data.verified -> AllIcons.Actions.Checked
                    data.href != null && leaf -> AllIcons.FileTypes.Any_type
                    !leaf -> if (expanded) AllIcons.Nodes.Folder else AllIcons.Nodes.Folder
                    else -> AllIcons.Nodes.Folder
                }

                // Set colors based on selection and link status
                if (sel) {
                    foreground = UIUtil.getTreeSelectionForeground(true)
                    background = UIUtil.getTreeSelectionBackground(true)
                    isOpaque = true
                } else {
                    foreground = if (data.href != null) HytaleTheme.accentColor else HytaleTheme.textPrimary
                    background = null
                    isOpaque = false
                }
            } else {
                text = value?.toString() ?: ""
                icon = AllIcons.Nodes.Folder
                foreground = HytaleTheme.textPrimary
                background = null
                isOpaque = false
            }

            return this
        }
    }
}
