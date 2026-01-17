package com.hytaledocs.intellij.documentation

import com.hytaledocs.intellij.services.DocumentationService
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.ExternalDocumentationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

/**
 * Documentation provider for Hytale API classes.
 * Provides inline documentation and external documentation links (F1).
 */
class HytaleDocumentationProvider : AbstractDocumentationProvider(), ExternalDocumentationHandler {

    companion object {
        private const val HYTALE_PACKAGE_PREFIX = "com.hypixel.hytale"
    }

    /**
     * Check if the element is a Hytale API element.
     */
    private fun isHytaleElement(element: PsiElement?): Boolean {
        if (element == null) return false

        val psiClass = when (element) {
            is PsiClass -> element
            is PsiMethod -> element.containingClass
            else -> PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        }

        val qualifiedName = psiClass?.qualifiedName ?: return false
        return qualifiedName.startsWith(HYTALE_PACKAGE_PREFIX)
    }

    /**
     * Get the class name for documentation lookup.
     */
    private fun getClassName(element: PsiElement?): String? {
        if (element == null) return null

        val psiClass = when (element) {
            is PsiClass -> element
            is PsiMethod -> element.containingClass
            else -> PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        }

        return psiClass?.name
    }

    /**
     * Get the fully qualified name for documentation lookup.
     */
    private fun getQualifiedName(element: PsiElement?): String? {
        if (element == null) return null

        val psiClass = when (element) {
            is PsiClass -> element
            is PsiMethod -> element.containingClass
            else -> PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        }

        return psiClass?.qualifiedName
    }

    // ==================== DocumentationProvider ====================

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        if (!isHytaleElement(element)) return null

        val className = getClassName(element) ?: return null
        val docService = DocumentationService.getInstance()
        val docUrl = docService.getDocUrlForClass(className)

        return if (docUrl != null) {
            "Hytale API: $className - Press F1 for documentation"
        } else {
            "Hytale API: $className"
        }
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (!isHytaleElement(element)) return null

        val className = getClassName(element) ?: return null
        val qualifiedName = getQualifiedName(element) ?: return null
        val docService = DocumentationService.getInstance()
        val docUrl = docService.getDocUrlForClass(className)

        val sb = StringBuilder()
        sb.append("<html><body>")
        sb.append("<h2>$className</h2>")
        sb.append("<p><code>$qualifiedName</code></p>")
        sb.append("<hr/>")

        if (docUrl != null) {
            sb.append("<p><b>Hytale API Documentation</b></p>")
            sb.append("<p>View full documentation at:</p>")
            sb.append("<p><a href=\"$docUrl\">$docUrl</a></p>")
            sb.append("<br/>")
            sb.append("<p><i>Press F1 or click the link to open in browser.</i></p>")
        } else {
            sb.append("<p>This is a Hytale API class.</p>")
            sb.append("<p>Documentation may be available at:</p>")
            sb.append("<p><a href=\"${DocumentationService.BASE_URL}\">${DocumentationService.BASE_URL}</a></p>")
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    override fun getDocumentationElementForLookupItem(
        psiManager: com.intellij.psi.PsiManager?,
        `object`: Any?,
        element: PsiElement?
    ): PsiElement? {
        return null
    }

    override fun getDocumentationElementForLink(
        psiManager: com.intellij.psi.PsiManager?,
        link: String?,
        context: PsiElement?
    ): PsiElement? {
        return null
    }

    // ==================== ExternalDocumentationHandler ====================

    override fun handleExternal(element: PsiElement?, originalElement: PsiElement?): Boolean {
        if (!isHytaleElement(element)) return false

        val className = getClassName(element) ?: return false
        val docService = DocumentationService.getInstance()

        docService.openDocForClass(className)
        return true
    }

    override fun handleExternalLink(
        psiManager: com.intellij.psi.PsiManager?,
        link: String?,
        context: PsiElement?
    ): Boolean {
        if (link == null) return false

        // Check if it's a Hytale docs link
        if (link.startsWith(DocumentationService.BASE_URL)) {
            DocumentationService.getInstance().openInBrowser(link)
            return true
        }

        return false
    }

    override fun canFetchDocumentationLink(link: String?): Boolean {
        return link?.startsWith(DocumentationService.BASE_URL) == true
    }

    override fun fetchExternalDocumentation(link: String, element: PsiElement?): String {
        // We don't fetch and render external docs inline, just return empty
        return ""
    }

    // ==================== Custom Element Location ====================

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int
    ): PsiElement? {
        // Find the class or method at the cursor position
        val element = file.findElementAt(targetOffset) ?: return null

        // Check if we're on a Hytale class reference
        val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        if (psiClass != null && isHytaleElement(psiClass)) {
            return psiClass
        }

        return null
    }
}
