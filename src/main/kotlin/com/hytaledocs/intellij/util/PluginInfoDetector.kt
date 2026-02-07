package com.hytaledocs.intellij.util

import java.io.File

/**
 * Utility for detecting plugin information from project files.
 *
 * This detector checks multiple sources in priority order:
 * 1. .hytale/project.json (created by project wizard)
 * 2. manifest.json (Hytale plugin manifest)
 * 3. Gradle build files (build.gradle/build.gradle.kts + settings.gradle)
 * 4. Project name fallback
 */
object PluginInfoDetector {

    /**
     * Plugin information extracted from project files.
     *
     * @param groupId The Maven/Gradle group ID (e.g., "com.example")
     * @param artifactId The artifact/module ID (e.g., "my-plugin")
     * @param modName The display name of the mod (e.g., "My Plugin")
     * @param jarPath Relative path to the built JAR file
     * @param buildTask The Gradle/Maven task to build the plugin
     */
    data class PluginInfo(
        val groupId: String,
        val artifactId: String,
        val modName: String,
        val jarPath: String,
        val buildTask: String
    )

    /**
     * Detects plugin info from project files.
     *
     * Checks in priority order:
     * 1. .hytale/project.json
     * 2. manifest.json
     * 3. build.gradle/build.gradle.kts files
     * 4. Project name fallback
     *
     * @param basePath The project base path
     * @param projectName The project name (used as fallback)
     * @return PluginInfo if detected, null otherwise
     */
    fun detect(basePath: String, projectName: String): PluginInfo? {
        val isMavenProject = isMavenProject(basePath)

        // Priority 0: .hytale/project.json
        readHytaleProjectJson(basePath, isMavenProject)?.let { return it }

        // Priority 1: manifest.json
        readManifestJson(basePath)?.let { return it }

        // Priority 2: Maven pom.xml
        readMavenPom(basePath)?.let { return it }

        // Priority 3: Gradle files
        readGradleFiles(basePath)?.let { return it }

        // Priority 4: Project name fallback
        if (projectName.isNotBlank()) {
            val artifactId = projectName.lowercase().replace(" ", "-")
            return PluginInfo(
                groupId = "com.example",
                artifactId = artifactId,
                modName = artifactId,
                jarPath = if (isMavenProject) "target/$artifactId-1.0.0.jar" else "build/libs/$artifactId-1.0.0.jar",
                buildTask = if (isMavenProject) "package" else "shadowJar"
            )
        }

        return null
    }

    /**
     * Read plugin info from .hytale/project.json (created by project wizard).
     * This file contains complete plugin metadata.
     */
    private fun readHytaleProjectJson(basePath: String, isMavenProject: Boolean): PluginInfo? {
        val projectFile = File(basePath, ".hytale/project.json")
        if (!projectFile.exists()) return null

        return try {
            val content = projectFile.readText()

            val groupRegex = """"groupId"\s*:\s*"([^"]+)"""".toRegex()
            val artifactRegex = """"artifactId"\s*:\s*"([^"]+)"""".toRegex()
            val modNameRegex = """"modName"\s*:\s*"([^"]+)"""".toRegex()
            val jarPathRegex = """"jarPath"\s*:\s*"([^"]+)"""".toRegex()
            val buildTaskRegex = """"buildTask"\s*:\s*"([^"]+)"""".toRegex()

            val groupId = groupRegex.find(content)?.groupValues?.get(1) ?: return null
            val artifactId = artifactRegex.find(content)?.groupValues?.get(1) ?: return null
            // modName is the actual plugin name (with spaces/caps)
            val modName = modNameRegex.find(content)?.groupValues?.get(1) ?: artifactId
            val jarPath = jarPathRegex.find(content)?.groupValues?.get(1)
                ?: if (isMavenProject) "target/$artifactId-1.0.0.jar" else "build/libs/$artifactId-1.0.0.jar"
            val buildTask = buildTaskRegex.find(content)?.groupValues?.get(1)
                ?: if (isMavenProject) "package" else "shadowJar"

            PluginInfo(groupId, artifactId, modName, jarPath, buildTask)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Read plugin info from pom.xml (Maven projects).
     */
    private fun readMavenPom(basePath: String): PluginInfo? {
        val pomFile = File(basePath, "pom.xml")
        if (!pomFile.exists()) return null

        return try {
            val content = pomFile.readText()
            val contentWithoutParent = content.replace(
                """<parent>\s*.*?</parent>""".toRegex(RegexOption.DOT_MATCHES_ALL),
                ""
            )

            fun firstMatch(source: String, vararg patterns: String): String? {
                for (pattern in patterns) {
                    val value = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
                        .find(source)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                    if (!value.isNullOrBlank()) return value
                }
                return null
            }

            val groupId = firstMatch(
                contentWithoutParent,
                """<groupId>\s*([^<\s][^<]*)\s*</groupId>"""
            ) ?: firstMatch(
                content,
                """<parent>\s*.*?<groupId>\s*([^<\s][^<]*)\s*</groupId>.*?</parent>"""
            ) ?: return null
            val artifactId = firstMatch(
                contentWithoutParent,
                """<artifactId>\s*([^<\s][^<]*)\s*</artifactId>"""
            ) ?: return null
            val modName = firstMatch(
                contentWithoutParent,
                """<name>\s*([^<\s][^<]*)\s*</name>"""
            ) ?: artifactId
            val version = firstMatch(
                contentWithoutParent,
                """<version>\s*([^<\s][^<]*)\s*</version>"""
            ) ?: firstMatch(
                content,
                """<parent>\s*.*?<version>\s*([^<\s][^<]*)\s*</version>.*?</parent>"""
            ) ?: "1.0.0"

            PluginInfo(
                groupId = groupId,
                artifactId = artifactId,
                modName = modName,
                jarPath = "target/$artifactId-$version.jar",
                buildTask = "package"
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Read plugin info from manifest.json (Hytale plugin manifest).
     * Contains Group and Name fields.
     */
    private fun readManifestJson(basePath: String): PluginInfo? {
        val manifestFile = File(basePath, "src/main/resources/manifest.json")
        if (!manifestFile.exists()) return null

        return try {
            val content = manifestFile.readText()
            val groupRegex = """"Group"\s*:\s*"([^"]+)"""".toRegex()
            val nameRegex = """"Name"\s*:\s*"([^"]+)"""".toRegex()

            val group = groupRegex.find(content)?.groupValues?.get(1)
            val name = nameRegex.find(content)?.groupValues?.get(1)

            if (group != null && name != null) {
                val isGradle = File(basePath, "build.gradle").exists() ||
                        File(basePath, "build.gradle.kts").exists()

                // artifactId for JAR filename (lowercase, no spaces)
                val jarArtifactId = name.lowercase().replace(" ", "-")

                PluginInfo(
                    groupId = group,
                    artifactId = jarArtifactId,
                    modName = name,  // Keep original name for plugin commands
                    jarPath = if (isGradle) "build/libs/$jarArtifactId-1.0.0.jar" else "target/$jarArtifactId-1.0.0.jar",
                    buildTask = if (isGradle) "shadowJar" else "package"
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Read plugin info from Gradle files (settings.gradle + build.gradle).
     */
    private fun readGradleFiles(basePath: String): PluginInfo? {
        val settingsGradle = File(basePath, "settings.gradle")
        val settingsGradleKts = File(basePath, "settings.gradle.kts")

        val projectName = when {
            settingsGradle.exists() -> {
                val content = settingsGradle.readText()
                val regex = """rootProject\.name\s*=\s*['"]([^'"]+)['"]""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            settingsGradleKts.exists() -> {
                val content = settingsGradleKts.readText()
                val regex = """rootProject\.name\s*=\s*"([^"]+)"""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            else -> null
        }

        val buildGradle = File(basePath, "build.gradle")
        val buildGradleKts = File(basePath, "build.gradle.kts")

        val groupId = when {
            buildGradle.exists() -> {
                val content = buildGradle.readText()
                val regex = """group\s*=\s*['"]([^'"]+)['"]""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            buildGradleKts.exists() -> {
                val content = buildGradleKts.readText()
                val regex = """group\s*=\s*"([^"]+)"""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            else -> null
        }

        if (projectName != null) {
            val artifactId = projectName.lowercase().replace(" ", "-")
            val baseGroup = groupId?.substringBeforeLast('.') ?: "com.example"

            return PluginInfo(
                groupId = baseGroup,
                artifactId = artifactId,
                modName = artifactId,
                jarPath = "build/libs/$artifactId-1.0.0.jar",
                buildTask = "shadowJar"
            )
        }

        return null
    }

    private fun isMavenProject(basePath: String): Boolean {
        return File(basePath, "pom.xml").exists()
    }
}
