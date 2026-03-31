package com.hytaledocs.intellij.hotReload

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class IntellijFileSynchronizer(
    private val recentlySyncedPath: MutableSet<String>,
) : FileSynchronizer {

    private val logger: Logger = Logger.getInstance(IntellijFileSynchronizer::class.java)

    override fun sync(source: File, target: File) {
        if (!source.exists()) {
            logger.warn("Skipping sync: source does not exist at ${source.path}")
            return
        }

        if (target.exists() && source.readBytes().contentEquals(target.readBytes())) return

        try {
            target.parentFile.mkdirs()

            recentlySyncedPath.add(target.canonicalPath)
            source.copyTo(target, overwrite = true)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)

            logger.info("Successfully synced ${source.path} -> ${target.path}")
        } catch (e: Exception) {
            recentlySyncedPath.remove(target.canonicalPath)
            logger.error("Failed to sync ${source.path}", e)
        }
    }

    override fun delete(target: File) {
        if (!target.exists()) return

        try {
            recentlySyncedPath.add(target.canonicalPath)
            target.delete()
            LocalFileSystem.getInstance().refresh(true)

            logger.info("Successfully deleted ${target.path}")
        } catch (e: Exception) {
            recentlySyncedPath.remove(target.canonicalPath)
            logger.error("Failed to delete ${target.path}", e)
        }
    }
}