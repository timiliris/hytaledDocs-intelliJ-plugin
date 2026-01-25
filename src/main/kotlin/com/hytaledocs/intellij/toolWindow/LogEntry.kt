package com.hytaledocs.intellij.toolWindow

import java.time.LocalDateTime

/**
 * Represents a single log entry from the server console.
 */
data class LogEntry(
    val timestamp: LocalDateTime,
    val level: LogLevel,
    val source: String?,  // Plugin name if detectable
    val message: String,
    val rawLine: String
)

/**
 * Log levels for filtering.
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    SYSTEM  // For system messages (commands, status updates)
}
