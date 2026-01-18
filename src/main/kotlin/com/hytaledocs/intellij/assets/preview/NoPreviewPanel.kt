package com.hytaledocs.intellij.assets.preview

import com.hytaledocs.intellij.assets.AssetNode
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Panel displayed when no file is selected or when no preview is available.
 * Shows file information when a file is selected.
 */
class NoPreviewPanel : JBPanel<NoPreviewPanel>(BorderLayout()) {

    private val iconLabel = JBLabel()
    private val titleLabel = JBLabel("Select an asset")
    private val subtitleLabel = JBLabel("Choose a file from the tree to preview")
    private val detailsPanel = JPanel(GridBagLayout())

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.empty(24)

        val centerPanel = JPanel()
        centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)
        centerPanel.isOpaque = false

        // Icon
        iconLabel.icon = AllIcons.FileTypes.Any_type
        iconLabel.alignmentX = CENTER_ALIGNMENT
        centerPanel.add(iconLabel)
        centerPanel.add(Box.createVerticalStrut(JBUI.scale(16)))

        // Title
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(16f))
        titleLabel.foreground = HytaleTheme.textPrimary
        titleLabel.alignmentX = CENTER_ALIGNMENT
        centerPanel.add(titleLabel)
        centerPanel.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Subtitle
        subtitleLabel.foreground = HytaleTheme.mutedText
        subtitleLabel.alignmentX = CENTER_ALIGNMENT
        centerPanel.add(subtitleLabel)
        centerPanel.add(Box.createVerticalStrut(JBUI.scale(24)))

        // Details panel (hidden by default)
        detailsPanel.isOpaque = false
        detailsPanel.alignmentX = CENTER_ALIGNMENT
        detailsPanel.isVisible = false
        centerPanel.add(detailsPanel)

        add(centerPanel, BorderLayout.CENTER)
    }

    /**
     * Show the default "no selection" state.
     */
    fun showNoSelection() {
        iconLabel.icon = AllIcons.FileTypes.Any_type
        titleLabel.text = "Select an asset"
        subtitleLabel.text = "Choose a file from the tree to preview"
        detailsPanel.isVisible = false
    }

    /**
     * Show file info for a file that can't be previewed.
     */
    fun showFileInfo(file: AssetNode.FileNode) {
        iconLabel.icon = file.assetType.icon
        titleLabel.text = file.displayName
        subtitleLabel.text = "Preview not available for this file type"

        // Build details panel
        detailsPanel.removeAll()
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4)
        }

        // Type row
        gbc.gridx = 0; gbc.gridy = 0
        detailsPanel.add(createDetailLabel("Type:"), gbc)
        gbc.gridx = 1
        detailsPanel.add(createValueLabel(file.assetType.displayName), gbc)

        // Size row
        gbc.gridx = 0; gbc.gridy = 1
        detailsPanel.add(createDetailLabel("Size:"), gbc)
        gbc.gridx = 1
        detailsPanel.add(createValueLabel(file.sizeFormatted), gbc)

        // Extension row
        gbc.gridx = 0; gbc.gridy = 2
        detailsPanel.add(createDetailLabel("Extension:"), gbc)
        gbc.gridx = 1
        detailsPanel.add(createValueLabel(".${file.extension}"), gbc)

        // Path row
        gbc.gridx = 0; gbc.gridy = 3
        detailsPanel.add(createDetailLabel("Path:"), gbc)
        gbc.gridx = 1
        detailsPanel.add(createValueLabel(file.relativePath), gbc)

        // Source row (if in ZIP)
        if (file.isInZip && file.zipSource != null) {
            gbc.gridx = 0; gbc.gridy = 4
            detailsPanel.add(createDetailLabel("Source:"), gbc)
            gbc.gridx = 1
            detailsPanel.add(createValueLabel("ZIP: ${file.zipSource!!.zipFile.name}"), gbc)
        }

        detailsPanel.isVisible = true
        detailsPanel.revalidate()
        detailsPanel.repaint()
    }

    private fun createDetailLabel(text: String): JLabel {
        return JLabel(text).apply {
            foreground = HytaleTheme.mutedText
            font = font.deriveFont(Font.PLAIN)
        }
    }

    private fun createValueLabel(text: String): JLabel {
        return JLabel(text).apply {
            foreground = HytaleTheme.textPrimary
            font = font.deriveFont(Font.BOLD)
        }
    }
}
