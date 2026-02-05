package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.HytaleBundle
import com.hytaledocs.intellij.run.HytaleServerRunConfiguration
import com.hytaledocs.intellij.services.ServerLaunchService
import com.hytaledocs.intellij.services.ServerProfile
import com.hytaledocs.intellij.services.ServerProfileManager
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.execution.RunManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.*
import java.nio.file.Paths
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Dialog for managing all server profiles.
 * Displays a table of profiles with Add, Edit, Delete, Duplicate, and Import buttons.
 */
class ServerManagerDialog(private val project: Project) : DialogWrapper(project, true) {

    private val profileManager = ServerProfileManager.getInstance()
    private val launchService = ServerLaunchService.getInstance(project)
    private val tableModel = ProfileTableModel()
    private val table: JBTable

    init {
        title = "Manage Server Profiles"
        setSize(700, 450)

        table = JBTable(tableModel).apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = JBUI.scale(36)
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

            // Column widths
            columnModel.getColumn(0).preferredWidth = 150  // Name
            columnModel.getColumn(1).preferredWidth = 300  // Path
            columnModel.getColumn(2).preferredWidth = 60   // Port
            columnModel.getColumn(3).preferredWidth = 80   // Status

            // Custom renderers
            columnModel.getColumn(3).cellRenderer = StatusCellRenderer()
        }

        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(680, 400)

        // Description
        val descLabel = JBLabel("Configure multiple Hytale server profiles to quickly switch between different server setups.")
        descLabel.foreground = HytaleTheme.mutedText
        descLabel.border = JBUI.Borders.empty(0, 0, 12, 0)
        panel.add(descLabel, BorderLayout.NORTH)

        // Table with toolbar
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { addProfile() }
            .setRemoveAction { deleteProfile() }
            .setEditAction { editProfile() }
            .addExtraAction(object : ToolbarDecorator.ElementActionButton("Duplicate", AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) {
                    duplicateProfile()
                }

                override fun isEnabled(): Boolean {
                    return table.selectedRow >= 0
                }
            })
            .addExtraAction(object : ToolbarDecorator.ElementActionButton("Import from Run Config", AllIcons.Actions.Download) {
                override fun actionPerformed(e: AnActionEvent) {
                    importFromRunConfig()
                }
            })

        val tablePanel = decorator.createPanel()
        tablePanel.border = JBUI.Borders.empty()
        panel.add(tablePanel, BorderLayout.CENTER)

        // Double-click to edit
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && table.selectedRow >= 0) {
                    editProfile()
                }
            }
        })

        return panel
    }

    private fun addProfile() {
        val options = arrayOf("Create New", "Browse for Server Folder")
        val choice = Messages.showDialog(
            project,
            "How would you like to add a server profile?",
            "Add Server Profile",
            options,
            0,
            Messages.getQuestionIcon()
        )

        when (choice) {
            0 -> createNewProfile()
            1 -> browseForServer()
        }
    }

    private fun createNewProfile() {
        val dialog = ServerProfileEditorDialog(project, null)
        if (dialog.showAndGet()) {
            val profile = dialog.getProfile()
            profileManager.addProfile(profile)
            tableModel.fireTableDataChanged()
        }
    }

    private fun browseForServer() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Hytale Server Directory")
            .withDescription("Choose a folder containing HytaleServer.jar")

        FileChooser.chooseFile(descriptor, project, null) { virtualFile ->
            val path = Paths.get(virtualFile.path)
            val name = virtualFile.name

            if (profileManager.isValidServerDirectory(path)) {
                // Valid server - prompt for name
                val profileName = Messages.showInputDialog(
                    project,
                    "Enter a name for this server profile:",
                    "Server Profile Name",
                    null,
                    name,
                    null
                )

                if (profileName != null && profileName.isNotBlank()) {
                    profileManager.importFromDirectory(path.toString(), profileName)
                    tableModel.fireTableDataChanged()
                }
            } else {
                // Not valid - ask to add anyway
                val result = Messages.showYesNoDialog(
                    project,
                    "No HytaleServer.jar found in this directory.\n" +
                            "Do you want to add it anyway? You can download server files later.",
                    "Server Not Found",
                    Messages.getWarningIcon()
                )

                if (result == Messages.YES) {
                    val profileName = Messages.showInputDialog(
                        project,
                        "Enter a name for this server profile:",
                        "Server Profile Name",
                        null,
                        name,
                        null
                    )

                    if (profileName != null && profileName.isNotBlank()) {
                        val profile = ServerProfile(
                            name = profileName,
                            path = path.toString()
                        )
                        profileManager.addProfile(profile)
                        tableModel.fireTableDataChanged()
                    }
                }
            }
        }
    }

    private fun editProfile() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val profile = tableModel.getProfileAt(selectedRow) ?: return

        val dialog = ServerProfileEditorDialog(project, profile)
        if (dialog.showAndGet()) {
            profileManager.updateProfile(dialog.getProfile())
            tableModel.fireTableDataChanged()
        }
    }

    private fun deleteProfile() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val profile = tableModel.getProfileAt(selectedRow) ?: return

        val result = Messages.showYesNoDialog(
            project,
            "Are you sure you want to delete the server profile '${profile.name}'?\n\n" +
                    "This will not delete any server files, only the profile configuration.",
            "Delete Profile",
            Messages.getWarningIcon()
        )

        if (result == Messages.YES) {
            profileManager.deleteProfile(profile.id)
            tableModel.fireTableDataChanged()
        }
    }

    private fun duplicateProfile() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val profile = tableModel.getProfileAt(selectedRow) ?: return

        val newName = Messages.showInputDialog(
            project,
            "Enter a name for the duplicated profile:",
            "Duplicate Profile",
            null,
            "${profile.name} (Copy)",
            null
        )

        if (newName != null && newName.isNotBlank()) {
            profileManager.duplicateProfile(profile.id, newName)
            tableModel.fireTableDataChanged()
        }
    }

    private fun importFromRunConfig() {
        val runManager = RunManager.getInstance(project)
        val hytaleConfigs = runManager.allConfigurationsList
            .filterIsInstance<HytaleServerRunConfiguration>()

        if (hytaleConfigs.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No Hytale Server run configurations found.\n\n" +
                        "Create a run configuration first using 'Run > Edit Configurations...'",
                "No Configurations Found"
            )
            return
        }

        // Show selection dialog if multiple configs
        val configNames = hytaleConfigs.map { it.name }.toTypedArray()
        val selected = if (configNames.size == 1) {
            configNames[0]
        } else {
            Messages.showEditableChooseDialog(
                "Select a run configuration to import:",
                "Import Run Configuration",
                Messages.getQuestionIcon(),
                configNames,
                configNames[0],
                null
            )
        }

        if (selected == null) return

        val config = hytaleConfigs.find { it.name == selected } ?: return

        val profileName = Messages.showInputDialog(
            project,
            "Enter a name for the imported profile:",
            "Import Profile",
            null,
            config.name,
            null
        )

        if (profileName != null && profileName.isNotBlank()) {
            val profile = ServerProfile(
                name = profileName,
                path = config.serverPath,
                javaPath = config.javaPath,
                port = config.port,
                minMemory = config.minMemory,
                maxMemory = config.maxMemory,
                authMode = config.authMode,
                allowOp = config.allowOp,
                acceptEarlyPlugins = config.acceptEarlyPlugins,
                jvmArgs = config.jvmArgs,
                serverArgs = config.serverArgs
            )
            profileManager.addProfile(profile)
            tableModel.fireTableDataChanged()

            Messages.showInfoMessage(
                project,
                "Successfully imported settings from '${config.name}'",
                "Import Complete"
            )
        }
    }

    /**
     * Table model for displaying server profiles.
     */
    private inner class ProfileTableModel : AbstractTableModel() {
        private val columnNames = arrayOf("Name", "Path", "Port", "Status")

        override fun getRowCount(): Int = profileManager.getAllProfiles().size

        override fun getColumnCount(): Int = columnNames.size

        override fun getColumnName(column: Int): String = columnNames[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val profiles = profileManager.getAllProfiles()
            if (rowIndex >= profiles.size) return null

            val profile = profiles[rowIndex]
            return when (columnIndex) {
                0 -> profile.name
                1 -> profile.getDisplayPath()
                2 -> profile.port
                3 -> {
                    // Get status from launch service if this is the active profile
                    val activeId = profileManager.getActiveProfileId()
                    if (profile.id == activeId) {
                        launchService.getStatus()
                    } else {
                        ServerLaunchService.ServerStatus.STOPPED
                    }
                }
                else -> null
            }
        }

        fun getProfileAt(rowIndex: Int): ServerProfile? {
            val profiles = profileManager.getAllProfiles()
            return if (rowIndex in profiles.indices) profiles[rowIndex] else null
        }
    }

    /**
     * Custom renderer for the status column.
     */
    private class StatusCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

            if (component is JLabel && value is ServerLaunchService.ServerStatus) {
                val (text, color) = when (value) {
                    ServerLaunchService.ServerStatus.STOPPED -> "Stopped" to HytaleTheme.mutedText
                    ServerLaunchService.ServerStatus.STARTING -> "Starting" to HytaleTheme.warningColor
                    ServerLaunchService.ServerStatus.RUNNING -> "Running" to HytaleTheme.successColor
                    ServerLaunchService.ServerStatus.STOPPING -> "Stopping" to HytaleTheme.warningColor
                    ServerLaunchService.ServerStatus.ERROR -> "Error" to HytaleTheme.errorColor
                }

                component.text = text
                if (!isSelected) {
                    component.foreground = color
                }
                component.font = component.font.deriveFont(Font.BOLD)
            }

            return component
        }
    }
}
