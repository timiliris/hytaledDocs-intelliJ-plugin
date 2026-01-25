package com.hytaledocs.intellij.toolWindow

import com.hytaledocs.intellij.services.CommandRegistryCache
import com.hytaledocs.intellij.services.CommandRegistryCache.CommandSuggestion
import com.hytaledocs.intellij.ui.HytaleTheme
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.ui.HintHint
import com.intellij.ui.JBColor
import com.intellij.ui.LightweightHint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.text.JTextComponent

/**
 * Autocomplete popup for server commands in the console tab.
 * Shows suggestions as the user types commands starting with '/'.
 */
class CommandAutoCompletePopup(
    private val project: Project,
    private val textField: JTextComponent,
    private val onCommandSelected: ((String) -> Unit)? = null
) : Disposable {

    companion object {
        private const val MAX_VISIBLE_ROWS = 8
        private const val POPUP_WIDTH = 350
        private const val DEBOUNCE_DELAY_MS = 100
    }

    private val registryCache = CommandRegistryCache.getInstance(project)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val listModel = DefaultListModel<SuggestionItem>()
    private val suggestionList = JBList(listModel)
    private val scrollPane = JBScrollPane(suggestionList)
    private var hint: LightweightHint? = null
    private var isShowing = false
    private var lastSuggestionRequest: Long = 0

    private val contentPanel = JPanel(BorderLayout()).apply {
        add(scrollPane, BorderLayout.CENTER)
        background = JBColor.namedColor("PopupMenu.background", UIUtil.getListBackground())
        border = JBUI.Borders.empty(2)
    }

    init {
        setupList()
        setupTextFieldListeners()
    }

    private fun setupList() {
        suggestionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        suggestionList.cellRenderer = SuggestionCellRenderer()
        suggestionList.isFocusable = false // Prevents focus stealing on Wayland
        suggestionList.background = JBColor.namedColor("PopupMenu.background", UIUtil.getListBackground())
        suggestionList.border = JBUI.Borders.empty()

        scrollPane.border = JBUI.Borders.empty()
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        // Double-click to select
        suggestionList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    applySuggestion()
                }
            }
        })
    }

    private fun setupTextFieldListeners() {
        textField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    isShowing && e.keyCode == KeyEvent.VK_DOWN -> {
                        moveSelection(1)
                        e.consume()
                    }
                    isShowing && e.keyCode == KeyEvent.VK_UP -> {
                        moveSelection(-1)
                        e.consume()
                    }
                    isShowing && (e.keyCode == KeyEvent.VK_TAB || e.keyCode == KeyEvent.VK_ENTER) -> {
                        if (listModel.size > 0) {
                            applySuggestion()
                            e.consume()
                        }
                    }
                    isShowing && e.keyCode == KeyEvent.VK_ESCAPE -> {
                        hidePopup()
                        e.consume()
                    }
                }
            }
        })

        textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = scheduleUpdate()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = scheduleUpdate()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = scheduleUpdate()
        })

        // Hide popup when focus is lost
        textField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                // Small delay to handle click-on-popup scenario
                SwingUtilities.invokeLater {
                    if (!textField.isFocusOwner) {
                        hidePopup()
                    }
                }
            }
        })
    }

    private fun scheduleUpdate() {
        alarm.cancelAllRequests()
        alarm.addRequest({ updateSuggestions() }, DEBOUNCE_DELAY_MS)
    }

    private fun updateSuggestions() {
        val text = textField.text
        val caretPos = textField.caretPosition

        // Show suggestions for any non-empty text (/ prefix is optional)
        if (text.isEmpty()) {
            hidePopup()
            return
        }

        // Strip / prefix - it's a UI convention, not part of the command
        val cleanText = text.removePrefix("/")

        // Get local completions first (immediate)
        val localSuggestions = registryCache.getLocalCompletions(cleanText)

        if (localSuggestions.isEmpty() && !registryCache.hasCommandRegistry()) {
            // No registry available, hide popup
            hidePopup()
            return
        }

        // Update list with local suggestions
        updateListModel(localSuggestions)

        if (localSuggestions.isNotEmpty()) {
            showPopup()
        } else {
            hidePopup()
        }

        // Also request dynamic suggestions from bridge (debounced)
        // Note: requestSuggestions handles / stripping internally
        lastSuggestionRequest = System.currentTimeMillis()
        registryCache.requestSuggestions(text, caretPos) { suggestions, startPos ->
            SwingUtilities.invokeLater {
                // Merge dynamic suggestions with local ones
                if (textField.text == text) { // Only if text hasn't changed
                    mergeDynamicSuggestions(suggestions, startPos)
                }
            }
        }
    }

    private fun updateListModel(suggestions: List<CommandSuggestion>) {
        listModel.clear()
        for (suggestion in suggestions.take(MAX_VISIBLE_ROWS * 2)) {
            listModel.addElement(SuggestionItem(
                text = suggestion.text,
                displayText = suggestion.displayText,
                description = suggestion.description,
                isCommand = suggestion.isCommand,
                aliases = suggestion.aliases
            ))
        }

        if (listModel.size > 0) {
            suggestionList.selectedIndex = 0
        }
    }

    private fun mergeDynamicSuggestions(dynamicSuggestions: List<String>, startPos: Int) {
        if (dynamicSuggestions.isEmpty()) return

        // Add dynamic suggestions that aren't already in the list
        val existingTexts = (0 until listModel.size).map { listModel.getElementAt(it).text }.toSet()

        // Get the clean text (without /) for building full suggestions
        val cleanText = textField.text.removePrefix("/")

        for (suggestion in dynamicSuggestions) {
            val fullText = if (startPos == 0) {
                suggestion
            } else {
                cleanText.substring(0, startPos) + suggestion
            }

            if (fullText !in existingTexts) {
                listModel.addElement(SuggestionItem(
                    text = fullText,
                    displayText = suggestion,
                    description = "",
                    isCommand = false,
                    aliases = emptyList()
                ))
            }
        }

        if (listModel.size > 0 && isShowing) {
            adjustPopupSize()
            updateHintLocation()
        }
    }

    private fun showPopup() {
        if (isShowing && hint?.isVisible == true) {
            adjustPopupSize()
            updateHintLocation()
            return
        }

        // Hide any existing hint
        hint?.hide()
        hint = null

        adjustPopupSize()

        // Create new LightweightHint with our content
        hint = LightweightHint(contentPanel).apply {
            setForceLightweightPopup(true) // Force layered pane embedding
        }

        // Get the layered pane from the text field's root pane
        val rootPane = textField.rootPane ?: return
        val layeredPane = rootPane.layeredPane ?: return

        try {
            val position = calculatePosition(layeredPane)
            val hintHint = HintHint(textField, position)
                .setAwtTooltip(false)
                .setPreferredPosition(Balloon.Position.above)
                .setRequestFocus(false)

            hint?.show(layeredPane, position.x, position.y, textField, hintHint)
            isShowing = true
        } catch (_: IllegalComponentStateException) {
            hidePopup()
        }
    }

    private fun hidePopup() {
        hint?.hide()
        hint = null
        isShowing = false
    }

    private fun adjustPopupSize() {
        val rowCount = minOf(listModel.size, MAX_VISIBLE_ROWS)
        val rowHeight = suggestionList.fixedCellHeight.takeIf { it > 0 }
            ?: suggestionList.getFontMetrics(suggestionList.font).height + JBUI.scale(8)
        val height = rowCount * rowHeight + JBUI.scale(8)

        contentPanel.preferredSize = Dimension(JBUI.scale(POPUP_WIDTH), height)
        scrollPane.preferredSize = Dimension(JBUI.scale(POPUP_WIDTH), height)

        // If hint is visible, update its size
        hint?.pack()
    }

    private fun calculatePosition(layeredPane: JLayeredPane): Point {
        val fieldLocationInPane = SwingUtilities.convertPoint(
            textField, 0, 0, layeredPane
        )

        val popupSize = contentPanel.preferredSize
        val paneSize = layeredPane.size

        // Default: position above the text field
        var x = fieldLocationInPane.x
        var y = fieldLocationInPane.y - popupSize.height - JBUI.scale(2)

        // Boundary fitting - keep within layered pane bounds
        // Horizontal: don't go off right edge
        if (x + popupSize.width > paneSize.width) {
            x = maxOf(0, paneSize.width - popupSize.width)
        }

        // Vertical: if doesn't fit above, try below
        if (y < 0) {
            y = fieldLocationInPane.y + textField.height + JBUI.scale(2)
        }

        // If still doesn't fit below, crop to top
        if (y + popupSize.height > paneSize.height) {
            y = maxOf(0, paneSize.height - popupSize.height)
        }

        return Point(x, y)
    }

    private fun updateHintLocation() {
        val hint = hint ?: return
        if (!hint.isVisible) return

        val rootPane = textField.rootPane ?: return
        val layeredPane = rootPane.layeredPane ?: return

        try {
            val position = calculatePosition(layeredPane)
            hint.updateLocation(position.x, position.y)
        } catch (_: IllegalComponentStateException) {
            hidePopup()
        }
    }

    private fun moveSelection(delta: Int) {
        val currentIndex = suggestionList.selectedIndex
        val newIndex = (currentIndex + delta).coerceIn(0, listModel.size - 1)
        suggestionList.selectedIndex = newIndex
        suggestionList.ensureIndexIsVisible(newIndex)
    }

    private fun applySuggestion() {
        val selectedItem = suggestionList.selectedValue ?: return
        textField.text = selectedItem.text
        textField.caretPosition = selectedItem.text.length
        hidePopup()
        onCommandSelected?.invoke(selectedItem.text)
    }

    override fun dispose() {
        alarm.cancelAllRequests()
        hidePopup()
    }

    /**
     * Data class for suggestion items in the popup.
     */
    data class SuggestionItem(
        val text: String,
        val displayText: String,
        val description: String,
        val isCommand: Boolean,
        val aliases: List<String>
    )

    /**
     * Custom cell renderer for suggestion items.
     */
    private class SuggestionCellRenderer : ListCellRenderer<SuggestionItem> {
        private val panel = JPanel(BorderLayout())
        private val nameLabel = JLabel()
        private val descLabel = JLabel()

        init {
            panel.border = JBUI.Borders.empty(4, 8)
            panel.isOpaque = true

            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD)

            descLabel.foreground = HytaleTheme.mutedText
            descLabel.font = descLabel.font.deriveFont(descLabel.font.size - 1f)

            val textPanel = JPanel(BorderLayout())
            textPanel.isOpaque = false
            textPanel.add(nameLabel, BorderLayout.WEST)
            textPanel.add(descLabel, BorderLayout.CENTER)

            panel.add(textPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out SuggestionItem>,
            value: SuggestionItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            panel.background = if (isSelected) {
                JBColor.namedColor("List.selectionBackground", UIUtil.getListSelectionBackground(true))
            } else {
                JBColor.namedColor("List.background", UIUtil.getListBackground())
            }

            nameLabel.foreground = if (isSelected) {
                JBColor.namedColor("List.selectionForeground", UIUtil.getListSelectionForeground(true))
            } else {
                JBColor.namedColor("List.foreground", UIUtil.getListForeground())
            }

            nameLabel.text = value.displayText

            val aliasText = if (value.aliases.isNotEmpty()) {
                " (${value.aliases.joinToString(", ")})"
            } else ""

            descLabel.text = if (value.description.isNotEmpty()) {
                "  ${value.description}$aliasText"
            } else {
                aliasText
            }

            descLabel.foreground = if (isSelected) {
                JBColor.namedColor("List.selectionInactiveForeground", HytaleTheme.mutedText)
            } else {
                HytaleTheme.mutedText
            }

            return panel
        }
    }
}
