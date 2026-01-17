package com.hytaledocs.intellij.actions

import com.hytaledocs.intellij.services.ServerDownloadService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import java.nio.file.Paths

class DownloadServerJarAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val serverPath = Paths.get(basePath, "server")
        val downloadService = ServerDownloadService.getInstance(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Downloading Hytale Server JAR",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false

                try {
                    downloadService.createServerDirectories(serverPath)

                    downloadService.downloadServerJar(serverPath) { progress ->
                        indicator.fraction = progress.progress / 100.0
                        indicator.text = progress.message
                    }.get()

                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Hytale Plugin")
                        .createNotification(
                            "Hytale Server JAR Downloaded",
                            "Successfully downloaded to $serverPath",
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

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
