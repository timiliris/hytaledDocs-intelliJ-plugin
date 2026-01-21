package com.hytaledocs.intellij.uifile.psi

import com.hytaledocs.intellij.uifile.UIFileType
import com.hytaledocs.intellij.uifile.UILanguage
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

/**
 * PSI file representation for Hytale .ui files.
 */
class UIFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, UILanguage) {

    override fun getFileType(): FileType = UIFileType

    override fun toString(): String = "Hytale UI File"
}
