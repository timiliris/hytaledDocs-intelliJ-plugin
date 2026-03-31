package com.hytaledocs.intellij.hotReload

import java.io.File

interface FileSynchronizer {
    fun sync(source: File, target: File)
    fun delete(target: File)
}