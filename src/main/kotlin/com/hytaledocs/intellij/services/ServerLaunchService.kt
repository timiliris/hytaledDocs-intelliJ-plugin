package com.hytaledocs.intellij.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

@Service(Service.Level.PROJECT)
class ServerLaunchService(private val project: Project) : Disposable {

    /**
     * ExecutorService with daemon threads for background server monitoring tasks.
     * Using a cached thread pool since we typically have 2 threads (log reader + process monitor).
     */
    private val executorService: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "HytaleServer-${threadCount.incrementAndGet()}"
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ServerLaunchService::class.java)
        private val threadCount = AtomicInteger(0)

        const val DEFAULT_MIN_MEMORY = "2G"
        const val DEFAULT_MAX_MEMORY = "8G"
        const val DEFAULT_PORT = 5520

        private val JOIN_PATTERNS = listOf(
            Regex("""Player\s+(\S+)\s+(?:joined|connected)""", RegexOption.IGNORE_CASE),
            Regex("""(\S+)\s+joined the game""", RegexOption.IGNORE_CASE),
            Regex("""\[ServerConnectionManager\].*?(\S+).*?connected""", RegexOption.IGNORE_CASE)
        )

        private val LEAVE_PATTERNS = listOf(
            Regex("""Player\s+(\S+)\s+(?:left|disconnected)""", RegexOption.IGNORE_CASE),
            Regex("""(\S+)\s+left the game""", RegexOption.IGNORE_CASE),
            Regex("""\[ServerConnectionManager\].*?(\S+).*?disconnected""", RegexOption.IGNORE_CASE)
        )

        fun getInstance(project: Project): ServerLaunchService {
            return project.getService(ServerLaunchService::class.java)
        }
    }

    data class ServerConfig(
        val serverPath: Path,
        val javaPath: Path,
        val minMemory: String = DEFAULT_MIN_MEMORY,
        val maxMemory: String = DEFAULT_MAX_MEMORY,
        val port: Int = DEFAULT_PORT,
        val authMode: AuthMode = AuthMode.OFFLINE,
        val allowOp: Boolean = true,
        val acceptEarlyPlugins: Boolean = true,
        val additionalJvmArgs: List<String> = emptyList(),
        val additionalServerArgs: List<String> = emptyList()
    )

    enum class AuthMode(val value: String) {
        AUTHENTICATED("authenticated"),
        OFFLINE("offline")
    }

    enum class ServerStatus {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }

    data class ServerStats(
        val status: ServerStatus,
        val uptime: Duration?,
        val playerCount: Int,
        val memoryUsageMB: Long?,
        val cpuUsagePercent: Double?
    )

    @Volatile
    private var serverProcess: Process? = null
    @Volatile
    private var status: ServerStatus = ServerStatus.STOPPED
    private val isRunning = AtomicBoolean(false)
    @Volatile
    private var startTime: Instant? = null
    private val playerCount = AtomicInteger(0)
    private val connectedPlayers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun getStatus(): ServerStatus = status

    fun isServerRunning(): Boolean = isRunning.get()

    fun getPlayerCount(): Int = playerCount.get()

    fun getConnectedPlayers(): Set<String> = connectedPlayers.toSet()

    fun getStats(): ServerStats {
        return ServerStats(
            status = status,
            uptime = startTime?.let { Duration.between(it, Instant.now()) },
            playerCount = playerCount.get(),
            memoryUsageMB = null, // Java ProcessHandle doesn't provide memory directly
            cpuUsagePercent = null
        )
    }

    fun needsAuthentication(): Boolean {
        val authService = AuthenticationService.getInstance()
        return authService.isAuthenticating()
    }

    fun triggerAuth() {
        if (isRunning.get()) {
            val authService = AuthenticationService.getInstance()
            authService.triggerServerAuth(project)
        }
    }

    private fun parseLogForPlayers(line: String) {
        // Delegate auth parsing to centralized service
        val authService = AuthenticationService.getInstance()
        authService.parseServerLogLine(line, project)

        for (pattern in JOIN_PATTERNS) {
            pattern.find(line)?.let { match ->
                val playerName = match.groupValues[1]
                if (connectedPlayers.add(playerName)) {
                    playerCount.incrementAndGet()
                }
                return
            }
        }

        for (pattern in LEAVE_PATTERNS) {
            pattern.find(line)?.let { match ->
                val playerName = match.groupValues[1]
                if (connectedPlayers.remove(playerName)) {
                    playerCount.decrementAndGet()
                }
                return
            }
        }
    }

    /**
     * Validates that all required files exist for launching the server.
     */
    fun validateServerFiles(serverPath: Path): ValidationResult {
        val errors = mutableListOf<String>()

        val serverJar = serverPath.resolve(ServerDownloadService.SERVER_JAR_NAME)
        if (!Files.exists(serverJar)) {
            errors.add("HytaleServer.jar not found")
        }

        val assetsZip = serverPath.resolve(ServerDownloadService.ASSETS_NAME)
        if (!Files.exists(assetsZip)) {
            errors.add("Assets.zip not found (server may still work in some modes)")
        }

        return ValidationResult(errors.isEmpty(), errors)
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>
    )

    /**
     * Starts the Hytale server with the given configuration.
     */
    fun startServer(
        config: ServerConfig,
        logCallback: Consumer<String>? = null,
        statusCallback: Consumer<ServerStatus>? = null
    ): CompletableFuture<Boolean> {
        if (isRunning.get()) {
            return CompletableFuture.completedFuture(false)
        }

        return CompletableFuture.supplyAsync {
            try {
                status = ServerStatus.STARTING
                statusCallback?.accept(status)

                val command = buildCommand(config)

                logCallback?.accept("Starting server with command:")
                logCallback?.accept(command.joinToString(" "))
                logCallback?.accept("")

                val processBuilder = ProcessBuilder(command)
                    .directory(config.serverPath.toFile())
                    .redirectErrorStream(true)

                serverProcess = processBuilder.start()
                isRunning.set(true)
                startTime = Instant.now()
                playerCount.set(0)
                connectedPlayers.clear()

                // Capture process reference for safe access in background threads
                val process = serverProcess
                if (process == null) {
                    logCallback?.accept("Failed to start server: process is null")
                    status = ServerStatus.ERROR
                    statusCallback?.accept(status)
                    isRunning.set(false)
                    return@supplyAsync false
                }

                // Start log reader task using ExecutorService with daemon threads
                executorService.submit {
                    try {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val currentLine = line ?: continue
                                logCallback?.accept(currentLine)
                                parseLogForPlayers(currentLine)

                                // Detect server boot
                                if (currentLine.contains("Hytale Server Booted!") ||
                                    currentLine.contains("Server started")) {
                                    status = ServerStatus.RUNNING
                                    statusCallback?.accept(status)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            logCallback?.accept("Error reading logs: ${e.message}")
                        }
                    }
                }

                // Wait for process to complete (in background) using ExecutorService
                executorService.submit {
                    try {
                        val exitCode = process.waitFor()
                        isRunning.set(false)
                        status = ServerStatus.STOPPED
                        startTime = null
                        playerCount.set(0)
                        connectedPlayers.clear()
                        statusCallback?.accept(status)
                        logCallback?.accept("")
                        logCallback?.accept("Server stopped with exit code: $exitCode")
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        isRunning.set(false)
                        status = ServerStatus.STOPPED
                        startTime = null
                    } catch (e: Exception) {
                        isRunning.set(false)
                        status = ServerStatus.ERROR
                        startTime = null
                        statusCallback?.accept(status)
                        logCallback?.accept("Server error: ${e.message}")
                    }
                }

                true
            } catch (e: Exception) {
                status = ServerStatus.ERROR
                statusCallback?.accept(status)
                logCallback?.accept("Failed to start server: ${e.message}")
                isRunning.set(false)
                false
            }
        }
    }

    /**
     * Stops the running server gracefully.
     */
    fun stopServer(logCallback: Consumer<String>? = null): CompletableFuture<Boolean> {
        if (!isRunning.get()) {
            return CompletableFuture.completedFuture(false)
        }

        return CompletableFuture.supplyAsync {
            try {
                status = ServerStatus.STOPPING
                logCallback?.accept("Stopping server...")

                serverProcess?.let { process ->
                    // Try graceful shutdown first
                    process.outputStream.write("stop\n".toByteArray())
                    process.outputStream.flush()

                    // Wait up to 30 seconds for graceful shutdown
                    val exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)

                    if (!exited) {
                        logCallback?.accept("Server did not stop gracefully, forcing...")
                        process.destroyForcibly()
                        process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }

                isRunning.set(false)
                status = ServerStatus.STOPPED
                logCallback?.accept("Server stopped.")
                true
            } catch (e: Exception) {
                logCallback?.accept("Error stopping server: ${e.message}")
                // Force kill
                serverProcess?.destroyForcibly()
                isRunning.set(false)
                status = ServerStatus.STOPPED
                false
            }
        }
    }

    /**
     * Sends a command to the running server's stdin.
     */
    fun sendCommand(command: String): Boolean {
        if (!isRunning.get()) {
            LOG.warn("Cannot send command '$command': server is not running")
            return false
        }

        return try {
            serverProcess?.outputStream?.let { stream ->
                stream.write("$command\n".toByteArray())
                stream.flush()
                true
            } ?: run {
                LOG.warn("Cannot send command '$command': server process output stream is null")
                false
            }
        } catch (e: Exception) {
            LOG.warn("Failed to send command '$command' to server", e)
            false
        }
    }

    /**
     * Builds the command line arguments for launching the server.
     */
    private fun buildCommand(config: ServerConfig): List<String> {
        val command = mutableListOf<String>()

        // Java executable
        command.add(config.javaPath.toString())

        // Memory settings
        command.add("-Xms${config.minMemory}")
        command.add("-Xmx${config.maxMemory}")

        // Additional JVM args
        command.addAll(config.additionalJvmArgs)

        // JAR
        command.add("-jar")
        command.add(ServerDownloadService.SERVER_JAR_NAME)

        // Assets
        val assetsPath = config.serverPath.resolve(ServerDownloadService.ASSETS_NAME)
        if (Files.exists(assetsPath)) {
            command.add("--assets")
            command.add(ServerDownloadService.ASSETS_NAME)
        }

        // Bind address
        command.add("-b")
        command.add("0.0.0.0:${config.port}")

        // Auth mode
        command.add("--auth-mode")
        command.add(config.authMode.value)

        // Optional flags
        if (config.allowOp) {
            command.add("--allow-op")
        }

        if (config.acceptEarlyPlugins) {
            command.add("--accept-early-plugins")
        }

        // Additional server args
        command.addAll(config.additionalServerArgs)

        return command
    }

    /**
     * Creates a default server configuration for the project.
     */
    fun createDefaultConfig(
        serverPath: Path,
        javaInstallation: JavaInstallService.JavaInstallation
    ): ServerConfig {
        val javaService = JavaInstallService.getInstance()
        val javaPath = javaService.getJavaExecutable(javaInstallation)

        return ServerConfig(
            serverPath = serverPath,
            javaPath = javaPath
        )
    }

    /**
     * Cleans up resources when the service is disposed.
     * Stops the server if running and shuts down the executor service.
     */
    override fun dispose() {
        // Stop the server if it's running
        if (isRunning.get()) {
            serverProcess?.let { process ->
                try {
                    process.outputStream.write("stop\n".toByteArray())
                    process.outputStream.flush()
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                    }
                } catch (e: Exception) {
                    process.destroyForcibly()
                }
            }
            isRunning.set(false)
            status = ServerStatus.STOPPED
        }

        // Shutdown the executor service gracefully
        executorService.shutdown()
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
                executorService.awaitTermination(2, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            executorService.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
