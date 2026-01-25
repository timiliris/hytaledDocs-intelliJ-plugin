package com.hytaledocs.intellij.gradle

import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData
import com.intellij.openapi.externalSystem.model.project.ModuleData

/**
 * Data class stored in the ExternalSystem DataNode when the hytale-dev Gradle plugin is detected.
 * This allows quick lookup of whether a module uses the Hytale Dev plugin without re-querying Gradle.
 */
data class HytaleDevData(
    val module: ModuleData,
    val pluginVersion: String?
) : AbstractExternalEntityData(module.owner) {

    companion object {
        /**
         * Key for storing HytaleDevData in the DataNode tree.
         * Processing weight is set higher than TASK to ensure it's processed after basic module data.
         */
        val KEY: Key<HytaleDevData> = Key.create(
            HytaleDevData::class.java,
            ProjectKeys.TASK.processingWeight + 1
        )
    }
}
