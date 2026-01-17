package com.hytaledocs.intellij.actions

import com.hytaledocs.intellij.HytaleIcons
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory

class NewPluginAction : CreateFileFromTemplateAction(
    "Hytale Plugin",
    "Create a new Hytale plugin class",
    HytaleIcons.HYTALE
), DumbAware {

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Hytale Plugin")
            .addKind("Plugin Class", HytaleIcons.HYTALE, "Hytale Plugin")
    }

    override fun getActionName(directory: PsiDirectory, newName: String, templateName: String): String {
        return "Creating Hytale Plugin: $newName"
    }
}

class NewListenerAction : CreateFileFromTemplateAction(
    "Hytale Listener",
    "Create a new Hytale event listener",
    HytaleIcons.HYTALE
), DumbAware {

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Hytale Listener")
            .addKind("Event Listener", HytaleIcons.HYTALE, "Hytale Listener")
    }

    override fun getActionName(directory: PsiDirectory, newName: String, templateName: String): String {
        return "Creating Hytale Listener: $newName"
    }
}

class NewCommandAction : CreateFileFromTemplateAction(
    "Hytale Command",
    "Create a new Hytale command",
    HytaleIcons.HYTALE
), DumbAware {

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Hytale Command")
            .addKind("Command", HytaleIcons.HYTALE, "Hytale Command")
    }

    override fun getActionName(directory: PsiDirectory, newName: String, templateName: String): String {
        return "Creating Hytale Command: $newName"
    }
}
