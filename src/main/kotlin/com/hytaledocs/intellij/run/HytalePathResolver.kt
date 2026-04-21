package com.hytaledocs.intellij.run

import com.hytaledocs.intellij.services.JavaInstallService
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

class HytalePathResolver(
    private val config: HytaleServerRunConfiguration
) {
    companion object {
        private val LOG = Logger.getInstance(HytalePathResolver::class.java)
    }

    fun resolveServerPath(projectBasePath: String): Path {
        val serverPath = config.serverPath
        return if (Path.of(serverPath).isAbsolute) {
            Path.of(serverPath)
        } else {
            Path.of(projectBasePath, serverPath)
        }
    }

    fun resolveJavaPath(): Path? {
        // Use configured path if available
        if (config.javaPath.isNotBlank()) {
            val path = Path.of(config.javaPath)
            if (Files.exists(path)) return path
        }

        // Find Java 25+
        val javaService = JavaInstallService.getInstance()
        val java25 = javaService.findJava25() ?: return null
        return javaService.getJavaExecutable(java25)
    }

    fun findGradleWrapper(projectBasePath: String): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val wrapperName = if (isWindows) "gradlew.bat" else "gradlew"
        val wrapper = Path.of(projectBasePath, wrapperName)
        if (!Files.exists(wrapper)) return null

        // On Unix, ensure the wrapper is executable
        if (!isWindows) {
            makeExecutableIfNeeded(wrapper)
        }
        return wrapper.toString()
    }

    fun findMavenWrapper(projectBasePath: String): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val wrapperName = if (isWindows) "mvnw.cmd" else "mvnw"
        val wrapper = Path.of(projectBasePath, wrapperName)
        if (!Files.exists(wrapper)) return null

        // On Unix, ensure the wrapper is executable
        if (!isWindows) {
            makeExecutableIfNeeded(wrapper)
        }
        return wrapper.toString()
    }

    fun findGlobalGradle(): String? = findGlobalTool("gradle")

    fun findGlobalMaven(): String? = findGlobalTool("mvn")

    fun hasGradleBuildFile(projectBasePath: String): Boolean {
        return Files.exists(Path.of(projectBasePath, "build.gradle")) ||
                Files.exists(Path.of(projectBasePath, "build.gradle.kts"))
    }

    fun hasMavenBuildFile(projectBasePath: String): Boolean {
        return Files.exists(Path.of(projectBasePath, "pom.xml"))
    }

    fun resolvePluginJarPath(projectBasePath: String): Path? {
        val jarPathStr = config.pluginJarPath
        if (jarPathStr.isBlank()) return null

        // Try relative path first
        val relativePath = Path.of(projectBasePath, jarPathStr)
        if (Files.exists(relativePath)) return relativePath

        // Try absolute path
        val absolutePath = Path.of(jarPathStr)
        if (Files.exists(absolutePath)) return absolutePath

        // Try common build output locations
        val commonLocations = listOf(
            "build/libs/${jarPathStr}",
            "target/${jarPathStr}",
            "build/libs/${Path.of(jarPathStr).fileName}",
            "target/${Path.of(jarPathStr).fileName}"
        )

        for (location in commonLocations) {
            val path = Path.of(projectBasePath, location)
            if (Files.exists(path)) return path
        }

        // Search in build/libs for any JAR matching pattern
        val buildLibs = Path.of(projectBasePath, "build/libs")
        if (Files.exists(buildLibs)) {
            Files.list(buildLibs).use { stream ->
                val jar = stream
                    .filter { it.toString().endsWith(".jar") }
                    .filter { !it.toString().contains("-sources") && !it.toString().contains("-javadoc") }
                    .findFirst()
                    .orElse(null)
                if (jar != null) {
                    return jar
                }
            }
        }

        return null
    }

    private fun findGlobalTool(name: String): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        return try {
            val command = if (isWindows) listOf("where", name) else listOf("which", name)
            val process = ProcessBuilder(command).start()
            val output = process.inputStream.bufferedReader().readLines()
            process.waitFor(2, TimeUnit.SECONDS)

            if (isWindows) {
                // On Windows, pick the best executable from multiple results
                output.firstOrNull { it.endsWith(".exe") }
                    ?: output.firstOrNull { it.endsWith(".cmd") }
                    ?: output.firstOrNull { it.endsWith(".bat") }
                    ?: output.firstOrNull()
            } else {
                output.firstOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun makeExecutableIfNeeded(file: Path) {
        try {
            if (!Files.isExecutable(file)) {
                LOG.info("Making ${file.fileName} executable")
                val process = ProcessBuilder("chmod", "+x", file.toString())
                    .start()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to make ${file.fileName} executable: ${e.message}")
        }
    }
}
