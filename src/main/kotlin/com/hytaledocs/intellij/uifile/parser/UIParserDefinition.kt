package com.hytaledocs.intellij.uifile.parser

import com.hytaledocs.intellij.uifile.lexer.UILexer
import com.hytaledocs.intellij.uifile.lexer.UITokenTypes
import com.hytaledocs.intellij.uifile.psi.UIElement
import com.hytaledocs.intellij.uifile.psi.UIElementTypes
import com.hytaledocs.intellij.uifile.psi.UIFile
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * Parser definition for Hytale UI files.
 * Defines how the IDE should process .ui files.
 */
class UIParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = UILexer()

    override fun createParser(project: Project?): PsiParser = UIParser()

    override fun getFileNodeType(): IFileElementType = UIElementTypes.FILE

    override fun getWhitespaceTokens(): TokenSet = UITokenTypes.WHITESPACE_TOKENS

    override fun getCommentTokens(): TokenSet = UITokenTypes.COMMENT_TOKENS

    override fun getStringLiteralElements(): TokenSet = UITokenTypes.STRING_TOKENS

    override fun createElement(node: ASTNode): PsiElement = UIElement(node.elementType)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = UIFile(viewProvider)
}
