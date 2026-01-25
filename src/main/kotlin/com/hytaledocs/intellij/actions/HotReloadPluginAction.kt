package com.hytaledocs.intellij.actions

import com.hytaledocs.intellij.services.ServerLaunchService
import com.hytaledocs.intellij.util.PluginInfoDetector
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Action to hot reload the plugin on a running Hytale server.
 * Build -> Unload -> Deploy -> Load
 */
class HotReloadPluginAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Hot Reload Plugin", true) {
            override fun run(indicator: ProgressIndicator) {
                performHotReload(project, indicator)
            }
        })
    }

    private fun performHotReload(project: Project, indicator: ProgressIndicator) {
        val basePath = project.basePath ?: run {
            showNotification(project, "Project path not found", NotificationType.ERROR)
            return
        }

        val launchService = ServerLaunchService.getInstance(project)

        // Check if server is running
        if (!launchService.isServerRunning()) {
            showNotification(project, "Server is not running. Start the server first.", NotificationType.WARNING)
            return
        }

        // Detect plugin info
        val detectedInfo = PluginInfoDetector.detect(basePath, project.name) ?: run {
            showNotification(project, "Could not detect plugin info. Check your project configuration.", NotificationType.ERROR)
            return
        }

        // Convert to local PluginInfo format
        val pluginInfo = PluginInfo(
            pluginName = "${detectedInfo.groupId}:${detectedInfo.modName}",
            jarPath = detectedInfo.jarPath,
            buildTask = detectedInfo.buildTask
        )

        try {
            // Step 1: Build
            indicator.text = "Building plugin..."
            indicator.fraction = 0.1
            if (!executeBuild(basePath, pluginInfo.buildTask)) {
                showNotification(project, "Build failed!", NotificationType.ERROR)
                return
            }

            // Step 2: Unload plugin
            indicator.text = "Unloading plugin..."
            indicator.fraction = 0.4
            launchService.sendCommand("plugin unload ${pluginInfo.pluginName}")
            // Wait for server to release file handles
            Thread.sleep(3000)
            // Help JVM release any cached references
            System.gc()
            Thread.sleep(500)

            // Step 3: Deploy
            indicator.text = "Deploying plugin..."
            indicator.fraction = 0.6
            if (!deployPlugin(basePath, pluginInfo)) {
                showNotification(project, "Deploy failed!", NotificationType.ERROR)
                return
            }

            // Step 4: Load plugin
            indicator.text = "Loading plugin..."
            indicator.fraction = 0.9
            launchService.sendCommand("plugin load ${pluginInfo.pluginName}")

            indicator.fraction = 1.0
            showNotification(project, "Plugin reloaded successfully!", NotificationType.INFORMATION)

        } catch (e: Exception) {
            showNotification(project, "Hot reload failed: ${e.message}", NotificationType.ERROR)
        }
    }

    private fun executeBuild(basePath: String, buildTask: String): Boolean {
        val gradleWrapper = findGradleWrapper(basePath) ?: return false

        return try {
            val process = ProcessBuilder(listOf(gradleWrapper, buildTask, "--no-daemon", "-q"))
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()

            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun findGradleWrapper(basePath: String): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val wrapperName = if (isWindows) "gradlew.bat" else "gradlew"
        val wrapper = File(basePath, wrapperName)
        return if (wrapper.exists()) wrapper.absolutePath else null
    }

    private fun deployPlugin(basePath: String, pluginInfo: PluginInfo): Boolean {
        val jarPath = resolveJarPath(basePath, pluginInfo.jarPath) ?: return false
        val modsDir = Path.of(basePath, "server", "mods")

        return try {
            if (!Files.exists(modsDir)) {
                Files.createDirectories(modsDir)
            }

            val baseJarName = jarPath.fileName.toString().substringBeforeLast(".jar")
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")

            if (isWindows) {
                // Windows: mandatory file locking - can't delete/overwrite open files
                // Use timestamped filename, cleanup happens on server start
                val timestamp = System.currentTimeMillis()
                val newFileName = "${baseJarName}-dev-${timestamp}.jar"
                val targetPath = modsDir.resolve(newFileName)
                Files.copy(jarPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            } else {
                // Linux/Mac: advisory locking - can delete open files
                // Just overwrite the same file each time
                val devFileName = "${baseJarName}-dev.jar"
                val targetPath = modsDir.resolve(devFileName)
                Files.deleteIfExists(targetPath)
                Files.copy(jarPath, targetPath)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveJarPath(basePath: String, jarPath: String): Path? {
        // Try relative path
        val relative = Path.of(basePath, jarPath)
        if (Files.exists(relative)) return relative

        // Search in build/libs
        val buildLibs = Path.of(basePath, "build/libs")
        if (Files.exists(buildLibs)) {
            Files.list(buildLibs).use { stream ->
                return stream
                    .filter { it.toString().endsWith(".jar") }
                    .filter { !it.toString().contains("-sources") && !it.toString().contains("-javadoc") }
                    .findFirst()
                    .orElse(null)
            }
        }
        return null
    }

    private fun showNotification(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hytale Plugin")
            .createNotification("Hot Reload", message, type)
            .notify(project)
    }

    /**
     * Internal plugin info for hot reload operations.
     * Contains the plugin name in "group:name" format for server commands.
     */
    private data class PluginInfo(
        val pluginName: String,
        val jarPath: String,
        val buildTask: String
    )

    override fun update(e: AnActionEvent) {
        val project = e.project
        val launchService = project?.let { ServerLaunchService.getInstance(it) }
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")

        // Hot reload disabled on Windows (file locking issues)
        // On Windows, just re-run the server config to restart with fresh build
        if (isWindows) {
            e.presentation.isEnabled = false
            e.presentation.isVisible = false
            return
        }

        // Only enable if server is running (Linux/Mac only)
        e.presentation.isEnabled = launchService?.isServerRunning() == true
        e.presentation.isVisible = project != null
    }
}
