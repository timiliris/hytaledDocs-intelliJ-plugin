package com.hytaledocs.intellij.uifile.highlighter

import com.hytaledocs.intellij.uifile.lexer.UILexer
import com.hytaledocs.intellij.uifile.lexer.UITokenTypes
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

/**
 * Syntax highlighter for Hytale UI files.
 * Provides color coding for different token types in .ui files.
 */
class UISyntaxHighlighter : SyntaxHighlighterBase() {

    companion object {
        // Component names (like class names)
        val COMPONENT = createTextAttributesKey("UI_COMPONENT", DefaultLanguageHighlighterColors.CLASS_NAME)

        // Property names (like instance fields)
        val PROPERTY = createTextAttributesKey("UI_PROPERTY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)

        // Identifiers
        val IDENTIFIER = createTextAttributesKey("UI_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)

        // String literals
        val STRING = createTextAttributesKey("UI_STRING", DefaultLanguageHighlighterColors.STRING)

        // Number literals
        val NUMBER = createTextAttributesKey("UI_NUMBER", DefaultLanguageHighlighterColors.NUMBER)

        // Color literals (hex colors)
        val COLOR = createTextAttributesKey("UI_COLOR", DefaultLanguageHighlighterColors.CONSTANT)

        // Boolean literals
        val BOOLEAN = createTextAttributesKey("UI_BOOLEAN", DefaultLanguageHighlighterColors.KEYWORD)

        // Keywords
        val KEYWORD = createTextAttributesKey("UI_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)

        // Style variables (@PrimaryButton)
        val STYLE_VAR = createTextAttributesKey("UI_STYLE_VAR", DefaultLanguageHighlighterColors.STATIC_FIELD)

        // Import variables ($C)
        val IMPORT_VAR = createTextAttributesKey("UI_IMPORT_VAR", DefaultLanguageHighlighterColors.GLOBAL_VARIABLE)

        // Element IDs (#StatusText)
        val ELEMENT_ID = createTextAttributesKey("UI_ELEMENT_ID", DefaultLanguageHighlighterColors.METADATA)

        // Comments
        val LINE_COMMENT = createTextAttributesKey("UI_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val BLOCK_COMMENT = createTextAttributesKey("UI_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)

        // Braces and brackets
        val BRACES = createTextAttributesKey("UI_BRACES", DefaultLanguageHighlighterColors.BRACES)
        val BRACKETS = createTextAttributesKey("UI_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
        val PARENTHESES = createTextAttributesKey("UI_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)

        // Operators and punctuation
        val COLON = createTextAttributesKey("UI_COLON", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val COMMA = createTextAttributesKey("UI_COMMA", DefaultLanguageHighlighterColors.COMMA)
        val SEMICOLON = createTextAttributesKey("UI_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)
        val DOT = createTextAttributesKey("UI_DOT", DefaultLanguageHighlighterColors.DOT)
        val EQUALS = createTextAttributesKey("UI_EQUALS", DefaultLanguageHighlighterColors.OPERATION_SIGN)

        // Bad character
        val BAD_CHARACTER = createTextAttributesKey("UI_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

        // Token to attributes mapping
        private val COMPONENT_KEYS = arrayOf(COMPONENT)
        private val PROPERTY_KEYS = arrayOf(PROPERTY)
        private val IDENTIFIER_KEYS = arrayOf(IDENTIFIER)
        private val STRING_KEYS = arrayOf(STRING)
        private val NUMBER_KEYS = arrayOf(NUMBER)
        private val COLOR_KEYS = arrayOf(COLOR)
        private val BOOLEAN_KEYS = arrayOf(BOOLEAN)
        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val STYLE_VAR_KEYS = arrayOf(STYLE_VAR)
        private val IMPORT_VAR_KEYS = arrayOf(IMPORT_VAR)
        private val ELEMENT_ID_KEYS = arrayOf(ELEMENT_ID)
        private val LINE_COMMENT_KEYS = arrayOf(LINE_COMMENT)
        private val BLOCK_COMMENT_KEYS = arrayOf(BLOCK_COMMENT)
        private val BRACES_KEYS = arrayOf(BRACES)
        private val BRACKETS_KEYS = arrayOf(BRACKETS)
        private val PARENTHESES_KEYS = arrayOf(PARENTHESES)
        private val COLON_KEYS = arrayOf(COLON)
        private val COMMA_KEYS = arrayOf(COMMA)
        private val SEMICOLON_KEYS = arrayOf(SEMICOLON)
        private val DOT_KEYS = arrayOf(DOT)
        private val EQUALS_KEYS = arrayOf(EQUALS)
        private val BAD_CHARACTER_KEYS = arrayOf(BAD_CHARACTER)
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }

    override fun getHighlightingLexer(): Lexer = UILexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        return when (tokenType) {
            UITokenTypes.COMPONENT -> COMPONENT_KEYS
            UITokenTypes.PROPERTY -> PROPERTY_KEYS
            UITokenTypes.IDENTIFIER -> IDENTIFIER_KEYS
            UITokenTypes.STRING -> STRING_KEYS
            UITokenTypes.NUMBER -> NUMBER_KEYS
            UITokenTypes.COLOR -> COLOR_KEYS
            UITokenTypes.BOOLEAN -> BOOLEAN_KEYS
            UITokenTypes.KEYWORD -> KEYWORD_KEYS
            UITokenTypes.STYLE_VAR -> STYLE_VAR_KEYS
            UITokenTypes.IMPORT_VAR -> IMPORT_VAR_KEYS
            UITokenTypes.ELEMENT_ID -> ELEMENT_ID_KEYS
            UITokenTypes.LINE_COMMENT -> LINE_COMMENT_KEYS
            UITokenTypes.BLOCK_COMMENT -> BLOCK_COMMENT_KEYS
            UITokenTypes.COMMENT -> LINE_COMMENT_KEYS
            UITokenTypes.LBRACE, UITokenTypes.RBRACE -> BRACES_KEYS
            UITokenTypes.LBRACKET, UITokenTypes.RBRACKET -> BRACKETS_KEYS
            UITokenTypes.LPAREN, UITokenTypes.RPAREN -> PARENTHESES_KEYS
            UITokenTypes.COLON -> COLON_KEYS
            UITokenTypes.EQUALS -> EQUALS_KEYS
            UITokenTypes.COMMA -> COMMA_KEYS
            UITokenTypes.SEMICOLON -> SEMICOLON_KEYS
            UITokenTypes.DOT -> DOT_KEYS
            UITokenTypes.AT, UITokenTypes.HASH, UITokenTypes.DOLLAR -> COLON_KEYS
            UITokenTypes.BAD_CHARACTER -> BAD_CHARACTER_KEYS
            else -> EMPTY_KEYS
        }
    }
}
