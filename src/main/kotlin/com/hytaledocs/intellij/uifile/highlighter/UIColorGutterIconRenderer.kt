package com.hytaledocs.intellij.uifile.highlighter

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.Icon

/**
 * Gutter icon renderer that displays a color swatch for hex color values.
 * Clicking the icon opens a color picker dialog.
 */
class UIColorGutterIconRenderer(
    private val color: Color,
    private val colorString: String,
    private val element: PsiElement
) : GutterIconRenderer() {

    companion object {
        private const val ICON_SIZE = 12
    }

    override fun getIcon(): Icon {
        return ColorIcon(JBUI.scale(ICON_SIZE), color)
    }

    override fun getTooltipText(): String {
        val r = color.red
        val g = color.green
        val b = color.blue
        val a = color.alpha

        return if (a < 255) {
            "$colorString (R:$r, G:$g, B:$b, A:$a)"
        } else {
            "$colorString (R:$r, G:$g, B:$b)"
        }
    }

    override fun getClickAction(): AnAction {
        return UIColorPickerAction(element, color, colorString)
    }

    override fun isNavigateAction(): Boolean = true

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UIColorGutterIconRenderer) return false
        return color == other.color && colorString == other.colorString
    }

    override fun hashCode(): Int {
        var result = color.hashCode()
        result = 31 * result + colorString.hashCode()
        return result
    }
}
