package com.hytaledocs.intellij.completion

import com.hytaledocs.intellij.completion.providers.EventClassCompletionProvider
import com.hytaledocs.intellij.completion.providers.EventMethodCompletionProvider
import com.hytaledocs.intellij.completion.providers.PluginMethodCompletionProvider
import com.intellij.codeInsight.completion.*
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiJavaPatterns
import com.intellij.psi.*

/**
 * Completion contributor for Hytale API.
 * Provides intelligent code completion for events, plugin lifecycle methods, and commands.
 */
class HytaleCompletionContributor : CompletionContributor() {

    init {
        // Pattern for event classes in generic type parameters
        // e.g., Consumer<PlayerConnect|>
        extend(
            CompletionType.BASIC,
            PsiJavaPatterns.psiElement()
                .inside(PsiTypeElement::class.java),
            EventClassCompletionProvider()
        )

        // Pattern for event classes in class references
        // e.g., PlayerConnect|Event.class
        extend(
            CompletionType.BASIC,
            PsiJavaPatterns.psiElement()
                .inside(PsiJavaCodeReferenceElement::class.java),
            EventClassCompletionProvider()
        )

        // Pattern for method calls on event objects
        // e.g., event.getPlayer|()
        extend(
            CompletionType.BASIC,
            PsiJavaPatterns.psiElement()
                .afterLeaf(".")
                .inside(PsiMethodCallExpression::class.java),
            EventMethodCompletionProvider()
        )

        // Pattern for method references in lambdas/method refs
        // e.g., event.get| or this::handle|
        extend(
            CompletionType.BASIC,
            PsiJavaPatterns.psiElement()
                .afterLeaf("."),
            EventMethodCompletionProvider()
        )

        // Pattern for override methods in plugin classes
        // e.g., override fun setup|()
        extend(
            CompletionType.BASIC,
            PsiJavaPatterns.psiElement()
                .inside(PsiClass::class.java),
            PluginMethodCompletionProvider()
        )
    }

    override fun beforeCompletion(context: CompletionInitializationContext) {
        // Ensure we don't interfere with normal Java completion
        super.beforeCompletion(context)
    }
}
