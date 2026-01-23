package com.hytaledocs.intellij.settings

import com.hytaledocs.intellij.wizard.HytaleModuleBuilder
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.*
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JLabel

/**
 * Application-level settings panel for HytaleDocs plugin.
 * Accessible via Settings > Tools > HytaleDocs
 *
 * This configurable allows users to set the Hytale game installation path
 * which is used when creating new projects.
 */
class HytaleAppSettingsConfigurable : BoundConfigurable("HytaleDocs") {

    private val settings = HytaleAppSettings.getInstance()
    private lateinit var installationPathField: TextFieldWithBrowseButton
    private lateinit var statusLabel: JLabel

    override fun createPanel(): DialogPanel {
        return panel {
            group("Hytale Installation") {
                row {
                    comment("""
                        Configure the path to your Hytale game installation.
                        This is used when creating new plugin projects to copy server files automatically.
                    """.trimIndent())
                }

                row("Installation path:") {
                    val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Select Hytale Installation Directory")

                    installationPathField = textFieldWithBrowseButton(descriptor)
                        .bindText(settings::hytaleInstallationPath)
                        .comment("Path to the Hytale game folder (containing Server/HytaleServer.jar)")
                        .align(AlignX.FILL)
                        .onChanged { updateStatus() }
                        .component

                    installationPathField.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateStatus()
                        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateStatus()
                        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateStatus()
                    })
                }

                row {
                    button("Auto-detect") {
                        autoDetectInstallation()
                    }.comment("Search for Hytale in common installation locations")
                }

                row {
                    statusLabel = label("").component
                    updateStatus()
                }
            }

            group("Detection Settings") {
                row {
                    checkBox("Auto-detect installation on startup")
                        .bindSelected(settings::autoDetectInstallation)
                        .comment("Automatically search for Hytale installation when no path is configured")
                }
            }

            group("Expected Directory Structure") {
                row {
                    comment("""
                        The installation path should point to the game directory containing:

                        <path>/
                        ├── Server/
                        │   └── HytaleServer.jar
                        └── Assets.zip (optional)

                        Common locations:
                        • Windows: %APPDATA%\Hytale\install\release\package\game\latest
                        • macOS: ~/Library/Application Support/Hytale/install/release/package/game/latest
                        • Linux: ~/.local/share/Hytale/install/release/package/game/latest
                    """.trimIndent())
                }
            }
        }
    }

    private fun autoDetectInstallation() {
        val detected = HytaleModuleBuilder.autoDetectHytaleInstallation()
        if (detected != null) {
            installationPathField.text = detected.basePath.toString()
            updateStatus()
        } else {
            statusLabel.text = "Could not auto-detect Hytale installation"
            statusLabel.foreground = JBColor(Color(180, 140, 60), Color(200, 160, 80))
        }
    }

    private fun updateStatus() {
        if (!::installationPathField.isInitialized) return

        val path = installationPathField.text.trim()

        if (path.isEmpty()) {
            statusLabel.text = "No installation path configured"
            statusLabel.foreground = JBColor.GRAY
            return
        }

        val basePath = Paths.get(path)
        if (!Files.exists(basePath)) {
            statusLabel.text = "Path does not exist"
            statusLabel.foreground = JBColor(Color(180, 60, 60), Color(200, 80, 80))
            return
        }

        val serverJar = basePath.resolve("Server/HytaleServer.jar")
        val assetsZip = basePath.resolve("Assets.zip")

        val hasServerJar = Files.exists(serverJar)
        val hasAssets = Files.exists(assetsZip)

        val status = buildString {
            if (hasServerJar && hasAssets) {
                append("Valid installation (Server + Assets)")
            } else if (hasServerJar) {
                append("Valid installation (Server only)")
            } else if (hasAssets) {
                append("Assets found, but HytaleServer.jar missing in Server/ folder")
            } else {
                append("Invalid path: HytaleServer.jar not found in Server/ folder")
            }
        }

        statusLabel.text = status
        statusLabel.foreground = when {
            hasServerJar -> JBColor(Color(40, 160, 80), Color(80, 200, 120))
            hasAssets -> JBColor(Color(180, 140, 60), Color(200, 160, 80))
            else -> JBColor(Color(180, 60, 60), Color(200, 80, 80))
        }
    }
}
