package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.HytaleBundle
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
    private val javaStatusLabel = JBLabel(HytaleBundle.message("status.checking"))
    private val serverJarStatusLabel = JBLabel(HytaleBundle.message("status.checking"))
    private val serverStatusLabel = JBLabel("STOPPED")
    private val authModeLabel = JBLabel("")

    // Stats labels
    private val uptimeLabel = JBLabel("--:--:--")
    private val playersLabel = JBLabel("0")
    private val playerListLabel = JBLabel("None")
    private val authStatusLabel = JBLabel("")

    // Stats update timer
    private var statsTimer: Timer? = null

    // Profiler
    private val profiler = ServerProfiler.getInstance(project)
    private var profilerPanel: ProfilerPanel? = null

    // Buttons - using standard IntelliJ buttons with hover
    private val installJavaButton = HytaleTheme.createButton(HytaleBundle.message("button.installJava"), AllIcons.Actions.Download)
    private val downloadServerButton = HytaleTheme.createButton(HytaleBundle.message("button.downloadServer"), AllIcons.Actions.Download)
    private val startServerButton = HytaleTheme.createButton(HytaleBundle.message("button.start"), AllIcons.Actions.Execute)
    private val stopServerButton = HytaleTheme.createButton(HytaleBundle.message("button.stop"), AllIcons.Actions.Suspend)

    // Console
    private var consolePane: JTextPane? = null
    private val commandField = JBTextField()
    private val modeIndicatorLabel = JBLabel("Log Parsing")

    // Console log service
    private lateinit var consoleLogService: ConsoleLogService

    // Command history
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private val maxHistorySize = 100

    // Log filtering
    private val allLogs = mutableListOf<LogEntry>()
    private val activeFilters = mutableSetOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.SYSTEM)
    private var filterToolbar: JPanel? = null

    // Log search
    private var searchField: JBTextField? = null
    private var searchMatches = mutableListOf<Int>()
    private var currentMatchIndex = -1
    private var matchCountLabel: JLabel? = null

    // Log counter
    private var logCountLabel: JLabel? = null

    // ANSI escape code regex
    private val ansiRegex = Regex("\u001B\\[[0-9;]*[a-zA-Z]")
    private val serverLogRegex = Regex("""^\[[\d/]+\s+[\d:]+\s+(\w+)\]\s+(.*)$""")

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.empty()

        // Initialize console log service
        consoleLogService = ConsoleLogService.getInstance(project)

        val tabbedPane = JBTabbedPane()
        tabbedPane.tabComponentInsets = JBUI.insets(0)

        tabbedPane.addTab(HytaleBundle.message("tab.server"), createServerTab())
        tabbedPane.addTab(HytaleBundle.message("tab.console"), createConsoleTab())
        profilerPanel = ProfilerPanel(project)
        tabbedPane.addTab(HytaleBundle.message("tab.profiler"), profilerPanel)
        tabbedPane.addTab(HytaleBundle.message("tab.assets"), AssetsExplorerPanel(project))
        tabbedPane.addTab(HytaleBundle.message("tab.docs"), DocumentationPanel(project))
        tabbedPane.addTab(HytaleBundle.message("tab.ai"), AIAssistantPanel(project))
        tabbedPane.addTab(HytaleBundle.message("tab.infos"), createResourcesTab())

        add(tabbedPane, BorderLayout.CENTER)

        refreshStatus()

        statsTimer = Timer(1000) { updateStats() }
        statsTimer?.start()

        // Register for console log events
        consoleLogService.registerLogCallback { event ->
            SwingUtilities.invokeLater {
                logUnified(event)
            }
        }

        // Register for mode changes
        consoleLogService.registerModeCallback { mode ->
            SwingUtilities.invokeLater {
                updateModeIndicator(mode)
            }
        }

        // Initialize mode indicator
        updateModeIndicator(consoleLogService.getMode())

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
                consoleLogService.logSystemMessage("[Auth] Waiting for authentication code...")
            }
            AuthenticationService.AuthState.CODE_DISPLAYED -> {
                consoleLogService.logSystemMessage("[Auth] Device code: ${session.deviceCode}")
                consoleLogService.logSystemMessage("[Auth] Enter code at: ${session.verificationUrl}")
            }
            AuthenticationService.AuthState.AUTHENTICATING -> {
                consoleLogService.logSystemMessage("[Auth] Authenticating...")
            }
            AuthenticationService.AuthState.SUCCESS -> {
                consoleLogService.logSystemMessage("[Auth] Authentication successful!")
            }
            AuthenticationService.AuthState.FAILED -> {
                consoleLogService.logSystemMessage("[Auth] Authentication failed: ${session.message}")
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
        val card = HytaleTheme.createCard(HytaleBundle.message("card.environment.title"))
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)

        // Java Row
        val javaRow = createStatusRow(HytaleBundle.message("label.java"), javaStatusLabel, installJavaButton)
        card.add(javaRow)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Server Row
        val serverRow = createStatusRow(HytaleBundle.message("label.serverFiles"), serverJarStatusLabel, downloadServerButton)
        card.add(serverRow)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Server Status Row
        val statusRow = createStatusRow(HytaleBundle.message("label.serverStatus"), serverStatusLabel, null)
        card.add(statusRow)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Auth Mode Row
        val authRow = createStatusRow(HytaleBundle.message("label.authMode"), authModeLabel, null)
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
        val card = HytaleTheme.createCard(HytaleBundle.message("card.controls.title"))
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(80))

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        buttonsPanel.isOpaque = false
        buttonsPanel.alignmentX = Component.LEFT_ALIGNMENT

        buttonsPanel.add(startServerButton)
        buttonsPanel.add(stopServerButton)
        buttonsPanel.add(HytaleTheme.createButton(HytaleBundle.message("button.refresh"), AllIcons.Actions.Refresh).apply {
            addActionListener {
                refreshStatus()
                notify(HytaleBundle.message("notification.statusRefreshed"), NotificationType.INFORMATION)
            }
        })
        buttonsPanel.add(HytaleTheme.createButton(HytaleBundle.message("button.settings"), AllIcons.General.Settings).apply {
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
        val card = HytaleTheme.createCard(HytaleBundle.message("card.stats.title"))
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(140))

        val statsGrid = JPanel(GridLayout(4, 2, JBUI.scale(16), JBUI.scale(8)))
        statsGrid.isOpaque = false
        statsGrid.alignmentX = Component.LEFT_ALIGNMENT

        // Uptime
        statsGrid.add(createStatLabel(HytaleBundle.message("label.uptime")))
        uptimeLabel.font = uptimeLabel.font.deriveFont(Font.BOLD)
        statsGrid.add(uptimeLabel)

        // Auth Status
        statsGrid.add(createStatLabel(HytaleBundle.message("label.auth")))
        statsGrid.add(authStatusLabel)

        // Players
        statsGrid.add(createStatLabel(HytaleBundle.message("label.players")))
        playersLabel.font = playersLabel.font.deriveFont(Font.BOLD)
        statsGrid.add(playersLabel)

        // Online
        statsGrid.add(createStatLabel(HytaleBundle.message("label.online")))
        playerListLabel.font = playerListLabel.font.deriveFont(Font.ITALIC)
        playerListLabel.foreground = HytaleTheme.mutedText
        statsGrid.add(playerListLabel)

        card.add(statsGrid)
        return card
    }

    private fun createStatLabel(text: String): JLabel = PanelUtils.createStatLabel(text)

    private fun createQuickSettingsCard(): JPanel {
        val card = HytaleTheme.createCard(HytaleBundle.message("card.settings.title"))
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
        settingsGrid.add(createStatLabel(HytaleBundle.message("label.authentication")), gbc)
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
        settingsGrid.add(createStatLabel(HytaleBundle.message("label.memory")), gbc)
        gbc.gridx = 1
        gbc.insets = JBUI.insets(4, 12, 4, 0)
        val memoryLabel = JLabel("${settings.minMemory} - ${settings.maxMemory}")
        memoryLabel.font = memoryLabel.font.deriveFont(Font.BOLD)
        settingsGrid.add(memoryLabel, gbc)

        // Port
        gbc.gridx = 0; gbc.gridy = 2
        gbc.insets = JBUI.insets(4, 0)
        settingsGrid.add(createStatLabel(HytaleBundle.message("label.port")), gbc)
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

        // Top panel (toolbar + search + filters)
        val topPanel = JPanel(BorderLayout())
        topPanel.isOpaque = false

        // Toolbar with BoxLayout for proper glue support
        val toolbar = JPanel()
        toolbar.layout = BoxLayout(toolbar, BoxLayout.X_AXIS)
        toolbar.isOpaque = false
        toolbar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, HytaleTheme.cardBorder),
            JBUI.Borders.empty(4, 8)
        )
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(4)))
        toolbar.add(HytaleTheme.createButton(HytaleBundle.message("button.clear"), AllIcons.Actions.GC).apply {
            addActionListener {
                consolePane?.text = ""
                allLogs.clear()
                updateLogCount()
            }
        })
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(4)))
        toolbar.add(HytaleTheme.createButton(HytaleBundle.message("button.copyAll"), AllIcons.Actions.Copy).apply {
            addActionListener {
                consolePane?.let {
                    it.selectAll()
                    it.copy()
                    it.select(0, 0)
                }
            }
        })

        // Spacer to push mode indicator to the right
        toolbar.add(Box.createHorizontalGlue())

        // Mode indicator (Log Parsing / Bridge Connected)
        modeIndicatorLabel.font = modeIndicatorLabel.font.deriveFont(Font.ITALIC, JBUI.scaleFontSize(11f).toFloat())
        modeIndicatorLabel.border = JBUI.Borders.empty(0, 8)
        toolbar.add(modeIndicatorLabel)
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(4)))

        topPanel.add(toolbar, BorderLayout.NORTH)

        // Search bar
        val searchPanel = createSearchBar()
        topPanel.add(searchPanel, BorderLayout.CENTER)

        // Filter toolbar
        filterToolbar = createFilterToolbar()
        topPanel.add(filterToolbar, BorderLayout.SOUTH)

        consoleCard.add(topPanel, BorderLayout.NORTH)

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
        commandField.emptyText.text = HytaleBundle.message("console.commandPlaceholder")
        commandPanel.add(commandField, BorderLayout.CENTER)

        val sendButton = HytaleTheme.createButton(HytaleBundle.message("button.send"), AllIcons.Actions.Execute)
        sendButton.addActionListener { sendCommand() }
        commandPanel.add(sendButton, BorderLayout.EAST)

        commandField.addActionListener { sendCommand() }

        // Command history navigation with UP/DOWN arrows
        commandField.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_UP -> {
                        navigateHistory(-1)
                        e.consume()
                    }
                    java.awt.event.KeyEvent.VK_DOWN -> {
                        navigateHistory(1)
                        e.consume()
                    }
                }
            }
        })

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
        val card = HytaleTheme.createCard(HytaleBundle.message("card.docs.title"))
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
        val card = HytaleTheme.createCard(HytaleBundle.message("card.templates.title"))
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
        val card = HytaleTheme.createCard(HytaleBundle.message("card.commands.title"))
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
        val card = HytaleTheme.createCard(HytaleBundle.message("card.contributors.title"))
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(180))

        // Thank you message
        val thankYouLabel = JLabel(HytaleBundle.message("contributors.thanks"))
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
            HytaleBundle.message("contributors.viewAll"),
            HytaleBundle.message("contributors.seeEveryone"),
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
            javaStatusLabel.text = HytaleBundle.message("status.notFound")
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
                serverJarStatusLabel.text = if (status.hasAssets) HytaleBundle.message("status.ready") else HytaleBundle.message("status.jarOnly")
                serverJarStatusLabel.foreground = if (status.hasAssets) HytaleTheme.successColor else HytaleTheme.warningColor
                serverJarStatusLabel.toolTipText = "Path: $serverPath"
                downloadServerButton.isVisible = false
            } else {
                serverJarStatusLabel.text = HytaleBundle.message("status.notFound")
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
                        notify(HytaleBundle.message("notification.javaInstalled"), NotificationType.INFORMATION)
                    }
                }.exceptionally { e ->
                    SwingUtilities.invokeLater {
                        notify(HytaleBundle.message("notification.javaInstallFailed", e.message ?: ""), NotificationType.ERROR)
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
                            notify(HytaleBundle.message("notification.downloadComplete"), NotificationType.INFORMATION)
                        }
                    }
                }.exceptionally { e ->
                    SwingUtilities.invokeLater {
                        notify(HytaleBundle.message("notification.downloadFailed", e.message ?: ""), NotificationType.ERROR)
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
        consoleLogService.logSystemMessage("Starting Hytale server...")
        consoleLogService.logSystemMessage("Auth mode: ${settings.authMode.displayName}")
        consoleLogService.logSystemMessage("Memory: ${settings.minMemory} - ${settings.maxMemory}")

        // Record profiler event
        profiler.recordEvent(ServerProfiler.EventType.SERVER_START)

        notify(HytaleBundle.message("notification.serverStarting"), NotificationType.INFORMATION)

        launchService.startServer(config,
            logCallback = { line ->
                // Route through ConsoleLogService for unified handling
                consoleLogService.onFallbackLog(line)
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

                    // Show notifications and record profiler events for status changes
                    when (status) {
                        ServerLaunchService.ServerStatus.RUNNING -> {
                            profiler.recordEvent(ServerProfiler.EventType.SERVER_READY)
                            notify(HytaleBundle.message("notification.serverRunning"), NotificationType.INFORMATION)
                        }
                        ServerLaunchService.ServerStatus.STOPPED -> {
                            profiler.recordEvent(ServerProfiler.EventType.SERVER_STOP)
                            notify(HytaleBundle.message("notification.serverStopped"), NotificationType.INFORMATION)
                        }
                        ServerLaunchService.ServerStatus.ERROR -> {
                            notify(HytaleBundle.message("notification.serverError"), NotificationType.ERROR)
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
        consoleLogService.logSystemMessage("Stopping server...")
        notify(HytaleBundle.message("notification.serverStopped"), NotificationType.INFORMATION)

        // Disable buttons while stopping
        startServerButton.isEnabled = false
        stopServerButton.isEnabled = false

        launchService.stopServer(
            logCallback = { line ->
                SwingUtilities.invokeLater {
                    log(line, isSystemMessage = true)
                    consoleLogService.logSystemMessage(line)
                }
            },
            statusCallback = { status ->
                SwingUtilities.invokeLater {
                    when (status) {
                        ServerLaunchService.ServerStatus.STOPPED -> {
                            profiler.recordEvent(ServerProfiler.EventType.SERVER_STOP)
                            serverStatusLabel.text = status.name
                            serverStatusLabel.foreground = HytaleTheme.textPrimary
                            startServerButton.isEnabled = true
                            stopServerButton.isEnabled = false
                            notify(HytaleBundle.message("notification.serverStopped"), NotificationType.INFORMATION)
                        }
                        else -> {}
                    }
                }
            }
        )
    }

    private fun sendCommand() {
        val command = commandField.text.trim()
        if (command.isNotEmpty()) {
            // Try to use bridge connection first if available
            val bridgeConnection = consoleLogService.getActiveConnection()
            val success = if (bridgeConnection != null) {
                bridgeConnection.executeCommand(command)
                true
            } else {
                // Fall back to stdin
                val launchService = ServerLaunchService.getInstance(project)
                launchService.sendCommand(command)
            }

            if (success) {
                // Add to history (remove duplicate if exists, add to front)
                commandHistory.remove(command)
                commandHistory.add(0, command)
                if (commandHistory.size > maxHistorySize) {
                    commandHistory.removeAt(commandHistory.lastIndex)
                }
                historyIndex = -1

                // Record profiler event
                profiler.recordEvent(ServerProfiler.EventType.COMMAND_SENT, command)

                log("> $command", isSystemMessage = true)
                consoleLogService.logSystemMessage("> $command")
                commandField.text = ""
            }
        }
    }

    private fun navigateHistory(direction: Int) {
        if (commandHistory.isEmpty()) return

        val newIndex = historyIndex + direction
        if (newIndex < -1) return
        if (newIndex >= commandHistory.size) return

        historyIndex = newIndex
        commandField.text = if (historyIndex >= 0) commandHistory[historyIndex] else ""
        // Move caret to end
        commandField.caretPosition = commandField.text.length
    }

    // ==================== SEARCH BAR ====================

    private fun createSearchBar(): JPanel {
        val panel = JPanel(BorderLayout(JBUI.scale(4), 0))
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(4, 8)

        searchField = JBTextField().apply {
            emptyText.text = HytaleBundle.message("console.searchPlaceholder")
            border = JBUI.Borders.empty(4, 8)
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = performSearch()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = performSearch()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = performSearch()
            })
        }
        panel.add(searchField, BorderLayout.CENTER)

        val navPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        navPanel.isOpaque = false

        navPanel.add(JButton(AllIcons.Actions.PreviousOccurence).apply {
            toolTipText = "Previous match"
            isBorderPainted = false
            isContentAreaFilled = false
            addActionListener { navigateSearch(-1) }
        })
        navPanel.add(JButton(AllIcons.Actions.NextOccurence).apply {
            toolTipText = "Next match"
            isBorderPainted = false
            isContentAreaFilled = false
            addActionListener { navigateSearch(1) }
        })

        matchCountLabel = JLabel("0/0")
        matchCountLabel?.foreground = HytaleTheme.mutedText
        navPanel.add(matchCountLabel)

        panel.add(navPanel, BorderLayout.EAST)
        return panel
    }

    private fun performSearch() {
        val query = searchField?.text?.lowercase() ?: return
        searchMatches.clear()
        currentMatchIndex = -1

        if (query.isEmpty()) {
            matchCountLabel?.text = "0/0"
            clearHighlights()
            return
        }

        val text = consolePane?.text?.lowercase() ?: return
        var index = text.indexOf(query)
        while (index >= 0) {
            searchMatches.add(index)
            index = text.indexOf(query, index + 1)
        }

        matchCountLabel?.text = if (searchMatches.isEmpty()) "0/0" else "0/${searchMatches.size}"

        if (searchMatches.isNotEmpty()) {
            currentMatchIndex = 0
            highlightMatches(query)
            scrollToMatch(0)
        }
    }

    private fun navigateSearch(direction: Int) {
        if (searchMatches.isEmpty()) return

        currentMatchIndex = (currentMatchIndex + direction).mod(searchMatches.size)
        matchCountLabel?.text = "${currentMatchIndex + 1}/${searchMatches.size}"
        scrollToMatch(currentMatchIndex)
    }

    private fun highlightMatches(query: String) {
        val highlighter = consolePane?.highlighter ?: return
        highlighter.removeAllHighlights()

        val painter = javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(
            JBColor(Color(255, 255, 0, 100), Color(128, 128, 0, 100))
        )

        for (offset in searchMatches) {
            try {
                highlighter.addHighlight(offset, offset + query.length, painter)
            } catch (e: Exception) {
                // Ignore bad locations
            }
        }
    }

    private fun clearHighlights() {
        consolePane?.highlighter?.removeAllHighlights()
    }

    private fun scrollToMatch(index: Int) {
        if (index < 0 || index >= searchMatches.size) return
        val offset = searchMatches[index]
        consolePane?.caretPosition = offset
        try {
            val rect = consolePane?.modelToView2D(offset)
            if (rect != null) {
                consolePane?.scrollRectToVisible(java.awt.Rectangle(
                    rect.x.toInt(), rect.y.toInt(),
                    rect.width.toInt(), rect.height.toInt()
                ))
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    // ==================== FILTER TOOLBAR ====================

    private fun createFilterToolbar(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, HytaleTheme.cardBorder),
            JBUI.Borders.empty(4, 8)
        )

        val filtersPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        filtersPanel.isOpaque = false
        filtersPanel.add(JLabel(HytaleBundle.message("console.filter")).apply { foreground = HytaleTheme.mutedText })
        filtersPanel.add(createFilterToggle("INFO", LogLevel.INFO, HytaleTheme.successColor))
        filtersPanel.add(createFilterToggle("WARN", LogLevel.WARN, HytaleTheme.warningColor))
        filtersPanel.add(createFilterToggle("ERROR", LogLevel.ERROR, HytaleTheme.errorColor))
        filtersPanel.add(createFilterToggle("DEBUG", LogLevel.DEBUG, HytaleTheme.mutedText))
        filtersPanel.add(createFilterToggle("SYSTEM", LogLevel.SYSTEM, HytaleTheme.accentColor))
        panel.add(filtersPanel, BorderLayout.WEST)

        // Log counter on the right
        logCountLabel = JLabel("0 logs")
        logCountLabel?.foreground = HytaleTheme.mutedText
        logCountLabel?.border = JBUI.Borders.empty(0, 8)
        panel.add(logCountLabel, BorderLayout.EAST)

        return panel
    }

    private fun createFilterToggle(label: String, level: LogLevel, color: Color): JToggleButton {
        return JToggleButton(label, activeFilters.contains(level)).apply {
            foreground = color
            font = font.deriveFont(Font.BOLD, 10f)
            isFocusPainted = false
            border = JBUI.Borders.empty(2, 6)
            addActionListener {
                if (isSelected) activeFilters.add(level) else activeFilters.remove(level)
                refreshConsole()
            }
        }
    }

    private fun refreshConsole() {
        consolePane?.text = ""
        val filteredLogs = allLogs.filter { activeFilters.contains(it.level) }
        filteredLogs.forEach { appendLogEntry(it) }
        updateLogCount()
        performSearch() // Re-apply search highlighting
    }

    private fun updateLogCount() {
        val filteredCount = allLogs.count { activeFilters.contains(it.level) }
        logCountLabel?.text = HytaleBundle.message("console.logs", filteredCount)
    }

    private fun parseLogEntry(message: String, isSystemMessage: Boolean): LogEntry {
        val cleanMessage = ansiRegex.replace(message, "")
        val timestamp = java.time.LocalDateTime.now()

        if (isSystemMessage) {
            return LogEntry(timestamp, LogLevel.SYSTEM, null, cleanMessage, message)
        }

        val match = serverLogRegex.find(cleanMessage)
        if (match != null) {
            val levelStr = match.groupValues[1].uppercase()
            val rest = match.groupValues[2].trim()

            val level = when (levelStr) {
                "DEBUG" -> LogLevel.DEBUG
                "INFO" -> LogLevel.INFO
                "WARN", "WARNING" -> LogLevel.WARN
                "ERROR", "SEVERE" -> LogLevel.ERROR
                else -> LogLevel.INFO
            }

            // Try to extract plugin source from log (e.g., [PluginName] message)
            val sourceMatch = Regex("""^\[([^\]]+)\]\s*(.*)$""").find(rest)
            val source = sourceMatch?.groupValues?.get(1)
            val finalMessage = sourceMatch?.groupValues?.get(2) ?: rest

            return LogEntry(timestamp, level, source, finalMessage, message)
        }

        // Fallback parsing
        val level = when {
            cleanMessage.contains("ERROR", ignoreCase = true) -> LogLevel.ERROR
            cleanMessage.contains("WARN", ignoreCase = true) -> LogLevel.WARN
            cleanMessage.contains("DEBUG", ignoreCase = true) -> LogLevel.DEBUG
            else -> LogLevel.INFO
        }

        return LogEntry(timestamp, level, null, cleanMessage, message)
    }

    private fun appendLogEntry(entry: LogEntry) {
        consolePane?.let { pane ->
            val doc = pane.styledDocument

            val levelColor = when (entry.level) {
                LogLevel.INFO -> HytaleTheme.successColor
                LogLevel.WARN -> HytaleTheme.warningColor
                LogLevel.ERROR -> HytaleTheme.errorColor
                LogLevel.DEBUG -> HytaleTheme.mutedText
                LogLevel.SYSTEM -> HytaleTheme.accentColor
            }

            val prefix = if (settings.showTimestamps) {
                "[${entry.timestamp.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}] "
            } else ""

            appendStyled(doc, prefix, HytaleTheme.mutedText)
            appendStyled(doc, "[${entry.level.name}] ", levelColor)

            if (entry.source != null) {
                appendStyled(doc, "[${entry.source}] ", HytaleTheme.accentColor)
            }

            appendStyled(doc, "${entry.message}\n", HytaleTheme.textPrimary)

            if (settings.autoScroll) {
                pane.caretPosition = doc.length
            }
        }
    }

    private fun log(message: String, isSystemMessage: Boolean = false) {
        // Parse and store the log entry
        val entry = parseLogEntry(message, isSystemMessage)
        allLogs.add(entry)

        // Enforce max log lines
        if (allLogs.size > settings.maxLogLines) {
            allLogs.removeAt(0)
        }

        // Only display if filter is active for this level
        if (activeFilters.contains(entry.level)) {
            appendLogEntry(entry)
        }

        // Update log counter
        updateLogCount()

        // Feed to profiler for event tracking (only non-system messages from server)
        if (!isSystemMessage) {
            profiler.parseLogLine(message)
        }
    }

    private fun appendStyled(doc: javax.swing.text.StyledDocument, text: String, color: Color) {
        val style = javax.swing.text.StyleContext.getDefaultStyleContext()
            .getStyle(javax.swing.text.StyleContext.DEFAULT_STYLE)
        val colorStyle = doc.addStyle("color", style)
        javax.swing.text.StyleConstants.setForeground(colorStyle, color)
        doc.insertString(doc.length, text, colorStyle)
    }

    /**
     * Log a unified log event from ConsoleLogService.
     * Handles both system messages and structured log events from fallback or bridge.
     */
    private fun logUnified(event: ConsoleLogService.UnifiedLogEvent) {
        consolePane?.let { pane ->
            val doc = pane.styledDocument

            if (event.isSystemMessage) {
                val finalMessage = if (settings.showTimestamps) {
                    "[${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] ${event.message}"
                } else {
                    event.message
                }
                appendStyled(doc, "$finalMessage\n", HytaleTheme.textPrimary)
            } else {
                // Get color based on log level
                val levelColor = when (event.level) {
                    ConsoleLogService.UnifiedLogLevel.TRACE -> HytaleTheme.mutedText
                    ConsoleLogService.UnifiedLogLevel.DEBUG -> HytaleTheme.mutedText
                    ConsoleLogService.UnifiedLogLevel.INFO -> HytaleTheme.successColor
                    ConsoleLogService.UnifiedLogLevel.WARNING -> HytaleTheme.warningColor
                    ConsoleLogService.UnifiedLogLevel.ERROR -> HytaleTheme.errorColor
                    ConsoleLogService.UnifiedLogLevel.FATAL -> HytaleTheme.errorColor
                    else -> HytaleTheme.textPrimary
                }

                val levelName = event.level.name

                // If we have structured data from bridge, show logger name
                if (event.loggerName != null && consoleLogService.isBridgeConnected()) {
                    // Extract short logger name (last part after dot)
                    val shortLogger = event.loggerName.substringAfterLast('.')
                    appendStyled(doc, "[$levelName] ", levelColor)
                    appendStyled(doc, "[$shortLogger] ", HytaleTheme.mutedText)
                    appendStyled(doc, "${event.message}\n", HytaleTheme.textPrimary)
                } else {
                    appendStyled(doc, "[$levelName] ", levelColor)
                    appendStyled(doc, "${event.message}\n", HytaleTheme.textPrimary)
                }

                // Show throwable if present
                if (!event.throwable.isNullOrEmpty()) {
                    appendStyled(doc, "${event.throwable}\n", HytaleTheme.errorColor)
                }
            }

            if (settings.autoScroll) {
                pane.caretPosition = doc.length
            }
        }
    }

    /**
     * Update the mode indicator label based on current console log mode.
     */
    private fun updateModeIndicator(mode: ConsoleLogService.ConsoleLogMode) {
        when (mode) {
            ConsoleLogService.ConsoleLogMode.FALLBACK_PARSING -> {
                modeIndicatorLabel.text = "Log Parsing"
                modeIndicatorLabel.foreground = HytaleTheme.warningColor
                modeIndicatorLabel.toolTipText = "Parsing logs from stdout (bridge not connected)"
            }
            ConsoleLogService.ConsoleLogMode.BRIDGE_CONNECTED -> {
                modeIndicatorLabel.text = "Bridge Connected"
                modeIndicatorLabel.foreground = HytaleTheme.successColor
                modeIndicatorLabel.toolTipText = "Receiving structured logs from dev bridge"
            }
        }
    }

    private fun notify(message: String, type: NotificationType) =
        PanelUtils.notify(project, "Hytale", message, type)

    override fun dispose() {
        statsTimer?.stop()
        statsTimer = null
        authPanel.dispose()
        profilerPanel?.dispose()
        profilerPanel = null
        authService.unregisterCallbacks(project)
    }
}
