package com.hytaledocs.intellij.completion

import com.hytaledocs.intellij.completion.providers.EventClassCompletionProvider
import com.hytaledocs.intellij.completion.providers.EventMethodCompletionProvider
import com.hytaledocs.intellij.completion.providers.PluginMethodCompletionProvider
import com.hytaledocs.intellij.completion.util.HytaleContextChecker
import com.intellij.codeInsight.completion.*
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiJavaPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext

/**
 * Completion contributor for Hytale API.
 * Provides intelligent code completion for events, plugin lifecycle methods, and commands.
 *
 * Patterns are restricted to Hytale-specific contexts to avoid interfering with
 * normal Java completion in non-Hytale code.
 */
class HytaleCompletionContributor : CompletionContributor() {

    init {
        // Pattern for event classes in generic type parameters
        // e.g., Consumer<PlayerConnect|>
        // Only triggered in files with Hytale imports or in Hytale package
        extend(
            CompletionType.BASIC,
            PsiJavaPatterns.psiElement()
                .inside(PsiTypeElement::class.java)
                .with(hytaleContextCondition()),
            EventClassCompletionProvider()
        )

        // Pattern for event classes in class references
        // e.g., PlayerConnect|Event.class
        // Only triggered in files with Hytale imports or in Hytale package
        extend(
            CompletionType.BASIC,
            PsiJavaPatterns.psiElement()
                .inside(PsiJavaCodeReferenceElement::class.java)
                .with(hytaleContextCondition()),
            EventClassCompletionProvider()
        )

        // Pattern for method calls on event objects
        // e.g., event.getPlayer|()
        // Only triggered when the qualifier is a Hytale event type
        extend(
            CompletionType.BASIC,
            PsiJavaPatterns.psiElement()
                .afterLeaf(".")
                .inside(PsiMethodCallExpression::class.java)
                .with(hytaleEventQualifierCondition()),
            EventMethodCompletionProvider()
        )

        // Pattern for method references in lambdas/method refs on Hytale events
        // e.g., event.get| where event is a Hytale event type
        // Only triggered when the qualifier is a Hytale event type
        extend(
            CompletionType.BASIC,
            PsiJavaPatterns.psiElement()
                .afterLeaf(".")
                .with(hytaleEventQualifierCondition()),
            EventMethodCompletionProvider()
        )

        // Pattern for override methods in plugin classes
        // e.g., override fun setup|()
        // Only triggered inside classes that extend JavaPlugin or PluginBase
        extend(
            CompletionType.BASIC,
            PsiJavaPatterns.psiElement()
                .inside(PsiClass::class.java)
                .with(pluginClassCondition()),
            PluginMethodCompletionProvider()
        )
    }

    /**
     * Creates a pattern condition that checks if the element is in a Hytale context
     * (file has Hytale imports, or is in a Hytale package).
     */
    private fun hytaleContextCondition() = object : PatternCondition<PsiElement>("hytaleContext") {
        override fun accepts(element: PsiElement, context: ProcessingContext?): Boolean {
            return HytaleContextChecker.isInHytaleContext(element)
        }
    }

    /**
     * Creates a pattern condition that checks if the qualifier before "." is a Hytale event type.
     */
    private fun hytaleEventQualifierCondition() = object : PatternCondition<PsiElement>("hytaleEventQualifier") {
        override fun accepts(element: PsiElement, context: ProcessingContext?): Boolean {
            return HytaleContextChecker.isAfterDotOnHytaleEvent(element)
        }
    }

    /**
     * Creates a pattern condition that checks if the element is inside a Hytale plugin class
     * (a class extending JavaPlugin or PluginBase).
     */
    private fun pluginClassCondition() = object : PatternCondition<PsiElement>("pluginClass") {
        override fun accepts(element: PsiElement, context: ProcessingContext?): Boolean {
            return HytaleContextChecker.isInsidePluginClass(element)
        }
    }

    override fun beforeCompletion(context: CompletionInitializationContext) {
        // Ensure we don't interfere with normal Java completion
        super.beforeCompletion(context)
    }
}
