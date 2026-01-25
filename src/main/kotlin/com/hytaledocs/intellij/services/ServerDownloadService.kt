package com.hytaledocs.intellij.services

import com.hytaledocs.intellij.settings.HytaleAppSettings
import com.hytaledocs.intellij.util.HttpClientPool
import com.hytaledocs.intellij.util.RetryUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import java.util.zip.ZipInputStream
import com.intellij.openapi.vfs.LocalFileSystem

@Service(Service.Level.PROJECT)
class ServerDownloadService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(ServerDownloadService::class.java)

        // Legacy CDN URL (may not be publicly available)
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
     * Downloads the Hytale server JAR from Maven repository.
     * This is the primary download method using versioned artifacts.
     *
     * @param targetDir Directory to save the JAR
     * @param version Specific version to download, or null for latest
     * @param progressCallback Optional progress callback
     * @return CompletableFuture containing the path to the downloaded JAR
     */
    fun downloadServerJarFromMaven(
        targetDir: Path,
        version: String? = null,
        progressCallback: Consumer<DownloadProgress>? = null
    ): CompletableFuture<Path> {
        return CompletableFuture.supplyAsync {
            progressCallback?.accept(DownloadProgress("maven", 0, "Checking Maven repository..."))

            val mavenService = MavenMetadataService.getInstance()
            val cacheService = ServerVersionCacheService.getInstance()
            val settings = HytaleAppSettings.getInstance()

            // Determine which version to download
            val targetVersion = version
                ?: settings.preferredServerVersion.takeIf { it.isNotBlank() }
                ?: mavenService.getLatestVersion().get()?.version
                ?: throw RuntimeException("No server version available from Maven repository")

            LOG.info("Target server version: $targetVersion")
            progressCallback?.accept(DownloadProgress("maven", 10, "Version: $targetVersion"))

            val targetPath = targetDir.resolve(SERVER_JAR_NAME)

            // Check if version is already cached
            if (cacheService.isVersionCached(targetVersion)) {
                progressCallback?.accept(DownloadProgress("maven", 50, "Using cached version $targetVersion"))
                LOG.info("Using cached version: $targetVersion")

                // Copy from cache to target
                cacheService.copyToDirectory(targetVersion, targetDir, SERVER_JAR_NAME)

                // Update last used version
                settings.lastUsedServerVersion = targetVersion

                progressCallback?.accept(DownloadProgress("maven", 100, "Server JAR ready!"))
                LocalFileSystem.getInstance().refreshAndFindFileByPath(targetPath.toString())
                return@supplyAsync targetPath
            }

            // Download and cache the version
            progressCallback?.accept(DownloadProgress("maven", 20, "Downloading version $targetVersion..."))

            val downloadUrl = MavenMetadataService.getJarDownloadUrl(targetVersion)
            LOG.info("Downloading from: $downloadUrl")

            cacheService.downloadAndCacheVersion(
                version = targetVersion,
                downloadUrl = downloadUrl,
                progressCallback = { cacheProgress ->
                    // Map cache progress to our progress (20-90%)
                    val mappedProgress = 20 + (cacheProgress.progress * 70 / 100)
                    progressCallback?.accept(DownloadProgress("maven", mappedProgress, cacheProgress.message))
                }
            ).get()

            // Copy from cache to target
            progressCallback?.accept(DownloadProgress("maven", 95, "Copying to project..."))
            cacheService.copyToDirectory(targetVersion, targetDir, SERVER_JAR_NAME)

            // Update last used version
            settings.lastUsedServerVersion = targetVersion

            progressCallback?.accept(DownloadProgress("maven", 100, "Server JAR ready!"))
            LocalFileSystem.getInstance().refreshAndFindFileByPath(targetPath.toString())

            targetPath
        }
    }

    /**
     * Gets available server versions from Maven.
     */
    fun getAvailableVersions(): CompletableFuture<List<MavenMetadataService.ServerVersion>> {
        return MavenMetadataService.getInstance().getAvailableVersions()
    }

    /**
     * Gets the latest server version from Maven.
     */
    fun getLatestVersion(): CompletableFuture<MavenMetadataService.ServerVersion?> {
        return MavenMetadataService.getInstance().getLatestVersion()
    }

    /**
     * Checks if a specific version is cached locally.
     */
    fun isVersionCached(version: String): Boolean {
        return ServerVersionCacheService.getInstance().isVersionCached(version)
    }

    /**
     * Downloads the Hytale server JAR directly from CDN.
     * This is the legacy method - prefer downloadServerJarFromMaven() instead.
     *
     * @deprecated Use downloadServerJarFromMaven() for versioned downloads
     */
    @Deprecated("Use downloadServerJarFromMaven() for versioned downloads from Maven repository")
    fun downloadServerJar(
        targetDir: Path,
        progressCallback: Consumer<DownloadProgress>? = null
    ): CompletableFuture<Path> {
        return CompletableFuture.supplyAsync {
            val targetPath = targetDir.resolve(SERVER_JAR_NAME)

            progressCallback?.accept(DownloadProgress("download", 0, "Starting download..."))

            val request = HttpRequest.newBuilder()
                .uri(URI.create(SERVER_JAR_URL))
                .GET()
                .build()

            progressCallback?.accept(DownloadProgress("download", 10, "Connecting to CDN..."))

            val response = RetryUtil.withRetry(
                maxAttempts = 3,
                delayMs = 2000,
                operation = "Download server JAR"
            ) {
                HttpClientPool.client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            }

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

            // Refresh VFS to make downloaded JAR visible to IntelliJ
            LocalFileSystem.getInstance().refreshAndFindFileByPath(targetPath.toString())

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

            val request = HttpRequest.newBuilder()
                .uri(URI.create(DOWNLOADER_URL))
                .GET()
                .build()

            val zipPath = targetDir.resolve("hytale-downloader.zip")
            Files.createDirectories(targetDir)

            val response = RetryUtil.withRetry(
                maxAttempts = 3,
                delayMs = 2000,
                operation = "Download Hytale Downloader"
            ) {
                HttpClientPool.client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            }

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

            // Refresh VFS to make extracted files visible to IntelliJ
            LocalFileSystem.getInstance().refreshAndFindFileByPath(extractDir.toString())

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
        return Files.walk(dir).use { stream ->
            stream
                .filter { it.fileName.toString() == name }
                .findFirst()
                .orElse(null)
        }
    }
}
