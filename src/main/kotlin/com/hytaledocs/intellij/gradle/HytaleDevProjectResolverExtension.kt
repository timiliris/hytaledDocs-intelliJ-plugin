package com.hytaledocs.intellij.gradle

import com.hytaledocs.intellij.gradle.tooling.HytaleDevModel
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ModuleData
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension

/**
 * Gradle ProjectResolverExtension that queries the HytaleDevModel during Gradle sync.
 * When the hytale-dev plugin is detected, it creates a HytaleDevData child node on the module.
 */
class HytaleDevProjectResolverExtension : AbstractProjectResolverExtension() {

    override fun getExtraProjectModelClasses(): Set<Class<out Any>> {
        return setOf(HytaleDevModel::class.java)
    }

    override fun getToolingExtensionsClasses(): Set<Class<out Any>> {
        return extraProjectModelClasses
    }

    override fun populateModuleExtraModels(gradleModule: IdeaModule, ideModule: DataNode<ModuleData>) {
        val model = resolverCtx.getExtraProject(gradleModule, HytaleDevModel::class.java)

        if (model != null && model.hasHytaleDevPlugin()) {
            ideModule.createChild(
                HytaleDevData.KEY,
                HytaleDevData(ideModule.data, model.pluginVersion)
            )
        }

        super.populateModuleExtraModels(gradleModule, ideModule)
    }
}
