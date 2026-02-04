package com.cloudstream.shared.extractors

/**
 * Common link resolution patterns for video players.
 * Works with JWPlayer, various embedded players, and URL parameter extraction.
 */
object LinkResolvers {
    /**
     * Extract video URLs from JWPlayer configurations in HTML.
     */
    fun extractJwPlayerSources(html: String): List<String> {
        val urls = mutableListOf<String>()
        // Pattern 1: file: "..."
        Regex("""file:\s*["']([^"']+)["']""").findAll(html).forEach { match ->
            urls.add(match.groupValues[1])
        }
        // Pattern 2: sources: [{file:"..."}]
        Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").findAll(html).forEach { match ->
            urls.add(match.groupValues[1])
        }
        return urls
    }
    
    /**
     * Extract URL from a query parameter (e.g., play.php?url=BASE64).
     */
    fun extractUrlParam(url: String): String? {
         if (url.contains("url=")) {
             return url.substringAfter("url=").substringBefore("&")
         }
         return null
    }
    
    /**
     * Try to decode Base64-encoded URL parameter.
     */
    fun decodeBase64Url(encoded: String): String? {
        return try {
            val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
            if (decoded.startsWith("http")) decoded else null
        } catch (e: Exception) {
            null
        }
    }
}
