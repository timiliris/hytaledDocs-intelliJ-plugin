package com.hytaledocs.intellij.actions

import com.hytaledocs.intellij.services.MavenMetadataService
import com.hytaledocs.intellij.services.ServerDownloadService
import com.hytaledocs.intellij.services.ServerVersionCacheService
import com.hytaledocs.intellij.settings.HytaleAppSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Action to download the Hytale server JAR from Maven repository.
 * Allows selecting a specific version or using the latest.
 */
class DownloadServerJarAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val serverPath = Paths.get(basePath, "server")
        val downloadService = ServerDownloadService.getInstance(project)
        val mavenService = MavenMetadataService.getInstance()
        val settings = HytaleAppSettings.getInstance()

        // Show version selection popup
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Fetching Available Versions",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Fetching versions from Maven repository..."

                try {
                    val versions = mavenService.getAvailableVersions().get(30, TimeUnit.SECONDS)

                    if (versions.isEmpty()) {
                        showError(project, "No versions available from Maven repository")
                        return
                    }

                    // Build popup items
                    val items = mutableListOf<VersionItem>()
                    items.add(VersionItem("Latest (${versions.first().version})", versions.first(), isLatest = true))

                    versions.forEach { version ->
                        val cached = ServerVersionCacheService.getInstance().isVersionCached(version.version)
                        val label = if (cached) {
                            "${version.getDisplayName()} [cached]"
                        } else {
                            version.getDisplayName()
                        }
                        items.add(VersionItem(label, version, isLatest = false))
                    }

                    // Show popup on EDT
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        val popup = JBPopupFactory.getInstance().createListPopup(
                            object : BaseListPopupStep<VersionItem>("Select Server Version", items) {
                                override fun onChosen(selectedValue: VersionItem, finalChoice: Boolean): PopupStep<*>? {
                                    if (finalChoice) {
                                        downloadVersion(project, serverPath, downloadService, selectedValue.version)
                                    }
                                    return PopupStep.FINAL_CHOICE
                                }

                                override fun getTextFor(value: VersionItem): String = value.label
                            }
                        )
                        popup.showCenteredInCurrentWindow(project)
                    }
                } catch (ex: Exception) {
                    showError(project, "Failed to fetch versions: ${ex.message}")
                }
            }
        })
    }

    private data class VersionItem(
        val label: String,
        val version: MavenMetadataService.ServerVersion,
        val isLatest: Boolean
    )

    private fun downloadVersion(
        project: com.intellij.openapi.project.Project,
        serverPath: java.nio.file.Path,
        downloadService: ServerDownloadService,
        version: MavenMetadataService.ServerVersion
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Downloading Hytale Server ${version.version}",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false

                try {
                    downloadService.createServerDirectories(serverPath)

                    downloadService.downloadServerJarFromMaven(
                        serverPath,
                        version.version
                    ) { progress ->
                        indicator.fraction = progress.progress / 100.0
                        indicator.text = progress.message
                    }.exceptionally { e ->
                        throw RuntimeException("Download failed: ${e.message}", e)
                    }.get(300, TimeUnit.SECONDS)

                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Hytale Plugin")
                        .createNotification(
                            "Hytale Server Downloaded",
                            "Version ${version.version} downloaded to $serverPath",
                            NotificationType.INFORMATION
                        )
                        .notify(project)
                } catch (ex: Exception) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Hytale Plugin")
                        .createNotification(
                            "Download Failed",
                            "Failed to download server JAR: ${ex.message}",
                            NotificationType.ERROR
                        )
                        .notify(project)
                }
            }
        })
    }

    private fun showError(project: com.intellij.openapi.project.Project, message: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Hytale Plugin")
                .createNotification(
                    "Maven Error",
                    message,
                    NotificationType.ERROR
                )
                .notify(project)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
        e.presentation.text = "Download Server JAR (Maven)"
        e.presentation.description = "Download the Hytale server JAR from Maven repository with version selection"
    }
}
