package com.hytaledocs.intellij.completion.providers

import com.hytaledocs.intellij.HytaleIcons
import com.hytaledocs.intellij.completion.data.EventInfo
import com.hytaledocs.intellij.completion.data.FieldInfo
import com.hytaledocs.intellij.completion.data.MethodInfo
import com.hytaledocs.intellij.completion.data.safeFields
import com.hytaledocs.intellij.completion.data.safeMethods
import com.hytaledocs.intellij.services.ServerDataService
import com.hytaledocs.intellij.settings.HytaleAppSettings
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import javax.swing.Icon

/**
 * Provides completion for methods on Hytale event objects.
 * Suggests methods and field accessors after the dot operator.
 * e.g., event.getPlayer|(), event.isCancelled|()
 */
class EventMethodCompletionProvider : CompletionProvider<CompletionParameters>() {

    companion object {
        private const val HYTALE_PACKAGE_PREFIX = "com.hypixel.hytale"
    }

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        // Check if Hytale completion is enabled
        val settings = HytaleAppSettings.getInstance()
        if (!settings.enableCodeCompletion) {
            return
        }

        val position = parameters.position

        // Find the expression before the dot
        val methodCallExpr = PsiTreeUtil.getParentOfType(position, PsiMethodCallExpression::class.java)
        val refExpr = PsiTreeUtil.getParentOfType(position, PsiReferenceExpression::class.java)

        val qualifierType = findQualifierType(methodCallExpr, refExpr, position) ?: return

        // Check if it's a Hytale event type
        val className = extractClassName(qualifierType)
        if (className.isNullOrEmpty()) return

        val dataService = ServerDataService.getInstance()

        // Try to find the event by class name
        val event = dataService.getEventByClassName(className)
            ?: dataService.getEventByFullName(qualifierType)

        if (event != null) {
            addEventMethods(event, result)
            addFieldAccessors(event, result)
            addInheritedMethods(event, dataService, result)
        }
    }

    /**
     * Find the type of the qualifier expression (the part before the dot).
     */
    private fun findQualifierType(
        methodCall: PsiMethodCallExpression?,
        refExpr: PsiReferenceExpression?,
        position: PsiElement
    ): String? {
        // Try method call qualifier
        if (methodCall != null) {
            val qualifier = methodCall.methodExpression.qualifierExpression
            if (qualifier != null) {
                return qualifier.type?.canonicalText
            }
        }

        // Try reference expression qualifier
        if (refExpr != null) {
            val qualifier = refExpr.qualifierExpression
            if (qualifier != null) {
                return qualifier.type?.canonicalText
            }
        }

        // Try to find a local variable or parameter before the position
        val prevSibling = position.prevSibling
        if (prevSibling is PsiReferenceExpression) {
            val resolved = prevSibling.resolve()
            if (resolved is PsiVariable) {
                return resolved.type.canonicalText
            }
        }

        return null
    }

    /**
     * Extract the simple class name from a fully qualified name or generic type.
     */
    private fun extractClassName(typeName: String): String? {
        // Handle generic types like Consumer<PlayerConnectEvent>
        val withoutGenerics = typeName.substringBefore('<').substringAfterLast('.')

        // Check if it's a Hytale type
        if (typeName.startsWith(HYTALE_PACKAGE_PREFIX)) {
            return withoutGenerics
        }

        // Also check for simple name matching
        if (withoutGenerics.endsWith("Event")) {
            return withoutGenerics
        }

        return null
    }

    /**
     * Add methods defined directly on the event.
     * Note: Gson can set list fields to null despite Kotlin non-null defaults,
     * so we use safeMethods()/safeFields() to guard against NPE.
     */
    private fun addEventMethods(event: EventInfo, result: CompletionResultSet) {
        for (method in event.safeMethods()) {
            val lookupElement = createMethodLookupElement(method)
            result.addElement(lookupElement)
        }
    }

    /**
     * Add field accessors (getter methods for fields).
     */
    private fun addFieldAccessors(event: EventInfo, result: CompletionResultSet) {
        val methods = event.safeMethods()
        for (field in event.safeFields()) {
            // If the field has an accessor, suggest it
            val accessor = field.accessor
            if (accessor != null && !methods.any { it.name == accessor.substringBefore("(") }) {
                val lookupElement = createFieldAccessorLookupElement(field)
                result.addElement(lookupElement)
            }
        }
    }

    /**
     * Add methods inherited from parent classes.
     */
    private fun addInheritedMethods(event: EventInfo, dataService: ServerDataService, result: CompletionResultSet) {
        // Add ICancellable methods if event is cancellable
        if (event.cancellable) {
            addCancellableMethods(result)
        }

        // Traverse parent hierarchy and add inherited methods
        var parentName = event.parent
        val visitedParents = mutableSetOf<String>()

        while (parentName != null && parentName !in visitedParents) {
            visitedParents.add(parentName)

            // Check if parent is in events list
            val parentEvent = dataService.getEventByClassName(parentName)
            if (parentEvent != null) {
                for (method in parentEvent.safeMethods()) {
                    val lookupElement = createMethodLookupElement(method, inherited = true)
                    result.addElement(lookupElement)
                }
                for (field in parentEvent.safeFields()) {
                    val accessor = field.accessor
                    if (accessor != null) {
                        val lookupElement = createFieldAccessorLookupElement(field, inherited = true)
                        result.addElement(lookupElement)
                    }
                }
                parentName = parentEvent.parent
            } else {
                // Check core interfaces
                val coreInterface = dataService.getCoreInterfaces().find { it.className == parentName }
                if (coreInterface != null) {
                    for (method in coreInterface.safeMethods()) {
                        val lookupElement = createMethodLookupElement(method, inherited = true)
                        result.addElement(lookupElement)
                    }
                }
                break
            }
        }
    }

    /**
     * Add ICancellable methods (isCancelled, setCancelled).
     */
    private fun addCancellableMethods(result: CompletionResultSet) {
        result.addElement(
            LookupElementBuilder.create("isCancelled")
                .withIcon(AllIcons.Nodes.Method)
                .withTypeText("boolean")
                .withTailText("()", true)
                .withInsertHandler { context, _ ->
                    context.document.insertString(context.tailOffset, "()")
                    context.editor.caretModel.moveToOffset(context.tailOffset)
                }
        )

        result.addElement(
            LookupElementBuilder.create("setCancelled")
                .withIcon(AllIcons.Nodes.Method)
                .withTypeText("void")
                .withTailText("(boolean cancelled)", true)
                .withInsertHandler { context, _ ->
                    context.document.insertString(context.tailOffset, "()")
                    context.editor.caretModel.moveToOffset(context.tailOffset - 1)
                }
        )
    }

    /**
     * Create a lookup element for a method.
     */
    private fun createMethodLookupElement(method: MethodInfo, inherited: Boolean = false): LookupElement {
        val signature = method.signature ?: "${method.name}()"
        val returnType = extractReturnType(signature)
        val params = extractParameters(signature)

        var builder = LookupElementBuilder.create(method.name)
            .withIcon(AllIcons.Nodes.Method)
            .withTypeText(returnType, true)
            .withTailText("($params)", true)
            .withInsertHandler { context, _ ->
                val hasParams = params.isNotEmpty()
                context.document.insertString(context.tailOffset, "()")
                if (hasParams) {
                    context.editor.caretModel.moveToOffset(context.tailOffset - 1)
                } else {
                    context.editor.caretModel.moveToOffset(context.tailOffset)
                }
            }

        if (inherited) {
            builder = builder.withTailText("($params) - inherited", true)
        }

        if (method.deprecated) {
            builder = builder.withStrikeoutness(true)
        }

        val settings = HytaleAppSettings.getInstance()
        val basePriority = settings.completionPriority.toDouble()
        return PrioritizedLookupElement.withPriority(builder, if (inherited) basePriority - 10 else basePriority)
    }

    /**
     * Create a lookup element for a field accessor.
     */
    private fun createFieldAccessorLookupElement(field: FieldInfo, inherited: Boolean = false): LookupElement {
        val accessor = field.accessor ?: return LookupElementBuilder.create(field.name)
        val methodName = accessor.substringBefore("(")

        var builder = LookupElementBuilder.create(methodName)
            .withIcon(AllIcons.Nodes.Method)
            .withTypeText(field.type, true)
            .withTailText("() - field accessor", true)
            .withInsertHandler { context, _ ->
                context.document.insertString(context.tailOffset, "()")
                context.editor.caretModel.moveToOffset(context.tailOffset)
            }

        if (inherited) {
            builder = builder.withTailText("() - inherited accessor", true)
        }

        val settings = HytaleAppSettings.getInstance()
        val basePriority = settings.completionPriority.toDouble()
        return PrioritizedLookupElement.withPriority(builder, if (inherited) basePriority - 15 else basePriority - 5)
    }

    /**
     * Extract the return type from a method signature.
     */
    private fun extractReturnType(signature: String): String {
        // Signature format: "public ReturnType methodName(params)"
        val parts = signature.trim()
            .removePrefix("public ")
            .removePrefix("protected ")
            .removePrefix("private ")
            .split(" ")

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
