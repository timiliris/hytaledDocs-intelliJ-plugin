package com.hytaledocs.intellij.documentation

import com.hytaledocs.intellij.completion.data.EventInfo
import com.hytaledocs.intellij.completion.data.FieldInfo
import com.hytaledocs.intellij.completion.data.LifecycleMethodInfo
import com.hytaledocs.intellij.completion.data.MethodInfo
import com.hytaledocs.intellij.services.DocumentationService
import com.hytaledocs.intellij.services.ServerDataService
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.ExternalDocumentationHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

/**
 * Documentation provider for Hytale API classes.
 * Provides inline documentation and external documentation links (F1).
 * Enhanced with data from ServerDataService for rich API documentation.
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

        return ReadAction.compute<Boolean, Throwable> {
            val psiClass = when (element) {
                is PsiClass -> element
                is PsiMethod -> element.containingClass
                else -> PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
            }

            val qualifiedName = psiClass?.qualifiedName ?: return@compute false
            qualifiedName.startsWith(HYTALE_PACKAGE_PREFIX)
        }
    }

    /**
     * Get the class name for documentation lookup.
     */
    private fun getClassName(element: PsiElement?): String? {
        if (element == null) return null

        return ReadAction.compute<String?, Throwable> {
            val psiClass = when (element) {
                is PsiClass -> element
                is PsiMethod -> element.containingClass
                else -> PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
            }

            psiClass?.name
        }
    }

    /**
     * Get the fully qualified name for documentation lookup.
     */
    private fun getQualifiedName(element: PsiElement?): String? {
        if (element == null) return null

        return ReadAction.compute<String?, Throwable> {
            val psiClass = when (element) {
                is PsiClass -> element
                is PsiMethod -> element.containingClass
                else -> PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
            }

            psiClass?.qualifiedName
        }
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
        val serverDataService = ServerDataService.getInstance()
        val docUrl = docService.getDocUrlForClass(className)

        val sb = StringBuilder()
        sb.append("<html><body>")
        sb.append("<h2>$className</h2>")
        sb.append("<p><code>$qualifiedName</code></p>")
        sb.append("<hr/>")

        // Try to get rich documentation from ServerDataService
        val eventInfo = serverDataService.getEventByClassName(className)
            ?: serverDataService.getEventByFullName(qualifiedName)

        if (eventInfo != null) {
            appendEventDocumentation(sb, eventInfo)
        } else {
            // Check if it's a lifecycle method
            val lifecycleMethod = serverDataService.getLifecycleMethod(className)
            if (lifecycleMethod != null) {
                appendLifecycleMethodDocumentation(sb, lifecycleMethod)
            } else {
                // Fallback to basic documentation
                appendBasicDocumentation(sb, docUrl)
            }
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    /**
     * Append rich documentation for an event class.
     */
    private fun appendEventDocumentation(sb: StringBuilder, event: EventInfo) {
        // Description and attributes
        event.description?.let {
            sb.append("<p>$it</p>")
        }

        // Event attributes
        val attributes = mutableListOf<String>()
        if (event.cancellable) attributes.add("<b>Cancellable</b>")
        if (event.isAsync) attributes.add("<b>Async</b>")
        if (event.isAbstract) attributes.add("<i>Abstract</i>")

        if (attributes.isNotEmpty()) {
            sb.append("<p>${attributes.joinToString(" | ")}</p>")
        }

        // Parent class
        event.parent?.let {
            sb.append("<p>Extends: <code>$it</code></p>")
        }

        // Deprecation warning
        if (event.annotations.any { it.contains("Deprecated") }) {
            sb.append("<p><b style=\"color: #FFA500;\">⚠ Deprecated</b></p>")
        }

        sb.append("<hr/>")

        // Fields
        if (event.fields.isNotEmpty()) {
            sb.append("<h3>Fields</h3>")
            sb.append("<table>")
            for (field in event.fields) {
                appendFieldRow(sb, field)
            }
            sb.append("</table>")
        }

        // Methods
        if (event.methods.isNotEmpty()) {
            sb.append("<h3>Methods</h3>")
            sb.append("<table>")
            for (method in event.methods) {
                appendMethodRow(sb, method)
            }
            sb.append("</table>")
        }

        // Inner classes
        if (event.innerClasses.isNotEmpty()) {
            sb.append("<h3>Inner Classes</h3>")
            sb.append("<ul>")
            for (inner in event.innerClasses) {
                sb.append("<li><code>${inner.name}</code>")
                if (inner.cancellable) sb.append(" (cancellable)")
                sb.append("</li>")
            }
            sb.append("</ul>")
        }

        // Source reference
        event.sourceFile?.let { file ->
            event.lineNumber?.let { line ->
                sb.append("<p><small>Source: $file:$line</small></p>")
            }
        }
    }

    /**
     * Append documentation for a lifecycle method.
     */
    private fun appendLifecycleMethodDocumentation(sb: StringBuilder, method: LifecycleMethodInfo) {
        sb.append("<p><b>Plugin Lifecycle Method</b></p>")

        method.description?.let {
            sb.append("<p>$it</p>")
        }

        sb.append("<p>Signature: <code>${method.signature}</code></p>")

        method.codeSnippet?.let {
            sb.append("<pre>$it</pre>")
        }
    }

    /**
     * Append basic documentation when no rich data is available.
     */
    private fun appendBasicDocumentation(sb: StringBuilder, docUrl: String?) {
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
    }

    /**
     * Append a table row for a field.
     */
    private fun appendFieldRow(sb: StringBuilder, field: FieldInfo) {
        sb.append("<tr>")
        sb.append("<td><code>${field.name}</code></td>")
        sb.append("<td><code>${field.type}</code></td>")
        field.accessor?.let {
            sb.append("<td>→ <code>$it</code></td>")
        }
        sb.append("</tr>")
    }

    /**
     * Append a table row for a method.
     */
    private fun appendMethodRow(sb: StringBuilder, method: MethodInfo) {
        sb.append("<tr>")
        val style = if (method.deprecated) "text-decoration: line-through;" else ""
        sb.append("<td style=\"$style\"><code>${method.name}</code></td>")
        method.signature?.let {
            val returnType = it.split(" ").firstOrNull { part -> !part.startsWith("public") && !part.startsWith("protected") }
            sb.append("<td><code>$returnType</code></td>")
        }
        sb.append("</tr>")
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
        return ReadAction.compute<PsiElement?, Throwable> {
            // Find the class or method at the cursor position
            val element = file.findElementAt(targetOffset) ?: return@compute null

            // Check if we're on a Hytale class reference
            val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
            if (psiClass != null && isHytaleElement(psiClass)) {
                return@compute psiClass
            }

            null
        }
    }
}
