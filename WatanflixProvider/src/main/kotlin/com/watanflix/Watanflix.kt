package com.watanflix

import com.lagradost.cloudstream3.*
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.youtube.YouTubePlayerDialog
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import android.app.Activity

class Watanflix : BaseProvider() {

    override val baseDomain get() = "watanflix.com"
    override val providerName get() = "Watanflix"
    override val githubConfigUrl get() = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/watanflix.json"

    override val mainPage = mainPageOf(
        "/en/category/مسلسلات" to "مسلسلات",
        "/en/category/الأفلام" to "أفلام",
        "/en/category/مسرحيات" to "مسرحيات",
        "/en/category/برامج" to "برامج",
        "/en/category/أطفال" to "أطفال"
    )

    override fun getParser(): NewBaseParser {
        return WatanflixParser()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Watanflix episodes are YouTube links - use YouTubePlayerDialog
        CommonActivity.activity?.let { activity ->
            if (activity is Activity) {
                activity.runOnUiThread {
                    val dialog = YouTubePlayerDialog(activity, data)
                    dialog.show()
                }
            }
        }
        return true
    }
}
