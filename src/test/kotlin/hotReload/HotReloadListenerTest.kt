package hotReload

import com.hytaledocs.intellij.hotReload.HotReloadListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class HotReloadListenerTest: BasePlatformTestCase() {

    fun testHotReload() {
        // 1. Manually register your listener for the test
        val listener = HotReloadListener(project)
        project.messageBus.connect(testRootDisposable).subscribe(VirtualFileManager.VFS_CHANGES, listener)

        // 2. Create a "source" file in the mock project
        // Use a path that includes "src/main/resources"
        val relPath = "src/main/resources/config.json"

        // Ensure the file is actually on disk so nio.Path can see it
        val basePath = myFixture.project.basePath!!
        val sourceFile = Path(basePath, relPath)
        sourceFile.parent.toFile().mkdirs()
        sourceFile.toFile().writeText("{ 'key': 'value' }")

        // Refresh VFS so IntelliJ knows about it
        val virtualFile = VfsTestUtil.findFileByCaseSensitivePath(sourceFile.toString())!!

        // 3. Simulate a VFS Change Event
        VfsTestUtil.createFile(virtualFile.parent, "config.json", "{ 'key': 'new_value' }")

        // 4. Assert the file exists in the "server" folder
        val expectedServerFile = Path(basePath, "server/mods/test/config.json")
        
        assertTrue("File should have been synced to server folder: $expectedServerFile", expectedServerFile.exists())
        assertEquals("{ 'key': 'new_value' }", expectedServerFile.readText())
    }

    fun testReverseHotReload() {
        val listener = HotReloadListener(project)
        project.messageBus.connect(testRootDisposable).subscribe(VirtualFileManager.VFS_CHANGES, listener)

        val basePath = myFixture.project.basePath!!
        val relPath = "server/mods/test/mod-config.json"
        val sourceFile = Path(basePath, relPath)
        sourceFile.parent.toFile().mkdirs()
        sourceFile.toFile().writeText("{ 'mod': 'data' }")

        val virtualFile = VfsTestUtil.findFileByCaseSensitivePath(sourceFile.toString())!!
        VfsTestUtil.createFile(virtualFile.parent, "mod-config.json", "{ 'mod': 'updated_data' }")

        val expectedResourceFile = Path(basePath, "src/main/resources/mod-config.json")
        assertTrue("File should have been synced back to resources folder: $expectedResourceFile", expectedResourceFile.exists())
        assertEquals("{ 'mod': 'updated_data' }", expectedResourceFile.readText())
    }

    fun testDeepDirectorySync() {
        val listener = HotReloadListener(project)
        project.messageBus.connect(testRootDisposable).subscribe(VirtualFileManager.VFS_CHANGES, listener)

        val basePath = myFixture.project.basePath!!
        
        // Scenario: src/main/resources/common/Blocks/testblock exists, but server/mods/test/common/Blocks/testblock does not.
        val relPath = "src/main/resources/common/Blocks/testblock/block.json"
        val sourceFile = Path(basePath, relPath)
        sourceFile.parent.toFile().mkdirs()
        sourceFile.toFile().writeText("{ 'id': 'test' }")

        // Ensure server/mods exists, but subfolders don't
        Path(basePath, "server/mods").toFile().mkdirs()
        
        // Refresh VFS
        val virtualFile = VfsTestUtil.findFileByCaseSensitivePath(sourceFile.toString())!!
        
        // Simulate change
        VfsTestUtil.createFile(virtualFile.parent, "block.json", "{ 'id': 'updated' }")

        val expectedServerFile = Path(basePath, "server/mods/test/common/Blocks/testblock/block.json")
        assertTrue("Deep directory structure should have been created and file synced to: $expectedServerFile", expectedServerFile.exists())
        assertEquals("{ 'id': 'updated' }", expectedServerFile.readText())
    }

    fun testDeepDirectoryReverseSync() {
        val listener = HotReloadListener(project)
        project.messageBus.connect(testRootDisposable).subscribe(VirtualFileManager.VFS_CHANGES, listener)

        val basePath = myFixture.project.basePath!!

        // Scenario: server/mods/test/common/Blocks/testblock exists, but src/main/resources/common/Blocks/testblock does not.
        val relPath = "server/mods/test/common/Blocks/testblock/block.json"
        val sourceFile = Path(basePath, relPath)
        sourceFile.parent.toFile().mkdirs()
        sourceFile.toFile().writeText("{ 'id': 'server' }")

        // Ensure src/main/resources exists, but subfolders don't
        Path(basePath, "src/main/resources").toFile().mkdirs()

        // Refresh VFS
        val virtualFile = VfsTestUtil.findFileByCaseSensitivePath(sourceFile.toString())!!

        // Simulate change
        VfsTestUtil.createFile(virtualFile.parent, "block.json", "{ 'id': 'server_updated' }")

        val expectedResourceFile = Path(basePath, "src/main/resources/common/Blocks/testblock/block.json")
        assertTrue("Deep directory structure should have been created and file synced back to: $expectedResourceFile", expectedResourceFile.exists())
        assertEquals("{ 'id': 'server_updated' }", expectedResourceFile.readText())
    }
}