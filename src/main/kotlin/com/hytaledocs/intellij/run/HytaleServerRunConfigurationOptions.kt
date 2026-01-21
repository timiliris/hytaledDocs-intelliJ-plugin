package com.hytaledocs.intellij.run

import com.intellij.execution.configurations.RunConfigurationOptions

/**
 * Persisted options for the Hytale Server run configuration.
 * Uses IntelliJ's built-in property delegates for automatic XML serialization.
 */
class HytaleServerRunConfigurationOptions : RunConfigurationOptions() {

    // Build settings
    var buildBeforeRun by property(true)
    var buildTask by string("shadowJar")

    // Deploy settings
    var deployPlugin by property(true)
    var pluginJarPath by string("")
    var pluginName by string("")

    // Server settings
    var serverPath by string("server")
    var javaPath by string("")
    var minMemory by string("2G")
    var maxMemory by string("8G")
    var port by property(5520)
    var authMode by string("authenticated")
    var allowOp by property(true)
    var acceptEarlyPlugins by property(true)

    // Additional args
    var jvmArgs by string("")
    var serverArgs by string("")

    // Hot reload settings
    var hotReloadEnabled by property(true)
}
