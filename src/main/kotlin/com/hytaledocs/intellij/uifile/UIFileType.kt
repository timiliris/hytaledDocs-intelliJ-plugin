package com.hytaledocs.intellij.uifile

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * File type for Hytale .ui definition files.
 */
object UIFileType : LanguageFileType(UILanguage) {

    override fun getName(): String = "Hytale UI File"

    override fun getDescription(): String = "Hytale UI definition file"

    override fun getDefaultExtension(): String = "ui"

    override fun getIcon(): Icon? {
        return try {
            IconLoader.getIcon("/icons/ui-file.svg", UIFileType::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
