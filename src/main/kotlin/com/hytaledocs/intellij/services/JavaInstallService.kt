package com.hytaledocs.intellij.services

import com.hytaledocs.intellij.util.HttpClientPool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.io.*
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import com.intellij.openapi.vfs.LocalFileSystem

@Service(Service.Level.APP)
class JavaInstallService {

    companion object {
        const val REQUIRED_JAVA_VERSION = 25
        const val ADOPTIUM_API_BASE = "https://api.adoptium.net/v3/binary/latest"

        fun getInstance(): JavaInstallService {
            return ApplicationManager.getApplication().getService(JavaInstallService::class.java)
        }

        private val JAVA_SEARCH_PATHS_WINDOWS = listOf(
            "C:\\Program Files\\Java",
            "C:\\Program Files\\Eclipse Adoptium",
            "C:\\Program Files\\Eclipse Foundation",
            "C:\\Program Files\\Temurin",
            "C:\\Program Files\\OpenJDK",
            "C:\\Program Files\\Microsoft\\jdk-21",
            "C:\\Program Files\\Microsoft\\jdk-25",
            "C:\\Program Files\\Zulu",
            "C:\\Program Files\\BellSoft\\LibericaJDK-21",
            "C:\\Program Files\\BellSoft\\LibericaJDK-25",
            "C:\\Program Files\\Amazon Corretto",
            System.getenv("USERPROFILE")?.let { "$it\\.jdks" } ?: ""
        ).filter { it.isNotEmpty() }

        private val JAVA_SEARCH_PATHS_MAC = listOf(
            "/Library/Java/JavaVirtualMachines",
            "/usr/local/opt/openjdk@21",
            "/usr/local/opt/openjdk@25"
        )

        private val JAVA_SEARCH_PATHS_LINUX = listOf(
            "/usr/lib/jvm",
            "/usr/java",
            "/opt/java"
        )
    }

    data class JavaInstallation(
        val version: String,
        val path: Path,
        val isManaged: Boolean  // true if installed by this plugin
    )

    data class DownloadProgress(
        val stage: String,
        val progress: Int,
        val message: String
    )

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")
    private val isLinux = !isWindows && !isMac

    /**
     * Get the managed Java installation directory.
     */
    fun getManagedJavaDir(): Path {
        val userHome = System.getProperty("user.home")
        return Paths.get(userHome, ".hytale-intellij", "java")
    }

    /**
     * Find all Java installations on the system.
     */
    fun findJavaInstallations(): List<JavaInstallation> {
        val installations = mutableListOf<JavaInstallation>()

        // Check JAVA_HOME
        System.getenv("JAVA_HOME")?.let { javaHome ->
            getJavaVersion(Paths.get(javaHome))?.let { version ->
                installations.add(JavaInstallation(version, Paths.get(javaHome), false))
            }
        }

        // Check system paths
        val searchPaths = when {
            isWindows -> JAVA_SEARCH_PATHS_WINDOWS
            isMac -> JAVA_SEARCH_PATHS_MAC
            else -> JAVA_SEARCH_PATHS_LINUX
        }

        searchPaths.forEach { basePath ->
            val dir = File(basePath)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { javaDir ->
                    if (javaDir.isDirectory) {
                        getJavaVersion(javaDir.toPath())?.let { version ->
                            installations.add(JavaInstallation(version, javaDir.toPath(), false))
                        }
                    }
                }
            }
        }

        // Check managed installations
        val managedDir = getManagedJavaDir()
        if (Files.exists(managedDir)) {
            Files.list(managedDir).forEach { javaDir ->
                if (Files.isDirectory(javaDir)) {
                    getJavaVersion(javaDir)?.let { version ->
                        installations.add(JavaInstallation(version, javaDir, true))
                    }
                }
            }
        }

        return installations.distinctBy { it.path }
    }

    /**
     * Find Java 25+ installation.
     */
    fun findJava25(): JavaInstallation? {
        return findJavaInstallations()
            .filter { getMajorVersion(it.version) >= REQUIRED_JAVA_VERSION }
            .maxByOrNull { getMajorVersion(it.version) }
    }

    /**
     * Download and install Java from Adoptium.
     */
    fun installJava(
        version: Int = REQUIRED_JAVA_VERSION,
        progressCallback: Consumer<DownloadProgress>? = null
    ): CompletableFuture<JavaInstallation> {
        return CompletableFuture.supplyAsync {
            progressCallback?.accept(DownloadProgress("prepare", 0, "Preparing to download Java $version..."))

            val os = when {
                isWindows -> "windows"
                isMac -> "mac"
                else -> "linux"
            }

            val arch = when (System.getProperty("os.arch")) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "aarch64"
                else -> "x64"
            }

            val extension = if (isWindows) "zip" else "tar.gz"

            // Adoptium API URL
            val downloadUrl = "$ADOPTIUM_API_BASE/$version/ga/$os/$arch/jdk/hotspot/normal/eclipse?project=jdk"

            progressCallback?.accept(DownloadProgress("download", 5, "Connecting to Adoptium..."))

            val request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .GET()
                .build()

            val tempFile = Files.createTempFile("java-$version", ".$extension")

            try {
                val response = HttpClientPool.client.send(request, HttpResponse.BodyHandlers.ofInputStream())

                if (response.statusCode() != 200) {
                    throw RuntimeException("Failed to download Java: HTTP ${response.statusCode()}")
                }

                val contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
                var downloaded = 0L

                progressCallback?.accept(DownloadProgress("download", 10, "Downloading Java $version..."))

                FileOutputStream(tempFile.toFile()).use { output ->
                    response.body().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            if (contentLength > 0) {
                                val percent = (10 + (downloaded * 60 / contentLength)).toInt()
                                val mb = downloaded / 1024 / 1024
                                progressCallback?.accept(
                                    DownloadProgress("download", percent, "Downloading... ${mb}MB")
                                )
                            }
                        }
                    }
                }

                progressCallback?.accept(DownloadProgress("extract", 70, "Extracting Java $version..."))

                // Create managed directory
                val managedDir = getManagedJavaDir()
                Files.createDirectories(managedDir)

                val installDir = managedDir.resolve("temurin-$version")
                if (Files.exists(installDir)) {
                    // Delete existing installation
                    installDir.toFile().deleteRecursively()
                }
                Files.createDirectories(installDir)

                // Extract
                if (isWindows) {
                    extractZip(tempFile, installDir)
                } else {
                    extractTarGz(tempFile, installDir)
                }

                progressCallback?.accept(DownloadProgress("verify", 90, "Verifying installation..."))

                // Find the actual JDK directory (it's usually nested)
                val jdkDir = Files.list(installDir)
                    .filter { Files.isDirectory(it) }
                    .filter { it.fileName.toString().startsWith("jdk") }
                    .findFirst()
                    .orElse(installDir)

                // Verify installation
                val installedVersion = getJavaVersion(jdkDir)
                    ?: throw RuntimeException("Failed to verify Java installation")

                // Refresh VFS to make installed Java files visible to IntelliJ
                LocalFileSystem.getInstance().refreshAndFindFileByPath(jdkDir.toString())

                progressCallback?.accept(DownloadProgress("complete", 100, "Java $version installed successfully!"))

                JavaInstallation(installedVersion, jdkDir, true)

            } finally {
                Files.deleteIfExists(tempFile)
            }
        }
    }

    /**
     * Get the Java executable path for an installation.
     */
    fun getJavaExecutable(installation: JavaInstallation): Path {
        return when {
            isWindows -> installation.path.resolve("bin/java.exe")
            isMac -> {
                // macOS might have Contents/Home structure
                val contentsHome = installation.path.resolve("Contents/Home/bin/java")
                if (Files.exists(contentsHome)) contentsHome
                else installation.path.resolve("bin/java")
            }
            else -> installation.path.resolve("bin/java")
        }
    }

    /**
     * Check if an installation meets the minimum version requirement.
     */
    fun meetsRequirement(installation: JavaInstallation): Boolean {
        return getMajorVersion(installation.version) >= REQUIRED_JAVA_VERSION
    }

    private fun getJavaVersion(javaHome: Path): String? {
        val javaExe = when {
            isWindows -> javaHome.resolve("bin/java.exe")
            isMac -> {
                val contentsHome = javaHome.resolve("Contents/Home/bin/java")
                if (Files.exists(contentsHome)) contentsHome
                else javaHome.resolve("bin/java")
            }
            else -> javaHome.resolve("bin/java")
        }

        if (!Files.exists(javaExe)) return null

        return try {
            val process = ProcessBuilder(javaExe.toString(), "-version")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // Parse version from output - handles multiple formats:
            // openjdk version "21.0.1" 2023-10-17
            // java version "25"
            // openjdk version "25-ea" 2025-03-18
            // Eclipse Temurin version "21.0.5+11"
            val versionPatterns = listOf(
                """version\s+"(\d+)""".toRegex(),  // Matches version "21, version "25
                """(\d+)(?:\.\d+)*(?:-\w+)?""".toRegex()  // Fallback: any major version number
            )

            for (pattern in versionPatterns) {
                pattern.find(output)?.groupValues?.get(1)?.let {
                    // Return full version string for display but extract major for comparison
                    return it
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getMajorVersion(version: String): Int {
        return version.split(".").firstOrNull()?.toIntOrNull() ?: 0
    }

    private fun extractZip(zipFile: Path, targetDir: Path) {
        ZipInputStream(Files.newInputStream(zipFile)).use { zis ->
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

    private fun extractTarGz(tarGzFile: Path, targetDir: Path) {
        GZIPInputStream(Files.newInputStream(tarGzFile)).use { gzis ->
            TarArchiveInputStream(gzis).use { tais ->
                var entry = tais.nextEntry
                while (entry != null) {
                    val targetPath = targetDir.resolve(entry.name)

                    if (entry.isDirectory) {
                        Files.createDirectories(targetPath)
                    } else {
                        Files.createDirectories(targetPath.parent)
                        Files.copy(tais, targetPath)

                        // Preserve executable permissions
                        if (!isWindows && (entry.mode and 0x49) != 0) { // Check for any execute bit
                            try {
                                Files.setPosixFilePermissions(
                                    targetPath,
                                    PosixFilePermissions.fromString("rwxr-xr-x")
                                )
                            } catch (e: UnsupportedOperationException) {
                                // Ignore on non-POSIX systems
                            }
                        }
                    }

                    entry = tais.nextEntry
                }
            }
        }
    }
}
