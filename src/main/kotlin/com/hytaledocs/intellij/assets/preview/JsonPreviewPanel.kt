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
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import java.util.zip.ZipFile
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Panel for previewing JSON and text files with syntax highlighting.
 */
class JsonPreviewPanel : JBPanel<JsonPreviewPanel>(BorderLayout()) {

    private val textPane = JTextPane()
    private val infoLabel = JBLabel()
    private val lineCountLabel = JBLabel()

    // Syntax highlighting colors
    private val keyColor = HytaleTheme.accentColor
    private val stringColor = HytaleTheme.successColor
    private val numberColor = HytaleTheme.warningColor
    private val booleanColor = HytaleTheme.purpleAccent
    private val nullColor = HytaleTheme.errorColor
    private val bracketColor = HytaleTheme.textPrimary
    private val commentColor = HytaleTheme.mutedText

    init {
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())

        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        toolbar.isOpaque = false
        toolbar.border = JBUI.Borders.empty(4, 8)

        val copyButton = JButton("Copy", AllIcons.Actions.Copy)
        copyButton.addActionListener {
            textPane.selectAll()
            textPane.copy()
            textPane.select(0, 0)
        }
        toolbar.add(copyButton)

        toolbar.add(Box.createHorizontalStrut(JBUI.scale(16)))
        lineCountLabel.foreground = HytaleTheme.mutedText
        toolbar.add(lineCountLabel)

        toolbar.add(Box.createHorizontalGlue())
        infoLabel.foreground = HytaleTheme.mutedText
        toolbar.add(infoLabel)

        add(toolbar, BorderLayout.NORTH)

        // Text pane
        textPane.isEditable = false
        textPane.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
        textPane.background = JBColor.namedColor("EditorPane.background", UIUtil.getPanelBackground())
        textPane.foreground = HytaleTheme.textPrimary
        textPane.border = JBUI.Borders.empty(8)

        val scrollPane = JBScrollPane(textPane)
        scrollPane.border = null
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Load and display a JSON or text file.
     */
    fun loadFile(file: AssetNode.FileNode) {
        try {
            val content = if (file.isInZip && file.zipSource != null) {
                // Load from ZIP
                loadTextFromZip(file.zipSource!!.zipFile, file.zipSource!!.entryPath)
            } else {
                // Load from file system
                file.file?.readText() ?: ""
            }

            val lines = content.lines().size

            infoLabel.text = file.sizeFormatted
            lineCountLabel.text = "$lines lines"

            // Apply syntax highlighting based on file type
            when (file.extension) {
                "json", "blockymodel", "blockyanim", "gltf" -> highlightJson(content)
                "yml", "yaml" -> highlightYaml(content)
                else -> textPane.text = content
            }

            textPane.caretPosition = 0
        } catch (e: Exception) {
            showError("Error loading file: ${e.message}")
        }
    }

    /**
     * Load text content from inside a ZIP file.
     */
    private fun loadTextFromZip(zipFile: File, entryPath: String): String {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return ""
                zip.getInputStream(entry).bufferedReader().readText()
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Clear the content.
     */
    fun clear() {
        textPane.text = ""
        infoLabel.text = ""
        lineCountLabel.text = ""
    }

    private fun showError(message: String) {
        textPane.text = message
        textPane.foreground = HytaleTheme.errorColor
        infoLabel.text = ""
    }

    /**
     * Apply JSON syntax highlighting to the content.
     */
    private fun highlightJson(content: String) {
        val doc = textPane.styledDocument
        doc.remove(0, doc.length)

        var i = 0
        var inString = false
        var stringStart = 0
        var isKey = true

        while (i < content.length) {
            val char = content[i]

            when {
                // Handle strings
                char == '"' && (i == 0 || content[i - 1] != '\\') -> {
                    if (inString) {
                        // End of string
                        val stringContent = content.substring(stringStart, i + 1)
                        val color = if (isKey) keyColor else stringColor
                        appendStyled(doc, stringContent, color)
                        inString = false
                    } else {
                        // Start of string
                        stringStart = i
                        inString = true
                    }
                    i++
                }

                inString -> i++

                // Handle colons (switch from key to value)
                char == ':' -> {
                    appendStyled(doc, ":", bracketColor)
                    isKey = false
                    i++
                }

                // Handle commas (switch back to key)
                char == ',' -> {
                    appendStyled(doc, ",", bracketColor)
                    isKey = true
                    i++
                }

                // Handle brackets
                char in "{}[]" -> {
                    appendStyled(doc, char.toString(), bracketColor)
                    if (char == '{' || char == '[') isKey = true
                    i++
                }

                // Handle numbers
                char.isDigit() || (char == '-' && i + 1 < content.length && content[i + 1].isDigit()) -> {
                    val numStart = i
                    while (i < content.length && (content[i].isDigit() || content[i] in ".-eE+")) i++
                    appendStyled(doc, content.substring(numStart, i), numberColor)
                }

                // Handle booleans and null
                content.substring(i).startsWith("true") -> {
                    appendStyled(doc, "true", booleanColor)
                    i += 4
                }
                content.substring(i).startsWith("false") -> {
                    appendStyled(doc, "false", booleanColor)
                    i += 5
                }
                content.substring(i).startsWith("null") -> {
                    appendStyled(doc, "null", nullColor)
                    i += 4
                }

                // Handle whitespace
                char.isWhitespace() -> {
                    appendStyled(doc, char.toString(), bracketColor)
                    i++
                }

                else -> i++
            }
        }
    }

    /**
     * Apply YAML syntax highlighting to the content.
     */
    private fun highlightYaml(content: String) {
        val doc = textPane.styledDocument
        doc.remove(0, doc.length)

        for (line in content.lines()) {
            when {
                // Comments
                line.trimStart().startsWith("#") -> {
                    appendStyled(doc, "$line\n", commentColor)
                }
                // Key-value pairs
                line.contains(":") -> {
                    val colonIndex = line.indexOf(":")
                    val key = line.substring(0, colonIndex + 1)
                    val value = line.substring(colonIndex + 1)
                    appendStyled(doc, key, keyColor)
                    highlightYamlValue(doc, value)
                    appendStyled(doc, "\n", bracketColor)
                }
                // List items
                line.trimStart().startsWith("-") -> {
                    val dashIndex = line.indexOf("-")
                    appendStyled(doc, line.substring(0, dashIndex + 1), bracketColor)
                    highlightYamlValue(doc, line.substring(dashIndex + 1))
                    appendStyled(doc, "\n", bracketColor)
                }
                else -> {
                    appendStyled(doc, "$line\n", bracketColor)
                }
            }
        }
    }

    private fun highlightYamlValue(doc: StyledDocument, value: String) {
        val trimmed = value.trim()
        when {
            trimmed.isEmpty() -> appendStyled(doc, value, bracketColor)
            trimmed == "true" || trimmed == "false" -> appendStyled(doc, value, booleanColor)
            trimmed == "null" || trimmed == "~" -> appendStyled(doc, value, nullColor)
            trimmed.toDoubleOrNull() != null -> appendStyled(doc, value, numberColor)
            trimmed.startsWith("\"") || trimmed.startsWith("'") -> appendStyled(doc, value, stringColor)
            else -> appendStyled(doc, value, stringColor)
        }
    }

    private fun appendStyled(doc: StyledDocument, text: String, color: Color) {
        val style = SimpleAttributeSet()
        StyleConstants.setForeground(style, color)
        doc.insertString(doc.length, text, style)
    }
}
