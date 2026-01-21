package com.hytaledocs.intellij.uifile.lexer

import com.hytaledocs.intellij.uifile.UILanguage
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * Token types for the Hytale UI file lexer.
 */
object UITokenTypes {

    // Basic tokens
    val WHITESPACE = UIElementType("WHITESPACE")
    val COMMENT = UIElementType("COMMENT")
    val LINE_COMMENT = UIElementType("LINE_COMMENT")
    val BLOCK_COMMENT = UIElementType("BLOCK_COMMENT")

    // Literals
    val STRING = UIElementType("STRING")
    val NUMBER = UIElementType("NUMBER")
    val COLOR = UIElementType("COLOR")             // #RRGGBB or #RRGGBB(alpha)
    val BOOLEAN = UIElementType("BOOLEAN")

    // Identifiers
    val IDENTIFIER = UIElementType("IDENTIFIER")
    val COMPONENT = UIElementType("COMPONENT")     // Group, Label, TextButton, etc.
    val PROPERTY = UIElementType("PROPERTY")       // Anchor, Background, Text, etc.
    val STYLE_VAR = UIElementType("STYLE_VAR")     // @PrimaryButton, @SecondaryButton
    val IMPORT_VAR = UIElementType("IMPORT_VAR")   // $C, $Common
    val ELEMENT_ID = UIElementType("ELEMENT_ID")   // #StatusText, #CloseButton

    // Punctuation
    val LBRACE = UIElementType("LBRACE")           // {
    val RBRACE = UIElementType("RBRACE")           // }
    val LPAREN = UIElementType("LPAREN")           // (
    val RPAREN = UIElementType("RPAREN")           // )
    val LBRACKET = UIElementType("LBRACKET")       // [
    val RBRACKET = UIElementType("RBRACKET")       // ]
    val COLON = UIElementType("COLON")             // :
    val COMMA = UIElementType("COMMA")             // ,
    val SEMICOLON = UIElementType("SEMICOLON")     // ;
    val DOT = UIElementType("DOT")                 // .
    val EQUALS = UIElementType("EQUALS")           // =
    val AT = UIElementType("AT")                   // @
    val HASH = UIElementType("HASH")               // #
    val DOLLAR = UIElementType("DOLLAR")           // $
    val SPREAD = UIElementType("SPREAD")           // ...
    val QUESTION = UIElementType("QUESTION")       // ?
    val PIPE = UIElementType("PIPE")               // |

    // Advanced value tokens
    val LOCALIZED_STRING = UIElementType("LOCALIZED_STRING")   // $L.key
    val BINDING = UIElementType("BINDING")                     // ${binding.path}
    val RESOURCE_PATH = UIElementType("RESOURCE_PATH")         // res://path
    val CALC = UIElementType("CALC")                           // calc(...)
    val FUNCTION_CALL = UIElementType("FUNCTION_CALL")         // func(...)

    // Keywords
    val KEYWORD = UIElementType("KEYWORD")

    // Bad character (error)
    val BAD_CHARACTER = UIElementType("BAD_CHARACTER")

    // Token sets
    val WHITESPACE_TOKENS = TokenSet.create(WHITESPACE)
    val COMMENT_TOKENS = TokenSet.create(COMMENT, LINE_COMMENT, BLOCK_COMMENT)
    val STRING_TOKENS = TokenSet.create(STRING)

    /**
     * Known Hytale UI BASE component types.
     *
     * These components work WITHOUT importing Common.ui.
     * For styled components (TextButton, TextField, etc.), use Common.ui templates:
     * Example: $C.@TextButton { @Text = "Click"; }
     *
     * NOTE: Image is DEPRECATED - use Group with Background property instead.
     */
    val KNOWN_COMPONENTS = setOf(
        // Layout components
        "Group",
        "ScrollView",

        // Text components
        "Label",

        // Interactive components
        "Slider",
        "ProgressBar"
    )

    /**
     * Known Hytale UI properties.
     */
    val KNOWN_PROPERTIES = setOf(
        // Anchor/Layout
        "Anchor", "Width", "Height", "X", "Y",
        "MinWidth", "MinHeight", "MaxWidth", "MaxHeight",
        "FlexWeight", "LayoutMode", "Alignment",
        "HorizontalAlignment", "VerticalAlignment",

        // Spacing
        "Padding", "Margin", "Gap", "Spacing",
        "Full", "Top", "Left", "Right", "Bottom",

        // Visual
        "Background", "Foreground", "Color",
        "Opacity", "Alpha", "Visible", "Enabled",
        "BorderColor", "BorderWidth", "CornerRadius",

        // Text properties
        "Text", "FontSize", "TextColor", "FontFamily",
        "RenderBold", "RenderItalic", "TextWrap",
        "LineHeight", "LetterSpacing",

        // Style
        "Style", "LabelStyle", "ButtonStyle",
        "Default", "Hovered", "Pressed", "Disabled", "Focused",

        // Image
        "Src", "Source", "Sprite", "Image", "Icon", "Texture",
        "Tint", "TintColor", "ScaleMode", "PreserveAspect",

        // Interaction
        "OnClick", "OnHover", "OnPress", "OnRelease",
        "OnFocus", "OnBlur", "OnInput", "OnChange",
        "Focusable", "Clickable", "Draggable",

        // Slider
        "Min", "Max", "Value", "Step",
        "Orientation", "Direction",

        // Animation
        "Animation", "Transition", "Duration", "Delay", "Easing",

        // State
        "Id", "Name", "Class", "Data",
        "Selected", "Checked", "Active"
    )

    /**
     * Known style function names.
     */
    val KNOWN_STYLE_FUNCTIONS = setOf(
        "TextButtonStyle", "ButtonStyle", "LabelStyle",
        "ImageStyle", "SliderStyle", "ScrollbarStyle",
        "ProgressBarStyle", "InputStyle", "TooltipStyle"
    )

    /**
     * Keywords that can appear in UI files.
     */
    val KEYWORDS = setOf(
        "true", "false", "null", "none",
        "inherit", "auto", "default",
        "Center", "Left", "Right", "Top", "Bottom",
        "Stretch", "Fill", "Fit", "Cover", "Contain"
    )

    /**
     * Known built-in functions for UI calculations and transformations.
     */
    val KNOWN_FUNCTIONS = setOf(
        // Math functions
        "min", "max", "clamp", "abs", "floor", "ceil", "round",
        "sin", "cos", "tan", "sqrt", "pow", "log",
        // Color functions
        "rgb", "rgba", "hsl", "hsla", "lighten", "darken", "saturate", "desaturate",
        "mix", "alpha", "opacity",
        // Layout functions
        "calc", "var", "env",
        // String functions
        "format", "concat", "uppercase", "lowercase",
        // Animation functions
        "ease", "linear", "easeIn", "easeOut", "easeInOut",
        // Utility functions
        "if", "switch", "map", "filter"
    )

    /**
     * Known layout mode values.
     */
    val LAYOUT_MODES = setOf(
        "Top", "Left", "Center", "Right", "Bottom",
        "TopLeft", "TopRight", "BottomLeft", "BottomRight",
        "TopScrolling", "LeftScrolling", "CenterScrolling",
        "Horizontal", "Vertical", "Grid", "Stack", "Flow"
    )

    /**
     * Known alignment values.
     */
    val ALIGNMENT_VALUES = setOf(
        "Start", "End", "Center", "Stretch",
        "SpaceBetween", "SpaceAround", "SpaceEvenly",
        "Baseline", "FlexStart", "FlexEnd"
    )
}

/**
 * Custom element type for UI language.
 */
class UIElementType(debugName: String) : IElementType(debugName, UILanguage)
