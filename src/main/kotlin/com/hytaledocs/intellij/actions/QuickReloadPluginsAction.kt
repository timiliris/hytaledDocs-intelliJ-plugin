package com.hytaledocs.intellij.actions

import com.hytaledocs.intellij.services.ServerLaunchService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class QuickReloadPluginsAction : AnAction(
    "Reload Plugins",
    "Send /reload plugins command to the running Hytale server",
    AllIcons.Actions.Refresh
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val launchService = ServerLaunchService.getInstance(project)

        val success = launchService.sendCommand("/reload plugins")

        if (success) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Hytale Plugin")
                .createNotification(
                    "Plugins Reloading",
                    "Sent /reload plugins command to the server",
                    NotificationType.INFORMATION
                )
                .notify(project)
        } else {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Hytale Plugin")
                .createNotification(
                    "Reload Failed",
                    "Failed to send reload command. Is the server running?",
                    NotificationType.ERROR
                )
                .notify(project)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val launchService = ServerLaunchService.getInstance(project)
        e.presentation.isEnabled = launchService.isServerRunning()
        e.presentation.isVisible = true
    }
}
