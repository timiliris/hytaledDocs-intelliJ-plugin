package com.hytaledocs.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import java.util.zip.ZipInputStream

@Service(Service.Level.PROJECT)
class ServerDownloadService(private val project: Project) {

    companion object {
        // Note: CDN may not be publicly available yet - returns 404
        // Users need to use the Hytale downloader with authentication or manually place the JAR
        const val SERVER_JAR_URL = "https://cdn.hytale.com/HytaleServer.jar"
        const val SERVER_JAR_NAME = "HytaleServer.jar"
        const val ASSETS_NAME = "Assets.zip"
        const val DOWNLOADER_URL = "https://downloader.hytale.com/hytale-downloader.zip"
        const val DEFAULT_PORT = 5520

        fun getInstance(project: Project): ServerDownloadService {
            return project.getService(ServerDownloadService::class.java)
        }
    }

    data class DownloadProgress(
        val stage: String,
        val progress: Int,  // 0-100
        val message: String
    )

    /**
     * Downloads the Hytale server JAR directly from CDN.
     * This is the simple method that doesn't require authentication.
     */
    fun downloadServerJar(
        targetDir: Path,
        progressCallback: Consumer<DownloadProgress>? = null
    ): CompletableFuture<Path> {
        return CompletableFuture.supplyAsync {
            val targetPath = targetDir.resolve(SERVER_JAR_NAME)

            progressCallback?.accept(DownloadProgress("download", 0, "Starting download..."))

            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(SERVER_JAR_URL))
                .GET()
                .build()

            progressCallback?.accept(DownloadProgress("download", 10, "Connecting to CDN..."))

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() == 404) {
                throw RuntimeException(
                    "Server JAR not available from CDN (HTTP 404).\n\n" +
                    "The Hytale server is not yet publicly downloadable.\n" +
                    "You can:\n" +
                    "1. Manually place HytaleServer.jar in the 'server' folder\n" +
                    "2. Use the official Hytale downloader with your account\n" +
                    "3. Wait for public server availability"
                )
            }

            if (response.statusCode() != 200) {
                throw RuntimeException("Failed to download server JAR: HTTP ${response.statusCode()}")
            }

            val contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
            var downloaded = 0L

            progressCallback?.accept(DownloadProgress("download", 20, "Downloading HytaleServer.jar..."))

            Files.createDirectories(targetDir)

            FileOutputStream(targetPath.toFile()).use { output ->
                response.body().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        if (contentLength > 0) {
                            val percent = (20 + (downloaded * 70 / contentLength)).toInt()
                            progressCallback?.accept(
                                DownloadProgress("download", percent, "Downloading... ${downloaded / 1024 / 1024}MB")
                            )
                        }
                    }
                }
            }

            progressCallback?.accept(DownloadProgress("download", 100, "Download complete!"))
            targetPath
        }
    }

    /**
     * Downloads the Hytale downloader CLI tool for full server setup with authentication.
     */
    fun downloadHytaleDownloader(
        targetDir: Path,
        progressCallback: Consumer<DownloadProgress>? = null
    ): CompletableFuture<Path> {
        return CompletableFuture.supplyAsync {
            progressCallback?.accept(DownloadProgress("downloader", 0, "Downloading Hytale Downloader..."))

            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(DOWNLOADER_URL))
                .GET()
                .build()

            val zipPath = targetDir.resolve("hytale-downloader.zip")
            Files.createDirectories(targetDir)

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() != 200) {
                throw RuntimeException("Failed to download Hytale Downloader: HTTP ${response.statusCode()}")
            }

            // Save zip
            FileOutputStream(zipPath.toFile()).use { output ->
                response.body().use { input ->
                    input.copyTo(output)
                }
            }

            progressCallback?.accept(DownloadProgress("downloader", 50, "Extracting..."))

            // Extract zip
            val extractDir = targetDir.resolve("hytale-downloader")
            extractZip(zipPath, extractDir)

            // Find executable
            val executableName = when {
                System.getProperty("os.name").lowercase().contains("win") -> "hytale-downloader.exe"
                else -> "hytale-downloader"
            }

            val executable = findExecutable(extractDir, executableName)
                ?: throw RuntimeException("Could not find $executableName in downloaded archive")

            // Make executable on Unix
            if (!System.getProperty("os.name").lowercase().contains("win")) {
                executable.toFile().setExecutable(true)
            }

            // Clean up zip
            Files.deleteIfExists(zipPath)

            progressCallback?.accept(DownloadProgress("downloader", 100, "Hytale Downloader ready!"))
            executable
        }
    }

    /**
     * Creates the standard server directory structure.
     */
    fun createServerDirectories(serverPath: Path) {
        listOf(
            "worlds",
            "config",
            "mods",
            "plugins",
            "logs",
            "backups"
        ).forEach { dir ->
            Files.createDirectories(serverPath.resolve(dir))
        }
    }

    /**
     * Checks if server files are present.
     */
    fun hasServerFiles(serverPath: Path): ServerFilesStatus {
        val hasJar = Files.exists(serverPath.resolve(SERVER_JAR_NAME))
        val hasAssets = Files.exists(serverPath.resolve(ASSETS_NAME))
        return ServerFilesStatus(hasJar, hasAssets)
    }

    data class ServerFilesStatus(
        val hasServerJar: Boolean,
        val hasAssets: Boolean
    ) {
        val isComplete: Boolean get() = hasServerJar && hasAssets
        val canStartOffline: Boolean get() = hasServerJar // Can start without assets in some modes
    }

    private fun extractZip(zipPath: Path, targetDir: Path) {
        Files.createDirectories(targetDir)

        ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val targetPath = targetDir.resolve(entry.name)

                if (entry.isDirectory) {
                    Files.createDirectories(targetPath)
                } else {
                    Files.createDirectories(targetPath.parent)
                    Files.copy(zis, targetPath)
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun findExecutable(dir: Path, name: String): Path? {
        return Files.walk(dir)
            .filter { it.fileName.toString() == name }
            .findFirst()
            .orElse(null)
    }
}
