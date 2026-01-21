package com.hytaledocs.intellij.uifile.parser

import com.hytaledocs.intellij.uifile.psi.UIElementTypes
import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

/**
 * Parser for Hytale UI files.
 * Since UI files use a relatively simple structure, we use a basic parsing strategy.
 */
class UIParser : PsiParser {

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val rootMarker = builder.mark()

        // Simply consume all tokens - the lexer handles most of the work
        // for syntax highlighting purposes. Full parsing would be more complex.
        while (!builder.eof()) {
            builder.advanceLexer()
        }

        rootMarker.done(UIElementTypes.FILE)
        return builder.treeBuilt
    }
}
