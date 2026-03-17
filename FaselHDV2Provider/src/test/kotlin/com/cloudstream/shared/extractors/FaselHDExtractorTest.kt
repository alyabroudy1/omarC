package com.cloudstream.shared.extractors

import org.junit.Test
import org.mozilla.javascript.Context
import java.io.File

class FaselHDExtractorTest {
    
    @Test
    fun testRhinoJsEvaluation() {
        println("Reading player page from file...")
        
        val htmlFile = File("/tmp/fasel_player.html")
        if (!htmlFile.exists()) {
            println("ERROR: HTML file not found")
            return
        }
        
        val html = htmlFile.readText()
        println("Read ${html.length} bytes")
        
        val scriptBlock = findRelevantScriptBlock(html)
        if (scriptBlock == null) {
            println("ERROR: Could not find script block")
            return
        }
        println("Found script block, length: ${scriptBlock.length}")
        
        // Try regex extraction first since Rhino can't parse this code
        println("\n=== Regex extraction ===")
        extractUrlsFromScript(scriptBlock)
        
        // Also try looking for hlsPlaylist in the raw HTML
        println("\n=== Looking for hlsPlaylist in HTML ===")
        findPlaylistInHtml(html)
    }

    private fun findRelevantScriptBlock(html: String): String? {
        val scriptRegex = Regex("""<script[^>]*>[\s\S]*?hlsPlaylist[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
        val match = scriptRegex.find(html)
        if (match != null) {
            return match.value.replace(Regex("""</?script[^>]*>"""), "").trim()
        }
        
        val mainPlayerScriptRegex = Regex("""<script[^>]*>[\s\S]*?mainPlayer\.setup[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
        val mainPlayerMatch = mainPlayerScriptRegex.find(html)
        if (mainPlayerMatch != null) {
            return mainPlayerMatch.value.replace(Regex("""</?script[^>]*>"""), "").trim()
        }
        
        val obfuscatedScripts = Regex("""<script[^>]*>[\s\S]*?_0x[a-f0-9]{4,}[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
            .findAll(html).toList()
        
        if (obfuscatedScripts.isNotEmpty()) {
            return obfuscatedScripts.maxByOrNull { it.value.length }?.value
                ?.replace(Regex("""</?script[^>]*>"""), "")?.trim()
        }
        
        return null
    }
    
    private fun findPlaylistInHtml(html: String) {
        // Look for hlsPlaylist = {...}
        val playlistPattern = Regex("""hlsPlaylist\s*=\s*(\{[^}]+\})""")
        val match = playlistPattern.find(html)
        if (match != null) {
            println("Found hlsPlaylist assignment: ${match.groupValues[1].take(200)}")
        }
        
        // Look for sources array
        val sourcesPattern = Regex("""sources\s*:\s*\[(\{[^}]+\})\]""")
        val sourcesMatch = sourcesPattern.find(html)
        if (sourcesMatch != null) {
            println("Found sources: ${sourcesMatch.groupValues[1].take(200)}")
        }
    }
    
    private fun extractUrlsFromScript(script: String) {
        // Try to find any m3u8 URLs
        val m3u8Pattern = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
        val m3u8Matches = m3u8Pattern.findAll(script)
        if (m3u8Matches.any()) {
            println("Found m3u8 URLs:")
            m3u8Matches.forEach { println("  ${it.value}") }
        }
        
        // Try to find lhdx.xyz patterns
        val lhdxPattern = Regex("""['"`]([^'"`]+lhdx\.xyz[^'"`]+)['"`]""")
        val lhdxMatches = lhdxPattern.findAll(script)
        if (lhdxMatches.any()) {
            println("\nFound lhdx.xyz patterns:")
            lhdxMatches.forEach { println("  ${it.groupValues[1]}") }
        }
        
        // Try to find scdns.io patterns  
        val scdnsPattern = Regex("""['"`]([^'"`]+scdns\.io[^'"`]+)['"`]""")
        val scdnsMatches = scdnsPattern.findAll(script)
        if (scdnsMatches.any()) {
            println("\nFound scdns.io patterns:")
            scdnsMatches.forEach { println("  ${it.groupValues[1]}") }
        }
        
        // Look for URL construction patterns - find strings that look like they could be video URLs
        val urlConstruction = Regex("""https?://[a-zA-Z0-9._/-]+""").findAll(script)
        val urls = urlConstruction.map { it.value }.distinct().filter { 
            it.contains("stream") || it.contains("video") || it.contains("play") || it.contains("cdn") 
        }
        if (urls.any()) {
            println("\nPotential video URLs:")
            urls.forEach { println("  $it") }
        }
    }
}
