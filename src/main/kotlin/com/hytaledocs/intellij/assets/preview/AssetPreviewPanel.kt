package com.hytaledocs.intellij.assets.preview

import com.hytaledocs.intellij.assets.AssetNode
import com.hytaledocs.intellij.assets.PreviewType
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.UIUtil
import java.awt.CardLayout

/**
 * Container panel that switches between different preview panels based on file type.
 * Uses CardLayout to show the appropriate preview for the selected asset.
 */
class AssetPreviewPanel : JBPanel<AssetPreviewPanel>(CardLayout()) {

    companion object {
        private const val CARD_NONE = "none"
        private const val CARD_IMAGE = "image"
        private const val CARD_JSON = "json"
        private const val CARD_AUDIO = "audio"
    }

    private val cardLayout: CardLayout
        get() = layout as CardLayout

    private val noPreviewPanel = NoPreviewPanel()
    private val imagePreviewPanel = ImagePreviewPanel()
    private val jsonPreviewPanel = JsonPreviewPanel()
    private val audioPreviewPanel = AudioPreviewPanel()

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())

        add(noPreviewPanel, CARD_NONE)
        add(imagePreviewPanel, CARD_IMAGE)
        add(jsonPreviewPanel, CARD_JSON)
        add(audioPreviewPanel, CARD_AUDIO)

        showNoSelection()
    }

    /**
     * Show the "no selection" state.
     */
    fun showNoSelection() {
        stopAudio()
        noPreviewPanel.showNoSelection()
        cardLayout.show(this, CARD_NONE)
    }

    /**
     * Preview the given file node.
     */
    fun previewFile(file: AssetNode.FileNode) {
        stopAudio()

        when (file.assetType.previewType) {
            PreviewType.IMAGE -> {
                imagePreviewPanel.loadImage(file)
                cardLayout.show(this, CARD_IMAGE)
            }
            PreviewType.JSON, PreviewType.TEXT -> {
                jsonPreviewPanel.loadFile(file)
                cardLayout.show(this, CARD_JSON)
            }
            PreviewType.AUDIO -> {
                audioPreviewPanel.loadAudio(file)
                cardLayout.show(this, CARD_AUDIO)
            }
            PreviewType.NONE -> {
                noPreviewPanel.showFileInfo(file)
                cardLayout.show(this, CARD_NONE)
            }
        }
    }

    /**
     * Clear all preview panels.
     */
    fun clear() {
        stopAudio()
        imagePreviewPanel.clear()
        jsonPreviewPanel.clear()
        noPreviewPanel.showNoSelection()
        cardLayout.show(this, CARD_NONE)
    }

    /**
     * Stop audio playback if active.
     */
    private fun stopAudio() {
        audioPreviewPanel.clear()
    }
}
