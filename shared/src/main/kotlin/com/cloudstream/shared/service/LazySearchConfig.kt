package com.cloudstream.shared.service

/**
 * Runtime configuration for lazy search.
 *
 * The NEW Cloudstream app sets [appSupportsLazySearch] = true during init.
 * OLD app versions never touch this → defaults to false.
 *
 * BaseProvider.search() checks this flag to decide whether to skip
 * the CF WebView fallback and return a placeholder instead.
 */
object LazySearchConfig {
    /** URL prefix used by lazy search placeholder SearchResponses */
    const val LAZY_SEARCH_PREFIX = "lazy://"

    /**
     * Set to true if the app's SearchViewModel supports intercepting lazy search
     * placeholders and resolving them on-demand. Evaluated via reflection so this
     * module doesn't force a dependency on newer app versions.
     */
    val appSupportsLazySearch: Boolean by lazy {
        try {
            // Plugins have a separate ClassLoader from the main app. We need to use the parent
            // or context ClassLoader to check for app classes.
            val cl = Thread.currentThread().contextClassLoader ?: LazySearchConfig::class.java.classLoader
            val clazz = Class.forName("com.lagradost.cloudstream3.ui.search.SearchViewModel", false, cl)
            val method = clazz.methods.find { it.name == "resolveLazySearch" }
            if (method != null) {
                android.util.Log.i("LazySearchConfig", "Reflection succeeded! Method found.")
                true
            } else {
                android.util.Log.w("LazySearchConfig", "Class found but method not found!")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("LazySearchConfig", "Reflection failed: ${e.message}", e)
            false
        }
    }
}
