package com.hytaledocs.intellij.hotReload

sealed class FileChangeType {

    data class SyncWrite(
        val absoluteSourcePath: String,
        val absoluteTargetPath: String,
    ) : FileChangeType()

    data class SyncDelete(
        val absoluteTargetPath: String,
    ) : FileChangeType()

    data object SourceCodeChanged : FileChangeType()
}