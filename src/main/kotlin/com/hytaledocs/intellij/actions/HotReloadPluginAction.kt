package com.hytaledocs.intellij.actions

import com.hytaledocs.intellij.services.ServerLaunchService
import com.hytaledocs.intellij.util.PluginInfoDetector
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/**
 * Action to hot reload the plugin on a running Hytale server.
 * Build -> Unload -> Deploy -> Load
 *
 * Supports Windows with robust file deployment strategy:
 * - Shadow copy to temp location first
 * - Atomic move with REPLACE_EXISTING
 * - Timestamped fallback if atomic move fails
 * - Retry logic with exponential backoff
 * - Automatic cleanup of old timestamped JARs
 */
class HotReloadPluginAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(HotReloadPluginAction::class.java)
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS_MS = listOf(1000L, 2000L, 4000L) // Exponential backoff
        private const val MAX_OLD_JARS_TO_KEEP = 2
    }

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

        LOG.info("Starting hot reload for plugin: ${pluginInfo.pluginName}")

        try {
            // Step 1: Build
            indicator.text = "Building plugin..."
            indicator.fraction = 0.1
            LOG.info("Building plugin with task: ${pluginInfo.buildTask}")
            if (!executeBuild(basePath, pluginInfo.buildTask)) {
                LOG.warn("Build failed for plugin: ${pluginInfo.pluginName}")
                showNotification(project, "Build failed!", NotificationType.ERROR)
                return
            }
            LOG.info("Build completed successfully")

            // Step 2: Unload plugin
            indicator.text = "Unloading plugin..."
            indicator.fraction = 0.4
            LOG.info("Unloading plugin: ${pluginInfo.pluginName}")
            launchService.sendCommand("plugin unload ${pluginInfo.pluginName}")
            // Wait for server to release file handles
            Thread.sleep(3000)
            // Help JVM release any cached references
            System.gc()
            Thread.sleep(500)

            // Step 3: Deploy with retry logic
            indicator.text = "Deploying plugin..."
            indicator.fraction = 0.6
            val deployResult = deployJarWithRetry(basePath, pluginInfo)
            if (!deployResult.success) {
                LOG.error("Deploy failed after ${MAX_RETRIES} attempts: ${deployResult.error}")
                showNotification(project, "Deploy failed: ${deployResult.error}", NotificationType.ERROR)
                return
            }
            LOG.info("Plugin deployed successfully to: ${deployResult.deployedPath}")

            // Step 4: Load plugin
            indicator.text = "Loading plugin..."
            indicator.fraction = 0.9
            LOG.info("Loading plugin: ${pluginInfo.pluginName}")
            launchService.sendCommand("plugin load ${pluginInfo.pluginName}")

            indicator.fraction = 1.0
            showNotification(project, "Plugin reloaded successfully!", NotificationType.INFORMATION)
            LOG.info("Hot reload completed successfully for: ${pluginInfo.pluginName}")

        } catch (e: Exception) {
            LOG.error("Hot reload failed with exception", e)
            showNotification(project, "Hot reload failed: ${e.message}", NotificationType.ERROR)
        }
    }

    private fun executeBuild(basePath: String, buildTask: String): Boolean {
        val gradleWrapper = findGradleWrapper(basePath)
        val mavenWrapper = findMavenWrapper(basePath)
        val globalGradle = findGlobalTool("gradle")
        val globalMaven = findGlobalTool("mvn")

        return try {
            val command = when {
                gradleWrapper != null -> {
                    val gradleTasks = parseBuildTasks(buildTask, "shadowJar")
                    listOf(gradleWrapper) + gradleTasks + listOf("--no-daemon", "-q")
                }
                mavenWrapper != null -> {
                    val mavenGoals = normalizeMavenGoals(buildTask)
                    listOf(mavenWrapper) + mavenGoals + listOf("-q")
                }
                globalGradle != null && hasGradleBuildFile(basePath) -> {
                    val gradleTasks = parseBuildTasks(buildTask, "shadowJar")
                    listOf(globalGradle) + gradleTasks + listOf("--no-daemon", "-q")
                }
                globalMaven != null && hasMavenBuildFile(basePath) -> {
                    val mavenGoals = normalizeMavenGoals(buildTask)
                    listOf(globalMaven) + mavenGoals + listOf("-q")
                }
                else -> return false
            }

            val process = ProcessBuilder(command)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()

            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            LOG.error("Build execution failed", e)
            false
        }
    }

    private fun findGradleWrapper(basePath: String): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val wrapperName = if (isWindows) "gradlew.bat" else "gradlew"
        val wrapper = File(basePath, wrapperName)
        if (!isWindows && wrapper.exists()) {
            wrapper.setExecutable(true)
        }
        return if (wrapper.exists()) wrapper.absolutePath else null
    }

    private fun findMavenWrapper(basePath: String): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val wrapperName = if (isWindows) "mvnw.cmd" else "mvnw"
        val wrapper = File(basePath, wrapperName)
        if (!isWindows && wrapper.exists()) {
            wrapper.setExecutable(true)
        }
        return if (wrapper.exists()) wrapper.absolutePath else null
    }

    private fun hasGradleBuildFile(basePath: String): Boolean {
        return File(basePath, "build.gradle").exists() || File(basePath, "build.gradle.kts").exists()
    }

    private fun hasMavenBuildFile(basePath: String): Boolean {
        return File(basePath, "pom.xml").exists()
    }

    private fun findGlobalTool(name: String): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        return try {
            val process = if (isWindows) ProcessBuilder("where", name).start() else ProcessBuilder("which", name).start()
            val results = process.inputStream.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
            process.waitFor()
            if (process.exitValue() != 0 || results.isEmpty()) return null

            if (!isWindows) return results.first()
            val lower = results.associateWith { it.lowercase() }
            lower.entries.firstOrNull { it.value.endsWith(".exe") }?.key
                ?: lower.entries.firstOrNull { it.value.endsWith(".cmd") }?.key
                ?: lower.entries.firstOrNull { it.value.endsWith(".bat") }?.key
                ?: results.first()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseBuildTasks(value: String, fallback: String): List<String> {
        val tasks = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (tasks.isEmpty()) listOf(fallback) else tasks
    }

    private fun normalizeMavenGoals(value: String): List<String> {
        val goals = parseBuildTasks(value, "package")
        val gradleOnlyTasks = setOf("build", "shadowJar", "jar", "assemble")
        return goals.map { goal -> if (goal in gradleOnlyTasks) "package" else goal }
    }

    /**
     * Result of a JAR deployment operation.
     */
    private data class DeployResult(
        val success: Boolean,
        val deployedPath: Path? = null,
        val error: String? = null
    )

    /**
     * Deploys a JAR file with retry logic and Windows-safe file handling.
     *
     * Strategy:
     * 1. Copy source JAR to a temp file (shadow copy)
     * 2. Try atomic move with REPLACE_EXISTING
     * 3. If atomic move fails (file locked), use timestamped filename as fallback
     * 4. Retry with exponential backoff on failure
     * 5. Clean up old timestamped JARs on success
     */
    private fun deployJarWithRetry(basePath: String, pluginInfo: PluginInfo): DeployResult {
        val jarPath = resolveJarPath(basePath, pluginInfo.jarPath)
            ?: return DeployResult(false, error = "Source JAR not found: ${pluginInfo.jarPath}")

        val modsDir = Path.of(basePath, "server", "mods")

        try {
            if (!Files.exists(modsDir)) {
                Files.createDirectories(modsDir)
                LOG.info("Created mods directory: $modsDir")
            }
        } catch (e: Exception) {
            return DeployResult(false, error = "Failed to create mods directory: ${e.message}")
        }

        val baseJarName = jarPath.fileName.toString().substringBeforeLast(".jar")
        val devFileName = "${baseJarName}-dev.jar"
        val targetPath = modsDir.resolve(devFileName)

        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                LOG.info("Deploy attempt $attempt/$MAX_RETRIES for: $devFileName")

                // Step 1: Create shadow copy in temp location
                val tempFile = Files.createTempFile("hytale-deploy-", ".jar")
                try {
                    Files.copy(jarPath, tempFile, StandardCopyOption.REPLACE_EXISTING)
                    LOG.debug("Created shadow copy at: $tempFile")

                    // Step 2: Try atomic move to target
                    try {
                        Files.move(
                            tempFile,
                            targetPath,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                        LOG.info("Atomic move succeeded to: $targetPath")

                        // Success! Clean up old timestamped JARs
                        cleanupOldTimestampedJars(modsDir, baseJarName)

                        return DeployResult(true, deployedPath = targetPath)
                    } catch (atomicEx: Exception) {
                        LOG.debug("Atomic move failed (${atomicEx.message}), trying non-atomic approach")

                        // Step 3: Atomic move failed - try regular move/copy
                        try {
                            // Try to delete existing file first
                            Files.deleteIfExists(targetPath)
                            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
                            LOG.info("Non-atomic move succeeded to: $targetPath")

                            cleanupOldTimestampedJars(modsDir, baseJarName)
                            return DeployResult(true, deployedPath = targetPath)
                        } catch (moveEx: Exception) {
                            LOG.debug("Non-atomic move failed (${moveEx.message}), falling back to timestamped filename")

                            // Step 4: File is locked - use timestamped filename as fallback
                            val timestamp = System.currentTimeMillis()
                            val timestampedFileName = "${baseJarName}-dev-${timestamp}.jar"
                            val timestampedPath = modsDir.resolve(timestampedFileName)

                            Files.copy(jarPath, timestampedPath, StandardCopyOption.REPLACE_EXISTING)
                            LOG.info("Deployed with timestamped filename: $timestampedPath")

                            // Clean up old timestamped JARs (keeping the one we just created)
                            cleanupOldTimestampedJars(modsDir, baseJarName)

                            return DeployResult(true, deployedPath = timestampedPath)
                        }
                    }
                } finally {
                    // Clean up temp file if it still exists
                    try {
                        Files.deleteIfExists(tempFile)
                    } catch (e: Exception) {
                        LOG.debug("Failed to delete temp file: $tempFile")
                    }
                }
            } catch (e: Exception) {
                lastException = e
                LOG.warn("Deploy attempt $attempt failed: ${e.message}")

                if (attempt < MAX_RETRIES) {
                    val delay = RETRY_DELAYS_MS[attempt - 1]
                    LOG.info("Retrying in ${delay}ms...")
                    Thread.sleep(delay)

                    // Try to help release file handles
                    System.gc()
                    Thread.sleep(100)
                }
            }
        }

        return DeployResult(
            false,
            error = "Failed after $MAX_RETRIES attempts: ${lastException?.message ?: "Unknown error"}"
        )
    }

    /**
     * Cleans up old timestamped JAR files, keeping only the most recent ones.
     * This prevents the mods directory from filling up with old dev JARs.
     */
    private fun cleanupOldTimestampedJars(modsDir: Path, baseJarName: String) {
        try {
            val pattern = Regex("${Regex.escape(baseJarName)}-dev(-\\d+)?\\.jar")

            val devJars = Files.list(modsDir).use { stream ->
                stream
                    .filter { path ->
                        val fileName = path.fileName.toString()
                        pattern.matches(fileName)
                    }
                    .sorted { a, b ->
                        // Sort by modification time, newest first
                        Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a))
                    }
                    .toList()
            }

            // Keep only the most recent JARs
            if (devJars.size > MAX_OLD_JARS_TO_KEEP) {
                val toDelete = devJars.drop(MAX_OLD_JARS_TO_KEEP)
                for (jar in toDelete) {
                    try {
                        Files.deleteIfExists(jar)
                        LOG.info("Cleaned up old dev JAR: ${jar.name}")
                    } catch (e: Exception) {
                        // File might still be locked by server, ignore
                        LOG.debug("Could not delete old JAR (may be in use): ${jar.name}")
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to cleanup old timestamped JARs", e)
            // Non-fatal, continue execution
        }
    }

    private fun resolveJarPath(basePath: String, jarPath: String): Path? {
        // Try relative path
        val relative = Path.of(basePath, jarPath)
        if (Files.exists(relative)) return relative

        val absolute = Path.of(jarPath)
        if (Files.exists(absolute)) return absolute

        // Search common outputs
        val outputDirs = listOf(Path.of(basePath, "build/libs"), Path.of(basePath, "target"))
        for (dir in outputDirs) {
            if (Files.exists(dir)) {
                Files.list(dir).use { stream ->
                    val found = stream
                        .filter { it.toString().endsWith(".jar") }
                        .filter { !it.toString().contains("-sources") && !it.toString().contains("-javadoc") }
                        .findFirst()
                        .orElse(null)
                    if (found != null) return found
                }
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

        // Hot reload is now available on all platforms (including Windows)
        // Only enable if server is running
        e.presentation.isEnabled = launchService?.isServerRunning() == true
        e.presentation.isVisible = project != null
    }
}
