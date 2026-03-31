package com.hytaledocs.intellij.hotReload

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.rt.coverage.util.ErrorReporter.printInfo
import java.io.File

class HotReloadListener(
    private val project: Project,
) : BulkFileListener {

    private val projectBasePath: String? get() = project.basePath

    override fun after(events: List<VFileEvent>) {
        val basePath = projectBasePath ?: return

        for (event in events) {
            val file = event.file ?: continue
            val path = file.path

            if (event.requestor == this) continue

            if (path.contains("src/main/resources")) {
                val serverFilePath = path.substringAfter("src/main/resources/")

                syncFile(File(path), File("$basePath/server/mods/test/$serverFilePath"))
            }

            if (path.contains("server/mods/test/")) {
                val resourceFilePath = path.substringAfter("server/mods/test/")

                syncFile(File(path), File("$basePath/src/main/resources/$resourceFilePath"))
            }

            if (path.contains("src/main/java") || path.contains("src/main/kotlin")) {
                printInfo("java changed")
            }

            printInfo("We are getting closer")
        }
    }

    private fun syncFile(source: File, target: File) {
        try {
            if (!source.exists()) return

            if (!target.parentFile.exists()) {
                target.mkdirs()
            }

            val overwrite = target.exists()
            source.copyTo(target, overwrite = overwrite)

            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
        } catch (e: Exception) {
            com.intellij.openapi.diagnostic.Logger.getInstance(HotReloadListener::class.java).error(e)
        }
    }

}