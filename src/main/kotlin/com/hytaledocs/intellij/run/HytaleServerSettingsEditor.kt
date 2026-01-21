package com.hytaledocs.intellij.run

import com.hytaledocs.intellij.services.JavaInstallService
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import java.io.File
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Settings editor for Hytale Server run configuration.
 * Provides UI for configuring build, deploy, and server settings.
 * All values are read/written directly from UI components to avoid binding issues.
 */
class HytaleServerSettingsEditor(private val project: Project) : SettingsEditor<HytaleServerRunConfiguration>() {

    // UI component references
    private var buildBeforeRunCheck: JBCheckBox? = null
    private var buildTaskCombo: ComboBox<String>? = null
    private var deployPluginCheck: JBCheckBox? = null
    private var pluginJarField: TextFieldWithBrowseButton? = null
    private var pluginNameField: JTextField? = null
    private var hotReloadCheck: JBCheckBox? = null
    private var serverPathField: TextFieldWithBrowseButton? = null
    private var javaPathField: TextFieldWithBrowseButton? = null
    private var portSpinner: JSpinner? = null
    private var minMemoryCombo: ComboBox<String>? = null
    private var maxMemoryCombo: ComboBox<String>? = null
    private var authModeCombo: ComboBox<String>? = null
    private var allowOpCheck: JBCheckBox? = null
    private var acceptEarlyPluginsCheck: JBCheckBox? = null
    private var jvmArgsField: JTextField? = null
    private var serverArgsField: JTextField? = null

    // Cache the editor panel
    private var editorPanel: JComponent? = null

    override fun resetEditorFrom(config: HytaleServerRunConfiguration) {
        // Load all values from config to UI
        buildBeforeRunCheck?.isSelected = config.buildBeforeRun
        buildTaskCombo?.selectedItem = config.buildTask
        deployPluginCheck?.isSelected = config.deployPlugin
        pluginJarField?.text = config.pluginJarPath
        pluginNameField?.text = config.pluginName
        hotReloadCheck?.isSelected = config.hotReloadEnabled
        serverPathField?.text = config.serverPath
        javaPathField?.text = config.javaPath
        portSpinner?.value = config.port
        minMemoryCombo?.selectedItem = config.minMemory
        maxMemoryCombo?.selectedItem = config.maxMemory
        authModeCombo?.selectedItem = config.authMode
        allowOpCheck?.isSelected = config.allowOp
        acceptEarlyPluginsCheck?.isSelected = config.acceptEarlyPlugins
        jvmArgsField?.text = config.jvmArgs
        serverArgsField?.text = config.serverArgs
    }

    override fun applyEditorTo(config: HytaleServerRunConfiguration) {
        // Save all values from UI to config
        buildBeforeRunCheck?.let { config.buildBeforeRun = it.isSelected }
        buildTaskCombo?.let { config.buildTask = it.selectedItem as? String ?: "shadowJar" }
        deployPluginCheck?.let { config.deployPlugin = it.isSelected }
        pluginJarField?.let { config.pluginJarPath = it.text }
        pluginNameField?.let { config.pluginName = it.text }
        hotReloadCheck?.let { config.hotReloadEnabled = it.isSelected }
        serverPathField?.let { config.serverPath = it.text }
        javaPathField?.let { config.javaPath = it.text }
        portSpinner?.let { config.port = it.value as Int }
        minMemoryCombo?.let { config.minMemory = it.selectedItem as? String ?: "2G" }
        maxMemoryCombo?.let { config.maxMemory = it.selectedItem as? String ?: "8G" }
        authModeCombo?.let { config.authMode = it.selectedItem as? String ?: "authenticated" }
        allowOpCheck?.let { config.allowOp = it.isSelected }
        acceptEarlyPluginsCheck?.let { config.acceptEarlyPlugins = it.isSelected }
        jvmArgsField?.let { config.jvmArgs = it.text }
        serverArgsField?.let { config.serverArgs = it.text }
    }

    override fun createEditor(): JComponent {
        // Return cached panel if already created
        editorPanel?.let { return it }

        val newPanel = panel {
            // Build Configuration
            group("Build") {
                row {
                    checkBox("Build before run")
                        .applyToComponent { buildBeforeRunCheck = this }
                        .comment("Run Gradle/Maven build task before starting server")
                }

                row("Build task:") {
                    comboBox(listOf("build", "shadowJar", "jar", "assemble"))
                        .applyToComponent { buildTaskCombo = this }
                        .comment("Gradle task to execute (e.g., build, shadowJar)")
                }
            }

            // Plugin Deployment
            group("Plugin Deployment") {
                row {
                    checkBox("Deploy plugin to server")
                        .applyToComponent { deployPluginCheck = this }
                        .comment("Copy plugin JAR to server's mods folder")
                }

                row("Plugin JAR:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileDescriptor("jar")
                            .withTitle("Select Plugin JAR"),
                        project
                    ).applyToComponent {
                        pluginJarField = this
                        textField.document.addDocumentListener(createChangeListener())
                    }
                    .comment("Path to the built plugin JAR (relative to project)")
                    .align(AlignX.FILL)
                }

                row("Plugin name:") {
                    textField()
                        .applyToComponent {
                            pluginNameField = this
                            document.addDocumentListener(createChangeListener())
                        }
                        .comment("Plugin ID for /plugin reload (e.g., com.example:myplugin)")
                        .align(AlignX.FILL)
                }

                row {
                    button("Auto-detect") {
                        autoDetectPluginInfo()
                    }.comment("Detect plugin info from manifest.json or build files")
                }

                row {
                    checkBox("Hot reload enabled")
                        .applyToComponent { hotReloadCheck = this }
                        .comment("Automatically reload plugin when server is already running")
                }
            }

            // Server Configuration
            group("Server") {
                row("Server directory:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Select Server Directory"),
                        project
                    ).applyToComponent {
                        serverPathField = this
                        textField.document.addDocumentListener(createChangeListener())
                    }
                    .comment("Directory containing HytaleServer.jar")
                    .align(AlignX.FILL)
                }

                row("Java executable:") {
                    val javaService = JavaInstallService.getInstance()
                    val java25 = javaService.findJava25()

                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleLocalFileDescriptor()
                            .withTitle("Select Java Executable"),
                        project
                    ).applyToComponent {
                        javaPathField = this
                        textField.document.addDocumentListener(createChangeListener())
                    }
                    .comment(if (java25 != null) "Java 25+ found: ${java25.version}" else "Path to java.exe (Java 25+ required)")
                    .align(AlignX.FILL)
                }

                row("Port:") {
                    spinner(1024..65535, 1)
                        .applyToComponent { portSpinner = this }
                        .comment("UDP port for Hytale (default: 5520)")
                }
            }

            // Memory Settings
            group("Memory") {
                row("Minimum memory:") {
                    comboBox(listOf("512M", "1G", "2G", "4G"))
                        .applyToComponent { minMemoryCombo = this }
                        .comment("-Xms")
                }

                row("Maximum memory:") {
                    comboBox(listOf("2G", "4G", "8G", "16G", "32G"))
                        .applyToComponent { maxMemoryCombo = this }
                        .comment("-Xmx")
                }
            }

            // Server Options
            group("Options") {
                row("Auth mode:") {
                    comboBox(listOf("authenticated", "offline"), textListCellRenderer { it ?: "" })
                        .applyToComponent { authModeCombo = this }
                        .comment("Offline = no Hytale account required")
                }

                row {
                    checkBox("Allow operator commands")
                        .applyToComponent { allowOpCheck = this }
                }

                row {
                    checkBox("Accept early plugins")
                        .applyToComponent { acceptEarlyPluginsCheck = this }
                }
            }

            // Advanced
            collapsibleGroup("Advanced") {
                row("JVM arguments:") {
                    expandableTextField()
                        .applyToComponent {
                            jvmArgsField = this
                            document.addDocumentListener(createChangeListener())
                        }
                        .comment("e.g., -XX:+UseZGC")
                        .align(AlignX.FILL)
                }

                row("Server arguments:") {
                    expandableTextField()
                        .applyToComponent {
                            serverArgsField = this
                            document.addDocumentListener(createChangeListener())
                        }
                        .comment("Additional arguments for HytaleServer.jar")
                        .align(AlignX.FILL)
                }
            }
        }

        editorPanel = newPanel
        return newPanel
    }

    private fun autoDetectPluginInfo() {
        val basePath = project.basePath ?: run {
            Messages.showWarningDialog(project, "Could not determine project path", "Auto-detect Failed")
            return
        }

        val info = detectPluginInfo(basePath)
        if (info != null) {
            // Update UI fields directly
            pluginJarField?.text = info.jarPath
            pluginNameField?.text = "${info.groupId}:${info.artifactId}"
            buildTaskCombo?.selectedItem = info.buildTask

            fireEditorStateChanged()

            Messages.showInfoMessage(
                project,
                "Detected:\n- Plugin: ${info.groupId}:${info.artifactId}\n- JAR: ${info.jarPath}\n- Build task: ${info.buildTask}",
                "Auto-detect Success"
            )
        } else {
            Messages.showWarningDialog(
                project,
                "Could not detect plugin info.\n\nMake sure one of these exists:\n- .hytale/project.json\n- src/main/resources/manifest.json\n- build.gradle with group defined",
                "Auto-detect Failed"
            )
        }
    }

    private data class PluginInfo(
        val groupId: String,
        val artifactId: String,
        val jarPath: String,
        val buildTask: String
    )

    private fun detectPluginInfo(basePath: String): PluginInfo? {
        // Priority 0: .hytale/project.json
        readHytaleProjectJson(basePath)?.let { return it }

        // Priority 1: manifest.json
        readManifestJson(basePath)?.let { return it }

        // Priority 2: Gradle files
        readGradleFiles(basePath)?.let { return it }

        // Priority 3: Project name fallback
        val projectName = project.name
        if (projectName.isNotBlank()) {
            val artifactId = projectName.lowercase().replace(" ", "-")
            return PluginInfo(
                groupId = "com.example",
                artifactId = artifactId,
                jarPath = "build/libs/$artifactId-1.0.0.jar",
                buildTask = "shadowJar"
            )
        }

        return null
    }

    private fun readHytaleProjectJson(basePath: String): PluginInfo? {
        val projectFile = File(basePath, ".hytale/project.json")
        if (!projectFile.exists()) return null

        return try {
            val content = projectFile.readText()

            val groupRegex = """"groupId"\s*:\s*"([^"]+)"""".toRegex()
            val artifactRegex = """"artifactId"\s*:\s*"([^"]+)"""".toRegex()
            val modNameRegex = """"modName"\s*:\s*"([^"]+)"""".toRegex()
            val jarPathRegex = """"jarPath"\s*:\s*"([^"]+)"""".toRegex()
            val buildTaskRegex = """"buildTask"\s*:\s*"([^"]+)"""".toRegex()

            val groupId = groupRegex.find(content)?.groupValues?.get(1) ?: return null
            val artifactId = artifactRegex.find(content)?.groupValues?.get(1) ?: return null
            val modName = modNameRegex.find(content)?.groupValues?.get(1) ?: artifactId
            val jarPath = jarPathRegex.find(content)?.groupValues?.get(1) ?: "build/libs/$artifactId-1.0.0.jar"
            val buildTask = buildTaskRegex.find(content)?.groupValues?.get(1) ?: "shadowJar"

            PluginInfo(groupId, modName, jarPath, buildTask)
        } catch (e: Exception) {
            null
        }
    }

    private fun readManifestJson(basePath: String): PluginInfo? {
        val manifestFile = File(basePath, "src/main/resources/manifest.json")
        if (!manifestFile.exists()) return null

        return try {
            val content = manifestFile.readText()
            val groupRegex = """"Group"\s*:\s*"([^"]+)"""".toRegex()
            val nameRegex = """"Name"\s*:\s*"([^"]+)"""".toRegex()

            val group = groupRegex.find(content)?.groupValues?.get(1)
            val name = nameRegex.find(content)?.groupValues?.get(1)

            if (group != null && name != null) {
                val isGradle = File(basePath, "build.gradle").exists() ||
                        File(basePath, "build.gradle.kts").exists()
                val jarArtifactId = name.lowercase().replace(" ", "-")

                PluginInfo(
                    groupId = group,
                    artifactId = name,
                    jarPath = if (isGradle) "build/libs/$jarArtifactId-1.0.0.jar" else "target/$jarArtifactId-1.0.0.jar",
                    buildTask = if (isGradle) "shadowJar" else "package"
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun readGradleFiles(basePath: String): PluginInfo? {
        val settingsGradle = File(basePath, "settings.gradle")
        val settingsGradleKts = File(basePath, "settings.gradle.kts")

        val projectName = when {
            settingsGradle.exists() -> {
                val content = settingsGradle.readText()
                val regex = """rootProject\.name\s*=\s*['"]([^'"]+)['"]""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            settingsGradleKts.exists() -> {
                val content = settingsGradleKts.readText()
                val regex = """rootProject\.name\s*=\s*"([^"]+)"""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            else -> null
        }

        val buildGradle = File(basePath, "build.gradle")
        val buildGradleKts = File(basePath, "build.gradle.kts")

        val groupId = when {
            buildGradle.exists() -> {
                val content = buildGradle.readText()
                val regex = """group\s*=\s*['"]([^'"]+)['"]""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            buildGradleKts.exists() -> {
                val content = buildGradleKts.readText()
                val regex = """group\s*=\s*"([^"]+)"""".toRegex()
                regex.find(content)?.groupValues?.get(1)
            }
            else -> null
        }

        if (projectName != null) {
            val artifactId = projectName.lowercase().replace(" ", "-")
            val baseGroup = groupId?.substringBeforeLast('.') ?: "com.example"

            return PluginInfo(
                groupId = baseGroup,
                artifactId = artifactId,
                jarPath = "build/libs/$artifactId-1.0.0.jar",
                buildTask = "shadowJar"
            )
        }

        return null
    }

    private fun createChangeListener(): DocumentListener {
        return object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = fireEditorStateChanged()
            override fun removeUpdate(e: DocumentEvent) = fireEditorStateChanged()
            override fun changedUpdate(e: DocumentEvent) = fireEditorStateChanged()
        }
    }
}
