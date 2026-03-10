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
     * Set to true by the app's SearchViewModel if it supports
     * intercepting lazy search placeholders and resolving them on-demand.
     */
    @Volatile
    var appSupportsLazySearch: Boolean = false
}
