package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.services.OfflineDocsService
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * AI Assistant panel for the Hytale tool window.
 * Provides integration with Claude Code and MCP server.
 * Manages local documentation cache for MCP server access.
 */
class AIAssistantPanel(private val project: Project) : JBPanel<AIAssistantPanel>(BorderLayout()) {

    companion object {
        // MCP config JSON template
        private val MCP_CONFIG_JSON = """
{
  "mcpServers": {
    "hytale-docs": {
      "command": "npx",
      "args": ["hytaledocs-mcp-server"]
    }
  }
}
        """.trimIndent()

        // Quick prompts for developers
        private val QUICK_PROMPTS = listOf(
            QuickPrompt(
                "Create Event Listener",
                "Create a Hytale event listener for PlayerConnectEvent that logs player connections",
                "Event"
            ),
            QuickPrompt(
                "Explain ECS System",
                "Explain how Hytale's Entity Component System works and show an example of creating a custom component",
                "ECS"
            ),
            QuickPrompt(
                "Custom UI Guide",
                "How do I create a custom UI screen in Hytale using the UI framework?",
                "UI"
            ),
            QuickPrompt(
                "Plugin Structure",
                "Show me the recommended file structure for a Hytale plugin project with best practices",
                "Setup"
            ),
            QuickPrompt(
                "Command Handler",
                "Create a command handler for a /teleport command that takes player name and coordinates",
                "Commands"
            ),
            QuickPrompt(
                "World Generation",
                "How do I create a custom world generator in Hytale?",
                "World"
            )
        )

        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }

    private data class QuickPrompt(val title: String, val prompt: String, val tag: String)

    private val docsService = OfflineDocsService.getInstance()

    // Cache status UI components
    private val cacheStatusLabel = JBLabel("Checking...")
    private val cacheCountLabel = JBLabel("")
    private val cacheLastRefreshLabel = JBLabel("")
    private val cacheProgressBar = JProgressBar(0, 100)
    private val cacheProgressLabel = JBLabel("")
    private val refreshButton = HytaleTheme.createModernButton("Download from GitHub", AllIcons.Vcs.Vendors.Github, HytaleTheme.accentColor)
    private val clearCacheButton = HytaleTheme.createModernButton("Clear Cache", AllIcons.Actions.GC, HytaleTheme.errorColor)

    // MCP status components
    private val mcpStatusLabel = JBLabel("Checking...")
    private val mcpStatusIcon = JBLabel()

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.empty(12)

        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = false

        // Header
        contentPanel.add(createHeaderPanel())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(16)))

        // Documentation Cache Card (main feature)
        contentPanel.add(createCacheCard())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // MCP Server Status Card
        contentPanel.add(createMcpStatusCard())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Quick Actions Card
        contentPanel.add(createQuickActionsCard())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Quick Prompts Card
        contentPanel.add(createQuickPromptsCard())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Resources Card
        contentPanel.add(createResourcesCard())

        contentPanel.add(Box.createVerticalGlue())

        val scrollPane = JBScrollPane(contentPanel)
        scrollPane.border = null
        scrollPane.viewportBorder = null
        add(scrollPane, BorderLayout.CENTER)

        // Initial status check
        refreshCacheStatus()
        checkMcpStatus()
    }

    private fun createHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(40))

        val titleLabel = JBLabel("AI Assistant")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(16f))
        titleLabel.icon = AllIcons.Actions.InlayGear
        panel.add(titleLabel, BorderLayout.WEST)

        val subtitleLabel = JBLabel("Claude Code + MCP Integration")
        subtitleLabel.foreground = HytaleTheme.mutedText
        subtitleLabel.font = subtitleLabel.font.deriveFont(JBUI.scaleFontSize(11f))
        panel.add(subtitleLabel, BorderLayout.SOUTH)

        return panel
    }

    // ==================== DOCUMENTATION CACHE CARD ====================

    private fun createCacheCard(): JPanel {
        val card = HytaleTheme.createCard("Documentation Cache")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(220))

        // Status row
        val statusRow = JPanel(GridBagLayout())
        statusRow.isOpaque = false
        statusRow.alignmentX = Component.LEFT_ALIGNMENT

        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 0)
        }

        // Status
        gbc.gridx = 0; gbc.gridy = 0
        statusRow.add(createStatLabel("Status:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(2, 8, 2, 0)
        statusRow.add(cacheStatusLabel, gbc)

        // Document count
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        gbc.insets = JBUI.insets(2, 0)
        statusRow.add(createStatLabel("Documents:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(2, 8, 2, 0)
        cacheCountLabel.font = cacheCountLabel.font.deriveFont(Font.BOLD)
        statusRow.add(cacheCountLabel, gbc)

        // Last refresh
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        gbc.insets = JBUI.insets(2, 0)
        statusRow.add(createStatLabel("Last Update:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(2, 8, 2, 0)
        cacheLastRefreshLabel.foreground = HytaleTheme.mutedText
        statusRow.add(cacheLastRefreshLabel, gbc)

        card.add(statusRow)
        card.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Progress bar (hidden by default)
        cacheProgressBar.isVisible = false
        cacheProgressBar.isStringPainted = false
        cacheProgressBar.alignmentX = Component.LEFT_ALIGNMENT
        cacheProgressBar.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(4))
        card.add(cacheProgressBar)

        cacheProgressLabel.isVisible = false
        cacheProgressLabel.foreground = HytaleTheme.mutedText
        cacheProgressLabel.font = cacheProgressLabel.font.deriveFont(JBUI.scaleFontSize(11f))
        cacheProgressLabel.alignmentX = Component.LEFT_ALIGNMENT
        card.add(cacheProgressLabel)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Buttons row
        val buttonsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        buttonsRow.isOpaque = false
        buttonsRow.alignmentX = Component.LEFT_ALIGNMENT

        refreshButton.addActionListener { refreshDocumentationCache() }
        buttonsRow.add(refreshButton)

        clearCacheButton.addActionListener { clearCache() }
        buttonsRow.add(clearCacheButton)

        card.add(buttonsRow)

        // Help text
        card.add(Box.createVerticalStrut(JBUI.scale(8)))
        val helpLabel = JBLabel("<html><small>Downloads docs from GitHub for offline access + MCP server</small></html>")
        helpLabel.foreground = HytaleTheme.mutedText
        helpLabel.alignmentX = Component.LEFT_ALIGNMENT
        card.add(helpLabel)

        return card
    }

    private fun createStatLabel(text: String): JLabel {
        return JLabel(text).apply {
            foreground = HytaleTheme.mutedText
            preferredSize = Dimension(JBUI.scale(80), preferredSize.height)
        }
    }

    private fun refreshCacheStatus() {
        val status = docsService.getCacheStatus()

        if (status.available && status.totalDocs > 0) {
            cacheStatusLabel.text = "Ready (Offline)"
            cacheStatusLabel.foreground = HytaleTheme.successColor
            cacheCountLabel.text = "${status.totalDocs} documents"
            cacheCountLabel.foreground = HytaleTheme.textPrimary

            if (status.lastUpdate != null) {
                try {
                    val instant = Instant.parse(status.lastUpdate)
                    cacheLastRefreshLabel.text = dateFormatter.format(instant)
                } catch (e: Exception) {
                    cacheLastRefreshLabel.text = status.lastUpdate
                }
            } else {
                cacheLastRefreshLabel.text = "Unknown"
            }
        } else {
            cacheStatusLabel.text = "Not downloaded"
            cacheStatusLabel.foreground = HytaleTheme.warningColor
            cacheCountLabel.text = "0 documents"
            cacheCountLabel.foreground = HytaleTheme.mutedText
            cacheLastRefreshLabel.text = "Never"
        }
    }

    private fun refreshDocumentationCache() {
        if (docsService.isDownloading()) {
            notify("Download already in progress", NotificationType.WARNING)
            return
        }

        // Update UI to show progress
        refreshButton.isEnabled = false
        clearCacheButton.isEnabled = false
        cacheProgressBar.isVisible = true
        cacheProgressBar.value = 0
        cacheProgressLabel.isVisible = true
        cacheProgressLabel.text = "Starting..."
        cacheStatusLabel.text = "Downloading..."
        cacheStatusLabel.foreground = HytaleTheme.warningColor

        docsService.downloadDocs { progress ->
            SwingUtilities.invokeLater {
                when (progress.phase) {
                    OfflineDocsService.DownloadPhase.CHECKING_UPDATES -> {
                        cacheProgressBar.isIndeterminate = true
                        cacheProgressLabel.text = progress.message
                    }
                    OfflineDocsService.DownloadPhase.DOWNLOADING -> {
                        cacheProgressBar.isIndeterminate = false
                        cacheProgressBar.value = progress.progress
                        cacheProgressLabel.text = progress.message
                    }
                    OfflineDocsService.DownloadPhase.EXTRACTING -> {
                        cacheProgressBar.isIndeterminate = false
                        cacheProgressBar.value = progress.progress
                        cacheProgressLabel.text = progress.message
                    }
                    OfflineDocsService.DownloadPhase.INDEXING -> {
                        cacheProgressBar.isIndeterminate = true
                        cacheProgressLabel.text = progress.message
                    }
                    OfflineDocsService.DownloadPhase.COMPLETE -> {
                        cacheProgressBar.isVisible = false
                        cacheProgressLabel.isVisible = false
                        refreshButton.isEnabled = true
                        clearCacheButton.isEnabled = true
                        refreshCacheStatus()
                        checkMcpStatus()
                        notify("Documentation downloaded from GitHub!", NotificationType.INFORMATION)
                    }
                    OfflineDocsService.DownloadPhase.ERROR -> {
                        cacheProgressBar.isVisible = false
                        cacheProgressLabel.isVisible = false
                        refreshButton.isEnabled = true
                        clearCacheButton.isEnabled = true
                        cacheStatusLabel.text = "Error"
                        cacheStatusLabel.foreground = HytaleTheme.errorColor
                        notify("Failed to download: ${progress.message}", NotificationType.ERROR)
                    }
                }
            }
        }
    }

    private fun clearCache() {
        val result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to clear the documentation cache?\nYou will need to download it again for offline access.",
            "Clear Cache",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (result == JOptionPane.YES_OPTION) {
            docsService.clearCache()
            refreshCacheStatus()
            checkMcpStatus()
            notify("Cache cleared", NotificationType.INFORMATION)
        }
    }

    // ==================== MCP STATUS CARD ====================

    private fun createMcpStatusCard(): JPanel {
        val card = HytaleTheme.createCard("MCP Server Configuration")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(220))

        // Status row
        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        statusRow.isOpaque = false
        statusRow.alignmentX = Component.LEFT_ALIGNMENT

        mcpStatusIcon.icon = AllIcons.General.InspectionsEye
        statusRow.add(mcpStatusIcon)
        statusRow.add(mcpStatusLabel)

        card.add(statusRow)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Install MCP Server row
        val serverInstallRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        serverInstallRow.isOpaque = false
        serverInstallRow.alignmentX = Component.LEFT_ALIGNMENT

        val installServerButton = HytaleTheme.createModernButton("Install MCP Server (npm)", AllIcons.Actions.Download, HytaleTheme.warningColor)
        installServerButton.addActionListener { installMcpServer() }
        serverInstallRow.add(installServerButton)

        card.add(serverInstallRow)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Auto-install config buttons row
        val installRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        installRow.isOpaque = false
        installRow.alignmentX = Component.LEFT_ALIGNMENT

        val installClaudeCodeButton = HytaleTheme.createModernButton("Install for Claude Code", AllIcons.Actions.Install, HytaleTheme.successColor)
        installClaudeCodeButton.addActionListener { installMcpForClaudeCode() }
        installRow.add(installClaudeCodeButton)

        val installClaudeDesktopButton = HytaleTheme.createModernButton("Install for Claude Desktop", AllIcons.Actions.Install, HytaleTheme.accentColor)
        installClaudeDesktopButton.addActionListener { installMcpForClaudeDesktop() }
        installRow.add(installClaudeDesktopButton)

        card.add(installRow)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Manual config buttons row
        val buttonsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        buttonsRow.isOpaque = false
        buttonsRow.alignmentX = Component.LEFT_ALIGNMENT

        val configureButton = HytaleTheme.createModernButton("Copy MCP Config", AllIcons.Actions.Copy)
        configureButton.addActionListener { copyMcpConfig() }
        buttonsRow.add(configureButton)

        val openCacheDirButton = HytaleTheme.createModernButton("Open Cache Folder", AllIcons.Actions.MenuOpen)
        openCacheDirButton.addActionListener { openCacheDirectory() }
        buttonsRow.add(openCacheDirButton)

        card.add(buttonsRow)

        return card
    }

    private fun installMcpServer() {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        // Run npm install in background
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(project, "Installing MCP Server", false) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Installing hytaledocs-mcp-server via npm..."

                    try {
                        val command = if (isWindows) {
                            listOf("cmd", "/c", "npm", "install", "-g", "hytaledocs-mcp-server")
                        } else {
                            listOf("npm", "install", "-g", "hytaledocs-mcp-server")
                        }

                        val process = ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .start()

                        val output = process.inputStream.bufferedReader().readText()
                        val exitCode = process.waitFor()

                        SwingUtilities.invokeLater {
                            if (exitCode == 0) {
                                notify("MCP Server installed successfully!", NotificationType.INFORMATION)
                            } else {
                                notify("Installation failed. Check npm is installed.\n$output", NotificationType.ERROR)
                            }
                        }
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater {
                            notify("Failed to install: ${e.message}", NotificationType.ERROR)
                        }
                    }
                }
            }
        )
    }

    private fun checkMcpStatus() {
        SwingUtilities.invokeLater {
            val status = docsService.getCacheStatus()

            if (status.available && status.totalDocs > 0) {
                mcpStatusLabel.text = "Ready - ${status.totalDocs} docs available for MCP"
                mcpStatusLabel.foreground = HytaleTheme.successColor
                mcpStatusIcon.icon = AllIcons.General.InspectionsOK
            } else {
                mcpStatusLabel.text = "No docs - click 'Download Documentation'"
                mcpStatusLabel.foreground = HytaleTheme.warningColor
                mcpStatusIcon.icon = AllIcons.General.Warning
            }
        }
    }

    private fun copyMcpConfig() {
        val cacheDir = OfflineDocsService.getCacheDir().absolutePath.replace("\\", "/")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        // Windows requires cmd /c wrapper to execute npx
        val config = if (isWindows) {
            """
{
  "mcpServers": {
    "hytale-docs": {
      "command": "cmd",
      "args": ["/c", "npx", "hytaledocs-mcp-server"],
      "env": {
        "HYTALE_MCP_CACHE_DIR": "$cacheDir"
      }
    }
  }
}
            """.trimIndent()
        } else {
            """
{
  "mcpServers": {
    "hytale-docs": {
      "command": "npx",
      "args": ["hytaledocs-mcp-server"],
      "env": {
        "HYTALE_MCP_CACHE_DIR": "$cacheDir"
      }
    }
  }
}
            """.trimIndent()
        }

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(config), null)
        notify("MCP configuration copied to clipboard!", NotificationType.INFORMATION)
    }

    private fun openCacheDirectory() {
        val cacheDir = OfflineDocsService.getCacheDir()
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        try {
            Desktop.getDesktop().open(cacheDir)
        } catch (e: Exception) {
            notify("Failed to open folder: ${e.message}", NotificationType.ERROR)
        }
    }

    private fun installMcpForClaudeCode() {
        try {
            val cacheDir = OfflineDocsService.getCacheDir().absolutePath.replace("\\", "/")
            val projectPath = project.basePath

            if (projectPath == null) {
                notify("No project open. Please open a project first.", NotificationType.WARNING)
                return
            }

            // Claude Code uses .mcp.json in project root for project-specific MCP servers
            val configFile = File(projectPath, ".mcp.json")

            val existingConfig = if (configFile.exists()) {
                try {
                    com.google.gson.JsonParser.parseString(configFile.readText()).asJsonObject
                } catch (e: Exception) {
                    com.google.gson.JsonObject()
                }
            } else {
                com.google.gson.JsonObject()
            }

            // Add or update mcpServers
            val mcpServers = existingConfig.getAsJsonObject("mcpServers") ?: com.google.gson.JsonObject()

            // Windows requires cmd /c wrapper to execute npx
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val hytaleDocsConfig = com.google.gson.JsonObject().apply {
                if (isWindows) {
                    addProperty("command", "cmd")
                    add("args", com.google.gson.JsonArray().apply {
                        add("/c")
                        add("npx")
                        add("hytaledocs-mcp-server")
                    })
                } else {
                    addProperty("command", "npx")
                    add("args", com.google.gson.JsonArray().apply { add("hytaledocs-mcp-server") })
                }
                add("env", com.google.gson.JsonObject().apply {
                    addProperty("HYTALE_MCP_CACHE_DIR", cacheDir)
                })
            }
            mcpServers.add("hytale-docs", hytaleDocsConfig)
            existingConfig.add("mcpServers", mcpServers)

            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            configFile.writeText(gson.toJson(existingConfig))

            notify("MCP server installed in project .mcp.json! Restart Claude Code to apply.", NotificationType.INFORMATION)
        } catch (e: Exception) {
            notify("Failed to install: ${e.message}", NotificationType.ERROR)
        }
    }

    private fun installMcpForClaudeDesktop() {
        try {
            val cacheDir = OfflineDocsService.getCacheDir().absolutePath.replace("\\", "/")
            val isWindows = System.getProperty("os.name").lowercase().contains("win")

            // Claude Desktop config location varies by OS
            val configFile = if (isWindows) {
                File(System.getenv("APPDATA"), "Claude/claude_desktop_config.json")
            } else if (System.getProperty("os.name").lowercase().contains("mac")) {
                File(System.getProperty("user.home"), "Library/Application Support/Claude/claude_desktop_config.json")
            } else {
                File(System.getProperty("user.home"), ".config/Claude/claude_desktop_config.json")
            }

            if (!configFile.parentFile.exists()) {
                configFile.parentFile.mkdirs()
            }

            val existingConfig = if (configFile.exists()) {
                try {
                    com.google.gson.JsonParser.parseString(configFile.readText()).asJsonObject
                } catch (e: Exception) {
                    com.google.gson.JsonObject()
                }
            } else {
                com.google.gson.JsonObject()
            }

            // Add or update mcpServers
            val mcpServers = existingConfig.getAsJsonObject("mcpServers") ?: com.google.gson.JsonObject()

            // Windows requires cmd /c wrapper to execute npx
            val hytaleDocsConfig = com.google.gson.JsonObject().apply {
                if (isWindows) {
                    addProperty("command", "cmd")
                    add("args", com.google.gson.JsonArray().apply {
                        add("/c")
                        add("npx")
                        add("hytaledocs-mcp-server")
                    })
                } else {
                    addProperty("command", "npx")
                    add("args", com.google.gson.JsonArray().apply { add("hytaledocs-mcp-server") })
                }
                add("env", com.google.gson.JsonObject().apply {
                    addProperty("HYTALE_MCP_CACHE_DIR", cacheDir)
                })
            }
            mcpServers.add("hytale-docs", hytaleDocsConfig)
            existingConfig.add("mcpServers", mcpServers)

            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            configFile.writeText(gson.toJson(existingConfig))

            notify("MCP server installed for Claude Desktop! Restart Claude Desktop to apply.", NotificationType.INFORMATION)
        } catch (e: Exception) {
            notify("Failed to install: ${e.message}", NotificationType.ERROR)
        }
    }

    // ==================== QUICK ACTIONS CARD ====================

    private fun createQuickActionsCard(): JPanel {
        val card = HytaleTheme.createCard("Quick Actions")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(80))

        val buttonsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        buttonsRow.isOpaque = false
        buttonsRow.alignmentX = Component.LEFT_ALIGNMENT

        val claudeCodeButton = HytaleTheme.createModernButton("Open Claude Code", AllIcons.Actions.Execute, HytaleTheme.purpleAccent)
        claudeCodeButton.addActionListener { openClaudeCode() }
        buttonsRow.add(claudeCodeButton)

        val claudeDesktopButton = HytaleTheme.createModernButton("Claude Desktop", AllIcons.Nodes.Desktop, HytaleTheme.accentColor)
        claudeDesktopButton.addActionListener { openClaudeDesktop() }
        buttonsRow.add(claudeDesktopButton)

        card.add(buttonsRow)

        return card
    }

    private fun openClaudeCode() {
        val projectPath = project.basePath ?: return

        try {
            // Open terminal in project directory, then run claude
            val command = if (System.getProperty("os.name").lowercase().contains("win")) {
                // Use cmd /k to keep terminal open, cd first then run claude
                listOf("cmd", "/c", "start", "cmd", "/k", "cd /d \"$projectPath\" && claude")
            } else if (System.getProperty("os.name").lowercase().contains("mac")) {
                // macOS - open Terminal and run command
                listOf("osascript", "-e", "tell application \"Terminal\" to do script \"cd '$projectPath' && claude\"")
            } else {
                // Linux - try common terminal emulators
                listOf("bash", "-c", "cd '$projectPath' && claude")
            }

            ProcessBuilder(command)
                .directory(File(projectPath))
                .start()

            notify("Opening Claude Code in ${File(projectPath).name}...", NotificationType.INFORMATION)
        } catch (e: Exception) {
            notify("Failed to open Claude Code: ${e.message}", NotificationType.ERROR)
        }
    }

    private fun openClaudeDesktop() {
        try {
            // Try the claude:// protocol
            BrowserUtil.browse("claude://")
        } catch (e: Exception) {
            notify("Claude Desktop not found. Install from claude.ai", NotificationType.WARNING)
        }
    }

    // ==================== QUICK PROMPTS CARD ====================

    private fun createQuickPromptsCard(): JPanel {
        val card = HytaleTheme.createCard("Quick Prompts")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(260))

        val descLabel = JBLabel("<html><small>Click to copy prompt to clipboard</small></html>")
        descLabel.foreground = HytaleTheme.mutedText
        descLabel.alignmentX = Component.LEFT_ALIGNMENT
        card.add(descLabel)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        for (quickPrompt in QUICK_PROMPTS) {
            card.add(createPromptRow(quickPrompt))
            card.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        return card
    }

    private fun createPromptRow(quickPrompt: QuickPrompt): JPanel {
        val row = JPanel(BorderLayout(JBUI.scale(8), 0))
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        // Tag badge
        val tagLabel = JLabel(quickPrompt.tag)
        tagLabel.foreground = HytaleTheme.purpleAccent
        tagLabel.font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scaleFontSize(10f).toInt())
        tagLabel.border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(HytaleTheme.purpleAccent, 4, 1),
            JBUI.Borders.empty(2, 6)
        )
        tagLabel.preferredSize = Dimension(JBUI.scale(70), tagLabel.preferredSize.height)
        row.add(tagLabel, BorderLayout.WEST)

        // Title
        val titleLabel = JLabel(quickPrompt.title)
        titleLabel.foreground = HytaleTheme.textPrimary
        row.add(titleLabel, BorderLayout.CENTER)

        // Copy icon
        val copyIcon = JLabel(AllIcons.Actions.Copy)
        copyIcon.isVisible = false
        row.add(copyIcon, BorderLayout.EAST)

        // Hover effect
        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                copyPromptToClipboard(quickPrompt)
            }

            override fun mouseEntered(e: MouseEvent?) {
                titleLabel.foreground = HytaleTheme.accentColor
                copyIcon.isVisible = true
            }

            override fun mouseExited(e: MouseEvent?) {
                titleLabel.foreground = HytaleTheme.textPrimary
                copyIcon.isVisible = false
            }
        })

        return row
    }

    private fun copyPromptToClipboard(quickPrompt: QuickPrompt) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(quickPrompt.prompt), null)
        notify("Prompt copied: ${quickPrompt.title}", NotificationType.INFORMATION)
    }

    // ==================== RESOURCES CARD ====================

    private fun createResourcesCard(): JPanel {
        val card = HytaleTheme.createCard("Resources")
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(150))

        val links = listOf(
            Triple("MCP Documentation", "Model Context Protocol docs", "https://modelcontextprotocol.io/docs"),
            Triple("Claude Code", "CLI tool documentation", "https://docs.anthropic.com/en/docs/claude-code"),
            Triple("Hytale Docs", "Community documentation", "https://hytale-docs.com"),
        )

        for ((name, desc, url) in links) {
            card.add(HytaleTheme.createLinkRow(name, desc, url))
            card.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        return card
    }

    // ==================== UTILITY ====================

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hytale Plugin")
            .createNotification("Hytale AI", message, type)
            .notify(project)
    }
}
