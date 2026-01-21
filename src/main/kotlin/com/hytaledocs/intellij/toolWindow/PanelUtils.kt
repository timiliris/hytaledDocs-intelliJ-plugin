package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JLabel

/**
 * Utility functions shared across tool window panels.
 * Centralizes common UI operations like notifications and styled labels.
 */
object PanelUtils {

    /**
     * Show a unified notification using the Hytale Plugin notification group.
     *
     * @param project The current project context
     * @param title The notification title (displayed in bold)
     * @param message The notification message content
     * @param type The notification type (INFORMATION, WARNING, ERROR)
     */
    fun notify(project: Project, title: String, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hytale Plugin")
            .createNotification(title, message, type)
            .notify(project)
    }

    /**
     * Create a styled label for statistics display.
     * Uses muted text color from the Hytale theme.
     *
     * @param text The label text
     * @return A JLabel with muted foreground color
     */
    fun createStatLabel(text: String): JLabel {
        return JLabel(text).apply {
            foreground = HytaleTheme.mutedText
        }
    }

    /**
     * Create a styled label for statistics display with a fixed width.
     * Useful for aligned stat rows.
     *
     * @param text The label text
     * @param width The preferred width in pixels (will be JBUI scaled)
     * @return A JLabel with muted foreground color and fixed width
     */
    fun createStatLabel(text: String, width: Int): JLabel {
        return JLabel(text).apply {
            foreground = HytaleTheme.mutedText
            preferredSize = Dimension(JBUI.scale(width), preferredSize.height)
        }
    }
}
