package com.hytaledocs.intellij.services

import com.hytaledocs.intellij.gradle.HytaleDevData
import com.hytaledocs.intellij.util.HttpClientPool
import com.intellij.openapi.components.Service
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleUtil
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture

/**
 * Represents the type of Hytale project detected.
 */
enum class HytaleProjectType {
    /** Uses net.janrupf.hytale-dev Gradle plugin (detected via tooling model after sync) */
    GRADLE_PLUGIN,
    /** Legacy project created via wizard (.hytale/project.json exists) */
    LEGACY_WIZARD,
    /** Legacy project with manifest.json only */
    LEGACY_MANUAL,
    /** Unknown or not a Hytale project */
    UNKNOWN
}

@Service(Service.Level.PROJECT)
class HytaleProjectService(private val project: Project) {

    companion object {
        const val SERVER_JAR_URL = "https://cdn.hytale.com/HytaleServer.jar"
        const val SERVER_JAR_NAME = "HytaleServer.jar"

        fun getInstance(project: Project): HytaleProjectService {
            return project.getService(HytaleProjectService::class.java)
        }
    }

    /**
     * Detects the type of Hytale project.
     * Priority: GRADLE_PLUGIN > LEGACY_WIZARD > LEGACY_MANUAL > UNKNOWN
     */
    fun detectProjectType(): HytaleProjectType {
        // Check for Gradle plugin detection via DataNode (post-sync)
        if (hasHytaleDevDataNode()) {
            return HytaleProjectType.GRADLE_PLUGIN
        }

        val basePath = project.basePath ?: return HytaleProjectType.UNKNOWN

        // Check for legacy wizard marker
        if (File(basePath, ".hytale/project.json").exists()) {
            return HytaleProjectType.LEGACY_WIZARD
        }

        // Check for various legacy manual setup indicators
        val legacyIndicators = listOf(
            File(basePath, "src/main/resources/manifest.json"),
            File(basePath, "server/HytaleServer.jar"),
            File(basePath, "libs/HytaleServer.jar")
        )

        if (legacyIndicators.any { it.exists() }) {
            return HytaleProjectType.LEGACY_MANUAL
        }

        // Check for HytaleServer dependency in build.gradle
        val buildGradle = File(basePath, "build.gradle")
        val buildGradleKts = File(basePath, "build.gradle.kts")
        val hasHytaleDep = when {
            buildGradle.exists() -> buildGradle.readText().contains("HytaleServer")
            buildGradleKts.exists() -> buildGradleKts.readText().contains("HytaleServer")
            else -> false
        }

        if (hasHytaleDep) {
            return HytaleProjectType.LEGACY_MANUAL
        }

        return HytaleProjectType.UNKNOWN
    }

    /**
     * Checks if any module has HytaleDevData in its DataNode tree.
     * This indicates the net.janrupf.hytale-dev Gradle plugin is applied.
     */
    private fun hasHytaleDevDataNode(): Boolean {
        val modules = ModuleManager.getInstance(project).modules
        for (module in modules) {
            val gradleModuleData: DataNode<ModuleData>? = GradleUtil.findGradleModuleData(module)
            if (gradleModuleData != null) {
                val hytaleDevData = gradleModuleData.children.find { it.key == HytaleDevData.KEY }
                if (hytaleDevData != null) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Returns true if this is any type of Hytale project.
     */
    fun isHytaleProject(): Boolean {
        return detectProjectType() != HytaleProjectType.UNKNOWN
    }

    /**
     * Returns true if this is a Gradle plugin project (uses net.janrupf.hytale-dev).
     */
    fun isGradlePluginProject(): Boolean {
        return detectProjectType() == HytaleProjectType.GRADLE_PLUGIN
    }

    /**
     * Returns true if this is a legacy project (wizard or manual).
     */
    fun isLegacyProject(): Boolean {
        val type = detectProjectType()
        return type == HytaleProjectType.LEGACY_WIZARD || type == HytaleProjectType.LEGACY_MANUAL
    }

    fun hasServerJar(): Boolean {
        val basePath = project.basePath ?: return false
        return File(basePath, "libs/$SERVER_JAR_NAME").exists()
    }

    fun downloadServerJar(): CompletableFuture<Path> {
        val basePath = project.basePath
            ?: return CompletableFuture.failedFuture(IllegalStateException("Project has no base path"))

        val libsDir = File(basePath, "libs")
        if (!libsDir.exists()) {
            libsDir.mkdirs()
        }

        val targetPath = libsDir.toPath().resolve(SERVER_JAR_NAME)

        return CompletableFuture.supplyAsync {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(SERVER_JAR_URL))
                .GET()
                .build()

            val response = HttpClientPool.client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() == 200) {
                response.body().use { inputStream ->
                    java.nio.file.Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
                targetPath
            } else {
                throw RuntimeException("Failed to download server JAR: HTTP ${response.statusCode()}")
            }
        }
    }
}
