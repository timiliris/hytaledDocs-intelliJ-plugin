package com.hytaledocs.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * Shared theme colors and UI factory methods for Hytale IntelliJ plugin.
 * Provides consistent styling across all plugin panels.
 */
object HytaleTheme {

    // ==================== COLORS ====================

    /** Background color for card components */
    val cardBackground: JBColor = JBColor.namedColor(
        "ToolWindow.HeaderTab.selectedInactiveBackground",
        JBColor(0xF7F8FA, 0x2B2D30)
    )

    /** Border color for card components */
    val cardBorder: JBColor = JBColor.namedColor(
        "Component.borderColor",
        JBColor(0xD1D5DB, 0x43454A)
    )

    /** Primary accent color (blue) */
    val accentColor: JBColor = JBColor(0x3574F0, 0x4A8EE6)

    /** Success/positive state color (green) */
    val successColor: JBColor = JBColor(0x59A869, 0x499C54)

    /** Warning state color (yellow/amber) */
    val warningColor: JBColor = JBColor(0xE5A50A, 0xD9A343)

    /** Error/negative state color (red) */
    val errorColor: JBColor = JBColor(0xDB5860, 0xC75450)

    /** Muted/secondary text color */
    val mutedText: JBColor = JBColor.namedColor(
        "Label.disabledForeground",
        JBColor(0x6B7280, 0x9DA0A8)
    )

    /** Primary text color */
    val textPrimary: JBColor = JBColor.namedColor(
        "Label.foreground",
        JBColor.foreground()
    )

    /** Purple accent color (used for code/template highlights) */
    val purpleAccent: JBColor = JBColor(0xA855F7, 0xC084FC)

    // ==================== FACTORY METHODS ====================

    /**
     * Creates a modern styled button with optional color.
     *
     * @param text Button label text
     * @param icon Optional icon to display
     * @param buttonColor Optional background color (makes button filled/colored)
     * @return Configured JButton instance
     */
    @JvmStatic
    @JvmOverloads
    fun createModernButton(
        text: String,
        icon: Icon? = null,
        buttonColor: Color? = null
    ): JButton {
        return if (buttonColor != null) {
            // Create a custom colored button that properly renders text
            object : JButton(text, icon) {
                init {
                    isFocusPainted = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    isOpaque = true
                    isContentAreaFilled = false
                    isBorderPainted = false
                    border = JBUI.Borders.empty(6, 12)
                }

                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                    // Draw rounded background
                    g2.color = if (model.isPressed) buttonColor.darker() else buttonColor
                    g2.fillRoundRect(0, 0, width, height, 8, 8)

                    // Draw icon if present
                    var textX = JBUI.scale(12)
                    if (icon != null) {
                        val iconY = (height - icon.iconHeight) / 2
                        icon.paintIcon(this, g2, JBUI.scale(12), iconY)
                        textX = JBUI.scale(12) + icon.iconWidth + JBUI.scale(6)
                    }

                    // Draw text in white
                    g2.color = Color.WHITE
                    g2.font = font
                    val fm = g2.fontMetrics
                    val textY = (height + fm.ascent - fm.descent) / 2
                    g2.drawString(text, textX, textY)

                    g2.dispose()
                }
            }
        } else {
            JButton(text, icon).apply {
                isFocusPainted = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(6, 12)
            }
        }
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
