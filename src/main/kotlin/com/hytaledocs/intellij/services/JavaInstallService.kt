package com.hytaledocs.intellij.services

import com.hytaledocs.intellij.util.HttpClientPool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.LocalFileSystem
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

@Service(Service.Level.APP)
class JavaInstallService {

    companion object {
        const val REQUIRED_JAVA_VERSION = 25
        const val ADOPTIUM_API_BASE = "https://api.adoptium.net/v3/binary/latest"
        private val LOG = Logger.getInstance(JavaInstallService::class.java)

        fun getInstance(): JavaInstallService {
            return ApplicationManager.getApplication().getService(JavaInstallService::class.java)
        }

        // ==================== WINDOWS SEARCH PATHS ====================
        private fun getWindowsSearchPaths(): List<String> {
            val paths = mutableListOf<String>()
            val userHome = System.getProperty("user.home")
            val userProfile = System.getenv("USERPROFILE") ?: userHome
            val localAppData = System.getenv("LOCALAPPDATA") ?: "$userProfile\\AppData\\Local"

            // Standard installation directories
            paths.addAll(listOf(
                "C:\\Program Files\\Java",
                "C:\\Program Files\\Eclipse Adoptium",
                "C:\\Program Files\\Eclipse Foundation",
                "C:\\Program Files\\Temurin",
                "C:\\Program Files\\OpenJDK",
                "C:\\Program Files\\AdoptOpenJDK",
                "C:\\Program Files\\Zulu",
                "C:\\Program Files\\Amazon Corretto",
                "C:\\Program Files\\SapMachine",
                "C:\\Program Files\\BellSoft",
                "C:\\Program Files\\Semeru",
                "C:\\Program Files\\ojdkbuild",
                // 32-bit paths
                "C:\\Program Files (x86)\\Java",
                "C:\\Program Files (x86)\\Eclipse Adoptium",
                "C:\\Program Files (x86)\\Zulu"
            ))

            // Microsoft JDK (versioned directories)
            val msJdkDir = File("C:\\Program Files\\Microsoft")
            if (msJdkDir.exists()) {
                msJdkDir.listFiles()?.filter { it.name.startsWith("jdk-") }?.forEach {
                    paths.add(it.absolutePath)
                }
            }

            // GraalVM paths
            paths.addAll(listOf(
                "C:\\Program Files\\GraalVM",
                "C:\\Program Files\\graalvm"
            ))
            val graalDir = File("C:\\Program Files")
            graalDir.listFiles()?.filter {
                it.name.startsWith("graalvm-") || it.name.startsWith("GraalVM-")
            }?.forEach {
                paths.add(it.absolutePath)
            }

            // User-local installations
            paths.addAll(listOf(
                "$userProfile\\.jdks",                          // IntelliJ managed JDKs
                "$localAppData\\Programs\\Eclipse Adoptium",    // Windows user install
                "$localAppData\\Programs\\Temurin"
            ))

            // Package managers
            // Scoop
            val scoopHome = System.getenv("SCOOP") ?: "$userProfile\\scoop"
            paths.addAll(listOf(
                "$scoopHome\\apps\\temurin-lts-jdk\\current",
                "$scoopHome\\apps\\temurin21-jdk\\current",
                "$scoopHome\\apps\\temurin25-jdk\\current",
                "$scoopHome\\apps\\openjdk\\current",
                "$scoopHome\\apps\\openjdk21\\current",
                "$scoopHome\\apps\\openjdk25\\current",
                "$scoopHome\\apps\\graalvm\\current",
                "$scoopHome\\apps\\zulu-jdk\\current",
                "$scoopHome\\apps\\corretto-jdk\\current",
                "$scoopHome\\apps\\sapmachine-jdk\\current"
            ))
            // Also scan scoop apps directory for any java-like entries
            val scoopApps = File("$scoopHome\\apps")
            if (scoopApps.exists()) {
                scoopApps.listFiles()?.filter { dir ->
                    dir.isDirectory && (dir.name.contains("jdk", ignoreCase = true) ||
                            dir.name.contains("java", ignoreCase = true) ||
                            dir.name.contains("temurin", ignoreCase = true) ||
                            dir.name.contains("openjdk", ignoreCase = true) ||
                            dir.name.contains("graalvm", ignoreCase = true))
                }?.forEach { dir ->
                    paths.add("${dir.absolutePath}\\current")
                }
            }

            // Chocolatey
            val chocoHome = System.getenv("ChocolateyInstall") ?: "C:\\ProgramData\\chocolatey"
            paths.add("$chocoHome\\lib\\temurin\\tools")
            paths.add("$chocoHome\\lib\\openjdk\\tools")

            // SDKMAN on Windows (WSL/Git Bash)
            paths.add("$userProfile\\.sdkman\\candidates\\java")

            // jabba
            paths.add("$userProfile\\.jabba\\jdk")

            // IntelliJ's own JBR
            val intellijPath = PathManager.getHomePath()
            if (intellijPath != null) {
                paths.add("$intellijPath\\jbr")
            }

            return paths.filter { it.isNotEmpty() }.distinct()
        }

        // ==================== macOS SEARCH PATHS ====================
        private fun getMacSearchPaths(): List<String> {
            val paths = mutableListOf<String>()
            val userHome = System.getProperty("user.home")

            // Standard macOS JDK location
            paths.add("/Library/Java/JavaVirtualMachines")

            // Homebrew on Apple Silicon (M1/M2/M3)
            paths.addAll(listOf(
                "/opt/homebrew/opt/openjdk",
                "/opt/homebrew/opt/openjdk@21",
                "/opt/homebrew/opt/openjdk@25",
                "/opt/homebrew/opt/java",
                "/opt/homebrew/opt/java21",
                "/opt/homebrew/opt/java25",
                "/opt/homebrew/Cellar/openjdk",
                "/opt/homebrew/Cellar/openjdk@21",
                "/opt/homebrew/Cellar/openjdk@25"
            ))

            // Homebrew on Intel Macs
            paths.addAll(listOf(
                "/usr/local/opt/openjdk",
                "/usr/local/opt/openjdk@21",
                "/usr/local/opt/openjdk@25",
                "/usr/local/opt/java",
                "/usr/local/Cellar/openjdk",
                "/usr/local/Cellar/openjdk@21",
                "/usr/local/Cellar/openjdk@25"
            ))

            // Oracle JDK
            paths.add("/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home")

            // Version managers
            paths.addAll(listOf(
                "$userHome/.sdkman/candidates/java",     // SDKMAN
                "$userHome/.asdf/installs/java",         // asdf
                "$userHome/.jabba/jdk",                  // jabba
                "$userHome/.jenv/versions",              // jenv
                "$userHome/.local/share/mise/installs/java"  // mise (formerly rtx)
            ))

            // IntelliJ managed JDKs
            paths.add("$userHome/.jdks")
            paths.add("$userHome/Library/Java/JavaVirtualMachines")

            // IntelliJ's own JBR
            val intellijPath = PathManager.getHomePath()
            if (intellijPath != null) {
                paths.add("$intellijPath/jbr/Contents/Home")
                paths.add("$intellijPath/jbr")
            }

            return paths.filter { it.isNotEmpty() }.distinct()
        }

        // ==================== LINUX SEARCH PATHS ====================
        private fun getLinuxSearchPaths(): List<String> {
            val paths = mutableListOf<String>()
            val userHome = System.getProperty("user.home")

            // Standard Linux JDK locations
            paths.addAll(listOf(
                "/usr/lib/jvm",
                "/usr/lib64/jvm",
                "/usr/java",
                "/opt/java",
                "/opt/jdk",
                "/opt/openjdk"
            ))

            // Distribution-specific paths
            paths.addAll(listOf(
                // Arch Linux
                "/usr/lib/jvm/java-21-openjdk",
                "/usr/lib/jvm/java-25-openjdk",
                // Fedora/RHEL
                "/usr/lib/jvm/java-21-openjdk",
                "/usr/lib/jvm/java-25-openjdk",
                "/usr/lib/jvm/temurin-21",
                "/usr/lib/jvm/temurin-25",
                // Ubuntu/Debian
                "/usr/lib/jvm/java-21-openjdk-amd64",
                "/usr/lib/jvm/java-25-openjdk-amd64",
                "/usr/lib/jvm/temurin-21-jdk-amd64",
                "/usr/lib/jvm/temurin-25-jdk-amd64"
            ))

            // Snap packages
            paths.addAll(listOf(
                "/snap/openjdk/current/jdk",
                "/snap/temurin/current"
            ))

            // Flatpak (runtime JDKs)
            paths.add("$userHome/.var/app/org.freedesktop.Sdk.Extension.openjdk")

            // Version managers
            paths.addAll(listOf(
                "$userHome/.sdkman/candidates/java",     // SDKMAN (most popular on Linux)
                "$userHome/.asdf/installs/java",         // asdf
                "$userHome/.jabba/jdk",                  // jabba
                "$userHome/.jenv/versions",              // jenv
                "$userHome/.local/share/mise/installs/java"  // mise (formerly rtx)
            ))

            // User local installations
            paths.addAll(listOf(
                "$userHome/.jdks",                        // IntelliJ managed JDKs
                "$userHome/java",
                "$userHome/.local/java",
                "$userHome/.local/share/java"
            ))

            // IntelliJ's own JBR
            val intellijPath = PathManager.getHomePath()
            if (intellijPath != null) {
                paths.add("$intellijPath/jbr")
            }

            return paths.filter { it.isNotEmpty() }.distinct()
        }
    }

    data class JavaInstallation(
        val version: String,
        val path: Path,
        val isManaged: Boolean,  // true if installed by this plugin
        val source: String = "unknown"  // Where this JDK was found
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
     * Find all Java installations on the system using multiple detection strategies.
     * This is the main entry point for Java detection.
     */
    fun findJavaInstallations(): List<JavaInstallation> {
        val installations = mutableSetOf<JavaInstallation>()

        // Strategy 1: Check JAVA_HOME environment variable (highest priority)
        findJavaFromEnvVar()?.let { installations.add(it) }

        // Strategy 2: Check JDK_HOME environment variable
        findJavaFromJdkHome()?.let { installations.add(it) }

        // Strategy 3: Scan platform-specific standard paths
        installations.addAll(findJavaFromStandardPaths())

        // Strategy 4: Check PATH for java executable and resolve its home
        findJavaFromPath()?.let { installations.add(it) }

        // Strategy 5: Check version manager directories (SDKMAN, asdf, jabba, mise)
        installations.addAll(findJavaFromVersionManagers())

        // Strategy 6: Check managed installations (installed by this plugin)
        installations.addAll(findManagedInstallations())

        // Strategy 7: On Windows, check registry for installed JDKs
        if (isWindows) {
            installations.addAll(findJavaFromWindowsRegistry())
        }

        // Strategy 8: On macOS, use java_home tool
        if (isMac) {
            installations.addAll(findJavaFromMacJavaHome())
        }

        // De-duplicate by normalized path and sort by version (highest first)
        return installations
            .distinctBy { it.path.toAbsolutePath().normalize() }
            .sortedByDescending { getMajorVersion(it.version) }
    }

    /**
     * Find Java from JAVA_HOME environment variable.
     */
    private fun findJavaFromEnvVar(): JavaInstallation? {
        val javaHome = System.getenv("JAVA_HOME") ?: return null
        val path = Paths.get(javaHome)
        if (!Files.exists(path)) return null

        return getJavaVersion(path)?.let { version ->
            JavaInstallation(version, path.toAbsolutePath().normalize(), false, "JAVA_HOME")
        }
    }

    /**
     * Find Java from JDK_HOME environment variable.
     */
    private fun findJavaFromJdkHome(): JavaInstallation? {
        val jdkHome = System.getenv("JDK_HOME") ?: return null
        val path = Paths.get(jdkHome)
        if (!Files.exists(path)) return null

        return getJavaVersion(path)?.let { version ->
            JavaInstallation(version, path.toAbsolutePath().normalize(), false, "JDK_HOME")
        }
    }

    /**
     * Find Java from PATH environment variable.
     */
    private fun findJavaFromPath(): JavaInstallation? {
        return try {
            val command = if (isWindows) listOf("where", "java") else listOf("which", "java")
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode != 0 || output.isEmpty()) return null

            // Get the first result (most relevant)
            val javaExePath = output.lines().firstOrNull { it.isNotBlank() } ?: return null
            val javaExe = Paths.get(javaExePath)

            if (!Files.exists(javaExe)) return null

            // Resolve symlinks and get the actual Java home
            val resolvedPath = try {
                javaExe.toRealPath()
            } catch (e: Exception) {
                javaExe
            }

            // Navigate from bin/java to java home
            val javaHome = resolvedPath.parent?.parent ?: return null

            // Verify it's a valid Java installation
            getJavaVersion(javaHome)?.let { version ->
                JavaInstallation(version, javaHome.toAbsolutePath().normalize(), false, "PATH")
            }
        } catch (e: Exception) {
            LOG.debug("Failed to find Java from PATH: ${e.message}")
            null
        }
    }

    /**
     * Find Java installations from platform-specific standard paths.
     */
    private fun findJavaFromStandardPaths(): List<JavaInstallation> {
        val installations = mutableListOf<JavaInstallation>()

        val searchPaths = when {
            isWindows -> getWindowsSearchPaths()
            isMac -> getMacSearchPaths()
            else -> getLinuxSearchPaths()
        }

        for (basePath in searchPaths) {
            val dir = File(basePath)
            if (!dir.exists()) continue

            // Check if the path itself is a Java installation
            if (isJavaHome(dir.toPath())) {
                getJavaVersion(dir.toPath())?.let { version ->
                    installations.add(JavaInstallation(
                        version,
                        dir.toPath().toAbsolutePath().normalize(),
                        false,
                        "standard"
                    ))
                }
                continue
            }

            // Scan subdirectories
            if (dir.isDirectory) {
                scanDirectoryForJava(dir.toPath(), 2)?.let { installations.addAll(it) }
            }
        }

        return installations
    }

    /**
     * Recursively scan a directory for Java installations.
     * @param maxDepth Maximum depth to scan (to avoid scanning too deeply)
     */
    private fun scanDirectoryForJava(dir: Path, maxDepth: Int): List<JavaInstallation>? {
        if (maxDepth <= 0 || !Files.exists(dir) || !Files.isDirectory(dir)) return null

        val installations = mutableListOf<JavaInstallation>()

        try {
            Files.list(dir).use { stream ->
                stream.forEach { subDir ->
                    if (Files.isDirectory(subDir)) {
                        // Check if this directory is a Java installation
                        val javaHome = resolveJavaHome(subDir)
                        if (javaHome != null) {
                            getJavaVersion(javaHome)?.let { version ->
                                installations.add(JavaInstallation(
                                    version,
                                    javaHome.toAbsolutePath().normalize(),
                                    false,
                                    "standard"
                                ))
                            }
                        } else if (maxDepth > 1) {
                            // Recurse into subdirectory (for version manager structures)
                            scanDirectoryForJava(subDir, maxDepth - 1)?.let {
                                installations.addAll(it)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to scan directory $dir: ${e.message}")
        }

        return installations.ifEmpty { null }
    }

    /**
     * Find Java installations from version managers (SDKMAN, asdf, jabba, mise, jenv).
     */
    private fun findJavaFromVersionManagers(): List<JavaInstallation> {
        val installations = mutableListOf<JavaInstallation>()
        val userHome = System.getProperty("user.home")

        val versionManagerPaths = mapOf(
            "SDKMAN" to "$userHome/.sdkman/candidates/java",
            "asdf" to "$userHome/.asdf/installs/java",
            "jabba" to "$userHome/.jabba/jdk",
            "mise" to "$userHome/.local/share/mise/installs/java",
            "jenv" to "$userHome/.jenv/versions"
        )

        for ((manager, basePath) in versionManagerPaths) {
            val dir = File(basePath)
            if (!dir.exists() || !dir.isDirectory) continue

            dir.listFiles()?.forEach { versionDir ->
                if (versionDir.isDirectory && !versionDir.name.startsWith(".")) {
                    val javaHome = resolveJavaHome(versionDir.toPath())
                    if (javaHome != null) {
                        getJavaVersion(javaHome)?.let { version ->
                            installations.add(JavaInstallation(
                                version,
                                javaHome.toAbsolutePath().normalize(),
                                false,
                                manager
                            ))
                        }
                    }
                }
            }
        }

        return installations
    }

    /**
     * Find managed installations (installed by this plugin).
     */
    private fun findManagedInstallations(): List<JavaInstallation> {
        val installations = mutableListOf<JavaInstallation>()
        val managedDir = getManagedJavaDir()

        if (!Files.exists(managedDir)) return installations

        try {
            Files.list(managedDir).use { stream ->
                stream.forEach { javaDir ->
                    if (Files.isDirectory(javaDir)) {
                        val javaHome = resolveJavaHome(javaDir)
                        if (javaHome != null) {
                            getJavaVersion(javaHome)?.let { version ->
                                installations.add(JavaInstallation(
                                    version,
                                    javaHome.toAbsolutePath().normalize(),
                                    true,
                                    "managed"
                                ))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to scan managed installations: ${e.message}")
        }

        return installations
    }

    /**
     * On Windows, try to find Java installations from the registry.
     */
    private fun findJavaFromWindowsRegistry(): List<JavaInstallation> {
        val installations = mutableListOf<JavaInstallation>()

        // Query registry for Java installations
        val registryKeys = listOf(
            "HKEY_LOCAL_MACHINE\\SOFTWARE\\JavaSoft\\Java Development Kit",
            "HKEY_LOCAL_MACHINE\\SOFTWARE\\JavaSoft\\JDK",
            "HKEY_LOCAL_MACHINE\\SOFTWARE\\Eclipse Adoptium\\JDK",
            "HKEY_LOCAL_MACHINE\\SOFTWARE\\Temurin\\JDK",
            "HKEY_LOCAL_MACHINE\\SOFTWARE\\AdoptOpenJDK\\JDK",
            "HKEY_LOCAL_MACHINE\\SOFTWARE\\Azul Systems\\Zulu"
        )

        for (key in registryKeys) {
            try {
                val process = ProcessBuilder("reg", "query", key)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) continue

                // Parse version keys and query each one for JavaHome
                val versionPattern = """$key\\(\d+[\d.]*)""".toRegex()
                versionPattern.findAll(output).forEach { match ->
                    val versionKey = match.value
                    try {
                        val homeProcess = ProcessBuilder("reg", "query", versionKey, "/v", "JavaHome")
                            .redirectErrorStream(true)
                            .start()

                        val homeOutput = homeProcess.inputStream.bufferedReader().readText()
                        if (homeProcess.waitFor(5, TimeUnit.SECONDS) && homeProcess.exitValue() == 0) {
                            val pathPattern = """JavaHome\s+REG_SZ\s+(.+)""".toRegex()
                            pathPattern.find(homeOutput)?.groupValues?.get(1)?.trim()?.let { javaHomePath ->
                                val path = Paths.get(javaHomePath)
                                if (Files.exists(path)) {
                                    getJavaVersion(path)?.let { version ->
                                        installations.add(JavaInstallation(
                                            version,
                                            path.toAbsolutePath().normalize(),
                                            false,
                                            "registry"
                                        ))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore individual query failures
                    }
                }
            } catch (e: Exception) {
                LOG.debug("Failed to query registry key $key: ${e.message}")
            }
        }

        return installations
    }

    /**
     * On macOS, use /usr/libexec/java_home to find Java installations.
     */
    private fun findJavaFromMacJavaHome(): List<JavaInstallation> {
        val installations = mutableListOf<JavaInstallation>()

        try {
            // List all Java installations using java_home -V
            val process = ProcessBuilder("/usr/libexec/java_home", "-V")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)

            // Parse output for Java home paths
            // Format: "    21.0.1 (arm64) "Eclipse Adoptium" - "OpenJDK 21.0.1" /Library/Java/..."
            val pathPattern = """\s+[\d.]+.*?(/[^\s]+(?:/Contents/Home)?)$""".toRegex(RegexOption.MULTILINE)
            pathPattern.findAll(output).forEach { match ->
                val javaHomePath = match.groupValues[1].trim()
                val path = Paths.get(javaHomePath)
                if (Files.exists(path)) {
                    getJavaVersion(path)?.let { version ->
                        installations.add(JavaInstallation(
                            version,
                            path.toAbsolutePath().normalize(),
                            false,
                            "java_home"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Failed to use /usr/libexec/java_home: ${e.message}")
        }

        return installations
    }

    /**
     * Check if a directory is a Java home directory.
     */
    private fun isJavaHome(dir: Path): Boolean {
        val javaExe = if (isWindows) "bin/java.exe" else "bin/java"
        return Files.exists(dir.resolve(javaExe))
    }

    /**
     * Resolve the actual Java home from a directory that might contain it.
     * Handles macOS Contents/Home structure and nested JDK directories.
     */
    private fun resolveJavaHome(dir: Path): Path? {
        // Direct check
        if (isJavaHome(dir)) return dir

        // macOS: Check Contents/Home
        val contentsHome = dir.resolve("Contents/Home")
        if (isJavaHome(contentsHome)) return contentsHome

        // Adoptium/nested: Check for jdk-* subdirectory
        try {
            Files.list(dir).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { it.fileName.toString().let { name ->
                        name.startsWith("jdk") || name.contains("temurin") ||
                                name.contains("adoptium") || name.contains("openjdk")
                    }}
                    .findFirst()
                    .orElse(null)
                    ?.let { jdkDir ->
                        if (isJavaHome(jdkDir)) return jdkDir
                        // Check Contents/Home inside
                        val nestedContentsHome = jdkDir.resolve("Contents/Home")
                        if (isJavaHome(nestedContentsHome)) return nestedContentsHome
                    }
            }
        } catch (e: Exception) {
            // Ignore scan failures
        }

        return null
    }

    /**
     * Find Java 25+ installation.
     * Returns the best match considering version and source priority.
     */
    fun findJava25(): JavaInstallation? {
        val validInstallations = findJavaInstallations()
            .filter { getMajorVersion(it.version) >= REQUIRED_JAVA_VERSION }

        if (validInstallations.isEmpty()) return null

        // Prioritize by source: managed > JAVA_HOME > PATH > version managers > standard
        val sourcePriority = mapOf(
            "managed" to 0,
            "JAVA_HOME" to 1,
            "JDK_HOME" to 2,
            "PATH" to 3,
            "SDKMAN" to 4,
            "asdf" to 5,
            "jabba" to 6,
            "mise" to 7,
            "java_home" to 8,
            "registry" to 9,
            "standard" to 10,
            "unknown" to 11
        )

        return validInstallations
            .sortedWith(compareBy<JavaInstallation> { sourcePriority[it.source] ?: 11 }
                .thenByDescending { getMajorVersion(it.version) })
            .firstOrNull()
    }

    /**
     * Find all Java installations that meet the version requirement.
     */
    fun findAllJava25Plus(): List<JavaInstallation> {
        return findJavaInstallations()
            .filter { getMajorVersion(it.version) >= REQUIRED_JAVA_VERSION }
    }

    /**
     * Get a summary of all detected Java installations for debugging.
     */
    fun getInstallationsSummary(): String {
        val installations = findJavaInstallations()
        if (installations.isEmpty()) {
            return "No Java installations found"
        }

        val sb = StringBuilder()
        sb.appendLine("Found ${installations.size} Java installation(s):")
        sb.appendLine()

        for ((index, install) in installations.withIndex()) {
            val marker = if (getMajorVersion(install.version) >= REQUIRED_JAVA_VERSION) "[OK]" else "[!]"
            val managedMarker = if (install.isManaged) " (managed)" else ""
            sb.appendLine("${index + 1}. $marker Java ${install.version}$managedMarker")
            sb.appendLine("   Source: ${install.source}")
            sb.appendLine("   Path: ${install.path}")
        }

        val java25 = findJava25()
        sb.appendLine()
        if (java25 != null) {
            sb.appendLine("Best Java 25+ match: Java ${java25.version} from ${java25.source}")
        } else {
            sb.appendLine("No Java 25+ installation found")
        }

        return sb.toString()
    }

    /**
     * Log all detected Java installations for debugging purposes.
     */
    fun logInstallations() {
        LOG.info(getInstallationsSummary())
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
                val jdkDir = Files.list(installDir).use { stream ->
                    stream
                        .filter { Files.isDirectory(it) }
                        .filter { it.fileName.toString().startsWith("jdk") }
                        .findFirst()
                        .orElse(installDir)
                }

                // Verify installation
                val installedVersion = getJavaVersion(jdkDir)
                    ?: throw RuntimeException("Failed to verify Java installation")

                // Refresh VFS to make installed Java files visible to IntelliJ
                LocalFileSystem.getInstance().refreshAndFindFileByPath(jdkDir.toString())

                progressCallback?.accept(DownloadProgress("complete", 100, "Java $version installed successfully!"))

                JavaInstallation(installedVersion, jdkDir, true, "managed")

            } finally {
                Files.deleteIfExists(tempFile)
            }
        }
    }

    /**
     * Get the Java executable path for an installation.
     */
    fun getJavaExecutable(installation: JavaInstallation): Path {
        return getJavaExecutablePath(installation.path)
            ?: throw IllegalStateException("Cannot find java executable in ${installation.path}")
    }

    /**
     * Try to get the Java executable path, returning null if not found.
     */
    fun getJavaExecutableOrNull(installation: JavaInstallation): Path? {
        return getJavaExecutablePath(installation.path)
    }

    /**
     * Check if an installation meets the minimum version requirement.
     */
    fun meetsRequirement(installation: JavaInstallation): Boolean {
        return getMajorVersion(installation.version) >= REQUIRED_JAVA_VERSION
    }

    /**
     * Get Java version by executing java -version.
     * Returns the full version string (e.g., "21.0.1" or "25").
     */
    private fun getJavaVersion(javaHome: Path): String? {
        val javaExe = getJavaExecutablePath(javaHome) ?: return null

        if (!Files.exists(javaExe)) return null

        return try {
            val process = ProcessBuilder(javaExe.toString(), "-version")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(10, TimeUnit.SECONDS)

            if (!completed || process.exitValue() != 0) {
                LOG.debug("java -version failed for $javaHome: exit=${process.exitValue()}")
                return null
            }

            parseJavaVersion(output)
        } catch (e: Exception) {
            LOG.debug("Failed to get Java version from $javaHome: ${e.message}")
            null
        }
    }

    /**
     * Get the path to the java executable for a given Java home.
     */
    private fun getJavaExecutablePath(javaHome: Path): Path? {
        val candidates = mutableListOf<Path>()

        if (isWindows) {
            candidates.add(javaHome.resolve("bin/java.exe"))
        } else {
            // macOS: Check Contents/Home structure
            if (isMac) {
                candidates.add(javaHome.resolve("Contents/Home/bin/java"))
            }
            candidates.add(javaHome.resolve("bin/java"))
        }

        return candidates.firstOrNull { Files.exists(it) }
    }

    /**
     * Parse Java version from java -version output.
     * Handles various formats from different JDK vendors:
     * - openjdk version "21.0.1" 2023-10-17
     * - java version "25"
     * - openjdk version "25-ea" 2025-03-18
     * - Eclipse Temurin version "21.0.5+11"
     * - OpenJDK 64-Bit Server VM (build 21.0.1+12)
     * - GraalVM CE 21.0.1+12.1 (build 21.0.1+12-jvmci-23.1-b19)
     */
    private fun parseJavaVersion(output: String): String? {
        // Pattern priority - most specific first
        val versionPatterns = listOf(
            // Standard format: version "21.0.1" or version "25"
            """version\s+"(\d+(?:\.\d+)*(?:-[^"]+)?)"""".toRegex(),
            // Build format: (build 21.0.1+12)
            """\(build\s+(\d+(?:\.\d+)*)""".toRegex(),
            // GraalVM format: GraalVM CE 21.0.1+12
            """GraalVM\s+\w+\s+(\d+(?:\.\d+)*)""".toRegex(),
            // Fallback: Any version number at the start of a word
            """\b(\d+)(?:\.\d+)*""".toRegex()
        )

        for (pattern in versionPatterns) {
            pattern.find(output)?.groupValues?.get(1)?.let { fullVersion ->
                // Extract just the version number, removing suffixes like -ea, +build
                val cleanVersion = fullVersion.split("-").first().split("+").first()
                if (cleanVersion.isNotEmpty() && cleanVersion[0].isDigit()) {
                    return cleanVersion
                }
            }
        }

        LOG.debug("Failed to parse Java version from output: $output")
        return null
    }

    /**
     * Extract major version number from version string.
     * Handles formats like "21", "21.0.1", "25-ea", etc.
     */
    private fun getMajorVersion(version: String): Int {
        // Remove any suffix after dash or plus
        val cleanVersion = version.split("-").first().split("+").first()

        // Get the first number (major version)
        return cleanVersion.split(".").firstOrNull()?.toIntOrNull() ?: 0
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
