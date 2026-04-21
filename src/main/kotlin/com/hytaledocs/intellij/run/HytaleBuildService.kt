package com.hytaledocs.intellij.run

import java.nio.file.Path

class HytaleBuildService(
    private val pathResolver: HytalePathResolver,
    private val config: HytaleServerRunConfiguration,
    private val console: HytaleConsole
) {
    fun executeBuild(projectBasePath: String): Boolean {
        val gradleWrapper = pathResolver.findGradleWrapper(projectBasePath)
        val mavenWrapper = pathResolver.findMavenWrapper(projectBasePath)
        val globalGradle = pathResolver.findGlobalGradle()
        val globalMaven = pathResolver.findGlobalMaven()

        val (command, workDir) = when {
            gradleWrapper != null -> {
                console.printInfo("Using Gradle wrapper: ${config.buildTask}")
                listOf(gradleWrapper, config.buildTask, "--no-daemon") to projectBasePath
            }

            mavenWrapper != null -> {
                console.printInfo("Using Maven wrapper: ${config.buildTask}")
                listOf(mavenWrapper, config.buildTask) to projectBasePath
            }

            globalGradle != null && pathResolver.hasGradleBuildFile(projectBasePath) -> {
                console.printInfo("Using global Gradle: ${config.buildTask}")
                listOf(globalGradle, config.buildTask, "--no-daemon") to projectBasePath
            }

            globalMaven != null && pathResolver.hasMavenBuildFile(projectBasePath) -> {
                console.printInfo("Using global Maven: ${config.buildTask}")
                listOf(globalMaven, config.buildTask) to projectBasePath
            }

            else -> {
                console.printError("No Gradle or Maven found (wrapper or global)")
                console.printInfo("Tip: Add gradlew.bat/gradlew to your project or install Gradle globally")
                return false
            }
        }

        return try {
            val process = ProcessBuilder(command)
                .directory(Path.of(workDir).toFile())
                .redirectErrorStream(true)
                .start()

            // Read build output
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    console.println(line)
                }
            }

            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            console.printError("Build error: ${e.message}")
            false
        }
    }
}
