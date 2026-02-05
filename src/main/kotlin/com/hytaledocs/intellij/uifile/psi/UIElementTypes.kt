package com.hytaledocs.intellij.uifile.psi

import com.hytaledocs.intellij.uifile.UILanguage
import com.intellij.psi.tree.IFileElementType

/**
 * Element types for UI PSI tree.
 */
object UIElementTypes {

    val FILE: IFileElementType by lazy { IFileElementType(UILanguage.INSTANCE) }
}
