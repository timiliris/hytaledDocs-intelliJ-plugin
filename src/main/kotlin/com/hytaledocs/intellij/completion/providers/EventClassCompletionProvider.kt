package com.hytaledocs.intellij.completion.providers

import com.hytaledocs.intellij.HytaleIcons
import com.hytaledocs.intellij.completion.data.EventInfo
import com.hytaledocs.intellij.services.ServerDataService
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import javax.swing.Icon

/**
 * Provides completion for Hytale event class names.
 * Suggests event classes in contexts like:
 * - Consumer<PlayerConnect|>
 * - getEventRegistry().register(PlayerConnect|Event.class, ...)
 * - @EventHandler PlayerConnect|Event event
 */
class EventClassCompletionProvider : CompletionProvider<CompletionParameters>() {

    companion object {
        private const val HYTALE_EVENT_PACKAGE = "com.hypixel.hytale"

        // Event-related class names that trigger suggestions
        private val EVENT_CONTEXT_CLASSES = setOf(
            "Consumer",
            "Function",
            "BiConsumer",
            "EventRegistry",
            "IEventRegistry",
            "EventBus",
            "IEventBus"
        )
    }

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val position = parameters.position
        val prefix = result.prefixMatcher.prefix

        // Check if we're in a relevant context for event completion
        if (!isEventCompletionContext(position)) {
            return
        }

        val dataService = ServerDataService.getInstance()
        val events = if (prefix.isNotEmpty()) {
            dataService.getEventsMatchingPrefix(prefix)
        } else {
            dataService.getAllEvents()
        }

        // Add event suggestions
        for (event in events) {
            val lookupElement = createEventLookupElement(event, position)
            result.addElement(lookupElement)
        }

        // Also add core interfaces
        dataService.getCoreInterfaces().forEach { iface ->
            if (prefix.isEmpty() || iface.className.lowercase().startsWith(prefix.lowercase())) {
                result.addElement(createInterfaceLookupElement(iface.className, iface.fullName, iface.description))
            }
        }
    }

    /**
     * Check if the current position is a valid context for event class completion.
     */
    private fun isEventCompletionContext(position: PsiElement): Boolean {
        // Check if we're in a type parameter context (e.g., Consumer<|>)
        val typeElement = PsiTreeUtil.getParentOfType(position, PsiTypeElement::class.java)
        if (typeElement != null) {
            val parent = typeElement.parent
            // Check if inside a generic parameter list
            if (parent is PsiReferenceParameterList) {
                val grandParent = parent.parent
                if (grandParent is PsiJavaCodeReferenceElement) {
                    val className = grandParent.referenceName
                    if (className != null && EVENT_CONTEXT_CLASSES.contains(className)) {
                        return true
                    }
                }
            }
        }

        // Check if we're after ".class" or in a method call context
        val methodCall = PsiTreeUtil.getParentOfType(position, PsiMethodCallExpression::class.java)
        if (methodCall != null) {
            val methodName = methodCall.methodExpression.referenceName
            if (methodName != null && (methodName.startsWith("register") || methodName == "dispatch")) {
                return true
            }
        }

        // Check if we're in a variable declaration with event-like type
        val variable = PsiTreeUtil.getParentOfType(position, PsiVariable::class.java)
        if (variable != null) {
            val typeText = variable.type.presentableText
            if (typeText.contains("Event") || typeText.contains("Consumer")) {
                return true
            }
        }

        // Check if typing after "extends" or "implements" in class context
        val classDecl = PsiTreeUtil.getParentOfType(position, PsiClass::class.java)
        if (classDecl != null) {
            val extendsList = classDecl.extendsList
            val implementsList = classDecl.implementsList
            if (extendsList != null && PsiTreeUtil.isAncestor(extendsList, position, false)) {
                return true
            }
            if (implementsList != null && PsiTreeUtil.isAncestor(implementsList, position, false)) {
                return true
            }
        }

        // Allow completion in any Java code reference that could be an event
        val codeRef = PsiTreeUtil.getParentOfType(position, PsiJavaCodeReferenceElement::class.java)
        if (codeRef != null) {
            val text = position.text
            // If the prefix looks like it could be an event name
            if (text.contains("Event") || text.endsWith("Event") ||
                text.matches(Regex("^[A-Z][a-zA-Z]*$"))) {
                return true
            }
        }

        return false
    }

    /**
     * Create a lookup element for an event class.
     */
    private fun createEventLookupElement(event: EventInfo, position: PsiElement): LookupElement {
        val icon = getEventIcon(event)
        val typeText = buildTypeText(event)

        return LookupElementBuilder.create(event.name)
            .withIcon(icon)
            .withTypeText(typeText, true)
            .withTailText(getTailText(event), true)
            .withInsertHandler { insertContext, _ ->
                handleEventInsert(insertContext, event, position)
            }
            .withPriority(calculatePriority(event))
    }

    /**
     * Create a lookup element for a core interface.
     */
    private fun createInterfaceLookupElement(className: String, fullName: String, description: String?): LookupElement {
        return LookupElementBuilder.create(className)
            .withIcon(HytaleIcons.HYTALE)
            .withTypeText("interface", true)
            .withTailText(if (description != null) " - $description" else "", true)
            .withInsertHandler { insertContext, _ ->
                // Add import if needed
                addImportIfNeeded(insertContext, fullName)
            }
    }

    /**
     * Get the appropriate icon for an event.
     */
    private fun getEventIcon(event: EventInfo): Icon {
        return HytaleIcons.HYTALE
    }

    /**
     * Build the type text shown in completion popup.
     */
    private fun buildTypeText(event: EventInfo): String {
        val parts = mutableListOf<String>()
        if (event.cancellable) parts.add("cancellable")
        if (event.isAsync) parts.add("async")
        if (event.isAbstract) parts.add("abstract")
        return if (parts.isEmpty()) "event" else parts.joinToString(", ")
    }

    /**
     * Get the tail text (description) for an event.
     */
    private fun getTailText(event: EventInfo): String {
        val parent = event.parent
        return if (parent != null) " extends $parent" else ""
    }

    /**
     * Handle insertion of event class, including imports.
     */
    private fun handleEventInsert(context: InsertionContext, event: EventInfo, position: PsiElement) {
        // Add import statement
        addImportIfNeeded(context, event.fullName)
    }

    /**
     * Add import statement if the class is not already imported.
     */
    private fun addImportIfNeeded(context: InsertionContext, fullName: String) {
        val file = context.file
        if (file is PsiJavaFile) {
            val importList = file.importList ?: return
            val packageName = fullName.substringBeforeLast('.')
            val className = fullName.substringAfterLast('.')

            // Check if already imported
            val alreadyImported = importList.importStatements.any { importStmt ->
                importStmt.qualifiedName == fullName
            } || importList.importStatements.any { importStmt ->
                importStmt.qualifiedName == "$packageName.*"
            }

            if (!alreadyImported && packageName.startsWith("com.hypixel.hytale")) {
                val factory = PsiElementFactory.getInstance(context.project)
                val importStatement = factory.createImportStatement(
                    JavaPsiFacade.getInstance(context.project)
                        .findClass(fullName, file.resolveScope) ?: return
                )
                importList.add(importStatement)
            }
        }
    }

    /**
     * Calculate priority for sorting completion results.
     */
    private fun calculatePriority(event: EventInfo): Double {
        var priority = 100.0

        // Boost common events
        if (event.name.startsWith("Player")) priority += 20
        if (event.name.contains("Connect") || event.name.contains("Disconnect")) priority += 10
        if (event.name.contains("Chat")) priority += 10

        // Lower priority for deprecated events
        if (event.annotations.any { it.contains("Deprecated") }) priority -= 50

        // Lower priority for abstract events
        if (event.isAbstract) priority -= 10

        return priority
    }

    private fun LookupElementBuilder.withPriority(priority: Double): LookupElement {
        return PrioritizedLookupElement.withPriority(this, priority)
    }
}
