package com.hytaledocs.intellij.services

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.hytaledocs.intellij.util.HttpClientPool
import com.hytaledocs.intellij.util.RetryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Service for caching downloaded Hytale server versions.
 * Stores server JARs in ~/.hytale-intellij/servers/{version}/
 */
@Service(Service.Level.APP)
class ServerVersionCacheService {

    companion object {
        private val LOG = Logger.getInstance(ServerVersionCacheService::class.java)
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        private const val CACHE_DIR_NAME = ".hytale-intellij"
        private const val SERVERS_DIR_NAME = "servers"
        private const val SERVER_JAR_NAME = "Server.jar"
        private const val METADATA_FILE_NAME = "version-metadata.json"

        fun getInstance(): ServerVersionCacheService {
            return ApplicationManager.getApplication().getService(ServerVersionCacheService::class.java)
        }
    }

    /**
     * Metadata stored alongside each cached version.
     */
    data class VersionMetadata(
        val version: String,
        val downloadTimestamp: Long,
        val downloadUrl: String,
        val fileSize: Long,
        val commitHash: String?
    )

    /**
     * Download progress callback data.
     */
    data class DownloadProgress(
        val stage: String,
        val progress: Int,  // 0-100
        val message: String,
        val bytesDownloaded: Long = 0,
        val totalBytes: Long = -1
    )

    /**
     * Gets the base cache directory path.
     */
    fun getCacheDirectory(): Path {
        val userHome = System.getProperty("user.home")
        return Path.of(userHome, CACHE_DIR_NAME, SERVERS_DIR_NAME)
    }

    /**
     * Gets the directory for a specific version.
     */
    fun getVersionDirectory(version: String): Path {
        return getCacheDirectory().resolve(sanitizeVersion(version))
    }

    /**
     * Gets the path to the server JAR for a specific version.
     */
    fun getServerJarPath(version: String): Path {
        return getVersionDirectory(version).resolve(SERVER_JAR_NAME)
    }

    /**
     * Gets the path to the metadata file for a specific version.
     */
    fun getMetadataPath(version: String): Path {
        return getVersionDirectory(version).resolve(METADATA_FILE_NAME)
    }

    /**
     * Checks if a version is cached locally.
     */
    fun isVersionCached(version: String): Boolean {
        val jarPath = getServerJarPath(version)
        return Files.exists(jarPath) && Files.size(jarPath) > 0
    }

    /**
     * Gets the metadata for a cached version.
     */
    fun getVersionMetadata(version: String): VersionMetadata? {
        val metadataPath = getMetadataPath(version)
        if (!Files.exists(metadataPath)) return null

        return try {
            val json = Files.readString(metadataPath)
            GSON.fromJson(json, VersionMetadata::class.java)
        } catch (e: Exception) {
            LOG.warn("Failed to read version metadata for $version", e)
            null
        }
    }

    /**
     * Lists all cached versions.
     */
    fun listCachedVersions(): List<String> {
        val cacheDir = getCacheDirectory()
        if (!Files.exists(cacheDir)) return emptyList()

        return Files.list(cacheDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .filter { Files.exists(it.resolve(SERVER_JAR_NAME)) }
                .map { it.fileName.toString() }
                .toList()
        }
    }

    /**
     * Gets detailed info about all cached versions.
     */
    fun listCachedVersionsWithMetadata(): List<VersionMetadata> {
        return listCachedVersions().mapNotNull { version ->
            getVersionMetadata(version)
        }
    }

    /**
     * Downloads a server version from Maven and caches it.
     *
     * @param version The version to download
     * @param downloadUrl The URL to download from
     * @param progressCallback Optional callback for download progress
     * @return CompletableFuture containing the path to the downloaded JAR
     */
    fun downloadAndCacheVersion(
        version: String,
        downloadUrl: String,
        progressCallback: Consumer<DownloadProgress>? = null
    ): CompletableFuture<Path> {
        return CompletableFuture.supplyAsync {
            val versionDir = getVersionDirectory(version)
            val jarPath = getServerJarPath(version)
            val metadataPath = getMetadataPath(version)

            // Create directories
            Files.createDirectories(versionDir)

            progressCallback?.accept(
                DownloadProgress("download", 0, "Starting download of version $version...")
            )

            LOG.info("Downloading server version $version from: $downloadUrl")

            val request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .header("User-Agent", "HytaleIntelliJPlugin/1.0")
                .GET()
                .build()

            progressCallback?.accept(
                DownloadProgress("download", 5, "Connecting to Maven repository...")
            )

            val response = RetryUtil.withRetry(
                maxAttempts = 3,
                delayMs = 2000,
                operation = "Download server JAR $version"
            ) {
                HttpClientPool.client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            }

            when (response.statusCode()) {
                200 -> {
                    val contentLength = response.headers()
                        .firstValueAsLong("content-length")
                        .orElse(-1L)

                    var downloaded = 0L

                    progressCallback?.accept(
                        DownloadProgress(
                            "download", 10,
                            "Downloading Server-$version.jar...",
                            0, contentLength
                        )
                    )

                    response.body().use { input ->
                        FileOutputStream(jarPath.toFile()).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloaded += bytesRead

                                if (contentLength > 0) {
                                    val percent = (10 + (downloaded * 85 / contentLength)).toInt()
                                    val mbDownloaded = downloaded / 1024 / 1024
                                    val mbTotal = contentLength / 1024 / 1024

                                    progressCallback?.accept(
                                        DownloadProgress(
                                            "download", percent,
                                            "Downloading... ${mbDownloaded}MB / ${mbTotal}MB",
                                            downloaded, contentLength
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Parse commit hash from version
                    val commitHash = version.split("-", limit = 2).getOrNull(1)

                    // Save metadata
                    val metadata = VersionMetadata(
                        version = version,
                        downloadTimestamp = Instant.now().toEpochMilli(),
                        downloadUrl = downloadUrl,
                        fileSize = Files.size(jarPath),
                        commitHash = commitHash
                    )

                    Files.writeString(metadataPath, GSON.toJson(metadata))

                    progressCallback?.accept(
                        DownloadProgress("download", 100, "Download complete!")
                    )

                    LOG.info("Successfully downloaded and cached server version $version (${metadata.fileSize / 1024 / 1024}MB)")
                    jarPath
                }
                403 -> throw RuntimeException(
                    "Access denied when downloading server $version (HTTP 403).\n" +
                    "The Maven repository may require authentication."
                )
                404 -> throw RuntimeException(
                    "Server version $version not found (HTTP 404).\n" +
                    "This version may not exist in the Maven repository."
                )
                else -> throw RuntimeException(
                    "Failed to download server $version: HTTP ${response.statusCode()}"
                )
            }
        }
    }

    /**
     * Copies a cached version to a target directory.
     *
     * @param version The version to copy
     * @param targetDir The target directory
     * @param targetFileName The name for the copied JAR (default: HytaleServer.jar)
     * @return The path to the copied JAR
     */
    fun copyToDirectory(
        version: String,
        targetDir: Path,
        targetFileName: String = "HytaleServer.jar"
    ): Path {
        if (!isVersionCached(version)) {
            throw IllegalStateException("Version $version is not cached")
        }

        val sourcePath = getServerJarPath(version)
        val targetPath = targetDir.resolve(targetFileName)

        Files.createDirectories(targetDir)
        Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

        LOG.info("Copied server $version to $targetPath")
        return targetPath
    }

    /**
     * Deletes a cached version.
     */
    fun deleteVersion(version: String): Boolean {
        val versionDir = getVersionDirectory(version)
        if (!Files.exists(versionDir)) return false

        return try {
            Files.walk(versionDir).use { stream ->
                stream
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
            LOG.info("Deleted cached version $version")
            true
        } catch (e: Exception) {
            LOG.warn("Failed to delete cached version $version", e)
            false
        }
    }

    /**
     * Calculates total size of all cached versions.
     */
    fun getTotalCacheSize(): Long {
        return listCachedVersions().sumOf { version ->
            try {
                Files.size(getServerJarPath(version))
            } catch (e: Exception) {
                0L
            }
        }
    }

    /**
     * Sanitizes a version string for use as a directory name.
     */
    private fun sanitizeVersion(version: String): String {
        return version.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
