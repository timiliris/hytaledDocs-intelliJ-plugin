package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.HytaleBundle
import com.hytaledocs.intellij.services.ServerProfiler
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

/**
 * Panel displaying server performance profiling information.
 */
class ProfilerPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val profiler = ServerProfiler.getInstance(project)

    // Stats labels
    private val uptimeLabel = JBLabel("--:--:--")
    private val startupLabel = JBLabel("N/A")
    private val playersLabel = JBLabel("0")
    private val peakPlayersLabel = JBLabel("0")
    private val commandsLabel = JBLabel("0")
    private val logsLabel = JBLabel("0")
    private val errorsLabel = JBLabel("0")
    private val warningsLabel = JBLabel("0")

    // Timeline panel
    private val timelinePanel = JPanel()
    private var refreshTimer: Timer? = null

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.empty(12)

        add(createStatsPanel(), BorderLayout.NORTH)
        add(createTimelinePanel(), BorderLayout.CENTER)
        add(createControlsPanel(), BorderLayout.SOUTH)

        // Start refresh timer
        refreshTimer = Timer(1000) { refresh() }
        refreshTimer?.start()
    }

    private fun createStatsPanel(): JPanel {
        val card = JPanel(BorderLayout())
        card.background = HytaleTheme.cardBackground
        card.border = RoundedLineBorder(HytaleTheme.cardBorder, 8, 1)

        // Title
        val titlePanel = JPanel(BorderLayout())
        titlePanel.isOpaque = false
        titlePanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, HytaleTheme.cardBorder),
            JBUI.Borders.empty(8, 12)
        )
        titlePanel.add(JBLabel(HytaleBundle.message("profiler.title")).apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }, BorderLayout.WEST)
        card.add(titlePanel, BorderLayout.NORTH)

        // Stats grid
        val statsGrid = JPanel(GridLayout(2, 4, JBUI.scale(16), JBUI.scale(8)))
        statsGrid.isOpaque = false
        statsGrid.border = JBUI.Borders.empty(12)

        statsGrid.add(createStatCard(HytaleBundle.message("profiler.uptime"), uptimeLabel, HytaleTheme.accentColor))
        statsGrid.add(createStatCard(HytaleBundle.message("profiler.startup"), startupLabel, HytaleTheme.successColor))
        statsGrid.add(createStatCard(HytaleBundle.message("profiler.players"), playersLabel, HytaleTheme.accentColor))
        statsGrid.add(createStatCard(HytaleBundle.message("profiler.peak"), peakPlayersLabel, HytaleTheme.warningColor))
        statsGrid.add(createStatCard(HytaleBundle.message("profiler.commands"), commandsLabel, HytaleTheme.mutedText))
        statsGrid.add(createStatCard(HytaleBundle.message("profiler.logs"), logsLabel, HytaleTheme.mutedText))
        statsGrid.add(createStatCard(HytaleBundle.message("profiler.errors"), errorsLabel, HytaleTheme.errorColor))
        statsGrid.add(createStatCard(HytaleBundle.message("profiler.warnings"), warningsLabel, HytaleTheme.warningColor))

        card.add(statsGrid, BorderLayout.CENTER)
        return card
    }

    private fun createStatCard(title: String, valueLabel: JBLabel, color: Color): JPanel {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        val titleLabel = JBLabel(title)
        titleLabel.foreground = HytaleTheme.mutedText
        titleLabel.font = titleLabel.font.deriveFont(10f)

        valueLabel.foreground = color
        valueLabel.font = valueLabel.font.deriveFont(Font.BOLD, 18f)

        panel.add(titleLabel, BorderLayout.NORTH)
        panel.add(valueLabel, BorderLayout.CENTER)

        return panel
    }

    private fun createTimelinePanel(): JPanel {
        val card = JPanel(BorderLayout())
        card.background = HytaleTheme.cardBackground
        card.border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(HytaleTheme.cardBorder, 8, 1),
            JBUI.Borders.empty()
        )

        // Title
        val titlePanel = JPanel(BorderLayout())
        titlePanel.isOpaque = false
        titlePanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, HytaleTheme.cardBorder),
            JBUI.Borders.empty(8, 12)
        )
        titlePanel.add(JBLabel(HytaleBundle.message("profiler.timeline")).apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }, BorderLayout.WEST)
        card.add(titlePanel, BorderLayout.NORTH)

        // Timeline list
        timelinePanel.layout = BoxLayout(timelinePanel, BoxLayout.Y_AXIS)
        timelinePanel.isOpaque = false
        timelinePanel.border = JBUI.Borders.empty(8)

        val scrollPane = JBScrollPane(timelinePanel)
        scrollPane.border = null
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED

        card.add(scrollPane, BorderLayout.CENTER)

        // Add margin
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.border = JBUI.Borders.empty(12, 0, 0, 0)
        wrapper.add(card, BorderLayout.CENTER)

        return wrapper
    }

    private fun createControlsPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(8, 0, 0, 0)

        panel.add(HytaleTheme.createButton(HytaleBundle.message("button.clear"), AllIcons.Actions.GC).apply {
            addActionListener {
                profiler.clear()
                refresh()
            }
        })

        panel.add(HytaleTheme.createButton(HytaleBundle.message("button.refresh"), AllIcons.Actions.Refresh).apply {
            addActionListener { refresh() }
        })

        return panel
    }

    fun refresh() {
        val stats = profiler.getStats()

        // Update stats labels
        uptimeLabel.text = stats.getFormattedUptime()
        startupLabel.text = stats.getFormattedStartupDuration()
        playersLabel.text = stats.currentPlayers.toString()
        peakPlayersLabel.text = stats.peakPlayers.toString()
        commandsLabel.text = stats.totalCommands.toString()
        logsLabel.text = stats.totalLogs.toString()
        errorsLabel.text = stats.errorLogs.toString()
        warningsLabel.text = stats.warnLogs.toString()

        // Update timeline
        updateTimeline()
    }

    private fun updateTimeline() {
        timelinePanel.removeAll()

        val events = profiler.getRecentEvents(30)

        if (events.isEmpty()) {
            val emptyLabel = JBLabel(HytaleBundle.message("profiler.noEvents"))
            emptyLabel.foreground = HytaleTheme.mutedText
            emptyLabel.border = JBUI.Borders.empty(20)
            timelinePanel.add(emptyLabel)
        } else {
            for (event in events) {
                timelinePanel.add(createEventRow(event))
                timelinePanel.add(Box.createVerticalStrut(4))
            }
        }

        timelinePanel.revalidate()
        timelinePanel.repaint()
    }

    private fun createEventRow(event: ServerProfiler.ProfileEvent): JPanel {
        val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        panel.isOpaque = false
        panel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
        panel.border = JBUI.Borders.empty(2, 4)

        // Time
        val timeLabel = JBLabel(event.getFormattedTime())
        timeLabel.foreground = HytaleTheme.mutedText
        timeLabel.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        timeLabel.preferredSize = Dimension(JBUI.scale(60), timeLabel.preferredSize.height)
        panel.add(timeLabel, BorderLayout.WEST)

        // Icon and type
        val icon = when (event.type) {
            ServerProfiler.EventType.SERVER_START -> AllIcons.Actions.Execute
            ServerProfiler.EventType.SERVER_READY -> AllIcons.Actions.Checked
            ServerProfiler.EventType.SERVER_STOP -> AllIcons.Actions.Suspend
            ServerProfiler.EventType.PLAYER_JOIN -> AllIcons.General.Add
            ServerProfiler.EventType.PLAYER_LEAVE -> AllIcons.General.Remove
            ServerProfiler.EventType.PLUGIN_LOAD -> AllIcons.Nodes.Plugin
            ServerProfiler.EventType.PLUGIN_ERROR -> AllIcons.General.Error
            ServerProfiler.EventType.COMMAND_SENT -> AllIcons.Debugger.Console
            ServerProfiler.EventType.WARNING -> AllIcons.General.Warning
            ServerProfiler.EventType.ERROR -> AllIcons.General.Error
        }

        val color = when (event.type) {
            ServerProfiler.EventType.SERVER_START, ServerProfiler.EventType.SERVER_READY -> HytaleTheme.successColor
            ServerProfiler.EventType.SERVER_STOP -> HytaleTheme.mutedText
            ServerProfiler.EventType.PLAYER_JOIN -> HytaleTheme.accentColor
            ServerProfiler.EventType.PLAYER_LEAVE -> HytaleTheme.mutedText
            ServerProfiler.EventType.PLUGIN_LOAD -> HytaleTheme.successColor
            ServerProfiler.EventType.PLUGIN_ERROR, ServerProfiler.EventType.ERROR -> HytaleTheme.errorColor
            ServerProfiler.EventType.WARNING -> HytaleTheme.warningColor
            ServerProfiler.EventType.COMMAND_SENT -> HytaleTheme.accentColor
        }

        val contentPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        contentPanel.isOpaque = false

        contentPanel.add(JLabel(icon))

        val typeLabel = JBLabel(event.type.displayName)
        typeLabel.foreground = color
        typeLabel.font = typeLabel.font.deriveFont(Font.BOLD, 12f)
        contentPanel.add(typeLabel)

        if (event.details.isNotEmpty()) {
            val detailsLabel = JBLabel(event.details)
            detailsLabel.foreground = HytaleTheme.textPrimary
            detailsLabel.font = detailsLabel.font.deriveFont(12f)
            contentPanel.add(detailsLabel)
        }

        if (event.duration != null) {
            val durationLabel = JBLabel("(${event.duration.toMillis()}ms)")
            durationLabel.foreground = HytaleTheme.mutedText
            contentPanel.add(durationLabel)
        }

        panel.add(contentPanel, BorderLayout.CENTER)

        return panel
    }

    fun dispose() {
        refreshTimer?.stop()
        refreshTimer = null
    }
}
