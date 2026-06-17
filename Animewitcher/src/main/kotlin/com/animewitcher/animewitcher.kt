package com.animewitcher

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.*
import kotlin.collections.HashMap

class AnimeWitcherProvider : MainAPI() {
    override var mainUrl = "https://animewitcher.com"
    override var name = "AnimeWitcher"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "ar"
    override val hasMainPage = true


    private var algoliaAppId = "5UIU27G8CZ"
    private var algoliaApiKey = "ef06c5ee4a0d213c011694f18861805c"

    private fun getAlgoliaHeaders(): Map<String, String> {
        return mapOf(
            "X-Algolia-Application-Id" to algoliaAppId,
            "X-Algolia-API-Key" to algoliaApiKey,
            "User-Agent" to "Algolia for Android (3.27.0); Android (13)",
            "Content-Type" to "application/json; charset=UTF-8",
        )
    }

    private val FIREBASE_PROJECT_ID = "animewitcher-1c66d"
    private val BU_AUTH_KEY: String? = null

    private val serverWordsCache = HashMap<String, ServerWords>()

    data class EpisodeInfo(
        val id: String,
        val name: String?,
        val number: Int,
        val imageUrl: String? = null
    )
    data class ServerModel(val name: String?, val link: String?, val quality: String?, val originalLink: String?, val openBrowser: Boolean)
    data class ServerWords(val name: String, val word1: String?, val word2: String?, val word3: String?, val word4: String?)

    private fun logd(msg: String) {
        println("[AnimeWitcherLog] $msg")
    }

    private fun algoliaUrl(index: String) = "https://${algoliaAppId}-dsn.algolia.net/1/indexes/$index/query"

    private fun getQualityAsInt(quality: String?): Int {
        return quality?.filter { it.isDigit() }?.toIntOrNull() ?: 0
    }

    private suspend fun refreshAlgoliaKeys() {


        try {

            val url = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/Settings"
            val res = app.get(url).text
            val json = JSONObject(res)
            val docs = json.optJSONArray("documents") ?: return

            for (i in 0 until docs.length()) {
                val doc = docs.getJSONObject(i)
                val fields = doc.optJSONObject("fields")

                if (fields != null && fields.has("search_settings")) {
                    val searchSettings = fields.getJSONObject("search_settings")
                        .optJSONObject("mapValue")
                        ?.optJSONObject("fields")

                    if (searchSettings != null) {

                        val newAppId = searchSettings.optJSONObject("app_id_v3")?.optString("stringValue")
                        val newApiKey = searchSettings.optJSONObject("api_key")?.optString("stringValue")

                        if (!newAppId.isNullOrEmpty() && !newApiKey.isNullOrEmpty()) {
                            algoliaAppId = newAppId
                            algoliaApiKey = newApiKey
                            logd("✅ Keys Updated from Firestore: ID=$algoliaAppId")
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logd("❌ Failed to refresh Algolia keys: ${e.message}")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {

        refreshAlgoliaKeys()

        val indexName = "recent"
        val pageParam = (page - 1).coerceAtLeast(0)

        val attributes = URLEncoder.encode("[\"objectID\",\"name\",\"poster_uri\",\"path\",\"type\",\"anime_id\"]", "utf-8")
        val params = "attributesToRetrieve=$attributes&hitsPerPage=30&page=$pageParam&query="

        val payload = JSONObject().put("params", params)
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        val res = app.post(algoliaUrl(indexName), requestBody = body, headers = getAlgoliaHeaders()).text

        val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
        val hits = json.optJSONArray("hits") ?: JSONArray()

        val list = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i)
            val title = obj.optString("name")
            if (title.isNullOrEmpty()) continue
            val poster = obj.optString("poster_uri")
            val animeId = obj.optString("anime_id", obj.optString("objectID"))
            val fullData = URLEncoder.encode(obj.toString(), "utf-8")
            val url = "$mainUrl/watch/${URLEncoder.encode(animeId, "utf-8")}?data=$fullData"
            list.add(newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster })
        }
        return@withContext newHomePageResponse("أحدث الأنميات", list)
    }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {

        refreshAlgoliaKeys()

        val indexName = "series"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val attributes = URLEncoder.encode(
            "[\"objectID\",\"name\",\"poster_uri\",\"type\",\"details\",\"tags\",\"story\",\"english_title\",\"_highlightResult\"]",
            "utf-8"
        )
        val params = "attributesToRetrieve=$attributes&hitsPerPage=50&page=0&query=$encodedQuery"
        val payload = JSONObject().put("params", params)
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        val res = app.post(algoliaUrl(indexName), requestBody = body, headers = getAlgoliaHeaders()).text

        val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
        val hits = json.optJSONArray("hits") ?: JSONArray()

        val results = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i)
            val title = obj.optString("name")
            if (title.isNullOrEmpty()) continue
            val poster = obj.optString("poster_uri")
            val animeId = obj.optString("objectID")
            val fullData = URLEncoder.encode(obj.toString(), "utf-8")
            val url = "$mainUrl/watch/${URLEncoder.encode(animeId, "utf-8")}?data=$fullData"
            results.add(newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster })
        }
        return@withContext results
    }


    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        logd("====== LOAD START ======")
        logd("Input URL: $url")
        val animeId = URLDecoder.decode(url.substringAfterLast('/').substringBefore('?'), "utf-8")
        logd("Extracted animeId: $animeId")

        val encodedData = url.substringAfter("?data=", "")
        val animeJson = if (encodedData.isNotEmpty()) {
            try {
                val decoded = URLDecoder.decode(encodedData, "utf-8")
                logd("Decoded data from URL parameter:\n$decoded")
                JSONObject(decoded)
            } catch (e: Exception) {
                logd("Error decoding data: ${e.message}")
                JSONObject()
            }
        } else {
            JSONObject()
        }

        val episodes = fetchEpisodes(animeId)
        logd("Found ${episodes.size} episodes.")

        val poster = animeJson.optString("poster_uri")
        val epList = episodes.map { info ->
            val dataStr = "$animeId|${info.id}"
            newEpisode(data = dataStr) {
                name = info.name ?: "الحلقة ${info.number}"
                episode = info.number
                posterUrl = info.imageUrl ?: poster
            }
        }

        val title = animeJson.optString("name", animeId)
        val details = animeJson.optJSONObject("details") ?: JSONObject()
        val plot = animeJson.optString("story").ifEmpty {
            animeJson.optJSONObject("_highlightResult")?.optJSONObject("story")?.optString("value")?.replace(Regex("</?em>"), "")
        }
        val year = details.optString("year").toIntOrNull()
        val status = if (details.optString("state") == "مكتمل") ShowStatus.Completed else ShowStatus.Ongoing
        val tagsArray = animeJson.optJSONArray("tags")
        val tags = if (tagsArray != null) (0 until tagsArray.length()).map { tagsArray.getString(it) } else emptyList()

        logd("Building LoadResponse for '$title' with ${epList.size} episodes.")
        logd("====== LOAD END ======")

        return@withContext newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.showStatus = status
            this.tags = tags
            addEpisodes(DubStatus.Subbed, epList)
        }
    }

    private suspend fun fetchEpisodes(animeId: String): List<EpisodeInfo> = withContext(Dispatchers.IO) {
        val encodedAnimeId = URLEncoder.encode(animeId, "utf-8")
        val collectionPath = "anime_list/$encodedAnimeId/episodes"
        val baseUrl = firestoreDocUrl(collectionPath)
        val list = ArrayList<EpisodeInfo>()

        try {
            logd("Fetching episodes from Firestore (auto-paginated): $baseUrl")

            var nextPage: String? = null
            var pageCount = 0
            do {
                val url = if (nextPage != null) "$baseUrl?pageToken=$nextPage&pageSize=300" else "$baseUrl?pageSize=300"
                val res = app.get(url).text
                val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
                val docs = json.optJSONArray("documents") ?: JSONArray()

                for (i in 0 until docs.length()) {
                    val doc = docs.getJSONObject(i)
                    val nameFull = doc.optString("name")
                    val docId = nameFull.substringAfterLast('/')
                    val fields = doc.optJSONObject("fields") ?: JSONObject()
                    val epName = fields.optJSONObject("name")?.optString("stringValue")
                    val number = fields.optJSONObject("number")?.optString("integerValue")?.toIntOrNull() ?: (list.size + 1)
                    val image = fields.optJSONObject("image")?.optString("stringValue")
                    list.add(EpisodeInfo(docId, epName, number, image))
                }

                nextPage = json.optString("nextPageToken", null)
                pageCount++
            } while (!nextPage.isNullOrEmpty())

            list.sortBy { it.number }
            logd("Fetched total ${list.size} episodes in $pageCount page(s).")
            return@withContext list
        } catch (e: Exception) {
            logd("Error fetching episodes: ${e.message}")
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private fun firestoreDocUrl(path: String): String = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/$path"


    private suspend fun fetchServersForEpisode(animeId: String, episodeId: String): List<ServerModel> = withContext(Dispatchers.IO) {
        val encodedAnimeId = URLEncoder.encode(animeId, "utf-8")
        val encodedEpisodeId = URLEncoder.encode(episodeId, "utf-8")

        try {
            val docPath = "anime_list/$encodedAnimeId/episodes/$encodedEpisodeId/servers2/all_servers"
            logd("Trying servers2 doc: $docPath")
            val res = app.get(firestoreDocUrl(docPath)).text
            val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
            val fields = json.optJSONObject("fields")
            if (fields != null && fields.has("servers")) {
                val arr = fields.getJSONObject("servers").optJSONObject("arrayValue")?.optJSONArray("values") ?: JSONArray()
                val list = ArrayList<ServerModel>()
                for (i in 0 until arr.length()) {
                    val map = arr.getJSONObject(i).getJSONObject("mapValue").getJSONObject("fields")
                    val name = map.optJSONObject("name")?.optString("stringValue")
                    val quality = map.optJSONObject("quality")?.optString("stringValue")
                    val link = map.optJSONObject("link")?.optString("stringValue")
                    val orig = map.optJSONObject("original_link")?.optString("stringValue")
                    val openBrowser = map.optJSONObject("open_browser")?.optBoolean("booleanValue") ?: false
                    if (!name.isNullOrEmpty() && !link.isNullOrEmpty()) {
                        list.add(ServerModel(name, link, quality, orig, openBrowser))
                    }
                }
                if (list.isNotEmpty()) {

                    list.sortByDescending { getQualityAsInt(it.quality) }
                    return@withContext list
                }
            }
        } catch (e: Exception) {
            logd("fetchServersForEpisode: servers2 doc failed: ${e.message}")
        }

        try {
            val collPath = "anime_list/$encodedAnimeId/episodes/$encodedEpisodeId/servers"
            logd("Trying servers collection: $collPath")
            val res = app.get(firestoreDocUrl(collPath)).text
            val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
            val docs = json.optJSONArray("documents") ?: JSONArray()
            val list = ArrayList<ServerModel>()
            for (i in 0 until docs.length()) {
                val fields = docs.getJSONObject(i).optJSONObject("fields") ?: JSONObject()
                val name = fields.optJSONObject("name")?.optString("stringValue")
                val quality = fields.optJSONObject("quality")?.optString("stringValue")
                val link = fields.optJSONObject("link")?.optString("stringValue")
                val orig = fields.optJSONObject("original_link")?.optString("stringValue")
                val openBrowser = fields.optJSONObject("open_browser")?.optBoolean("booleanValue") ?: false
                val visible = fields.optJSONObject("visible")?.optBoolean("booleanValue") ?: true
                if (!name.isNullOrEmpty() && !link.isNullOrEmpty() && visible) {
                    list.add(ServerModel(name, link, quality, orig, openBrowser))
                }
            }

            list.sortByDescending { getQualityAsInt(it.quality) }
            return@withContext list
        } catch (e: Exception) {
            logd("fetchServersForEpisode: servers collection failed: ${e.message}")
            e.printStackTrace()
            return@withContext emptyList()
        }
    }


    private suspend fun getServerWords(serverName: String): ServerWords? = withContext(Dispatchers.IO) {
        if (serverWordsCache.containsKey(serverName)) return@withContext serverWordsCache[serverName]
        try {
            val path = "Settings/servers/servers/${URLEncoder.encode(serverName, "utf-8")}"
            val res = app.get(firestoreDocUrl(path)).text
            val f = JSONObject(res).optJSONObject("fields") ?: JSONObject()
            val sw = ServerWords(
                name = serverName,
                word1 = f.optJSONObject("word1")?.optString("stringValue"),
                word2 = f.optJSONObject("word2")?.optString("stringValue"),
                word3 = f.optJSONObject("word3")?.optString("stringValue"),
                word4 = f.optJSONObject("word4")?.optString("stringValue")
            )
            serverWordsCache[serverName] = sw
            return@withContext sw
        } catch (e: Exception) {
            logd("getServerWords failed for $serverName: ${e.message}")
            return@withContext null
        }
    }

    private suspend fun getFinalRedirectUrl(urlIn: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            app.get(urlIn, allowRedirects = true).url
        } catch (e: Exception) {
            logd("getFinalRedirectUrl ERROR for $urlIn: ${e.message}")
            null
        }
    }

    private suspend fun getFinalDownloadUrl(urlIn: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            app.get(urlIn, allowRedirects = false).headers["Location"]
        } catch (e: Exception) {
            logd("getFinalDownloadUrl ERROR for $urlIn: ${e.message}")
            null
        }
    }

    private suspend fun resolveServerModel(server: ServerModel): String? = withContext(Dispatchers.IO) {
        val name = server.name ?: return@withContext null
        val link = server.link ?: return@withContext null
        val words = getServerWords(name)

        try {
            when (name.uppercase(Locale.getDefault())) {
                "MF", "ST", "MG" -> {
                    return@withContext link
                }
                "KF" -> {
                    if (words?.word1 == null || words.word2 == null) return@withContext null
                    val res = try { app.get(link).text } catch (e: Exception) { "" }
                    if (res.isBlank()) return@withContext null
                    return@withContext "https://${res.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("amp;", "")}"
                }
                "PD" -> {
                    val html = try { app.get(link).text } catch (e: Exception) { "" }
                    val og = Regex("""<meta property="og:video" content="([^"]+)""").find(html)?.groupValues?.get(1)
                    if (!og.isNullOrEmpty()) return@withContext og.replace("&amp;", "&")
                    val m = Regex("""href="(/u/[A-Za-z0-9_-]+)"[^>]*>\s*(?:Download|تحميل|download)""", RegexOption.IGNORE_CASE).find(html)
                    if (m != null) {
                        val rel = m.groupValues[1]
                        val full = try { URL(link).let { base -> URL(base, rel).toString() } } catch (_: Exception) { rel }
                        return@withContext full
                    }
                    val m2 = Regex("(/u/[A-Za-z0-9_-]+)").find(html)
                    if (m2 != null) {
                        val rel = m2.groupValues[1]
                        val full = try { URL(link).let { base -> URL(base, rel).toString() } } catch (_: Exception) { rel }
                        return@withContext full
                    }
                    val abs = Regex("""https?://pixeldrain\.com/u/[A-Za-z0-9_-]+""").find(html)?.value
                    if (!abs.isNullOrEmpty()) return@withContext abs
                    return@withContext link
                }
                "VT" -> {
                    if (words?.word1 == null || words.word2 == null || words.word3 == null || words.word4 == null) return@withContext null
                    val res1 = try { app.get(link).text } catch (e: Exception) { "" }
                    val part1 = res1.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("\">", "").trim()
                    val newLink = "https://vidtube.one$part1"
                    val res2 = try { app.get(newLink).text } catch (e: Exception) { "" }
                    return@withContext "https://${res2.substringAfter(words.word3!!).substringBefore(words.word4!!)}"
                }
                "AR" -> {
                    return@withContext getFinalRedirectUrl(link)
                }
                "WC" -> {
                    return@withContext getFinalDownloadUrl(link)
                }
                "BU" -> {
                    return@withContext null
                }
                "QI" -> {
                    return@withContext server.originalLink ?: link
                }
                else -> {
                    if (words?.word1 == null || words.word2 == null) return@withContext null
                    val res = try { app.get(link).text } catch (e: Exception) { "" }
                    if (res.isBlank()) return@withContext null
                    return@withContext res.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("amp;", "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        logd("====== LOADLINKS START ======")
        logd("Input data: $data")
        try {
            val parts = data.split('|')
            if (parts.size < 2) {
                logd("loadLinks: invalid data format: $data")
                return@withContext false
            }
            var rawAnimeId = parts[0].trim()
            val episodeId = parts[1].trim()

            if (rawAnimeId.startsWith("http", ignoreCase = true) || rawAnimeId.contains(mainUrl)) {
                try {
                    val pathPart = rawAnimeId.substringAfterLast('/').substringBefore('?')
                    rawAnimeId = URLDecoder.decode(pathPart, "utf-8").trim()
                    logd("Normalized animeId from url -> $rawAnimeId")
                } catch (e: Exception) {
                    logd("Failed to normalize animeId from URL $rawAnimeId : ${e.message}")
                }
            }

            val animeId = rawAnimeId
            logd("Using animeId='$animeId', episodeId='$episodeId'")

            val servers = fetchServersForEpisode(animeId, episodeId)

            val sortedServers = servers.sortedWith(compareByDescending<ServerModel> { getQualityAsInt(it.quality) }
                .thenBy {
                    when(it.name?.uppercase(Locale.getDefault())) {
                        "PD" -> 0          // أعلى أولوية
                        "PD EU TEST" -> 1  // بعده مباشرة
                        else -> 2          // باقي السيرفرات حسب الجودة
                    }
                }
            )

            logd("Fetched and sorted ${sortedServers.size} servers for this episode: ${sortedServers.map { "${it.name}-${it.quality}" }}")

            val resolvedList = mutableListOf<Pair<ServerModel, String>>()
            for (server in sortedServers) {   // <-- استخدم sortedServers هنا بدلاً من servers
                try {
                    logd("Resolving server: ${server.name} -> ${server.link}")
                    val resolvedUrl = resolveServerModel(server)
                    if (!resolvedUrl.isNullOrBlank()) {
                        val logUrl = if (resolvedUrl.length > 200) resolvedUrl.take(200) + "..." else resolvedUrl
                        logd("Resolved ${server.name} -> $logUrl")
                        resolvedList.add(Pair(server, resolvedUrl))
                    } else {
                        logd("Could not resolve ${server.name} (null/blank)")
                    }
                } catch (e: Exception) {
                    logd("Exception while resolving ${server.name}: ${e.message}")
                }
            }

            logd("Fetched and sorted ${servers.size} servers for this episode: ${servers.map { "${it.name}-${it.quality}" }}")



            for ((server, finalUrl) in resolvedList) {
                if (finalUrl.trim().startsWith("<")) {
                    logd("Skipping invalid resolved URL for ${server.name} (starts with HTML)")
                    continue
                }

                val serverName = server.name ?: "Server"

                if (serverName.equals("PD", ignoreCase = true)) {

                    val id = finalUrl.substringAfterLast("/u/", finalUrl.substringAfterLast("/api/file/"))
                    val newUrl = "https://pd.1drv.eu.org/$id"

                    logd("Creating PD EU Test extractor: $newUrl")

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "PD EU Test",
                            url = newUrl
                        ) {
                            referer = mainUrl
                        }
                    )

                    try {
                        logd("Calling loadExtractor for PD -> $finalUrl")
                        loadExtractor(
                            finalUrl,
                            mainUrl, // Referer
                            subtitleCallback,
                            callback
                        )
                    } catch (e: Exception) {
                        logd("Error invoking loadExtractor for PD: ${e.message}")
                    }

                    continue
                }


                if (serverName.uppercase(Locale.getDefault()) == "KF") {
                    logd("Manually adding KF direct link.")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = serverName,
                            url = finalUrl,
                        ) {
                            referer = "https://krakenfiles.com/"
                            quality = getQualityFromName(server.quality)
                        }
                    )
                } else {
                    try {
                        logd("Calling loadExtractor for $serverName -> $finalUrl")
                        loadExtractor(
                            finalUrl,
                            mainUrl, // Referer
                            subtitleCallback,
                            callback
                        )
                    } catch (e: Exception) {
                        logd("Error invoking loadExtractor for $serverName: ${e.message}")
                    }
                }
            }


            logd("====== LOADLINKS END ======")
            return@withContext true
        } catch (e: Exception) {
            logd("Error in loadLinks: ${e.message}")
            e.printStackTrace()
            logd("====== LOADLINKS END WITH ERROR ======")
            return@withContext false
        }
    }
}