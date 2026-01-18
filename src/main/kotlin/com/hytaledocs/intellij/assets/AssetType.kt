package com.hytaledocs.intellij.assets

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * Enum representing the different types of assets supported in Hytale projects.
 * Each type has associated file extensions, display name, and icon.
 */
enum class AssetType(
    val displayName: String,
    val displayNameFr: String,
    val extensions: Set<String>,
    val icon: Icon,
    val previewType: PreviewType
) {
    TEXTURE(
        displayName = "Textures",
        displayNameFr = "Textures",
        extensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp"),
        icon = AllIcons.FileTypes.Image,
        previewType = PreviewType.IMAGE
    ),
    MODEL(
        displayName = "Models",
        displayNameFr = "Modeles",
        extensions = setOf("blockymodel", "gltf", "glb"),
        icon = AllIcons.FileTypes.Json,
        previewType = PreviewType.JSON
    ),
    ANIMATION(
        displayName = "Animations",
        displayNameFr = "Animations",
        extensions = setOf("blockyanim"),
        icon = AllIcons.Actions.Play_forward,
        previewType = PreviewType.JSON
    ),
    SOUND(
        displayName = "Sounds",
        displayNameFr = "Sons",
        extensions = setOf("ogg", "wav", "mp3"),
        icon = AllIcons.FileTypes.Properties,
        previewType = PreviewType.AUDIO
    ),
    DATA(
        displayName = "Data",
        displayNameFr = "Donnees",
        extensions = setOf("json"),
        icon = AllIcons.FileTypes.Json,
        previewType = PreviewType.JSON
    ),
    UI(
        displayName = "UI",
        displayNameFr = "Interface",
        extensions = setOf("ui"),
        icon = AllIcons.FileTypes.Xml,
        previewType = PreviewType.TEXT
    ),
    CONFIG(
        displayName = "Config",
        displayNameFr = "Configuration",
        extensions = setOf("yml", "yaml", "toml", "properties"),
        icon = AllIcons.FileTypes.Config,
        previewType = PreviewType.TEXT
    ),
    OTHER(
        displayName = "Other",
        displayNameFr = "Autres",
        extensions = emptySet(),
        icon = AllIcons.FileTypes.Any_type,
        previewType = PreviewType.NONE
    );

    companion object {
        /**
         * Get the asset type for a given file extension.
         */
        fun fromExtension(extension: String): AssetType {
            val ext = extension.lowercase().removePrefix(".")
            return entries.find { it.extensions.contains(ext) } ?: OTHER
        }

        /**
         * Get the asset type for a given file name.
         */
        fun fromFileName(fileName: String): AssetType {
            val extension = fileName.substringAfterLast('.', "")
            return fromExtension(extension)
        }

        /**
         * Get all supported file extensions.
         */
        fun allExtensions(): Set<String> {
            return entries.flatMap { it.extensions }.toSet()
        }
    }
}

/**
 * Type of preview panel to use for an asset.
 */
enum class PreviewType {
    IMAGE,
    JSON,
    AUDIO,
    TEXT,
    NONE
}
