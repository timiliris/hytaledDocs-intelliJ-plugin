package com.hytaledocs.intellij.util

import java.net.http.HttpClient
import java.time.Duration

/**
 * Shared HttpClient singleton for all HTTP operations in the plugin.
 *
 * This provides a single, properly configured HttpClient instance that:
 * - Follows redirects automatically
 * - Has a 10-second connection timeout
 * - Reuses connections efficiently
 *
 * Usage:
 * ```kotlin
 * val client = HttpClientPool.client
 * val response = client.send(request, HttpResponse.BodyHandlers.ofString())
 * ```
 */
object HttpClientPool {

    /**
     * The shared HttpClient instance.
     * Configured with:
     * - 10 second connect timeout
     * - Automatic redirect following
     */
    val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
    }
}
