package com.hytaledocs.intellij.gradle.tooling

import org.gradle.api.Project
import org.jetbrains.plugins.gradle.tooling.ErrorMessageBuilder
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService

/**
 * ModelBuilder that runs in the Gradle process during IntelliJ Gradle sync.
 * Detects whether the net.janrupf.hytale-dev plugin is applied to the project.
 */
class HytaleDevModelBuilder : ModelBuilderService {

    companion object {
        private const val HYTALE_DEV_PLUGIN_ID = "net.janrupf.hytale-dev"
    }

    override fun canBuild(modelName: String): Boolean {
        return HytaleDevModel::class.java.name == modelName
    }

    override fun buildAll(modelName: String, project: Project): Any {
        val hasPlugin = project.plugins.hasPlugin(HYTALE_DEV_PLUGIN_ID)
        var version: String? = null

        if (hasPlugin) {
            // Try to get plugin version from the applied plugin
            val appliedPlugin = project.plugins.findPlugin(HYTALE_DEV_PLUGIN_ID)
            if (appliedPlugin != null) {
                // The plugin class might expose version info, but for now we leave it null
                // Future: could extract from plugin metadata
            }
        }

        return HytaleDevModelImpl(hasPlugin, version)
    }

    override fun getErrorMessageBuilder(project: Project, e: Exception): ErrorMessageBuilder {
        return ErrorMessageBuilder.create(project, e, "HytaleDocs import errors")
            .withDescription("Unable to detect Hytale Dev plugin")
    }
}
