package com.lagradost.cloudstream3.plugins

import com.cloudstream.shared.parsing.CssSelector
import com.cloudstream.shared.parsing.EpisodeConfig
import com.cloudstream.shared.parsing.LoadPageConfig
import com.cloudstream.shared.parsing.MainPageConfig
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.parsing.WatchServerSelector

class LodyParser : NewBaseParser() {

    override fun getSearchUrl(domain: String, query: String): String {
        return "$domain/wp-content/themes/Lodynet2020/Api/RequestSearch.php?value=$query"
    }

    override val mainPageConfig = MainPageConfig(
        container = ".IndexNewlyField",
        title = CssSelector(query = ".IndexFieldTitle a", attr = "text"),
        url = CssSelector(query = ".ItemNewlyField a", attr = "href"),
        poster = CssSelector(query = ".NewlyCover", attr = "data-src")
    )

    override val searchConfig = MainPageConfig(
        container = "",
        title = CssSelector(query = "", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        poster = CssSelector(query = "", attr = "src")
    )

    override val loadPageConfig = LoadPageConfig(
        title = CssSelector(query = "h1#PrimaryTitle", attr = "text"),
        poster = CssSelector(query = "#CoverSingle", attr = "data-src"),
        plot = CssSelector(query = "#ContentDetails p", attr = "text")
    )

    override val episodeConfig = EpisodeConfig(
        container = "#ListEpisodes .ItemEpisode, #ListEpisodes .CurrentEpisode",
        title = CssSelector(query = "", attr = "text"),
        url = CssSelector(query = "", attr = "href"),
        episode = CssSelector(query = "", attr = "id", regex = """Ep(\d+)""")
    )

    override val watchServersSelectors = WatchServerSelector(
        url = CssSelector(query = "#AllServerWatch button", attr = "onclick", regex = """SwitchServer\(this,\s*(\d+)"""),
        id = CssSelector(query = "", attr = "id"),
        title = CssSelector(query = "", attr = "text"),
        iframe = CssSelector(query = "", attr = "src")
    )
}
