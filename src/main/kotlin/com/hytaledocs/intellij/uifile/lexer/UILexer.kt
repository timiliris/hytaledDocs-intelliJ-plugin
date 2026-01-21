package com.hytaledocs.intellij.uifile.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Lexer for Hytale UI files.
 * Tokenizes .ui file content with proper support for Hytale's UI DSL syntax:
 * - Style variables: @PrimaryButton = TextButtonStyle(...)
 * - Import variables: $C = "../Common.ui"
 * - Element IDs: Label #StatusText { ... }
 * - Colors with alpha: #141c26(0.98)
 * - Properties with parentheses: Anchor: (Width: 400, Height: 280);
 */
class UILexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var bufferEnd: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufferEnd = endOffset
        this.tokenStart = startOffset
        this.tokenEnd = startOffset
        this.tokenType = null
        advance()
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = tokenType

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun advance() {
        tokenStart = tokenEnd

        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }

        val c = buffer[tokenStart]

        when {
            // Whitespace
            c.isWhitespace() -> {
                tokenEnd = tokenStart
                while (tokenEnd < bufferEnd && buffer[tokenEnd].isWhitespace()) {
                    tokenEnd++
                }
                tokenType = UITokenTypes.WHITESPACE
            }

            // Line comment //
            c == '/' && tokenStart + 1 < bufferEnd && buffer[tokenStart + 1] == '/' -> {
                tokenEnd = tokenStart + 2
                while (tokenEnd < bufferEnd && buffer[tokenEnd] != '\n') {
                    tokenEnd++
                }
                tokenType = UITokenTypes.LINE_COMMENT
            }

            // Block comment /* */
            c == '/' && tokenStart + 1 < bufferEnd && buffer[tokenStart + 1] == '*' -> {
                tokenEnd = tokenStart + 2
                while (tokenEnd + 1 < bufferEnd) {
                    if (buffer[tokenEnd] == '*' && buffer[tokenEnd + 1] == '/') {
                        tokenEnd += 2
                        break
                    }
                    tokenEnd++
                }
                if (tokenEnd + 1 >= bufferEnd) tokenEnd = bufferEnd
                tokenType = UITokenTypes.BLOCK_COMMENT
            }

            // String literals
            c == '"' -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < bufferEnd) {
                    val ch = buffer[tokenEnd]
                    if (ch == '"') {
                        tokenEnd++
                        break
                    }
                    if (ch == '\\' && tokenEnd + 1 < bufferEnd) {
                        tokenEnd += 2  // Skip escaped character
                    } else {
                        tokenEnd++
                    }
                }
                tokenType = UITokenTypes.STRING
            }

            // Style variable @Name
            c == '@' -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < bufferEnd && (buffer[tokenEnd].isLetterOrDigit() || buffer[tokenEnd] == '_')) {
                    tokenEnd++
                }
                tokenType = if (tokenEnd > tokenStart + 1) UITokenTypes.STYLE_VAR else UITokenTypes.AT
            }

            // Import variable $Name, Localized string $L.key, or Binding ${path}
            c == '$' -> {
                tokenEnd = tokenStart + 1

                // Check for binding: ${path}
                if (tokenEnd < bufferEnd && buffer[tokenEnd] == '{') {
                    tokenEnd++ // Skip {
                    val bindingStart = tokenEnd
                    var braceDepth = 1
                    while (tokenEnd < bufferEnd && braceDepth > 0) {
                        when (buffer[tokenEnd]) {
                            '{' -> braceDepth++
                            '}' -> braceDepth--
                        }
                        if (braceDepth > 0) tokenEnd++
                    }
                    if (tokenEnd < bufferEnd && buffer[tokenEnd] == '}') {
                        tokenEnd++ // Include closing }
                    }
                    tokenType = UITokenTypes.BINDING
                }
                // Check for localized string: $L.key
                else if (tokenEnd < bufferEnd && buffer[tokenEnd] == 'L') {
                    tokenEnd++ // Skip L
                    if (tokenEnd < bufferEnd && buffer[tokenEnd] == '.') {
                        tokenEnd++ // Skip .
                        // Parse the key path (can contain dots)
                        while (tokenEnd < bufferEnd &&
                               (buffer[tokenEnd].isLetterOrDigit() || buffer[tokenEnd] == '_' || buffer[tokenEnd] == '.')) {
                            tokenEnd++
                        }
                        tokenType = UITokenTypes.LOCALIZED_STRING
                    } else {
                        // Just $L without dot, treat as import var
                        tokenType = UITokenTypes.IMPORT_VAR
                    }
                }
                // Regular import variable: $Name
                else {
                    while (tokenEnd < bufferEnd && (buffer[tokenEnd].isLetterOrDigit() || buffer[tokenEnd] == '_')) {
                        tokenEnd++
                    }
                    tokenType = if (tokenEnd > tokenStart + 1) UITokenTypes.IMPORT_VAR else UITokenTypes.DOLLAR
                }
            }

            // Color #RRGGBB or #RRGGBB(alpha) or Element ID #Name
            c == '#' -> {
                tokenEnd = tokenStart + 1

                // Check if it's a hex color or an element ID
                val firstChar = if (tokenEnd < bufferEnd) buffer[tokenEnd] else ' '

                // First, check if this could be an element ID by looking ahead
                // Element IDs contain letters that aren't valid hex digits (g-z, G-Z)
                // Colors are pure hex: 0-9, a-f, A-F
                var couldBeElementId = false
                var lookAhead = tokenEnd
                while (lookAhead < bufferEnd && (buffer[lookAhead].isLetterOrDigit() || buffer[lookAhead] == '_')) {
                    val ch = buffer[lookAhead]
                    // If we find a letter that's not a hex digit, it must be an element ID
                    if (ch.isLetter() && !isHexDigit(ch)) {
                        couldBeElementId = true
                        break
                    }
                    lookAhead++
                }

                if (couldBeElementId || (firstChar.isLetter() && !isHexDigit(firstChar)) || firstChar == '_') {
                    // Element ID: #StatusText, #CloseButton, etc.
                    while (tokenEnd < bufferEnd && (buffer[tokenEnd].isLetterOrDigit() || buffer[tokenEnd] == '_')) {
                        tokenEnd++
                    }
                    tokenType = UITokenTypes.ELEMENT_ID
                } else if (isHexDigit(firstChar)) {
                    // Parse hex color
                    while (tokenEnd < bufferEnd && isHexDigit(buffer[tokenEnd])) {
                        tokenEnd++
                    }
                    // Check for alpha in parentheses: #RRGGBB(0.98)
                    if (tokenEnd < bufferEnd && buffer[tokenEnd] == '(') {
                        tokenEnd++
                        while (tokenEnd < bufferEnd && buffer[tokenEnd] != ')') {
                            tokenEnd++
                        }
                        if (tokenEnd < bufferEnd && buffer[tokenEnd] == ')') {
                            tokenEnd++
                        }
                    }
                    val hexLength = tokenEnd - tokenStart - 1
                    tokenType = if (hexLength >= 3) UITokenTypes.COLOR else UITokenTypes.BAD_CHARACTER
                } else {
                    tokenType = UITokenTypes.HASH
                }
            }

            // Number (integer or float, possibly negative)
            c.isDigit() || (c == '-' && tokenStart + 1 < bufferEnd && buffer[tokenStart + 1].isDigit()) ||
            (c == '.' && tokenStart + 1 < bufferEnd && buffer[tokenStart + 1].isDigit()) -> {
                tokenEnd = tokenStart
                if (buffer[tokenEnd] == '-') tokenEnd++

                // Integer part
                while (tokenEnd < bufferEnd && buffer[tokenEnd].isDigit()) {
                    tokenEnd++
                }
                // Decimal part
                if (tokenEnd < bufferEnd && buffer[tokenEnd] == '.') {
                    tokenEnd++
                    while (tokenEnd < bufferEnd && buffer[tokenEnd].isDigit()) {
                        tokenEnd++
                    }
                }
                // Percent
                if (tokenEnd < bufferEnd && buffer[tokenEnd] == '%') {
                    tokenEnd++
                }
                tokenType = UITokenTypes.NUMBER
            }

            // Punctuation
            c == '{' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.LBRACE
            }
            c == '}' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.RBRACE
            }
            c == '(' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.LPAREN
            }
            c == ')' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.RPAREN
            }
            c == '[' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.LBRACKET
            }
            c == ']' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.RBRACKET
            }
            c == ':' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.COLON
            }
            c == ',' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.COMMA
            }
            c == ';' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.SEMICOLON
            }
            c == '.' -> {
                // Check for spread syntax: ...
                if (tokenStart + 2 < bufferEnd &&
                    buffer[tokenStart + 1] == '.' &&
                    buffer[tokenStart + 2] == '.') {
                    tokenEnd = tokenStart + 3
                    tokenType = UITokenTypes.SPREAD
                } else {
                    tokenEnd = tokenStart + 1
                    tokenType = UITokenTypes.DOT
                }
            }
            c == '=' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.EQUALS
            }

            // Question mark (for ternary expressions)
            c == '?' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.QUESTION
            }

            // Pipe (for filters or alternatives)
            c == '|' -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.PIPE
            }

            // Identifier (component name, property name, keyword, etc.)
            // Also handles: res://path, calc(...), function calls
            c.isLetter() || c == '_' -> {
                tokenEnd = tokenStart
                while (tokenEnd < bufferEnd && (buffer[tokenEnd].isLetterOrDigit() || buffer[tokenEnd] == '_')) {
                    tokenEnd++
                }
                val text = buffer.subSequence(tokenStart, tokenEnd).toString()

                // Check for resource path: res://path
                if (text == "res" && tokenEnd + 2 < bufferEnd &&
                    buffer[tokenEnd] == ':' && buffer[tokenEnd + 1] == '/' && buffer[tokenEnd + 2] == '/') {
                    tokenEnd += 3 // Skip ://
                    // Parse the resource path
                    while (tokenEnd < bufferEnd &&
                           (buffer[tokenEnd].isLetterOrDigit() ||
                            buffer[tokenEnd] == '_' ||
                            buffer[tokenEnd] == '/' ||
                            buffer[tokenEnd] == '.' ||
                            buffer[tokenEnd] == '-')) {
                        tokenEnd++
                    }
                    tokenType = UITokenTypes.RESOURCE_PATH
                }
                // Check for calc() expression
                else if (text == "calc" && tokenEnd < bufferEnd && buffer[tokenEnd] == '(') {
                    tokenEnd++ // Skip (
                    var parenDepth = 1
                    while (tokenEnd < bufferEnd && parenDepth > 0) {
                        when (buffer[tokenEnd]) {
                            '(' -> parenDepth++
                            ')' -> parenDepth--
                        }
                        tokenEnd++
                    }
                    tokenType = UITokenTypes.CALC
                }
                // Check for function call: func(...)
                else if (UITokenTypes.KNOWN_FUNCTIONS.contains(text) &&
                         tokenEnd < bufferEnd && buffer[tokenEnd] == '(') {
                    // Don't consume the parentheses - let the parser handle function calls
                    tokenType = UITokenTypes.IDENTIFIER
                }
                else {
                    tokenType = when {
                        // Check booleans BEFORE keywords since "true"/"false" are in KEYWORDS
                        text == "true" || text == "false" -> UITokenTypes.BOOLEAN
                        UITokenTypes.KNOWN_COMPONENTS.contains(text) -> UITokenTypes.COMPONENT
                        UITokenTypes.KNOWN_STYLE_FUNCTIONS.contains(text) -> UITokenTypes.COMPONENT
                        UITokenTypes.KNOWN_PROPERTIES.contains(text) -> UITokenTypes.PROPERTY
                        UITokenTypes.KEYWORDS.contains(text) -> UITokenTypes.KEYWORD
                        UITokenTypes.KNOWN_FUNCTIONS.contains(text) -> UITokenTypes.IDENTIFIER
                        else -> UITokenTypes.IDENTIFIER
                    }
                }
            }

            // Unknown character
            else -> {
                tokenEnd = tokenStart + 1
                tokenType = UITokenTypes.BAD_CHARACTER
            }
        }
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = bufferEnd

    private fun isHexDigit(c: Char): Boolean {
        return c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }
}
