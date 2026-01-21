package com.hytaledocs.intellij.uifile.highlighter

import com.hytaledocs.intellij.uifile.UIFileType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/**
 * Color settings page for UI files.
 * Allows users to customize the syntax highlighting colors.
 */
class UIColorSettingsPage : ColorSettingsPage {

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Component", UISyntaxHighlighter.COMPONENT),
            AttributesDescriptor("Property", UISyntaxHighlighter.PROPERTY),
            AttributesDescriptor("Identifier", UISyntaxHighlighter.IDENTIFIER),
            AttributesDescriptor("String", UISyntaxHighlighter.STRING),
            AttributesDescriptor("Number", UISyntaxHighlighter.NUMBER),
            AttributesDescriptor("Color value", UISyntaxHighlighter.COLOR),
            AttributesDescriptor("Boolean", UISyntaxHighlighter.BOOLEAN),
            AttributesDescriptor("Keyword", UISyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Line comment", UISyntaxHighlighter.LINE_COMMENT),
            AttributesDescriptor("Block comment", UISyntaxHighlighter.BLOCK_COMMENT),
            AttributesDescriptor("Braces", UISyntaxHighlighter.BRACES),
            AttributesDescriptor("Brackets", UISyntaxHighlighter.BRACKETS),
            AttributesDescriptor("Parentheses", UISyntaxHighlighter.PARENTHESES),
            AttributesDescriptor("Colon", UISyntaxHighlighter.COLON),
            AttributesDescriptor("Comma", UISyntaxHighlighter.COMMA),
            AttributesDescriptor("Semicolon", UISyntaxHighlighter.SEMICOLON),
            AttributesDescriptor("Dot", UISyntaxHighlighter.DOT),
            AttributesDescriptor("Bad character", UISyntaxHighlighter.BAD_CHARACTER)
        )
    }

    override fun getIcon(): Icon? = UIFileType.icon

    override fun getHighlighter(): SyntaxHighlighter = UISyntaxHighlighter()

    override fun getDemoText(): String = """
// Hytale UI Definition File
// Example demonstrating syntax highlighting

Panel {
    id: "main-panel"
    width: 400
    height: 300
    backgroundColor: #1a1a2e
    padding: 16

    /* Header section */
    Label {
        text: "Welcome to Hytale"
        textColor: #ffffff
        fontSize: 24
        fontWeight: "bold"
        alignment: "center"
    }

    Button {
        text: "Play"
        width: 200
        height: 48
        backgroundColor: #e94560
        borderRadius: 8
        onClick: "startGame"
        visible: true
    }

    Slider {
        min: 0
        max: 100
        value: 50
        orientation: "horizontal"
    }

    ItemSlot {
        slotIndex: 0
        showTooltip: true
    }
}
""".trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Hytale UI"
}
