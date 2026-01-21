package com.hytaledocs.intellij.uifile

import com.hytaledocs.intellij.uifile.lexer.UILexer
import com.hytaledocs.intellij.uifile.lexer.UITokenTypes
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.FoldingGroup
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Code folding builder for UI files.
 * Allows folding of component blocks (content between { and }).
 */
class UIFoldingBuilder : FoldingBuilderEx() {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val descriptors = mutableListOf<FoldingDescriptor>()
        collectFoldingRegions(root, document, descriptors)
        return descriptors.toTypedArray()
    }

    private fun collectFoldingRegions(
        root: PsiElement,
        document: Document,
        descriptors: MutableList<FoldingDescriptor>
    ) {
        val text = root.text
        val lexer = UILexer()
        lexer.start(text, 0, text.length, 0)

        val braceStack = ArrayDeque<BraceInfo>()

        while (lexer.tokenType != null) {
            val tokenType = lexer.tokenType
            val start = lexer.tokenStart
            val end = lexer.tokenEnd

            when (tokenType) {
                UITokenTypes.LBRACE -> {
                    // Find the component name before this brace
                    val componentName = findComponentNameBefore(text, start)
                    braceStack.addLast(BraceInfo(start, componentName))
                }

                UITokenTypes.RBRACE -> {
                    if (braceStack.isNotEmpty()) {
                        val openBrace = braceStack.removeLast()
                        val openLine = document.getLineNumber(openBrace.offset)
                        val closeLine = document.getLineNumber(end)

                        // Only fold if spans multiple lines
                        if (closeLine > openLine) {
                            val range = TextRange(openBrace.offset, end)
                            val placeholder = getPlaceholderText(openBrace.componentName)

                            descriptors.add(
                                FoldingDescriptor(
                                    root.node,
                                    range,
                                    FoldingGroup.newGroup("ui-component"),
                                    placeholder
                                )
                            )
                        }
                    }
                }
            }

            lexer.advance()
        }
    }

    private fun findComponentNameBefore(text: String, braceOffset: Int): String? {
        // Look backwards from the brace to find the component name
        var i = braceOffset - 1

        // Skip whitespace
        while (i >= 0 && text[i].isWhitespace()) {
            i--
        }

        if (i < 0) return null

        // Find the end of the identifier
        val identEnd = i + 1

        // Find the start of the identifier
        while (i >= 0 && (text[i].isLetterOrDigit() || text[i] == '_')) {
            i--
        }

        val identStart = i + 1

        if (identStart >= identEnd) return null

        return text.substring(identStart, identEnd)
    }

    private fun getPlaceholderText(componentName: String?): String {
        return if (componentName != null) {
            "$componentName {...}"
        } else {
            "{...}"
        }
    }

    override fun getPlaceholderText(node: ASTNode): String = "{...}"

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    private data class BraceInfo(val offset: Int, val componentName: String?)
}
