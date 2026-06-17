package com.oha
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Base64

data class ChannelData(
    @JsonProperty("country") val country: String?,
    @JsonProperty("id") val id: Long?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("p") val p: Int?
)

class OhaTvProvider : MainAPI() {

    override var mainUrl = "https://www.oha.to"

    override var name = "OHA TV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie)

    private val worldFlag = "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d4/World_Flag_%282004%29.svg/960px-World_Flag_%282004%29.svg.png"

    private fun getCountryFlag(countryName: String?): String {
        if (countryName.isNullOrEmpty() || countryName.equals("Unknown", ignoreCase = true)) return worldFlag

        val cleanName = countryName.trim().lowercase()
        val code = countryToCode[cleanName]

        return if (code != null) {
            "https://raw.githubusercontent.com/Abodabodd/country_icons/refs/heads/master/icons/flags/png1000px/${code}.png"
        } else {
            worldFlag
        }
    }

    private var cachedChannels: List<ChannelData>? = null

    private suspend fun getChannels(): List<ChannelData> {
        if (cachedChannels == null) {
            val responseArray = app.get("$mainUrl/channels").parsedSafe<Array<ChannelData>>()
            cachedChannels = responseArray?.toList() ?: emptyList()
        }
        return cachedChannels!!
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = getChannels()
        val groupedChannels = response.groupBy { it.country ?: "Other" }

        val homeLists = groupedChannels.map { (countryName, channelsList) ->

            val items = channelsList.mapNotNull { ch ->
                if (ch.id == null || ch.name == null) return@mapNotNull null

                val safeCountry = ch.country?.trim() ?: "Unknown"

                val displayName = "${ch.name} (${safeCountry})"

                val encodedName = Base64.encodeToString(
                    displayName.toByteArray(),
                    Base64.NO_WRAP
                )

                val safeUrl = "$mainUrl/channel/${ch.id}?n=$encodedName"
                newMovieSearchResponse(
                    name = displayName,
                    url = safeUrl,
                ) {
                    posterUrl = getCountryFlag(safeCountry)
                }
            }
            HomePageList(countryName, items)
        }
        return HomePageResponse(homeLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = getChannels()

        return response.filter { ch ->
            val searchTarget = "${ch.name} ${ch.country ?: ""}"
            searchTarget.contains(query, ignoreCase = true)
        }.mapNotNull { ch ->
            if (ch.id == null || ch.name == null) return@mapNotNull null

            val safeCountry = ch.country?.trim() ?: "Unknown"

            val displayName = "${ch.name} (${safeCountry})"

            val encodedName = Base64.encodeToString(
                displayName.toByteArray(),
                Base64.NO_WRAP
            )

            val safeUrl = "$mainUrl/channel/${ch.id}?n=$encodedName"
            newMovieSearchResponse(
                name = displayName,
                url = safeUrl,
            ) {
                posterUrl = getCountryFlag(safeCountry)
            }
        }
    }


    override suspend fun load(url: String): LoadResponse {

        val channelId = url.substringAfterLast("/").substringBefore("?")
        val encodedName = url.substringAfter("n=", "")

        val finalName = if (encodedName.isNotEmpty()) {
            String(Base64.decode(encodedName, Base64.NO_WRAP))
        } else {
            "Live Stream"
        }

        return newMovieLoadResponse(
            name = finalName,
            url = url,
            type = TvType.Movie,
            dataUrl = channelId,
        ) {
            val countryName = finalName.substringAfterLast("(")
                .substringBeforeLast(")")

            val flag = getCountryFlag(countryName)
            posterUrl = flag
            backgroundPosterUrl = flag
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val streamUrl = "$mainUrl/play/$data/index.m3u8"

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "OHA TV Server",
                url = streamUrl,
            ) {
                referer = "$mainUrl/"
                quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private val countryToCode = mapOf(
        "andorra" to "ad",
        "united arab emirates" to "ae",
        "afghanistan" to "af",
        "antigua and barbuda" to "ag",
        "anguilla" to "ai",
        "albania" to "al",
        "armenia" to "am",
        "angola" to "ao",
        "antarctica" to "aq",
        "argentina" to "ar",
        "american samoa" to "as",
        "austria" to "at",
        "australia" to "au",
        "aruba" to "aw",
        "åland islands" to "ax",
        "azerbaijan" to "az",
        "bosnia and herzegovina" to "ba",
        "barbados" to "bb",
        "bangladesh" to "bd",
        "belgium" to "be",
        "burkina faso" to "bf",
        "bulgaria" to "bg",
        "bahrain" to "bh",
        "burundi" to "bi",
        "benin" to "bj",
        "balkans" to "bk",
        "saint barthélemy" to "bl",
        "bermuda" to "bm",
        "brunei darussalam" to "bn",
        "bolivia" to "bo",
        "caribbean netherlands" to "bq",
        "brazil" to "br",
        "bahamas" to "bs",
        "bhutan" to "bt",
        "bouvet island" to "bv",
        "botswana" to "bw",
        "belarus" to "by",
        "belize" to "bz",
        "canada" to "ca",
        "cocos (keeling) islands" to "cc",
        "congo" to "cd",
        "central african republic" to "cf",
        "republic of the congo" to "cg",
        "switzerland" to "ch",
        "côte d'ivoire" to "ci",
        "cook islands" to "ck",
        "chile" to "cl",
        "cameroon" to "cm",
        "china" to "cn",
        "colombia" to "co",
        "costa rica" to "cr",
        "cuba" to "cu",
        "cape verde" to "cv",
        "curaçao" to "cw",
        "christmas island" to "cx",
        "cyprus" to "cy",
        "czech republic" to "cz",
        "germany" to "de",
        "djibouti" to "dj",
        "denmark" to "dk",
        "dominica" to "dm",
        "dominican republic" to "do",
        "algeria" to "dz",
        "ecuador" to "ec",
        "estonia" to "ee",
        "egypt" to "eg",
        "western sahara" to "eh",
        "eritrea" to "er",
        "spain" to "es",
        "ethiopia" to "et",
        "europe" to "eu",
        "finland" to "fi",
        "fiji" to "fj",
        "falkland islands" to "fk",
        "micronesia" to "fm",
        "faroe islands" to "fo",
        "france" to "fr",
        "gabon" to "ga",
        "england" to "gb-eng",
        "northern ireland" to "gb-nir",
        "scotland" to "gb-sct",
        "wales" to "gb-wls",
        "united kingdom" to "gb",
        "grenada" to "gd",
        "georgia" to "ge",
        "french guiana" to "gf",
        "guernsey" to "gg",
        "ghana" to "gh",
        "gibraltar" to "gi",
        "greenland" to "gl",
        "gambia" to "gm",
        "guinea" to "gn",
        "guadeloupe" to "gp",
        "equatorial guinea" to "gq",
        "greece" to "gr",
        "south georgia" to "gs",
        "guatemala" to "gt",
        "guam" to "gu",
        "guinea-bissau" to "gw",
        "guyana" to "gy",
        "hong kong" to "hk",
        "heard island" to "hm",
        "honduras" to "hn",
        "croatia" to "hr",
        "haiti" to "ht",
        "hungary" to "hu",
        "indonesia" to "id",
        "ireland" to "ie",
        "israel" to "il",
        "isle of man" to "im",
        "india" to "in",
        "british indian ocean territory" to "io",
        "arabia" to "iq",
        "iran" to "ir",
        "iceland" to "is",
        "italy" to "it",
        "jersey" to "je",
        "jamaica" to "jm",
        "jordan" to "jo",
        "japan" to "jp",
        "kenya" to "ke",
        "kyrgyzstan" to "kg",
        "cambodia" to "kh",
        "kiribati" to "ki",
        "comoros" to "km",
        "saint kitts and nevis" to "kn",
        "north korea" to "kp",
        "south korea" to "kr",
        "kuwait" to "kw",
        "cayman islands" to "ky",
        "kazakhstan" to "kz",
        "laos" to "la",
        "lebanon" to "lb",
        "saint lucia" to "lc",
        "liechtenstein" to "li",
        "sri lanka" to "lk",
        "liberia" to "lr",
        "lesotho" to "ls",
        "lithuania" to "lt",
        "luxembourg" to "lu",
        "latvia" to "lv",
        "libya" to "ly",
        "morocco" to "ma",
        "monaco" to "mc",
        "moldova" to "md",
        "montenegro" to "me",
        "saint martin" to "mf",
        "madagascar" to "mg",
        "marshall islands" to "mh",
        "north macedonia" to "mk",
        "mali" to "ml",
        "myanmar" to "mm",
        "mongolia" to "mn",
        "macao" to "mo",
        "northern mariana islands" to "mp",
        "martinique" to "mq",
        "mauritania" to "mr",
        "montserrat" to "ms",
        "malta" to "mt",
        "mauritius" to "mu",
        "maldives" to "mv",
        "malawi" to "mw",
        "mexico" to "mx",
        "malaysia" to "my",
        "mozambique" to "mz",
        "namibia" to "na",
        "new caledonia" to "nc",
        "niger" to "ne",
        "norfolk island" to "nf",
        "nigeria" to "ng",
        "nicaragua" to "ni",
        "netherlands" to "nl",
        "norway" to "no",
        "nepal" to "np",
        "nauru" to "nr",
        "niue" to "nu",
        "new zealand" to "nz",
        "oman" to "om",
        "panama" to "pa",
        "peru" to "pe",
        "french polynesia" to "pf",
        "papua new guinea" to "pg",
        "philippines" to "ph",
        "pakistan" to "pk",
        "poland" to "pl",
        "saint pierre and miquelon" to "pm",
        "pitcairn" to "pn",
        "puerto rico" to "pr",
        "palestine" to "ps",
        "portugal" to "pt",
        "palau" to "pw",
        "paraguay" to "py",
        "qatar" to "qa",
        "réunion" to "re",
        "romania" to "ro",
        "serbia" to "rs",
        "russia" to "ru",
        "russian federation" to "ru",
        "rwanda" to "rw",
        "solomon islands" to "sb",
        "seychelles" to "sc",
        "sudan" to "sd",
        "sweden" to "se",
        "singapore" to "sg",
        "saint helena" to "sh",
        "slovenia" to "si",
        "svalbard and jan mayen islands" to "sj",
        "slovakia" to "sk",
        "sierra leone" to "sl",
        "san marino" to "sm",
        "senegal" to "sn",
        "somalia" to "so",
        "suriname" to "sr",
        "south sudan" to "ss",
        "sao tome and principe" to "st",
        "el salvador" to "sv",
        "sint maarten" to "sx",
        "syria" to "sy",
        "swaziland" to "sz",
        "turks and caicos islands" to "tc",
        "chad" to "td",
        "french southern territories" to "tf",
        "togo" to "tg",
        "thailand" to "th",
        "tajikistan" to "tj",
        "tokelau" to "tk",
        "timor-leste" to "tl",
        "turkmenistan" to "tm",
        "tunisia" to "tn",
        "tonga" to "to",
        "turkey" to "tr",
        "trinidad and tobago" to "tt",
        "tuvalu" to "tv",
        "taiwan" to "tw",
        "tanzania" to "tz",
        "ukraine" to "ua",
        "uganda" to "ug",
        "united states" to "us",
        "usa" to "us",
        "uruguay" to "uy",
        "uzbekistan" to "uz",
        "vatican city" to "va",
        "saint vincent and the grenadines" to "vc",
        "venezuela" to "ve",
        "british virgin islands" to "vg",
        "us virgin islands" to "vi",
        "vietnam" to "vn",
        "vanuatu" to "vu",
        "wallis and futuna" to "wf",
        "samoa" to "ws",
        "kosovo" to "xk",
        "yemen" to "ye",
        "mayotte" to "yt",
        "south africa" to "za",
        "zambia" to "zm",
        "zimbabwe" to "zw",
    )
}