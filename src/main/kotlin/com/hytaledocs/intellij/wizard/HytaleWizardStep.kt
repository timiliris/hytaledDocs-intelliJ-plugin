package com.hytaledocs.intellij.wizard

import com.hytaledocs.intellij.HytaleIcons
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class HytaleWizardStep(
    private val builder: HytaleModuleBuilder,
    private val wizardContext: WizardContext
) : ModuleWizardStep() {

    // Project location
    private val projectLocationField = TextFieldWithBrowseButton()
    private val projectNameField = JBTextField(builder.modName)

    // Fields
    private val modNameField = JBTextField(builder.modName)
    private val modIdField = JBTextField(builder.modId)
    private val packageNameField = JBTextField(builder.packageName)
    private val commandNameField = JBTextField(builder.commandName)
    private val authorField = JBTextField(builder.author)
    private val descriptionArea = JBTextArea(builder.modDescription, 2, 40)
    private val versionField = JBTextField(builder.version)
    private val serverPathField = TextFieldWithBrowseButton()

    // Template selection
    private val templateEmptyRadio = JBRadioButton("Empty Mod", builder.templateType == HytaleModuleBuilder.TemplateType.EMPTY)
    private val templateFullRadio = JBRadioButton("Full Template", builder.templateType == HytaleModuleBuilder.TemplateType.FULL)
    private val templateGroup = ButtonGroup()

    // Game detection
    private val copyFromGameCheckbox = JCheckBox("Copy server files from game installation", builder.copyFromGame)
    private val gameStatusLabel = JBLabel()

    // Track manual edits
    private var modIdManuallyEdited = false
    private var packageNameManuallyEdited = false
    private var commandNameManuallyEdited = false
    private var projectNameManuallyEdited = false

    init {
        setupProjectLocation()
        setupAutoCompletion()
        setupServerPathBrowser()
        setupTemplateSelection()
        setupGameDetection()
    }

    private fun setupProjectLocation() {
        // Set default project location
        val defaultPath = System.getProperty("user.home") + File.separator + "IdeaProjects"
        projectLocationField.text = defaultPath

        projectLocationField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Select Project Location")
                    .withDescription("Choose where to create the project")
            )
        )

        // Update project name when mod name changes
        modNameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateProjectName()
            override fun removeUpdate(e: DocumentEvent) = updateProjectName()
            override fun changedUpdate(e: DocumentEvent) = updateProjectName()
        })

        // Track manual project name edits
        projectNameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { projectNameManuallyEdited = true }
            override fun removeUpdate(e: DocumentEvent) { projectNameManuallyEdited = true }
            override fun changedUpdate(e: DocumentEvent) { projectNameManuallyEdited = true }
        })
    }

    private fun updateProjectName() {
        if (!projectNameManuallyEdited) {
            val name = modNameField.text.trim()
            val projectName = name.replace(Regex("[^a-zA-Z0-9]"), "")
            projectNameManuallyEdited = false
            projectNameField.text = projectName
            projectNameManuallyEdited = false
        }
    }

    private fun setupAutoCompletion() {
        // Auto-generate mod ID and package name from mod name
        modNameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateDerivedFields()
            override fun removeUpdate(e: DocumentEvent) = updateDerivedFields()
            override fun changedUpdate(e: DocumentEvent) = updateDerivedFields()
        })

        // Track manual edits
        modIdField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { modIdManuallyEdited = true }
            override fun removeUpdate(e: DocumentEvent) { modIdManuallyEdited = true }
            override fun changedUpdate(e: DocumentEvent) { modIdManuallyEdited = true }
        })

        packageNameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { packageNameManuallyEdited = true }
            override fun removeUpdate(e: DocumentEvent) { packageNameManuallyEdited = true }
            override fun changedUpdate(e: DocumentEvent) { packageNameManuallyEdited = true }
        })

        commandNameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { commandNameManuallyEdited = true }
            override fun removeUpdate(e: DocumentEvent) { commandNameManuallyEdited = true }
            override fun changedUpdate(e: DocumentEvent) { commandNameManuallyEdited = true }
        })
    }

    private fun updateDerivedFields() {
        val name = modNameField.text.trim()

        if (!modIdManuallyEdited) {
            val generatedId = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            modIdManuallyEdited = false
            modIdField.text = generatedId
            modIdManuallyEdited = false
        }

        if (!packageNameManuallyEdited) {
            val simpleName = name.lowercase().replace(Regex("[^a-z0-9]"), "")
            packageNameManuallyEdited = false
            packageNameField.text = "com.example.$simpleName"
            packageNameManuallyEdited = false
        }

        if (!commandNameManuallyEdited) {
            val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val abbreviation = if (words.size > 1) {
                words.map { it.first().lowercaseChar() }.joinToString("")
            } else {
                name.lowercase().replace(Regex("[^a-z]"), "").take(3)
            }
            commandNameManuallyEdited = false
            commandNameField.text = abbreviation
            commandNameManuallyEdited = false
        }
    }

    private fun setupServerPathBrowser() {
        serverPathField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle("Select Server Folder")
                    .withDescription("Select the folder containing HytaleServer.jar")
            )
        )
    }

    private fun setupTemplateSelection() {
        templateGroup.add(templateEmptyRadio)
        templateGroup.add(templateFullRadio)

        val updateCommandVisibility = {
            commandNameField.isEnabled = templateFullRadio.isSelected
        }

        templateEmptyRadio.addActionListener { updateCommandVisibility() }
        templateFullRadio.addActionListener { updateCommandVisibility() }
    }

    private fun setupGameDetection() {
        val installation = builder.hytaleInstallation

        if (installation != null) {
            val hasJar = installation.hasServerJar()
            val hasAssets = installation.hasAssets()

            val statusText = buildString {
                append("Hytale detected: ")
                append(installation.basePath.fileName.toString())
                if (hasJar && hasAssets) {
                    append(" (Server + Assets)")
                } else if (hasJar) {
                    append(" (Server only)")
                } else if (hasAssets) {
                    append(" (Assets only)")
                }
            }

            gameStatusLabel.text = statusText
            gameStatusLabel.foreground = JBColor(Color(40, 160, 80), Color(80, 200, 120))
            copyFromGameCheckbox.isEnabled = true
            copyFromGameCheckbox.isSelected = true
        } else {
            gameStatusLabel.text = "Hytale not detected - specify server folder below"
            gameStatusLabel.foreground = JBColor.GRAY
            copyFromGameCheckbox.isEnabled = false
            copyFromGameCheckbox.isSelected = false
        }
    }

    override fun getComponent(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.border = JBUI.Borders.empty(10)

        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        val contentPanel = createContentPanel()
        val scrollPane = JBScrollPane(contentPanel)
        scrollPane.border = null
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun createHeaderPanel(): JPanel {
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = JBUI.Borders.emptyBottom(16)

        val iconLabel = JBLabel(HytaleIcons.HYTALE_WIZARD)
        iconLabel.border = JBUI.Borders.emptyRight(15)
        headerPanel.add(iconLabel, BorderLayout.WEST)

        val titlePanel = JPanel()
        titlePanel.layout = BoxLayout(titlePanel, BoxLayout.Y_AXIS)

        val titleLabel = JBLabel("Create New Hytale Mod")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 18f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        titlePanel.add(titleLabel)

        titlePanel.add(Box.createVerticalStrut(4))

        val subtitleLabel = JBLabel("Configure your mod settings")
        subtitleLabel.foreground = JBColor.GRAY
        subtitleLabel.font = subtitleLabel.font.deriveFont(12f)
        subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
        titlePanel.add(subtitleLabel)

        headerPanel.add(titlePanel, BorderLayout.CENTER)

        return headerPanel
    }

    private fun createContentPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()

        var row = 0

        // === Project Location Section ===
        addSectionHeader(panel, gbc, row++, "Project")

        addFormRow(panel, gbc, row++, "Name:", projectNameField,
            "Project folder name")

        addFormRow(panel, gbc, row++, "Location:", projectLocationField,
            "Where to create the project")

        // === Template Selection Section ===
        addSectionHeader(panel, gbc, row++, "Template")

        addFormRow(panel, gbc, row++, "Type:", createTemplatePanel(),
            "Choose between an empty mod or a full-featured template")

        // === Mod Information Section ===
        addSectionHeader(panel, gbc, row++, "Mod Information")

        addFormRow(panel, gbc, row++, "Mod Name:", modNameField,
            "Display name of your mod (e.g., 'My Cool Mod')")

        addHintRow(panel, gbc, row++,
            "Note: Names without spaces (e.g., 'MyCoolMod') enable hot reload support")

        addFormRow(panel, gbc, row++, "Mod ID:", modIdField,
            "Unique identifier, auto-generated (e.g., 'my-cool-mod')")

        addFormRow(panel, gbc, row++, "Package:", packageNameField,
            "Java package (e.g., 'com.example.mycoolmod')")

        addFormRow(panel, gbc, row++, "Command:", commandNameField,
            "In-game command shortcut (e.g., 'mcm' for /mcm)")

        addFormRow(panel, gbc, row++, "Version:", versionField,
            "Initial version (e.g., '1.0.0')")

        // === Metadata Section ===
        addSectionHeader(panel, gbc, row++, "Metadata")

        addFormRow(panel, gbc, row++, "Author:", authorField,
            "Your name or organization")

        addFormRow(panel, gbc, row++, "Description:", createDescriptionScrollPane(),
            "Brief description of your mod")

        // === Server Configuration Section ===
        addSectionHeader(panel, gbc, row++, "Server Files")

        addGameDetectionRow(panel, gbc, row++)

        addFormRow(panel, gbc, row++, "Server Folder:", serverPathField,
            "Folder with HytaleServer.jar (optional if game detected)")

        // Spacer
        gbc.gridy = row
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(Box.createVerticalGlue(), gbc)

        return panel
    }

    private fun createTemplatePanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))

        val emptyPanel = JPanel(BorderLayout())
        emptyPanel.border = JBUI.Borders.emptyRight(20)
        templateEmptyRadio.toolTipText = "Minimal mod with just the main class"
        emptyPanel.add(templateEmptyRadio, BorderLayout.WEST)

        val fullPanel = JPanel(BorderLayout())
        templateFullRadio.toolTipText = "Complete mod with commands, listeners, and UI"
        fullPanel.add(templateFullRadio, BorderLayout.WEST)

        panel.add(emptyPanel)
        panel.add(fullPanel)

        return panel
    }

    private fun addGameDetectionRow(panel: JPanel, gbc: GridBagConstraints, row: Int) {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(4, 0, 4, 0)
        gbc.weightx = 1.0

        val gamePanel = JPanel(BorderLayout())
        gamePanel.border = JBUI.Borders.empty(6)
        gamePanel.background = JBColor(Color(245, 248, 250), Color(45, 50, 55))

        val innerPanel = JPanel()
        innerPanel.layout = BoxLayout(innerPanel, BoxLayout.Y_AXIS)
        innerPanel.isOpaque = false

        gameStatusLabel.alignmentX = Component.LEFT_ALIGNMENT
        innerPanel.add(gameStatusLabel)

        innerPanel.add(Box.createVerticalStrut(4))

        copyFromGameCheckbox.alignmentX = Component.LEFT_ALIGNMENT
        copyFromGameCheckbox.isOpaque = false
        innerPanel.add(copyFromGameCheckbox)

        gamePanel.add(innerPanel, BorderLayout.CENTER)
        panel.add(gamePanel, gbc)

        gbc.gridwidth = 1
    }

    private fun createDescriptionScrollPane(): JScrollPane {
        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        descriptionArea.border = JBUI.Borders.empty(4)
        val scrollPane = JBScrollPane(descriptionArea)
        scrollPane.preferredSize = Dimension(300, 50)
        return scrollPane
    }

    private fun addSectionHeader(panel: JPanel, gbc: GridBagConstraints, row: Int, title: String) {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = if (row == 0) JBUI.insets(0, 0, 6, 0) else JBUI.insets(12, 0, 6, 0)
        gbc.weightx = 1.0
        gbc.weighty = 0.0

        val headerPanel = JPanel(BorderLayout())

        val label = JBLabel(title)
        label.font = label.font.deriveFont(Font.BOLD, 12f)
        label.foreground = JBColor(Color(60, 130, 200), Color(100, 160, 220))
        headerPanel.add(label, BorderLayout.WEST)

        val separator = JSeparator()
        separator.foreground = JBColor(Color(200, 200, 200), Color(80, 80, 80))
        val separatorPanel = JPanel(BorderLayout())
        separatorPanel.border = JBUI.Borders.emptyLeft(10)
        separatorPanel.add(separator, BorderLayout.CENTER)
        headerPanel.add(separatorPanel, BorderLayout.CENTER)

        panel.add(headerPanel, gbc)
        gbc.gridwidth = 1
    }

    private fun addFormRow(panel: JPanel, gbc: GridBagConstraints, row: Int, labelText: String, field: JComponent, tooltip: String) {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = JBUI.insets(3, 0, 3, 8)
        gbc.weightx = 0.0

        val label = JBLabel(labelText)
        label.toolTipText = tooltip
        panel.add(label, gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        gbc.insets = JBUI.insets(3, 0, 3, 0)

        field.toolTipText = tooltip
        if (field is JTextField) {
            field.columns = 25
        }
        panel.add(field, gbc)
    }

    private fun addHintRow(panel: JPanel, gbc: GridBagConstraints, row: Int, hintText: String) {
        gbc.gridx = 1
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = JBUI.insets(0, 0, 4, 0)
        gbc.weightx = 1.0

        val hintLabel = JBLabel(hintText)
        hintLabel.font = hintLabel.font.deriveFont(11f)
        hintLabel.foreground = JBColor(Color(120, 120, 120), Color(150, 150, 150))
        panel.add(hintLabel, gbc)
    }

    override fun updateDataModel() {
        // Update builder
        builder.modName = modNameField.text.trim()
        builder.modId = modIdField.text.trim()
        builder.packageName = packageNameField.text.trim()
        builder.commandName = commandNameField.text.trim().lowercase()
        builder.author = authorField.text.trim()
        builder.modDescription = descriptionArea.text.trim()
        builder.version = versionField.text.trim()
        builder.serverPath = serverPathField.text.trim()
        builder.templateType = if (templateEmptyRadio.isSelected) {
            HytaleModuleBuilder.TemplateType.EMPTY
        } else {
            HytaleModuleBuilder.TemplateType.FULL
        }
        builder.copyFromGame = copyFromGameCheckbox.isSelected

        // Update wizard context with project location
        val projectPath = projectLocationField.text.trim() + "/" + projectNameField.text.trim()
        wizardContext.projectName = projectNameField.text.trim()
        wizardContext.setProjectFileDirectory(projectPath)
    }

    override fun validate(): Boolean {
        if (projectNameField.text.isBlank()) {
            throw com.intellij.openapi.options.ConfigurationException("Project name cannot be empty")
        }
        if (projectLocationField.text.isBlank()) {
            throw com.intellij.openapi.options.ConfigurationException("Project location cannot be empty")
        }
        if (!File(projectLocationField.text).isDirectory) {
            throw com.intellij.openapi.options.ConfigurationException("Project location must be a valid directory")
        }
        if (modNameField.text.isBlank()) {
            throw com.intellij.openapi.options.ConfigurationException("Mod name cannot be empty")
        }
        if (modIdField.text.isBlank()) {
            throw com.intellij.openapi.options.ConfigurationException("Mod ID cannot be empty")
        }
        if (packageNameField.text.isBlank()) {
            throw com.intellij.openapi.options.ConfigurationException("Package name cannot be empty")
        }
        if (!packageNameField.text.matches(Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$"))) {
            throw com.intellij.openapi.options.ConfigurationException("Package name must be a valid Java package (e.g., 'com.example.mymod')")
        }
        if (templateFullRadio.isSelected) {
            if (commandNameField.text.isBlank()) {
                throw com.intellij.openapi.options.ConfigurationException("Command name cannot be empty for Full Template")
            }
            if (!commandNameField.text.matches(Regex("^[a-z][a-z0-9]*$"))) {
                throw com.intellij.openapi.options.ConfigurationException("Command must be lowercase letters and numbers only")
            }
        }
        return true
    }
}
