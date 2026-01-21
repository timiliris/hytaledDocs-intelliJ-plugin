package com.hytaledocs.intellij.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Run configuration type for Hytale Server.
 * Appears in the Run/Debug configuration dropdown.
 */
class HytaleServerConfigurationType : ConfigurationType {

    companion object {
        const val ID = "HytaleServerRunConfiguration"

        fun getInstance(): HytaleServerConfigurationType {
            return ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
                .filterIsInstance<HytaleServerConfigurationType>()
                .first()
        }
    }

    override fun getDisplayName(): String = "Hytale Server"

    override fun getConfigurationTypeDescription(): String =
        "Run and manage a Hytale development server with automatic plugin deployment"

    override fun getIcon(): Icon =
        IconLoader.getIcon("/icons/hytaledocs-plugin-16.svg", HytaleServerConfigurationType::class.java)

    override fun getId(): String = ID

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(HytaleServerConfigurationFactory(this))
}

/**
 * Factory for creating Hytale Server run configurations.
 */
class HytaleServerConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = HytaleServerConfigurationType.ID

    override fun getName(): String = "Hytale Server"

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return HytaleServerRunConfiguration(project, this, "Hytale Server")
    }

    override fun getOptionsClass(): Class<out HytaleServerRunConfigurationOptions> =
        HytaleServerRunConfigurationOptions::class.java
}
