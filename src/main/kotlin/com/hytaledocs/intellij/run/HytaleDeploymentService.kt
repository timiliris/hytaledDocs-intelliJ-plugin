package com.hytaledocs.intellij.run

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.moveTo

class HytaleDeploymentService(
    private val pathResolver: HytalePathResolver,
    private val console: HytaleConsole,
    private val config: HytaleServerRunConfiguration
) {
    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(HytaleDeploymentService::class.java)
    }

    fun deployPlugin(projectBasePath: String): Boolean {
        val jarPath = pathResolver.resolvePluginJarPath(projectBasePath) ?: run {
            console.printError("Plugin JAR not found: ${config.pluginJarPath}")
            return false
        }

        val serverPath = pathResolver.resolveServerPath(projectBasePath)
        val modsDir = serverPath.resolve("mods")

        try {
            // Create mods directory if needed
            if (!Files.exists(modsDir)) {
                Files.createDirectories(modsDir)
                console.printInfo("Created mods directory")
            }
        } catch (e: Exception) {
            console.printError("Failed to create mods directory: ${e.message}")
            return false
        }

        val baseJarName = jarPath.fileName.toString().substringBeforeLast(".jar")
        val devFileName = "${baseJarName}-dev.jar"
        val targetPath = modsDir.resolve(devFileName)

        val maxRetries = 3
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                LOG.info("Deploy attempt $attempt/$maxRetries for: $devFileName")
                console.printInfo("Deploy attempt $attempt/$maxRetries...")

                // Step 1: Create shadow copy in temp location
                val tempFile = Files.createTempFile("hytale-deploy-", ".jar")
                try {
                    Files.copy(jarPath, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    LOG.debug("Created shadow copy at: $tempFile")

                    // Step 2: Try atomic move to target
                    try {
                        Files.move(
                            tempFile,
                            targetPath,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                        console.printInfo("Deployed ${devFileName} to ${modsDir}")
                        LOG.info("Atomic move succeeded to: $targetPath")

                        // Success! Clean up old timestamped JARs
                        cleanupOldTimestampedJars(modsDir, baseJarName)

                        return true
                    } catch (atomicEx: Exception) {
                        LOG.debug("Atomic move failed (${atomicEx.message}), trying non-atomic approach")

                        // Step 3: Atomic move failed - try regular move/copy
                        try {
                            // Try to delete existing file first
                            Files.deleteIfExists(targetPath)
                            Files.move(tempFile, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            console.printInfo("Deployed ${devFileName} to ${modsDir}")
                            LOG.info("Non-atomic move succeeded to: $targetPath")

                            cleanupOldTimestampedJars(modsDir, baseJarName)
                            return true
                        } catch (moveEx: Exception) {
                            LOG.debug("Non-atomic move failed (${moveEx.message}), falling back to timestamped filename")

                            // Step 4: File is locked - use timestamped filename as fallback
                            val timestamp = System.currentTimeMillis()
                            val timestampedFileName = "${baseJarName}-dev-${timestamp}.jar"
                            val timestampedPath = modsDir.resolve(timestampedFileName)

                            Files.copy(jarPath, timestampedPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            console.printInfo("Deployed ${timestampedFileName} to ${modsDir} (timestamped fallback)")
                            LOG.info("Timestamped fallback succeeded: $timestampedPath")

                            cleanupOldTimestampedJars(modsDir, baseJarName)
                            return true
                        }
                    }
                } finally {
                    Files.deleteIfExists(tempFile)
                }
            } catch (e: Exception) {
                lastException = e
                LOG.warn("Deploy attempt $attempt failed: ${e.message}")
                if (attempt < maxRetries) {
                    Thread.sleep(if (attempt == 1) 1000L else 2000L)
                }
            }
        }

        console.printError("Deployment failed after $maxRetries attempts: ${lastException?.message}")
        return false
    }

    private fun cleanupOldTimestampedJars(modsDir: Path, baseJarName: String) {
        val maxOldJarsToKeep = 2
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
            if (devJars.size > maxOldJarsToKeep) {
                val toDelete = devJars.drop(maxOldJarsToKeep)
                for (jar in toDelete) {
                    try {
                        Files.deleteIfExists(jar)
                        console.printInfo("Cleaned up old dev JAR: ${jar.fileName}")
                        LOG.info("Cleaned up old dev JAR: ${jar.fileName}")
                    } catch (e: Exception) {
                        // File might still be locked by server, ignore
                        LOG.debug("Could not delete old JAR (may be in use): ${jar.fileName}")
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to cleanup old timestamped JARs", e)
            // Non-fatal, continue execution
        }
    }
}
