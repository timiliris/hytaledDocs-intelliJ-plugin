package com.hytaledocs.intellij.run

import com.hytaledocs.intellij.services.JavaInstallService
import com.hytaledocs.intellij.util.PluginInfoDetector
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
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
                        "Select Plugin JAR",
                        project,
                        FileChooserDescriptorFactory.createSingleFileDescriptor("jar")
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
                        "Select Server Directory",
                        project,
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
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
                        "Select Java Executable",
                        project,
                        FileChooserDescriptorFactory.createSingleLocalFileDescriptor()
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
            pluginNameField?.text = "${info.groupId}:${info.modName}"
            buildTaskCombo?.selectedItem = info.buildTask

            fireEditorStateChanged()

            Messages.showInfoMessage(
                project,
                "Detected:\n- Plugin: ${info.groupId}:${info.modName}\n- JAR: ${info.jarPath}\n- Build task: ${info.buildTask}",
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

    /**
     * Detects plugin info from project files using the shared utility.
     */
    private fun detectPluginInfo(basePath: String): PluginInfoDetector.PluginInfo? {
        return PluginInfoDetector.detect(basePath, project.name)
    }

    private fun createChangeListener(): DocumentListener {
        return object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = fireEditorStateChanged()
            override fun removeUpdate(e: DocumentEvent) = fireEditorStateChanged()
            override fun changedUpdate(e: DocumentEvent) = fireEditorStateChanged()
        }
    }
}
