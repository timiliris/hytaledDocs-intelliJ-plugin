package com.hytaledocs.intellij.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project

/**
 * Run configuration for Hytale Server.
 * Supports building, deploying, and running the server with hot reload.
 */
class HytaleServerRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<HytaleServerRunConfigurationOptions>(project, factory, name) {

    override fun getOptionsClass(): Class<out HytaleServerRunConfigurationOptions> {
        return HytaleServerRunConfigurationOptions::class.java
    }

    override fun getOptions(): HytaleServerRunConfigurationOptions {
        return super.getOptions() as HytaleServerRunConfigurationOptions
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return HytaleServerSettingsEditor(project)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return HytaleServerRunState(project, this, environment)
    }

    // Build settings
    var buildBeforeRun: Boolean
        get() = options.buildBeforeRun
        set(value) { options.buildBeforeRun = value }

    var buildTask: String
        get() = options.buildTask ?: "shadowJar"
        set(value) { options.buildTask = value }

    // Deploy settings
    var deployPlugin: Boolean
        get() = options.deployPlugin
        set(value) { options.deployPlugin = value }

    var pluginJarPath: String
        get() = options.pluginJarPath ?: ""
        set(value) { options.pluginJarPath = value }

    var pluginName: String
        get() = options.pluginName ?: ""
        set(value) { options.pluginName = value }

    // Server settings
    var serverPath: String
        get() = options.serverPath ?: "server"
        set(value) { options.serverPath = value }

    var javaPath: String
        get() = options.javaPath ?: ""
        set(value) { options.javaPath = value }

    var minMemory: String
        get() = options.minMemory ?: "2G"
        set(value) { options.minMemory = value }

    var maxMemory: String
        get() = options.maxMemory ?: "8G"
        set(value) { options.maxMemory = value }

    var port: Int
        get() = options.port
        set(value) { options.port = value }

    var authMode: String
        get() = options.authMode ?: "authenticated"
        set(value) { options.authMode = value }

    var allowOp: Boolean
        get() = options.allowOp
        set(value) { options.allowOp = value }

    var acceptEarlyPlugins: Boolean
        get() = options.acceptEarlyPlugins
        set(value) { options.acceptEarlyPlugins = value }

    var jvmArgs: String
        get() = options.jvmArgs ?: ""
        set(value) { options.jvmArgs = value }

    var serverArgs: String
        get() = options.serverArgs ?: ""
        set(value) { options.serverArgs = value }

    var hotReloadEnabled: Boolean
        get() = options.hotReloadEnabled
        set(value) { options.hotReloadEnabled = value }
}
