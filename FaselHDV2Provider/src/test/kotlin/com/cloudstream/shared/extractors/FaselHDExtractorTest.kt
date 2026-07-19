package com.cloudstream.shared.extractors

import org.junit.Test
import java.io.File
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

class FaselHDExtractorTest {

    class PreferIpv6Dns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                println("DNS resolution failed for $hostname: ${e.message}")
                return emptyList()
            }
            println("Default DNS lookup for $hostname returned: ${addresses.map { "${it.hostAddress} (IPv6: ${it is Inet6Address})" }}")
            val sorted = addresses.sortedWith(Comparator { a, b ->
                when {
                    a is Inet6Address && b is Inet4Address -> -1
                    a is Inet4Address && b is Inet6Address -> 1
                    else -> 0
                }
            })
            println("PreferIpv6Dns sorted addresses: ${sorted.map { "${it.hostAddress} (IPv6: ${it is Inet6Address})" }}")
            return sorted
        }
    }

    class ForceIpv6Dns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                println("DNS resolution failed for $hostname: ${e.message}")
                return emptyList()
            }
            val ipv6Only = addresses.filterIsInstance<Inet6Address>()
            println("ForceIpv6Dns filtered addresses: ${ipv6Only.map { it.hostAddress }} (from total: ${addresses.map { it.hostAddress }})")
            if (ipv6Only.isEmpty()) {
                println("Warning: No IPv6 addresses resolved. Falling back to IPv4.")
                return addresses
            }
            return ipv6Only
        }
    }

    @Test
    fun testIpv6Resolution() {
        println("=== Local Network Interfaces ===")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isUp && !iface.isLoopback) {
                        val addrs = iface.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val addr = addrs.nextElement()
                            println("Interface ${iface.name}: ${addr.hostAddress} (IPv6: ${addr is Inet6Address})")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to list interfaces: ${e.message}")
        }

        val targetUrl = "https://www.fasel-hd.cam/movies/%d9%81%d9%8a%d9%84%d9%85-joker-2019-%d9%85%d8%aa%d8%b1%d8%ac%d9%85-wr"
        println("\n=== Testing Fetch with Default DNS (Standard Client) ===")
        val standardClient = OkHttpClient.Builder().build()
        try {
            val request = Request.Builder().url(targetUrl).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36").build()
            val response = standardClient.newCall(request).execute()
            println("Standard Client Response Code: ${response.code}")
            println("Response headers: ${response.headers}")
            response.close()
        } catch (e: Exception) {
            println("Standard Client Fetch Failed: ${e.message}")
            e.printStackTrace()
        }

        println("\n=== Testing Fetch with PreferIpv6Dns Client ===")
        val preferIpv6Client = OkHttpClient.Builder().dns(PreferIpv6Dns()).build()
        try {
            val request = Request.Builder().url(targetUrl).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36").build()
            val response = preferIpv6Client.newCall(request).execute()
            println("PreferIpv6 Client Response Code: ${response.code}")
            println("Response headers: ${response.headers}")
            response.close()
        } catch (e: Exception) {
            println("PreferIpv6 Client Fetch Failed: ${e.message}")
            e.printStackTrace()
        }

        println("\n=== Testing Fetch with ForceIpv6Dns Client ===")
        val forceIpv6Client = OkHttpClient.Builder().dns(ForceIpv6Dns()).build()
        try {
            val request = Request.Builder().url(targetUrl).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36").build()
            val response = forceIpv6Client.newCall(request).execute()
            println("ForceIpv6 Client Response Code: ${response.code}")
            println("Response headers: ${response.headers}")
            response.close()
        } catch (e: Exception) {
            println("ForceIpv6 Client Fetch Failed: ${e.message}")
            e.printStackTrace()
        }
    }

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
