package com.hytaledocs.intellij.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * API client for fetching documentation from hytale-docs.com.
 * Provides caching and async fetching of sidebar structure and doc content.
 */
@Service(Service.Level.APP)
class DocumentationApiClient {

    companion object {
        private val LOG = Logger.getInstance(DocumentationApiClient::class.java)

        const val BASE_URL = "https://hytale-docs.com"
        const val API_BASE = "$BASE_URL/api/docs"

        // Cache TTL in milliseconds (5 minutes)
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        fun getInstance(): DocumentationApiClient {
            return ApplicationManager.getApplication().getService(DocumentationApiClient::class.java)
        }
    }

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    private val gson = Gson()

    // Cache for sidebar and docs
    private val sidebarCache = ConcurrentHashMap<String, CachedData<SidebarResponse>>()
    private val docCache = ConcurrentHashMap<String, CachedData<DocResponse>>()

    data class CachedData<T>(
        val data: T,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > CACHE_TTL_MS
    }

    // ==================== Data Classes ====================

    data class SidebarItem(
        val title: String,
        val href: String?,
        val verified: Boolean = false,
        val items: List<SidebarItem>? = null
    )

    data class SidebarResponse(
        val sidebar: List<SidebarItem>,
        val locale: String
    )

    data class DocMeta(
        val title: String,
        val description: String? = null
    )

    data class DocNavigation(
        val prev: DocNavLink? = null,
        val next: DocNavLink? = null
    )

    data class DocNavLink(
        val title: String,
        val href: String
    )

    data class DocResponse(
        val slug: String,
        val meta: DocMeta,
        val content: String, // HTML content
        val markdown: String? = null, // Raw markdown
        val navigation: DocNavigation? = null,
        val locale: String
    )

    // ==================== API Methods ====================

    /**
     * Fetch the sidebar navigation structure.
     */
    fun fetchSidebar(locale: String = "en"): CompletableFuture<SidebarResponse?> {
        // Check cache first
        val cacheKey = "sidebar_$locale"
        sidebarCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                return CompletableFuture.completedFuture(cached.data)
            }
        }

        return CompletableFuture.supplyAsync {
            try {
                val url = "$API_BASE?locale=$locale"
                LOG.info("Fetching sidebar from: $url")

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() == 200) {
                    val sidebarResponse = gson.fromJson(response.body(), SidebarResponse::class.java)
                    sidebarCache[cacheKey] = CachedData(sidebarResponse)
                    sidebarResponse
                } else {
                    LOG.warn("Failed to fetch sidebar: HTTP ${response.statusCode()}")
                    null
                }
            } catch (e: Exception) {
                LOG.warn("Error fetching sidebar: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Fetch documentation content for a specific path.
     * @param path The doc path (e.g., "modding/plugins/overview")
     */
    fun fetchDoc(path: String, locale: String = "en"): CompletableFuture<DocResponse?> {
        // Normalize path
        val normalizedPath = path
            .removePrefix("/docs/")
            .removePrefix("docs/")
            .trim('/')

        // Check cache first
        val cacheKey = "${normalizedPath}_$locale"
        docCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                return CompletableFuture.completedFuture(cached.data)
            }
        }

        return CompletableFuture.supplyAsync {
            try {
                val url = "$API_BASE/content/$normalizedPath?locale=$locale"
                LOG.info("Fetching doc from: $url")

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() == 200) {
                    val docResponse = gson.fromJson(response.body(), DocResponse::class.java)
                    docCache[cacheKey] = CachedData(docResponse)
                    docResponse
                } else {
                    LOG.warn("Failed to fetch doc '$normalizedPath': HTTP ${response.statusCode()}")
                    null
                }
            } catch (e: Exception) {
                LOG.warn("Error fetching doc '$normalizedPath': ${e.message}", e)
                null
            }
        }
    }

    /**
     * Search documentation.
     * @param query Search query
     */
    fun searchDocs(query: String, locale: String = "en"): CompletableFuture<List<SearchResult>> {
        return CompletableFuture.supplyAsync {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "$BASE_URL/api/search?q=$encodedQuery&locale=$locale"
                LOG.info("Searching docs: $url")

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() == 200) {
                    val type = object : TypeToken<SearchResponse>() {}.type
                    val searchResponse: SearchResponse = gson.fromJson(response.body(), type)
                    searchResponse.results ?: emptyList()
                } else {
                    LOG.warn("Search failed: HTTP ${response.statusCode()}")
                    emptyList()
                }
            } catch (e: Exception) {
                LOG.warn("Error searching docs: ${e.message}", e)
                emptyList()
            }
        }
    }

    data class SearchResult(
        val title: String,
        val href: String,
        val excerpt: String? = null,
        val category: String? = null
    )

    data class SearchResponse(
        val results: List<SearchResult>? = null,
        val query: String? = null
    )

    /**
     * Clear all caches.
     */
    fun clearCache() {
        sidebarCache.clear()
        docCache.clear()
    }

    /**
     * Preload common documentation pages.
     */
    fun preloadCommonDocs() {
        val commonPaths = listOf(
            "getting-started/introduction",
            "modding/plugins/overview",
            "modding/plugins/project-setup",
            "modding/plugins/events/overview",
            "api/server-internals/custom-ui"
        )

        commonPaths.forEach { path ->
            fetchDoc(path)
        }
    }
}
