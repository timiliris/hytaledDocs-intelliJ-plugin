package com.hytaledocs.intellij.actions

import com.hytaledocs.intellij.run.HytaleServerConfigurationType
import com.hytaledocs.intellij.run.HytaleServerRunConfiguration
import com.intellij.execution.RunManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import java.io.File

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
        val pluginInfo = detectPluginInfo(basePath, project.name)

        if (pluginInfo == null) {
            showNotification(project, "Could not detect plugin info. Make sure manifest.json or build.gradle exists.", NotificationType.ERROR)
            return
        }

        // Update config
        config.pluginJarPath = pluginInfo.jarPath
        config.pluginName = "${pluginInfo.groupId}:${pluginInfo.artifactId}"
        config.buildTask = pluginInfo.buildTask

        // Force save
        runManager.makeStable(existingSettings)

        showNotification(
            project,
            "Run configuration updated:\n• Plugin: ${pluginInfo.groupId}:${pluginInfo.artifactId}\n• JAR: ${pluginInfo.jarPath}",
            NotificationType.INFORMATION
        )

        LOG.info("Manually reconfigured run config: ${pluginInfo.groupId}:${pluginInfo.artifactId}")
    }

    private fun showNotification(project: com.intellij.openapi.project.Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hytale Plugin")
            .createNotification("Hytale Run Configuration", message, type)
            .notify(project)
    }

    private data class PluginInfo(
        val groupId: String,
        val artifactId: String,
        val jarPath: String,
        val buildTask: String
    )

    private fun detectPluginInfo(basePath: String, projectName: String): PluginInfo? {
        // Priority 0: .hytale/project.json
        readHytaleProjectJson(basePath)?.let { return it }

        // Priority 1: manifest.json
        readManifestJson(basePath)?.let { return it }

        // Priority 2: Gradle files
        readGradleFiles(basePath)?.let { return it }

        // Priority 3: Project name fallback
        if (projectName.isNotBlank()) {
            val artifactId = projectName.lowercase().replace(" ", "-")
            return PluginInfo(
                groupId = "com.example",
                artifactId = artifactId,
                jarPath = "build/libs/$artifactId-1.0.0.jar",
                buildTask = "shadowJar"
            )
        }

        return null
    }

    private fun readHytaleProjectJson(basePath: String): PluginInfo? {
        val projectFile = File(basePath, ".hytale/project.json")
        if (!projectFile.exists()) return null

        return try {
            val content = projectFile.readText()

            val groupRegex = """"groupId"\s*:\s*"([^"]+)"""".toRegex()
            val artifactRegex = """"artifactId"\s*:\s*"([^"]+)"""".toRegex()
            val modNameRegex = """"modName"\s*:\s*"([^"]+)"""".toRegex()
            val jarPathRegex = """"jarPath"\s*:\s*"([^"]+)"""".toRegex()
            val buildTaskRegex = """"buildTask"\s*:\s*"([^"]+)"""".toRegex()

            val groupId = groupRegex.find(content)?.groupValues?.get(1) ?: return null
            val artifactId = artifactRegex.find(content)?.groupValues?.get(1) ?: return null
            // modName is the actual plugin name (with spaces/caps)
            val modName = modNameRegex.find(content)?.groupValues?.get(1) ?: artifactId
            val jarPath = jarPathRegex.find(content)?.groupValues?.get(1) ?: "build/libs/$artifactId-1.0.0.jar"
            val buildTask = buildTaskRegex.find(content)?.groupValues?.get(1) ?: "shadowJar"

            PluginInfo(groupId, modName, jarPath, buildTask)  // Use modName for plugin commands
        } catch (e: Exception) {
            null
        }
    }

    private fun readManifestJson(basePath: String): PluginInfo? {
        val manifestFile = File(basePath, "src/main/resources/manifest.json")
        if (!manifestFile.exists()) return null

        return try {
            val content = manifestFile.readText()
            val groupRegex = """"Group"\s*:\s*"([^"]+)"""".toRegex()
            val nameRegex = """"Name"\s*:\s*"([^"]+)"""".toRegex()

            val group = groupRegex.find(content)?.groupValues?.get(1)
            val name = nameRegex.find(content)?.groupValues?.get(1)

            if (group != null && name != null) {
                val isGradle = File(basePath, "build.gradle").exists() ||
                        File(basePath, "build.gradle.kts").exists()

                // artifactId for JAR filename (lowercase, no spaces)
                val jarArtifactId = name.lowercase().replace(" ", "-")

                PluginInfo(
                    groupId = group,
                    artifactId = name,  // Keep original name for plugin commands
                    jarPath = if (isGradle) "build/libs/$jarArtifactId-1.0.0.jar" else "target/$jarArtifactId-1.0.0.jar",
                    buildTask = if (isGradle) "shadowJar" else "package"
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun readGradleFiles(basePath: String): PluginInfo? {
        val settingsGradle = File(basePath, "settings.gradle")
        val settingsGradleKts = File(basePath, "settings.gradle.kts")

        val projectName = when {
            settingsGradle.exists() -> {
                val content = settingsGradle.readText()
                val regex = """rootProject\.name\s*=\s*['"]([^'"]+)['"]""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            settingsGradleKts.exists() -> {
                val content = settingsGradleKts.readText()
                val regex = """rootProject\.name\s*=\s*"([^"]+)"""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            else -> null
        }

        val buildGradle = File(basePath, "build.gradle")
        val buildGradleKts = File(basePath, "build.gradle.kts")

        val groupId = when {
            buildGradle.exists() -> {
                val content = buildGradle.readText()
                val regex = """group\s*=\s*['"]([^'"]+)['"]""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            buildGradleKts.exists() -> {
                val content = buildGradleKts.readText()
                val regex = """group\s*=\s*"([^"]+)"""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            else -> null
        }

        if (projectName != null) {
            val artifactId = projectName.lowercase().replace(" ", "-")
            val baseGroup = groupId?.substringBeforeLast('.') ?: "com.example"

            return PluginInfo(
                groupId = baseGroup,
                artifactId = artifactId,
                jarPath = "build/libs/$artifactId-1.0.0.jar",
                buildTask = "shadowJar"
            )
        }

        return null
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}
