package com.arabseed.utils

object LinkResolvers {
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
    
    fun extractUrlParam(url: String): String? {
         if (url.contains("url=")) {
             return url.substringAfter("url=").substringBefore("&")
         }
         return null
    }
}
