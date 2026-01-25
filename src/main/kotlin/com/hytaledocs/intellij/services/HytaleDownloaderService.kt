package com.hytaledocs.intellij.services

import com.hytaledocs.intellij.util.HttpClientPool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.zip.ZipInputStream
import com.intellij.openapi.vfs.LocalFileSystem

@Service(Service.Level.APP)
class HytaleDownloaderService {

    companion object {
        const val DOWNLOADER_URL = "https://downloader.hytale.com/hytale-downloader.zip"
        const val VERIFICATION_URL = "https://oauth.accounts.hytale.com/oauth2/device/verify"

        fun getInstance(): HytaleDownloaderService {
            return ApplicationManager.getApplication().getService(HytaleDownloaderService::class.java)
        }
    }

    data class DownloaderStatus(
        val stage: Stage,
        val progress: Int = 0,
        val message: String = "",
        val deviceCode: String? = null,
        val verificationUrl: String? = null
    )

    enum class Stage {
        IDLE,
        DOWNLOADING_CLI,
        EXTRACTING_CLI,
        RUNNING_CLI,
        AWAITING_AUTH,
        AUTHENTICATED,
        DOWNLOADING_SERVER,
        EXTRACTING_SERVER,
        COMPLETED,
        ERROR
    }

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val isRunning = AtomicBoolean(false)
    private var currentProcess: Process? = null

    /**
     * Get the cache directory for the downloader.
     */
    private fun getCacheDir(): Path {
        val userHome = System.getProperty("user.home")
        return Paths.get(userHome, ".hytale-intellij", "downloader")
    }

    /**
     * Get the path to the downloader executable.
     */
    private fun getDownloaderExecutable(): Path {
        val exeName = if (isWindows) "hytale-downloader-windows-amd64.exe" else "hytale-downloader-linux-amd64"
        return getCacheDir().resolve(exeName)
    }

    /**
     * Check if the downloader CLI is already downloaded.
     */
    fun isDownloaderAvailable(): Boolean {
        return Files.exists(getDownloaderExecutable())
    }

    /**
     * Download and extract the Hytale Downloader CLI.
     */
    fun ensureDownloader(statusCallback: Consumer<DownloaderStatus>? = null): CompletableFuture<Path> {
        return CompletableFuture.supplyAsync {
            val downloaderPath = getDownloaderExecutable()

            if (Files.exists(downloaderPath)) {
                return@supplyAsync downloaderPath
            }

            statusCallback?.accept(DownloaderStatus(Stage.DOWNLOADING_CLI, 0, "Downloading Hytale Downloader CLI..."))

            val cacheDir = getCacheDir()
            Files.createDirectories(cacheDir)

            val zipPath = cacheDir.resolve("hytale-downloader.zip")

            // Download the zip
            val request = HttpRequest.newBuilder()
                .uri(URI.create(DOWNLOADER_URL))
                .GET()
                .build()

            val response = HttpClientPool.client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() != 200) {
                throw RuntimeException("Failed to download Hytale Downloader: HTTP ${response.statusCode()}")
            }

            val contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
            var downloaded = 0L

            FileOutputStream(zipPath.toFile()).use { output ->
                response.body().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        if (contentLength > 0) {
                            val percent = (downloaded * 100 / contentLength).toInt()
                            statusCallback?.accept(DownloaderStatus(Stage.DOWNLOADING_CLI, percent, "Downloading CLI... $percent%"))
                        }
                    }
                }
            }

            statusCallback?.accept(DownloaderStatus(Stage.EXTRACTING_CLI, 0, "Extracting CLI..."))

            // Extract the zip
            ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val fileName = File(entry.name).name
                        if (fileName.contains("hytale-downloader")) {
                            val targetPath = cacheDir.resolve(fileName)
                            Files.copy(zis, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

                            // Make executable on Unix
                            if (!isWindows) {
                                targetPath.toFile().setExecutable(true)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Clean up
            Files.deleteIfExists(zipPath)

            // Refresh VFS to make extracted files visible to IntelliJ
            LocalFileSystem.getInstance().refreshAndFindFileByPath(cacheDir.toString())

            statusCallback?.accept(DownloaderStatus(Stage.EXTRACTING_CLI, 100, "CLI ready!"))

            downloaderPath
        }
    }

    /**
     * Run the Hytale Downloader to download server files.
     * This will prompt the user for authentication via browser.
     */
    fun downloadServerFiles(
        destinationPath: Path,
        statusCallback: Consumer<DownloaderStatus>? = null
    ): CompletableFuture<Boolean> {
        if (isRunning.get()) {
            return CompletableFuture.completedFuture(false)
        }

        return CompletableFuture.supplyAsync {
            isRunning.set(true)

            try {
                // Ensure downloader is available
                val downloaderPath = ensureDownloader(statusCallback)
                    .exceptionally { e ->
                        throw RuntimeException("Failed to download CLI: ${e.message}", e)
                    }
                    .get(300, TimeUnit.SECONDS)

                // Create destination directory
                Files.createDirectories(destinationPath)

                statusCallback?.accept(DownloaderStatus(Stage.RUNNING_CLI, 0, "Starting Hytale Downloader..."))

                // Run the downloader
                val processBuilder = ProcessBuilder(downloaderPath.toString())
                    .directory(destinationPath.toFile())
                    .redirectErrorStream(true)

                currentProcess = processBuilder.start()
                val process = currentProcess ?: return@supplyAsync false

                // Regex patterns for parsing output
                val progressRegex = """(\d+)\.?\d*%""".toRegex()
                val authService = AuthenticationService.getInstance()

                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue

                        // Delegate auth parsing to centralized service
                        val wasAuthLine = authService.parseDownloaderLine(currentLine, null)

                        // Update status based on auth state
                        val session = authService.getCurrentSession()
                        if (session != null && session.source == AuthenticationService.AuthSource.DOWNLOADER) {
                            when (session.state) {
                                AuthenticationService.AuthState.CODE_DISPLAYED -> {
                                    statusCallback?.accept(
                                        DownloaderStatus(
                                            Stage.AWAITING_AUTH,
                                            0,
                                            "Enter code in browser: ${session.deviceCode}",
                                            deviceCode = session.deviceCode,
                                            verificationUrl = session.verificationUrl
                                        )
                                    )
                                }
                                AuthenticationService.AuthState.SUCCESS -> {
                                    statusCallback?.accept(DownloaderStatus(Stage.AUTHENTICATED, 0, "Authentication successful!"))
                                }
                                else -> {}
                            }
                        }

                        // Check for download progress
                        if (!wasAuthLine) {
                            progressRegex.find(currentLine)?.let { match ->
                                val progress = match.groupValues[1].toIntOrNull() ?: 0
                                statusCallback?.accept(DownloaderStatus(Stage.DOWNLOADING_SERVER, progress, "Downloading server files... $progress%"))
                            }

                            // Check for completion
                            if (currentLine.contains("Download complete", ignoreCase = true) ||
                                currentLine.contains("Successfully downloaded", ignoreCase = true)) {
                                statusCallback?.accept(DownloaderStatus(Stage.EXTRACTING_SERVER, 50, "Extracting server files..."))
                            }
                        }
                    }
                }

                val exitCode = currentProcess?.waitFor() ?: -1

                if (exitCode == 0) {
                    // Extract server files from the downloaded zip
                    extractServerFiles(destinationPath, statusCallback)
                    statusCallback?.accept(DownloaderStatus(Stage.COMPLETED, 100, "Server files downloaded successfully!"))
                    true
                } else {
                    statusCallback?.accept(DownloaderStatus(Stage.ERROR, 0, "Downloader exited with code: $exitCode"))
                    false
                }
            } catch (e: Exception) {
                statusCallback?.accept(DownloaderStatus(Stage.ERROR, 0, "Error: ${e.message}"))
                false
            } finally {
                isRunning.set(false)
                currentProcess = null
            }
        }
    }

    /**
     * Extract server files from the downloaded zip (format: YYYY.MM.DD-*.zip).
     */
    private fun extractServerFiles(destinationPath: Path, statusCallback: Consumer<DownloaderStatus>?) {
        // Find the server zip file
        val serverZip = Files.list(destinationPath).use { stream ->
            stream
                .filter { path ->
                    val name = path.fileName.toString()
                    name.endsWith(".zip") && name.contains("-") && name[0].isDigit()
                }
                .max { a, b ->
                    Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b))
                }
                .orElse(null)
        } ?: return

        statusCallback?.accept(DownloaderStatus(Stage.EXTRACTING_SERVER, 0, "Extracting ${serverZip.fileName}..."))

        ZipInputStream(Files.newInputStream(serverZip)).use { zis ->
            var entry = zis.nextEntry
            var extractedCount = 0

            while (entry != null) {
                val name = entry.name

                // Handle root-level .zip files (like Assets.zip)
                if (!name.contains("/") && name.endsWith(".zip")) {
                    val targetPath = destinationPath.resolve(name)
                    Files.copy(zis, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    extractedCount++
                }
                // Extract files from Server/ directory
                else if (name.startsWith("Server/")) {
                    val relativePath = name.removePrefix("Server/")
                    if (relativePath.isNotEmpty()) {
                        val targetPath = destinationPath.resolve(relativePath)

                        if (entry.isDirectory) {
                            Files.createDirectories(targetPath)
                        } else {
                            Files.createDirectories(targetPath.parent)
                            Files.copy(zis, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            extractedCount++
                        }
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }

            statusCallback?.accept(DownloaderStatus(Stage.EXTRACTING_SERVER, 100, "Extracted $extractedCount files"))
        }

        // Refresh VFS to make extracted server files visible to IntelliJ
        LocalFileSystem.getInstance().refreshAndFindFileByPath(destinationPath.toString())
    }

    /**
     * Cancel the running downloader.
     */
    fun cancel() {
        currentProcess?.destroyForcibly()
        currentProcess = null
        isRunning.set(false)
    }

    /**
     * Check if the downloader is currently running.
     */
    fun isDownloading(): Boolean = isRunning.get()
}
