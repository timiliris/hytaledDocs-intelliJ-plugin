package com.hytaledocs.intellij.uifile.highlighter

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import java.awt.Color
import javax.swing.JColorChooser

/**
 * Action that opens a color picker dialog when clicking on a color gutter icon.
 */
class UIColorPickerAction(
    private val element: PsiElement,
    private val currentColor: Color,
    private val currentColorString: String
) : AnAction("Choose Color...") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = element.project
        val file = element.containingFile ?: return

        // Show standard Swing color chooser dialog
        val newColor = JColorChooser.showDialog(
            null,  // Parent component
            "Choose Color",
            currentColor
        ) ?: return

        // Format new color as hex string
        val newColorString = formatColorAsHex(newColor)

        // Replace the color in the document
        WriteCommandAction.runWriteCommandAction(project) {
            val document = file.viewProvider.document ?: return@runWriteCommandAction
            val startOffset = element.textRange.startOffset
            val endOffset = element.textRange.endOffset

            document.replaceString(startOffset, endOffset, newColorString)
        }
    }

    private fun formatColorAsHex(color: Color): String {
        return if (color.alpha < 255) {
            String.format("#%02X%02X%02X%02X", color.red, color.green, color.blue, color.alpha)
        } else {
            String.format("#%02X%02X%02X", color.red, color.green, color.blue)
        }
    }
}
