package com.hytaledocs.intellij.actions

import com.hytaledocs.intellij.services.HytaleDownloaderService
import com.hytaledocs.intellij.services.JavaInstallService
import com.hytaledocs.intellij.services.ServerDownloadService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.nio.file.Paths
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class SetupServerAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val dialog = SetupServerDialog(project)
        if (dialog.showAndGet()) {
            runSetup(project, dialog.installJava, dialog.downloadServer)
        }
    }

    private fun runSetup(project: Project, installJava: Boolean, downloadServer: Boolean) {
        if (!installJava && !downloadServer) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Setting Up Hytale Server",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false

                try {
                    var currentProgress = 0.0
                    val javaWeight = if (installJava) 0.5 else 0.0
                    val serverWeight = if (downloadServer) 0.5 else 0.0
                    val totalWeight = javaWeight + serverWeight

                    // Step 1: Install Java 25 if needed
                    if (installJava) {
                        indicator.text = "Installing Java 25..."
                        val javaService = JavaInstallService.getInstance()

                        javaService.installJava(25) { progress ->
                            val scaledProgress = (progress.progress / 100.0) * (javaWeight / totalWeight)
                            indicator.fraction = scaledProgress
                            indicator.text = progress.message
                        }.get()

                        currentProgress = javaWeight / totalWeight
                        notify(project, "Java 25 installed successfully!", NotificationType.INFORMATION)
                    }

                    // Step 2: Download server files using Hytale Downloader
                    if (downloadServer) {
                        indicator.text = "Downloading server files..."
                        val basePath = project.basePath ?: throw IllegalStateException("No project path")
                        val serverPath = Paths.get(basePath, "server")

                        val downloadService = ServerDownloadService.getInstance(project)
                        downloadService.createServerDirectories(serverPath)

                        val downloaderService = HytaleDownloaderService.getInstance()

                        downloaderService.downloadServerFiles(serverPath) { status ->
                            val baseProgress = currentProgress
                            val scaledProgress = baseProgress + (status.progress / 100.0) * (serverWeight / totalWeight)
                            indicator.fraction = scaledProgress

                            when (status.stage) {
                                HytaleDownloaderService.Stage.AWAITING_AUTH -> {
                                    indicator.text = "Waiting for authentication: ${status.deviceCode}"
                                    SwingUtilities.invokeLater {
                                        notify(
                                            project,
                                            "Enter code ${status.deviceCode} in your browser to authenticate with Hytale",
                                            NotificationType.INFORMATION
                                        )
                                    }
                                }
                                HytaleDownloaderService.Stage.AUTHENTICATED -> {
                                    indicator.text = "Authenticated! Starting download..."
                                }
                                HytaleDownloaderService.Stage.DOWNLOADING_SERVER -> {
                                    indicator.text = "Downloading server files: ${status.progress}%"
                                }
                                HytaleDownloaderService.Stage.EXTRACTING_SERVER -> {
                                    indicator.text = "Extracting server files..."
                                }
                                else -> {
                                    indicator.text = status.message
                                }
                            }
                        }.get()

                        notify(project, "Server files downloaded successfully!", NotificationType.INFORMATION)
                    }

                    indicator.fraction = 1.0
                    indicator.text = "Setup complete!"

                    SwingUtilities.invokeLater {
                        notify(
                            project,
                            "Hytale server setup complete! Open the Hytale tool window to start the server.",
                            NotificationType.INFORMATION
                        )
                    }
                } catch (ex: Exception) {
                    SwingUtilities.invokeLater {
                        notify(
                            project,
                            "Setup failed: ${ex.message}",
                            NotificationType.ERROR
                        )
                    }
                }
            }
        })
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hytale Plugin")
            .createNotification("Hytale", message, type)
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

private class SetupServerDialog(private val project: Project) : DialogWrapper(project) {

    private val installJavaCheckBox = JBCheckBox("Install Java 25 (from Adoptium)")
    private val downloadServerCheckBox = JBCheckBox("Download Server Files (requires Hytale account)")

    val installJava: Boolean get() = installJavaCheckBox.isSelected
    val downloadServer: Boolean get() = downloadServerCheckBox.isSelected

    init {
        title = "Setup Hytale Server"
        init()

        // Check current status and pre-select options
        val javaService = JavaInstallService.getInstance()
        val java25 = javaService.findJava25()

        if (java25 != null) {
            installJavaCheckBox.isSelected = false
            installJavaCheckBox.text = "Install Java 25 (already installed: ${java25.version})"
        } else {
            installJavaCheckBox.isSelected = true
        }

        val basePath = project.basePath
        if (basePath != null) {
            val serverPath = Paths.get(basePath, "server")
            val downloadService = ServerDownloadService.getInstance(project)
            val status = downloadService.hasServerFiles(serverPath)

            if (status.hasServerJar) {
                downloadServerCheckBox.isSelected = false
                downloadServerCheckBox.text = "Download Server Files (already present)"
            } else {
                downloadServerCheckBox.isSelected = true
            }
        } else {
            downloadServerCheckBox.isSelected = true
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)

        val descLabel = JBLabel("Select the components to set up:")
        descLabel.border = JBUI.Borders.emptyBottom(10)
        contentPanel.add(descLabel)

        installJavaCheckBox.border = JBUI.Borders.emptyBottom(5)
        contentPanel.add(installJavaCheckBox)

        downloadServerCheckBox.border = JBUI.Borders.emptyBottom(10)
        contentPanel.add(downloadServerCheckBox)

        val noteLabel = JBLabel("<html><small>Note: Server download requires authentication with your Hytale account.<br/>A browser will open for you to sign in.</small></html>")
        noteLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        contentPanel.add(noteLabel)

        panel.add(contentPanel, BorderLayout.CENTER)
        return panel
    }

    override fun doOKAction() {
        if (!installJava && !downloadServer) {
            return
        }
        super.doOKAction()
    }
}
