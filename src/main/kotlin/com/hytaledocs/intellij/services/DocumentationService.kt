package com.hytaledocs.intellij.services

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger

/**
 * Service for managing Hytale documentation URLs and navigation.
 * Provides quick access to documentation pages and contextual help mappings.
 */
@Service(Service.Level.APP)
class DocumentationService {

    companion object {
        private val LOG = Logger.getInstance(DocumentationService::class.java)

        const val BASE_URL = "https://hytale-docs.com"
        const val DOCS_BASE = "$BASE_URL/docs"

        fun getInstance(): DocumentationService {
            return ApplicationManager.getApplication().getService(DocumentationService::class.java)
        }
    }

    /**
     * Documentation sections with their URLs and descriptions.
     */
    enum class DocSection(
        val path: String,
        val title: String,
        val description: String,
        val icon: String = "📄"
    ) {
        // Getting Started
        INTRO("getting-started/overview", "Getting Started", "Introduction to Hytale modding", "🚀"),
        QUICK_START("getting-started/quick-start", "Quick Start", "Create your first mod in minutes", "⚡"),
        PROJECT_SETUP("getting-started/project-setup", "Project Setup", "Setting up your development environment", "🛠️"),

        // Modding
        MODDING_OVERVIEW("modding/overview", "Modding Overview", "Introduction to Hytale modding system", "🔧"),
        PLUGINS("modding/plugins", "Plugins", "Creating server plugins", "🔌"),
        EVENTS("modding/events", "Events", "Event system and listeners", "📡"),
        COMMANDS("modding/commands", "Commands", "Creating custom commands", "💬"),
        UI_SYSTEM("modding/ui", "UI System", "Custom UI with .ui files", "🖼️"),

        // Servers
        SERVERS_OVERVIEW("servers/overview", "Server Overview", "Running a Hytale server", "🖥️"),
        SERVER_SETUP("servers/setup", "Server Setup", "Installing and configuring the server", "📦"),
        SERVER_CONFIG("servers/configuration", "Configuration", "Server configuration files", "⚙️"),
        AUTHENTICATION("servers/authentication", "Authentication", "Server authentication and OAuth", "🔐"),
        PERMISSIONS("servers/permissions", "Permissions", "Permission system and groups", "👥"),

        // API Reference
        API_OVERVIEW("api/overview", "API Overview", "Server API documentation", "📚"),
        API_PLUGINS("api/plugins", "Plugin API", "JavaPlugin and plugin lifecycle", "🔌"),
        API_EVENTS("api/events", "Events API", "Event classes and handlers", "📡"),
        API_COMMANDS("api/commands", "Commands API", "Command system classes", "💬"),
        API_ECS("api/ecs", "ECS System", "Entity Component System", "🧩"),
        API_PACKETS("api/packets", "Packets", "Network packet system", "📨");

        val url: String get() = "$DOCS_BASE/en/$path"
    }

    /**
     * Quick links for the documentation panel.
     */
    data class QuickLink(
        val title: String,
        val url: String,
        val description: String,
        val category: String,
        val icon: String = "📄"
    )

    /**
     * Get all quick links organized by category.
     */
    fun getQuickLinks(): Map<String, List<QuickLink>> {
        return DocSection.entries.map { section ->
            QuickLink(
                title = section.title,
                url = section.url,
                description = section.description,
                category = getCategoryFromPath(section.path),
                icon = section.icon
            )
        }.groupBy { it.category }
    }

    /**
     * Get quick links as a flat list.
     */
    fun getQuickLinksList(): List<QuickLink> {
        return DocSection.entries.map { section ->
            QuickLink(
                title = section.title,
                url = section.url,
                description = section.description,
                category = getCategoryFromPath(section.path),
                icon = section.icon
            )
        }
    }

    private fun getCategoryFromPath(path: String): String {
        return when {
            path.startsWith("getting-started") -> "Getting Started"
            path.startsWith("modding") -> "Modding"
            path.startsWith("servers") -> "Servers"
            path.startsWith("api") -> "API Reference"
            else -> "Other"
        }
    }

    /**
     * Mapping of Hytale class names to documentation URLs for F1 help.
     */
    private val classDocMapping = mapOf(
        // Plugin System
        "JavaPlugin" to "${DOCS_BASE}/en/api/plugins#javaplugin",
        "JavaPluginInit" to "${DOCS_BASE}/en/api/plugins#javaplugininit",

        // Events
        "EventRegistry" to "${DOCS_BASE}/en/api/events#eventregistry",
        "PlayerConnectEvent" to "${DOCS_BASE}/en/api/events#playerconnectevent",
        "PlayerDisconnectEvent" to "${DOCS_BASE}/en/api/events#playerdisconnectevent",
        "PlayerChatEvent" to "${DOCS_BASE}/en/api/events#playerchatevent",
        "PlayerDeathEvent" to "${DOCS_BASE}/en/api/events#playerdeathevent",
        "BlockBreakEvent" to "${DOCS_BASE}/en/api/events#blockbreakevent",
        "BlockPlaceEvent" to "${DOCS_BASE}/en/api/events#blockplaceevent",

        // Commands
        "CommandBase" to "${DOCS_BASE}/en/api/commands#commandbase",
        "AbstractCommandCollection" to "${DOCS_BASE}/en/api/commands#abstractcommandcollection",
        "AbstractPlayerCommand" to "${DOCS_BASE}/en/api/commands#abstractplayercommand",
        "CommandContext" to "${DOCS_BASE}/en/api/commands#commandcontext",
        "CommandRegistry" to "${DOCS_BASE}/en/api/commands#commandregistry",

        // UI System
        "InteractiveCustomUIPage" to "${DOCS_BASE}/en/api/ui#interactivecustomuipage",
        "UICommandBuilder" to "${DOCS_BASE}/en/api/ui#uicommandbuilder",
        "UIEventBuilder" to "${DOCS_BASE}/en/api/ui#uieventbuilder",
        "CustomPageLifetime" to "${DOCS_BASE}/en/api/ui#custompagelifetime",
        "NotificationUtil" to "${DOCS_BASE}/en/api/ui#notificationutil",

        // ECS
        "Player" to "${DOCS_BASE}/en/api/ecs#player",
        "PlayerRef" to "${DOCS_BASE}/en/api/ecs#playerref",
        "World" to "${DOCS_BASE}/en/api/ecs#world",
        "EntityStore" to "${DOCS_BASE}/en/api/ecs#entitystore",
        "Store" to "${DOCS_BASE}/en/api/ecs#store",
        "Ref" to "${DOCS_BASE}/en/api/ecs#ref",

        // Codec
        "Codec" to "${DOCS_BASE}/en/api/codec#codec",
        "BuilderCodec" to "${DOCS_BASE}/en/api/codec#buildercodec",
        "KeyedCodec" to "${DOCS_BASE}/en/api/codec#keyedcodec",

        // Logging
        "HytaleLogger" to "${DOCS_BASE}/en/api/logging#hytalelogger",

        // Message
        "Message" to "${DOCS_BASE}/en/api/messages#message"
    )

    /**
     * Get documentation URL for a class name.
     * Returns null if no mapping exists.
     */
    fun getDocUrlForClass(className: String): String? {
        // Try exact match first
        classDocMapping[className]?.let { return it }

        // Try simple class name (without package)
        val simpleName = className.substringAfterLast('.')
        classDocMapping[simpleName]?.let { return it }

        // Try partial match
        return classDocMapping.entries
            .firstOrNull { (key, _) -> simpleName.contains(key) || key.contains(simpleName) }
            ?.value
    }

    /**
     * Get documentation URL for a fully qualified class name.
     */
    fun getDocUrlForFqn(fqn: String): String? {
        // Check if it's a Hytale class
        if (!fqn.startsWith("com.hypixel.hytale")) {
            return null
        }

        val simpleName = fqn.substringAfterLast('.')
        return getDocUrlForClass(simpleName)
    }

    /**
     * Open documentation URL in the default browser.
     */
    fun openInBrowser(url: String) {
        LOG.info("Opening documentation: $url")
        BrowserUtil.browse(url)
    }

    /**
     * Open documentation for a specific section.
     */
    fun openSection(section: DocSection) {
        openInBrowser(section.url)
    }

    /**
     * Open documentation for a class name.
     * Falls back to API overview if no specific doc found.
     */
    fun openDocForClass(className: String) {
        val url = getDocUrlForClass(className) ?: DocSection.API_OVERVIEW.url
        openInBrowser(url)
    }

    /**
     * Search documentation (opens search page with query).
     */
    fun searchDocs(query: String) {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        openInBrowser("$BASE_URL/search?q=$encodedQuery")
    }

    /**
     * Get the main documentation URL.
     */
    fun getMainUrl(): String = BASE_URL

    /**
     * Get URL for a specific locale.
     */
    fun getLocalizedUrl(section: DocSection, locale: String = "en"): String {
        return "$DOCS_BASE/$locale/${section.path}"
    }
}
