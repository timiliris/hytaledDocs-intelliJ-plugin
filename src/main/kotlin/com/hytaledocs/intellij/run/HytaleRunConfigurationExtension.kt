package com.hytaledocs.intellij.run

import com.hytaledocs.intellij.bridge.DevBridgeServer
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.diagnostic.Logger

/**
 * Run configuration extension that injects Dev Bridge environment variables
 * into Hytale server processes.
 *
 * Applies to:
 * 1. JarApplication configs from Gradle plugin (detected by HYTALE_DEV_AGENT_CONFIGURATION env var)
 * 2. Legacy HytaleServerRunConfiguration
 */
class HytaleRunConfigurationExtension : RunConfigurationExtension() {

    companion object {
        private val LOG = Logger.getInstance(HytaleRunConfigurationExtension::class.java)
        private const val AGENT_CONFIG_ENV_VAR = "HYTALE_DEV_AGENT_CONFIGURATION"
        private const val BRIDGE_PORT_ENV_VAR = "HYTALE_DEV_BRIDGE_PORT"
        private const val BRIDGE_TOKEN_ENV_VAR = "HYTALE_DEV_BRIDGE_TOKEN"
    }

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
        // Apply to legacy HytaleServerRunConfiguration
        if (configuration is HytaleServerRunConfiguration) {
            return true
        }

        // For JarApplication configs, we check for the agent env var in updateJavaParameters
        val typeName = configuration.type.id
        return typeName == "JarApplication" || typeName == "Application"
    }

    override fun <T : RunConfigurationBase<*>?> updateJavaParameters(
        configuration: T & Any,
        params: JavaParameters,
        runnerSettings: RunnerSettings?
    ) {
        val project = configuration.project
        val env = params.env

        // Check if this is a Gradle-based Hytale run
        val isGradleHytaleRun = env.containsKey(AGENT_CONFIG_ENV_VAR)

        // Check if this is a legacy HytaleServerRunConfiguration
        val isLegacyHytaleRun = configuration is HytaleServerRunConfiguration

        if (!isGradleHytaleRun && !isLegacyHytaleRun) {
            // Not a Hytale run, do not inject
            return
        }

        // Skip if bridge env vars are already set
        if (env.containsKey(BRIDGE_PORT_ENV_VAR)) {
            LOG.debug("Bridge env vars already set, skipping injection")
            return
        }

        // Start bridge server if not running
        val bridgeServer = DevBridgeServer.getInstance(project)
        if (!bridgeServer.ensureStarted()) {
            LOG.warn("Failed to start DevBridgeServer, skipping env var injection")
            return
        }

        // Inject environment variables
        env[BRIDGE_PORT_ENV_VAR] = bridgeServer.port.toString()
        env[BRIDGE_TOKEN_ENV_VAR] = bridgeServer.authToken

        LOG.info("Injected bridge env vars: port=${bridgeServer.port}")
    }

    override fun getSerializationId(): String = "hytale-bridge"
}
