package com.hytaledocs.intellij.completion.util

import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * Utility class for checking if a PSI element is in a Hytale-related context.
 * Used to restrict completion suggestions to relevant code locations.
 */
object HytaleContextChecker {

    private const val HYTALE_PACKAGE_PREFIX = "com.hypixel.hytale"
    private const val JAVA_PLUGIN_CLASS = "com.hypixel.hytale.server.core.plugin.JavaPlugin"
    private const val PLUGIN_BASE_CLASS = "com.hypixel.hytale.server.core.plugin.PluginBase"

    private val PLUGIN_CLASS_NAMES = setOf(
        "JavaPlugin",
        "PluginBase"
    )

    private val HYTALE_IMPORT_PATTERNS = listOf(
        "com.hypixel.hytale",
        "com.hypixel.hytale.server",
        "com.hypixel.hytale.api"
    )

    /**
     * Check if the file contains any Hytale-related imports.
     */
    fun hasHytaleImports(element: PsiElement): Boolean {
        val file = element.containingFile as? PsiJavaFile ?: return false
        val importList = file.importList ?: return false

        return importList.importStatements.any { importStmt ->
            val qualifiedName = importStmt.qualifiedName ?: return@any false
            HYTALE_IMPORT_PATTERNS.any { pattern -> qualifiedName.startsWith(pattern) }
        } || importList.importStatements.any { importStmt ->
            importStmt.isOnDemand && HYTALE_IMPORT_PATTERNS.any { pattern ->
                importStmt.qualifiedName?.startsWith(pattern) == true
            }
        }
    }

    /**
     * Check if the element is inside a class that extends JavaPlugin or PluginBase.
     */
    fun isInsidePluginClass(element: PsiElement): Boolean {
        val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return false
        return isPluginClass(containingClass)
    }

    /**
     * Check if a class extends JavaPlugin or PluginBase.
     */
    fun isPluginClass(psiClass: PsiClass): Boolean {
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

        return false
    }

    /**
     * Check if the qualifier expression before a "." is a Hytale event type.
     */
    fun isHytaleEventQualifier(element: PsiElement): Boolean {
        // Find the reference expression containing this element
        val refExpr = PsiTreeUtil.getParentOfType(element, PsiReferenceExpression::class.java)
        val methodCall = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java)

        val qualifierType = when {
            methodCall != null -> methodCall.methodExpression.qualifierExpression?.type?.canonicalText
            refExpr != null -> refExpr.qualifierExpression?.type?.canonicalText
            else -> null
        }

        if (qualifierType == null) return false

        // Check if it's a Hytale type
        return qualifierType.startsWith(HYTALE_PACKAGE_PREFIX) ||
               qualifierType.endsWith("Event") // Also allow types ending with Event for flexibility
    }

    /**
     * Check if the element is in a Hytale-relevant context.
     * This includes:
     * - Files with Hytale imports
     * - Classes extending Hytale plugin classes
     * - Code referencing Hytale types
     */
    fun isInHytaleContext(element: PsiElement): Boolean {
        // Quick check: does the file have Hytale imports?
        if (hasHytaleImports(element)) {
            return true
        }

        // Check if we're in a plugin class
        if (isInsidePluginClass(element)) {
            return true
        }

        // Check package name of the containing file
        val file = element.containingFile as? PsiJavaFile
        if (file != null) {
            val packageName = file.packageName
            if (packageName.startsWith(HYTALE_PACKAGE_PREFIX)) {
                return true
            }
        }

        return false
    }

    /**
     * Check if the element is at a position where we're typing after a "." on a Hytale event.
     */
    fun isAfterDotOnHytaleEvent(element: PsiElement): Boolean {
        // First verify we're in a Hytale context
        if (!isInHytaleContext(element)) {
            return false
        }

        // Then check if the qualifier is a Hytale event type
        return isHytaleEventQualifier(element)
    }
}
