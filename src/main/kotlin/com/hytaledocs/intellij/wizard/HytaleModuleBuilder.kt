package com.hytaledocs.intellij.wizard

import com.hytaledocs.intellij.HytaleIcons
import com.hytaledocs.intellij.settings.HytaleAppSettings
import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import javax.swing.Icon

class HytaleModuleBuilder : ModuleBuilder() {

    enum class TemplateType(val displayName: String, val description: String) {
        EMPTY("Empty Mod", "Minimal mod with just the main class"),
        FULL("Full Template", "Complete mod with commands, listeners, and UI")
    }

    companion object {
        /**
         * Detects Hytale installation, first checking user-configured path, then auto-detecting.
         * @return HytaleInstallation if found, null otherwise
         */
        fun detectHytaleInstallation(): HytaleInstallation? {
            // Priority 0: Check user-configured path in application settings
            try {
                val appSettings = HytaleAppSettings.getInstance()
                if (appSettings.hasCustomInstallationPath()) {
                    val customPath = Paths.get(appSettings.hytaleInstallationPath)
                    val installation = checkInstallationPath(customPath)
                    if (installation != null) {
                        return installation
                    }
                }
            } catch (e: Exception) {
                // Settings might not be available yet (e.g., during startup)
            }

            // Priority 1+: Auto-detect
            return autoDetectHytaleInstallation()
        }

        /**
         * Creates a HytaleInstallation from a custom path.
         * @param customPath The path to check for Hytale installation
         * @return HytaleInstallation if valid, null otherwise
         */
        fun fromCustomPath(customPath: String): HytaleInstallation? {
            if (customPath.isBlank()) return null
            return checkInstallationPath(Paths.get(customPath))
        }

        /**
         * Check if a given path contains a valid Hytale installation.
         */
        private fun checkInstallationPath(basePath: Path): HytaleInstallation? {
            if (!Files.exists(basePath)) return null

            val serverJar = basePath.resolve("Server/HytaleServer.jar")
            val assetsZip = basePath.resolve("Assets.zip")

            val hasServerJar = Files.exists(serverJar)
            val hasAssets = Files.exists(assetsZip)

            if (hasServerJar || hasAssets) {
                return HytaleInstallation(
                    basePath = basePath,
                    serverJarPath = if (hasServerJar) serverJar else null,
                    assetsPath = if (hasAssets) assetsZip else null
                )
            }

            return null
        }

        /**
         * Auto-detects Hytale game installation by checking common locations.
         */
        fun autoDetectHytaleInstallation(): HytaleInstallation? {
            val userHome = System.getProperty("user.home")
            val osName = System.getProperty("os.name").lowercase()
            val isWindows = osName.contains("win")
            val isMac = osName.contains("mac")
            val isLinux = osName.contains("linux") || osName.contains("nix") || osName.contains("nux")

            // Priority 1: Official Hytale Launcher location
            // Structure: .../Hytale/install/release/package/game/latest/
            //   - Assets.zip is in 'latest/'
            //   - HytaleServer.jar is in 'latest/Server/'
            val officialLauncherPaths = mutableListOf<java.nio.file.Path>()

            if (isWindows) {
                // Windows: AppData/Roaming
                officialLauncherPaths.add(Paths.get(userHome, "AppData", "Roaming", "Hytale", "install", "release", "package", "game", "latest"))
            } else if (isMac) {
                // macOS: ~/Library/Application Support/Hytale/...
                officialLauncherPaths.add(Paths.get(userHome, "Library", "Application Support", "Hytale", "install", "release", "package", "game", "latest"))
            } else if (isLinux) {
                // Linux: XDG_DATA_HOME or ~/.local/share
                val xdgDataHome = System.getenv("XDG_DATA_HOME")
                if (xdgDataHome != null && xdgDataHome.isNotEmpty()) {
                    officialLauncherPaths.add(Paths.get(xdgDataHome, "Hytale", "install", "release", "package", "game", "latest"))
                }
                // Default XDG_DATA_HOME location
                officialLauncherPaths.add(Paths.get(userHome, ".local", "share", "Hytale", "install", "release", "package", "game", "latest"))
                // Flatpak installation
                officialLauncherPaths.add(Paths.get(userHome, ".var", "app", "com.hypixel.HytaleLauncher", "data", "Hytale", "install", "release", "package", "game", "latest"))
            }

            // Check Priority 1 paths
            for (launcherPath in officialLauncherPaths) {
                if (Files.exists(launcherPath)) {
                    val serverJar = launcherPath.resolve("Server/HytaleServer.jar")
                    val assetsZip = launcherPath.resolve("Assets.zip")

                    if (Files.exists(serverJar) || Files.exists(assetsZip)) {
                        return HytaleInstallation(
                            basePath = launcherPath,
                            serverJarPath = if (Files.exists(serverJar)) serverJar else null,
                            assetsPath = if (Files.exists(assetsZip)) assetsZip else null
                        )
                    }
                }
            }

            // Priority 2: Check other common installation paths (Windows only)
            if (isWindows) {
                val otherPaths = listOf(
                    // Standard installation paths
                    "C:/Program Files/Hytale",
                    "C:/Program Files (x86)/Hytale",
                    "C:/Hytale",
                    // Epic Games
                    "C:/Program Files/Epic Games/Hytale",
                    // Steam (if applicable)
                    "C:/Program Files (x86)/Steam/steamapps/common/Hytale",
                    // User-specific paths
                    "$userHome/Hytale",
                    "$userHome/Games/Hytale",
                    "$userHome/AppData/Local/Hytale",
                    // Custom drives
                    "D:/Hytale",
                    "D:/Games/Hytale",
                    "E:/Hytale",
                    "E:/Games/Hytale"
                )

                for (basePath in otherPaths) {
                    val path = Paths.get(basePath)
                    if (Files.exists(path)) {
                        // Check for server files in various locations
                        val serverJarLocations = listOf(
                            path.resolve("HytaleServer.jar"),
                            path.resolve("Server/HytaleServer.jar"),
                            path.resolve("server/HytaleServer.jar")
                        )
                        val assetsLocations = listOf(
                            path.resolve("Assets.zip"),
                            path.resolve("assets/Assets.zip")
                        )

                        val serverJar = serverJarLocations.firstOrNull { Files.exists(it) }
                        val assetsZip = assetsLocations.firstOrNull { Files.exists(it) }

                        if (serverJar != null || assetsZip != null) {
                            return HytaleInstallation(
                                basePath = path,
                                serverJarPath = serverJar,
                                assetsPath = assetsZip
                            )
                        }
                    }
                }
            }

            return null
        }
    }

    data class HytaleInstallation(
        val basePath: java.nio.file.Path,
        val serverJarPath: java.nio.file.Path?,
        val assetsPath: java.nio.file.Path?
    ) {
        fun hasServerJar(): Boolean = serverJarPath != null
        fun hasAssets(): Boolean = assetsPath != null
    }

    var modName: String = "My Hytale Mod"
    var modId: String = "my-hytale-mod"
    var packageName: String = "com.example.myhytalemod"
    var commandName: String = "mhm"
    var author: String = System.getProperty("user.name") ?: "Author"
    var modDescription: String = "A Hytale server mod"
    var version: String = "1.0.0"
    var serverPath: String = ""
    var templateType: TemplateType = TemplateType.FULL
    var language: String = "Java"  // "Java" or "Kotlin"
    var buildSystem: String = "Gradle"  // "Gradle" or "Maven"
    var hytaleInstallation: HytaleInstallation? = detectHytaleInstallation()
    var copyFromGame: Boolean = hytaleInstallation != null

    override fun getModuleType(): ModuleType<*> = JavaModuleType.getModuleType()

    override fun getName(): String = "Hytale Mod"

    override fun getDescription(): String = "Create a new Hytale server plugin"

    override fun getNodeIcon(): Icon = HytaleIcons.HYTALE

    override fun getPresentableName(): String = "Hytale Mod"

    override fun getGroupName(): String = "Hytale Docs"

    override fun getBuilderId(): String = "hytale.mod"

    override fun getCustomOptionsStep(context: WizardContext, parentDisposable: Disposable): ModuleWizardStep {
        return HytaleWizardStep(this, context)
    }

    override fun setupRootModel(modifiableRootModel: ModifiableRootModel) {
        val contentEntry = doAddContentEntry(modifiableRootModel) ?: return
        val moduleDir = contentEntry.file ?: return
        createProjectStructure(moduleDir.path)
        LocalFileSystem.getInstance().refresh(true)
    }

    private fun createProjectStructure(basePath: String) {
        WriteAction.run<Throwable> {
            val packagePath = packageName.replace('.', '/')
            val modFolder = modId.replace("-", "").lowercase()
            val srcLang = if (language == "Kotlin") "kotlin" else "java"

            // Create base directories
            val baseDirs = mutableListOf(
                "src/main/$srcLang/$packagePath",
                "src/main/resources",
                "server"
            )

            // Add template-specific directories
            when (templateType) {
                TemplateType.EMPTY -> {
                    // No additional directories needed
                }
                TemplateType.FULL -> {
                    baseDirs.addAll(listOf(
                        "src/main/$srcLang/$packagePath/commands",
                        "src/main/$srcLang/$packagePath/listeners",
                        "src/main/$srcLang/$packagePath/ui",
                        "src/main/resources/Common/UI/Custom/$modFolder"
                    ))
                }
            }

            baseDirs.forEach { FileUtil.createDirectory(File(basePath, it)) }

            // Generate build system files
            when (buildSystem) {
                "Maven" -> {
                    generatePomXml(basePath)
                }
                else -> { // Gradle
                    generateBuildGradle(basePath)
                    generateSettingsGradle(basePath)
                    generateGradleWrapper(basePath)
                }
            }

            generateGitignore(basePath)

            // Generate template-specific files
            when (templateType) {
                TemplateType.EMPTY -> {
                    generateManifestEmpty(basePath)
                    if (language == "Kotlin") {
                        generateMainClassEmptyKotlin(basePath, packagePath)
                    } else {
                        generateMainClassEmpty(basePath, packagePath)
                    }
                }
                TemplateType.FULL -> {
                    generateManifest(basePath)
                    if (language == "Kotlin") {
                        generateMainClassKotlin(basePath, packagePath)
                        generateMainCommandKotlin(basePath, packagePath)
                        generatePlayerListenerKotlin(basePath, packagePath)
                    } else {
                        generateMainClass(basePath, packagePath)
                        generateMainCommand(basePath, packagePath)
                        generateHelpSubCommand(basePath, packagePath)
                        generateInfoSubCommand(basePath, packagePath)
                        generateReloadSubCommand(basePath, packagePath)
                        generateUISubCommand(basePath, packagePath)
                        generateMainUI(basePath, packagePath)
                        generatePlayerListener(basePath, packagePath)
                    }
                    generateUIFile(basePath, modFolder)
                }
            }

            // Create server directory (needed for deployToServer task)
            Files.createDirectories(Paths.get(basePath, "server"))
            // Copy server files from game installation if opted in
            if (copyFromGame) {
                copyServerFiles(basePath)
            }

            // Generate IntelliJ run configurations
            generateRunConfigurations(basePath)
        }
    }

    private fun copyServerFiles(basePath: String) {
        val serverFolderPath = Paths.get(basePath, "server")

        // Priority 1: Copy from Hytale game installation
        if (hytaleInstallation != null) {
            hytaleInstallation?.serverJarPath?.let { jarPath ->
                try {
                    Files.copy(jarPath, serverFolderPath.resolve("HytaleServer.jar"), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    // Ignore copy errors
                }
            }
            hytaleInstallation?.assetsPath?.let { assetsPath ->
                try {
                    Files.copy(assetsPath, serverFolderPath.resolve("Assets.zip"), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    // Ignore copy errors
                }
            }
            return
        }

        // Priority 2: Copy from user-configured server path
        if (serverPath.isNotBlank()) {
            val serverPathDir = Paths.get(serverPath)
            val jarPath = serverPathDir.resolve("HytaleServer.jar")
            val assetsPath = serverPathDir.resolve("Assets.zip")

            if (Files.exists(jarPath)) {
                try {
                    Files.copy(jarPath, serverFolderPath.resolve("HytaleServer.jar"), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) { }
            }
            if (Files.exists(assetsPath)) {
                try {
                    Files.copy(assetsPath, serverFolderPath.resolve("Assets.zip"), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) { }
            }
            return
        }

        // Priority 3: Search common locations
        val searchLocations = listOf(
            Paths.get(basePath, "..", "server"),
            Paths.get(System.getProperty("user.home"), "IdeaProjects", "myplugins", "server"),
            Paths.get(System.getProperty("user.home"), "HytaleServer"),
            Paths.get(System.getProperty("user.home"), "hytale-server"),
            Paths.get(System.getProperty("user.home"), "server"),
            Paths.get(basePath, "..", "..", "server"),
        )

        for (location in searchLocations) {
            try {
                val normalizedPath = location.normalize()
                val jarPath = normalizedPath.resolve("HytaleServer.jar")
                if (Files.exists(jarPath)) {
                    Files.copy(jarPath, serverFolderPath.resolve("HytaleServer.jar"), StandardCopyOption.REPLACE_EXISTING)

                    val assetsPath = normalizedPath.resolve("Assets.zip")
                    if (Files.exists(assetsPath)) {
                        Files.copy(assetsPath, serverFolderPath.resolve("Assets.zip"), StandardCopyOption.REPLACE_EXISTING)
                    }
                    return
                }
            } catch (e: Exception) {
                // Continue searching
            }
        }
    }

    private fun generateGitignore(basePath: String) {
        File(basePath, ".gitignore").writeText("""
            # Gradle
            .gradle/
            build/

            # Maven
            target/
            pom.xml.tag
            pom.xml.releaseBackup
            pom.xml.versionsBackup

            # IDE - ignore most but keep run configurations
            .idea/*
            !.idea/runConfigurations/
            *.iml

            # Hytale
            server/*
        """.trimIndent())
    }

    private fun generateRunConfigurations(basePath: String) {
        // Create .idea/runConfigurations directory
        val runConfigDir = File(basePath, ".idea/runConfigurations")
        FileUtil.createDirectory(runConfigDir)

        if (buildSystem == "Gradle") {
            generateGradleRunConfigs(runConfigDir)
        } else {
            generateMavenRunConfigs(runConfigDir)
        }

        // Generate Run Server configuration (common for both)
        generateRunServerConfig(runConfigDir)
    }

    private fun generateGradleRunConfigs(configDir: File) {
        // Build configuration
        File(configDir, "Build.xml").writeText("""
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="Build" type="GradleRunConfiguration" factoryName="Gradle">
                <ExternalSystemSettings>
                  <option name="executionName" />
                  <option name="externalProjectPath" value="${'$'}PROJECT_DIR${'$'}" />
                  <option name="externalSystemIdString" value="GRADLE" />
                  <option name="scriptParameters" value="" />
                  <option name="taskDescriptions">
                    <list />
                  </option>
                  <option name="taskNames">
                    <list>
                      <option value="build" />
                    </list>
                  </option>
                  <option name="vmOptions" />
                </ExternalSystemSettings>
                <GradleScriptDebugEnabled>true</GradleScriptDebugEnabled>
                <method v="2" />
              </configuration>
            </component>
        """.trimIndent())

        // Clean Build configuration
        File(configDir, "Clean_Build.xml").writeText("""
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="Clean Build" type="GradleRunConfiguration" factoryName="Gradle">
                <ExternalSystemSettings>
                  <option name="executionName" />
                  <option name="externalProjectPath" value="${'$'}PROJECT_DIR${'$'}" />
                  <option name="externalSystemIdString" value="GRADLE" />
                  <option name="scriptParameters" value="" />
                  <option name="taskDescriptions">
                    <list />
                  </option>
                  <option name="taskNames">
                    <list>
                      <option value="clean" />
                      <option value="build" />
                    </list>
                  </option>
                  <option name="vmOptions" />
                </ExternalSystemSettings>
                <GradleScriptDebugEnabled>true</GradleScriptDebugEnabled>
                <method v="2" />
              </configuration>
            </component>
        """.trimIndent())

        // Build & Deploy configuration
        File(configDir, "Build___Deploy.xml").writeText("""
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="Build &amp; Deploy" type="GradleRunConfiguration" factoryName="Gradle">
                <ExternalSystemSettings>
                  <option name="executionName" />
                  <option name="externalProjectPath" value="${'$'}PROJECT_DIR${'$'}" />
                  <option name="externalSystemIdString" value="GRADLE" />
                  <option name="scriptParameters" value="" />
                  <option name="taskDescriptions">
                    <list />
                  </option>
                  <option name="taskNames">
                    <list>
                      <option value="build" />
                      <option value="deployToServer" />
                    </list>
                  </option>
                  <option name="vmOptions" />
                </ExternalSystemSettings>
                <GradleScriptDebugEnabled>true</GradleScriptDebugEnabled>
                <method v="2" />
              </configuration>
            </component>
        """.trimIndent())

        // ShadowJar configuration
        File(configDir, "ShadowJar.xml").writeText("""
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="ShadowJar" type="GradleRunConfiguration" factoryName="Gradle">
                <ExternalSystemSettings>
                  <option name="executionName" />
                  <option name="externalProjectPath" value="${'$'}PROJECT_DIR${'$'}" />
                  <option name="externalSystemIdString" value="GRADLE" />
                  <option name="scriptParameters" value="" />
                  <option name="taskDescriptions">
                    <list />
                  </option>
                  <option name="taskNames">
                    <list>
                      <option value="shadowJar" />
                    </list>
                  </option>
                  <option name="vmOptions" />
                </ExternalSystemSettings>
                <GradleScriptDebugEnabled>true</GradleScriptDebugEnabled>
                <method v="2" />
              </configuration>
            </component>
        """.trimIndent())
    }

    private fun generateMavenRunConfigs(configDir: File) {
        // Build configuration
        File(configDir, "Build.xml").writeText("""
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="Build" type="MavenRunConfiguration" factoryName="Maven">
                <MavenSettings>
                  <option name="myGeneralSettings" />
                  <option name="myRunnerSettings" />
                  <option name="myRunnerParameters">
                    <MavenRunnerParameters>
                      <option name="cmdOptions" value="" />
                      <option name="profiles">
                        <set />
                      </option>
                      <option name="goals">
                        <list>
                          <option value="package" />
                        </list>
                      </option>
                      <option name="pomFileName" value="" />
                      <option name="profilesMap">
                        <map />
                      </option>
                      <option name="resolveToWorkspace" value="false" />
                      <option name="workingDirPath" value="${'$'}PROJECT_DIR${'$'}" />
                    </MavenRunnerParameters>
                  </option>
                </MavenSettings>
                <method v="2" />
              </configuration>
            </component>
        """.trimIndent())

        // Clean Build configuration
        File(configDir, "Clean_Build.xml").writeText("""
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="Clean Build" type="MavenRunConfiguration" factoryName="Maven">
                <MavenSettings>
                  <option name="myGeneralSettings" />
                  <option name="myRunnerSettings" />
                  <option name="myRunnerParameters">
                    <MavenRunnerParameters>
                      <option name="cmdOptions" value="" />
                      <option name="profiles">
                        <set />
                      </option>
                      <option name="goals">
                        <list>
                          <option value="clean" />
                          <option value="package" />
                        </list>
                      </option>
                      <option name="pomFileName" value="" />
                      <option name="profilesMap">
                        <map />
                      </option>
                      <option name="resolveToWorkspace" value="false" />
                      <option name="workingDirPath" value="${'$'}PROJECT_DIR${'$'}" />
                    </MavenRunnerParameters>
                  </option>
                </MavenSettings>
                <method v="2" />
              </configuration>
            </component>
        """.trimIndent())

        // Build & Deploy configuration
        File(configDir, "Build___Deploy.xml").writeText("""
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="Build &amp; Deploy" type="MavenRunConfiguration" factoryName="Maven">
                <MavenSettings>
                  <option name="myGeneralSettings" />
                  <option name="myRunnerSettings" />
                  <option name="myRunnerParameters">
                    <MavenRunnerParameters>
                      <option name="cmdOptions" value="" />
                      <option name="profiles">
                        <set />
                      </option>
                      <option name="goals">
                        <list>
                          <option value="clean" />
                          <option value="package" />
                        </list>
                      </option>
                      <option name="pomFileName" value="" />
                      <option name="profilesMap">
                        <map />
                      </option>
                      <option name="resolveToWorkspace" value="false" />
                      <option name="workingDirPath" value="${'$'}PROJECT_DIR${'$'}" />
                    </MavenRunnerParameters>
                  </option>
                </MavenSettings>
                <method v="2" />
              </configuration>
            </component>
        """.trimIndent())
    }

    private fun generateRunServerConfig(configDir: File) {
        // Generate a .hytale/project.json file with plugin info
        // This will be read by HytaleRunConfigurationSetup at project open
        val hytaleDir = File(configDir.parentFile.parentFile, ".hytale")
        hytaleDir.mkdirs()

        val artifactId = modId.lowercase().replace(" ", "-")
        val group = packageName.substringBeforeLast('.')
        // Plugin name must have no spaces for hot reload to work
        val pluginName = modName.replace(" ", "")

        File(hytaleDir, "project.json").writeText("""
            {
              "groupId": "$group",
              "artifactId": "$artifactId",
              "modName": "$pluginName",
              "version": "$version",
              "buildSystem": "$buildSystem",
              "language": "$language",
              "jarPath": "${if (buildSystem == "Gradle") "build/libs/$artifactId-$version.jar" else "target/$artifactId-$version.jar"}",
              "buildTask": "${if (buildSystem == "Gradle") "shadowJar" else "package"}"
            }
        """.trimIndent())
    }

    private fun generateGradleWrapper(basePath: String) {
        // Create gradle/wrapper directory
        FileUtil.createDirectory(File(basePath, "gradle/wrapper"))

        // gradle-wrapper.properties with Gradle 9.2.1 (supports Java 25)
        File(basePath, "gradle/wrapper/gradle-wrapper.properties").writeText("""
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-9.2.1-bin.zip
            networkTimeout=10000
            validateDistributionUrl=true
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """.trimIndent())

        // Copy gradle-wrapper.jar from resources
        javaClass.getResourceAsStream("/gradle-wrapper/gradle-wrapper.jar")?.use { input ->
            File(basePath, "gradle/wrapper/gradle-wrapper.jar").outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Copy gradlew script (Unix) - ensure LF line endings
        javaClass.getResourceAsStream("/gradle-wrapper/gradlew")?.use { input ->
            val gradlewFile = File(basePath, "gradlew")
            // Read content and ensure Unix line endings (LF only, no CRLF)
            val content = input.bufferedReader().readText().replace("\r\n", "\n").replace("\r", "\n")
            gradlewFile.writeText(content)
            gradlewFile.setExecutable(true)
        }

        // Copy gradlew.bat script (Windows)
        javaClass.getResourceAsStream("/gradle-wrapper/gradlew.bat")?.use { input ->
            File(basePath, "gradlew.bat").outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun generateBuildGradle(basePath: String) {
        val isKotlin = language == "Kotlin"
        val kotlinPlugin = if (isKotlin) "\n    id 'org.jetbrains.kotlin.jvm' version '2.3.0'" else ""
        val kotlinDeps = if (isKotlin) "\n            implementation 'org.jetbrains.kotlin:kotlin-stdlib'" else ""
        val compileTask = if (isKotlin) "compileKotlin" else "compileJava"

        File(basePath, "build.gradle").writeText("""
            plugins {
                id 'java'$kotlinPlugin
                id 'com.gradleup.shadow' version '8.3.0'
            }

            group = '$packageName'
            version = '$version'

            repositories {
                mavenCentral()
                // Official Hytale Maven repository
                maven {
                    name = 'hytale-release'
                    url = 'https://maven.hytale.com/release'
                }
                maven {
                    name = 'hytale-pre-release'
                    url = 'https://maven.hytale.com/pre-release'
                }
            }

            dependencies {
                // Hytale Server API from official Maven repository
                compileOnly 'com.hypixel.hytale:Server:2026.01.24-6e2d4fc36'
                // JSR305 annotations (@Nonnull, @Nullable)
                compileOnly 'com.google.code.findbugs:jsr305:3.0.2'
                implementation 'com.google.code.gson:gson:2.10.1'$kotlinDeps
            }

            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(25)
                }
            }

            shadowJar {
                archiveClassifier.set('')
                // Exclude server classes from the final JAR
                dependencies {
                    exclude(dependency { it.moduleGroup == 'com.hypixel' })
                }
            }

            // Disable the default jar task to avoid conflicts with shadowJar
            tasks.named('jar') {
                enabled = false
            }

            tasks.named('build') {
                dependsOn shadowJar
            }

            // Deploy plugin JAR to server mods folder
            tasks.register('deployToServer', Copy) {
                // Using 'from shadowJar' automatically adds task dependency and proper input tracking
                from shadowJar
                into 'server/mods'
                doLast {
                    println "Deployed to server/mods/"
                }
            }

            // Watch for changes and auto-rebuild (useful during development)
            tasks.register('watch') {
                doLast {
                    println "Watching for changes... Press Ctrl+C to stop."
                    println "Run 'gradle build --continuous' for auto-rebuild on file changes."
                }
            }
        """.trimIndent())
    }

    private fun generateSettingsGradle(basePath: String) {
        File(basePath, "settings.gradle").writeText("rootProject.name = '$modId'")
    }

    private fun generateManifestEmpty(basePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        // Plugin name must have no spaces for hot reload to work
        val pluginName = modName.replace(" ", "")
        val escapedDescription = modDescription.replace("\"", "\\\"").replace("\n", "\\n")
        File(basePath, "src/main/resources/manifest.json").writeText("""
            {
              "Group": "${packageName.substringBeforeLast('.')}",
              "Name": "$pluginName",
              "Version": "$version",
              "Description": "$escapedDescription",
              "Authors": [{"Name": "$author"}],
              "Main": "$packageName.$className",
              "LoadOrder": "POSTWORLD"
            }
        """.trimIndent())
    }

    private fun generateMainClassEmpty(basePath: String, packagePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        File(basePath, "src/main/java/$packagePath/$className.java").writeText("""
            package $packageName;

            import com.hypixel.hytale.server.core.plugin.JavaPlugin;
            import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
            import com.hypixel.hytale.logger.HytaleLogger;

            import javax.annotation.Nonnull;
            import java.util.logging.Level;

            /**
             * ${modName} - A Hytale server plugin.
             *
             * @author $author
             * @version $version
             */
            public class $className extends JavaPlugin {

                private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
                private static $className instance;

                public $className(@Nonnull JavaPluginInit init) {
                    super(init);
                    instance = this;
                }

                public static $className getInstance() {
                    return instance;
                }

                @Override
                protected void setup() {
                    LOGGER.at(Level.INFO).log("[$modName] Setting up...");

                    // TODO: Register commands and listeners here

                    LOGGER.at(Level.INFO).log("[$modName] Setup complete!");
                }

                @Override
                protected void start() {
                    LOGGER.at(Level.INFO).log("[$modName] Started!");
                }

                @Override
                protected void shutdown() {
                    LOGGER.at(Level.INFO).log("[$modName] Shutting down...");
                    instance = null;
                }
            }
        """.trimIndent())
    }

    private fun generateManifest(basePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        // Plugin name must have no spaces for hot reload to work
        val pluginName = modName.replace(" ", "")
        // Escape description for JSON
        val escapedDescription = modDescription.replace("\"", "\\\"").replace("\n", "\\n")
        File(basePath, "src/main/resources/manifest.json").writeText("""
            {
              "Group": "${packageName.substringBeforeLast('.')}",
              "Name": "$pluginName",
              "Version": "$version",
              "Description": "$escapedDescription",
              "Authors": [{"Name": "$author"}],
              "Main": "$packageName.$className",
              "LoadOrder": "POSTWORLD",
              "IncludesAssetPack": true
            }
        """.trimIndent())
    }

    private fun generateMainClass(basePath: String, packagePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        // Command: /$commandName
        File(basePath, "src/main/java/$packagePath/$className.java").writeText("""
            package $packageName;

            import com.hypixel.hytale.server.core.plugin.JavaPlugin;
            import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
            import com.hypixel.hytale.event.EventRegistry;
            import com.hypixel.hytale.logger.HytaleLogger;

            import ${packageName}.commands.${className}Command;
            import ${packageName}.listeners.PlayerListener;

            import javax.annotation.Nonnull;
            import java.util.logging.Level;

            /**
             * ${modName} - A Hytale server plugin.
             */
            public class $className extends JavaPlugin {

                private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
                private static $className instance;

                public $className(@Nonnull JavaPluginInit init) {
                    super(init);
                    instance = this;
                }

                /**
                 * Get the plugin instance.
                 * @return The plugin instance
                 */
                public static $className getInstance() {
                    return instance;
                }

                @Override
                protected void setup() {
                    LOGGER.at(Level.INFO).log("[$modName] Setting up...");

                    // Register commands
                    registerCommands();

                    // Register event listeners
                    registerListeners();

                    LOGGER.at(Level.INFO).log("[$modName] Setup complete!");
                }

                /**
                 * Register plugin commands.
                 */
                private void registerCommands() {
                    try {
                        getCommandRegistry().registerCommand(new ${className}Command());
                        LOGGER.at(Level.INFO).log("[$modName] Registered /$commandName command");
                    } catch (Exception e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("[$modName] Failed to register commands");
                    }
                }

                /**
                 * Register event listeners.
                 */
                private void registerListeners() {
                    EventRegistry eventBus = getEventRegistry();

                    try {
                        new PlayerListener().register(eventBus);
                        LOGGER.at(Level.INFO).log("[$modName] Registered player event listeners");
                    } catch (Exception e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("[$modName] Failed to register listeners");
                    }
                }

                @Override
                protected void start() {
                    LOGGER.at(Level.INFO).log("[$modName] Started!");
                    LOGGER.at(Level.INFO).log("[$modName] Use /$commandName help for commands");
                }

                @Override
                protected void shutdown() {
                    LOGGER.at(Level.INFO).log("[$modName] Shutting down...");
                    instance = null;
                }
            }
        """.trimIndent())
    }

    private fun generateMainCommand(basePath: String, packagePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        // Command: /$commandName
        File(basePath, "src/main/java/$packagePath/commands/${className}Command.java").writeText("""
            package ${packageName}.commands;

            import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

            /**
             * Main command for $modName plugin.
             *
             * Usage:
             * - /$commandName help - Show available commands
             * - /$commandName info - Show plugin information
             * - /$commandName reload - Reload plugin configuration
             * - /$commandName ui - Open the plugin dashboard
             */
            public class ${className}Command extends AbstractCommandCollection {

                public ${className}Command() {
                    super("$commandName", "$modName plugin commands");

                    // Add subcommands
                    this.addSubCommand(new HelpSubCommand());
                    this.addSubCommand(new InfoSubCommand());
                    this.addSubCommand(new ReloadSubCommand());
                    this.addSubCommand(new UISubCommand());
                }

                @Override
                protected boolean canGeneratePermission() {
                    return false; // No permission required for base command
                }
            }
        """.trimIndent())
    }

    private fun generateHelpSubCommand(basePath: String, packagePath: String) {
        // Command: /$commandName
        File(basePath, "src/main/java/$packagePath/commands/HelpSubCommand.java").writeText("""
            package ${packageName}.commands;

            import com.hypixel.hytale.server.core.Message;
            import com.hypixel.hytale.server.core.command.system.CommandContext;
            import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

            import javax.annotation.Nonnull;

            /**
             * /$commandName help - Show available commands
             */
            public class HelpSubCommand extends CommandBase {

                public HelpSubCommand() {
                    super("help", "Show available commands");
                    this.setPermissionGroup(null);
                }

                @Override
                protected boolean canGeneratePermission() {
                    return false;
                }

                @Override
                protected void executeSync(@Nonnull CommandContext context) {
                    context.sendMessage(Message.raw(""));
                    context.sendMessage(Message.raw("=== $modName Commands ==="));
                    context.sendMessage(Message.raw("/$commandName help - Show this help message"));
                    context.sendMessage(Message.raw("/$commandName info - Show plugin information"));
                    context.sendMessage(Message.raw("/$commandName reload - Reload configuration"));
                    context.sendMessage(Message.raw("/$commandName ui - Open the dashboard UI"));
                    context.sendMessage(Message.raw("========================"));
                }
            }
        """.trimIndent())
    }

    private fun generateInfoSubCommand(basePath: String, packagePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        // Command: /$commandName
        File(basePath, "src/main/java/$packagePath/commands/InfoSubCommand.java").writeText("""
            package ${packageName}.commands;

            import com.hypixel.hytale.server.core.Message;
            import com.hypixel.hytale.server.core.command.system.CommandContext;
            import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

            import ${packageName}.${className};

            import javax.annotation.Nonnull;

            /**
             * /$commandName info - Show plugin information
             */
            public class InfoSubCommand extends CommandBase {

                public InfoSubCommand() {
                    super("info", "Show plugin information");
                    this.setPermissionGroup(null);
                }

                @Override
                protected boolean canGeneratePermission() {
                    return false;
                }

                @Override
                protected void executeSync(@Nonnull CommandContext context) {
                    ${className} plugin = ${className}.getInstance();

                    context.sendMessage(Message.raw(""));
                    context.sendMessage(Message.raw("=== $modName Info ==="));
                    context.sendMessage(Message.raw("Name: $modName"));
                    context.sendMessage(Message.raw("Version: $version"));
                    context.sendMessage(Message.raw("Author: $author"));
                    context.sendMessage(Message.raw("Status: " + (plugin != null ? "Running" : "Not loaded")));
                    context.sendMessage(Message.raw("===================="));
                }
            }
        """.trimIndent())
    }

    private fun generateReloadSubCommand(basePath: String, packagePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        // Command: /$commandName
        File(basePath, "src/main/java/$packagePath/commands/ReloadSubCommand.java").writeText("""
            package ${packageName}.commands;

            import com.hypixel.hytale.server.core.Message;
            import com.hypixel.hytale.server.core.command.system.CommandContext;
            import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

            import ${packageName}.${className};

            import javax.annotation.Nonnull;

            /**
             * /$commandName reload - Reload plugin configuration
             */
            public class ReloadSubCommand extends CommandBase {

                public ReloadSubCommand() {
                    super("reload", "Reload plugin configuration");
                    this.setPermissionGroup(null);
                }

                @Override
                protected boolean canGeneratePermission() {
                    return false;
                }

                @Override
                protected void executeSync(@Nonnull CommandContext context) {
                    ${className} plugin = ${className}.getInstance();

                    if (plugin == null) {
                        context.sendMessage(Message.raw("Error: Plugin not loaded"));
                        return;
                    }

                    context.sendMessage(Message.raw("Reloading $modName..."));

                    // TODO: Add your reload logic here
                    // Example: Reload config files, refresh caches, etc.

                    context.sendMessage(Message.raw("$modName reloaded successfully!"));
                }
            }
        """.trimIndent())
    }

    private fun generateMainUI(basePath: String, packagePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        val uiClassName = modName.replace(" ", "") + "DashboardUI"
        val modFolder = modId.replace("-", "").lowercase()
        File(basePath, "src/main/java/$packagePath/ui/${uiClassName}.java").writeText("""
            package ${packageName}.ui;

            import com.hypixel.hytale.codec.Codec;
            import com.hypixel.hytale.codec.KeyedCodec;
            import com.hypixel.hytale.codec.builder.BuilderCodec;
            import com.hypixel.hytale.component.Ref;
            import com.hypixel.hytale.component.Store;
            import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
            import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
            import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
            import com.hypixel.hytale.server.core.Message;
            import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
            import com.hypixel.hytale.server.core.ui.builder.EventData;
            import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
            import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
            import com.hypixel.hytale.server.core.universe.PlayerRef;
            import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
            import com.hypixel.hytale.server.core.util.NotificationUtil;

            import javax.annotation.Nonnull;

            /**
             * ${modName} Dashboard UI
             *
             * A simple interactive dashboard page showing plugin information
             * with refresh and close buttons.
             */
            public class ${uiClassName} extends InteractiveCustomUIPage<${uiClassName}.UIEventData> {

                // Path relative to Common/UI/Custom/
                public static final String LAYOUT = "$modFolder/Dashboard.ui";

                private final PlayerRef playerRef;
                private int refreshCount = 0;

                public ${uiClassName}(@Nonnull PlayerRef playerRef) {
                    super(playerRef, CustomPageLifetime.CanDismiss, UIEventData.CODEC);
                    this.playerRef = playerRef;
                }

                @Override
                public void build(
                        @Nonnull Ref<EntityStore> ref,
                        @Nonnull UICommandBuilder cmd,
                        @Nonnull UIEventBuilder evt,
                        @Nonnull Store<EntityStore> store
                ) {
                    // Load base layout
                    cmd.append(LAYOUT);

                    // Bind refresh button
                    evt.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        "#RefreshButton",
                        new EventData().append("Action", "refresh"),
                        false
                    );

                    // Bind close button
                    evt.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        "#CloseButton",
                        new EventData().append("Action", "close"),
                        false
                    );
                }

                @Override
                public void handleDataEvent(
                        @Nonnull Ref<EntityStore> ref,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull UIEventData data
                ) {
                    if (data.action == null) return;

                    switch (data.action) {
                        case "refresh":
                            refreshCount++;
                            UICommandBuilder cmd = new UICommandBuilder();
                            cmd.set("#StatusText.Text", "Refreshed " + refreshCount + " time(s)!");
                            this.sendUpdate(cmd, false);

                            NotificationUtil.sendNotification(
                                playerRef.getPacketHandler(),
                                Message.raw("${modName}"),
                                Message.raw("Dashboard refreshed!"),
                                NotificationStyle.Success
                            );
                            break;

                        case "close":
                            this.close();
                            break;
                    }
                }

                /**
                 * Event data class with codec for handling UI events.
                 */
                public static class UIEventData {
                    public static final BuilderCodec<UIEventData> CODEC = BuilderCodec.builder(
                            UIEventData.class, UIEventData::new
                    )
                    .append(new KeyedCodec<>("Action", Codec.STRING), (e, v) -> e.action = v, e -> e.action)
                    .add()
                    .build();

                    private String action;

                    public UIEventData() {}
                }
            }
        """.trimIndent())
    }

    private fun generateUIFile(basePath: String, modFolder: String) {
        File(basePath, "src/main/resources/Common/UI/Custom/$modFolder/Dashboard.ui").writeText("""
            // ${modName} Dashboard UI
            // Import Common.ui for reusable components
            ${"$"}C = "../Common.ui";

            // Custom button styles
            @PrimaryButton = TextButtonStyle(
              Default: (Background: #3a7bd5, LabelStyle: (FontSize: 14, TextColor: #ffffff, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center)),
              Hovered: (Background: #4a8be5, LabelStyle: (FontSize: 14, TextColor: #ffffff, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center)),
              Pressed: (Background: #2a6bc5, LabelStyle: (FontSize: 14, TextColor: #ffffff, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center))
            );

            @SecondaryButton = TextButtonStyle(
              Default: (Background: #2b3542, LabelStyle: (FontSize: 14, TextColor: #96a9be, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center)),
              Hovered: (Background: #3b4552, LabelStyle: (FontSize: 14, TextColor: #b6c9de, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center)),
              Pressed: (Background: #1b2532, LabelStyle: (FontSize: 14, TextColor: #96a9be, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center))
            );

            // Main container
            Group {
              Anchor: (Width: 400, Height: 280);
              Background: #141c26(0.98);
              LayoutMode: Top;
              Padding: (Full: 20);

              // Title
              Label {
                Text: "${modName} Dashboard";
                Anchor: (Height: 40);
                Style: (FontSize: 24, TextColor: #ffffff, HorizontalAlignment: Center, RenderBold: true);
              }

              // Separator
              Group { Anchor: (Height: 1); Background: #2b3542; }
              Group { Anchor: (Height: 16); }

              // Status text
              Label #StatusText {
                Text: "Welcome to ${modName}!";
                Anchor: (Height: 30);
                Style: (FontSize: 16, TextColor: #96a9be, HorizontalAlignment: Center);
              }

              Group { Anchor: (Height: 8); }

              // Info section
              Label {
                Text: "Version: $version | By $author";
                Anchor: (Height: 24);
                Style: (FontSize: 14, TextColor: #6e7da1, HorizontalAlignment: Center);
              }

              // Spacer
              Group { FlexWeight: 1; }

              // Buttons row
              Group {
                LayoutMode: Center;
                Anchor: (Height: 50);

                TextButton #RefreshButton {
                  Text: "Refresh";
                  Anchor: (Width: 100, Height: 44);
                  Style: @PrimaryButton;
                }

                Group { Anchor: (Width: 16); }

                TextButton #CloseButton {
                  Text: "Close";
                  Anchor: (Width: 100, Height: 44);
                  Style: @SecondaryButton;
                }
              }

              // Footer
              Group { Anchor: (Height: 8); }
              Label {
                Text: "Press ESC to close";
                Anchor: (Height: 16);
                Style: (FontSize: 11, TextColor: #555555, HorizontalAlignment: Center);
              }
            }
        """.trimIndent())
    }

    private fun generateUISubCommand(basePath: String, packagePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        val uiClassName = modName.replace(" ", "") + "DashboardUI"
        // Command: /$commandName
        File(basePath, "src/main/java/$packagePath/commands/UISubCommand.java").writeText("""
            package ${packageName}.commands;

            import com.hypixel.hytale.component.Ref;
            import com.hypixel.hytale.component.Store;
            import com.hypixel.hytale.server.core.Message;
            import com.hypixel.hytale.server.core.command.system.CommandContext;
            import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
            import com.hypixel.hytale.server.core.entity.entities.Player;
            import com.hypixel.hytale.server.core.universe.PlayerRef;
            import com.hypixel.hytale.server.core.universe.world.World;
            import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

            import ${packageName}.ui.${uiClassName};

            import javax.annotation.Nonnull;

            /**
             * /$commandName ui - Open the plugin dashboard UI
             *
             * Extends AbstractPlayerCommand to ensure proper thread handling
             * when opening custom UI pages.
             */
            public class UISubCommand extends AbstractPlayerCommand {

                public UISubCommand() {
                    super("ui", "Open the plugin dashboard");
                    this.addAliases(new String[]{"dashboard", "gui"});
                    this.setPermissionGroup(null);
                }

                @Override
                protected boolean canGeneratePermission() {
                    return false;
                }

                /**
                 * Called on the world thread with proper player context.
                 */
                @Override
                protected void execute(
                        @Nonnull CommandContext context,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull Ref<EntityStore> ref,
                        @Nonnull PlayerRef playerRef,
                        @Nonnull World world
                ) {
                    context.sendMessage(Message.raw("Opening ${modName} Dashboard..."));

                    try {
                        // Get the player component (safe - we're on world thread)
                        Player player = store.getComponent(ref, Player.getComponentType());
                        if (player == null) {
                            context.sendMessage(Message.raw("Error: Could not get Player component."));
                            return;
                        }

                        // Create and open the custom page
                        ${uiClassName} dashboardPage = new ${uiClassName}(playerRef);
                        player.getPageManager().openCustomPage(ref, store, dashboardPage);
                        context.sendMessage(Message.raw("Dashboard opened. Press ESC to close."));
                    } catch (Exception e) {
                        context.sendMessage(Message.raw("Error opening dashboard: " + e.getMessage()));
                    }
                }
            }
        """.trimIndent())
    }

    private fun generatePlayerListener(basePath: String, packagePath: String) {
        File(basePath, "src/main/java/$packagePath/listeners/PlayerListener.java").writeText("""
            package ${packageName}.listeners;

            import com.hypixel.hytale.event.EventRegistry;
            import com.hypixel.hytale.logger.HytaleLogger;
            import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
            import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;

            import java.util.logging.Level;

            /**
             * Listener for player connection events.
             *
             * Listens to:
             * - PlayerConnectEvent - When a player connects to the server
             * - PlayerDisconnectEvent - When a player disconnects from the server
             */
            public class PlayerListener {

                private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

                /**
                 * Register all player event listeners.
                 * @param eventBus The event registry to register listeners with
                 */
                public void register(EventRegistry eventBus) {
                    // PlayerConnectEvent - When a player connects
                    try {
                        eventBus.register(PlayerConnectEvent.class, this::onPlayerConnect);
                        LOGGER.at(Level.INFO).log("[$modName] Registered PlayerConnectEvent listener");
                    } catch (Exception e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("[$modName] Failed to register PlayerConnectEvent");
                    }

                    // PlayerDisconnectEvent - When a player disconnects
                    try {
                        eventBus.register(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
                        LOGGER.at(Level.INFO).log("[$modName] Registered PlayerDisconnectEvent listener");
                    } catch (Exception e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("[$modName] Failed to register PlayerDisconnectEvent");
                    }
                }

                /**
                 * Handle player connect event.
                 * @param event The player connect event
                 */
                private void onPlayerConnect(PlayerConnectEvent event) {
                    String playerName = event.getPlayerRef() != null ? event.getPlayerRef().getUsername() : "Unknown";
                    String worldName = event.getWorld() != null ? event.getWorld().getName() : "unknown";

                    LOGGER.at(Level.INFO).log("[$modName] Player %s connected to world %s", playerName, worldName);

                    // TODO: Add your player join logic here
                    // Examples:
                    // - Send welcome message
                    // - Load player data
                    // - Announce join to other players
                }

                /**
                 * Handle player disconnect event.
                 * @param event The player disconnect event
                 */
                private void onPlayerDisconnect(PlayerDisconnectEvent event) {
                    String playerName = event.getPlayerRef() != null ? event.getPlayerRef().getUsername() : "Unknown";

                    LOGGER.at(Level.INFO).log("[$modName] Player %s disconnected", playerName);

                    // TODO: Add your player leave logic here
                    // Examples:
                    // - Save player data
                    // - Announce leave to other players
                    // - Clean up player resources
                }
            }
        """.trimIndent())
    }

    private fun generatePomXml(basePath: String) {
        val kotlinDeps = if (language == "Kotlin") """
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-stdlib</artifactId>
            <version>${'$'}{kotlin.version}</version>
        </dependency>
        """ else ""

        val kotlinProps = if (language == "Kotlin") """
        <kotlin.version>2.1.0</kotlin.version>
        """ else ""

        val kotlinBuild = if (language == "Kotlin") """
            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
                <version>${'$'}{kotlin.version}</version>
                <executions>
                    <execution>
                        <id>compile</id>
                        <goals>
                            <goal>compile</goal>
                        </goals>
                        <configuration>
                            <sourceDirs>
                                <sourceDir>${'$'}{project.basedir}/src/main/kotlin</sourceDir>
                            </sourceDirs>
                        </configuration>
                    </execution>
                </executions>
            </plugin>""" else ""

        File(basePath, "pom.xml").writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>

                <groupId>$packageName</groupId>
                <artifactId>$modId</artifactId>
                <version>$version</version>
                <packaging>jar</packaging>

                <name>$modName</name>
                <description>$modDescription</description>

                <properties>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    <maven.compiler.source>25</maven.compiler.source>
                    <maven.compiler.target>25</maven.compiler.target>
                    $kotlinProps
                </properties>

                <repositories>
                    <repository>
                        <id>hytale-release</id>
                        <url>https://maven.hytale.com/release</url>
                    </repository>
                    <repository>
                        <id>hytale-pre-release</id>
                        <url>https://maven.hytale.com/pre-release</url>
                    </repository>
                </repositories>

                <dependencies>
                    <!-- HytaleServer API (from official Maven repository) -->
                    <dependency>
                        <groupId>com.hypixel.hytale</groupId>
                        <artifactId>Server</artifactId>
                        <version>2026.01.24-6e2d4fc36</version>
                        <scope>provided</scope>
                    </dependency>

                    <!-- JSR305 annotations -->
                    <dependency>
                        <groupId>com.google.code.findbugs</groupId>
                        <artifactId>jsr305</artifactId>
                        <version>3.0.2</version>
                        <scope>provided</scope>
                    </dependency>

                    <!-- Gson -->
                    <dependency>
                        <groupId>com.google.code.gson</groupId>
                        <artifactId>gson</artifactId>
                        <version>2.10.1</version>
                    </dependency>
                    $kotlinDeps
                </dependencies>

                <build>
                    <plugins>
                        $kotlinBuild
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <version>3.13.0</version>
                            <configuration>
                                <release>25</release>
                                <compilerArgs>
                                    <arg>--enable-preview</arg>
                                </compilerArgs>
                            </configuration>
                        </plugin>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-shade-plugin</artifactId>
                            <version>3.6.0</version>
                            <executions>
                                <execution>
                                    <phase>package</phase>
                                    <goals>
                                        <goal>shade</goal>
                                    </goals>
                                    <configuration>
                                        <createDependencyReducedPom>false</createDependencyReducedPom>
                                        <artifactSet>
                                            <excludes>
                                                <exclude>com.hypixel.hytale:*</exclude>
                                            </excludes>
                                        </artifactSet>
                                    </configuration>
                                </execution>
                            </executions>
                        </plugin>
                    </plugins>
                </build>
            </project>
        """.trimIndent())
    }

    private fun generateMainClassEmptyKotlin(basePath: String, packagePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        File(basePath, "src/main/kotlin/$packagePath/$className.kt").writeText("""
            package $packageName

            import com.hypixel.hytale.server.core.plugin.JavaPlugin
            import com.hypixel.hytale.server.core.plugin.JavaPluginInit
            import com.hypixel.hytale.logger.HytaleLogger
            import java.util.logging.Level

            /**
             * $modName - A Hytale server plugin.
             *
             * @author $author
             * @version $version
             */
            class $className(init: JavaPluginInit) : JavaPlugin(init) {

                companion object {
                    private val LOGGER = HytaleLogger.forEnclosingClass()

                    @JvmStatic
                    var instance: $className? = null
                        private set
                }

                init {
                    instance = this
                }

                override fun setup() {
                    LOGGER.at(Level.INFO).log("[$modName] Setting up...")

                    // TODO: Register commands and listeners here

                    LOGGER.at(Level.INFO).log("[$modName] Setup complete!")
                }

                override fun start() {
                    LOGGER.at(Level.INFO).log("[$modName] Started!")
                }

                override fun shutdown() {
                    LOGGER.at(Level.INFO).log("[$modName] Shutting down...")
                    instance = null
                }
            }
        """.trimIndent())
    }

    private fun generateMainClassKotlin(basePath: String, packagePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        File(basePath, "src/main/kotlin/$packagePath/$className.kt").writeText("""
            package $packageName

            import com.hypixel.hytale.server.core.plugin.JavaPlugin
            import com.hypixel.hytale.server.core.plugin.JavaPluginInit
            import com.hypixel.hytale.event.EventRegistry
            import com.hypixel.hytale.logger.HytaleLogger
            import ${packageName}.commands.${className}Command
            import ${packageName}.listeners.PlayerListener
            import java.util.logging.Level

            /**
             * $modName - A Hytale server plugin.
             */
            class $className(init: JavaPluginInit) : JavaPlugin(init) {

                companion object {
                    private val LOGGER = HytaleLogger.forEnclosingClass()

                    @JvmStatic
                    var instance: $className? = null
                        private set
                }

                init {
                    instance = this
                }

                override fun setup() {
                    LOGGER.at(Level.INFO).log("[$modName] Setting up...")

                    // Register commands
                    registerCommands()

                    // Register event listeners
                    registerListeners()

                    LOGGER.at(Level.INFO).log("[$modName] Setup complete!")
                }

                private fun registerCommands() {
                    try {
                        commandRegistry.registerCommand(${className}Command())
                        LOGGER.at(Level.INFO).log("[$modName] Registered /$commandName command")
                    } catch (e: Exception) {
                        LOGGER.at(Level.WARNING).withCause(e).log("[$modName] Failed to register commands")
                    }
                }

                private fun registerListeners() {
                    val eventBus: EventRegistry = eventRegistry

                    try {
                        PlayerListener().register(eventBus)
                        LOGGER.at(Level.INFO).log("[$modName] Registered player event listeners")
                    } catch (e: Exception) {
                        LOGGER.at(Level.WARNING).withCause(e).log("[$modName] Failed to register listeners")
                    }
                }

                override fun start() {
                    LOGGER.at(Level.INFO).log("[$modName] Started!")
                    LOGGER.at(Level.INFO).log("[$modName] Use /$commandName help for commands")
                }

                override fun shutdown() {
                    LOGGER.at(Level.INFO).log("[$modName] Shutting down...")
                    instance = null
                }
            }
        """.trimIndent())
    }

    private fun generateMainCommandKotlin(basePath: String, packagePath: String) {
        val className = modName.replace(" ", "") + "Plugin"
        File(basePath, "src/main/kotlin/$packagePath/commands/${className}Command.kt").writeText("""
            package ${packageName}.commands

            import com.hypixel.hytale.server.core.Message
            import com.hypixel.hytale.server.core.command.system.CommandContext
            import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection
            import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase

            /**
             * Main command for $modName plugin.
             *
             * Usage:
             * - /$commandName help - Show available commands
             * - /$commandName info - Show plugin information
             * - /$commandName reload - Reload plugin configuration
             */
            class ${className}Command : AbstractCommandCollection("$commandName", "$modName plugin commands") {

                init {
                    addSubCommand(HelpSubCommand())
                    addSubCommand(InfoSubCommand())
                    addSubCommand(ReloadSubCommand())
                }

                override fun canGeneratePermission(): Boolean = false
            }

            /**
             * /$commandName help - Show available commands
             */
            class HelpSubCommand : CommandBase("help", "Show available commands") {

                init {
                    setPermissionGroup(null)
                }

                override fun canGeneratePermission(): Boolean = false

                override fun executeSync(context: CommandContext) {
                    context.sendMessage(Message.raw(""))
                    context.sendMessage(Message.raw("=== $modName Commands ==="))
                    context.sendMessage(Message.raw("/$commandName help - Show this help message"))
                    context.sendMessage(Message.raw("/$commandName info - Show plugin information"))
                    context.sendMessage(Message.raw("/$commandName reload - Reload configuration"))
                    context.sendMessage(Message.raw("========================"))
                }
            }

            /**
             * /$commandName info - Show plugin information
             */
            class InfoSubCommand : CommandBase("info", "Show plugin information") {

                init {
                    setPermissionGroup(null)
                }

                override fun canGeneratePermission(): Boolean = false

                override fun executeSync(context: CommandContext) {
                    val plugin = ${packageName}.${className}.instance

                    context.sendMessage(Message.raw(""))
                    context.sendMessage(Message.raw("=== $modName Info ==="))
                    context.sendMessage(Message.raw("Name: $modName"))
                    context.sendMessage(Message.raw("Version: $version"))
                    context.sendMessage(Message.raw("Author: $author"))
                    context.sendMessage(Message.raw("Status: " + if (plugin != null) "Running" else "Not loaded"))
                    context.sendMessage(Message.raw("===================="))
                }
            }

            /**
             * /$commandName reload - Reload plugin configuration
             */
            class ReloadSubCommand : CommandBase("reload", "Reload plugin configuration") {

                init {
                    setPermissionGroup(null)
                }

                override fun canGeneratePermission(): Boolean = false

                override fun executeSync(context: CommandContext) {
                    val plugin = ${packageName}.${className}.instance

                    if (plugin == null) {
                        context.sendMessage(Message.raw("Error: Plugin not loaded"))
                        return
                    }

                    context.sendMessage(Message.raw("Reloading $modName..."))

                    // TODO: Add your reload logic here

                    context.sendMessage(Message.raw("$modName reloaded successfully!"))
                }
            }
        """.trimIndent())
    }

    private fun generatePlayerListenerKotlin(basePath: String, packagePath: String) {
        File(basePath, "src/main/kotlin/$packagePath/listeners/PlayerListener.kt").writeText("""
            package ${packageName}.listeners

            import com.hypixel.hytale.event.EventRegistry
            import com.hypixel.hytale.logger.HytaleLogger
            import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent
            import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent
            import java.util.logging.Level

            /**
             * Listener for player connection events.
             */
            class PlayerListener {

                companion object {
                    private val LOGGER = HytaleLogger.forEnclosingClass()
                }

                /**
                 * Register all player event listeners.
                 */
                fun register(eventBus: EventRegistry) {
                    // PlayerConnectEvent
                    try {
                        eventBus.register(PlayerConnectEvent::class.java, ::onPlayerConnect)
                        LOGGER.at(Level.INFO).log("[$modName] Registered PlayerConnectEvent listener")
                    } catch (e: Exception) {
                        LOGGER.at(Level.WARNING).withCause(e).log("[$modName] Failed to register PlayerConnectEvent")
                    }

                    // PlayerDisconnectEvent
                    try {
                        eventBus.register(PlayerDisconnectEvent::class.java, ::onPlayerDisconnect)
                        LOGGER.at(Level.INFO).log("[$modName] Registered PlayerDisconnectEvent listener")
                    } catch (e: Exception) {
                        LOGGER.at(Level.WARNING).withCause(e).log("[$modName] Failed to register PlayerDisconnectEvent")
                    }
                }

                private fun onPlayerConnect(event: PlayerConnectEvent) {
                    val playerName = event.playerRef?.username ?: "Unknown"
                    val worldName = event.world?.name ?: "unknown"

                    LOGGER.at(Level.INFO).log("[$modName] Player %s connected to world %s", playerName, worldName)

                    // TODO: Add your player join logic here
                }

                private fun onPlayerDisconnect(event: PlayerDisconnectEvent) {
                    val playerName = event.playerRef?.username ?: "Unknown"

                    LOGGER.at(Level.INFO).log("[$modName] Player %s disconnected", playerName)

                    // TODO: Add your player leave logic here
                }
            }
        """.trimIndent())
    }

    /**
     * Create project structure at a given path.
     * Used by NewProjectWizard API.
     */
    fun createProjectAtPath(basePath: String) {
        createProjectStructure(basePath)
        LocalFileSystem.getInstance().refresh(true)
    }
}
