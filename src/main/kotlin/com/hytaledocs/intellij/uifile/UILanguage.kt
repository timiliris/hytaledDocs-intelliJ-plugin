package com.hytaledocs.intellij.uifile

import com.intellij.lang.Language

/**
 * Defines the UI file language for Hytale .ui files.
 */
object UILanguage : Language("HytaleUI") {

    override fun getDisplayName(): String = "Hytale UI"

    override fun isCaseSensitive(): Boolean = true
}
