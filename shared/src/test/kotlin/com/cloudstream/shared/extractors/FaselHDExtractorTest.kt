package com.cloudstream.shared.extractors

import kotlinx.coroutines.runBlocking
import org.junit.Test
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

class FaselHDExtractorTest {
    @Test
    fun testExtraction() {
        val extractor = FaselHDExtractor()
        val url = "https://www.fasel-hd.cam/video_player?player_token=aWkxbng1eEVEUnFlSUlGRmxHWHp4eVJjSWtzbXI1Ni9yaGo3UTNQY3JtTkFNRUhGM0NkdTYxQkkvNWtPY2hMY2ZVUkRhNCs3RTNpVWlERVV0UnlMd0k1Y29zMm1XalpZcmN2MGJoSEVRM1Q2NVd4MWxaYmlXNzVqTEFpRXVlMEJCUFYzTnUrTjVRVHhWNDBnaWpzRmhFVldYQVRvdG9WY05odHdiMnhwOXlLVzRhN1lwMFBIQTVtK1R4K1hraElwbk93ZjVFVnJuMGw0YngwUWxhTjEyY1FsZlFiMU40bW9IZjVSS1hseldMcz06OmHo9TPzrMX48I1kdHjFfZs%3D"
        
        runBlocking {
            extractor.getUrl(url, null, 
                subtitleCallback = { sub: SubtitleFile -> 
                    println("Subtitle: ${sub.url}")
                },
                callback = { link: ExtractorLink -> 
                    println("Link: ${link.name} | ${link.quality} | ${link.url}")
                }
            )
        }
    }
}
