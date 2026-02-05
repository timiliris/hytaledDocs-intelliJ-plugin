package com.hytaledocs.intellij.uifile

import com.intellij.ide.AppLifecycleListener

/**
 * Forces early initialization of [UILanguage] before stub indexing
 * can trigger it on a worker thread.
 *
 * This prevents NoClassDefFoundError when UILanguage's lazy init
 * is first accessed during background indexing.
 */
class UILanguagePreloader : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        // Touch INSTANCE to force lazy initialization on a safe thread
        UILanguage.INSTANCE
    }
}
