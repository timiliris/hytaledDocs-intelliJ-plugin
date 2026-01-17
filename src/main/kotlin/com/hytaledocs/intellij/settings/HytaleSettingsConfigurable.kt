package com.hytaledocs.intellij.settings

import com.hytaledocs.intellij.services.JavaInstallService
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import javax.swing.DefaultComboBoxModel

/**
 * Settings panel for Hytale server configuration.
 * Accessible via Settings > Tools > Hytale Server
 */
class HytaleSettingsConfigurable(private val project: Project) : BoundConfigurable("Hytale Server") {

    private val settings = HytaleServerSettings.getInstance(project)
    private val javaService = JavaInstallService.getInstance()

    override fun createPanel(): DialogPanel {
        return panel {
            group("Server Paths") {
                row("Server directory:") {
                    textField()
                        .bindText(settings::serverPath)
                        .comment("Directory containing HytaleServer.jar and Assets.zip (relative to project)")
                        .align(AlignX.FILL)
                }

                row("Java executable:") {
                    val javaInstalls = javaService.findJavaInstallations()
                    val java25 = javaService.findJava25()

                    textField()
                        .bindText(settings::javaPath)
                        .comment(if (java25 != null) "Java 25+ found: ${java25.version}" else "Path to java.exe (Java 25+ required)")
                        .align(AlignX.FILL)
                }
            }

            group("Memory Settings") {
                row("Minimum memory:") {
                    comboBox(listOf("512M", "1G", "2G", "4G"))
                        .bindItem(
                            { settings.minMemory },
                            { settings.minMemory = it ?: "2G" }
                        )
                        .comment("Initial heap size (-Xms)")
                }

                row("Maximum memory:") {
                    comboBox(listOf("2G", "4G", "8G", "16G", "32G"))
                        .bindItem(
                            { settings.maxMemory },
                            { settings.maxMemory = it ?: "8G" }
                        )
                        .comment("Maximum heap size (-Xmx)")
                }
            }

            group("Server Settings") {
                row("Port:") {
                    spinner(1024..65535, 1)
                        .bindIntValue(settings::port)
                        .comment("UDP port for Hytale (default: 5520)")
                }

                row("Authentication mode:") {
                    comboBox(AuthMode.entries.toList(), textListCellRenderer { it?.displayName ?: "" })
                        .bindItem(
                            { settings.authMode },
                            { settings.authMode = it ?: AuthMode.AUTHENTICATED }
                        )
                        .comment("Offline mode allows players without Hytale accounts")
                }

                row {
                    checkBox("Allow operator commands")
                        .bindSelected(settings::allowOp)
                        .comment("Enable /op and operator privileges")
                }

                row {
                    checkBox("Accept early plugins")
                        .bindSelected(settings::acceptEarlyPlugins)
                        .comment("Allow experimental plugin API usage")
                }
            }

            group("Backup Settings") {
                lateinit var backupCheckbox: com.intellij.ui.dsl.builder.Cell<javax.swing.JCheckBox>

                row {
                    backupCheckbox = checkBox("Enable automatic backups")
                        .bindSelected(settings::backupEnabled)
                }

                row("Backup frequency (minutes):") {
                    spinner(5..1440, 5)
                        .bindIntValue(settings::backupFrequency)
                }
            }

            group("Advanced") {
                row("Additional JVM arguments:") {
                    expandableTextField()
                        .bindText(settings::jvmArgs)
                        .comment("e.g., -XX:+UseZGC -XX:+ZGenerational")
                        .align(AlignX.FILL)
                }

                row("Additional server arguments:") {
                    expandableTextField()
                        .bindText(settings::serverArgs)
                        .comment("Additional arguments passed to HytaleServer.jar")
                        .align(AlignX.FILL)
                }
            }

            group("Console Settings") {
                row("Maximum log lines:") {
                    spinner(100..50000, 100)
                        .bindIntValue(settings::maxLogLines)
                        .comment("Limit console output to prevent memory issues")
                }

                row {
                    checkBox("Auto-scroll to bottom")
                        .bindSelected(settings::autoScroll)
                }

                row {
                    checkBox("Show timestamps")
                        .bindSelected(settings::showTimestamps)
                }
            }
        }
    }
}
