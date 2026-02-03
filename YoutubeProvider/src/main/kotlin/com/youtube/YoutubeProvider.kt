package com.youtube

import android.content.Intent
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

class YoutubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YoutubeProvider"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = listOf(
            newMovieSearchResponse("Test YouTube Video", "https://www.youtube.com/watch?v=5AwtptT8X8k", TvType.Movie) {
                this.posterUrl = "https://img.youtube.com/vi/5AwtptT8X8k/maxresdefault.jpg"
            }
        )
        return newHomePageResponse("Test Videos", items)
    }

    override suspend fun load(url: String): LoadResponse {
        return newMovieLoadResponse("Test YouTube Video", url, TvType.Movie, url) {
            this.posterUrl = "https://img.youtube.com/vi/5AwtptT8X8k/maxresdefault.jpg"
            this.plot = "A test video to verify the YouTube WebView Player implementation."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        CommonActivity.activity?.let { activity ->
           activity.runOnUiThread {
               YouTubePlayerDialog(activity, data).show()
           }
        }
        return true
    }
}
