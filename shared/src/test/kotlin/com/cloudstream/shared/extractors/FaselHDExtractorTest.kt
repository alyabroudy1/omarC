package com.cloudstream.shared.extractors

import org.junit.Test
import java.io.File

class FaselHDExtractorTest {
    
    // We instantiate the protected strategy classes via reflection or just duplicate the core parsing logic to test it if they are private
    // Since we just want to verify the logic, we test the companion object parsing methods first

    @Test
    fun testParseDataUrls() {
        val htmlButtonChunk = """
            <button class="hd_btn " data-url="https://master.c.scdns.io/stream/v2/token/normal/0/5.42.207.5/yes/hash/domain/master.m3u8">auto</button>
            <button class="hd_btn " data-url="https://r466--8katnn5p.c.scdns.io/stream/v1/hls/token/domain/all/5.42.207.5/yes/DE/0/hash/160_hd1080b_playlist.m3u8">1080p</button>
        """.trimIndent()
        
        val streams = FaselHDExtractor.parseDataUrls(htmlButtonChunk)
        assert(streams.size == 2)
        assert(streams[0].quality == "auto")
        assert(streams[1].quality == "1080p")
    }

    @Test
    fun testParseJwplayerPlaylist() {
        val jsonChunk = """
            [{"file":"https://master.c.scdns.io/stream/v2/token/normal/0/5.42.207.5/yes/hash/domain/master.m3u8","type":"hls","label":"auto"},{"file":"https://r466--8katnn5p.c.scdns.io/stream/v1/hls/token/domain/all/5.42.207.5/yes/DE/0/hash/160_hd1080b_playlist.m3u8","type":"hls","label":"1080p"}]
        """.trimIndent()
        
        val streams = FaselHDExtractor.parseJwplayerPlaylist(jsonChunk)
        assert(streams.size == 2)
        assert(streams[0].quality == "auto")
        assert(streams[1].quality == "1080p")
    }
}
