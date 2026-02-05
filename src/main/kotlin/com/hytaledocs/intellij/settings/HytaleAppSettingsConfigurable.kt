package com.hytaledocs.intellij.settings

import com.hytaledocs.intellij.HytaleBundle
import com.hytaledocs.intellij.services.MavenMetadataService
import com.hytaledocs.intellij.services.ServerVersionCacheService
import com.hytaledocs.intellij.wizard.HytaleModuleBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.DefaultComboBoxModel
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
    private lateinit var languageComboBox: ComboBox<String>
    private lateinit var installationPathField: TextFieldWithBrowseButton
    private lateinit var statusLabel: JLabel
    private lateinit var versionComboBox: ComboBox<String>
    private lateinit var versionStatusLabel: JLabel
    private lateinit var cacheStatusLabel: JLabel
    private var availableVersions: List<MavenMetadataService.ServerVersion> = emptyList()
    private var initialLanguage: String = settings.language

    override fun createPanel(): DialogPanel {
        return panel {
            group("Language / Langue") {
                row("Language:") {
                    val languages = HytaleAppSettings.SUPPORTED_LANGUAGES.entries.toList()
                    languageComboBox = comboBox(languages.map { it.value })
                        .comment("Plugin interface language (requires restart to fully apply)")
                        .onChanged { updateLanguageSelection() }
                        .component

                    // Set initial selection
                    val currentIndex = languages.indexOfFirst { it.key == settings.language }
                    if (currentIndex >= 0) {
                        languageComboBox.selectedIndex = currentIndex
                    }
                }
            }

            group("Hytale Installation") {
                row {
                    comment("""
                        Configure the path to your Hytale game installation.
                        This is used when creating new plugin projects to copy server files automatically.
                    """.trimIndent())
                }

                row("Installation path:") {
                    installationPathField = TextFieldWithBrowseButton().apply {
                        addBrowseFolderListener(
                            "Select Hytale Installation Directory",
                            "Path to the Hytale game folder (containing Server/HytaleServer.jar)",
                            null,
                            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        )
                        text = settings.hytaleInstallationPath
                        textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateStatus()
                            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateStatus()
                            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateStatus()
                        })
                    }
                    cell(installationPathField)
                        .comment("Path to the Hytale game folder (containing Server/HytaleServer.jar)")
                        .align(AlignX.FILL)
                        .onApply { settings.hytaleInstallationPath = installationPathField.text }
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

            group("Code Completion") {
                row {
                    checkBox("Enable Hytale code completion")
                        .bindSelected(settings::enableCodeCompletion)
                        .comment("Show Hytale-specific completions for events, methods, and plugin lifecycle")
                }

                row("Completion priority:") {
                    val priorityOptions = listOf(
                        "High (top of list)" to 200,
                        "Normal" to 100,
                        "Low (bottom of list)" to 50
                    )

                    val priorityCombo = ComboBox(priorityOptions.map { it.first }.toTypedArray())

                    // Set initial selection based on current value
                    val currentPriority = settings.completionPriority
                    priorityCombo.selectedIndex = when {
                        currentPriority >= 150 -> 0  // High
                        currentPriority >= 75 -> 1   // Normal
                        else -> 2                     // Low
                    }

                    priorityCombo.addActionListener {
                        val selectedIndex = priorityCombo.selectedIndex
                        settings.completionPriority = priorityOptions.getOrNull(selectedIndex)?.second ?: 100
                    }

                    cell(priorityCombo)
                        .comment("Controls where Hytale completions appear in the suggestion list")
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

            // ==================== Maven Server Version Settings ====================

            group("Server Version (Maven Repository)") {
                row {
                    comment("""
                        Download server JARs directly from the official Hytale Maven repository.
                        This allows you to choose specific server versions for development.
                    """.trimIndent())
                }

                row("Server version:") {
                    versionComboBox = comboBox(listOf("Loading..."))
                        .comment("Select a specific version or use 'Latest' for auto-updates")
                        .align(AlignX.FILL)
                        .onChanged { updateVersionSelection() }
                        .component

                    button("Refresh") {
                        refreshVersions()
                    }
                }

                row {
                    versionStatusLabel = label("").component
                }

                row {
                    checkBox("Auto-update to latest version")
                        .bindSelected(settings::autoUpdateServerVersion)
                        .comment("Automatically download new server versions when available")
                }

                row("Maven repository:") {
                    textField()
                        .bindText(settings::mavenRepositoryUrl)
                        .align(AlignX.FILL)
                        .comment("Base URL: https://maven.hytale.com/release")
                }
            }

            group("Version Cache") {
                row {
                    cacheStatusLabel = label("Checking cache...").component
                }

                row {
                    button("Open Cache Folder") {
                        openCacheFolder()
                    }
                    button("Clear Cache") {
                        clearCache()
                    }.comment("Delete all cached server versions")
                }

                row {
                    comment("""
                        Server JARs are cached in ~/.hytale-intellij/servers/
                        Each version is stored separately to allow easy switching.
                    """.trimIndent())
                }
            }
        }.also {
            // Load versions after panel is created
            ApplicationManager.getApplication().executeOnPooledThread {
                refreshVersions()
                updateCacheStatus()
            }
        }
    }

    private fun refreshVersions() {
        versionStatusLabel.text = "Fetching versions from Maven..."
        versionStatusLabel.foreground = JBColor.GRAY

        MavenMetadataService.getInstance().getAvailableVersions(forceRefresh = true)
            .thenAccept { versions ->
                ApplicationManager.getApplication().invokeLater {
                    availableVersions = versions
                    updateVersionComboBox(versions)
                }
            }
            .exceptionally { error ->
                ApplicationManager.getApplication().invokeLater {
                    versionStatusLabel.text = "Failed to fetch versions: ${error.message}"
                    versionStatusLabel.foreground = JBColor(Color(180, 60, 60), Color(200, 80, 80))
                }
                null
            }
    }

    private fun updateVersionComboBox(versions: List<MavenMetadataService.ServerVersion>) {
        val model = DefaultComboBoxModel<String>()
        model.addElement("Latest (auto-update)")

        versions.forEach { version ->
            val cached = ServerVersionCacheService.getInstance().isVersionCached(version.version)
            val displayText = if (cached) {
                "${version.getDisplayName()} [cached]"
            } else {
                version.getDisplayName()
            }
            model.addElement(displayText)
        }

        versionComboBox.model = model

        // Select current preference
        val preferred = settings.preferredServerVersion
        if (preferred.isBlank()) {
            versionComboBox.selectedIndex = 0
        } else {
            val index = versions.indexOfFirst { it.version == preferred }
            if (index >= 0) {
                versionComboBox.selectedIndex = index + 1 // +1 for "Latest" option
            }
        }

        versionStatusLabel.text = "${versions.size} versions available"
        versionStatusLabel.foreground = JBColor(Color(40, 160, 80), Color(80, 200, 120))
    }

    private fun updateVersionSelection() {
        if (!::versionComboBox.isInitialized) return

        val selectedIndex = versionComboBox.selectedIndex
        if (selectedIndex == 0) {
            // "Latest" selected
            settings.preferredServerVersion = ""
        } else if (selectedIndex > 0 && availableVersions.isNotEmpty()) {
            val version = availableVersions.getOrNull(selectedIndex - 1)
            if (version != null) {
                settings.preferredServerVersion = version.version
            }
        }
    }

    private fun updateCacheStatus() {
        val cacheService = ServerVersionCacheService.getInstance()
        val cachedVersions = cacheService.listCachedVersions()
        val totalSize = cacheService.getTotalCacheSize()
        val sizeMB = totalSize / 1024 / 1024

        ApplicationManager.getApplication().invokeLater {
            if (::cacheStatusLabel.isInitialized) {
                cacheStatusLabel.text = "${cachedVersions.size} versions cached (${sizeMB} MB total)"
            }
        }
    }

    private fun openCacheFolder() {
        val cacheDir = ServerVersionCacheService.getInstance().getCacheDirectory()
        if (Files.exists(cacheDir)) {
            java.awt.Desktop.getDesktop().open(cacheDir.toFile())
        } else {
            Files.createDirectories(cacheDir)
            java.awt.Desktop.getDesktop().open(cacheDir.toFile())
        }
    }

    private fun clearCache() {
        val cacheService = ServerVersionCacheService.getInstance()
        cacheService.listCachedVersions().forEach { version ->
            cacheService.deleteVersion(version)
        }
        updateCacheStatus()
        refreshVersions() // Refresh to update [cached] labels
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

    private fun updateLanguageSelection() {
        if (!::languageComboBox.isInitialized) return

        val languages = HytaleAppSettings.SUPPORTED_LANGUAGES.entries.toList()
        val selectedIndex = languageComboBox.selectedIndex
        if (selectedIndex >= 0 && selectedIndex < languages.size) {
            val newLanguage = languages[selectedIndex].key

            // Only prompt if language actually changed
            if (newLanguage != initialLanguage) {
                settings.language = newLanguage

                // Force save settings to XML immediately
                ApplicationManager.getApplication().saveSettings()

                // Clear bundle cache to load new language
                HytaleBundle.clearCache()

                // Show restart dialog
                val result = Messages.showYesNoDialog(
                    "Language changed to ${languages[selectedIndex].value}.\n\n" +
                    "The IDE needs to be restarted for all changes to take effect.\n\n" +
                    "Restart now?",
                    "Restart Required",
                    "Restart",
                    "Later",
                    Messages.getQuestionIcon()
                )

                if (result == Messages.YES) {
                    ApplicationManagerEx.getApplicationEx().restart(true)
                }

                // Update initial language so we don't prompt again
                initialLanguage = newLanguage
            }
        }
    }
}
