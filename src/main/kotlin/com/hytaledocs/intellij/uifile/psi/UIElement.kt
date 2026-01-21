package com.hytaledocs.intellij.uifile.psi

import com.intellij.psi.impl.source.tree.CompositePsiElement
import com.intellij.psi.tree.IElementType

/**
 * Generic PSI element for UI file constructs.
 */
class UIElement(type: IElementType) : CompositePsiElement(type) {

    override fun toString(): String = "UIElement(${node.elementType})"
}
