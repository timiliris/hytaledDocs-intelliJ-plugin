package com.hytaledocs.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Shared theme colors and UI factory methods for Hytale IntelliJ plugin.
 * Provides consistent styling across all plugin panels.
 */
object HytaleTheme {

    // ==================== COLORS ====================
    // All colors use JBColor.namedColor() to respect IDE themes

    /** Background color for card components */
    val cardBackground: JBColor = JBColor.namedColor(
        "Panel.background",
        JBColor.PanelBackground
    )

    /** Border color for card components */
    val cardBorder: JBColor = JBColor.namedColor(
        "Component.borderColor",
        JBColor.border()
    )

    /** Primary accent color (blue) - uses IDE link color */
    val accentColor: JBColor = JBColor.namedColor(
        "Link.activeForeground",
        JBColor.namedColor("Link.pressedForeground", JBColor.blue)
    )

    /** Success/positive state color (green) */
    val successColor: JBColor = JBColor.namedColor(
        "Label.successForeground",
        JBColor.namedColor("Objects.Green", JBColor(0x59A869, 0x499C54))
    )

    /** Warning state color (yellow/amber) */
    val warningColor: JBColor = JBColor.namedColor(
        "Label.warningForeground",
        JBColor.namedColor("Objects.Yellow", JBColor(0xE5A50A, 0xD9A343))
    )

    /** Error/negative state color (red) */
    val errorColor: JBColor = JBColor.namedColor(
        "Label.errorForeground",
        JBColor.namedColor("Objects.Red", JBColor(0xDB5860, 0xC75450))
    )

    /** Muted/secondary text color */
    val mutedText: JBColor = JBColor.namedColor(
        "Label.infoForeground",
        JBColor.namedColor("Label.disabledForeground", JBColor.gray)
    )

    /** Primary text color */
    val textPrimary: JBColor = JBColor.namedColor(
        "Label.foreground",
        JBColor.foreground()
    )

    /** Purple accent color (used for code/template highlights) */
    val purpleAccent: JBColor = JBColor.namedColor(
        "Plugins.tagForeground",
        JBColor.namedColor("Objects.Purple", JBColor(0xA855F7, 0xC084FC))
    )

    // ==================== FACTORY METHODS ====================

    /**
     * Creates a standard IntelliJ button.
     *
     * @param text Button label text
     * @param icon Optional icon to display
     * @return Configured JButton instance with native IntelliJ styling
     */
    @JvmStatic
    @JvmOverloads
    fun createButton(text: String, icon: Icon? = null): JButton {
        return JButton(text, icon)
    }

    /**
     * Creates a card panel with rounded border and optional title.
     *
     * @param title Optional section title displayed at top of card
     * @return Configured JPanel styled as a card
     */
    @JvmStatic
    @JvmOverloads
    fun createCard(title: String? = null): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = cardBackground
            border = BorderFactory.createCompoundBorder(
                RoundedLineBorder(cardBorder, 8, 1),
                JBUI.Borders.empty(12)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            if (title != null) {
                add(createSectionTitle(title))
                add(Box.createVerticalStrut(JBUI.scale(8)))
            }
        }
    }

    /**
     * Creates a bold section title label.
     *
     * @param text The title text
     * @return Configured JLabel for section headers
     */
    @JvmStatic
    fun createSectionTitle(text: String): JLabel {
        return JLabel(text).apply {
            font = font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(13f))
            foreground = textPrimary
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    /**
     * Creates a clickable link row with icon, title, description, and URL.
     *
     * @param title Link title (displayed in accent color)
     * @param description Secondary description text
     * @param url URL to open when clicked
     * @return Configured JPanel as a clickable link row
     */
    @JvmStatic
    fun createLinkRow(title: String, description: String, url: String): JPanel {
        val row = JPanel(BorderLayout(JBUI.scale(8), 0))
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val iconLabel = JLabel(AllIcons.Ide.External_link_arrow)
        row.add(iconLabel, BorderLayout.WEST)

        val textPanel = JPanel(BorderLayout())
        textPanel.isOpaque = false

        val titleLabel = JLabel(title)
        titleLabel.foreground = accentColor
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        textPanel.add(titleLabel, BorderLayout.NORTH)

        val descLabel = JLabel(description)
        descLabel.foreground = mutedText
        descLabel.font = descLabel.font.deriveFont(JBUI.scaleFontSize(11f))
        textPanel.add(descLabel, BorderLayout.SOUTH)

        row.add(textPanel, BorderLayout.CENTER)

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                BrowserUtil.browse(url)
            }

            override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                titleLabel.foreground = accentColor.brighter()
            }

            override fun mouseExited(e: java.awt.event.MouseEvent?) {
                titleLabel.foreground = accentColor
            }
        })

        return row
    }
}
