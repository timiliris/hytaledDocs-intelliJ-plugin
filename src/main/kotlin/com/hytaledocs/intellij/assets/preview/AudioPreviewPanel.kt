package com.hytaledocs.intellij.assets.preview

import com.hytaledocs.intellij.assets.AssetNode
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.sound.sampled.*
import javax.swing.*

/**
 * Panel for previewing audio files with playback controls.
 * Supports WAV files natively. OGG support requires java-vorbis-support library.
 */
class AudioPreviewPanel : JBPanel<AudioPreviewPanel>(BorderLayout()), Disposable {

    private var clip: Clip? = null
    private var currentFile: File? = null
    private var tempFile: File? = null
    private var updateTimer: Timer? = null

    private val playButton = JButton(AllIcons.Actions.Execute)
    private val stopButton = JButton(AllIcons.Actions.Suspend)
    private val progressSlider = JSlider(0, 100, 0)
    private val timeLabel = JBLabel("00:00 / 00:00")
    private val volumeSlider = JSlider(0, 100, 80)
    private val volumeIcon = JBLabel(AllIcons.Actions.InlayDropTriangle)
    private val fileNameLabel = JBLabel()
    private val statusLabel = JBLabel()
    private val waveformPanel = WaveformPanel()

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.empty(16)

        val centerPanel = JPanel()
        centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)
        centerPanel.isOpaque = false

        // File name
        fileNameLabel.font = fileNameLabel.font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(14f))
        fileNameLabel.foreground = HytaleTheme.textPrimary
        fileNameLabel.alignmentX = CENTER_ALIGNMENT
        centerPanel.add(fileNameLabel)
        centerPanel.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Status
        statusLabel.foreground = HytaleTheme.mutedText
        statusLabel.alignmentX = CENTER_ALIGNMENT
        centerPanel.add(statusLabel)
        centerPanel.add(Box.createVerticalStrut(JBUI.scale(24)))

        // Waveform visualization
        waveformPanel.preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(80))
        waveformPanel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(80))
        waveformPanel.alignmentX = CENTER_ALIGNMENT
        centerPanel.add(waveformPanel)
        centerPanel.add(Box.createVerticalStrut(JBUI.scale(16)))

        // Progress slider
        progressSlider.isOpaque = false
        progressSlider.alignmentX = CENTER_ALIGNMENT
        progressSlider.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
        progressSlider.addChangeListener {
            if (progressSlider.valueIsAdjusting) {
                clip?.let { c ->
                    val newPos = (progressSlider.value / 100.0 * c.microsecondLength).toLong()
                    c.microsecondPosition = newPos
                }
            }
        }
        centerPanel.add(progressSlider)
        centerPanel.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Time label
        timeLabel.foreground = HytaleTheme.mutedText
        timeLabel.alignmentX = CENTER_ALIGNMENT
        centerPanel.add(timeLabel)
        centerPanel.add(Box.createVerticalStrut(JBUI.scale(16)))

        // Control buttons
        val controlsPanel = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0))
        controlsPanel.isOpaque = false

        playButton.toolTipText = "Play"
        playButton.addActionListener { togglePlayPause() }
        controlsPanel.add(playButton)

        stopButton.toolTipText = "Stop"
        stopButton.addActionListener { stop() }
        stopButton.isEnabled = false
        controlsPanel.add(stopButton)

        controlsPanel.add(Box.createHorizontalStrut(JBUI.scale(24)))

        // Volume control
        volumeIcon.toolTipText = "Volume"
        controlsPanel.add(volumeIcon)
        volumeSlider.preferredSize = Dimension(JBUI.scale(80), JBUI.scale(20))
        volumeSlider.isOpaque = false
        volumeSlider.toolTipText = "Volume"
        volumeSlider.addChangeListener { updateVolume() }
        controlsPanel.add(volumeSlider)

        centerPanel.add(controlsPanel)

        add(centerPanel, BorderLayout.CENTER)

        // Timer for updating progress
        updateTimer = Timer(100) { updateProgress() }
    }

    /**
     * Load an audio file for playback.
     */
    fun loadAudio(file: AssetNode.FileNode) {
        stop()
        currentFile = file.file
        fileNameLabel.text = file.displayName
        statusLabel.text = "Loading..."
        statusLabel.foreground = HytaleTheme.mutedText

        try {
            // Try to open the audio file
            val audioInputStream = file.zipSource?.let { source ->
                // Load from ZIP - need to get input stream
                loadAudioFromZip(source.zipFile, source.entryPath, file.extension)
                    ?: throw UnsupportedAudioFileException("Cannot load audio from ZIP")
            } ?: if (file.extension == "ogg") {
                // OGG files need special handling with java-vorbis-support
                file.file?.let { tryLoadOgg(it) }
                    ?: throw UnsupportedAudioFileException("OGG format requires java-vorbis-support library")
            } else {
                file.file?.let { AudioSystem.getAudioInputStream(it) }
                    ?: throw UnsupportedAudioFileException("File not accessible")
            }

            // Get a clip
            clip = AudioSystem.getClip()
            clip?.open(audioInputStream)

            // Update UI
            val duration = clip?.microsecondLength ?: 0
            val seconds = duration / 1_000_000
            statusLabel.text = "${file.sizeFormatted} | ${formatTime(seconds)}"
            statusLabel.foreground = HytaleTheme.successColor

            playButton.isEnabled = true
            stopButton.isEnabled = false
            progressSlider.value = 0
            timeLabel.text = "00:00 / ${formatTime(seconds)}"

            // Add listener for clip end
            clip?.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    SwingUtilities.invokeLater {
                        if (clip?.microsecondPosition ?: 0 >= (clip?.microsecondLength ?: 1) - 1000) {
                            stop()
                        }
                    }
                }
            }

            updateVolume()

        } catch (e: UnsupportedAudioFileException) {
            showError("Unsupported format: ${file.extension}\n${e.message}")
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    /**
     * Try to load OGG file using java-vorbis-support if available.
     */
    private fun tryLoadOgg(file: File): AudioInputStream? {
        return try {
            // The java-vorbis-support library registers itself with AudioSystem
            // If it's available, this will work
            AudioSystem.getAudioInputStream(file)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load audio from inside a ZIP file.
     * Extracts to a temporary file for playback.
     */
    private fun loadAudioFromZip(zipFile: File, entryPath: String, extension: String): AudioInputStream? {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return null
                val inputStream = BufferedInputStream(zip.getInputStream(entry))

                // Create temp file for audio playback
                val newTempFile = File.createTempFile("hytale_audio_", ".$extension")
                newTempFile.outputStream().use { out ->
                    inputStream.copyTo(out)
                }

                // Store temp file reference for cleanup in dispose()
                this@AudioPreviewPanel.tempFile = newTempFile
                currentFile = newTempFile

                if (extension == "ogg") {
                    tryLoadOgg(newTempFile)
                } else {
                    AudioSystem.getAudioInputStream(newTempFile)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clear the panel and stop playback.
     */
    fun clear() {
        stop()
        currentFile = null
        fileNameLabel.text = ""
        statusLabel.text = ""
        timeLabel.text = "00:00 / 00:00"
        progressSlider.value = 0
        playButton.isEnabled = false
        stopButton.isEnabled = false
    }

    private fun showError(message: String) {
        statusLabel.text = message
        statusLabel.foreground = HytaleTheme.errorColor
        playButton.isEnabled = false
        stopButton.isEnabled = false
    }

    private fun togglePlayPause() {
        clip?.let { c ->
            if (c.isRunning) {
                c.stop()
                playButton.icon = AllIcons.Actions.Execute
                updateTimer?.stop()
            } else {
                c.start()
                playButton.icon = AllIcons.Actions.Pause
                stopButton.isEnabled = true
                updateTimer?.start()
            }
        }
    }

    private fun stop() {
        clip?.let { c ->
            c.stop()
            c.microsecondPosition = 0
        }
        clip?.close()
        clip = null

        playButton.icon = AllIcons.Actions.Execute
        playButton.isEnabled = currentFile != null
        stopButton.isEnabled = false
        progressSlider.value = 0
        updateTimer?.stop()

        clip?.microsecondLength?.let { duration ->
            timeLabel.text = "00:00 / ${formatTime(duration / 1_000_000)}"
        }
    }

    private fun updateProgress() {
        clip?.let { c ->
            val position = c.microsecondPosition
            val duration = c.microsecondLength
            if (duration > 0) {
                progressSlider.value = ((position.toDouble() / duration) * 100).toInt()
                timeLabel.text = "${formatTime(position / 1_000_000)} / ${formatTime(duration / 1_000_000)}"
                waveformPanel.setProgress(position.toDouble() / duration)
            }
        }
    }

    private fun updateVolume() {
        clip?.let { c ->
            try {
                val gainControl = c.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                val volume = volumeSlider.value / 100.0f
                val dB = if (volume > 0) (20.0 * Math.log10(volume.toDouble())).toFloat() else gainControl.minimum
                gainControl.value = dB.coerceIn(gainControl.minimum, gainControl.maximum)
            } catch (e: Exception) {
                // Volume control not supported
            }
        }
    }

    private fun formatTime(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    override fun dispose() {
        stop()
        tempFile?.let { file ->
            try {
                file.delete()
            } catch (e: Exception) {
                // ignore
            }
        }
        tempFile = null
        updateTimer?.stop()
        updateTimer = null
    }

    /**
     * Simple waveform visualization panel.
     */
    private inner class WaveformPanel : JPanel() {
        private var progress = 0.0

        init {
            isOpaque = true
            background = JBColor.namedColor("EditorPane.background", UIUtil.getPanelBackground())
        }

        fun setProgress(progress: Double) {
            this.progress = progress
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val w = width
            val h = height
            val centerY = h / 2

            // Draw waveform bars
            val barCount = w / 4
            for (i in 0 until barCount) {
                val x = i * 4
                val barProgress = i.toDouble() / barCount

                // Generate pseudo-random bar heights for visualization
                val seed = (i * 7 + 13) % 100
                val barHeight = (seed / 100.0 * h * 0.7).toInt()

                val color = if (barProgress <= progress) {
                    HytaleTheme.accentColor
                } else {
                    HytaleTheme.mutedText
                }

                g2.color = color
                g2.fillRect(x, centerY - barHeight / 2, 2, barHeight)
            }

            // Draw progress line
            val progressX = (progress * w).toInt()
            g2.color = HytaleTheme.successColor
            g2.fillRect(progressX - 1, 0, 2, h)
        }
    }
}
