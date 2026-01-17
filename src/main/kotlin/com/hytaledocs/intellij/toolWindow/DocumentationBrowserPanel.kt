package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.services.DocumentationService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingUtilities

/**
 * Embedded browser panel for viewing Hytale documentation within the IDE.
 * Uses JCEF (Chromium Embedded Framework) for rendering.
 */
class DocumentationBrowserPanel(
    private val project: Project
) : JBPanel<DocumentationBrowserPanel>(BorderLayout()), Disposable {

    companion object {
        private val textPrimary = JBColor.namedColor("Label.foreground", JBColor.foreground())
        private val textSecondary = JBColor.namedColor("Label.infoForeground",
            JBColor.namedColor("Label.disabledForeground", JBColor.gray))
    }

    private var browser: JBCefBrowser? = null
    private val urlField = JBTextField()
    private val progressBar = JProgressBar()
    private val statusLabel = JBLabel("")
    private var isJcefSupported = true

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())

        // Check JCEF support
        isJcefSupported = checkJcefSupport()

        if (isJcefSupported) {
            setupBrowserPanel()
        } else {
            setupFallbackPanel()
        }
    }

    private fun checkJcefSupport(): Boolean {
        return try {
            Class.forName("com.intellij.ui.jcef.JBCefBrowser")
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun setupBrowserPanel() {
        // Toolbar
        add(createToolbar(), BorderLayout.NORTH)

        // Browser
        try {
            browser = JBCefBrowser(DocumentationService.BASE_URL)
            browser?.let { b ->
                // Add load handler for progress
                b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadingStateChange(
                        browser: CefBrowser?,
                        isLoading: Boolean,
                        canGoBack: Boolean,
                        canGoForward: Boolean
                    ) {
                        SwingUtilities.invokeLater {
                            progressBar.isIndeterminate = isLoading
                            progressBar.isVisible = isLoading
                            if (!isLoading) {
                                urlField.text = browser?.url ?: ""
                                statusLabel.text = "Ready"
                            } else {
                                statusLabel.text = "Loading..."
                            }
                        }
                    }

                    override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                        SwingUtilities.invokeLater {
                            urlField.text = browser?.url ?: ""
                        }
                    }

                    override fun onLoadError(
                        browser: CefBrowser?,
                        frame: CefFrame?,
                        errorCode: CefLoadHandler.ErrorCode?,
                        errorText: String?,
                        failedUrl: String?
                    ) {
                        SwingUtilities.invokeLater {
                            statusLabel.text = "Error: $errorText"
                            statusLabel.foreground = JBColor.RED
                        }
                    }
                }, b.cefBrowser)

                add(b.component, BorderLayout.CENTER)
                Disposer.register(this, b)
            }
        } catch (e: Exception) {
            setupFallbackPanel()
        }

        // Status bar
        add(createStatusBar(), BorderLayout.SOUTH)
    }

    private fun setupFallbackPanel() {
        // JCEF not available - show fallback message
        val fallbackPanel = JPanel(BorderLayout())
        fallbackPanel.border = JBUI.Borders.empty(20)

        val messagePanel = JPanel()
        messagePanel.layout = javax.swing.BoxLayout(messagePanel, javax.swing.BoxLayout.Y_AXIS)
        messagePanel.isOpaque = false

        val titleLabel = JBLabel("Embedded Browser Not Available")
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD).deriveFont(JBUI.scaleFontSize(16f))
        titleLabel.foreground = textPrimary
        titleLabel.alignmentX = java.awt.Component.CENTER_ALIGNMENT
        messagePanel.add(titleLabel)

        messagePanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))

        val descLabel = JBLabel("<html><center>JCEF (Chromium Embedded Framework) is not available in this IDE.<br>Please use the 'Open in Browser' button to view documentation.</center></html>")
        descLabel.foreground = textSecondary
        descLabel.alignmentX = java.awt.Component.CENTER_ALIGNMENT
        messagePanel.add(descLabel)

        messagePanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(20)))

        val openBrowserButton = JButton("Open Documentation in Browser", AllIcons.Ide.External_link_arrow)
        openBrowserButton.alignmentX = java.awt.Component.CENTER_ALIGNMENT
        openBrowserButton.addActionListener {
            DocumentationService.getInstance().openInBrowser(DocumentationService.BASE_URL)
        }
        messagePanel.add(openBrowserButton)

        fallbackPanel.add(messagePanel, BorderLayout.CENTER)
        add(fallbackPanel, BorderLayout.CENTER)
    }

    private fun createToolbar(): JPanel {
        val toolbar = JPanel(BorderLayout(JBUI.scale(8), 0))
        toolbar.border = JBUI.Borders.empty(8)
        toolbar.isOpaque = false

        // Navigation buttons
        val navPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        navPanel.isOpaque = false

        val backButton = JButton(AllIcons.Actions.Back)
        backButton.toolTipText = "Back"
        backButton.isFocusPainted = false
        backButton.addActionListener { browser?.cefBrowser?.goBack() }
        navPanel.add(backButton)

        val forwardButton = JButton(AllIcons.Actions.Forward)
        forwardButton.toolTipText = "Forward"
        forwardButton.isFocusPainted = false
        forwardButton.addActionListener { browser?.cefBrowser?.goForward() }
        navPanel.add(forwardButton)

        val refreshButton = JButton(AllIcons.Actions.Refresh)
        refreshButton.toolTipText = "Refresh"
        refreshButton.isFocusPainted = false
        refreshButton.addActionListener { browser?.cefBrowser?.reload() }
        navPanel.add(refreshButton)

        val homeButton = JButton(AllIcons.Nodes.HomeFolder)
        homeButton.toolTipText = "Home"
        homeButton.isFocusPainted = false
        homeButton.addActionListener { loadUrl(DocumentationService.BASE_URL) }
        navPanel.add(homeButton)

        toolbar.add(navPanel, BorderLayout.WEST)

        // URL field
        urlField.text = DocumentationService.BASE_URL
        urlField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    loadUrl(urlField.text)
                }
            }
        })
        toolbar.add(urlField, BorderLayout.CENTER)

        // External browser button
        val externalButton = JButton(AllIcons.Ide.External_link_arrow)
        externalButton.toolTipText = "Open in external browser"
        externalButton.isFocusPainted = false
        externalButton.addActionListener {
            val url = browser?.cefBrowser?.url ?: DocumentationService.BASE_URL
            DocumentationService.getInstance().openInBrowser(url)
        }
        toolbar.add(externalButton, BorderLayout.EAST)

        return toolbar
    }

    private fun createStatusBar(): JPanel {
        val statusBar = JPanel(BorderLayout(JBUI.scale(8), 0))
        statusBar.border = JBUI.Borders.empty(4, 8)
        statusBar.isOpaque = false

        statusLabel.foreground = textSecondary
        statusLabel.font = statusLabel.font.deriveFont(JBUI.scaleFontSize(11f))
        statusBar.add(statusLabel, BorderLayout.WEST)

        progressBar.isIndeterminate = true
        progressBar.isVisible = false
        progressBar.preferredSize = java.awt.Dimension(100, JBUI.scale(4))
        statusBar.add(progressBar, BorderLayout.EAST)

        return statusBar
    }

    /**
     * Load a URL in the embedded browser.
     */
    fun loadUrl(url: String) {
        if (isJcefSupported && browser != null) {
            urlField.text = url
            browser?.cefBrowser?.loadURL(url)
        } else {
            DocumentationService.getInstance().openInBrowser(url)
        }
    }

    /**
     * Get the current URL.
     */
    fun getCurrentUrl(): String? {
        return browser?.cefBrowser?.url
    }

    override fun dispose() {
        browser = null
    }
}
