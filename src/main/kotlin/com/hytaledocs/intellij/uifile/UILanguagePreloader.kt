package com.hytaledocs.intellij.uifile

import com.intellij.openapi.application.PreloadingActivity
import com.intellij.openapi.progress.ProgressIndicator

/**
 * Forces early initialization of [UILanguage] on the main thread,
 * before stub indexing can trigger it on a worker thread.
 *
 * This prevents NoClassDefFoundError when UILanguage's lazy init
 * is first accessed during background indexing.
 */
class UILanguagePreloader : PreloadingActivity() {
    override fun preload(indicator: ProgressIndicator?) {
        // Touch INSTANCE to force lazy initialization on a safe thread
        UILanguage.INSTANCE
    }
}
