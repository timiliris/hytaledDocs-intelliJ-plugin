package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.services.ServerLaunchService
import com.hytaledocs.intellij.services.ServerProfile
import com.hytaledocs.intellij.services.ServerProfileListener
import com.hytaledocs.intellij.services.ServerProfileManager
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ActionListener
import java.nio.file.Paths
import javax.swing.*

/**
 * Server selector panel that displays a dropdown of server profiles with controls.
 * Includes:
 * - ComboBox for server selection
 * - Add server button (+)
 * - Browse folder button
 * - Settings/gear button to open ServerManagerDialog
 * - Status indicator dot
 */
class ServerSelectorPanel(
    private val project: Project,
    private val onSelectionChanged: (ServerProfile?) -> Unit
) : JBPanel<ServerSelectorPanel>(BorderLayout()), Disposable {

    private val profileManager = ServerProfileManager.getInstance()
    private val launchService = ServerLaunchService.getInstance(project)

    private val profileComboBox: ComboBox<ProfileItem>
    private val statusIndicator: StatusIndicatorPanel
    private var isUpdating = false

    private val profileListener = object : ServerProfileListener {
        override fun onProfileAdded(profile: ServerProfile) {
            SwingUtilities.invokeLater { refreshProfiles() }
        }

        override fun onProfileUpdated(profile: ServerProfile) {
            SwingUtilities.invokeLater { refreshProfiles() }
        }

        override fun onProfileDeleted(profileId: String) {
            SwingUtilities.invokeLater { refreshProfiles() }
        }

        override fun onActiveProfileChanged(profile: ServerProfile?) {
            SwingUtilities.invokeLater {
                selectProfileInCombo(profile?.id)
                onSelectionChanged(profile)
            }
        }
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 8, 0)

        // Create main content panel
        val contentPanel = JPanel(BorderLayout(JBUI.scale(8), 0))
        contentPanel.isOpaque = false

        // Status indicator (left side)
        statusIndicator = StatusIndicatorPanel()
        contentPanel.add(statusIndicator, BorderLayout.WEST)

        // Server dropdown (center)
        profileComboBox = ComboBox<ProfileItem>().apply {
            renderer = ProfileListCellRenderer()
            addActionListener {
                if (!isUpdating) {
                    val selected = selectedItem as? ProfileItem
                    if (selected != null) {
                        profileManager.setActiveProfile(selected.profile.id)
                    }
                }
            }
        }
        contentPanel.add(profileComboBox, BorderLayout.CENTER)

        // Buttons panel (right side)
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0))
        buttonsPanel.isOpaque = false

        // Add button (+)
        val addButton = createIconButton(AllIcons.General.Add, "Add new server profile")
        addButton.addActionListener { showAddProfileDialog() }
        buttonsPanel.add(addButton)

        // Browse folder button
        val browseButton = createIconButton(AllIcons.Actions.MenuOpen, "Browse and add server folder")
        browseButton.addActionListener { browseForServer() }
        buttonsPanel.add(browseButton)

        // Settings/gear button
        val settingsButton = createIconButton(AllIcons.General.Settings, "Manage server profiles")
        settingsButton.addActionListener { openServerManager() }
        buttonsPanel.add(settingsButton)

        contentPanel.add(buttonsPanel, BorderLayout.EAST)

        add(contentPanel, BorderLayout.CENTER)

        // Register listener
        profileManager.addListener(profileListener)

        // Auto-detect server in project if no profiles exist
        autoDetectProjectServer()

        // Initialize profiles
        refreshProfiles()
        updateStatusIndicator()
    }

    /**
     * Auto-detect a server directory in the current project and create a default profile.
     */
    private fun autoDetectProjectServer() {
        // Only auto-detect if no profiles exist
        if (profileManager.getAllProfiles().isNotEmpty()) return

        val projectPath = project.basePath ?: return

        // Common server directory names to check
        val serverDirNames = listOf("server", "Server", "hytale-server", "hytale")

        for (dirName in serverDirNames) {
            val serverDir = Paths.get(projectPath, dirName)
            if (profileManager.isValidServerDirectory(serverDir)) {
                // Found a valid server directory - create default profile
                val profile = profileManager.importFromDirectory(
                    serverDir.toString(),
                    "${project.name} Server"
                )
                if (profile != null) {
                    profileManager.setActiveProfile(profile.id)
                }
                return
            }
        }

        // Check if project root itself is a server directory
        val projectRoot = Paths.get(projectPath)
        if (profileManager.isValidServerDirectory(projectRoot)) {
            val profile = profileManager.importFromDirectory(
                projectRoot.toString(),
                project.name
            )
            if (profile != null) {
                profileManager.setActiveProfile(profile.id)
            }
        }
    }

    private fun createIconButton(icon: Icon, tooltip: String): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    private fun refreshProfiles() {
        isUpdating = true
        try {
            profileComboBox.removeAllItems()

            val profiles = profileManager.getAllProfiles()
            if (profiles.isEmpty()) {
                // Show placeholder
                profileComboBox.addItem(ProfileItem(
                    ServerProfile(name = "No servers configured"),
                    isPlaceholder = true
                ))
            } else {
                profiles.forEach { profile ->
                    profileComboBox.addItem(ProfileItem(profile))
                }

                // Select active profile, or first profile if none is active
                var activeId = profileManager.getActiveProfileId()
                if (activeId == null && profiles.isNotEmpty()) {
                    // No active profile set - auto-select the first one
                    activeId = profiles.first().id
                    profileManager.setActiveProfile(activeId)
                }
                selectProfileInCombo(activeId)
            }
        } finally {
            isUpdating = false
        }
    }

    private fun selectProfileInCombo(profileId: String?) {
        if (profileId == null) return

        isUpdating = true
        try {
            for (i in 0 until profileComboBox.itemCount) {
                val item = profileComboBox.getItemAt(i)
                if (!item.isPlaceholder && item.profile.id == profileId) {
                    profileComboBox.selectedIndex = i
                    break
                }
            }
        } finally {
            isUpdating = false
        }
    }

    private fun updateStatusIndicator() {
        val status = launchService.getStatus()
        statusIndicator.setStatus(status)
    }

    /**
     * Update the status based on the actual server launch service status.
     * Call this from the parent panel's status update logic.
     */
    fun updateStatusFromLaunchService() {
        updateStatusIndicator()
    }

    private fun showAddProfileDialog() {
        val dialog = ServerProfileEditorDialog(project, null)
        if (dialog.showAndGet()) {
            val profile = dialog.getProfile()
            profileManager.addProfile(profile)
            profileManager.setActiveProfile(profile.id)
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
                // Valid server directory - create profile via import
                val profile = profileManager.importFromDirectory(path.toString(), name)
                if (profile != null) {
                    profileManager.setActiveProfile(profile.id)
                }
            } else {
                // Not a valid server directory - ask if user wants to add anyway
                val result = JOptionPane.showConfirmDialog(
                    this,
                    "No HytaleServer.jar found in this directory.\n" +
                            "Do you want to add it anyway?\n\n" +
                            "You can download server files later.",
                    "Server Not Found",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                )

                if (result == JOptionPane.YES_OPTION) {
                    val profile = ServerProfile(
                        name = name,
                        path = path.toString()
                    )
                    profileManager.addProfile(profile)
                    profileManager.setActiveProfile(profile.id)
                }
            }
        }
    }

    private fun openServerManager() {
        val dialog = ServerManagerDialog(project)
        dialog.show()
    }

    /**
     * Get the currently selected profile.
     */
    fun getSelectedProfile(): ServerProfile? {
        val item = profileComboBox.selectedItem as? ProfileItem
        return if (item?.isPlaceholder == true) null else item?.profile
    }

    override fun dispose() {
        profileManager.removeListener(profileListener)
    }

    /**
     * Wrapper class for profile items in the combo box.
     */
    private data class ProfileItem(
        val profile: ServerProfile,
        val isPlaceholder: Boolean = false
    ) {
        override fun toString(): String = profile.name
    }

    /**
     * Custom cell renderer for the profile dropdown.
     */
    private inner class ProfileListCellRenderer : ListCellRenderer<ProfileItem> {
        private val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        private val nameLabel = JLabel()
        private val pathLabel = JLabel()

        init {
            panel.isOpaque = true
            panel.border = JBUI.Borders.empty(4, 8)

            val textPanel = JPanel(BorderLayout())
            textPanel.isOpaque = false
            textPanel.add(nameLabel, BorderLayout.NORTH)
            textPanel.add(pathLabel, BorderLayout.SOUTH)

            panel.add(textPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out ProfileItem>?,
            value: ProfileItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value == null) {
                nameLabel.text = ""
                pathLabel.text = ""
                return panel
            }

            if (value.isPlaceholder) {
                nameLabel.text = value.profile.name
                nameLabel.foreground = HytaleTheme.mutedText
                nameLabel.font = nameLabel.font.deriveFont(Font.ITALIC)
                pathLabel.text = "Click + to add a server"
                pathLabel.foreground = HytaleTheme.mutedText
                pathLabel.font = pathLabel.font.deriveFont(JBUI.scaleFontSize(10f))
            } else {
                nameLabel.text = value.profile.name
                nameLabel.foreground = if (isSelected) list?.selectionForeground else HytaleTheme.textPrimary
                nameLabel.font = nameLabel.font.deriveFont(Font.BOLD)

                pathLabel.text = value.profile.getDisplayPath()
                pathLabel.foreground = HytaleTheme.mutedText
                pathLabel.font = pathLabel.font.deriveFont(JBUI.scaleFontSize(10f))
            }

            panel.background = if (isSelected) {
                list?.selectionBackground ?: HytaleTheme.accentColor
            } else {
                list?.background ?: HytaleTheme.cardBackground
            }

            return panel
        }
    }

    /**
     * Status indicator dot panel.
     */
    private inner class StatusIndicatorPanel : JPanel() {
        private var status: ServerLaunchService.ServerStatus = ServerLaunchService.ServerStatus.STOPPED

        init {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
            toolTipText = "Server stopped"
        }

        fun setStatus(newStatus: ServerLaunchService.ServerStatus) {
            status = newStatus
            toolTipText = when (status) {
                ServerLaunchService.ServerStatus.STOPPED -> "Server stopped"
                ServerLaunchService.ServerStatus.STARTING -> "Server starting..."
                ServerLaunchService.ServerStatus.RUNNING -> "Server running"
                ServerLaunchService.ServerStatus.STOPPING -> "Server stopping..."
                ServerLaunchService.ServerStatus.ERROR -> "Server error"
            }
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val size = JBUI.scale(10)
            val x = (width - size) / 2
            val y = (height - size) / 2

            // Draw status dot
            val color = when (status) {
                ServerLaunchService.ServerStatus.STOPPED -> HytaleTheme.mutedText
                ServerLaunchService.ServerStatus.STARTING, ServerLaunchService.ServerStatus.STOPPING -> HytaleTheme.warningColor
                ServerLaunchService.ServerStatus.RUNNING -> HytaleTheme.successColor
                ServerLaunchService.ServerStatus.ERROR -> HytaleTheme.errorColor
            }

            g2.color = color
            g2.fillOval(x, y, size, size)

            // Draw border
            g2.color = color.darker()
            g2.drawOval(x, y, size, size)
        }
    }
}
