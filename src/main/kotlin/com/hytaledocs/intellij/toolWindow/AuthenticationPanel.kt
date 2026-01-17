package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.services.AuthenticationService
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Modern authentication panel that displays when server needs authentication.
 * Shows the device code prominently with copy and open browser buttons.
 */
class AuthenticationPanel : JBPanel<AuthenticationPanel>(BorderLayout()) {

    companion object {
        // Panel-specific color for authentication code display (purple) - uses IDE theme
        private val codeColor = HytaleTheme.purpleAccent
    }

    private val titleLabel = JBLabel("Authentication Required")
    private val statusLabel = JBLabel("")
    private val codeLabel = JBLabel("")
    private val messageLabel = JBLabel("")
    private val copyButton: JButton
    private val openBrowserButton: JButton
    private val retryButton: JButton

    private var currentSession: AuthenticationService.AuthSession? = null

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.empty(16)

        // Main card
        val card = JPanel(BorderLayout())
        card.background = HytaleTheme.cardBackground
        card.border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(HytaleTheme.cardBorder, 12, 1),
            JBUI.Borders.empty(20)
        )

        // Content panel
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = false

        // Icon and title row
        val headerPanel = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0))
        headerPanel.isOpaque = false

        val iconLabel = JLabel(AllIcons.General.User)
        headerPanel.add(iconLabel)

        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(16f))
        titleLabel.foreground = HytaleTheme.textPrimary
        headerPanel.add(titleLabel)

        headerPanel.alignmentX = Component.CENTER_ALIGNMENT
        contentPanel.add(headerPanel)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(16)))

        // Status label
        statusLabel.foreground = HytaleTheme.warningColor
        statusLabel.font = statusLabel.font.deriveFont(JBUI.scaleFontSize(12f))
        statusLabel.alignmentX = Component.CENTER_ALIGNMENT
        contentPanel.add(statusLabel)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Code display panel
        val codePanel = JPanel(BorderLayout())
        codePanel.isOpaque = false
        codePanel.alignmentX = Component.CENTER_ALIGNMENT
        codePanel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(60))

        val codeBox = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0))
        codeBox.background = HytaleTheme.cardBackground
        codeBox.border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(codeColor, 8, 2),
            JBUI.Borders.empty(12, 24)
        )

        codeLabel.font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scaleFontSize(24f).toInt())
        codeLabel.foreground = codeColor
        codeBox.add(codeLabel)

        codePanel.add(codeBox, BorderLayout.CENTER)
        contentPanel.add(codePanel)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(16)))

        // Message label
        messageLabel.foreground = HytaleTheme.mutedText
        messageLabel.font = messageLabel.font.deriveFont(JBUI.scaleFontSize(12f))
        messageLabel.alignmentX = Component.CENTER_ALIGNMENT
        contentPanel.add(messageLabel)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(20)))

        // Buttons panel
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0))
        buttonsPanel.isOpaque = false
        buttonsPanel.alignmentX = Component.CENTER_ALIGNMENT

        copyButton = createButton("Copy Code", AllIcons.Actions.Copy)
        copyButton.addActionListener { copyCodeToClipboard() }
        buttonsPanel.add(copyButton)

        openBrowserButton = createButton("Open Browser", AllIcons.Ide.External_link_arrow, isPrimary = true)
        openBrowserButton.addActionListener { openAuthUrl() }
        buttonsPanel.add(openBrowserButton)

        retryButton = createButton("Retry", AllIcons.Actions.Refresh)
        retryButton.addActionListener { triggerRetry() }
        retryButton.isVisible = false
        buttonsPanel.add(retryButton)

        contentPanel.add(buttonsPanel)

        card.add(contentPanel, BorderLayout.CENTER)
        add(card, BorderLayout.NORTH)

        // Initially hidden
        isVisible = false
    }

    private fun createButton(text: String, icon: Icon? = null, isPrimary: Boolean = false): JButton {
        return JButton(text, icon).apply {
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(8, 16)
            if (isPrimary) {
                background = HytaleTheme.accentColor
                foreground = JBColor.WHITE
                isOpaque = true
            }
        }
    }

    /**
     * Update the panel with new authentication session data.
     */
    fun updateSession(session: AuthenticationService.AuthSession?) {
        currentSession = session

        if (session == null) {
            isVisible = false
            return
        }

        when (session.state) {
            AuthenticationService.AuthState.AWAITING_CODE -> {
                isVisible = true
                titleLabel.text = "Authentication Required"
                statusLabel.text = "Waiting for authentication code..."
                statusLabel.foreground = HytaleTheme.warningColor
                codeLabel.text = "..."
                messageLabel.text = "The server is requesting authentication"
                copyButton.isEnabled = false
                openBrowserButton.isEnabled = false
                retryButton.isVisible = false
            }

            AuthenticationService.AuthState.CODE_DISPLAYED -> {
                isVisible = true
                titleLabel.text = "Enter Code in Browser"
                statusLabel.text = "Authentication code ready"
                statusLabel.foreground = HytaleTheme.warningColor
                codeLabel.text = session.deviceCode
                messageLabel.text = "Enter this code at ${getSimplifiedUrl(session.verificationUrl)}"
                copyButton.isEnabled = true
                openBrowserButton.isEnabled = true
                retryButton.isVisible = false
            }

            AuthenticationService.AuthState.AUTHENTICATING -> {
                isVisible = true
                titleLabel.text = "Authenticating..."
                statusLabel.text = "Waiting for confirmation"
                statusLabel.foreground = HytaleTheme.accentColor
                messageLabel.text = "Complete authentication in your browser"
                copyButton.isEnabled = false
                openBrowserButton.isEnabled = true
                retryButton.isVisible = false
            }

            AuthenticationService.AuthState.SUCCESS -> {
                isVisible = true
                titleLabel.text = "Authentication Successful"
                statusLabel.text = "Server is authenticated"
                statusLabel.foreground = HytaleTheme.successColor
                codeLabel.text = "\u2713" // Checkmark
                messageLabel.text = session.message ?: "You can now use the server"
                copyButton.isVisible = false
                openBrowserButton.isVisible = false
                retryButton.isVisible = false

                // Auto-hide after delay
                Timer(3000) { isVisible = false }.apply {
                    isRepeats = false
                    start()
                }
            }

            AuthenticationService.AuthState.FAILED -> {
                isVisible = true
                titleLabel.text = "Authentication Failed"
                statusLabel.text = "Please try again"
                statusLabel.foreground = HytaleTheme.errorColor
                codeLabel.text = "\u2717" // X mark
                messageLabel.text = session.message ?: "Authentication failed"
                copyButton.isVisible = false
                openBrowserButton.isVisible = false
                retryButton.isVisible = true
            }

            AuthenticationService.AuthState.IDLE -> {
                isVisible = false
            }
        }

        revalidate()
        repaint()
    }

    private fun getSimplifiedUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            uri.host ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun copyCodeToClipboard() {
        currentSession?.deviceCode?.let { code ->
            val selection = StringSelection(code)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)

            // Visual feedback
            val originalText = copyButton.text
            copyButton.text = "Copied!"
            Timer(1500) {
                copyButton.text = originalText
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    private fun openAuthUrl() {
        currentSession?.verificationUrl?.let { url ->
            BrowserUtil.browse(url)
        }
    }

    private fun triggerRetry() {
        AuthenticationService.getInstance().resetSession()
        isVisible = false
    }
}
