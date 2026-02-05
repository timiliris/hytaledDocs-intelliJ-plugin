package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.services.JavaInstallService
import com.hytaledocs.intellij.services.ServerProfile
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import javax.swing.*

/**
 * Dialog for editing a single server profile.
 * Supports creating new profiles or editing existing ones.
 */
class ServerProfileEditorDialog(
    private val project: Project,
    private val existingProfile: ServerProfile?
) : DialogWrapper(project, true) {

    private val isEditMode = existingProfile != null

    // Form fields
    private lateinit var nameField: JBTextField
    private lateinit var serverPathField: TextFieldWithBrowseButton
    private lateinit var javaPathField: TextFieldWithBrowseButton
    private lateinit var portSpinner: JSpinner
    private lateinit var minMemoryCombo: ComboBox<String>
    private lateinit var maxMemoryCombo: ComboBox<String>
    private lateinit var authModeCombo: ComboBox<String>
    private lateinit var allowOpCheck: JBCheckBox
    private lateinit var acceptEarlyPluginsCheck: JBCheckBox
    private lateinit var jvmArgsField: JBTextField
    private lateinit var serverArgsField: JBTextField

    // Auto-detect Java info label
    private var javaInfoLabel: JLabel? = null

    init {
        title = if (isEditMode) "Edit Server Profile" else "New Server Profile"
        setSize(550, 500)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val javaService = JavaInstallService.getInstance()
        val java25 = javaService.findJava25()

        return panel {
            // Profile Name
            row("Profile name:") {
                nameField = textField()
                    .align(AlignX.FILL)
                    .focused()
                    .applyToComponent {
                        text = existingProfile?.name ?: "My Server"
                    }
                    .component as JBTextField
            }.comment("A friendly name to identify this server profile")

            separator()

            // Server Configuration
            group("Server Configuration") {
                row("Server directory:") {
                    serverPathField = textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Select Server Directory"),
                        project
                    ) { it.path }
                    .align(AlignX.FILL)
                        .applyToComponent {
                            text = existingProfile?.path ?: "server"
                        }
                        .component
                }.comment("Directory containing HytaleServer.jar")

                row("Java executable:") {
                    javaPathField = textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleLocalFileDescriptor()
                            .withTitle("Select Java Executable"),
                        project
                    ) { it.path }
                    .align(AlignX.FILL)
                        .applyToComponent {
                            text = existingProfile?.javaPath ?: java25?.let { javaService.getJavaExecutable(it).toString() } ?: ""
                        }
                        .component
                }

                row {
                    button("Auto-detect Java 25") {
                        autoDetectJava()
                    }.applyToComponent {
                        icon = AllIcons.Actions.Find
                    }

                    javaInfoLabel = label("")
                        .applyToComponent {
                            foreground = HytaleTheme.mutedText
                        }
                        .component

                    // Update info label with current Java status
                    if (java25 != null) {
                        javaInfoLabel?.text = "Found: Java ${java25.version}"
                        javaInfoLabel?.foreground = HytaleTheme.successColor
                    } else {
                        javaInfoLabel?.text = "Java 25+ not found"
                        javaInfoLabel?.foreground = HytaleTheme.warningColor
                    }
                }

                row("Port:") {
                    portSpinner = spinner(1024..65535, 1)
                        .applyToComponent {
                            value = existingProfile?.port ?: 5520
                        }
                        .component
                }.comment("UDP port for Hytale (default: 5520)")
            }

            // Memory Settings
            group("Memory Settings") {
                row("Minimum memory:") {
                    minMemoryCombo = comboBox(listOf("512M", "1G", "2G", "4G"))
                        .applyToComponent {
                            selectedItem = existingProfile?.minMemory ?: "2G"
                        }
                        .component
                }.comment("-Xms: Initial heap size")

                row("Maximum memory:") {
                    maxMemoryCombo = comboBox(listOf("2G", "4G", "8G", "16G", "32G"))
                        .applyToComponent {
                            selectedItem = existingProfile?.maxMemory ?: "8G"
                        }
                        .component
                }.comment("-Xmx: Maximum heap size")
            }

            // Server Options
            group("Server Options") {
                row("Authentication:") {
                    authModeCombo = comboBox(listOf("authenticated", "offline"))
                        .applyToComponent {
                            selectedItem = existingProfile?.authMode ?: "authenticated"
                        }
                        .component
                }.comment("Offline mode = no Hytale account required")

                row {
                    allowOpCheck = checkBox("Allow operator commands (--allow-op)")
                        .applyToComponent {
                            isSelected = existingProfile?.allowOp ?: true
                        }
                        .component
                }

                row {
                    acceptEarlyPluginsCheck = checkBox("Accept early plugins (--accept-early-plugins)")
                        .applyToComponent {
                            isSelected = existingProfile?.acceptEarlyPlugins ?: true
                        }
                        .component
                }
            }

            // Advanced Settings (collapsible)
            collapsibleGroup("Advanced Settings") {
                row("JVM arguments:") {
                    jvmArgsField = expandableTextField()
                        .align(AlignX.FILL)
                        .applyToComponent {
                            text = existingProfile?.jvmArgs ?: ""
                        }
                        .component as JBTextField
                }.comment("Additional JVM flags (e.g., -XX:+UseZGC)")

                row("Server arguments:") {
                    serverArgsField = expandableTextField()
                        .align(AlignX.FILL)
                        .applyToComponent {
                            text = existingProfile?.serverArgs ?: ""
                        }
                        .component as JBTextField
                }.comment("Additional arguments for HytaleServer.jar")
            }
        }
    }

    private fun autoDetectJava() {
        val javaService = JavaInstallService.getInstance()
        val java25 = javaService.findJava25()

        if (java25 != null) {
            val javaExe = javaService.getJavaExecutable(java25)
            javaPathField.text = javaExe.toString()
            javaInfoLabel?.text = "Found: Java ${java25.version} (${java25.source})"
            javaInfoLabel?.foreground = HytaleTheme.successColor
        } else {
            javaInfoLabel?.text = "Java 25+ not found. Click 'Install Java 25' in the Server tab."
            javaInfoLabel?.foreground = HytaleTheme.errorColor
        }
    }

    override fun doValidate(): ValidationInfo? {
        // Validate name
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            return ValidationInfo("Profile name is required", nameField)
        }

        // Validate server path (optional but warn if not valid)
        val serverPath = serverPathField.text.trim()
        if (serverPath.isNotEmpty()) {
            val path = if (Paths.get(serverPath).isAbsolute) {
                Paths.get(serverPath)
            } else {
                project.basePath?.let { Paths.get(it, serverPath) }
            }

            if (path != null && Files.exists(path)) {
                val serverJar = path.resolve("HytaleServer.jar")
                if (!Files.exists(serverJar)) {
                    // Just a warning, not blocking
                }
            }
        }

        // Validate port
        val port = portSpinner.value as Int
        if (port < 1024 || port > 65535) {
            return ValidationInfo("Port must be between 1024 and 65535", portSpinner)
        }

        return null
    }

    /**
     * Get the configured profile from the dialog.
     */
    fun getProfile(): ServerProfile {
        return ServerProfile(
            id = existingProfile?.id ?: UUID.randomUUID().toString(),
            name = nameField.text.trim(),
            path = serverPathField.text.trim(),
            javaPath = javaPathField.text.trim(),
            port = portSpinner.value as Int,
            minMemory = minMemoryCombo.selectedItem as? String ?: "2G",
            maxMemory = maxMemoryCombo.selectedItem as? String ?: "8G",
            authMode = authModeCombo.selectedItem as? String ?: "authenticated",
            allowOp = allowOpCheck.isSelected,
            acceptEarlyPlugins = acceptEarlyPluginsCheck.isSelected,
            jvmArgs = jvmArgsField.text.trim(),
            serverArgs = serverArgsField.text.trim(),
            lastUsed = existingProfile?.lastUsed ?: 0,
            createdAt = existingProfile?.createdAt ?: System.currentTimeMillis()
        )
    }
}
