package com.hytaledocs.intellij.hotReload

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HotReloadListener(
    private val project: Project,
    private val classifier: FileChangeClassifier,
    private val synchronizer: FileSynchronizer,
    private val recentlySyncedPath: MutableSet<String>,
    private val updateJarPlugin: () -> Boolean
) : BulkFileListener {

    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val updateJob = AtomicReference<java.util.concurrent.ScheduledFuture<*>?>(null)

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
            scheduleUpdate()
            sourceCodeChanges = false
        }
    }

    private fun scheduleUpdate() {
        val oldJob = updateJob.getAndSet(
            scheduler.schedule({
                updateJarPlugin()
                updateJob.set(null)
            }, 500, TimeUnit.MILLISECONDS)
        )
        oldJob?.cancel(false)
    }

}