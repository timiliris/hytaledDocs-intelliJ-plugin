package com.hytaledocs.intellij.uifile.highlighter

import com.hytaledocs.intellij.settings.HytaleServerSettings
import com.hytaledocs.intellij.uifile.lexer.UITokenTypes
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Annotator that adds color preview gutter icons for hex color values in UI files.
 */
class UIColorAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Check if UI file support is enabled
        val project = element.project
        if (!HytaleServerSettings.getInstance(project).uiFileSupportEnabled) return

        // Check if this element is a color token
        val node = element.node ?: return
        if (node.elementType != UITokenTypes.COLOR) return

        val colorString = element.text
        val color = parseHexColor(colorString) ?: return

        // Create annotation with gutter icon
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .gutterIconRenderer(UIColorGutterIconRenderer(color, colorString, element))
            .create()
    }

    companion object {
        /**
         * Parse a hex color string into a Color object.
         * Supports formats: #RGB, #RRGGBB, #RRGGBBAA
         */
        fun parseHexColor(colorString: String): Color? {
            if (!colorString.startsWith("#")) return null

            val hex = colorString.substring(1)

            return try {
                when (hex.length) {
                    3 -> {
                        // #RGB -> #RRGGBB
                        val r = hex[0].toString().repeat(2).toInt(16)
                        val g = hex[1].toString().repeat(2).toInt(16)
                        val b = hex[2].toString().repeat(2).toInt(16)
                        JBColor(Color(r, g, b), Color(r, g, b))
                    }
                    6 -> {
                        // #RRGGBB
                        val r = hex.substring(0, 2).toInt(16)
                        val g = hex.substring(2, 4).toInt(16)
                        val b = hex.substring(4, 6).toInt(16)
                        JBColor(Color(r, g, b), Color(r, g, b))
                    }
                    8 -> {
                        // #RRGGBBAA
                        val r = hex.substring(0, 2).toInt(16)
                        val g = hex.substring(2, 4).toInt(16)
                        val b = hex.substring(4, 6).toInt(16)
                        val a = hex.substring(6, 8).toInt(16)
                        JBColor(Color(r, g, b, a), Color(r, g, b, a))
                    }
                    else -> null
                }
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
