package com.hytaledocs.intellij.assets.preview

import com.hytaledocs.intellij.assets.AssetNode
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.swing.*

/**
 * Panel for previewing image assets with zoom and pan capabilities.
 */
class ImagePreviewPanel : JBPanel<ImagePreviewPanel>(BorderLayout()) {

    private var currentImage: BufferedImage? = null
    private var zoomLevel = 1.0
    private var panOffset = Point(0, 0)
    private var dragStart: Point? = null

    private val imagePanel = ImageDisplayPanel()
    private val infoLabel = JBLabel()
    private val zoomLabel = JBLabel("100%")

    private val zoomInButton = JButton(AllIcons.General.Add)
    private val zoomOutButton = JButton(AllIcons.General.Remove)
    private val fitButton = JButton(AllIcons.General.FitContent)
    private val actualSizeButton = JButton("1:1")

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())

        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        toolbar.isOpaque = false
        toolbar.border = JBUI.Borders.empty(4, 8)

        zoomInButton.toolTipText = "Zoom In"
        zoomOutButton.toolTipText = "Zoom Out"
        fitButton.toolTipText = "Fit to Window"
        actualSizeButton.toolTipText = "Actual Size (1:1)"

        zoomInButton.addActionListener { setZoom(zoomLevel * 1.25) }
        zoomOutButton.addActionListener { setZoom(zoomLevel / 1.25) }
        fitButton.addActionListener { fitToWindow() }
        actualSizeButton.addActionListener { setZoom(1.0) }

        toolbar.add(zoomInButton)
        toolbar.add(zoomOutButton)
        toolbar.add(fitButton)
        toolbar.add(actualSizeButton)
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(16)))
        zoomLabel.foreground = HytaleTheme.mutedText
        toolbar.add(zoomLabel)
        toolbar.add(Box.createHorizontalGlue())
        infoLabel.foreground = HytaleTheme.mutedText
        toolbar.add(infoLabel)

        add(toolbar, BorderLayout.NORTH)

        // Image scroll pane
        val scrollPane = JBScrollPane(imagePanel)
        scrollPane.border = null
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        add(scrollPane, BorderLayout.CENTER)

        // Mouse wheel zoom
        imagePanel.addMouseWheelListener { e ->
            val delta = -e.wheelRotation * 0.1
            setZoom(zoomLevel * (1 + delta))
        }

        // Mouse drag for pan
        imagePanel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                dragStart = e.point
                imagePanel.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }

            override fun mouseReleased(e: MouseEvent) {
                dragStart = null
                imagePanel.cursor = Cursor.getDefaultCursor()
            }
        })

        imagePanel.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                dragStart?.let { start ->
                    val dx = e.x - start.x
                    val dy = e.y - start.y
                    panOffset = Point(panOffset.x + dx, panOffset.y + dy)
                    dragStart = e.point
                    imagePanel.repaint()
                }
            }
        })
    }

    /**
     * Load and display an image file.
     */
    fun loadImage(file: AssetNode.FileNode) {
        try {
            currentImage = file.zipSource?.let { source ->
                // Load from ZIP
                loadImageFromZip(source.zipFile, source.entryPath)
            } ?: run {
                // Load from file system
                file.file?.let { ImageIO.read(it) }
            }

            currentImage?.let { img ->
                infoLabel.text = "${img.width} x ${img.height} px | ${file.sizeFormatted}"
                infoLabel.foreground = HytaleTheme.mutedText
                fitToWindow()
            } ?: run {
                showError("Failed to load image")
            }
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    /**
     * Load an image from inside a ZIP file.
     */
    private fun loadImageFromZip(zipFile: File, entryPath: String): BufferedImage? {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return null
                zip.getInputStream(entry).use { inputStream ->
                    ImageIO.read(inputStream)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clear the current image.
     */
    fun clear() {
        currentImage = null
        infoLabel.text = ""
        zoomLevel = 1.0
        panOffset = Point(0, 0)
        imagePanel.repaint()
    }

    private fun showError(message: String) {
        currentImage = null
        infoLabel.text = message
        infoLabel.foreground = HytaleTheme.errorColor
        imagePanel.repaint()
    }

    private fun setZoom(newZoom: Double) {
        zoomLevel = newZoom.coerceIn(0.1, 10.0)
        zoomLabel.text = "${(zoomLevel * 100).toInt()}%"
        updateImageSize()
        imagePanel.repaint()
    }

    private fun fitToWindow() {
        val img = currentImage ?: return
        val viewportWidth = imagePanel.parent?.width ?: width
        val viewportHeight = imagePanel.parent?.height ?: height

        if (viewportWidth > 0 && viewportHeight > 0) {
            val scaleX = (viewportWidth - 20.0) / img.width
            val scaleY = (viewportHeight - 20.0) / img.height
            setZoom(minOf(scaleX, scaleY, 1.0))
        }

        panOffset = Point(0, 0)
    }

    private fun updateImageSize() {
        val img = currentImage ?: return
        val newWidth = (img.width * zoomLevel).toInt()
        val newHeight = (img.height * zoomLevel).toInt()
        imagePanel.preferredSize = Dimension(newWidth, newHeight)
        imagePanel.revalidate()
    }

    /**
     * Inner panel that actually draws the image.
     */
    private inner class ImageDisplayPanel : JPanel() {
        init {
            isOpaque = true
            background = JBColor.namedColor("EditorPane.background", UIUtil.getPanelBackground())
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            currentImage?.let { img ->
                val scaledWidth = (img.width * zoomLevel).toInt()
                val scaledHeight = (img.height * zoomLevel).toInt()

                // Center the image
                val x = (width - scaledWidth) / 2 + panOffset.x
                val y = (height - scaledHeight) / 2 + panOffset.y

                // Draw checkerboard pattern for transparency
                drawCheckerboard(g2, x, y, scaledWidth, scaledHeight)

                // Draw image
                g2.drawImage(img, x, y, scaledWidth, scaledHeight, null)
            }
        }

        private fun drawCheckerboard(g2: Graphics2D, x: Int, y: Int, width: Int, height: Int) {
            val checkSize = 8
            val light = JBColor.namedColor("EditorPane.background", Color.WHITE)
            val dark = JBColor.namedColor("Panel.background", Color.LIGHT_GRAY)

            for (i in 0 until (width / checkSize) + 1) {
                for (j in 0 until (height / checkSize) + 1) {
                    val isLight = (i + j) % 2 == 0
                    g2.color = if (isLight) light else dark
                    g2.fillRect(
                        x + i * checkSize,
                        y + j * checkSize,
                        minOf(checkSize, width - i * checkSize),
                        minOf(checkSize, height - j * checkSize)
                    )
                }
            }
        }
    }
}
