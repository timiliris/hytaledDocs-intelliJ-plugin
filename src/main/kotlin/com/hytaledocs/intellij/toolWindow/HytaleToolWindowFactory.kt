package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.services.*
import com.hytaledocs.intellij.settings.AuthMode
import com.hytaledocs.intellij.settings.HytaleServerSettings
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.EmptyBorder

class HytaleToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HytaleToolWindowPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        Disposer.register(toolWindow.disposable, panel)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

class HytaleToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : JBPanel<HytaleToolWindowPanel>(BorderLayout()), Disposable {

    private val settings = HytaleServerSettings.getInstance(project)
    private val javaService = JavaInstallService.getInstance()
    private val downloaderService = HytaleDownloaderService.getInstance()
    private val authService = AuthenticationService.getInstance()

    // Authentication panel
    private lateinit var authPanel: AuthenticationPanel

    // Status labels
    private val javaStatusLabel = JBLabel("Checking...")
    private val serverJarStatusLabel = JBLabel("Checking...")
    private val serverStatusLabel = JBLabel("STOPPED")
    private val authModeLabel = JBLabel("")

    // Stats labels
    private val uptimeLabel = JBLabel("--:--:--")
    private val playersLabel = JBLabel("0")
    private val playerListLabel = JBLabel("None")
    private val authStatusLabel = JBLabel("")

    // Stats update timer
    private var statsTimer: Timer? = null

    // Buttons - using standard IntelliJ buttons with hover
    private val installJavaButton = HytaleTheme.createButton("Install Java 25", AllIcons.Actions.Download)
    private val downloadServerButton = HytaleTheme.createButton("Download Server", AllIcons.Actions.Download)
    private val startServerButton = HytaleTheme.createButton("Start", AllIcons.Actions.Execute)
    private val stopServerButton = HytaleTheme.createButton("Stop", AllIcons.Actions.Suspend)

    // Console
    private var consolePane: JTextPane? = null
    private val commandField = JBTextField()

    // ANSI escape code regex
    private val ansiRegex = Regex("\u001B\\[[0-9;]*[a-zA-Z]")
    private val serverLogRegex = Regex("""^\[[\d/]+\s+[\d:]+\s+(\w+)\]\s+(.*)$""")

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.empty()

        val tabbedPane = JBTabbedPane()
        tabbedPane.tabComponentInsets = JBUI.insets(0)

        tabbedPane.addTab("Server", createServerTab())
        tabbedPane.addTab("Console", createConsoleTab())
        tabbedPane.addTab("Assets", AssetsExplorerPanel(project))
        tabbedPane.addTab("Docs", DocumentationPanel(project))
        tabbedPane.addTab("AI", AIAssistantPanel(project))
        tabbedPane.addTab("Infos", createResourcesTab())

        add(tabbedPane, BorderLayout.CENTER)

        refreshStatus()

        statsTimer = Timer(1000) { updateStats() }
        statsTimer?.start()

        // Register for authentication events
        authService.registerCallback(project) { session ->
            SwingUtilities.invokeLater {
                authPanel.updateSession(session)
                updateAuthStatusLabel(session)
                logAuthEvent(session)
            }
        }
    }

    private fun logAuthEvent(session: AuthenticationService.AuthSession) {
        when (session.state) {
            AuthenticationService.AuthState.AWAITING_CODE -> {
                log("[Auth] Waiting for authentication code...", isSystemMessage = true)
            }
            AuthenticationService.AuthState.CODE_DISPLAYED -> {
                log("[Auth] Device code: ${session.deviceCode}", isSystemMessage = true)
                log("[Auth] Enter code at: ${session.verificationUrl}", isSystemMessage = true)
            }
            AuthenticationService.AuthState.AUTHENTICATING -> {
                log("[Auth] Authenticating...", isSystemMessage = true)
            }
            AuthenticationService.AuthState.SUCCESS -> {
                log("[Auth] Authentication successful!", isSystemMessage = true)
            }
            AuthenticationService.AuthState.FAILED -> {
                log("[Auth] Authentication failed: ${session.message}", isSystemMessage = true)
            }
            else -> {}
        }
    }

    private fun updateAuthStatusLabel(session: AuthenticationService.AuthSession?) {
        when (session?.state) {
            AuthenticationService.AuthState.AWAITING_CODE -> {
                authStatusLabel.text = "Waiting..."
                authStatusLabel.foreground = HytaleTheme.warningColor
            }
            AuthenticationService.AuthState.CODE_DISPLAYED -> {
                authStatusLabel.text = "Code: ${session.deviceCode}"
                authStatusLabel.foreground = HytaleTheme.warningColor
            }
            AuthenticationService.AuthState.SUCCESS -> {
                authStatusLabel.text = "Authenticated"
                authStatusLabel.foreground = HytaleTheme.successColor
            }
            AuthenticationService.AuthState.FAILED -> {
                authStatusLabel.text = "Failed"
                authStatusLabel.foreground = HytaleTheme.errorColor
            }
            else -> {
                authStatusLabel.text = ""
            }
        }
    }

    private fun createStatusBadge(text: String, color: Color): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            val badge = JLabel(text).apply {
                foreground = color
                font = font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(11f))
                border = BorderFactory.createCompoundBorder(
                    RoundedLineBorder(color, 4, 1),
                    JBUI.Borders.empty(2, 6)
                )
            }
            add(badge)
        }
    }

    // ==================== SERVER TAB ====================

    private fun createServerTab(): JPanel {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        mainPanel.border = JBUI.Borders.empty(12)

        // Auth panel at top (initially hidden)
        authPanel = AuthenticationPanel()
        mainPanel.add(authPanel, BorderLayout.NORTH)

        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = false

        // Status Card
        contentPanel.add(createStatusCard())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Server Controls Card
        contentPanel.add(createControlsCard())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Live Stats Card
        contentPanel.add(createStatsCard())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Quick Settings Card
        contentPanel.add(createQuickSettingsCard())

        // Push content to top
        contentPanel.add(Box.createVerticalGlue())

        val scrollPane = JBScrollPane(contentPanel)
        scrollPane.border = null
        scrollPane.viewportBorder = null
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun createStatusCard(): JPanel {
        val card = HytaleTheme.createCard("Environment Status")
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)

        // Java Row
        val javaRow = createStatusRow("Java 25", javaStatusLabel, installJavaButton)
        card.add(javaRow)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Server Row
        val serverRow = createStatusRow("Server Files", serverJarStatusLabel, downloadServerButton)
        card.add(serverRow)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Server Status Row
        val statusRow = createStatusRow("Server Status", serverStatusLabel, null)
        card.add(statusRow)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Auth Mode Row
        val authRow = createStatusRow("Auth Mode", authModeLabel, null)
        card.add(authRow)

        // Button actions
        installJavaButton.addActionListener { installJava() }
        downloadServerButton.addActionListener { downloadServer() }

        return card
    }

    private fun createStatusRow(label: String, valueLabel: JLabel, actionButton: JButton?): JPanel {
        val row = JPanel(BorderLayout(JBUI.scale(12), 0))
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))

        val labelComponent = JLabel(label)
        labelComponent.foreground = HytaleTheme.mutedText
        labelComponent.preferredSize = Dimension(JBUI.scale(100), labelComponent.preferredSize.height)
        row.add(labelComponent, BorderLayout.WEST)

        valueLabel.font = valueLabel.font.deriveFont(Font.BOLD)
        row.add(valueLabel, BorderLayout.CENTER)

        if (actionButton != null) {
            actionButton.isVisible = false
            row.add(actionButton, BorderLayout.EAST)
        }

        return row
    }

    private fun createControlsCard(): JPanel {
        val card = HytaleTheme.createCard("Server Controls")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(80))

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        buttonsPanel.isOpaque = false
        buttonsPanel.alignmentX = Component.LEFT_ALIGNMENT

        buttonsPanel.add(startServerButton)
        buttonsPanel.add(stopServerButton)
        buttonsPanel.add(HytaleTheme.createButton("Refresh", AllIcons.Actions.Refresh).apply {
            addActionListener {
                refreshStatus()
                notify("Status refreshed", NotificationType.INFORMATION)
            }
        })
        buttonsPanel.add(HytaleTheme.createButton("Settings", AllIcons.General.Settings).apply {
            addActionListener {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Hytale Server")
            }
        })

        stopServerButton.isEnabled = false
        startServerButton.addActionListener { startServer() }
        stopServerButton.addActionListener { stopServer() }

        card.add(buttonsPanel)
        return card
    }

    private fun createStatsCard(): JPanel {
        val card = HytaleTheme.createCard("Live Statistics")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(140))

        val statsGrid = JPanel(GridLayout(4, 2, JBUI.scale(16), JBUI.scale(8)))
        statsGrid.isOpaque = false
        statsGrid.alignmentX = Component.LEFT_ALIGNMENT

        // Uptime
        statsGrid.add(createStatLabel("Uptime"))
        uptimeLabel.font = uptimeLabel.font.deriveFont(Font.BOLD)
        statsGrid.add(uptimeLabel)

        // Auth Status
        statsGrid.add(createStatLabel("Auth"))
        statsGrid.add(authStatusLabel)

        // Players
        statsGrid.add(createStatLabel("Players"))
        playersLabel.font = playersLabel.font.deriveFont(Font.BOLD)
        statsGrid.add(playersLabel)

        // Online
        statsGrid.add(createStatLabel("Online"))
        playerListLabel.font = playerListLabel.font.deriveFont(Font.ITALIC)
        playerListLabel.foreground = HytaleTheme.mutedText
        statsGrid.add(playerListLabel)

        card.add(statsGrid)
        return card
    }

    private fun createStatLabel(text: String): JLabel = PanelUtils.createStatLabel(text)

    private fun createQuickSettingsCard(): JPanel {
        val card = HytaleTheme.createCard("Quick Settings")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(120))

        val settingsGrid = JPanel(GridBagLayout())
        settingsGrid.isOpaque = false
        settingsGrid.alignmentX = Component.LEFT_ALIGNMENT

        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4, 0)
        }

        // Auth Mode
        gbc.gridx = 0; gbc.gridy = 0
        settingsGrid.add(createStatLabel("Authentication"), gbc)
        gbc.gridx = 1
        gbc.insets = JBUI.insets(4, 12, 4, 0)
        val authCombo = JComboBox(AuthMode.entries.map { it.displayName }.toTypedArray())
        authCombo.selectedIndex = if (settings.authMode == AuthMode.AUTHENTICATED) 0 else 1
        authCombo.addActionListener {
            settings.authMode = if (authCombo.selectedIndex == 0) AuthMode.AUTHENTICATED else AuthMode.OFFLINE
            refreshStatus()
        }
        settingsGrid.add(authCombo, gbc)

        // Memory
        gbc.gridx = 0; gbc.gridy = 1
        gbc.insets = JBUI.insets(4, 0)
        settingsGrid.add(createStatLabel("Memory"), gbc)
        gbc.gridx = 1
        gbc.insets = JBUI.insets(4, 12, 4, 0)
        val memoryLabel = JLabel("${settings.minMemory} - ${settings.maxMemory}")
        memoryLabel.font = memoryLabel.font.deriveFont(Font.BOLD)
        settingsGrid.add(memoryLabel, gbc)

        // Port
        gbc.gridx = 0; gbc.gridy = 2
        gbc.insets = JBUI.insets(4, 0)
        settingsGrid.add(createStatLabel("Port"), gbc)
        gbc.gridx = 1
        gbc.insets = JBUI.insets(4, 12, 4, 0)
        val portLabel = JLabel("${settings.port} (UDP/QUIC)")
        portLabel.font = portLabel.font.deriveFont(Font.BOLD)
        settingsGrid.add(portLabel, gbc)

        card.add(settingsGrid)
        return card
    }

    // ==================== CONSOLE TAB ====================

    private fun createConsoleTab(): JPanel {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        mainPanel.border = JBUI.Borders.empty(12)

        // Console area with card styling
        val consoleCard = JPanel(BorderLayout())
        consoleCard.background = HytaleTheme.cardBackground
        consoleCard.border = RoundedLineBorder(HytaleTheme.cardBorder, 8, 1)

        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        toolbar.isOpaque = false
        toolbar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, HytaleTheme.cardBorder),
            JBUI.Borders.empty(4, 8)
        )
        toolbar.add(HytaleTheme.createButton("Clear", AllIcons.Actions.GC).apply {
            addActionListener { consolePane?.text = "" }
        })
        toolbar.add(HytaleTheme.createButton("Copy All", AllIcons.Actions.Copy).apply {
            addActionListener {
                consolePane?.let {
                    it.selectAll()
                    it.copy()
                    it.select(0, 0)
                }
            }
        })
        consoleCard.add(toolbar, BorderLayout.NORTH)

        // Console text area
        val editorColors = EditorColorsManager.getInstance().globalScheme
        consolePane = JTextPane().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
            background = editorColors.defaultBackground
            foreground = editorColors.defaultForeground
            border = JBUI.Borders.empty(8)
        }

        val scrollPane = JBScrollPane(consolePane)
        scrollPane.border = null
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
        consoleCard.add(scrollPane, BorderLayout.CENTER)

        // Command input
        val commandPanel = JPanel(BorderLayout(JBUI.scale(8), 0))
        commandPanel.isOpaque = false
        commandPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, HytaleTheme.cardBorder),
            JBUI.Borders.empty(8)
        )

        val promptLabel = JLabel(">")
        promptLabel.foreground = HytaleTheme.accentColor
        promptLabel.font = promptLabel.font.deriveFont(Font.BOLD)
        commandPanel.add(promptLabel, BorderLayout.WEST)

        commandField.border = JBUI.Borders.empty(4, 8)
        commandField.emptyText.text = "Enter server command..."
        commandPanel.add(commandField, BorderLayout.CENTER)

        val sendButton = HytaleTheme.createButton("Send", AllIcons.Actions.Execute)
        sendButton.addActionListener { sendCommand() }
        commandPanel.add(sendButton, BorderLayout.EAST)

        commandField.addActionListener { sendCommand() }
        consoleCard.add(commandPanel, BorderLayout.SOUTH)

        mainPanel.add(consoleCard, BorderLayout.CENTER)
        return mainPanel
    }

    // ==================== RESOURCES TAB ====================

    private fun createResourcesTab(): JPanel {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        mainPanel.border = JBUI.Borders.empty(12)

        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = false

        // Documentation Links Card
        contentPanel.add(createLinksCard())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Live Templates Card
        contentPanel.add(createTemplatesCard())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Useful Commands Card
        contentPanel.add(createCommandsCard())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Contributors Card
        contentPanel.add(createContributorsCard())

        contentPanel.add(Box.createVerticalGlue())

        val scrollPane = JBScrollPane(contentPanel)
        scrollPane.border = null
        scrollPane.viewportBorder = null
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun createLinksCard(): JPanel {
        val card = HytaleTheme.createCard("Documentation & Community")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(220))

        val links = listOf(
            Triple("Discord", "Join the HytaleDocs community", "https://discord.gg/yAjaFBH4Y8"),
            Triple("Hytale Docs", "Community documentation", "https://hytale-docs.com"),
            Triple("API Reference", "Server API documentation", "https://hytale-docs.com/api"),
            Triple("Plugin Guide", "Getting started with plugins", "https://hytale-docs.com/modding/plugins/project-setup"),
            Triple("Server Setup", "Server configuration guide", "https://hytale-docs.com/modding/server/setup")
        )

        links.forEach { (name, desc, url) ->
            card.add(HytaleTheme.createLinkRow(name, desc, url))
            card.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        return card
    }

    private fun createTemplatesCard(): JPanel {
        val card = HytaleTheme.createCard("Live Templates")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(160))

        val templates = listOf(
            "hyevent" to "Create event listener",
            "hycmd" to "Create command handler",
            "hyecs" to "Create ECS system",
            "hyplugin" to "Create plugin main class"
        )

        templates.forEach { (abbrev, desc) ->
            card.add(createTemplateRow(abbrev, desc))
            card.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        return card
    }

    private fun createTemplateRow(abbrev: String, description: String): JPanel {
        val row = JPanel(BorderLayout(JBUI.scale(12), 0))
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))

        val codeLabel = JLabel(abbrev)
        codeLabel.foreground = HytaleTheme.purpleAccent
        codeLabel.font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scaleFontSize(12f).toInt())
        codeLabel.border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(HytaleTheme.purpleAccent, 4, 1),
            JBUI.Borders.empty(2, 6)
        )
        codeLabel.preferredSize = Dimension(JBUI.scale(80), codeLabel.preferredSize.height)
        row.add(codeLabel, BorderLayout.WEST)

        val descLabel = JLabel(description)
        descLabel.foreground = HytaleTheme.mutedText
        row.add(descLabel, BorderLayout.CENTER)

        return row
    }

    private fun createCommandsCard(): JPanel {
        val card = HytaleTheme.createCard("Useful Server Commands")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(140))

        val commands = listOf(
            "/help" to "Show all available commands",
            "/op <player>" to "Give operator permissions",
            "/auth login device" to "Authenticate the server",
            "/reload plugins" to "Reload all plugins"
        )

        commands.forEach { (cmd, desc) ->
            card.add(createCommandRow(cmd, desc))
            card.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        return card
    }

    private fun createCommandRow(command: String, description: String): JPanel {
        val row = JPanel(BorderLayout(JBUI.scale(12), 0))
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))

        val cmdLabel = JLabel(command)
        cmdLabel.foreground = HytaleTheme.successColor
        cmdLabel.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
        cmdLabel.preferredSize = Dimension(JBUI.scale(140), cmdLabel.preferredSize.height)
        row.add(cmdLabel, BorderLayout.WEST)

        val descLabel = JLabel(description)
        descLabel.foreground = HytaleTheme.mutedText
        row.add(descLabel, BorderLayout.CENTER)

        return row
    }

    private fun createContributorsCard(): JPanel {
        val card = HytaleTheme.createCard("Contributors")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(180))

        // Thank you message
        val thankYouLabel = JLabel("Thanks to all contributors who help improve this plugin!")
        thankYouLabel.foreground = HytaleTheme.mutedText
        thankYouLabel.font = thankYouLabel.font.deriveFont(JBUI.scaleFontSize(12f))
        thankYouLabel.alignmentX = Component.LEFT_ALIGNMENT
        card.add(thankYouLabel)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Contributors list
        val contributors = listOf(
            Triple("maartenpeels", "Mac & Linux support", "https://github.com/maartenpeels")
        )

        contributors.forEach { (name, contribution, url) ->
            card.add(createContributorRow(name, contribution, url))
            card.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Link to all contributors
        card.add(HytaleTheme.createLinkRow(
            "View all contributors",
            "See everyone who contributed",
            "https://github.com/HytaleDocs/hytale-intellij-plugin/graphs/contributors"
        ))

        return card
    }

    private fun createContributorRow(name: String, contribution: String, url: String): JPanel {
        val row = JPanel(BorderLayout(JBUI.scale(12), 0))
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val nameLabel = JLabel("@$name")
        nameLabel.foreground = HytaleTheme.accentColor
        nameLabel.font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scaleFontSize(12f).toInt())
        nameLabel.preferredSize = Dimension(JBUI.scale(140), nameLabel.preferredSize.height)
        row.add(nameLabel, BorderLayout.WEST)

        val contribLabel = JLabel(contribution)
        contribLabel.foreground = HytaleTheme.mutedText
        row.add(contribLabel, BorderLayout.CENTER)

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                BrowserUtil.browse(url)
            }
        })

        return row
    }

    // ==================== UTILITY METHODS ====================

    private fun refreshStatus() {
        // Check Java
        val allJavaInstalls = javaService.findJavaInstallations()
        val java25 = javaService.findJava25()

        if (java25 != null) {
            javaStatusLabel.text = "Java ${java25.version}"
            javaStatusLabel.foreground = HytaleTheme.successColor
            javaStatusLabel.toolTipText = "Path: ${java25.path}"
            installJavaButton.isVisible = false

            if (settings.javaPath.isEmpty()) {
                settings.javaPath = javaService.getJavaExecutable(java25).toString()
            }
        } else if (allJavaInstalls.isNotEmpty()) {
            val versions = allJavaInstalls.joinToString(", ") { it.version }
            javaStatusLabel.text = "Need 25+ (found: $versions)"
            javaStatusLabel.foreground = HytaleTheme.warningColor
            javaStatusLabel.toolTipText = "Found: ${allJavaInstalls.joinToString("\n") { "${it.version} at ${it.path}" }}"
            installJavaButton.isVisible = true
        } else {
            javaStatusLabel.text = "Not found"
            javaStatusLabel.foreground = HytaleTheme.errorColor
            javaStatusLabel.toolTipText = "Click 'Install Java 25' to download"
            installJavaButton.isVisible = true
        }

        // Check Server JAR
        val serverPath = getServerPath()
        if (serverPath != null) {
            val downloadService = ServerDownloadService.getInstance(project)
            val status = downloadService.hasServerFiles(serverPath)
            if (status.hasServerJar) {
                serverJarStatusLabel.text = if (status.hasAssets) "Ready" else "JAR only"
                serverJarStatusLabel.foreground = if (status.hasAssets) HytaleTheme.successColor else HytaleTheme.warningColor
                serverJarStatusLabel.toolTipText = "Path: $serverPath"
                downloadServerButton.isVisible = false
            } else {
                serverJarStatusLabel.text = "Not found"
                serverJarStatusLabel.foreground = HytaleTheme.errorColor
                serverJarStatusLabel.toolTipText = "Click to download with Hytale account"
                downloadServerButton.isVisible = true
            }
        } else {
            serverJarStatusLabel.text = "No path configured"
            serverJarStatusLabel.foreground = HytaleTheme.warningColor
            downloadServerButton.isVisible = true
        }

        // Update auth mode display
        authModeLabel.text = settings.authMode.displayName
        authModeLabel.foreground = if (settings.authMode == AuthMode.AUTHENTICATED) HytaleTheme.successColor else HytaleTheme.warningColor

        // Update button states
        val launchService = ServerLaunchService.getInstance(project)
        val isRunning = launchService.isServerRunning()
        startServerButton.isEnabled = !isRunning && java25 != null
        stopServerButton.isEnabled = isRunning
        serverStatusLabel.text = launchService.getStatus().name
        serverStatusLabel.foreground = when (launchService.getStatus()) {
            ServerLaunchService.ServerStatus.RUNNING -> HytaleTheme.successColor
            ServerLaunchService.ServerStatus.STARTING, ServerLaunchService.ServerStatus.STOPPING -> HytaleTheme.warningColor
            ServerLaunchService.ServerStatus.ERROR -> HytaleTheme.errorColor
            else -> HytaleTheme.textPrimary
        }
    }

    private fun updateStats() {
        val launchService = ServerLaunchService.getInstance(project)
        val stats = launchService.getStats()

        if (stats.uptime != null) {
            val hours = stats.uptime.toHours()
            val minutes = stats.uptime.toMinutesPart()
            val seconds = stats.uptime.toSecondsPart()
            uptimeLabel.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
            uptimeLabel.foreground = HytaleTheme.successColor
        } else {
            uptimeLabel.text = "--:--:--"
            uptimeLabel.foreground = HytaleTheme.mutedText
            // Clear auth status when server stops
            if (!launchService.isServerRunning() && !authService.isAuthenticating()) {
                authStatusLabel.text = ""
            }
        }

        playersLabel.text = "${stats.playerCount}"
        playersLabel.foreground = if (stats.playerCount > 0) HytaleTheme.successColor else HytaleTheme.textPrimary

        val players = launchService.getConnectedPlayers()
        playerListLabel.text = if (players.isEmpty()) "None" else players.joinToString(", ")
    }

    private fun getServerPath(): java.nio.file.Path? {
        val basePath = project.basePath ?: return null
        return Paths.get(basePath, settings.serverPath)
    }

    private fun installJava() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Installing Java 25", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                javaService.installJava(25) { progress ->
                    indicator.fraction = progress.progress / 100.0
                    indicator.text = progress.message
                }.thenAccept {
                    SwingUtilities.invokeLater {
                        refreshStatus()
                        notify("Java 25 installed successfully!", NotificationType.INFORMATION)
                    }
                }.exceptionally { e ->
                    SwingUtilities.invokeLater {
                        notify("Failed to install Java: ${e.message}", NotificationType.ERROR)
                    }
                    null
                }.get(120, TimeUnit.SECONDS)
            }
        })
    }

    private fun downloadServer() {
        val serverPath = getServerPath() ?: run {
            notify("No server path configured", NotificationType.ERROR)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading Hytale Server", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val downloadService = ServerDownloadService.getInstance(project)
                downloadService.createServerDirectories(serverPath)

                log("Starting Hytale Downloader...", isSystemMessage = true)

                downloaderService.downloadServerFiles(serverPath) { status ->
                    indicator.fraction = status.progress / 100.0
                    indicator.text = status.message

                    SwingUtilities.invokeLater {
                        when (status.stage) {
                            HytaleDownloaderService.Stage.AWAITING_AUTH -> {
                                log("Authentication required!", isSystemMessage = true)
                                log("Code: ${status.deviceCode}", isSystemMessage = true)
                                log("Opening browser...", isSystemMessage = true)
                                notify("Enter code ${status.deviceCode} in browser", NotificationType.INFORMATION)
                            }
                            HytaleDownloaderService.Stage.AUTHENTICATED -> {
                                log("Authentication successful!", isSystemMessage = true)
                            }
                            HytaleDownloaderService.Stage.DOWNLOADING_SERVER -> {
                                log("Downloading: ${status.progress}%", isSystemMessage = true)
                            }
                            HytaleDownloaderService.Stage.COMPLETED -> {
                                log("Download complete!", isSystemMessage = true)
                            }
                            HytaleDownloaderService.Stage.ERROR -> {
                                log("Error: ${status.message}", isSystemMessage = true)
                            }
                            else -> {
                                log(status.message, isSystemMessage = true)
                            }
                        }
                    }
                }.thenAccept { success ->
                    SwingUtilities.invokeLater {
                        refreshStatus()
                        if (success) {
                            notify("Server files downloaded!", NotificationType.INFORMATION)
                        }
                    }
                }.exceptionally { e ->
                    SwingUtilities.invokeLater {
                        notify("Download failed: ${e.message}", NotificationType.ERROR)
                    }
                    null
                }.get(300, TimeUnit.SECONDS)
            }
        })
    }

    private fun startServer() {
        val serverPath = getServerPath() ?: return
        val java25 = javaService.findJava25() ?: return

        val launchService = ServerLaunchService.getInstance(project)

        val config = ServerLaunchService.ServerConfig(
            serverPath = serverPath,
            javaPath = javaService.getJavaExecutable(java25),
            minMemory = settings.minMemory,
            maxMemory = settings.maxMemory,
            port = settings.port,
            authMode = if (settings.authMode == AuthMode.AUTHENTICATED)
                ServerLaunchService.AuthMode.AUTHENTICATED
            else
                ServerLaunchService.AuthMode.OFFLINE,
            allowOp = settings.allowOp,
            acceptEarlyPlugins = settings.acceptEarlyPlugins,
            additionalJvmArgs = settings.jvmArgs.split(" ").filter { it.isNotBlank() },
            additionalServerArgs = settings.serverArgs.split(" ").filter { it.isNotBlank() }
        )

        consolePane?.text = ""
        log("Starting Hytale server...", isSystemMessage = true)
        log("Auth mode: ${settings.authMode.displayName}", isSystemMessage = true)
        log("Memory: ${settings.minMemory} - ${settings.maxMemory}", isSystemMessage = true)

        notify("Starting server...", NotificationType.INFORMATION)

        launchService.startServer(config,
            logCallback = { line ->
                SwingUtilities.invokeLater {
                    log(line, isSystemMessage = false)
                }
            },
            statusCallback = { status ->
                SwingUtilities.invokeLater {
                    serverStatusLabel.text = status.name
                    serverStatusLabel.foreground = when (status) {
                        ServerLaunchService.ServerStatus.RUNNING -> HytaleTheme.successColor
                        ServerLaunchService.ServerStatus.STARTING, ServerLaunchService.ServerStatus.STOPPING -> HytaleTheme.warningColor
                        ServerLaunchService.ServerStatus.ERROR -> HytaleTheme.errorColor
                        else -> HytaleTheme.textPrimary
                    }
                    startServerButton.isEnabled = status == ServerLaunchService.ServerStatus.STOPPED
                    stopServerButton.isEnabled = status == ServerLaunchService.ServerStatus.RUNNING ||
                            status == ServerLaunchService.ServerStatus.STARTING

                    // Show notifications for status changes
                    when (status) {
                        ServerLaunchService.ServerStatus.RUNNING -> {
                            notify("Server is now running!", NotificationType.INFORMATION)
                        }
                        ServerLaunchService.ServerStatus.STOPPED -> {
                            notify("Server stopped", NotificationType.INFORMATION)
                        }
                        ServerLaunchService.ServerStatus.ERROR -> {
                            notify("Server error occurred", NotificationType.ERROR)
                        }
                        else -> {}
                    }
                }
            }
        )
    }

    private fun stopServer() {
        val launchService = ServerLaunchService.getInstance(project)
        log("Stopping server...", isSystemMessage = true)
        notify("Stopping server...", NotificationType.INFORMATION)
        launchService.stopServer { line ->
            SwingUtilities.invokeLater { log(line, isSystemMessage = true) }
        }
    }

    private fun sendCommand() {
        val command = commandField.text.trim()
        if (command.isNotEmpty()) {
            val launchService = ServerLaunchService.getInstance(project)
            if (launchService.sendCommand(command)) {
                log("> $command", isSystemMessage = true)
                commandField.text = ""
            }
        }
    }

    private fun log(message: String, isSystemMessage: Boolean = false) {
        val cleanMessage = ansiRegex.replace(message, "")

        consolePane?.let { pane ->
            val doc = pane.styledDocument

            if (isSystemMessage) {
                val finalMessage = if (settings.showTimestamps) {
                    "[${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] $cleanMessage"
                } else {
                    cleanMessage
                }
                appendStyled(doc, "$finalMessage\n", HytaleTheme.textPrimary)
            } else {
                val match = serverLogRegex.find(cleanMessage)
                if (match != null) {
                    val level = match.groupValues[1]
                    val rest = match.groupValues[2].trim()

                    val levelColor = when (level.uppercase()) {
                        "INFO" -> HytaleTheme.successColor
                        "WARN", "WARNING" -> HytaleTheme.warningColor
                        "ERROR", "SEVERE" -> HytaleTheme.errorColor
                        "DEBUG" -> HytaleTheme.mutedText
                        else -> HytaleTheme.textPrimary
                    }

                    appendStyled(doc, "[${level.uppercase()}] ", levelColor)
                    appendStyled(doc, "$rest\n", HytaleTheme.textPrimary)
                } else {
                    val color = when {
                        cleanMessage.startsWith("WARNING:") -> HytaleTheme.warningColor
                        cleanMessage.contains("ERROR", ignoreCase = true) -> HytaleTheme.errorColor
                        else -> HytaleTheme.textPrimary
                    }
                    appendStyled(doc, "$cleanMessage\n", color)
                }
            }

            if (settings.autoScroll) {
                pane.caretPosition = doc.length
            }
        }
    }

    private fun appendStyled(doc: javax.swing.text.StyledDocument, text: String, color: Color) {
        val style = javax.swing.text.StyleContext.getDefaultStyleContext()
            .getStyle(javax.swing.text.StyleContext.DEFAULT_STYLE)
        val colorStyle = doc.addStyle("color", style)
        javax.swing.text.StyleConstants.setForeground(colorStyle, color)
        doc.insertString(doc.length, text, colorStyle)
    }

    private fun notify(message: String, type: NotificationType) =
        PanelUtils.notify(project, "Hytale", message, type)

    override fun dispose() {
        statsTimer?.stop()
        statsTimer = null
        authPanel.dispose()
        authService.unregisterCallbacks(project)
    }
}
