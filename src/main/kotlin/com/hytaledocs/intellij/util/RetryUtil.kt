package com.hytaledocs.intellij.util

import com.intellij.openapi.diagnostic.Logger

object RetryUtil {
    private val LOG = Logger.getInstance(RetryUtil::class.java)

    fun <T> withRetry(
        maxAttempts: Int = 3,
        delayMs: Long = 1000,
        operation: String = "operation",
        block: () -> T
    ): T {
        var lastException: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                LOG.warn("$operation failed (attempt ${attempt + 1}/$maxAttempts): ${e.message}")
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(delayMs * (attempt + 1)) // exponential backoff
                }
            }
        }
        throw lastException ?: RuntimeException("$operation failed after $maxAttempts attempts")
    }
}
