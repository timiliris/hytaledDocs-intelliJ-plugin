package com.hytaledocs.intellij.hotReload

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File

class HotReloadListener(
    private val project: Project,
    private val classifier: FileChangeClassifier,
    private val synchronizer: FileSynchronizer,
    private val recentlySyncedPath: MutableSet<String>,
    private val updateJarPlugin: () -> Boolean
) : BulkFileListener {


    override fun after(events: List<VFileEvent>) {
        val basePath = project.basePath ?: return
        var sourceCodeChanges = false

        for (event in events) {
            val path = event.path
            val canonicalPath = File(path).canonicalPath

            if (recentlySyncedPath.remove(canonicalPath)) continue

            val isDelete = event is VFileDeleteEvent

            when (val change = classifier.classify(path, basePath, isDelete)) {
                is FileChangeType.SyncWrite -> synchronizer.sync(
                    File(change.absoluteSourcePath),
                    File(change.absoluteTargetPath)
                )

                is FileChangeType.SyncDelete -> synchronizer.delete(File(change.absoluteTargetPath))

                FileChangeType.SourceCodeChanged -> sourceCodeChanges = true

                null -> Unit
            }
        }

        if (sourceCodeChanges) {
            updateJarPlugin()
            sourceCodeChanges = false
        }
    }

}