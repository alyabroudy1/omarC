package com.cloudstream.shared.service

/**
 * Thrown when a search request is blocked by Cloudflare and the caller
 * opted out of the automatic WebView fallback (lazy search mode).
 * 
 * This allows SearchViewModel to catch it and show a "tap to search"
 * placeholder instead of silently dropping the provider.
 */
class CloudflareBlockedSearchException(
    val providerName: String,
    val domain: String
) : Exception("Cloudflare blocked during search for $providerName at $domain")
