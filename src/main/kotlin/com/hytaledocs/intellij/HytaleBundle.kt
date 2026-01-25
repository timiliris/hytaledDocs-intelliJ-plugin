package com.hytaledocs.intellij

import com.hytaledocs.intellij.settings.HytaleAppSettings
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.util.*

@NonNls
private const val BUNDLE = "messages.HytaleBundle"

/**
 * Custom bundle that uses the plugin's language setting instead of system locale.
 * Default language is English, regardless of system locale.
 */
object HytaleBundle {
    private var cachedBundle: ResourceBundle? = null
    private var cachedLanguage: String? = null

    /**
     * Get a message from the bundle with the configured language.
     */
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        val bundle = getBundle()
        return try {
            val pattern = bundle.getString(key)
            if (params.isEmpty()) {
                pattern
            } else {
                java.text.MessageFormat.format(pattern, *params)
            }
        } catch (e: MissingResourceException) {
            key // Return key if not found
        }
    }

    /**
     * Get the ResourceBundle for the currently configured language.
     * Caches the bundle to avoid repeated loading.
     */
    private fun getBundle(): ResourceBundle {
        val currentLanguage = try {
            HytaleAppSettings.getInstance().language
        } catch (e: Exception) {
            // Settings may not be available during early initialization
            "en"
        }

        // Return cached bundle if language hasn't changed
        if (cachedBundle != null && cachedLanguage == currentLanguage) {
            return cachedBundle!!
        }

        // Load the appropriate bundle
        // Use Locale.ROOT for English to load base HytaleBundle.properties
        // (Locale.ENGLISH would look for HytaleBundle_en.properties which doesn't exist)
        val locale = when (currentLanguage) {
            "fr" -> Locale.FRENCH
            "de" -> Locale.GERMAN
            "es" -> Locale("es")
            "it" -> Locale.ITALIAN
            "pt_BR" -> Locale("pt", "BR")
            "pl" -> Locale("pl")
            "ru" -> Locale("ru")
            else -> Locale.ROOT  // English = base bundle
        }

        cachedBundle = try {
            // Try to load the specific locale bundle with NO fallback to system locale
            ResourceBundle.getBundle(BUNDLE, locale, HytaleBundle::class.java.classLoader, NoFallbackControl())
        } catch (e: MissingResourceException) {
            // Fallback to base bundle (English)
            ResourceBundle.getBundle(BUNDLE, Locale.ROOT, HytaleBundle::class.java.classLoader, NoFallbackControl())
        }
        cachedLanguage = currentLanguage

        return cachedBundle!!
    }

    /**
     * Clear the cached bundle to force reload on next access.
     * Call this when the language setting changes.
     */
    @JvmStatic
    fun clearCache() {
        cachedBundle = null
        cachedLanguage = null
    }

    /**
     * Custom ResourceBundle.Control that:
     * 1. Loads properties files as UTF-8
     * 2. Does NOT fall back to system locale (only uses requested locale or base)
     */
    private class NoFallbackControl : ResourceBundle.Control() {

        override fun getCandidateLocales(baseName: String, locale: Locale): List<Locale> {
            // Only return the requested locale and ROOT (base bundle)
            // This prevents falling back to system locale
            return if (locale == Locale.ROOT) {
                listOf(Locale.ROOT)
            } else {
                listOf(locale, Locale.ROOT)
            }
        }

        override fun getFallbackLocale(baseName: String, locale: Locale): Locale? {
            // No fallback to system locale
            return null
        }

        override fun newBundle(
            baseName: String,
            locale: Locale,
            format: String,
            loader: ClassLoader,
            reload: Boolean
        ): ResourceBundle? {
            val bundleName = toBundleName(baseName, locale)
            val resourceName = toResourceName(bundleName, "properties")

            val stream = if (reload) {
                loader.getResource(resourceName)?.openConnection()?.apply {
                    useCaches = false
                }?.getInputStream()
            } else {
                loader.getResourceAsStream(resourceName)
            }

            return stream?.use { inputStream ->
                PropertyResourceBundle(inputStream.reader(Charsets.UTF_8))
            }
        }
    }
}
