package com.hytaledocs.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Service for profiling Hytale server performance.
 * Tracks events, timing, and provides analytics.
 */
@Service(Service.Level.PROJECT)
class ServerProfiler(private val project: Project) {

    data class ProfileEvent(
        val timestamp: Instant,
        val type: EventType,
        val details: String,
        val duration: Duration? = null
    ) {
        fun getFormattedTime(): String {
            return LocalDateTime.ofInstant(timestamp, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        }
    }

    enum class EventType(val displayName: String, val icon: String) {
        SERVER_START("Server Start", "play"),
        SERVER_READY("Server Ready", "check"),
        SERVER_STOP("Server Stop", "stop"),
        PLAYER_JOIN("Player Joined", "user-plus"),
        PLAYER_LEAVE("Player Left", "user-minus"),
        PLUGIN_LOAD("Plugin Loaded", "package"),
        PLUGIN_ERROR("Plugin Error", "alert"),
        COMMAND_SENT("Command Sent", "terminal"),
        WARNING("Warning", "alert-triangle"),
        ERROR("Error", "x-circle")
    }

    private val events = mutableListOf<ProfileEvent>()
    private var serverStartTime: Instant? = null
    private var serverReadyTime: Instant? = null
    private var peakPlayerCount = 0
    private var totalCommandsSent = 0

    // Log level counters
    private var totalLogLines = 0
    private var infoLogCount = 0
    private var warnLogCount = 0
    private var errorLogCount = 0

    /**
     * Record a profiler event.
     */
    fun recordEvent(type: EventType, details: String = "", duration: Duration? = null) {
        val event = ProfileEvent(Instant.now(), type, details, duration)
        events.add(event)

        // Track specific metrics
        when (type) {
            EventType.SERVER_START -> {
                serverStartTime = event.timestamp
                serverReadyTime = null
                peakPlayerCount = 0
            }
            EventType.SERVER_READY -> {
                serverReadyTime = event.timestamp
            }
            EventType.SERVER_STOP -> {
                serverStartTime = null
                serverReadyTime = null
            }
            EventType.PLAYER_JOIN -> {
                val currentPlayers = getPlayerCount()
                if (currentPlayers > peakPlayerCount) {
                    peakPlayerCount = currentPlayers
                }
            }
            EventType.COMMAND_SENT -> {
                totalCommandsSent++
            }
            else -> {}
        }
    }

    /**
     * Get the server startup duration (time from start to ready).
     */
    fun getStartupDuration(): Duration? {
        val start = serverStartTime ?: return null
        val ready = serverReadyTime ?: return null
        return Duration.between(start, ready)
    }

    /**
     * Get the current server uptime.
     */
    fun getUptime(): Duration? {
        val start = serverStartTime ?: return null
        return Duration.between(start, Instant.now())
    }

    /**
     * Get the current player count from events.
     */
    fun getPlayerCount(): Int {
        var count = 0
        for (event in events) {
            when (event.type) {
                EventType.PLAYER_JOIN -> count++
                EventType.PLAYER_LEAVE -> count--
                EventType.SERVER_STOP -> count = 0
                else -> {}
            }
        }
        return maxOf(0, count)
    }

    /**
     * Get peak player count during this session.
     */
    fun getPeakPlayerCount(): Int = peakPlayerCount

    /**
     * Get total commands sent during this session.
     */
    fun getTotalCommandsSent(): Int = totalCommandsSent

    /**
     * Get all recorded events.
     */
    fun getEvents(): List<ProfileEvent> = events.toList()

    /**
     * Get recent events (last N).
     */
    fun getRecentEvents(limit: Int = 50): List<ProfileEvent> {
        return events.takeLast(limit).reversed()
    }

    /**
     * Get events of a specific type.
     */
    fun getEventsByType(type: EventType): List<ProfileEvent> {
        return events.filter { it.type == type }
    }

    /**
     * Check if server is currently running (based on events).
     */
    fun isServerRunning(): Boolean {
        val lastStartStop = events.lastOrNull {
            it.type == EventType.SERVER_START || it.type == EventType.SERVER_STOP
        }
        return lastStartStop?.type == EventType.SERVER_START || serverStartTime != null
    }

    /**
     * Get statistics summary.
     */
    fun getStats(): ProfileStats {
        return ProfileStats(
            uptime = getUptime(),
            startupDuration = getStartupDuration(),
            currentPlayers = getPlayerCount(),
            peakPlayers = peakPlayerCount,
            totalCommands = totalCommandsSent,
            totalEvents = events.size,
            errorCount = events.count { it.type == EventType.ERROR || it.type == EventType.PLUGIN_ERROR },
            warningCount = events.count { it.type == EventType.WARNING },
            totalLogs = totalLogLines,
            infoLogs = infoLogCount,
            warnLogs = warnLogCount,
            errorLogs = errorLogCount
        )
    }

    /**
     * Clear all recorded events.
     */
    fun clear() {
        events.clear()
        serverStartTime = null
        serverReadyTime = null
        peakPlayerCount = 0
        totalCommandsSent = 0
        totalLogLines = 0
        infoLogCount = 0
        warnLogCount = 0
        errorLogCount = 0
    }

    /**
     * Parse a log line and extract profiling information.
     */
    fun parseLogLine(line: String) {
        val lowerLine = line.lowercase()

        // Count log lines by level (format: [timestamp] [LEVEL] ...)
        totalLogLines++
        when {
            lowerLine.contains("] [error]") || lowerLine.contains("[error]") -> {
                errorLogCount++
                recordEvent(EventType.ERROR, line.take(100))
            }
            lowerLine.contains("] [warn]") || lowerLine.contains("[warn]") ||
            lowerLine.contains("] [warning]") || lowerLine.contains("[warning]") -> {
                warnLogCount++
                recordEvent(EventType.WARNING, line.take(100))
            }
            lowerLine.contains("] [info]") || lowerLine.contains("[info]") -> {
                infoLogCount++
            }
        }

        // Detect server start/ready
        if (lowerLine.contains("starting server") || lowerLine.contains("server starting")) {
            recordEvent(EventType.SERVER_START)
        } else if (lowerLine.contains("server booted") || lowerLine.contains("server ready") ||
                   lowerLine.contains("done (") && lowerLine.contains("s)!")) {
            recordEvent(EventType.SERVER_READY)
        }

        // Detect player join/leave
        val joinPatterns = listOf(
            Regex("""player\s+(\S+)\s+(?:joined|connected)""", RegexOption.IGNORE_CASE),
            Regex("""(\S+)\s+joined the game""", RegexOption.IGNORE_CASE)
        )
        val leavePatterns = listOf(
            Regex("""player\s+(\S+)\s+(?:left|disconnected)""", RegexOption.IGNORE_CASE),
            Regex("""(\S+)\s+left the game""", RegexOption.IGNORE_CASE)
        )

        for (pattern in joinPatterns) {
            pattern.find(line)?.let { match ->
                recordEvent(EventType.PLAYER_JOIN, match.groupValues[1])
                return
            }
        }

        for (pattern in leavePatterns) {
            pattern.find(line)?.let { match ->
                recordEvent(EventType.PLAYER_LEAVE, match.groupValues[1])
                return
            }
        }

        // Detect plugin loads
        if (lowerLine.contains("loading plugin") || lowerLine.contains("plugin loaded")) {
            val pluginMatch = Regex("""(?:loading|loaded)\s+plugin[:\s]+(\S+)""", RegexOption.IGNORE_CASE).find(line)
            recordEvent(EventType.PLUGIN_LOAD, pluginMatch?.groupValues?.get(1) ?: "Unknown")
        }
    }

    data class ProfileStats(
        val uptime: Duration?,
        val startupDuration: Duration?,
        val currentPlayers: Int,
        val peakPlayers: Int,
        val totalCommands: Int,
        val totalEvents: Int,
        val errorCount: Int,
        val warningCount: Int,
        // Log line counters
        val totalLogs: Int,
        val infoLogs: Int,
        val warnLogs: Int,
        val errorLogs: Int
    ) {
        fun getFormattedUptime(): String {
            val up = uptime ?: return "--:--:--"
            val hours = up.toHours()
            val minutes = up.toMinutesPart()
            val seconds = up.toSecondsPart()
            return String.format("%02d:%02d:%02d", hours, minutes, seconds)
        }

        fun getFormattedStartupDuration(): String {
            val dur = startupDuration ?: return "N/A"
            return String.format("%.2fs", dur.toMillis() / 1000.0)
        }
    }

    companion object {
        fun getInstance(project: Project): ServerProfiler {
            return project.getService(ServerProfiler::class.java)
        }
    }
}
