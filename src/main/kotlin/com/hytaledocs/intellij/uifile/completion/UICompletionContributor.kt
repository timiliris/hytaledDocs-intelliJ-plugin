package com.hytaledocs.intellij.uifile.completion

import com.hytaledocs.intellij.settings.HytaleServerSettings
import com.hytaledocs.intellij.uifile.UILanguage
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.ui.JBColor
import com.intellij.util.ProcessingContext
import java.awt.Color
import javax.swing.Icon

/**
 * Code completion contributor for Hytale UI files.
 * Provides intelligent, context-aware suggestions for components, properties, and values.
 *
 * Features:
 * - Component completions with all 56+ Hytale UI component types
 * - Property completions based on component type context
 * - Value completions for enum properties (LayoutMode, Alignment, etc.)
 * - Style reference completions (@StyleName syntax)
 * - Template reference completions ($C.@ComponentName syntax)
 * - Color value completions with preview
 * - Anchor property completions (Left, Right, Top, Bottom, Width, Height, etc.)
 */
class UICompletionContributor : CompletionContributor() {

    init {
        // Component completion (at top level or inside a component)
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(UILanguage.INSTANCE),
            UICompletionProvider()
        )
    }
}

/**
 * Provides completion suggestions based on context.
 */
class UICompletionProvider : CompletionProvider<CompletionParameters>() {

    companion object {
        // ============================================================
        // COMPONENT DEFINITIONS (56+ components)
        // ============================================================

        /**
         * All Hytale UI component types organized by category.
         */
        private val COMPONENT_CATEGORIES = mapOf(
            "Layout Zones" to listOf(
                ComponentDef("ZonedLayout", "Full 16:9 layout with zones", AllIcons.Actions.ModuleDirectory),
                ComponentDef("ZoneHeader", "Fixed header zone", AllIcons.General.ArrowUp),
                ComponentDef("ZoneBody", "Flexible body zone", AllIcons.Actions.SplitVertically),
                ComponentDef("ZoneFooter", "Fixed footer zone", AllIcons.General.ArrowDown),
                ComponentDef("ZoneColumn", "Column in body zone", AllIcons.Actions.SplitHorizontally)
            ),
            "Layout" to listOf(
                ComponentDef("Group", "Container for organizing UI elements", AllIcons.Nodes.Folder),
                ComponentDef("Container", "Generic container for child elements", AllIcons.Nodes.Folder),
                ComponentDef("Panel", "Panel container", AllIcons.Nodes.Folder),
                ComponentDef("Box", "Flexible box container", AllIcons.Nodes.Folder),
                ComponentDef("HorizontalGroup", "Horizontal layout container", AllIcons.Actions.SplitHorizontally),
                ComponentDef("VerticalGroup", "Vertical layout container", AllIcons.Actions.SplitVertically),
                ComponentDef("Grid", "Grid layout container", AllIcons.Nodes.DataTables),
                ComponentDef("Stack", "Stacked container (z-index ordering)", AllIcons.Nodes.Folder),
                ComponentDef("Frame", "Frame container", AllIcons.Nodes.Folder),
                ComponentDef("ScrollView", "Scrollable view container", AllIcons.Actions.MoveDown),
                ComponentDef("ScrollPanel", "Scrollable panel", AllIcons.Actions.MoveDown),
                ComponentDef("ScrollGroup", "Scrollable group", AllIcons.Actions.MoveDown)
            ),
            "Text" to listOf(
                ComponentDef("Label", "Text label element", AllIcons.Nodes.Tag),
                ComponentDef("Text", "Text display element", AllIcons.Nodes.Tag),
                ComponentDef("RichText", "Formatted rich text element", AllIcons.FileTypes.Html)
            ),
            "Buttons" to listOf(
                ComponentDef("Button", "Basic button element", AllIcons.Actions.RunToCursor),
                ComponentDef("TextButton", "Button with text label", AllIcons.Actions.Execute),
                ComponentDef("ImageButton", "Button with background image", AllIcons.Actions.RunToCursor),
                ComponentDef("IconButton", "Button with icon only", AllIcons.Actions.RunToCursor),
                ComponentDef("ToggleButton", "Toggle button element", AllIcons.Actions.ShowCode),
                ComponentDef("CheckBox", "Checkbox toggle element", AllIcons.Actions.Checked),
                ComponentDef("RadioButton", "Radio button element", AllIcons.Actions.Checked)
            ),
            "Input" to listOf(
                ComponentDef("TextField", "Single-line text input field", AllIcons.Actions.Search),
                ComponentDef("TextInput", "Text input element", AllIcons.Actions.Search),
                ComponentDef("TextArea", "Multi-line text input area", AllIcons.Actions.Search),
                ComponentDef("NumberField", "Number input field", AllIcons.Nodes.Parameter),
                ComponentDef("DropdownBox", "Dropdown selection menu", AllIcons.General.ArrowDown),
                ComponentDef("Dropdown", "Dropdown element", AllIcons.General.ArrowDown),
                ComponentDef("ComboBox", "Combination dropdown box", AllIcons.General.ArrowDown),
                ComponentDef("Select", "Selection element", AllIcons.General.ArrowDown),
                ComponentDef("Menu", "Menu container", AllIcons.General.ArrowDown)
            ),
            "Slider & Progress" to listOf(
                ComponentDef("Slider", "Value slider element", AllIcons.Vcs.Equal),
                ComponentDef("FloatSlider", "Float value slider", AllIcons.Vcs.Equal),
                ComponentDef("Scrollbar", "Scrollbar element", AllIcons.Vcs.Equal),
                ComponentDef("ProgressBar", "Progress indicator bar", AllIcons.Process.ProgressResume)
            ),
            "Display" to listOf(
                ComponentDef("Image", "Image display element", AllIcons.FileTypes.Image),
                ComponentDef("Icon", "Icon display element", AllIcons.Nodes.EmptyNode),
                ComponentDef("Sprite", "Sprite/texture display element", AllIcons.Nodes.EmptyNode),
                ComponentDef("NineSlice", "Nine-slice scalable image", AllIcons.FileTypes.Image)
            ),
            "Game" to listOf(
                ComponentDef("ItemSlot", "Inventory item slot", AllIcons.Nodes.Static),
                ComponentDef("ItemIcon", "Item icon display", AllIcons.Nodes.Static),
                ComponentDef("ItemGrid", "Grid of item slots", AllIcons.Debugger.Db_array),
                ComponentDef("Inventory", "Inventory container", AllIcons.Debugger.Db_array),
                ComponentDef("InventorySlot", "Single inventory slot", AllIcons.Nodes.Static),
                ComponentDef("Hotbar", "Hotbar container", AllIcons.Debugger.Db_array),
                ComponentDef("HotbarSlot", "Single hotbar slot", AllIcons.Nodes.Static),
                ComponentDef("Tooltip", "Tooltip popup element", AllIcons.General.BalloonInformation),
                ComponentDef("TooltipPanel", "Tooltip panel container", AllIcons.General.BalloonInformation)
            ),
            "Other" to listOf(
                ComponentDef("Divider", "Visual divider line", AllIcons.General.Divider),
                ComponentDef("Separator", "Visual separator", AllIcons.General.Divider),
                ComponentDef("Spacer", "Empty space element", AllIcons.General.Divider),
                ComponentDef("Canvas", "Drawing canvas", AllIcons.FileTypes.Image),
                ComponentDef("RenderTarget", "Render target surface", AllIcons.FileTypes.Image),
                ComponentDef("Model3D", "3D model display", AllIcons.FileTypes.Image)
            )
        )

        /**
         * Flat list of all components for quick lookup.
         */
        private val ALL_COMPONENTS: Map<String, ComponentDef> = COMPONENT_CATEGORIES.values
            .flatten()
            .associateBy { it.name }

        // ============================================================
        // PROPERTY DEFINITIONS
        // ============================================================

        /**
         * Anchor properties (position and size within parent).
         */
        private val ANCHOR_PROPERTIES = listOf(
            PropertyDef("Left", "number", "Distance from parent's left edge"),
            PropertyDef("Right", "number", "Distance from parent's right edge"),
            PropertyDef("Top", "number", "Distance from parent's top edge"),
            PropertyDef("Bottom", "number", "Distance from parent's bottom edge"),
            PropertyDef("Width", "number", "Element width"),
            PropertyDef("Height", "number", "Element height"),
            PropertyDef("MinWidth", "number", "Minimum width constraint"),
            PropertyDef("MaxWidth", "number", "Maximum width constraint"),
            PropertyDef("MinHeight", "number", "Minimum height constraint"),
            PropertyDef("MaxHeight", "number", "Maximum height constraint"),
            PropertyDef("HorizontalCenter", "number", "Horizontal center offset"),
            PropertyDef("VerticalCenter", "number", "Vertical center offset"),
            PropertyDef("Horizontal", "number", "Horizontal stretch (0 to fill)"),
            PropertyDef("Vertical", "number", "Vertical stretch (0 to fill)"),
            PropertyDef("Full", "number", "Full stretch both directions (0 to fill)")
        )

        /**
         * Layout properties for containers.
         */
        private val LAYOUT_PROPERTIES = listOf(
            PropertyDef("LayoutMode", "enum", "Layout mode for children"),
            PropertyDef("HorizontalAlignment", "enum", "Horizontal content alignment"),
            PropertyDef("VerticalAlignment", "enum", "Vertical content alignment"),
            PropertyDef("Gap", "number", "Gap between children"),
            PropertyDef("Spacing", "number", "Spacing between elements"),
            PropertyDef("Padding", "number", "Inner padding (all sides)"),
            PropertyDef("PaddingTop", "number", "Top inner padding"),
            PropertyDef("PaddingRight", "number", "Right inner padding"),
            PropertyDef("PaddingBottom", "number", "Bottom inner padding"),
            PropertyDef("PaddingLeft", "number", "Left inner padding"),
            PropertyDef("Margin", "number", "Outer margin (all sides)"),
            PropertyDef("MarginTop", "number", "Top outer margin"),
            PropertyDef("MarginRight", "number", "Right outer margin"),
            PropertyDef("MarginBottom", "number", "Bottom outer margin"),
            PropertyDef("MarginLeft", "number", "Left outer margin"),
            PropertyDef("FlexWeight", "number", "Flex weight for proportional sizing"),
            PropertyDef("Columns", "number", "Number of grid columns"),
            PropertyDef("Rows", "number", "Number of grid rows")
        )

        /**
         * Text properties.
         */
        private val TEXT_PROPERTIES = listOf(
            PropertyDef("Text", "string", "Text content"),
            PropertyDef("FontSize", "number", "Font size in pixels"),
            PropertyDef("FontFamily", "string", "Font family name"),
            PropertyDef("FontStyle", "enum", "Font style (Normal, Italic)"),
            PropertyDef("FontWeight", "enum", "Font weight (Normal, Bold, Light)"),
            PropertyDef("TextColor", "color", "Text color"),
            PropertyDef("TextAlignment", "enum", "Text alignment"),
            PropertyDef("LineHeight", "number", "Line height"),
            PropertyDef("LetterSpacing", "number", "Letter spacing"),
            PropertyDef("RenderBold", "boolean", "Render text bold"),
            PropertyDef("RenderItalic", "boolean", "Render text italic"),
            PropertyDef("TextWrap", "boolean", "Enable text wrapping"),
            PropertyDef("PlaceholderText", "string", "Placeholder text for inputs")
        )

        /**
         * Appearance properties.
         */
        private val APPEARANCE_PROPERTIES = listOf(
            PropertyDef("Background", "color", "Background color"),
            PropertyDef("Foreground", "color", "Foreground color"),
            PropertyDef("Color", "color", "Element color"),
            PropertyDef("BorderColor", "color", "Border stroke color"),
            PropertyDef("BorderWidth", "number", "Border thickness"),
            PropertyDef("CornerRadius", "number", "Corner rounding radius"),
            PropertyDef("Opacity", "number", "Element opacity (0-1)"),
            PropertyDef("Alpha", "number", "Alpha transparency (0-1)"),
            PropertyDef("Visible", "boolean", "Element visibility"),
            PropertyDef("Enabled", "boolean", "Element enabled state"),
            PropertyDef("Focusable", "boolean", "Can receive focus"),
            PropertyDef("Shadow", "string", "Shadow effect"),
            PropertyDef("Blur", "number", "Blur effect amount")
        )

        /**
         * Image properties.
         */
        private val IMAGE_PROPERTIES = listOf(
            PropertyDef("Src", "string", "Image source path"),
            PropertyDef("Source", "string", "Resource source"),
            PropertyDef("Sprite", "string", "Sprite texture reference"),
            PropertyDef("Image", "string", "Image reference"),
            PropertyDef("Icon", "string", "Icon reference"),
            PropertyDef("Texture", "string", "Texture reference"),
            PropertyDef("Tint", "color", "Color tint overlay"),
            PropertyDef("TintColor", "color", "Tint color value"),
            PropertyDef("ScaleMode", "enum", "Image scale mode"),
            PropertyDef("PreserveAspect", "boolean", "Preserve aspect ratio")
        )

        /**
         * Interaction/Event properties.
         */
        private val INTERACTION_PROPERTIES = listOf(
            PropertyDef("OnClick", "handler", "Click event handler"),
            PropertyDef("OnHover", "handler", "Hover event handler"),
            PropertyDef("OnPress", "handler", "Press event handler"),
            PropertyDef("OnRelease", "handler", "Release event handler"),
            PropertyDef("OnFocus", "handler", "Focus event handler"),
            PropertyDef("OnBlur", "handler", "Blur event handler"),
            PropertyDef("OnInput", "handler", "Input change handler"),
            PropertyDef("OnChange", "handler", "Value change handler"),
            PropertyDef("Clickable", "boolean", "Responds to clicks"),
            PropertyDef("Draggable", "boolean", "Can be dragged"),
            PropertyDef("Tooltip", "string", "Tooltip text"),
            PropertyDef("Cursor", "enum", "Mouse cursor style")
        )

        /**
         * Slider/Progress properties.
         */
        private val SLIDER_PROPERTIES = listOf(
            PropertyDef("Min", "number", "Minimum value"),
            PropertyDef("Max", "number", "Maximum value"),
            PropertyDef("Value", "number", "Current value"),
            PropertyDef("Step", "number", "Value increment step"),
            PropertyDef("Orientation", "enum", "Slider orientation")
        )

        /**
         * List/Dropdown properties.
         */
        private val LIST_PROPERTIES = listOf(
            PropertyDef("Items", "array", "List items"),
            PropertyDef("SelectedIndex", "number", "Selected item index"),
            PropertyDef("SelectedItem", "any", "Selected item value")
        )

        /**
         * Style properties.
         */
        private val STYLE_PROPERTIES = listOf(
            PropertyDef("Style", "reference", "Style reference"),
            PropertyDef("LabelStyle", "reference", "Label style reference"),
            PropertyDef("ButtonStyle", "reference", "Button style reference")
        )

        /**
         * State properties (for style definitions).
         */
        private val STATE_PROPERTIES = listOf(
            PropertyDef("Default", "object", "Default state style"),
            PropertyDef("Hovered", "object", "Hovered state style"),
            PropertyDef("Pressed", "object", "Pressed state style"),
            PropertyDef("Disabled", "object", "Disabled state style"),
            PropertyDef("Focused", "object", "Focused state style"),
            PropertyDef("Selected", "object", "Selected state style"),
            PropertyDef("Active", "object", "Active state style"),
            PropertyDef("Checked", "object", "Checked state style")
        )

        /**
         * Animation properties.
         */
        private val ANIMATION_PROPERTIES = listOf(
            PropertyDef("Animation", "string", "Animation name"),
            PropertyDef("Transition", "string", "Transition definition"),
            PropertyDef("Duration", "number", "Animation duration in ms"),
            PropertyDef("Delay", "number", "Animation delay in ms"),
            PropertyDef("Easing", "enum", "Animation easing function")
        )

        /**
         * Identity properties.
         */
        private val IDENTITY_PROPERTIES = listOf(
            PropertyDef("Id", "string", "Unique element identifier"),
            PropertyDef("Name", "string", "Element name"),
            PropertyDef("Class", "string", "Style class name"),
            PropertyDef("Data", "any", "Custom data")
        )

        /**
         * Zone-specific properties.
         */
        private val ZONE_PROPERTIES = listOf(
            PropertyDef("ZoneType", "enum", "Zone type (HEADER, BODY, FOOTER, COLUMN)")
        )

        /**
         * Common properties applicable to all components.
         */
        private val COMMON_PROPERTIES = listOf(
            PropertyDef("Anchor", "object", "Position and size anchor"),
            PropertyDef("Background", "color", "Background color"),
            PropertyDef("Visible", "boolean", "Element visibility"),
            PropertyDef("Enabled", "boolean", "Element enabled state"),
            PropertyDef("Opacity", "number", "Element opacity (0-1)"),
            PropertyDef("Style", "reference", "Style reference")
        )

        /**
         * All properties for quick lookup.
         */
        private val ALL_PROPERTIES: Map<String, PropertyDef> = (
            ANCHOR_PROPERTIES +
            LAYOUT_PROPERTIES +
            TEXT_PROPERTIES +
            APPEARANCE_PROPERTIES +
            IMAGE_PROPERTIES +
            INTERACTION_PROPERTIES +
            SLIDER_PROPERTIES +
            LIST_PROPERTIES +
            STYLE_PROPERTIES +
            STATE_PROPERTIES +
            ANIMATION_PROPERTIES +
            IDENTITY_PROPERTIES +
            ZONE_PROPERTIES
        ).associateBy { it.name }

        // ============================================================
        // ENUM VALUE DEFINITIONS
        // ============================================================

        /**
         * Enum values for specific properties.
         */
        private val ENUM_VALUES = mapOf(
            "LayoutMode" to listOf(
                EnumValue("Top", "Top-aligned vertical layout"),
                EnumValue("TopScrolling", "Top-aligned with scrolling"),
                EnumValue("Bottom", "Bottom-aligned layout"),
                EnumValue("Left", "Left-aligned horizontal layout"),
                EnumValue("Right", "Right-aligned horizontal layout"),
                EnumValue("Center", "Centered layout"),
                EnumValue("Overlay", "Overlay positioning"),
                EnumValue("Absolute", "Absolute positioning")
            ),
            "HorizontalAlignment" to listOf(
                EnumValue("Left", "Align to left"),
                EnumValue("Center", "Center horizontally"),
                EnumValue("Right", "Align to right"),
                EnumValue("Stretch", "Stretch to fill")
            ),
            "VerticalAlignment" to listOf(
                EnumValue("Top", "Align to top"),
                EnumValue("Center", "Center vertically"),
                EnumValue("Bottom", "Align to bottom"),
                EnumValue("Stretch", "Stretch to fill")
            ),
            "TextAlignment" to listOf(
                EnumValue("Left", "Left-aligned text"),
                EnumValue("Center", "Center-aligned text"),
                EnumValue("Right", "Right-aligned text"),
                EnumValue("Justify", "Justified text")
            ),
            "FontWeight" to listOf(
                EnumValue("Normal", "Normal weight"),
                EnumValue("Bold", "Bold weight"),
                EnumValue("Light", "Light weight"),
                EnumValue("Medium", "Medium weight")
            ),
            "FontStyle" to listOf(
                EnumValue("Normal", "Normal style"),
                EnumValue("Italic", "Italic style")
            ),
            "Orientation" to listOf(
                EnumValue("Horizontal", "Horizontal orientation"),
                EnumValue("Vertical", "Vertical orientation")
            ),
            "Direction" to listOf(
                EnumValue("Horizontal", "Horizontal direction"),
                EnumValue("Vertical", "Vertical direction"),
                EnumValue("Row", "Row direction"),
                EnumValue("Column", "Column direction")
            ),
            "Cursor" to listOf(
                EnumValue("Default", "Default cursor"),
                EnumValue("Pointer", "Pointer/hand cursor"),
                EnumValue("Text", "Text input cursor"),
                EnumValue("Grab", "Grab cursor"),
                EnumValue("Grabbing", "Grabbing cursor"),
                EnumValue("NotAllowed", "Not allowed cursor"),
                EnumValue("Wait", "Wait cursor")
            ),
            "ScaleMode" to listOf(
                EnumValue("Stretch", "Stretch to fill"),
                EnumValue("Fit", "Fit within bounds"),
                EnumValue("Fill", "Fill and crop"),
                EnumValue("None", "No scaling"),
                EnumValue("Tile", "Tile/repeat"),
                EnumValue("Cover", "Cover area"),
                EnumValue("Contain", "Contain within area")
            ),
            "Easing" to listOf(
                EnumValue("Linear", "Linear easing"),
                EnumValue("EaseIn", "Ease in"),
                EnumValue("EaseOut", "Ease out"),
                EnumValue("EaseInOut", "Ease in and out"),
                EnumValue("EaseInQuad", "Quadratic ease in"),
                EnumValue("EaseOutQuad", "Quadratic ease out"),
                EnumValue("EaseInCubic", "Cubic ease in"),
                EnumValue("EaseOutCubic", "Cubic ease out")
            ),
            "ZoneType" to listOf(
                EnumValue("HEADER", "Header zone"),
                EnumValue("BODY", "Body zone"),
                EnumValue("FOOTER", "Footer zone"),
                EnumValue("COLUMN", "Column zone")
            )
        )

        // ============================================================
        // STYLE TYPE DEFINITIONS
        // ============================================================

        /**
         * Style function types for style definitions.
         */
        private val STYLE_TYPES = listOf(
            StyleTypeDef("TextButtonStyle", "Style for text buttons"),
            StyleTypeDef("ButtonStyle", "Style for buttons"),
            StyleTypeDef("LabelStyle", "Style for labels"),
            StyleTypeDef("ImageStyle", "Style for images"),
            StyleTypeDef("SliderStyle", "Style for sliders"),
            StyleTypeDef("ScrollbarStyle", "Style for scrollbars"),
            StyleTypeDef("ProgressBarStyle", "Style for progress bars"),
            StyleTypeDef("InputStyle", "Style for input fields"),
            StyleTypeDef("TextFieldStyle", "Style for text fields"),
            StyleTypeDef("TooltipStyle", "Style for tooltips"),
            StyleTypeDef("PatchStyle", "Style for nine-patch images")
        )

        // ============================================================
        // TEMPLATE DEFINITIONS
        // ============================================================

        /**
         * Common template references for quick insertion.
         */
        private val COMMON_TEMPLATES = listOf(
            TemplateDef("\$C.@TextButton", "Primary text button template"),
            TemplateDef("\$C.@SecondaryTextButton", "Secondary text button template"),
            TemplateDef("\$C.@TextField", "Styled text field template"),
            TemplateDef("\$C.@Container", "Styled container template"),
            TemplateDef("\$C.@Panel", "Styled panel template"),
            TemplateDef("\$C.@Label", "Styled label template"),
            TemplateDef("\$C.@Checkbox", "Styled checkbox template"),
            TemplateDef("\$C.@Slider", "Styled slider template"),
            TemplateDef("\$C.@ProgressBar", "Styled progress bar template"),
            TemplateDef("\$C.@Dropdown", "Styled dropdown template"),
            TemplateDef("\$C.@Tooltip", "Styled tooltip template"),
            TemplateDef("\$C.@ScrollView", "Styled scroll view template")
        )

        // ============================================================
        // COLOR DEFINITIONS
        // ============================================================

        /**
         * Common color presets for quick insertion.
         */
        private val COLOR_PRESETS = listOf(
            ColorPreset("#ffffff", "White"),
            ColorPreset("#000000", "Black"),
            ColorPreset("#ff0000", "Red"),
            ColorPreset("#00ff00", "Green"),
            ColorPreset("#0000ff", "Blue"),
            ColorPreset("#ffff00", "Yellow"),
            ColorPreset("#ff00ff", "Magenta"),
            ColorPreset("#00ffff", "Cyan"),
            ColorPreset("#808080", "Gray"),
            ColorPreset("#c0c0c0", "Silver"),
            ColorPreset("#3a7bd5", "Hytale Blue"),
            ColorPreset("#141c26", "Hytale Dark"),
            ColorPreset("#1a2634", "Hytale Panel"),
            ColorPreset("#2d3e50", "Hytale Border"),
            ColorPreset("#4a90d9", "Hytale Accent"),
            ColorPreset("#7cb342", "Hytale Green"),
            ColorPreset("#f4511e", "Hytale Orange"),
            ColorPreset("#ab47bc", "Hytale Purple")
        )

        // ============================================================
        // COMPONENT-SPECIFIC PROPERTY MAPPINGS
        // ============================================================

        /**
         * Properties relevant to specific component types.
         */
        private val COMPONENT_PROPERTIES: Map<String, List<String>> = mapOf(
            // Layout containers
            "Group" to listOf("Anchor", "LayoutMode", "HorizontalAlignment", "VerticalAlignment", "Gap", "Padding", "Background", "Visible", "Style"),
            "Container" to listOf("Anchor", "LayoutMode", "HorizontalAlignment", "VerticalAlignment", "Gap", "Padding", "Background", "Visible", "Style"),
            "Panel" to listOf("Anchor", "LayoutMode", "HorizontalAlignment", "VerticalAlignment", "Gap", "Padding", "Background", "BorderColor", "BorderWidth", "CornerRadius", "Visible", "Style"),
            "HorizontalGroup" to listOf("Anchor", "Gap", "HorizontalAlignment", "VerticalAlignment", "Background", "Visible", "Style"),
            "VerticalGroup" to listOf("Anchor", "Gap", "HorizontalAlignment", "VerticalAlignment", "Background", "Visible", "Style"),
            "Grid" to listOf("Anchor", "Columns", "Rows", "Gap", "Background", "Visible", "Style"),
            "ScrollView" to listOf("Anchor", "LayoutMode", "Background", "Visible", "Style"),

            // Text components
            "Label" to listOf("Anchor", "Text", "FontSize", "FontFamily", "FontWeight", "TextColor", "TextAlignment", "LineHeight", "RenderBold", "RenderItalic", "Visible", "Style"),
            "Text" to listOf("Anchor", "Text", "FontSize", "FontFamily", "FontWeight", "TextColor", "TextAlignment", "LineHeight", "TextWrap", "Visible", "Style"),
            "RichText" to listOf("Anchor", "Text", "FontSize", "FontFamily", "TextColor", "TextWrap", "Visible", "Style"),

            // Button components
            "Button" to listOf("Anchor", "Background", "Enabled", "Focusable", "OnClick", "Visible", "Style"),
            "TextButton" to listOf("Anchor", "Text", "FontSize", "TextColor", "Background", "Enabled", "Focusable", "OnClick", "Visible", "Style"),
            "ImageButton" to listOf("Anchor", "Image", "Src", "Background", "Enabled", "Focusable", "OnClick", "Visible", "Style"),
            "ToggleButton" to listOf("Anchor", "Text", "Value", "Enabled", "OnChange", "Visible", "Style"),
            "CheckBox" to listOf("Anchor", "Text", "Value", "Enabled", "OnChange", "Visible", "Style"),

            // Input components
            "TextField" to listOf("Anchor", "Text", "PlaceholderText", "FontSize", "TextColor", "Background", "Enabled", "Focusable", "OnInput", "OnChange", "Visible", "Style"),
            "TextInput" to listOf("Anchor", "Text", "PlaceholderText", "FontSize", "TextColor", "Background", "Enabled", "Focusable", "OnInput", "OnChange", "Visible", "Style"),
            "TextArea" to listOf("Anchor", "Text", "PlaceholderText", "FontSize", "TextColor", "Background", "TextWrap", "Enabled", "Focusable", "OnInput", "OnChange", "Visible", "Style"),
            "NumberField" to listOf("Anchor", "Value", "Min", "Max", "Step", "Background", "Enabled", "OnChange", "Visible", "Style"),

            // Dropdown/Select components
            "Dropdown" to listOf("Anchor", "Items", "SelectedIndex", "Background", "Enabled", "OnChange", "Visible", "Style"),
            "DropdownBox" to listOf("Anchor", "Items", "SelectedIndex", "Background", "Enabled", "OnChange", "Visible", "Style"),
            "ComboBox" to listOf("Anchor", "Items", "SelectedIndex", "Text", "Background", "Enabled", "OnChange", "Visible", "Style"),
            "Select" to listOf("Anchor", "Items", "SelectedIndex", "Background", "Enabled", "OnChange", "Visible", "Style"),

            // Slider/Progress components
            "Slider" to listOf("Anchor", "Min", "Max", "Value", "Step", "Orientation", "Background", "Enabled", "OnChange", "Visible", "Style"),
            "FloatSlider" to listOf("Anchor", "Min", "Max", "Value", "Step", "Orientation", "Background", "Enabled", "OnChange", "Visible", "Style"),
            "ProgressBar" to listOf("Anchor", "Min", "Max", "Value", "Background", "Foreground", "Visible", "Style"),
            "Scrollbar" to listOf("Anchor", "Min", "Max", "Value", "Orientation", "Visible", "Style"),

            // Image components
            "Image" to listOf("Anchor", "Src", "Source", "Tint", "TintColor", "ScaleMode", "PreserveAspect", "Visible", "Style"),
            "Sprite" to listOf("Anchor", "Sprite", "Tint", "TintColor", "Visible", "Style"),
            "Icon" to listOf("Anchor", "Icon", "Tint", "TintColor", "Visible", "Style"),
            "NineSlice" to listOf("Anchor", "Src", "Tint", "Visible", "Style"),

            // Game components
            "ItemSlot" to listOf("Anchor", "Background", "Enabled", "OnClick", "Tooltip", "Visible", "Style"),
            "ItemGrid" to listOf("Anchor", "Columns", "Rows", "Gap", "Background", "Visible", "Style"),
            "Inventory" to listOf("Anchor", "Columns", "Rows", "Background", "Visible", "Style"),
            "Hotbar" to listOf("Anchor", "Background", "Visible", "Style"),
            "Tooltip" to listOf("Anchor", "Text", "Background", "BorderColor", "Visible", "Style"),

            // Zone components
            "ZonedLayout" to listOf("Anchor", "LayoutMode", "Background", "Visible"),
            "ZoneHeader" to listOf("Anchor", "ZoneType", "LayoutMode", "Background", "Visible"),
            "ZoneBody" to listOf("Anchor", "ZoneType", "FlexWeight", "LayoutMode", "Background", "Visible"),
            "ZoneFooter" to listOf("Anchor", "ZoneType", "LayoutMode", "Background", "Visible"),
            "ZoneColumn" to listOf("Anchor", "ZoneType", "FlexWeight", "LayoutMode", "Background", "Visible")
        )

        /**
         * Set of container component types.
         */
        private val CONTAINER_COMPONENTS = setOf(
            "Group", "Container", "Panel", "Box",
            "HorizontalGroup", "VerticalGroup",
            "Grid", "Stack", "Frame",
            "ScrollView", "ScrollPanel", "ScrollGroup",
            "ZonedLayout", "ZoneHeader", "ZoneBody", "ZoneFooter", "ZoneColumn",
            "Inventory", "ItemGrid", "Hotbar"
        )

        /**
         * Set of text component types.
         */
        private val TEXT_COMPONENTS = setOf(
            "Label", "Text", "RichText",
            "TextButton", "CheckBox", "ToggleButton"
        )

        /**
         * Set of input component types.
         */
        private val INPUT_COMPONENTS = setOf(
            "TextField", "TextInput", "TextArea", "NumberField"
        )

        /**
         * Set of slider/progress component types.
         */
        private val SLIDER_COMPONENTS = setOf(
            "Slider", "FloatSlider", "ProgressBar", "Scrollbar"
        )

        /**
         * Set of image component types.
         */
        private val IMAGE_COMPONENTS = setOf(
            "Image", "Icon", "Sprite", "NineSlice", "ImageButton"
        )

        /**
         * Set of list/dropdown component types.
         */
        private val LIST_COMPONENTS = setOf(
            "Dropdown", "DropdownBox", "ComboBox", "Select", "Menu"
        )
    }

    // ============================================================
    // DATA CLASSES
    // ============================================================

    private data class ComponentDef(
        val name: String,
        val description: String,
        val icon: Icon
    )

    private data class PropertyDef(
        val name: String,
        val type: String,
        val description: String
    )

    private data class EnumValue(
        val value: String,
        val description: String
    )

    private data class StyleTypeDef(
        val name: String,
        val description: String
    )

    private data class TemplateDef(
        val template: String,
        val description: String
    )

    private data class ColorPreset(
        val hex: String,
        val name: String
    )

    // ============================================================
    // COMPLETION LOGIC
    // ============================================================

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        // Check if UI file support is enabled
        val project = parameters.originalFile.project
        if (!HytaleServerSettings.getInstance(project).uiFileSupportEnabled) return

        val position = parameters.position
        val text = parameters.originalFile.text
        val offset = parameters.offset

        // Determine context by analyzing surrounding text
        val textBefore = if (offset > 0) text.substring(0, offset) else ""

        // Determine completion context
        val completionContext = analyzeContext(textBefore, text, offset)

        when (completionContext) {
            is CompletionContext.PropertyValue -> {
                addPropertyValueCompletions(completionContext.propertyName, completionContext.componentType, result)
            }
            is CompletionContext.AnchorProperty -> {
                addAnchorPropertyCompletions(result)
            }
            is CompletionContext.StyleState -> {
                addStyleStateCompletions(result)
            }
            is CompletionContext.StyleDefinition -> {
                addStyleTypeCompletions(result)
            }
            is CompletionContext.Reference -> {
                addReferenceCompletions(textBefore, result)
            }
            is CompletionContext.TemplateRef -> {
                addTemplateCompletions(result)
            }
            is CompletionContext.InsideComponent -> {
                addPropertyCompletions(completionContext.componentType, result)
                addComponentCompletions(result)
            }
            is CompletionContext.TopLevel -> {
                addComponentCompletions(result)
                addTemplateCompletions(result)
                addTopLevelCompletions(result)
            }
        }
    }

    /**
     * Analyze the text context to determine what type of completion to provide.
     */
    private fun analyzeContext(textBefore: String, fullText: String, offset: Int): CompletionContext {
        // Check for style reference (@)
        val lastAtIndex = textBefore.lastIndexOf('@')
        val lastNewlineBeforeAt = if (lastAtIndex > 0) textBefore.lastIndexOf('\n', lastAtIndex - 1) else -1
        if (lastAtIndex > lastNewlineBeforeAt && lastAtIndex == textBefore.length - 1) {
            return CompletionContext.Reference
        }

        // Check for template reference ($)
        val lastDollarIndex = textBefore.lastIndexOf('$')
        if (lastDollarIndex >= 0) {
            val afterDollar = textBefore.substring(lastDollarIndex)
            if (afterDollar.matches(Regex("\\$[A-Za-z]*\\.?(@[A-Za-z]*)?$"))) {
                return CompletionContext.TemplateRef
            }
        }

        // Count braces to determine depth
        val braceDepth = textBefore.count { it == '{' } - textBefore.count { it == '}' }

        // Check if we're inside parentheses (Anchor: (...), Style: (...))
        val parenDepth = textBefore.count { it == '(' } - textBefore.count { it == ')' }

        // Check if we're after a colon (property value context)
        val colonIndex = textBefore.lastIndexOf(':')
        val newlineIndex = textBefore.lastIndexOf('\n')
        val braceIndex = maxOf(textBefore.lastIndexOf('{'), textBefore.lastIndexOf('}'))
        val parenIndex = maxOf(textBefore.lastIndexOf('('), textBefore.lastIndexOf(')'))

        val isAfterColon = colonIndex > maxOf(newlineIndex, braceIndex, parenIndex)

        if (isAfterColon) {
            // Property value completion
            val lineStart = maxOf(newlineIndex, 0)
            val lineBefore = textBefore.substring(lineStart, colonIndex).trim()
            val propertyName = lineBefore.split(Regex("\\s+")).lastOrNull() ?: ""

            // Detect component type from context
            val componentType = detectComponentType(textBefore)

            // Check if we're inside an Anchor block
            if (isInsideAnchorBlock(textBefore)) {
                return CompletionContext.AnchorProperty
            }

            // Check if we're inside a style state block (Default, Hovered, etc.)
            if (isInsideStyleState(textBefore)) {
                return CompletionContext.StyleState
            }

            return CompletionContext.PropertyValue(propertyName, componentType)
        }

        // Check if we're inside an Anchor block
        if (parenDepth > 0 && isInsideAnchorBlock(textBefore)) {
            return CompletionContext.AnchorProperty
        }

        // Check if we're inside a style definition
        if (isInsideStyleDefinition(textBefore)) {
            if (isAtStyleStateLevel(textBefore)) {
                return CompletionContext.StyleState
            }
            return CompletionContext.StyleDefinition
        }

        // Check if we're at top level or inside a component
        if (braceDepth > 0) {
            val componentType = detectComponentType(textBefore)
            return CompletionContext.InsideComponent(componentType)
        }

        return CompletionContext.TopLevel
    }

    /**
     * Check if cursor is inside an Anchor block.
     */
    private fun isInsideAnchorBlock(textBefore: String): Boolean {
        val lastAnchor = textBefore.lastIndexOf("Anchor")
        if (lastAnchor < 0) return false

        val afterAnchor = textBefore.substring(lastAnchor)
        val openParen = afterAnchor.indexOf('(')
        val closeParen = afterAnchor.lastIndexOf(')')

        return openParen >= 0 && (closeParen < 0 || closeParen < openParen ||
                afterAnchor.count { it == '(' } > afterAnchor.count { it == ')' })
    }

    /**
     * Check if cursor is inside a style state block.
     */
    private fun isInsideStyleState(textBefore: String): Boolean {
        val stateNames = listOf("Default", "Hovered", "Pressed", "Disabled", "Focused", "Selected", "Active", "Checked")
        for (state in stateNames) {
            val lastState = textBefore.lastIndexOf(state)
            if (lastState >= 0) {
                val afterState = textBefore.substring(lastState)
                if (afterState.contains("(") && afterState.count { it == '(' } > afterState.count { it == ')' }) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Check if cursor is inside a style definition.
     */
    private fun isInsideStyleDefinition(textBefore: String): Boolean {
        val stylePatterns = listOf(
            "TextButtonStyle", "ButtonStyle", "LabelStyle", "ImageStyle",
            "SliderStyle", "ScrollbarStyle", "ProgressBarStyle", "InputStyle",
            "TextFieldStyle", "TooltipStyle", "PatchStyle"
        )
        for (pattern in stylePatterns) {
            val lastPattern = textBefore.lastIndexOf(pattern)
            if (lastPattern >= 0) {
                val afterPattern = textBefore.substring(lastPattern)
                if (afterPattern.count { it == '(' } > afterPattern.count { it == ')' }) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Check if cursor is at the style state level (where Default, Hovered, etc. are expected).
     */
    private fun isAtStyleStateLevel(textBefore: String): Boolean {
        val stylePatterns = listOf(
            "TextButtonStyle", "ButtonStyle", "LabelStyle", "ImageStyle",
            "SliderStyle", "ScrollbarStyle", "ProgressBarStyle", "InputStyle",
            "TextFieldStyle", "TooltipStyle", "PatchStyle"
        )
        for (pattern in stylePatterns) {
            val lastPattern = textBefore.lastIndexOf(pattern)
            if (lastPattern >= 0) {
                val afterPattern = textBefore.substring(lastPattern)
                // Check if we're at the first level of parentheses (state level)
                val parenDepth = afterPattern.count { it == '(' } - afterPattern.count { it == ')' }
                if (parenDepth == 1) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Detect the current component type from context.
     */
    private fun detectComponentType(textBefore: String): String? {
        // Look for the most recent component declaration
        val componentPattern = Regex("(${ALL_COMPONENTS.keys.joinToString("|")})\\s*\\{")
        val matches = componentPattern.findAll(textBefore).toList()

        if (matches.isNotEmpty()) {
            // Count braces to find which component we're currently in
            var depth = 0
            var currentComponent: String? = null

            for (i in textBefore.indices) {
                val char = textBefore[i]
                when (char) {
                    '{' -> {
                        // Check if this is a component opening
                        val beforeBrace = textBefore.substring(0, i).trimEnd()
                        for ((name, _) in ALL_COMPONENTS) {
                            if (beforeBrace.endsWith(name)) {
                                if (depth == 0 || currentComponent == null) {
                                    currentComponent = name
                                }
                                break
                            }
                        }
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            currentComponent = null
                        }
                    }
                }
            }

            return currentComponent
        }

        return null
    }

    // ============================================================
    // COMPLETION METHODS
    // ============================================================

    /**
     * Add component completions.
     */
    private fun addComponentCompletions(result: CompletionResultSet) {
        for ((category, components) in COMPONENT_CATEGORIES) {
            for (component in components) {
                val element = LookupElementBuilder.create(component.name)
                    .withIcon(component.icon)
                    .withTypeText("Component")
                    .withTailText(" { ... }", true)
                    .withInsertHandler { ctx, _ ->
                        val editor = ctx.editor
                        val document = editor.document
                        val caretOffset = editor.caretModel.offset

                        document.insertString(caretOffset, " {\n    \n}")
                        editor.caretModel.moveToOffset(caretOffset + 6)
                    }
                    .bold()

                result.addElement(
                    PrioritizedLookupElement.withPriority(element, 100.0)
                )
            }
        }
    }

    /**
     * Add property completions based on component type.
     */
    private fun addPropertyCompletions(componentType: String?, result: CompletionResultSet) {
        val relevantProperties = if (componentType != null && COMPONENT_PROPERTIES.containsKey(componentType)) {
            // Get component-specific properties
            val specificProps = COMPONENT_PROPERTIES[componentType] ?: emptyList()
            specificProps.mapNotNull { ALL_PROPERTIES[it] }
        } else {
            // Show all common properties
            (COMMON_PROPERTIES + ANCHOR_PROPERTIES + LAYOUT_PROPERTIES + APPEARANCE_PROPERTIES)
                .distinctBy { it.name }
        }

        for (property in relevantProperties) {
            val typeText = getPropertyTypeDisplay(property.type)
            val element = LookupElementBuilder.create(property.name)
                .withIcon(getPropertyIcon(property.type))
                .withTypeText(typeText)
                .withTailText(": ", true)
                .withInsertHandler { ctx, _ ->
                    val editor = ctx.editor
                    val document = editor.document
                    val caretOffset = editor.caretModel.offset

                    val insertText = getPropertyInsertText(property)
                    document.insertString(caretOffset, ": $insertText")

                    // Position cursor appropriately
                    val cursorOffset = when {
                        insertText.startsWith("\"") -> caretOffset + 3  // Inside quotes
                        insertText.startsWith("(") -> caretOffset + 3  // Inside parentheses
                        insertText == "#" -> caretOffset + 3  // After #
                        else -> caretOffset + 2 + insertText.length
                    }
                    editor.caretModel.moveToOffset(cursorOffset)
                }

            result.addElement(
                PrioritizedLookupElement.withPriority(element, 90.0)
            )
        }

        // Also add component-type-specific properties
        addContextSpecificProperties(componentType, result)
    }

    /**
     * Add context-specific properties based on component type.
     */
    private fun addContextSpecificProperties(componentType: String?, result: CompletionResultSet) {
        if (componentType == null) return

        val additionalProperties = mutableListOf<PropertyDef>()

        when {
            componentType in TEXT_COMPONENTS -> additionalProperties.addAll(TEXT_PROPERTIES)
            componentType in INPUT_COMPONENTS -> additionalProperties.addAll(TEXT_PROPERTIES + listOf(
                PropertyDef("PlaceholderText", "string", "Placeholder text")
            ))
            componentType in SLIDER_COMPONENTS -> additionalProperties.addAll(SLIDER_PROPERTIES)
            componentType in IMAGE_COMPONENTS -> additionalProperties.addAll(IMAGE_PROPERTIES)
            componentType in LIST_COMPONENTS -> additionalProperties.addAll(LIST_PROPERTIES)
            componentType in CONTAINER_COMPONENTS -> additionalProperties.addAll(LAYOUT_PROPERTIES)
        }

        for (property in additionalProperties) {
            if (ALL_PROPERTIES.containsKey(property.name)) continue  // Already added

            val typeText = getPropertyTypeDisplay(property.type)
            val element = LookupElementBuilder.create(property.name)
                .withIcon(getPropertyIcon(property.type))
                .withTypeText(typeText)
                .withTailText(": ", true)
                .withInsertHandler { ctx, _ ->
                    val editor = ctx.editor
                    val document = editor.document
                    val caretOffset = editor.caretModel.offset

                    val insertText = getPropertyInsertText(property)
                    document.insertString(caretOffset, ": $insertText")

                    val cursorOffset = when {
                        insertText.startsWith("\"") -> caretOffset + 3
                        insertText.startsWith("(") -> caretOffset + 3
                        insertText == "#" -> caretOffset + 3
                        else -> caretOffset + 2 + insertText.length
                    }
                    editor.caretModel.moveToOffset(cursorOffset)
                }

            result.addElement(
                PrioritizedLookupElement.withPriority(element, 85.0)
            )
        }
    }

    /**
     * Add property value completions based on property name.
     */
    private fun addPropertyValueCompletions(propertyName: String, componentType: String?, result: CompletionResultSet) {
        // Check for enum values
        val enumValues = ENUM_VALUES[propertyName]
        if (enumValues != null) {
            for ((index, enumValue) in enumValues.withIndex()) {
                val element = LookupElementBuilder.create(enumValue.value)
                    .withIcon(AllIcons.Nodes.Enum)
                    .withTypeText("value")
                    .withTailText(" - ${enumValue.description}", true)

                result.addElement(
                    PrioritizedLookupElement.withPriority(element, 100.0 - index)
                )
            }
            return
        }

        // Check for boolean properties
        if (isBooleanProperty(propertyName)) {
            listOf("true" to "Enable", "false" to "Disable").forEach { (value, desc) ->
                val element = LookupElementBuilder.create(value)
                    .withIcon(AllIcons.Nodes.Constant)
                    .withTypeText("boolean")
                    .withTailText(" - $desc", true)
                    .bold()

                result.addElement(
                    PrioritizedLookupElement.withPriority(element, 100.0)
                )
            }
            return
        }

        // Check for color properties
        if (isColorProperty(propertyName)) {
            addColorCompletions(result)
            return
        }

        // Check for reference properties (Style, etc.)
        if (isReferenceProperty(propertyName)) {
            addReferenceCompletions("", result)
            return
        }

        // For other properties, add type-specific hints
        val property = ALL_PROPERTIES[propertyName]
        if (property != null) {
            when (property.type) {
                "number" -> addNumberHint(propertyName, result)
                "string" -> addStringHint(propertyName, result)
                "object" -> addObjectHint(propertyName, result)
            }
        }
    }

    /**
     * Add anchor property completions.
     */
    private fun addAnchorPropertyCompletions(result: CompletionResultSet) {
        for (property in ANCHOR_PROPERTIES) {
            val element = LookupElementBuilder.create(property.name)
                .withIcon(AllIcons.Nodes.Property)
                .withTypeText("number")
                .withTailText(": ", true)
                .withInsertHandler { ctx, _ ->
                    val editor = ctx.editor
                    val document = editor.document
                    val caretOffset = editor.caretModel.offset

                    document.insertString(caretOffset, ": ")
                    editor.caretModel.moveToOffset(caretOffset + 2)
                }

            result.addElement(
                PrioritizedLookupElement.withPriority(element, 95.0)
            )
        }
    }

    /**
     * Add style state completions (Default, Hovered, etc.).
     */
    private fun addStyleStateCompletions(result: CompletionResultSet) {
        for (state in STATE_PROPERTIES) {
            val element = LookupElementBuilder.create(state.name)
                .withIcon(AllIcons.Nodes.Property)
                .withTypeText("state")
                .withTailText(": (...)", true)
                .withInsertHandler { ctx, _ ->
                    val editor = ctx.editor
                    val document = editor.document
                    val caretOffset = editor.caretModel.offset

                    document.insertString(caretOffset, ": (\n        \n    )")
                    editor.caretModel.moveToOffset(caretOffset + 11)
                }

            result.addElement(
                PrioritizedLookupElement.withPriority(element, 95.0)
            )
        }
    }

    /**
     * Add style type completions (TextButtonStyle, LabelStyle, etc.).
     */
    private fun addStyleTypeCompletions(result: CompletionResultSet) {
        for (styleType in STYLE_TYPES) {
            val element = LookupElementBuilder.create(styleType.name)
                .withIcon(AllIcons.Nodes.Type)
                .withTypeText("style")
                .withTailText("(...)", true)
                .withInsertHandler { ctx, _ ->
                    val editor = ctx.editor
                    val document = editor.document
                    val caretOffset = editor.caretModel.offset

                    document.insertString(caretOffset, "(\n    Default: (\n        \n    )\n)")
                    editor.caretModel.moveToOffset(caretOffset + 26)
                }

            result.addElement(
                PrioritizedLookupElement.withPriority(element, 100.0)
            )
        }
    }

    /**
     * Add reference completions (@StyleName).
     */
    private fun addReferenceCompletions(textBefore: String, result: CompletionResultSet) {
        // Add common style references
        val commonStyles = listOf(
            "DefaultButtonStyle", "PrimaryButtonStyle", "SecondaryButtonStyle",
            "DefaultLabelStyle", "TitleStyle", "SubtitleStyle",
            "DefaultInputStyle", "DefaultSliderStyle", "DefaultProgressStyle",
            "DefaultTooltipStyle", "DefaultPanelStyle"
        )

        for ((index, style) in commonStyles.withIndex()) {
            val element = LookupElementBuilder.create("@$style")
                .withPresentableText(style)
                .withIcon(AllIcons.Nodes.Property)
                .withTypeText("style reference")

            result.addElement(
                PrioritizedLookupElement.withPriority(element, 90.0 - index)
            )
        }

        // Add spread syntax hint
        val spreadElement = LookupElementBuilder.create("...@")
            .withPresentableText("...@StyleName")
            .withIcon(AllIcons.Nodes.Property)
            .withTypeText("spread")
            .withTailText(" (extend style)", true)
            .withInsertHandler { ctx, _ ->
                val editor = ctx.editor
                val document = editor.document
                val caretOffset = editor.caretModel.offset

                document.insertString(caretOffset, "...")
                editor.caretModel.moveToOffset(caretOffset + 3)
            }

        result.addElement(
            PrioritizedLookupElement.withPriority(spreadElement, 85.0)
        )
    }

    /**
     * Add template completions ($C.@ComponentName).
     */
    private fun addTemplateCompletions(result: CompletionResultSet) {
        for ((index, template) in COMMON_TEMPLATES.withIndex()) {
            val element = LookupElementBuilder.create(template.template)
                .withIcon(AllIcons.Nodes.Template)
                .withTypeText("template")
                .withTailText(" - ${template.description}", true)
                .withInsertHandler { ctx, _ ->
                    val editor = ctx.editor
                    val document = editor.document
                    val caretOffset = editor.caretModel.offset

                    document.insertString(caretOffset, " {\n    \n}")
                    editor.caretModel.moveToOffset(caretOffset + 6)
                }

            result.addElement(
                PrioritizedLookupElement.withPriority(element, 95.0 - index)
            )
        }

        // Add generic template syntax hint
        val templateHint = LookupElementBuilder.create("\$")
            .withPresentableText("\$ImportAlias.@TemplateName")
            .withIcon(AllIcons.Nodes.Template)
            .withTypeText("template syntax")

        result.addElement(
            PrioritizedLookupElement.withPriority(templateHint, 80.0)
        )
    }

    /**
     * Add color completions with preview.
     */
    private fun addColorCompletions(result: CompletionResultSet) {
        for ((index, color) in COLOR_PRESETS.withIndex()) {
            val element = LookupElementBuilder.create(color.hex)
                .withIcon(createColorIcon(color.hex))
                .withTypeText("color")
                .withTailText(" - ${color.name}", true)

            result.addElement(
                PrioritizedLookupElement.withPriority(element, 95.0 - index)
            )
        }

        // Add color with alpha hint
        val alphaElement = LookupElementBuilder.create("#000000(0.5)")
            .withPresentableText("#RRGGBB(alpha)")
            .withIcon(AllIcons.Gutter.Colors)
            .withTypeText("color with alpha")
            .withTailText(" (0-1)", true)

        result.addElement(
            PrioritizedLookupElement.withPriority(alphaElement, 80.0)
        )
    }

    /**
     * Add top-level completions (imports, variables, styles).
     */
    private fun addTopLevelCompletions(result: CompletionResultSet) {
        // Import syntax
        val importElement = LookupElementBuilder.create("\$")
            .withPresentableText("\$Alias = \"path.ui\"")
            .withIcon(AllIcons.Nodes.Include)
            .withTypeText("import")
            .withInsertHandler { ctx, _ ->
                val editor = ctx.editor
                val document = editor.document
                val caretOffset = editor.caretModel.offset

                document.insertString(caretOffset, "C = \"../Common.ui\";")
                editor.caretModel.moveToOffset(caretOffset + 6)
            }

        result.addElement(
            PrioritizedLookupElement.withPriority(importElement, 80.0)
        )

        // Variable/Style definition
        val varElement = LookupElementBuilder.create("@")
            .withPresentableText("@VariableName = value")
            .withIcon(AllIcons.Nodes.Variable)
            .withTypeText("variable/style")
            .withInsertHandler { ctx, _ ->
                val editor = ctx.editor
                val document = editor.document
                val caretOffset = editor.caretModel.offset

                document.insertString(caretOffset, "StyleName = ")
                editor.caretModel.moveToOffset(caretOffset + 10)
            }

        result.addElement(
            PrioritizedLookupElement.withPriority(varElement, 75.0)
        )

        // Style type completions
        for (styleType in STYLE_TYPES) {
            val element = LookupElementBuilder.create("@${styleType.name.removeSuffix("Style")} = ${styleType.name}")
                .withPresentableText("@Name = ${styleType.name}(...)")
                .withIcon(AllIcons.Nodes.Type)
                .withTypeText("style definition")
                .withInsertHandler { ctx, _ ->
                    val editor = ctx.editor
                    val document = editor.document
                    val caretOffset = editor.caretModel.offset

                    val styleName = styleType.name.removeSuffix("Style")
                    val insert = "${styleName}Style = ${styleType.name}(\n    Default: (\n        \n    )\n);"
                    document.insertString(caretOffset, insert)
                    editor.caretModel.moveToOffset(caretOffset + styleName.length + styleType.name.length + 28)
                }

            result.addElement(
                PrioritizedLookupElement.withPriority(element, 70.0)
            )
        }
    }

    /**
     * Add a number hint for numeric properties.
     */
    private fun addNumberHint(propertyName: String, result: CompletionResultSet) {
        val hints = when (propertyName.lowercase()) {
            "opacity", "alpha" -> listOf("1" to "Fully opaque", "0.5" to "50% transparent", "0" to "Fully transparent")
            "fontsize" -> listOf("12" to "Small", "14" to "Normal", "16" to "Medium", "20" to "Large", "24" to "Extra large")
            "borderwidth" -> listOf("1" to "Thin", "2" to "Medium", "4" to "Thick")
            "cornerradius" -> listOf("0" to "Sharp", "4" to "Slight", "8" to "Rounded", "16" to "Very rounded")
            "gap", "spacing" -> listOf("4" to "Tight", "8" to "Normal", "16" to "Loose", "24" to "Very loose")
            "padding" -> listOf("0" to "None", "4" to "Tight", "8" to "Normal", "16" to "Comfortable", "24" to "Spacious")
            else -> listOf("0" to "Zero", "100" to "Default")
        }

        for ((index, hint) in hints.withIndex()) {
            val element = LookupElementBuilder.create(hint.first)
                .withIcon(AllIcons.Nodes.Parameter)
                .withTypeText("number")
                .withTailText(" - ${hint.second}", true)

            result.addElement(
                PrioritizedLookupElement.withPriority(element, 90.0 - index)
            )
        }
    }

    /**
     * Add a string hint for string properties.
     */
    private fun addStringHint(propertyName: String, result: CompletionResultSet) {
        val element = LookupElementBuilder.create("\"\"")
            .withPresentableText("\"text\"")
            .withIcon(AllIcons.Nodes.Constant)
            .withTypeText("string")
            .withInsertHandler { ctx, _ ->
                val editor = ctx.editor
                editor.caretModel.moveToOffset(editor.caretModel.offset - 1)
            }

        result.addElement(
            PrioritizedLookupElement.withPriority(element, 90.0)
        )
    }

    /**
     * Add an object hint for object properties.
     */
    private fun addObjectHint(propertyName: String, result: CompletionResultSet) {
        val element = LookupElementBuilder.create("()")
            .withPresentableText("(property: value)")
            .withIcon(AllIcons.Nodes.Artifact)
            .withTypeText("object")
            .withInsertHandler { ctx, _ ->
                val editor = ctx.editor
                val document = editor.document
                val caretOffset = editor.caretModel.offset

                document.replaceString(caretOffset - 2, caretOffset, "(\n        \n    )")
                editor.caretModel.moveToOffset(caretOffset + 7)
            }

        result.addElement(
            PrioritizedLookupElement.withPriority(element, 90.0)
        )
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private fun getPropertyTypeDisplay(type: String): String {
        return when (type) {
            "number" -> "number"
            "string" -> "string"
            "boolean" -> "boolean"
            "color" -> "color"
            "enum" -> "enum"
            "reference" -> "reference"
            "handler" -> "handler"
            "object" -> "object"
            "array" -> "array"
            else -> "any"
        }
    }

    private fun getPropertyIcon(type: String): Icon {
        return when (type) {
            "number" -> AllIcons.Nodes.Parameter
            "string" -> AllIcons.Nodes.Constant
            "boolean" -> AllIcons.Nodes.Constant
            "color" -> AllIcons.Gutter.Colors
            "enum" -> AllIcons.Nodes.Enum
            "reference" -> AllIcons.Nodes.Property
            "handler" -> AllIcons.Nodes.Method
            "object" -> AllIcons.Nodes.Artifact
            "array" -> AllIcons.Nodes.Artifact
            else -> AllIcons.Nodes.Property
        }
    }

    private fun getPropertyInsertText(property: PropertyDef): String {
        return when (property.type) {
            "color" -> "#"
            "boolean" -> "true"
            "number" -> ""
            "string" -> "\"\""
            "reference" -> "@"
            "handler" -> "\"\""
            "object" -> "()"
            "array" -> "[]"
            else -> ""
        }
    }

    private fun isColorProperty(propertyName: String): Boolean {
        return propertyName.contains("Color", ignoreCase = true) ||
               propertyName.equals("Tint", ignoreCase = true) ||
               propertyName.equals("Background", ignoreCase = true) ||
               propertyName.equals("Foreground", ignoreCase = true)
    }

    private fun isBooleanProperty(propertyName: String): Boolean {
        return propertyName in listOf(
            "Visible", "Enabled", "Focusable", "Clickable", "Draggable",
            "Droppable", "Selected", "Checked", "Disabled", "Readonly",
            "ShowTooltip", "FillParent", "FitContent", "Stretch", "PreserveAspect",
            "RenderBold", "RenderItalic", "TextWrap"
        )
    }

    private fun isReferenceProperty(propertyName: String): Boolean {
        return propertyName.equals("Style", ignoreCase = true) ||
               propertyName.endsWith("Style", ignoreCase = true)
    }

    private fun createColorIcon(hex: String): Icon {
        return try {
            val cleanHex = hex.removePrefix("#")
            val color = Color(
                cleanHex.substring(0, 2).toInt(16),
                cleanHex.substring(2, 4).toInt(16),
                cleanHex.substring(4, 6).toInt(16)
            )
            ColorIcon(12, color)
        } catch (e: Exception) {
            AllIcons.Gutter.Colors
        }
    }

    /**
     * Completion context types.
     */
    private sealed class CompletionContext {
        object TopLevel : CompletionContext()
        data class InsideComponent(val componentType: String?) : CompletionContext()
        data class PropertyValue(val propertyName: String, val componentType: String?) : CompletionContext()
        object AnchorProperty : CompletionContext()
        object StyleState : CompletionContext()
        object StyleDefinition : CompletionContext()
        object Reference : CompletionContext()
        object TemplateRef : CompletionContext()
    }
}

/**
 * Simple color icon for color preview in completions.
 */
private class ColorIcon(private val size: Int, private val color: Color) : Icon {
    override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )

            // Draw border
            g2.color = JBColor.GRAY
            g2.fillRoundRect(x, y, size, size, 2, 2)

            // Draw color
            g2.color = color
            g2.fillRoundRect(x + 1, y + 1, size - 2, size - 2, 2, 2)
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size
}
