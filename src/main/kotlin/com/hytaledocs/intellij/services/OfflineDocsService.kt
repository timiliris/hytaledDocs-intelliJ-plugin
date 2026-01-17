package com.hytaledocs.intellij.services

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipInputStream

/**
 * Service for managing offline documentation downloaded from GitHub.
 * Downloads the wiki-next repository as a ZIP and extracts markdown files.
 *
 * This provides:
 * - Offline access to documentation in the Docs tab
 * - Local cache for the MCP server
 *
 * Cache location: ~/.hytale-intellij/docs-cache/
 */
@Service(Service.Level.APP)
class OfflineDocsService {

    companion object {
        private val LOG = Logger.getInstance(OfflineDocsService::class.java)

        // GitHub repository info
        private const val GITHUB_OWNER = "timiliris"
        private const val GITHUB_REPO = "Hytale-Docs"
        private const val GITHUB_BRANCH = "master"

        private const val GITHUB_ARCHIVE_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/archive/refs/heads/$GITHUB_BRANCH.zip"
        private const val GITHUB_API_COMMITS = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/commits/$GITHUB_BRANCH"

        // Path inside the ZIP where docs are located
        private const val DOCS_PATH_IN_ZIP = "content/docs/en/"

        private const val CACHE_VERSION = "1.0.0"

        fun getInstance(): OfflineDocsService {
            return ApplicationManager.getApplication().getService(OfflineDocsService::class.java)
        }

        /**
         * Get the cache directory path
         */
        fun getCacheDir(): File {
            val userHome = System.getProperty("user.home")
            return File(userHome, ".hytale-intellij/docs-cache")
        }
    }

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var isDownloading = false

    // In-memory cache of parsed docs
    private var docsIndex: DocsIndex? = null
    private val docsCache = mutableMapOf<String, ParsedDoc>()

    // ==================== Data Classes ====================

    data class DocsIndex(
        val version: String,
        val lastUpdate: String,
        val commitSha: String?,
        val totalDocs: Int,
        val docs: List<DocEntry>
    )

    data class DocEntry(
        val slug: String,
        val title: String,
        val description: String?,
        val category: String,
        val filePath: String
    )

    data class ParsedDoc(
        val slug: String,
        val title: String,
        val description: String?,
        val content: String,  // Raw markdown
        val category: String
    )

    data class DocFrontmatter(
        val title: String? = null,
        val description: String? = null,
        val order: Int? = null,
        val tags: List<String>? = null
    )

    data class CacheStatus(
        val available: Boolean,
        val totalDocs: Int,
        val lastUpdate: String?,
        val commitSha: String?,
        val cacheDir: String
    )

    data class DownloadProgress(
        val phase: DownloadPhase,
        val progress: Int,  // 0-100
        val message: String
    )

    enum class DownloadPhase {
        CHECKING_UPDATES,
        DOWNLOADING,
        EXTRACTING,
        INDEXING,
        COMPLETE,
        ERROR
    }

    // ==================== Public API ====================

    fun isDownloading(): Boolean = isDownloading

    /**
     * Get current cache status
     */
    fun getCacheStatus(): CacheStatus {
        loadIndexIfNeeded()

        val index = docsIndex
        return CacheStatus(
            available = index != null && index.totalDocs > 0,
            totalDocs = index?.totalDocs ?: 0,
            lastUpdate = index?.lastUpdate,
            commitSha = index?.commitSha,
            cacheDir = getCacheDir().absolutePath
        )
    }

    /**
     * Download/refresh documentation from GitHub
     */
    fun downloadDocs(
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): CompletableFuture<Boolean> {
        if (isDownloading) {
            return CompletableFuture.completedFuture(false)
        }

        isDownloading = true

        return CompletableFuture.supplyAsync {
            try {
                val cacheDir = getCacheDir()
                val docsDir = File(cacheDir, "docs")

                cacheDir.mkdirs()
                docsDir.mkdirs()

                // Phase 1: Check for updates (optional - could compare commit SHA)
                onProgress?.invoke(DownloadProgress(
                    DownloadPhase.CHECKING_UPDATES,
                    0,
                    "Checking for updates..."
                ))

                val latestCommit = fetchLatestCommitSha()

                // Phase 2: Download ZIP
                onProgress?.invoke(DownloadProgress(
                    DownloadPhase.DOWNLOADING,
                    10,
                    "Downloading documentation archive..."
                ))

                val zipData = downloadArchive { percent ->
                    onProgress?.invoke(DownloadProgress(
                        DownloadPhase.DOWNLOADING,
                        10 + (percent * 0.5).toInt(),
                        "Downloading... $percent%"
                    ))
                }

                if (zipData == null) {
                    onProgress?.invoke(DownloadProgress(
                        DownloadPhase.ERROR,
                        0,
                        "Failed to download archive"
                    ))
                    return@supplyAsync false
                }

                // Phase 3: Extract markdown files
                onProgress?.invoke(DownloadProgress(
                    DownloadPhase.EXTRACTING,
                    60,
                    "Extracting documentation files..."
                ))

                // Clear old docs
                docsDir.deleteRecursively()
                docsDir.mkdirs()

                val extractedFiles = extractDocsFromZip(zipData, docsDir) { current, total ->
                    val percent = if (total > 0) (current * 100) / total else 0
                    onProgress?.invoke(DownloadProgress(
                        DownloadPhase.EXTRACTING,
                        60 + (percent * 0.2).toInt(),
                        "Extracting... $current files"
                    ))
                }

                // Phase 4: Build index
                onProgress?.invoke(DownloadProgress(
                    DownloadPhase.INDEXING,
                    80,
                    "Building search index..."
                ))

                val docEntries = mutableListOf<DocEntry>()
                docsCache.clear()

                for (file in extractedFiles) {
                    val parsed = parseMarkdownFile(file, docsDir)
                    if (parsed != null) {
                        docsCache[parsed.slug] = parsed
                        docEntries.add(DocEntry(
                            slug = parsed.slug,
                            title = parsed.title,
                            description = parsed.description,
                            category = parsed.category,
                            filePath = file.relativeTo(docsDir).path
                        ))
                    }
                }

                // Save index
                docsIndex = DocsIndex(
                    version = CACHE_VERSION,
                    lastUpdate = Instant.now().toString(),
                    commitSha = latestCommit,
                    totalDocs = docEntries.size,
                    docs = docEntries.sortedBy { it.slug }
                )

                val indexFile = File(cacheDir, "index.json")
                indexFile.writeText(gson.toJson(docsIndex))

                LOG.info("Downloaded ${docEntries.size} documentation files")

                onProgress?.invoke(DownloadProgress(
                    DownloadPhase.COMPLETE,
                    100,
                    "Downloaded ${docEntries.size} documents"
                ))

                true
            } catch (e: Exception) {
                LOG.error("Failed to download docs", e)
                onProgress?.invoke(DownloadProgress(
                    DownloadPhase.ERROR,
                    0,
                    "Error: ${e.message}"
                ))
                false
            } finally {
                isDownloading = false
            }
        }
    }

    /**
     * Get a document by slug
     */
    fun getDoc(slug: String): ParsedDoc? {
        loadIndexIfNeeded()

        // Check in-memory cache first
        docsCache[slug]?.let { return it }

        // Try to load from file
        val cacheDir = getCacheDir()
        val docsDir = File(cacheDir, "docs")

        val entry = docsIndex?.docs?.find { it.slug == slug } ?: return null
        val file = File(docsDir, entry.filePath)

        if (!file.exists()) return null

        val parsed = parseMarkdownFile(file, docsDir)
        if (parsed != null) {
            docsCache[slug] = parsed
        }
        return parsed
    }

    /**
     * List all documents, optionally filtered by category
     */
    fun listDocs(category: String? = null): List<DocEntry> {
        loadIndexIfNeeded()

        val docs = docsIndex?.docs ?: return emptyList()

        return if (category != null) {
            docs.filter { it.category.equals(category, ignoreCase = true) }
        } else {
            docs
        }
    }

    /**
     * Get all categories
     */
    fun getCategories(): List<String> {
        loadIndexIfNeeded()
        return docsIndex?.docs?.map { it.category }?.distinct()?.sorted() ?: emptyList()
    }

    /**
     * Simple search in documents
     */
    fun searchDocs(query: String, limit: Int = 20): List<DocEntry> {
        loadIndexIfNeeded()

        val queryLower = query.lowercase()
        val words = queryLower.split(Regex("\\s+"))

        return docsIndex?.docs
            ?.map { entry ->
                var score = 0

                // Title match (highest weight)
                if (entry.title.lowercase().contains(queryLower)) {
                    score += 100
                }
                words.forEach { word ->
                    if (entry.title.lowercase().contains(word)) score += 20
                }

                // Slug match
                if (entry.slug.lowercase().contains(queryLower)) {
                    score += 50
                }

                // Description match
                entry.description?.let { desc ->
                    if (desc.lowercase().contains(queryLower)) score += 30
                    words.forEach { word ->
                        if (desc.lowercase().contains(word)) score += 10
                    }
                }

                entry to score
            }
            ?.filter { it.second > 0 }
            ?.sortedByDescending { it.second }
            ?.take(limit)
            ?.map { it.first }
            ?: emptyList()
    }

    /**
     * Clear the cache
     */
    fun clearCache() {
        try {
            getCacheDir().deleteRecursively()
            docsIndex = null
            docsCache.clear()
            LOG.info("Cache cleared")
        } catch (e: Exception) {
            LOG.error("Failed to clear cache", e)
        }
    }

    // ==================== Private Methods ====================

    private fun loadIndexIfNeeded() {
        if (docsIndex != null) return

        val indexFile = File(getCacheDir(), "index.json")
        if (indexFile.exists()) {
            try {
                docsIndex = gson.fromJson(indexFile.readText(), DocsIndex::class.java)
            } catch (e: Exception) {
                LOG.warn("Failed to load index", e)
            }
        }
    }

    private fun fetchLatestCommitSha(): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API_COMMITS))
                .GET()
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "HytaleIntelliJPlugin")
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                // Parse SHA from response
                val json = gson.fromJson(response.body(), Map::class.java)
                json["sha"] as? String
            } else {
                LOG.warn("Failed to fetch commit SHA: ${response.statusCode()}")
                null
            }
        } catch (e: Exception) {
            LOG.warn("Error fetching commit SHA", e)
            null
        }
    }

    private fun downloadArchive(onProgress: (Int) -> Unit): ByteArray? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_ARCHIVE_URL))
                .GET()
                .header("User-Agent", "HytaleIntelliJPlugin")
                .timeout(Duration.ofMinutes(5))
                .build()

            // For progress, we'd need content-length, but GitHub redirects...
            // For now, just download
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())

            if (response.statusCode() == 200) {
                onProgress(100)
                response.body()
            } else {
                LOG.warn("Failed to download archive: ${response.statusCode()}")
                null
            }
        } catch (e: Exception) {
            LOG.error("Error downloading archive", e)
            null
        }
    }

    private fun extractDocsFromZip(
        zipData: ByteArray,
        outputDir: File,
        onProgress: (Int, Int) -> Unit
    ): List<File> {
        val extractedFiles = mutableListOf<File>()

        ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
            var entry = zis.nextEntry
            var count = 0

            while (entry != null) {
                // ZIP entry path: wiki-next-main/content/docs/en/...
                val entryPath = entry.name

                // Find the docs path after the repo folder
                val docsPathIndex = entryPath.indexOf(DOCS_PATH_IN_ZIP)

                if (docsPathIndex >= 0 && !entry.isDirectory) {
                    val relativePath = entryPath.substring(docsPathIndex + DOCS_PATH_IN_ZIP.length)

                    if (relativePath.endsWith(".md") || relativePath.endsWith(".mdx")) {
                        val outputFile = File(outputDir, relativePath)
                        outputFile.parentFile?.mkdirs()

                        FileOutputStream(outputFile).use { fos ->
                            zis.copyTo(fos)
                        }

                        extractedFiles.add(outputFile)
                        count++
                        onProgress(count, -1) // We don't know total
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        return extractedFiles
    }

    private fun parseMarkdownFile(file: File, baseDir: File): ParsedDoc? {
        return try {
            val content = file.readText()
            val relativePath = file.relativeTo(baseDir).path
                .replace("\\", "/")
                .removeSuffix(".md")
                .removeSuffix(".mdx")

            // Parse frontmatter (YAML between --- delimiters)
            val frontmatter = parseFrontmatter(content)
            val markdownContent = removeFrontmatter(content)

            // Derive category from path
            val pathParts = relativePath.split("/")
            val category = if (pathParts.size > 1) {
                pathParts.first().replaceFirstChar { it.uppercase() }
            } else {
                "General"
            }

            // Use frontmatter title or derive from filename
            val title = frontmatter?.title
                ?: file.nameWithoutExtension
                    .replace("-", " ")
                    .split(" ")
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            ParsedDoc(
                slug = relativePath,
                title = title,
                description = frontmatter?.description,
                content = markdownContent,
                category = category
            )
        } catch (e: Exception) {
            LOG.warn("Failed to parse ${file.name}", e)
            null
        }
    }

    private fun parseFrontmatter(content: String): DocFrontmatter? {
        if (!content.startsWith("---")) return null

        val endIndex = content.indexOf("---", 3)
        if (endIndex < 0) return null

        val frontmatterText = content.substring(3, endIndex).trim()

        // Simple YAML parsing for common fields
        var title: String? = null
        var description: String? = null

        for (line in frontmatterText.lines()) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")

                when (key.lowercase()) {
                    "title" -> title = value
                    "description" -> description = value
                }
            }
        }

        return DocFrontmatter(title = title, description = description)
    }

    private fun removeFrontmatter(content: String): String {
        if (!content.startsWith("---")) return content

        val endIndex = content.indexOf("---", 3)
        if (endIndex < 0) return content

        return content.substring(endIndex + 3).trim()
    }
}
