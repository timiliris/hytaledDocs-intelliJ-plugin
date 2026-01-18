package com.hytaledocs.intellij.completion.providers

import com.hytaledocs.intellij.HytaleIcons
import com.hytaledocs.intellij.completion.data.LifecycleMethodInfo
import com.hytaledocs.intellij.services.ServerDataService
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * Provides completion for Hytale plugin lifecycle methods.
 * Suggests override methods like setup(), start(), shutdown() in plugin classes.
 */
class PluginMethodCompletionProvider : CompletionProvider<CompletionParameters>() {

    companion object {
        private const val JAVA_PLUGIN_CLASS = "com.hypixel.hytale.server.core.plugin.JavaPlugin"
        private const val PLUGIN_BASE_CLASS = "com.hypixel.hytale.server.core.plugin.PluginBase"

        private val PLUGIN_CLASS_NAMES = setOf(
            "JavaPlugin",
            "PluginBase"
        )
    }

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val position = parameters.position
        val containingClass = PsiTreeUtil.getParentOfType(position, PsiClass::class.java) ?: return

        // Check if the class extends JavaPlugin or PluginBase
        if (!isPluginClass(containingClass)) {
            return
        }

        val dataService = ServerDataService.getInstance()
        val lifecycleMethods = dataService.getLifecycleMethods()

        // Get methods that haven't been overridden yet
        val existingMethodNames = containingClass.methods.map { it.name }.toSet()

        for (method in lifecycleMethods) {
            // Skip if already overridden
            if (method.name in existingMethodNames) {
                continue
            }

            val lookupElement = createLifecycleMethodLookupElement(method, containingClass)
            result.addElement(lookupElement)
        }

        // Also suggest API access methods from PluginBase
        addPluginApiMethods(containingClass, existingMethodNames, result, dataService)
    }

    /**
     * Check if the class extends JavaPlugin or PluginBase.
     */
    private fun isPluginClass(psiClass: PsiClass): Boolean {
        var currentClass: PsiClass? = psiClass

        while (currentClass != null) {
            val superClass = currentClass.superClass
            if (superClass != null) {
                val superName = superClass.qualifiedName
                if (superName == JAVA_PLUGIN_CLASS || superName == PLUGIN_BASE_CLASS) {
                    return true
                }
                if (PLUGIN_CLASS_NAMES.contains(superClass.name)) {
                    return true
                }
            }
            currentClass = superClass
        }

        // Also check interface implementations (unlikely but possible)
        return false
    }

    /**
     * Create a lookup element for a lifecycle method with full override implementation.
     */
    private fun createLifecycleMethodLookupElement(
        method: LifecycleMethodInfo,
        containingClass: PsiClass
    ): LookupElement {
        val signature = method.signature
        val returnType = extractReturnType(signature)
        val params = extractParameters(signature)
        val methodName = method.name

        val description = method.description ?: "Plugin lifecycle method"

        return LookupElementBuilder.create(methodName)
            .withIcon(AllIcons.Nodes.Method)
            .withTypeText(returnType, true)
            .withTailText("() - override lifecycle method", true)
            .withPresentableText("override $methodName")
            .withInsertHandler { insertContext, _ ->
                insertOverrideMethod(insertContext, method, containingClass)
            }
            .let { PrioritizedLookupElement.withPriority(it, 150.0) } // High priority for lifecycle methods
    }

    /**
     * Insert a full override method implementation.
     */
    private fun insertOverrideMethod(
        context: InsertionContext,
        method: LifecycleMethodInfo,
        containingClass: PsiClass
    ) {
        val document = context.document
        val editor = context.editor

        // Delete the partial text that was typed
        val startOffset = context.startOffset
        val tailOffset = context.tailOffset
        document.deleteString(startOffset, tailOffset)

        // Generate the override method code
        val returnType = extractReturnType(method.signature)
        val methodName = method.name
        val isVoid = returnType == "void"
        val hasReturn = returnType.contains("CompletableFuture")

        val methodCode = buildString {
            append("\n    @Override\n")
            if (returnType.contains("CompletableFuture")) {
                append("    public $returnType $methodName() {\n")
                append("        // TODO: Implement $methodName\n")
                append("        return super.$methodName();\n")
            } else {
                append("    protected void $methodName() {\n")
                append("        // TODO: Implement $methodName\n")
            }
            append("    }\n")
        }

        // Insert at the current position
        document.insertString(startOffset, methodCode)

        // Move caret to the TODO line
        val todoOffset = startOffset + methodCode.indexOf("// TODO")
        if (todoOffset > startOffset) {
            editor.caretModel.moveToOffset(todoOffset + "// TODO: Implement ".length + methodName.length)
        }

        // Reformat the inserted code
        context.commitDocument()
    }

    /**
     * Add plugin API method suggestions (getEventRegistry, getCommandRegistry, etc.).
     */
    private fun addPluginApiMethods(
        containingClass: PsiClass,
        existingMethodNames: Set<String>,
        result: CompletionResultSet,
        dataService: ServerDataService
    ) {
        val apiMethods = dataService.getPluginApiMethods()

        for ((name, info) in apiMethods) {
            // Extract method name from "getXxx()" format
            val methodName = info.method?.substringBefore("(") ?: continue

            // Create a suggestion for calling these methods
            val returnType = info.returnType ?: "Object"

            val lookupElement = LookupElementBuilder.create(methodName)
                .withIcon(AllIcons.Nodes.Method)
                .withTypeText(returnType, true)
                .withTailText("() - plugin API", true)
                .withInsertHandler { insertContext, _ ->
                    insertContext.document.insertString(insertContext.tailOffset, "()")
                    insertContext.editor.caretModel.moveToOffset(insertContext.tailOffset)
                }

            result.addElement(PrioritizedLookupElement.withPriority(lookupElement, 80.0))
        }
    }

    /**
     * Extract the return type from a method signature.
     */
    private fun extractReturnType(signature: String): String {
        // Signature format: "public ReturnType methodName()" or "protected void methodName()"
        val normalized = signature.trim()
            .removePrefix("@Nullable\n")
            .removePrefix("public ")
            .removePrefix("protected ")
            .removePrefix("private ")

        val parts = normalized.split(" ")
        return if (parts.isNotEmpty()) parts[0] else "void"
    }

    /**
     * Extract parameters from a method signature.
     */
    private fun extractParameters(signature: String): String {
        val startParen = signature.indexOf('(')
        val endParen = signature.lastIndexOf(')')

        return if (startParen != -1 && endParen != -1 && endParen > startParen) {
            signature.substring(startParen + 1, endParen)
        } else {
            ""
        }
    }
}
