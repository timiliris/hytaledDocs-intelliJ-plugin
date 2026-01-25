package com.hytaledocs.intellij.actions

import com.hytaledocs.intellij.run.HytaleServerConfigurationType
import com.hytaledocs.intellij.run.HytaleServerRunConfiguration
import com.hytaledocs.intellij.util.PluginInfoDetector
import com.intellij.execution.RunManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger

/**
 * Action to manually reconfigure the Hytale Server run configuration
 * by detecting plugin info from project files.
 */
class ReconfigureRunConfigAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(ReconfigureRunConfigAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val runManager = RunManager.getInstance(project)
        val existingSettings = runManager.allSettings
            .find { it.type is HytaleServerConfigurationType }

        val config = existingSettings?.configuration as? HytaleServerRunConfiguration

        if (config == null) {
            showNotification(project, "No Hytale Server run configuration found", NotificationType.WARNING)
            return
        }

        // Try to detect plugin info
        val pluginInfo = PluginInfoDetector.detect(basePath, project.name)

        if (pluginInfo == null) {
            showNotification(project, "Could not detect plugin info. Make sure manifest.json or build.gradle exists.", NotificationType.ERROR)
            return
        }

        // Update config
        config.pluginJarPath = pluginInfo.jarPath
        config.pluginName = "${pluginInfo.groupId}:${pluginInfo.modName}"
        config.buildTask = pluginInfo.buildTask

        // Force save
        runManager.makeStable(existingSettings)

        showNotification(
            project,
            "Run configuration updated:\n• Plugin: ${pluginInfo.groupId}:${pluginInfo.modName}\n• JAR: ${pluginInfo.jarPath}",
            NotificationType.INFORMATION
        )

        LOG.info("Manually reconfigured run config: ${pluginInfo.groupId}:${pluginInfo.modName}")
    }

    private fun showNotification(project: com.intellij.openapi.project.Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hytale Plugin")
            .createNotification("Hytale Run Configuration", message, type)
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}
