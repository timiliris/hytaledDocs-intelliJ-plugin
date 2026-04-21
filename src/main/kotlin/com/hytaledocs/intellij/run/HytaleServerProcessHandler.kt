package com.hytaledocs.intellij.run

import com.hytaledocs.intellij.hotReload.HotReloadListener
import com.hytaledocs.intellij.hotReload.HytaleFileChangeClassifier
import com.hytaledocs.intellij.hotReload.IntellijFileSynchronizer
import com.hytaledocs.intellij.services.ServerLaunchService
import com.hytaledocs.intellij.util.PluginInfoDetector
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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
) : ProcessHandler(), HytaleConsole {

    companion object {
        private val LOG = Logger.getInstance(HytaleServerProcessHandler::class.java)
    }

    private val pathResolver = HytalePathResolver(config)
    private val buildService = HytaleBuildService(pathResolver, config, this)
    private val deploymentService = HytaleDeploymentService(pathResolver, this, config)
    private val processManager = HytaleProcessManager()
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
            if (!buildService.executeBuild(projectBasePath)) {
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
                .get(30, TimeUnit.SECONDS)
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

            if (!deploymentService.deployPlugin(projectBasePath)) {
                printError("Deploy failed!")
                // Continue anyway - server might still start
            } else {
                printSuccess("Plugin deployed successfully")
            }

            println("")
        }

        if (config.hotReloadEnabled) {
            printInfo("HotReload enabled")
            val recentlySyncedPath: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

            val listener = HotReloadListener(
                project,
                HytaleFileChangeClassifier(),
                synchronizer = IntellijFileSynchronizer(recentlySyncedPath),
                recentlySyncedPath
            ) {
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Hytale Hot Reload", false) {
                    override fun run(indicator: ProgressIndicator) {
                        printInfo("HotReload Started!")
                        if (!buildService.executeBuild(projectBasePath)) printError("Build failed!")
                        if (!deploymentService.deployPlugin(projectBasePath)) printError("Deploying plugin failed!")
                        printInfo("HotReload Done")

                        val pluginId = if (config.pluginName.isNotBlank()) {
                            config.pluginName
                        } else {
                            val info = PluginInfoDetector.detect(projectBasePath, project.name)
                            if (info != null) "${info.groupId}:${info.artifactId}" else "com.example:${project.name}"
                        }

                        launchService.sendCommand("default", "/plugin reload $pluginId")
                        launchService.sendCommand("default", "/say reloaded $pluginId")
                    }
                })
                true
            }

            project.messageBus.connect().subscribe(
                topic = VirtualFileManager.VFS_CHANGES,
                handler = listener
            )
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

    private fun killLingeringServerProcesses() {
        processManager.killLingeringServerProcesses(this)
    }

    private fun startServer(projectBasePath: String) {
        val serverPath = pathResolver.resolveServerPath(projectBasePath)

        // Validate server files
        val validation = launchService.validateServerFiles(serverPath)
        if (!validation.isValid) {
            printError("Server validation failed:")
            validation.errors.forEach { printError("  - $it") }
            notifyProcessTerminated(1)
            return
        }

        // Find Java
        val javaPath = pathResolver.resolveJavaPath() ?: run {
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

    override fun println(text: String) {
        notifyTextAvailable("$text\n", ProcessOutputType.STDOUT)
    }

    override fun printInfo(text: String) {
        notifyTextAvailable("[INFO] $text\n", ProcessOutputType.STDOUT)
    }

    override fun printSuccess(text: String) {
        notifyTextAvailable("[SUCCESS] $text\n", ProcessOutputType.STDOUT)
    }

    override fun printError(text: String) {
        notifyTextAvailable("[ERROR] $text\n", ProcessOutputType.STDERR)
    }

    override fun destroyProcessImpl() {
        isTerminating = true

        // Always stop the server when the stop button is pressed
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
                notifyProcessTerminated(1)
                false
            }.orTimeout(45, TimeUnit.SECONDS)
                .exceptionally { e ->
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
