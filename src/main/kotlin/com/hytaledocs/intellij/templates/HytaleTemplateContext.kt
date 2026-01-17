package com.hytaledocs.intellij.templates

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType

class HytaleTemplateContext : TemplateContextType("Hytale") {

    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file
        val text = file.text

        // Check if file contains Hytale imports
        return text.contains("com.hypixel.hytale") ||
               text.contains("JavaPlugin") ||
               text.contains("EventRegistry")
    }
}
