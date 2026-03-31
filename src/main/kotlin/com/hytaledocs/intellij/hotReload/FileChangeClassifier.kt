package com.hytaledocs.intellij.hotReload

interface FileChangeClassifier {
    fun classify(
        absolutePath: String,
        projectBasePath: String,
        isDeleted: Boolean,
    ): FileChangeType?
}