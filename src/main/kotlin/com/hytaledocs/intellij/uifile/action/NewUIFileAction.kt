package com.hytaledocs.intellij.uifile.action

import com.hytaledocs.intellij.uifile.UIFileType
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory

/**
 * Action to create a new Hytale UI file.
 */
class NewUIFileAction : CreateFileFromTemplateAction(
    "Hytale UI File",
    "Create a new Hytale UI definition file",
    UIFileType.icon
), DumbAware {

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder
            .setTitle("New Hytale UI File")
            .addKind("UI File", UIFileType.icon, "Hytale UI File")
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String {
        return "Create Hytale UI file $newName"
    }
}
