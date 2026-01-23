package com.hytaledocs.intellij.wizard

import com.hytaledocs.intellij.HytaleIcons
import com.hytaledocs.intellij.settings.HytaleAppSettings
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Hytale Mod project wizard with multi-step design.
 */
class HytaleNewProjectWizard : LanguageGeneratorNewProjectWizard {
    override val name: String = "Hytale Mod"
    override val icon: Icon = HytaleIcons.HYTALE
    override val ordinal: Int = 100

    override fun createStep(parent: NewProjectWizardStep): NewProjectWizardStep {
        return HytaleProjectWizardStep(parent)
    }
}

class HytaleProjectWizardStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {

    // Fields
    private val modNameField = JBTextField("MyHytaleMod")
    private val modIdField = JBTextField("myhytalemod")
    private val packageNameField = JBTextField("com.example.myhytalemod")
    private val commandNameField = JBTextField("mhm")
    private val authorField = JBTextField(System.getProperty("user.name") ?: "Author")
    private val descriptionArea = JBTextArea("A Hytale server mod", 3, 30)
    private val versionField = JBTextField("1.0.0")

    // Template selection
    private var selectedTemplate = HytaleModuleBuilder.TemplateType.FULL

    // Language and Build System
    private var selectedLanguage = "Java"
    private var selectedBuildSystem = "Gradle"
    private lateinit var languageSegmentedButton: SegmentedButton<String>
    private lateinit var buildSystemSegmentedButton: SegmentedButton<String>

    // Game detection - mutable to allow manual selection
    private var hytaleInstallation = HytaleModuleBuilder.detectHytaleInstallation()
    private val copyFromGameCheckbox = JCheckBox("Copy server files automatically", hytaleInstallation != null)

    // Server panel reference for dynamic updates
    private var serverPanel: JPanel? = null
    private var serverStatusLabel: JBLabel? = null
    private var serverStatusIcon: JBLabel? = null

    // Track manual edits
    private var modIdManuallyEdited = false
    private var packageNameManuallyEdited = false
    private var commandNameManuallyEdited = false

    // Multi-step
    private var currentStep = 0
    private lateinit var cardLayout: CardLayout
    private lateinit var cardPanel: JPanel
    private lateinit var stepIndicators: List<JPanel>
    private lateinit var stepLabels: List<JBLabel>
    private lateinit var prevButton: JButton
    private lateinit var nextButton: JButton

    // Template cards
    private lateinit var emptyCard: JPanel
    private lateinit var fullCard: JPanel

    private var mainPanel: JPanel? = null

    init {
        setupAutoCompletion()
        copyFromGameCheckbox.isEnabled = hytaleInstallation != null
    }

    private fun setupAutoCompletion() {
        modNameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateDerivedFields()
            override fun removeUpdate(e: DocumentEvent) = updateDerivedFields()
            override fun changedUpdate(e: DocumentEvent) = updateDerivedFields()
        })

        modIdField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { if (!isUpdatingModId) modIdManuallyEdited = true }
            override fun removeUpdate(e: DocumentEvent) { if (!isUpdatingModId) modIdManuallyEdited = true }
            override fun changedUpdate(e: DocumentEvent) { if (!isUpdatingModId) modIdManuallyEdited = true }
        })

        packageNameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { if (!isUpdatingPackageName) packageNameManuallyEdited = true }
            override fun removeUpdate(e: DocumentEvent) { if (!isUpdatingPackageName) packageNameManuallyEdited = true }
            override fun changedUpdate(e: DocumentEvent) { if (!isUpdatingPackageName) packageNameManuallyEdited = true }
        })

        commandNameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { if (!isUpdatingCommandName) commandNameManuallyEdited = true }
            override fun removeUpdate(e: DocumentEvent) { if (!isUpdatingCommandName) commandNameManuallyEdited = true }
            override fun changedUpdate(e: DocumentEvent) { if (!isUpdatingCommandName) commandNameManuallyEdited = true }
        })
    }

    // Flags to prevent DocumentListener from marking programmatic changes as manual edits
    private var isUpdatingModId = false
    private var isUpdatingPackageName = false
    private var isUpdatingCommandName = false

    private fun updateDerivedFields() {
        val name = modNameField.text.trim()

        if (!modIdManuallyEdited) {
            isUpdatingModId = true
            modIdField.text = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            isUpdatingModId = false
        }

        if (!packageNameManuallyEdited) {
            isUpdatingPackageName = true
            packageNameField.text = "com.example." + name.lowercase().replace(Regex("[^a-z0-9]"), "")
            isUpdatingPackageName = false
        }

        if (!commandNameManuallyEdited) {
            val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val abbrev = if (words.size > 1) words.map { it.first().lowercaseChar() }.joinToString("")
            else name.lowercase().replace(Regex("[^a-z]"), "").take(3)
            isUpdatingCommandName = true
            commandNameField.text = abbrev
            isUpdatingCommandName = false
        }
    }

    override fun setupUI(builder: com.intellij.ui.dsl.builder.Panel) {
        val panel = mainPanel ?: createMainPanel().also { mainPanel = it }
        builder.row {
            cell(panel).align(com.intellij.ui.dsl.builder.Align.FILL)
        }.resizableRow()
    }

    private fun createMainPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)
        panel.preferredSize = Dimension(500, 400)

        // Header with step indicators
        panel.add(createHeader(), BorderLayout.NORTH)

        // Card panel for steps
        cardLayout = CardLayout()
        cardPanel = JPanel(cardLayout)
        cardPanel.add(createStep1Panel(), "step1")
        cardPanel.add(createStep2Panel(), "step2")
        cardPanel.add(createStep3Panel(), "step3")
        panel.add(cardPanel, BorderLayout.CENTER)

        // Navigation buttons
        panel.add(createNavigationPanel(), BorderLayout.SOUTH)

        updateStepIndicators()
        return panel
    }

    private fun createHeader(): JPanel {
        val header = JPanel(BorderLayout())
        header.border = JBUI.Borders.emptyBottom(16)
        header.isOpaque = false

        // Logo and title on left
        val brandPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        brandPanel.isOpaque = false

        val iconLabel = JBLabel(HytaleIcons.HYTALE_LARGE)
        iconLabel.border = JBUI.Borders.emptyRight(12)
        brandPanel.add(iconLabel)

        val titlePanel = JPanel()
        titlePanel.layout = BoxLayout(titlePanel, BoxLayout.Y_AXIS)
        titlePanel.isOpaque = false

        val brandRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        brandRow.isOpaque = false
        val hytaleLabel = JBLabel("Hytale")
        hytaleLabel.font = hytaleLabel.font.deriveFont(Font.BOLD, 16f)
        hytaleLabel.foreground = JBColor(Color(50, 50, 50), Color(220, 220, 220))
        brandRow.add(hytaleLabel)
        val docsLabel = JBLabel("Docs")
        docsLabel.font = docsLabel.font.deriveFont(Font.BOLD, 16f)
        docsLabel.foreground = JBColor(Color(255, 140, 0), Color(255, 165, 50))
        brandRow.add(docsLabel)
        titlePanel.add(brandRow)

        val subtitleLabel = JBLabel("Dev Tools")
        subtitleLabel.font = subtitleLabel.font.deriveFont(11f)
        subtitleLabel.foreground = JBColor.GRAY
        titlePanel.add(subtitleLabel)

        brandPanel.add(titlePanel)
        header.add(brandPanel, BorderLayout.WEST)

        // Step indicators on right
        val stepsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        stepsPanel.isOpaque = false

        val stepNames = listOf("Template", "Info", "Finish")
        stepIndicators = mutableListOf()
        stepLabels = mutableListOf()

        for (i in stepNames.indices) {
            val stepPanel = JPanel(FlowLayout(FlowLayout.CENTER, 4, 0))
            stepPanel.isOpaque = false

            val circle = createStepCircle(i + 1)
            stepPanel.add(circle)
            (stepIndicators as MutableList).add(circle)

            val label = JBLabel(stepNames[i])
            label.font = label.font.deriveFont(11f)
            stepPanel.add(label)
            (stepLabels as MutableList).add(label)

            stepsPanel.add(stepPanel)

            if (i < stepNames.size - 1) {
                val dash = JBLabel("—")
                dash.foreground = JBColor(Color(180, 180, 180), Color(80, 80, 80))
                stepsPanel.add(dash)
            }
        }

        header.add(stepsPanel, BorderLayout.EAST)

        return header
    }

    private fun createStepCircle(number: Int): JPanel {
        return object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val isActive = (number - 1) <= currentStep
                val isCurrent = (number - 1) == currentStep

                // Circle
                g2.color = when {
                    isCurrent -> JBColor(Color(255, 140, 0), Color(255, 165, 50))
                    isActive -> JBColor(Color(100, 180, 100), Color(80, 160, 80))
                    else -> JBColor(Color(180, 180, 180), Color(80, 80, 80))
                }
                g2.fillOval(0, 0, 20, 20)

                // Number
                g2.color = Color.WHITE
                g2.font = font.deriveFont(Font.BOLD, 11f)
                val fm = g2.fontMetrics
                val text = number.toString()
                val x = (20 - fm.stringWidth(text)) / 2
                val y = (20 + fm.ascent - fm.descent) / 2
                g2.drawString(text, x, y)
            }

            init {
                preferredSize = Dimension(20, 20)
                isOpaque = false
            }
        }
    }

    private fun createStep1Panel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(8, 0)

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        content.alignmentX = Component.LEFT_ALIGNMENT

        // Title
        val titleLabel = JBLabel("Choose a Template")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 18f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        titleLabel.border = JBUI.Borders.emptyBottom(4)
        content.add(titleLabel)

        val subtitleLabel = JBLabel("Select the starting point for your mod")
        subtitleLabel.foreground = JBColor.GRAY
        subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
        subtitleLabel.border = JBUI.Borders.emptyBottom(20)
        content.add(subtitleLabel)

        // Template cards
        val cardsPanel = JPanel(GridLayout(1, 2, 16, 0))
        cardsPanel.isOpaque = false
        cardsPanel.alignmentX = Component.LEFT_ALIGNMENT
        cardsPanel.maximumSize = Dimension(500, 150)

        emptyCard = createTemplateCard(
            "Empty Mod",
            "Start from scratch with minimal code",
            listOf("Main class only", "Basic structure", "For experienced devs"),
            HytaleModuleBuilder.TemplateType.EMPTY
        )

        fullCard = createTemplateCard(
            "Full Template",
            "Complete starter with examples",
            listOf("Commands included", "Event listeners", "Custom UI example"),
            HytaleModuleBuilder.TemplateType.FULL
        )

        cardsPanel.add(emptyCard)
        cardsPanel.add(fullCard)
        content.add(cardsPanel)

        content.add(Box.createVerticalStrut(16))

        // Tip
        val tipPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        tipPanel.isOpaque = false
        tipPanel.alignmentX = Component.LEFT_ALIGNMENT
        val tipLabel = JBLabel("💡 Recommended: Full Template for beginners")
        tipLabel.font = tipLabel.font.deriveFont(12f)
        tipLabel.foreground = JBColor(Color(100, 140, 180), Color(120, 160, 200))
        tipPanel.add(tipLabel)
        content.add(tipPanel)

        content.add(Box.createVerticalGlue())

        panel.add(content, BorderLayout.NORTH)
        updateTemplateSelection()
        return panel
    }

    private fun createTemplateCard(title: String, subtitle: String, features: List<String>, type: HytaleModuleBuilder.TemplateType): JPanel {
        val card = JPanel()
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.border = JBUI.Borders.empty(16)
        card.background = JBColor(Color(250, 250, 252), Color(45, 48, 52))
        card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        titleLabel.foreground = JBColor(Color(40, 40, 40), Color(230, 230, 230))
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        card.add(titleLabel)

        card.add(Box.createVerticalStrut(4))

        val subtitleLabel = JBLabel(subtitle)
        subtitleLabel.font = subtitleLabel.font.deriveFont(11f)
        subtitleLabel.foreground = JBColor.GRAY
        subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
        card.add(subtitleLabel)

        card.add(Box.createVerticalStrut(12))

        for (feature in features) {
            val featureLabel = JBLabel("✓ $feature")
            featureLabel.font = featureLabel.font.deriveFont(11f)
            featureLabel.foreground = JBColor(Color(80, 80, 80), Color(160, 160, 160))
            featureLabel.alignmentX = Component.LEFT_ALIGNMENT
            card.add(featureLabel)
            card.add(Box.createVerticalStrut(2))
        }

        card.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                selectedTemplate = type
                updateTemplateSelection()
            }
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                if (selectedTemplate != type) {
                    card.background = JBColor(Color(245, 245, 250), Color(50, 53, 58))
                }
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                updateCardStyle(card, type)
            }
        })

        return card
    }

    private fun updateTemplateSelection() {
        updateCardStyle(emptyCard, HytaleModuleBuilder.TemplateType.EMPTY)
        updateCardStyle(fullCard, HytaleModuleBuilder.TemplateType.FULL)
    }

    private fun updateCardStyle(card: JPanel, type: HytaleModuleBuilder.TemplateType) {
        val isSelected = selectedTemplate == type
        card.background = if (isSelected) {
            JBColor(Color(255, 250, 240), Color(55, 50, 45))
        } else {
            JBColor(Color(250, 250, 252), Color(45, 48, 52))
        }
        card.border = if (isSelected) {
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(255, 140, 0), Color(255, 165, 50)), 2),
                JBUI.Borders.empty(14)
            )
        } else {
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(200, 200, 200), Color(60, 60, 60)), 1),
                JBUI.Borders.empty(15)
            )
        }
    }

    private fun createStep2Panel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(8, 0)

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        // Title
        val titleLabel = JBLabel("Mod Information")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 18f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        titleLabel.border = JBUI.Borders.emptyBottom(4)
        content.add(titleLabel)

        val subtitleLabel = JBLabel("Configure your mod's identity and build settings")
        subtitleLabel.foreground = JBColor.GRAY
        subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
        subtitleLabel.border = JBUI.Borders.emptyBottom(20)
        content.add(subtitleLabel)

        // Use IntelliJ DSL panel for the form with native segmented buttons
        val formPanel = panel {
            row("Mod Name:") {
                cell(modNameField)
                    .columns(25)
                    .comment("Names without spaces (e.g., 'MyCoolMod') enable hot reload support")
            }
            row("Package:") {
                cell(packageNameField)
                    .columns(25)
                    .comment("Java package name")
            }
            row("Language:") {
                languageSegmentedButton = segmentedButton(listOf("Kotlin", "Java")) { text = it }
                    .whenItemSelected { selectedLanguage = it }
                languageSegmentedButton.selectedItem = selectedLanguage
            }
            row("Build System:") {
                buildSystemSegmentedButton = segmentedButton(listOf("Gradle", "Maven")) { text = it }
                    .whenItemSelected { selectedBuildSystem = it }
                buildSystemSegmentedButton.selectedItem = selectedBuildSystem
            }
            row("Version:") {
                cell(versionField)
                    .columns(15)
                    .comment("Semantic version (e.g., 1.0.0)")
            }
            row("Command:") {
                cell(commandNameField)
                    .columns(10)
                    .comment("In-game command shortcut")
            }
        }
        formPanel.alignmentX = Component.LEFT_ALIGNMENT
        content.add(formPanel)
        content.add(Box.createVerticalGlue())

        panel.add(content, BorderLayout.NORTH)
        return panel
    }

    private fun createStep3Panel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(8, 0)

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        // Title
        val titleLabel = JBLabel("Finish Setup")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 18f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        titleLabel.border = JBUI.Borders.emptyBottom(4)
        content.add(titleLabel)

        val subtitleLabel = JBLabel("Add metadata and configure server files")
        subtitleLabel.foreground = JBColor.GRAY
        subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
        subtitleLabel.border = JBUI.Borders.emptyBottom(20)
        content.add(subtitleLabel)

        // Form
        val formPanel = JPanel(GridBagLayout())
        formPanel.isOpaque = false
        formPanel.alignmentX = Component.LEFT_ALIGNMENT
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(6, 0, 6, 12)
        gbc.anchor = GridBagConstraints.NORTHWEST

        // Author
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        formPanel.add(JBLabel("Author:").apply { preferredSize = Dimension(90, 28) }, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        authorField.preferredSize = Dimension(300, 28)
        formPanel.add(authorField, gbc)

        // Description
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        formPanel.add(JBLabel("Description:").apply { preferredSize = Dimension(90, 28) }, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        val scrollPane = JBScrollPane(descriptionArea)
        scrollPane.preferredSize = Dimension(300, 70)
        formPanel.add(scrollPane, gbc)

        content.add(formPanel)
        content.add(Box.createVerticalStrut(20))

        // Server detection
        content.add(createServerPanel())

        content.add(Box.createVerticalGlue())

        panel.add(content, BorderLayout.NORTH)
        return panel
    }

    private fun createServerPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.maximumSize = Dimension(450, 120)
        panel.border = JBUI.Borders.empty(12)
        serverPanel = panel

        updateServerPanelBackground()

        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = false

        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        statusRow.isOpaque = false
        statusRow.alignmentX = Component.LEFT_ALIGNMENT

        val detected = hytaleInstallation != null
        val statusIconChar = if (detected) "✓" else "○"
        val iconLabel = JBLabel(statusIconChar)
        iconLabel.font = iconLabel.font.deriveFont(Font.BOLD, 16f)
        serverStatusIcon = iconLabel
        updateServerStatusIconColor()
        statusRow.add(iconLabel)
        statusRow.add(Box.createHorizontalStrut(10))

        val textLabel = JBLabel(getServerStatusText())
        textLabel.font = textLabel.font.deriveFont(Font.BOLD, 13f)
        serverStatusLabel = textLabel
        statusRow.add(textLabel)

        contentPanel.add(statusRow)
        contentPanel.add(Box.createVerticalStrut(8))

        // Checkbox and Browse button row
        val controlsRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        controlsRow.isOpaque = false
        controlsRow.alignmentX = Component.LEFT_ALIGNMENT

        copyFromGameCheckbox.isOpaque = false
        controlsRow.add(copyFromGameCheckbox)

        controlsRow.add(Box.createHorizontalStrut(16))

        val browseButton = JButton("Browse...")
        browseButton.toolTipText = "Select Hytale installation directory manually"
        browseButton.addActionListener {
            browseForInstallation()
        }
        controlsRow.add(browseButton)

        contentPanel.add(controlsRow)

        // Path hint when installation is found
        hytaleInstallation?.let { installation ->
            contentPanel.add(Box.createVerticalStrut(6))
            val pathLabel = JBLabel(installation.basePath.toString())
            pathLabel.font = pathLabel.font.deriveFont(10f)
            pathLabel.foreground = JBColor.GRAY
            pathLabel.alignmentX = Component.LEFT_ALIGNMENT
            contentPanel.add(pathLabel)
        }

        panel.add(contentPanel, BorderLayout.CENTER)
        return panel
    }

    private fun browseForInstallation() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Hytale Installation Directory")
            .withDescription("Select the folder containing Server/HytaleServer.jar")

        val selectedFolder = FileChooser.chooseFile(descriptor, null, null)
        if (selectedFolder != null) {
            val customPath = selectedFolder.path
            val installation = HytaleModuleBuilder.fromCustomPath(customPath)

            if (installation != null) {
                hytaleInstallation = installation
                copyFromGameCheckbox.isEnabled = true
                copyFromGameCheckbox.isSelected = true

                // Save to application settings for future projects
                try {
                    val appSettings = HytaleAppSettings.getInstance()
                    appSettings.hytaleInstallationPath = customPath
                } catch (e: Exception) {
                    // Ignore if settings not available
                }

                updateServerStatus()
            } else {
                // Show error - invalid path
                JOptionPane.showMessageDialog(
                    serverPanel,
                    "The selected folder does not contain a valid Hytale installation.\n\n" +
                    "Expected structure:\n" +
                    "  <selected folder>/\n" +
                    "  └── Server/\n" +
                    "      └── HytaleServer.jar\n\n" +
                    "Please select the correct folder.",
                    "Invalid Installation Path",
                    JOptionPane.WARNING_MESSAGE
                )
            }
        }
    }

    private fun updateServerStatus() {
        updateServerPanelBackground()
        updateServerStatusIconColor()
        serverStatusLabel?.text = getServerStatusText()
        serverPanel?.revalidate()
        serverPanel?.repaint()
    }

    private fun getServerStatusText(): String {
        return hytaleInstallation?.let { installation ->
            val hasJar = installation.hasServerJar()
            val hasAssets = installation.hasAssets()
            buildString {
                append("Hytale detected")
                if (hasJar && hasAssets) append(" • Server + Assets")
                else if (hasJar) append(" • Server only")
                else if (hasAssets) append(" • Assets only")
            }
        } ?: "Hytale installation not found - click Browse to select"
    }

    private fun updateServerPanelBackground() {
        val detected = hytaleInstallation != null
        serverPanel?.background = if (detected) {
            JBColor(Color(240, 255, 245), Color(35, 55, 45))
        } else {
            JBColor(Color(255, 252, 240), Color(55, 50, 40))
        }
    }

    private fun updateServerStatusIconColor() {
        val detected = hytaleInstallation != null
        serverStatusIcon?.text = if (detected) "✓" else "○"
        serverStatusIcon?.foreground = if (detected) {
            JBColor(Color(40, 160, 80), Color(80, 200, 120))
        } else {
            JBColor(Color(180, 140, 60), Color(200, 160, 80))
        }
    }

    private fun createNavigationPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.emptyTop(16)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        buttonPanel.isOpaque = false

        prevButton = JButton("← Previous")
        prevButton.addActionListener { goToPreviousStep() }
        buttonPanel.add(prevButton)

        nextButton = JButton("Next →")
        nextButton.addActionListener { goToNextStep() }
        buttonPanel.add(nextButton)

        panel.add(buttonPanel, BorderLayout.WEST)

        updateNavigationButtons()
        return panel
    }

    private fun goToNextStep() {
        if (currentStep < 2) {
            currentStep++
            cardLayout.show(cardPanel, "step${currentStep + 1}")
            updateStepIndicators()
            updateNavigationButtons()
        }
    }

    private fun goToPreviousStep() {
        if (currentStep > 0) {
            currentStep--
            cardLayout.show(cardPanel, "step${currentStep + 1}")
            updateStepIndicators()
            updateNavigationButtons()
        }
    }

    private fun updateStepIndicators() {
        for (i in stepIndicators.indices) {
            stepIndicators[i].repaint()
            stepLabels[i].foreground = when {
                i == currentStep -> JBColor(Color(255, 140, 0), Color(255, 165, 50))
                i < currentStep -> JBColor(Color(100, 180, 100), Color(80, 160, 80))
                else -> JBColor.GRAY
            }
        }
    }

    private fun updateNavigationButtons() {
        prevButton.isEnabled = currentStep > 0
        nextButton.text = if (currentStep == 2) "Ready!" else "Next →"
        nextButton.isEnabled = currentStep < 2
    }

    override fun setupProject(project: Project) {
        val builder = HytaleModuleBuilder()
        builder.modName = modNameField.text.trim()
        builder.modId = modIdField.text.trim()
        builder.packageName = packageNameField.text.trim()
        builder.commandName = commandNameField.text.trim().lowercase()
        builder.author = authorField.text.trim()
        builder.modDescription = descriptionArea.text.trim()
        builder.version = versionField.text.trim()
        builder.templateType = selectedTemplate
        builder.language = selectedLanguage
        builder.buildSystem = selectedBuildSystem
        builder.copyFromGame = copyFromGameCheckbox.isSelected
        builder.hytaleInstallation = hytaleInstallation

        val projectPath = context.projectDirectory?.toString() ?: return
        builder.createProjectAtPath(projectPath)
    }
}
