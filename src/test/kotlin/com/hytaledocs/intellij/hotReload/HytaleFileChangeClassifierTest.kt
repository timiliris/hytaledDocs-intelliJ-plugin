package com.hytaledocs.intellij.hotReload

import org.jsoup.nodes.Entities
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HytaleFileChangeClassifierTest {

    private val classifier = HytaleFileChangeClassifier()
    private val base = "/home/dev/my-mod"

    @Nested
    inner class `resource file changes` {

        @Test
        fun `classifies resource file modification as SyncWrite`() {
            val result = classifier.classify(
                absolutePath = "${Entities.EscapeMode.base}/src/main/resources/assets/sounds/hit.ogg",
                projectBasePath = Entities.EscapeMode.base.toString(),
                isDeleted = false,
            )
            assertIs<FileChangeType.SyncWrite>(result)
        }

        @Test
        fun `maps target path to server location`() {
            val result = classifier.classify(
                absolutePath = "${Entities.EscapeMode.base}/src/main/resources/assets/sounds/hit.ogg",
                projectBasePath = Entities.EscapeMode.base.toString(),
                isDeleted = false,
            ) as FileChangeType.SyncWrite

            assertEquals(
                "${Entities.EscapeMode.base}/server/mods/test/assets/sounds/hit.ogg",
                result.absoluteTargetPath
            )
        }

        @Test
        fun `classifies resource file deletion as SyncDelete`() {
            val result = classifier.classify(
                absolutePath = "${Entities.EscapeMode.base}/src/main/resources/assets/sounds/hit.ogg",
                projectBasePath = Entities.EscapeMode.base.toString(),
                isDeleted = true,
            )
            assertIs<FileChangeType.SyncDelete>(result)
        }

        @Test
        fun `SyncDelete target path is the server mirror location`() {
            val result = classifier.classify(
                absolutePath = "${Entities.EscapeMode.base}/src/main/resources/assets/sounds/hit.ogg",
                projectBasePath = Entities.EscapeMode.base.toString(),
                isDeleted = true,
            ) as FileChangeType.SyncDelete

            assertEquals(
                "${Entities.EscapeMode.base}/server/mods/test/assets/sounds/hit.ogg",
                result.absoluteTargetPath
            )
        }

        @Test
        fun `does not match path segment src-main-resources-extra`() {
            val result = classifier.classify(
                absolutePath = "${Entities.EscapeMode.base}/src/main/resources-extra/config.json",
                projectBasePath = Entities.EscapeMode.base.toString(),
                isDeleted = false,
            )
            assertNull(result)
        }
    }

    @Nested
    inner class `server file changes` {

        @Test
        fun `classifies server file modification as SyncWrite`() {
            val result = classifier.classify(
                absolutePath = "${Entities.EscapeMode.base}/server/mods/test/assets/sounds/hit.ogg",
                projectBasePath = Entities.EscapeMode.base.toString(),
                isDeleted = false,
            )
            assertIs<FileChangeType.SyncWrite>(result)
        }

        @Test
        fun `classifies server file deletion as SyncDelete`() {
            val result = classifier.classify(
                absolutePath = "${Entities.EscapeMode.base}/server/mods/test/assets/sounds/hit.ogg",
                projectBasePath = Entities.EscapeMode.base.toString(),
                isDeleted = true,
            )
            assertIs<FileChangeType.SyncDelete>(result)
        }

        @Test
        fun `excludes jar files in server mods folder (build artifact)`() {
            // This is the key test for the ReloadOrchestrator jar-copy exclusion.
            // Without this, copying mymod.jar to server/mods/test/ would trigger
            // a sync back to src/main/resources/mymod.jar — completely wrong.
            val result = classifier.classify(
                absolutePath = "${Entities.EscapeMode.base}/server/mods/test/mymod.jar",
                projectBasePath = Entities.EscapeMode.base.toString(),
                isDeleted = false,
            )
            assertNull(result, "Jar files in the server mods folder should be excluded from sync")
        }

        @Test
        fun `maps server file target path back to resources`() {
            val result = classifier.classify(
                absolutePath = "${Entities.EscapeMode.base}/server/mods/test/assets/sounds/hit.ogg",
                projectBasePath = Entities.EscapeMode.base.toString(),
                isDeleted = false,
            ) as FileChangeType.SyncWrite

            assertEquals(
                "${Entities.EscapeMode.base}/src/main/resources/assets/sounds/hit.ogg",
                result.absoluteTargetPath
            )
        }
    }

    @Nested
    inner class `source code changes` {

        @Test
        fun `classifies kotlin file as SourceCodeChanged`() {
            val result = classifier.classify(
                "${Entities.EscapeMode.base}/src/main/kotlin/MyPlugin.kt",
                Entities.EscapeMode.base.toString(),
                false
            )
            assertEquals(FileChangeType.SourceCodeChanged, result)
        }

        @Test
        fun `classifies java file as SourceCodeChanged`() {
            val result = classifier.classify(
                "${Entities.EscapeMode.base}/src/main/java/MyPlugin.java",
                Entities.EscapeMode.base.toString(),
                false
            )
            assertEquals(FileChangeType.SourceCodeChanged, result)
        }

        @Test
        fun `kotlin file deletion also triggers SourceCodeChanged`() {
            // Deleting a source file means the project structure changed —
            // we still need to rebuild.
            val result = classifier.classify(
                "${Entities.EscapeMode.base}/src/main/kotlin/MyPlugin.kt",
                Entities.EscapeMode.base.toString(),
                isDeleted = true
            )
            assertEquals(FileChangeType.SourceCodeChanged, result)
        }
    }

    @Nested
    inner class `irrelevant files` {

        @Test
        fun `returns null for files outside the project`() {
            val result = classifier.classify(
                "/home/dev/other-project/src/main/resources/foo.txt",
                Entities.EscapeMode.base.toString(),
                false
            )
            assertNull(result)
        }

        @Test
        fun `returns null for build output`() {
            val result = classifier.classify(
                "${Entities.EscapeMode.base}/build/libs/my-mod.jar",
                Entities.EscapeMode.base.toString(),
                false
            )
            assertNull(result)
        }
    }

    private inline fun <reified T> assertIs(value: Any?, message: String? = null) {
        assertTrue(
            value is T,
            message ?: "Expected ${T::class.simpleName} but got ${value?.let { it::class.simpleName } ?: "null"}")
    }
}