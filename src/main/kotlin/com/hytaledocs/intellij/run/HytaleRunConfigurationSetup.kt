package com.hytaledocs.intellij.run

import com.hytaledocs.intellij.util.PluginInfoDetector
import com.intellij.execution.RunManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.io.File

/**
 * Startup activity that creates a default Hytale Server run configuration
 * if one doesn't exist and the project appears to be a Hytale plugin project.
 */
class HytaleRunConfigurationSetup : ProjectActivity {

    companion object {
        private val LOG = Logger.getInstance(HytaleRunConfigurationSetup::class.java)
    }

    override suspend fun execute(project: Project) {
        // Check if this looks like a Hytale plugin project
        if (!isHytaleProject(project)) {
            LOG.info("Not a Hytale project, skipping run configuration setup")
            return
        }

        // Check if we already have a Hytale Server run configuration
        val runManager = RunManager.getInstance(project)
        val existingSettings = runManager.allSettings
            .find { it.type is HytaleServerConfigurationType }

        if (existingSettings != null) {
            val existingConfig = existingSettings.configuration as? HytaleServerRunConfiguration
            // If config exists but is incomplete (missing plugin name), try to reconfigure
            if (existingConfig != null && existingConfig.pluginName.isBlank()) {
                LOG.info("Existing config has empty plugin name, attempting to reconfigure")
                val pluginInfo = detectPluginInfo(project)
                if (pluginInfo != null) {
                    existingConfig.pluginJarPath = pluginInfo.jarPath
                    existingConfig.pluginName = "${pluginInfo.groupId}:${pluginInfo.artifactId}"
                    existingConfig.buildTask = pluginInfo.buildTask
                    // Force save the configuration
                    runManager.makeStable(existingSettings)
                    LOG.info("Reconfigured existing run config: ${pluginInfo.groupId}:${pluginInfo.artifactId}")
                } else {
                    LOG.warn("Could not detect plugin info to reconfigure")
                }
            } else {
                LOG.info("Hytale Server run configuration already exists and is configured")
            }
            return
        }

        // Try to detect plugin info from various sources
        val pluginInfo = detectPluginInfo(project)
        if (pluginInfo == null) {
            LOG.warn("Could not detect plugin info, creating config with defaults")
            createDefaultRunConfiguration(project)
            return
        }

        LOG.info("Detected plugin info: ${pluginInfo.groupId}:${pluginInfo.artifactId}")

        // Create the run configuration
        createHytaleServerRunConfiguration(project, pluginInfo)
    }

    private fun isHytaleProject(project: Project): Boolean {
        val basePath = project.basePath ?: return false

        // Check for Hytale-specific indicators
        val indicators = listOf(
            File(basePath, ".hytale/project.json"),
            File(basePath, "server/HytaleServer.jar"),
            File(basePath, "libs/HytaleServer.jar"),
            File(basePath, "src/main/resources/manifest.json")
        )

        val hasIndicator = indicators.any { it.exists() }

        // Also check for Hytale dependency in build.gradle
        val buildGradle = File(basePath, "build.gradle")
        val buildGradleKts = File(basePath, "build.gradle.kts")
        val hasHytaleDep = when {
            buildGradle.exists() -> buildGradle.readText().contains("HytaleServer")
            buildGradleKts.exists() -> buildGradleKts.readText().contains("HytaleServer")
            else -> false
        }

        return hasIndicator || hasHytaleDep
    }

    /**
     * Detects plugin info from project files using the shared utility.
     */
    private fun detectPluginInfo(project: Project): PluginInfoDetector.PluginInfo? {
        val basePath = project.basePath ?: return null
        return PluginInfoDetector.detect(basePath, project.name)
    }


    private fun createDefaultRunConfiguration(project: Project) {
        val runManager = RunManager.getInstance(project)
        val configType = HytaleServerConfigurationType.getInstance()
        val factory = configType.configurationFactories.first()

        val settings = runManager.createConfiguration("Hytale Server", factory)
        val config = settings.configuration as HytaleServerRunConfiguration

        // Set defaults without plugin info
        config.buildBeforeRun = true
        config.buildTask = "shadowJar"
        config.deployPlugin = true
        config.pluginJarPath = "" // User needs to configure
        config.pluginName = "" // User needs to configure
        config.serverPath = "server"
        config.minMemory = "2G"
        config.maxMemory = "8G"
        config.port = 5520
        config.authMode = "authenticated"
        config.allowOp = true
        config.acceptEarlyPlugins = true
        config.hotReloadEnabled = true

        // Make configuration permanent (not temporary) and editable
        settings.isTemporary = false
        runManager.addConfiguration(settings)
        runManager.makeStable(settings)
        runManager.selectedConfiguration = settings

        LOG.info("Created default Hytale Server run configuration (needs manual plugin config)")
    }

    private fun createHytaleServerRunConfiguration(project: Project, pluginInfo: PluginInfoDetector.PluginInfo) {
        val runManager = RunManager.getInstance(project)
        val configType = HytaleServerConfigurationType.getInstance()
        val factory = configType.configurationFactories.first()

        val settings = runManager.createConfiguration("Hytale Server", factory)
        val config = settings.configuration as HytaleServerRunConfiguration

        // Configure with detected values
        config.buildBeforeRun = true
        config.buildTask = pluginInfo.buildTask
        config.deployPlugin = true
        config.pluginJarPath = pluginInfo.jarPath
        config.pluginName = "${pluginInfo.groupId}:${pluginInfo.modName}"
        config.serverPath = "server"
        config.minMemory = "2G"
        config.maxMemory = "8G"
        config.port = 5520
        config.authMode = "authenticated"
        config.allowOp = true
        config.acceptEarlyPlugins = true
        config.hotReloadEnabled = true

        // Make configuration permanent (not temporary) and editable
        settings.isTemporary = false
        runManager.addConfiguration(settings)
        runManager.makeStable(settings)
        runManager.selectedConfiguration = settings

        LOG.info("Created Hytale Server run configuration: ${pluginInfo.groupId}:${pluginInfo.modName}")
    }
}
