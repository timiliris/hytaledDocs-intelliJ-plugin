package com.hytaledocs.intellij.gradle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Startup activity that subscribes to Gradle sync completion events
 * and notifies the tool window to refresh its content.
 */
class HytaleGradleSyncStartupActivity : ProjectActivity {

    companion object {
        private val LOG = Logger.getInstance(HytaleGradleSyncStartupActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        // Subscribe to project data import events via message bus
        val connection = project.messageBus.connect()

        connection.subscribe(
            ProjectDataImportListener.TOPIC,
            object : ProjectDataImportListener {
                override fun onImportFinished(projectPath: String?) {
                    LOG.info("External system import finished for project: ${project.name}")

                    // Notify the tool window to refresh (on EDT)
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        refreshToolWindow(project)
                    }
                }
            }
        )
    }

    private fun refreshToolWindow(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("HytaleDocs")
        if (toolWindow != null) {
            // Get the content and check if it's our panel
            val content = toolWindow.contentManager.getContent(0)
            val component = content?.component
            if (component is HytaleToolWindowRefreshable) {
                LOG.info("Refreshing Hytale tool window after Gradle sync")
                component.onProjectTypeChanged()
            }
        }
    }
}

/**
 * Interface for tool window panels that can be refreshed when project type changes.
 */
interface HytaleToolWindowRefreshable {
    /**
     * Called when the project type detection may have changed (e.g., after Gradle sync).
     * Implementations should refresh their content accordingly.
     */
    fun onProjectTypeChanged()
}
