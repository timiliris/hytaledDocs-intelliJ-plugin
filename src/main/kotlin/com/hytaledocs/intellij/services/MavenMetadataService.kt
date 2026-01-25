package com.hytaledocs.intellij.services

import com.hytaledocs.intellij.settings.HytaleAppSettings
import com.hytaledocs.intellij.util.HttpClientPool
import com.hytaledocs.intellij.util.RetryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import org.w3c.dom.Element
import java.io.StringReader
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Service for fetching and parsing Maven metadata from the Hytale Maven repository.
 * Provides version information for the Hytale Server artifact.
 *
 * Maven Repository: https://maven.hytale.com/release/
 * GroupId: com.hypixel.hytale
 * ArtifactId: Server
 */
@Service(Service.Level.APP)
class MavenMetadataService {

    companion object {
        private val LOG = Logger.getInstance(MavenMetadataService::class.java)

        const val MAVEN_REPOSITORY_URL = "https://maven.hytale.com/release"
        const val GROUP_ID = "com.hypixel.hytale"
        const val ARTIFACT_ID = "Server"

        private const val CACHE_DURATION_MS = 60 * 60 * 1000L // 1 hour

        fun getInstance(): MavenMetadataService {
            return ApplicationManager.getApplication().getService(MavenMetadataService::class.java)
        }

        /**
         * Constructs the Maven metadata URL for the Server artifact.
         */
        fun getMetadataUrl(baseUrl: String = MAVEN_REPOSITORY_URL): String {
            val groupPath = GROUP_ID.replace('.', '/')
            return "$baseUrl/$groupPath/$ARTIFACT_ID/maven-metadata.xml"
        }

        /**
         * Constructs the download URL for a specific version.
         */
        fun getJarDownloadUrl(version: String, baseUrl: String = MAVEN_REPOSITORY_URL): String {
            val groupPath = GROUP_ID.replace('.', '/')
            return "$baseUrl/$groupPath/$ARTIFACT_ID/$version/$ARTIFACT_ID-$version.jar"
        }

        /**
         * Constructs the POM URL for a specific version.
         */
        fun getPomUrl(version: String, baseUrl: String = MAVEN_REPOSITORY_URL): String {
            val groupPath = GROUP_ID.replace('.', '/')
            return "$baseUrl/$groupPath/$ARTIFACT_ID/$version/$ARTIFACT_ID-$version.pom"
        }

        /**
         * Constructs the Javadoc URL for a specific version.
         */
        fun getJavadocUrl(version: String, baseUrl: String = MAVEN_REPOSITORY_URL): String {
            val groupPath = GROUP_ID.replace('.', '/')
            return "$baseUrl/$groupPath/$ARTIFACT_ID/$version/$ARTIFACT_ID-$version-javadoc.jar"
        }

        /**
         * Constructs the Sources URL for a specific version.
         */
        fun getSourcesUrl(version: String, baseUrl: String = MAVEN_REPOSITORY_URL): String {
            val groupPath = GROUP_ID.replace('.', '/')
            return "$baseUrl/$groupPath/$ARTIFACT_ID/$version/$ARTIFACT_ID-$version-sources.jar"
        }
    }

    /**
     * Parsed Maven metadata containing version information.
     */
    data class MavenMetadata(
        val groupId: String,
        val artifactId: String,
        val versions: List<String>,
        val latestVersion: String?,
        val releaseVersion: String?,
        val lastUpdated: String?
    )

    /**
     * Represents a server version with parsed metadata.
     */
    data class ServerVersion(
        val version: String,
        val date: LocalDate?,
        val commitHash: String?,
        val downloadUrl: String,
        val javadocUrl: String,
        val sourcesUrl: String
    ) {
        /**
         * Returns a human-readable display string for this version.
         */
        fun getDisplayName(): String {
            return if (date != null) {
                val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
                "$version (${date.format(formatter)})"
            } else {
                version
            }
        }
    }

    // Cache for metadata
    private var cachedMetadata: MavenMetadata? = null
    private var cacheTimestamp: Long = 0

    /**
     * Fetches Maven metadata from the repository.
     * Uses caching to avoid excessive requests.
     *
     * @param forceRefresh If true, bypasses the cache
     * @return CompletableFuture containing the parsed metadata
     */
    fun fetchMetadata(forceRefresh: Boolean = false): CompletableFuture<MavenMetadata> {
        // Check cache
        if (!forceRefresh && cachedMetadata != null) {
            val age = System.currentTimeMillis() - cacheTimestamp
            if (age < CACHE_DURATION_MS) {
                LOG.debug("Returning cached Maven metadata (age: ${age / 1000}s)")
                return CompletableFuture.completedFuture(cachedMetadata)
            }
        }

        return CompletableFuture.supplyAsync {
            val settings = HytaleAppSettings.getInstance()
            val baseUrl = settings.state.mavenRepositoryUrl.ifBlank { MAVEN_REPOSITORY_URL }
            val metadataUrl = getMetadataUrl(baseUrl)

            LOG.info("Fetching Maven metadata from: $metadataUrl")

            val request = HttpRequest.newBuilder()
                .uri(URI.create(metadataUrl))
                .header("User-Agent", "HytaleIntelliJPlugin/1.0")
                .GET()
                .build()

            val response = RetryUtil.withRetry(
                maxAttempts = 3,
                delayMs = 1000,
                operation = "Fetch Maven metadata"
            ) {
                HttpClientPool.client.send(request, HttpResponse.BodyHandlers.ofString())
            }

            when (response.statusCode()) {
                200 -> {
                    val metadata = parseMetadataXml(response.body())
                    cachedMetadata = metadata
                    cacheTimestamp = System.currentTimeMillis()
                    LOG.info("Successfully fetched Maven metadata: ${metadata.versions.size} versions found")
                    metadata
                }
                403 -> throw RuntimeException(
                    "Access denied to Maven repository (HTTP 403).\n" +
                    "The Maven repository may require authentication.\n" +
                    "Please ensure you have access to maven.hytale.com"
                )
                404 -> throw RuntimeException(
                    "Maven metadata not found (HTTP 404).\n" +
                    "The artifact may not be published yet."
                )
                else -> throw RuntimeException(
                    "Failed to fetch Maven metadata: HTTP ${response.statusCode()}"
                )
            }
        }
    }

    /**
     * Gets available server versions, parsed with date and commit info.
     */
    fun getAvailableVersions(forceRefresh: Boolean = false): CompletableFuture<List<ServerVersion>> {
        return fetchMetadata(forceRefresh).thenApply { metadata ->
            val settings = HytaleAppSettings.getInstance()
            val baseUrl = settings.state.mavenRepositoryUrl.ifBlank { MAVEN_REPOSITORY_URL }

            metadata.versions.map { version ->
                parseVersion(version, baseUrl)
            }.sortedByDescending { it.date ?: LocalDate.MIN }
        }
    }

    /**
     * Gets the latest version.
     */
    fun getLatestVersion(forceRefresh: Boolean = false): CompletableFuture<ServerVersion?> {
        return fetchMetadata(forceRefresh).thenApply { metadata ->
            val settings = HytaleAppSettings.getInstance()
            val baseUrl = settings.state.mavenRepositoryUrl.ifBlank { MAVEN_REPOSITORY_URL }

            metadata.latestVersion?.let { parseVersion(it, baseUrl) }
                ?: metadata.releaseVersion?.let { parseVersion(it, baseUrl) }
                ?: metadata.versions.firstOrNull()?.let { parseVersion(it, baseUrl) }
        }
    }

    /**
     * Clears the cached metadata.
     */
    fun clearCache() {
        cachedMetadata = null
        cacheTimestamp = 0
        LOG.info("Maven metadata cache cleared")
    }

    /**
     * Parses a version string into a ServerVersion object.
     * Version format: YYYY.MM.DD-commitHash (e.g., 2026.01.24-6e2d4fc36)
     */
    private fun parseVersion(version: String, baseUrl: String): ServerVersion {
        val parts = version.split("-", limit = 2)

        val date = try {
            if (parts.isNotEmpty()) {
                val dateParts = parts[0].split(".")
                if (dateParts.size == 3) {
                    LocalDate.of(
                        dateParts[0].toInt(),
                        dateParts[1].toInt(),
                        dateParts[2].toInt()
                    )
                } else null
            } else null
        } catch (e: Exception) {
            LOG.debug("Failed to parse date from version: $version", e)
            null
        }

        val commitHash = if (parts.size > 1) parts[1] else null

        return ServerVersion(
            version = version,
            date = date,
            commitHash = commitHash,
            downloadUrl = getJarDownloadUrl(version, baseUrl),
            javadocUrl = getJavadocUrl(version, baseUrl),
            sourcesUrl = getSourcesUrl(version, baseUrl)
        )
    }

    /**
     * Parses the maven-metadata.xml content.
     */
    private fun parseMetadataXml(xml: String): MavenMetadata {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Prevent XXE attacks
            setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(xml.byteInputStream())

        document.documentElement.normalize()

        val groupId = getElementText(document.documentElement, "groupId") ?: GROUP_ID
        val artifactId = getElementText(document.documentElement, "artifactId") ?: ARTIFACT_ID

        val versioning = document.getElementsByTagName("versioning").item(0) as? Element
        val latestVersion = versioning?.let { getElementText(it, "latest") }
        val releaseVersion = versioning?.let { getElementText(it, "release") }
        val lastUpdated = versioning?.let { getElementText(it, "lastUpdated") }

        val versions = mutableListOf<String>()
        val versionsElement = versioning?.getElementsByTagName("versions")?.item(0) as? Element
        versionsElement?.getElementsByTagName("version")?.let { nodeList ->
            for (i in 0 until nodeList.length) {
                val versionText = nodeList.item(i).textContent?.trim()
                if (!versionText.isNullOrBlank()) {
                    versions.add(versionText)
                }
            }
        }

        return MavenMetadata(
            groupId = groupId,
            artifactId = artifactId,
            versions = versions,
            latestVersion = latestVersion,
            releaseVersion = releaseVersion,
            lastUpdated = lastUpdated
        )
    }

    private fun getElementText(parent: Element, tagName: String): String? {
        val elements = parent.getElementsByTagName(tagName)
        return if (elements.length > 0) elements.item(0).textContent?.trim() else null
    }
}
