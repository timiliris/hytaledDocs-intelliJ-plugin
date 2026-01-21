package com.hytaledocs.intellij.uifile

import com.hytaledocs.intellij.uifile.lexer.UITokenTypes
import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

/**
 * Brace matcher for UI files.
 * Matches curly braces {} and parentheses ().
 */
class UIBraceMatcher : PairedBraceMatcher {

    companion object {
        private val PAIRS = arrayOf(
            BracePair(UITokenTypes.LBRACE, UITokenTypes.RBRACE, true),
            BracePair(UITokenTypes.LPAREN, UITokenTypes.RPAREN, false),
            BracePair(UITokenTypes.LBRACKET, UITokenTypes.RBRACKET, false)
        )
    }

    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean {
        return true
    }

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int {
        return openingBraceOffset
    }
}
