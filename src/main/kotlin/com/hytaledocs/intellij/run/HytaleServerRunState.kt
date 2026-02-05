package com.hytaledocs.intellij.run

import com.hytaledocs.intellij.services.JavaInstallService
import com.hytaledocs.intellij.services.ServerLaunchService
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Execution state for Hytale Server run configuration.
 * Handles: Build -> Deploy -> Start/Reload server.
 * Supports debugging by adding JDWP agent when running in debug mode.
 */
class HytaleServerRunState(
    private val project: Project,
    private val config: HytaleServerRunConfiguration,
    private val environment: ExecutionEnvironment
) : RunProfileState {

    companion object {
        private val LOG = Logger.getInstance(HytaleServerRunState::class.java)
        private const val DEBUG_PORT_START = 5005
        private const val DEBUG_PORT_END = 5015
    }

    val isDebugMode: Boolean
        get() = environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID

    // Find available debug port at construction time
    private val debugPort: Int by lazy { findAvailablePort() }

    /**
     * Returns the remote connection for debugging.
     * IntelliJ will use this to attach the debugger after the server starts.
     */
    fun getRemoteConnection(): RemoteConnection {
        return RemoteConnection(true, "localhost", debugPort.toString(), false)
    }

    /**
     * Find an available port for debugging.
     */
    private fun findAvailablePort(): Int {
        for (port in DEBUG_PORT_START..DEBUG_PORT_END) {
            try {
                ServerSocket(port).use { return port }
            } catch (e: Exception) {
                // Port in use, try next
            }
        }
        // Fallback: let OS assign a port
        return ServerSocket(0).use { it.localPort }
    }

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult? {
        val consoleView = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console

        val processHandler = HytaleServerProcessHandler(project, config, consoleView, isDebugMode, debugPort)
        consoleView.attachToProcess(processHandler)

        // Start execution in background
        processHandler.startExecution()

        return DefaultExecutionResult(consoleView, processHandler)
    }
}

/**
 * Process handler for the Hytale server.
 * Manages the entire lifecycle: build, deploy, start, and hot reload.
 * Supports debug mode with JDWP agent for remote debugging.
 */
class HytaleServerProcessHandler(
    private val project: Project,
    private val config: HytaleServerRunConfiguration,
    private val console: ConsoleView,
    private val isDebugMode: Boolean = false,
    private val debugPort: Int = 5005
) : ProcessHandler() {

    companion object {
        private val LOG = Logger.getInstance(HytaleServerProcessHandler::class.java)
    }

    private val launchService = ServerLaunchService.getInstance(project)
    @Volatile
    private var isTerminating = false

    fun startExecution() {
        startNotify()

        CompletableFuture.runAsync {
            try {
                execute()
            } catch (e: Exception) {
                printError("Execution failed: ${e.message}")
                LOG.error("Hytale server execution failed", e)
                notifyProcessTerminated(1)
            }
        }
    }

    private fun execute() {
        val projectBasePath = project.basePath ?: run {
            printError("Project base path not found")
            notifyProcessTerminated(1)
            return
        }

        val serverAlreadyRunning = launchService.isServerRunning()
        val useHotReload = config.hotReloadEnabled && serverAlreadyRunning

        if (useHotReload) {
            printInfo("=== Hot Reload Mode ===")
            printInfo("Server is already running - will rebuild and redeploy without restart")
            println("")
        }

        // Step 1: Build (if enabled)
        if (config.buildBeforeRun && config.buildTask.isNotBlank()) {
            printInfo("=== Building plugin ===")
            if (!executeBuild(projectBasePath)) {
                printError("Build failed!")
                notifyProcessTerminated(1)
                return
            }
            printSuccess("Build completed successfully")
            println("")
        }

        // Step 2: Stop server if running (only if NOT using hot reload)
        if (serverAlreadyRunning && !useHotReload) {
            printInfo("=== Stopping server for redeploy ===")
            launchService.stopServer { line -> println(line) }
                .get(30, java.util.concurrent.TimeUnit.SECONDS)
            printSuccess("Server stopped")
            // Wait a bit for file handles to be released
            Thread.sleep(1000)
            println("")
        }

        // Step 3: Deploy plugin (if enabled)
        if (config.deployPlugin && config.pluginJarPath.isNotBlank()) {
            printInfo("=== Deploying plugin ===")

            // Kill any lingering HytaleServer processes that might be holding files
            // (only if not using hot reload - we don't want to kill our running server!)
            if (!useHotReload) {
                killLingeringServerProcesses()
            }

            if (!deployPlugin(projectBasePath)) {
                printError("Deploy failed!")
                // Continue anyway - server might still start
            } else {
                printSuccess("Plugin deployed successfully")
            }

            println("")
        }

        // Step 4: Start server (only if NOT using hot reload)
        if (useHotReload) {
            printInfo("=== Hot Reload Complete ===")
            printInfo("Plugin deployed - server will reload automatically")
            printSuccess("Hot reload successful!")
            // Don't call notifyProcessTerminated - keep the process handler alive
            // to show logs and allow stopping later
        } else {
            printInfo("=== Starting Hytale Server ===")
            if (isDebugMode) {
                printInfo("Debug mode enabled on port $debugPort")
            }
            startServer(projectBasePath)
        }
    }

    private fun executeBuild(projectBasePath: String): Boolean {
        val gradleWrapper = findGradleWrapper(projectBasePath)
        val mavenWrapper = findMavenWrapper(projectBasePath)
        val globalGradle = findGlobalGradle()
        val globalMaven = findGlobalMaven()

        val (command, workDir) = when {
            gradleWrapper != null -> {
                printInfo("Using Gradle wrapper: ${config.buildTask}")
                listOf(gradleWrapper, config.buildTask, "--no-daemon") to projectBasePath
            }
            mavenWrapper != null -> {
                printInfo("Using Maven wrapper: ${config.buildTask}")
                listOf(mavenWrapper, config.buildTask) to projectBasePath
            }
            globalGradle != null && hasGradleBuildFile(projectBasePath) -> {
                printInfo("Using global Gradle: ${config.buildTask}")
                listOf(globalGradle, config.buildTask, "--no-daemon") to projectBasePath
            }
            globalMaven != null && hasMavenBuildFile(projectBasePath) -> {
                printInfo("Using global Maven: ${config.buildTask}")
                listOf(globalMaven, config.buildTask) to projectBasePath
            }
            else -> {
                printError("No Gradle or Maven found (wrapper or global)")
                printInfo("Tip: Add gradlew.bat/gradlew to your project or install Gradle globally")
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
                    println(line)
                }
            }

            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            printError("Build error: ${e.message}")
            false
        }
    }

    private fun findGradleWrapper(projectBasePath: String): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val wrapperName = if (isWindows) "gradlew.bat" else "gradlew"
        val wrapper = Path.of(projectBasePath, wrapperName)
        return if (Files.exists(wrapper)) wrapper.toString() else null
    }

    private fun findMavenWrapper(projectBasePath: String): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val wrapperName = if (isWindows) "mvnw.cmd" else "mvnw"
        val wrapper = Path.of(projectBasePath, wrapperName)
        return if (Files.exists(wrapper)) wrapper.toString() else null
    }

    private fun findGlobalGradle(): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        return try {
            val process = if (isWindows) {
                ProcessBuilder("where", "gradle.bat").start()
            } else {
                ProcessBuilder("which", "gradle").start()
            }
            val result = process.inputStream.bufferedReader().use { it.readLine() }
            process.waitFor()
            if (process.exitValue() == 0 && result != null && result.isNotBlank()) result else null
        } catch (e: Exception) {
            null
        }
    }

    private fun findGlobalMaven(): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        return try {
            val process = if (isWindows) {
                ProcessBuilder("where", "mvn.cmd").start()
            } else {
                ProcessBuilder("which", "mvn").start()
            }
            val result = process.inputStream.bufferedReader().use { it.readLine() }
            process.waitFor()
            if (process.exitValue() == 0 && result != null && result.isNotBlank()) result else null
        } catch (e: Exception) {
            null
        }
    }

    private fun hasGradleBuildFile(projectBasePath: String): Boolean {
        return Files.exists(Path.of(projectBasePath, "build.gradle")) ||
                Files.exists(Path.of(projectBasePath, "build.gradle.kts"))
    }

    private fun hasMavenBuildFile(projectBasePath: String): Boolean {
        return Files.exists(Path.of(projectBasePath, "pom.xml"))
    }

    /**
     * Kill any lingering HytaleServer Java processes that might be holding file locks.
     * Uses modern ProcessHandle API (Java 9+) instead of deprecated WMIC.
     */
    private fun killLingeringServerProcesses() {
        try {
            ProcessHandle.allProcesses()
                .filter { handle ->
                    handle.info().commandLine()
                        .map { cmd -> cmd.contains("HytaleServer.jar") }
                        .orElse(false)
                }
                .forEach { handle ->
                    printInfo("Killing lingering server process: ${handle.pid()}")
                    handle.destroyForcibly()
                }
            Thread.sleep(2000) // Wait for processes to terminate
        } catch (e: Exception) {
            LOG.warn("Failed to kill lingering processes", e)
        }
    }

    private fun deployPlugin(projectBasePath: String): Boolean {
        val jarPath = resolvePluginJarPath(projectBasePath) ?: run {
            printError("Plugin JAR not found: ${config.pluginJarPath}")
            return false
        }

        val serverPath = resolveServerPath(projectBasePath)
        val modsDir = serverPath.resolve("mods")

        try {
            // Create mods directory if needed
            if (!Files.exists(modsDir)) {
                Files.createDirectories(modsDir)
                printInfo("Created mods directory")
            }

            val baseJarName = jarPath.fileName.toString().substringBeforeLast(".jar")
            val devFileName = "${baseJarName}-dev.jar"
            val targetPath = modsDir.resolve(devFileName)

            // Try to delete/copy with retries (in case file is still being released)
            var success = false
            for (attempt in 1..3) {
                try {
                    Files.deleteIfExists(targetPath)
                    printInfo("Copying ${devFileName} to ${modsDir}...")
                    Files.copy(jarPath, targetPath)
                    success = true
                    break
                } catch (e: Exception) {
                    if (attempt < 3) {
                        printInfo("File locked, retrying in 2 seconds... (attempt $attempt/3)")
                        Thread.sleep(2000)
                    } else {
                        throw e
                    }
                }
            }

            return success
        } catch (e: Exception) {
            printError("Failed to copy plugin: ${e.message}")
            return false
        }
    }

    private fun resolvePluginJarPath(projectBasePath: String): Path? {
        val jarPath = config.pluginJarPath
        if (jarPath.isBlank()) return null

        // Try relative path first
        val relativePath = Path.of(projectBasePath, jarPath)
        if (Files.exists(relativePath)) return relativePath

        // Try absolute path
        val absolutePath = Path.of(jarPath)
        if (Files.exists(absolutePath)) return absolutePath

        // Try common build output locations
        val commonLocations = listOf(
            "build/libs/${jarPath}",
            "target/${jarPath}",
            "build/libs/${Path.of(jarPath).fileName}",
            "target/${Path.of(jarPath).fileName}"
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
                    printInfo("Found JAR: ${jar.fileName}")
                    return jar
                }
            }
        }

        return null
    }

    private fun resolveServerPath(projectBasePath: String): Path {
        val serverPath = config.serverPath
        return if (Path.of(serverPath).isAbsolute) {
            Path.of(serverPath)
        } else {
            Path.of(projectBasePath, serverPath)
        }
    }

    private fun startServer(projectBasePath: String) {
        val serverPath = resolveServerPath(projectBasePath)

        // Validate server files
        val validation = launchService.validateServerFiles(serverPath)
        if (!validation.isValid) {
            printError("Server validation failed:")
            validation.errors.forEach { printError("  - $it") }
            notifyProcessTerminated(1)
            return
        }

        // Find Java
        val javaPath = resolveJavaPath() ?: run {
            printError("Java 25+ not found. Please configure Java path.")
            notifyProcessTerminated(1)
            return
        }

        // Build additional JVM args with debug support
        val additionalJvmArgs = buildList {
            // Add user-specified JVM args
            addAll(config.jvmArgs.split(" ").filter { it.isNotBlank() })

            // Add debug agent if in debug mode
            if (isDebugMode) {
                add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$debugPort")
            }
        }

        // Build server config
        val serverConfig = ServerLaunchService.ServerConfig(
            serverPath = serverPath,
            javaPath = javaPath,
            minMemory = config.minMemory,
            maxMemory = config.maxMemory,
            port = config.port,
            authMode = if (config.authMode == "authenticated")
                ServerLaunchService.AuthMode.AUTHENTICATED
            else
                ServerLaunchService.AuthMode.OFFLINE,
            allowOp = config.allowOp,
            acceptEarlyPlugins = config.acceptEarlyPlugins,
            additionalJvmArgs = additionalJvmArgs,
            additionalServerArgs = config.serverArgs.split(" ").filter { it.isNotBlank() }
        )

        // Start server with callbacks
        launchService.startServer(
            config = serverConfig,
            logCallback = { line -> println(line) },
            statusCallback = { status ->
                when (status) {
                    ServerLaunchService.ServerStatus.RUNNING -> {
                        printSuccess("Server is now running!")
                        if (isDebugMode) {
                            printInfo("Debugger can connect on port $debugPort")
                        }
                    }
                    ServerLaunchService.ServerStatus.STOPPED -> {
                        if (!isTerminating) {
                            notifyProcessTerminated(0)
                        }
                    }
                    ServerLaunchService.ServerStatus.ERROR -> {
                        if (!isTerminating) {
                            notifyProcessTerminated(1)
                        }
                    }
                    else -> {}
                }
            }
        )
    }

    private fun resolveJavaPath(): Path? {
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

    private fun println(text: String) {
        notifyTextAvailable("$text\n", ProcessOutputType.STDOUT)
    }

    private fun printInfo(text: String) {
        notifyTextAvailable("[INFO] $text\n", ProcessOutputType.STDOUT)
    }

    private fun printSuccess(text: String) {
        notifyTextAvailable("[SUCCESS] $text\n", ProcessOutputType.STDOUT)
    }

    private fun printError(text: String) {
        notifyTextAvailable("[ERROR] $text\n", ProcessOutputType.STDERR)
    }

    override fun destroyProcessImpl() {
        isTerminating = true

        // Always stop the server when the stop button is pressed
        // Hot reload only applies during re-run (handled in execute())
        if (launchService.isServerRunning()) {
            printInfo("Stopping server...")
            launchService.stopServer(
                logCallback = { line ->
                    try {
                        println(line)
                    } catch (e: Exception) {
                        // Ignore - console may be closing
                    }
                },
                statusCallback = { status ->
                    if (status == ServerLaunchService.ServerStatus.STOPPED) {
                        printInfo("Server stopped successfully")
                        notifyProcessTerminated(0)
                    }
                }
            ).exceptionally { e ->
                LOG.warn("Error stopping server", e)
                // Force notify termination even on error
                notifyProcessTerminated(1)
                false
            }.orTimeout(45, TimeUnit.SECONDS)
            .exceptionally { e ->
                // Timeout occurred - force kill and notify
                LOG.warn("Stop server timed out, forcing termination", e)
                printError("Server stop timed out - forcing shutdown")
                notifyProcessTerminated(1)
                false
            }
        } else {
            notifyProcessTerminated(0)
        }
    }

    override fun detachProcessImpl() {
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? {
        // Return an output stream that sends commands to the server
        return object : OutputStream() {
            private val buffer = StringBuilder()

            override fun write(b: Int) {
                val char = b.toChar()
                if (char == '\n') {
                    val command = buffer.toString().trim()
                    if (command.isNotEmpty()) {
                        launchService.sendCommand(command)
                    }
                    buffer.clear()
                } else {
                    buffer.append(char)
                }
            }
        }
    }
}
