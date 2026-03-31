package com.hytaledocs.intellij.hotReload

import java.io.File

class HytaleFileChangeClassifier : FileChangeClassifier {
    override fun classify(
        absolutePath: String,
        projectBasePath: String,
        isDeleted: Boolean
    ): FileChangeType? {
        val path = absolutePath.replace(File.separatorChar, '/')
        val base = projectBasePath.replace(File.separatorChar, '/')

        if (!path.startsWith(base)) return null

        return when {
            path.containsSegment(RESOURCES_SEGMENT) ->
                syncOrDelete(
                    path = path,
                    isDelete = isDeleted,
                    targetPath = { relative -> "$base/$SERVER_MODS_SEGMENT/$relative" },
                    segmentAfter = RESOURCES_SEGMENT,
                )

            path.containsSegment(SERVER_MODS_SEGMENT) ->
                syncOrDelete(
                    path = path,
                    isDelete = isDeleted,
                    targetPath = { relative -> "$base/$RESOURCES_SEGMENT/$relative" },
                    segmentAfter = SERVER_MODS_SEGMENT,
                )


            SOURCE_SEGMENTS.any { path.containsSegment(it) } ->
                FileChangeType.SourceCodeChanged

            else -> null
        }

    }


    private fun syncOrDelete(
        path: String,
        isDelete: Boolean,
        targetPath: (relative: String) -> String,
        segmentAfter: String,
    ): FileChangeType {
        val relative = path.substringAfter("$segmentAfter/")
        val target = targetPath(relative)
        return if (isDelete) {
            FileChangeType.SyncDelete(absoluteTargetPath = target)
        } else {
            FileChangeType.SyncWrite(absoluteSourcePath = path, absoluteTargetPath = target)
        }
    }

    private fun String.containsSegment(segment: String): Boolean =
        contains("/$segment/") || contains("/$segment")


    private companion object {
        const val RESOURCES_SEGMENT = "src/main/resources"
        const val SERVER_MODS_SEGMENT = "server/mods/test"
        val SOURCE_SEGMENTS = listOf("src/main/java", "src/main/kotlin")
    }
}