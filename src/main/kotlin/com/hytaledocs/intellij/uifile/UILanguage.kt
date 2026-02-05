package com.hytaledocs.intellij.uifile

import com.intellij.lang.Language

/**
 * Defines the UI file language for Hytale .ui files.
 *
 * Uses class + lazy companion instead of object to avoid NoClassDefFoundError
 * when <clinit> fails during stub indexing on a worker thread (JVM spec 5.5:
 * a failed <clinit> permanently breaks the class).
 */
class UILanguage private constructor() : Language("HytaleUI") {

    companion object {
        @JvmStatic
        val INSTANCE: UILanguage by lazy { UILanguage() }
    }

    override fun getDisplayName(): String = "Hytale UI"

    override fun isCaseSensitive(): Boolean = true
}
