package com.youtube.innertube

import com.lagradost.api.Log
import com.fasterxml.jackson.databind.JsonNode

/**
 * Parses InnerTube API JSON responses into domain models.
 * Single responsibility: JSON traversal and field extraction.
 * 
 * Supports both classic renderer format (videoRenderer, playlistRenderer)
 * and newer lockupViewModel format used by mobile YouTube.
 */
object InnerTubeParser {

    private const val TAG = "InnerTubeParser"

    // ==================== SEARCH ====================

    /**
     * Parse search API response into a list of videos and playlists.
     */
    fun parseSearchResults(json: JsonNode): List<YouTubeSearchResult> {
        val results = mutableListOf<YouTubeSearchResult>()
        
        // Search results live under:
        // contents.twoColumnSearchResultsRenderer.primaryContents.sectionListRenderer.contents[].itemSectionRenderer.contents[]
        val sections = json.path("contents")
            .path("twoColumnSearchResultsRenderer")
            .path("primaryContents")
            .path("sectionListRenderer")
            .path("contents")
        
        for (section in sections) {
            val items = section.path("itemSectionRenderer").path("contents")
            for (item in items) {
                parseSearchItem(item)?.let { results.add(it) }
            }
        }
        
        Log.d(TAG, "Parsed ${results.size} search results")
        return results
    }

    /**
     * Parse browse API response (e.g., trending) into categorized results.
     */
    fun parseBrowseResults(json: JsonNode): YouTubeBrowseResult {
        val items = mutableListOf<YouTubeSearchResult>()
        
        // Trending structure:
        // contents.twoColumnBrowseResultsRenderer.tabs[].tabRenderer.content.sectionListRenderer.contents[].itemSectionRenderer.contents[].shelfRenderer.content.expandedShelfContentsRenderer.items[]
        // OR contents.twoColumnBrowseResultsRenderer.tabs[].tabRenderer.content.richGridRenderer.contents[].richItemRenderer.content
        val tabs = json.path("contents")
            .path("twoColumnBrowseResultsRenderer")
            .path("tabs")
        
        for (tab in tabs) {
            val tabContent = tab.path("tabRenderer").path("content")
            
            // Try sectionListRenderer path
            val sections = tabContent.path("sectionListRenderer").path("contents")
            for (section in sections) {
                collectVideosFromNode(section, items)
            }
            
            // Try richGridRenderer path
            val richGrid = tabContent.path("richGridRenderer").path("contents")
            for (richItem in richGrid) {
                val content = richItem.path("richItemRenderer").path("content")
                parseSearchItem(content)?.let { items.add(it) }
            }
        }
        
        // Also check single-column layout (used for some browse pages)
        val singleColumn = json.path("contents")
            .path("singleColumnBrowseResultsRenderer")
            .path("tabs")
        for (tab in singleColumn) {
            val sections = tab.path("tabRenderer").path("content")
                .path("sectionListRenderer").path("contents")
            for (section in sections) {
                collectVideosFromNode(section, items)
            }
        }
        
        Log.d(TAG, "Parsed ${items.size} browse items")
        return YouTubeBrowseResult("Trending", items)
    }

    // ==================== VIDEO DETAILS ====================

    /**
     * Parse video details from the "next" endpoint.
     */
    fun parseVideoDetails(json: JsonNode): YouTubeVideo? {
        // Try videoPrimaryInfoRenderer path
        val primaryInfo = findNode(json, "videoPrimaryInfoRenderer")
        val secondaryInfo = findNode(json, "videoSecondaryInfoRenderer")
        
        // Also try currentVideoEndpoint
        val videoId = json.path("currentVideoEndpoint")
            .path("watchEndpoint")
            .path("videoId")
            .textValue()
        
        if (videoId == null) {
            Log.w(TAG, "No videoId found in video details")
            return null
        }
        
        val title = primaryInfo?.path("title")?.path("runs")?.get(0)?.path("text")?.textValue()
            ?: findTextContent(json, "title")
            ?: "YouTube Video"
        
        val channelName = secondaryInfo?.path("owner")
            ?.path("videoOwnerRenderer")
            ?.path("title")?.path("runs")?.get(0)?.path("text")?.textValue()
        
        val viewCount = primaryInfo?.path("viewCount")
            ?.path("videoViewCountRenderer")
            ?.path("viewCount")?.path("simpleText")?.textValue()
        
        val publishedTime = primaryInfo?.path("dateText")?.path("simpleText")?.textValue()
        
        return YouTubeVideo(
            id = videoId,
            title = title,
            thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg",
            channelName = channelName,
            viewCount = viewCount,
            publishedTime = publishedTime
        )
    }

    /**
     * Parse video description from video details response.
     */
    fun parseVideoDescription(json: JsonNode): String? {
        val secondaryInfo = findNode(json, "videoSecondaryInfoRenderer")
        val descRuns = secondaryInfo?.path("attributedDescription")?.path("content")?.textValue()
        if (descRuns != null) return descRuns
        
        // Fallback: description.runs[].text joined
        val runs = secondaryInfo?.path("description")?.path("runs")
        if (runs != null && runs.isArray) {
            return runs.mapNotNull { it.path("text").textValue() }.joinToString("")
        }
        return null
    }

    // ==================== PLAYLIST ====================

    /**
     * Parse playlist header (title, thumbnail).
     */
    fun parsePlaylistHeader(json: JsonNode): YouTubePlaylist? {
        val header = findNode(json, "playlistHeaderRenderer")
        if (header == null) {
            Log.w(TAG, "No playlistHeaderRenderer found")
            return null
        }
        
        val playlistId = header.path("playlistId").textValue() ?: return null
        val title = header.path("title").path("simpleText").textValue()
            ?: header.path("title").path("runs")?.get(0)?.path("text")?.textValue()
            ?: "Playlist"
        val videoCount = header.path("numVideosText").path("runs")?.get(0)?.path("text")?.textValue()
        
        return YouTubePlaylist(
            id = playlistId,
            title = title,
            videoCount = videoCount
        )
    }

    /**
     * Parse playlist videos from browse response.
     */
    fun parsePlaylistVideos(json: JsonNode): List<YouTubeVideo> {
        val videos = mutableListOf<YouTubeVideo>()
        
        // Playlist videos under:
        // contents.twoColumnBrowseResultsRenderer.tabs[].tabRenderer.content.sectionListRenderer.contents[].itemSectionRenderer.contents[].playlistVideoListRenderer.contents[]
        val renderers = mutableListOf<JsonNode>()
        findAllNodes(json, "playlistVideoRenderer", renderers)
        findAllNodes(json, "playlistPanelVideoRenderer", renderers)
        
        for (renderer in renderers) {
            parsePlaylistVideoRenderer(renderer)?.let { videos.add(it) }
        }
        
        Log.d(TAG, "Parsed ${videos.size} playlist videos")
        return videos
    }

    // ==================== INTERNAL PARSING ====================

    private fun parseSearchItem(item: JsonNode): YouTubeSearchResult? {
        // Classic format
        if (item.has("videoRenderer")) {
            return parseVideoRenderer(item.get("videoRenderer"))?.let { 
                if (!it.isShort) YouTubeSearchResult.Video(it) else null
            }
        }
        if (item.has("playlistRenderer")) {
            return parsePlaylistRenderer(item.get("playlistRenderer"))?.let {
                YouTubeSearchResult.Playlist(it)
            }
        }
        
        // New lockupViewModel format
        if (item.has("lockupViewModel")) {
            return parseLockupViewModel(item.get("lockupViewModel"))
        }
        
        // Shelf/grid containers — recurse into them
        if (item.has("shelfRenderer")) {
            val shelf = item.get("shelfRenderer").path("content")
            val expanded = shelf.path("expandedShelfContentsRenderer").path("items")
            if (expanded.isArray) {
                for (child in expanded) {
                    return parseSearchItem(child) ?: continue
                }
            }
            val horizontal = shelf.path("horizontalListRenderer").path("items")
            if (horizontal.isArray) {
                for (child in horizontal) {
                    return parseSearchItem(child) ?: continue
                }
            }
        }
        
        // Grid renderer
        if (item.has("gridVideoRenderer")) {
            return parseVideoRenderer(item.get("gridVideoRenderer"))?.let {
                if (!it.isShort) YouTubeSearchResult.Video(it) else null
            }
        }
        
        return null
    }

    private fun collectVideosFromNode(node: JsonNode, list: MutableList<YouTubeSearchResult>) {
        val items = node.path("itemSectionRenderer").path("contents")
        for (item in items) {
            parseSearchItem(item)?.let { list.add(it) }
        }
        
        // Shelf renderer
        val shelfContent = node.path("shelfRenderer").path("content")
        val expandedItems = shelfContent.path("expandedShelfContentsRenderer").path("items")
        for (item in expandedItems) {
            parseSearchItem(item)?.let { list.add(it) }
        }
        val horizontalItems = shelfContent.path("horizontalListRenderer").path("items")
        for (item in horizontalItems) {
            parseSearchItem(item)?.let { list.add(it) }
        }
    }

    // ==================== RENDERER PARSERS ====================

    /**
     * Parse a videoRenderer node into a YouTubeVideo.
     */
    private fun parseVideoRenderer(v: JsonNode): YouTubeVideo? {
        val videoId = v.path("videoId").textValue() ?: return null
        
        val title = v.path("title").path("runs")?.get(0)?.path("text")?.textValue()
            ?: v.path("title").path("simpleText").textValue()
            ?: return null
        
        val thumbnail = v.path("thumbnail").path("thumbnails")
            .lastOrNull()?.path("url")?.textValue()
        
        val duration = v.path("lengthText").path("simpleText").textValue()
            ?: v.path("lengthText").path("runs")?.get(0)?.path("text")?.textValue()
        
        val publishedTime = v.path("publishedTimeText").path("simpleText").textValue()
            ?: v.path("publishedTimeText").path("runs")?.get(0)?.path("text")?.textValue()
        
        val channelName = v.path("ownerText").path("runs")?.get(0)?.path("text")?.textValue()
            ?: v.path("longBylineText").path("runs")?.get(0)?.path("text")?.textValue()

        val viewCount = v.path("viewCountText").path("simpleText").textValue()
            ?: v.path("shortViewCountText").path("simpleText").textValue()
        
        val isShort = isShortVideo(v, title, duration)
        
        return YouTubeVideo(
            id = videoId,
            title = title,
            thumbnail = thumbnail,
            duration = duration,
            publishedTime = publishedTime,
            channelName = channelName,
            viewCount = viewCount,
            isShort = isShort
        )
    }

    /**
     * Parse a playlistRenderer node into a YouTubePlaylist.
     */
    private fun parsePlaylistRenderer(p: JsonNode): YouTubePlaylist? {
        val playlistId = p.path("playlistId").textValue() ?: return null
        
        val title = p.path("title").path("simpleText").textValue()
            ?: p.path("title").path("runs")?.get(0)?.path("text")?.textValue()
            ?: return null
        
        val thumbnail = p.path("thumbnails")?.get(0)?.path("thumbnails")
            ?.lastOrNull()?.path("url")?.textValue()
            ?: p.path("thumbnail").path("thumbnails")
                ?.lastOrNull()?.path("url")?.textValue()
        
        val videoCount = p.path("videoCount").textValue()
            ?: p.path("videoCountText").path("runs")?.get(0)?.path("text")?.textValue()
        
        val channelName = p.path("longBylineText").path("runs")?.get(0)?.path("text")?.textValue()
        
        return YouTubePlaylist(
            id = playlistId,
            title = title,
            thumbnail = thumbnail,
            videoCount = videoCount,
            channelName = channelName
        )
    }

    /**
     * Parse mobile-format lockupViewModel into either video or playlist.
     */
    private fun parseLockupViewModel(lvm: JsonNode): YouTubeSearchResult? {
        val contentType = lvm.path("contentType").textValue()
        val contentId = lvm.path("contentId").textValue() ?: return null
        
        val title = lvm.path("metadata")
            .path("lockupMetadataViewModel")
            .path("title").path("content").textValue()
        
        val imageNode = lvm.path("contentImage")
            .path("collectionThumbnailViewModel")
            .path("primaryThumbnail")
            .path("thumbnailViewModel").path("image").path("sources")
            .takeIf { it.isArray }
            ?: lvm.path("contentImage")
                .path("thumbnailViewModel").path("image").path("sources")
        
        val thumbnail = imageNode?.lastOrNull()?.path("url")?.textValue()
        
        // Duration from overlay
        val duration = lvm.path("contentImage")
            .path("thumbnailViewModel").path("overlays")
            ?.firstOrNull()
            ?.path("thumbnailOverlayTimeStatusViewModel")
            ?.path("text")?.path("content")?.textValue()
        
        return when (contentType) {
            "PLAYLIST", "COLLECTION" -> {
                val videoCount = lvm.path("metadata")
                    .path("lockupMetadataViewModel")
                    .path("metadata")?.path("content")?.textValue()
                YouTubeSearchResult.Playlist(YouTubePlaylist(
                    id = contentId,
                    title = title ?: "Playlist",
                    thumbnail = thumbnail,
                    videoCount = videoCount
                ))
            }
            else -> { // "VIDEO" or null
                val isShort = isShortVideo(lvm, title, duration)
                if (isShort) return null
                
                YouTubeSearchResult.Video(YouTubeVideo(
                    id = contentId,
                    title = title ?: "No Title",
                    thumbnail = thumbnail,
                    duration = duration,
                    isShort = false
                ))
            }
        }
    }

    /**
     * Parse playlistVideoRenderer into a YouTubeVideo.
     */
    private fun parsePlaylistVideoRenderer(v: JsonNode): YouTubeVideo? {
        val videoId = v.path("videoId").textValue() ?: return null
        
        val title = v.path("title").path("runs")?.get(0)?.path("text")?.textValue()
            ?: v.path("title").path("simpleText").textValue()
            ?: "Untitled"
        
        val thumbnail = v.path("thumbnail").path("thumbnails")
            ?.lastOrNull()?.path("url")?.textValue()
        
        val duration = v.path("lengthText").path("simpleText").textValue()
            ?: v.path("lengthText").path("runs")?.get(0)?.path("text")?.textValue()
        
        return YouTubeVideo(
            id = videoId,
            title = title,
            thumbnail = thumbnail,
            duration = duration
        )
    }

    // ==================== STREAMING DATA ====================

    /**
     * Parse streaming data from the /player API response.
     *
     * Returns a StreamingResult containing:
     * - hlsManifestUrl: for live streams (M3U8 with all qualities)
     * - muxedFormats: video+audio combined (itag 18=360p, 22=720p)
     * - adaptiveFormats: video-only + audio-only streams for DASH
     */
    fun parseStreamingData(json: JsonNode): StreamingResult {
        val streamingData = json.path("streamingData")
        if (streamingData.isMissingNode) {
            Log.w(TAG, "No streamingData in player response")
            return StreamingResult()
        }

        // Check for HLS manifest (live streams — M3U8 with all qualities)
        val hlsManifestUrl = streamingData.path("hlsManifestUrl").textValue()
        if (hlsManifestUrl != null) {
            Log.d(TAG, "Found HLS manifest: ${hlsManifestUrl.take(80)}")
        }

        // ── Muxed formats (video+audio combined) ──
        val muxedFormats = mutableListOf<YouTubeStreamFormat>()
        val muxedArray = streamingData.path("formats")
        if (muxedArray.isArray) {
            for (format in muxedArray) {
                parseMuxedFormat(format)?.let {
                    muxedFormats.add(it)
                    Log.d(TAG, "Muxed: itag=${it.itag} quality=${it.qualityLabel} mime=${it.mimeType}")
                }
            }
        }

        // ── Adaptive formats (video-only + audio-only) ──
        val adaptiveFormats = mutableListOf<YouTubeStreamFormat>()
        val adaptiveArray = streamingData.path("adaptiveFormats")
        if (adaptiveArray.isArray) {
            for (format in adaptiveArray) {
                parseAdaptiveFormat(format)?.let {
                    adaptiveFormats.add(it)
                    Log.d(TAG, "Adaptive: itag=${it.itag} ${it.qualityLabel ?: it.baseMimeType} codecs=${it.codecs}")
                }
            }
        }

        Log.d(TAG, "Parsed ${muxedFormats.size} muxed, ${adaptiveFormats.size} adaptive, hlsManifest=${hlsManifestUrl != null}")
        return StreamingResult(
            hlsManifestUrl = hlsManifestUrl,
            muxedFormats = muxedFormats,
            adaptiveFormats = adaptiveFormats
        )
    }

    /**
     * Parse a muxed format node (video+audio combined).
     * Skips formats without a direct URL (signatureCipher-protected).
     */
    private fun parseMuxedFormat(format: JsonNode): YouTubeStreamFormat? {
        val url = format.path("url").textValue() ?: return null
        return YouTubeStreamFormat(
            itag = format.path("itag").intValue(),
            url = url,
            mimeType = format.path("mimeType").textValue() ?: "video/mp4",
            qualityLabel = format.path("qualityLabel").textValue(),
            quality = format.path("quality").textValue(),
            width = format.path("width").intValue().takeIf { it > 0 },
            height = format.path("height").intValue().takeIf { it > 0 },
            bitrate = format.path("bitrate").longValue().takeIf { it > 0 },
            contentLength = format.path("contentLength").textValue()?.toLongOrNull()
        )
    }

    /**
     * Parse an adaptive format node (video-only or audio-only).
     * Extracts DASH-required fields: initRange, indexRange, codecs, duration.
     * Skips cipher-protected or formats missing required range data.
     */
    private fun parseAdaptiveFormat(format: JsonNode): YouTubeStreamFormat? {
        val itag = format.path("itag").intValue()
        val url = format.path("url").textValue()
        if (url == null) {
            val hasCipher = format.has("signatureCipher") || format.has("cipher")
            Log.d(TAG, "Adaptive itag=$itag SKIPPED: no direct URL (cipher=$hasCipher)")
            return null
        }

        // initRange and indexRange are required for DASH SegmentBase
        val initRange = buildRangeString(format.path("initRange"))
        val indexRange = buildRangeString(format.path("indexRange"))
        if (initRange == null || indexRange == null) {
            Log.d(TAG, "Adaptive itag=$itag SKIPPED: missing ranges (init=${initRange != null} index=${indexRange != null})")
            return null
        }

        // Extract codecs from compound mimeType: "video/mp4; codecs=\"avc1.64001F\""
        val rawMimeType = format.path("mimeType").textValue()
        if (rawMimeType == null) {
            Log.d(TAG, "Adaptive itag=$itag SKIPPED: no mimeType")
            return null
        }
        val codecs = extractCodecs(rawMimeType)

        return YouTubeStreamFormat(
            itag = itag,
            url = url,
            mimeType = rawMimeType,
            qualityLabel = format.path("qualityLabel").textValue(),
            quality = format.path("quality").textValue(),
            width = format.path("width").intValue().takeIf { it > 0 },
            height = format.path("height").intValue().takeIf { it > 0 },
            bitrate = format.path("bitrate").longValue().takeIf { it > 0 },
            contentLength = format.path("contentLength").textValue()?.toLongOrNull(),
            codecs = codecs,
            initRange = initRange,
            indexRange = indexRange,
            approxDurationMs = format.path("approxDurationMs").textValue()?.toLongOrNull()
        )
    }

    /** Build "start-end" range string from a JSON object with start/end fields. */
    private fun buildRangeString(rangeNode: JsonNode): String? {
        val start = rangeNode.path("start").textValue() ?: return null
        val end = rangeNode.path("end").textValue() ?: return null
        return "$start-$end"
    }

    /** Extract codecs value from compound mimeType like `video/mp4; codecs="avc1.64001F"`. */
    private fun extractCodecs(mimeType: String): String? {
        val match = Regex("""codecs="([^"]+)"""").find(mimeType)
        return match?.groupValues?.getOrNull(1)
    }

    // ==================== HELPERS ====================

    /**
     * Detect if a video is a YouTube Short.
     */
    private fun isShortVideo(node: JsonNode, title: String?, duration: String?): Boolean {
        // Title contains #Shorts
        if (title?.contains("#short", ignoreCase = true) == true) return true
        
        // Shorts badge
        val badges = node.path("badges")
        if (badges.isArray) {
            for (badge in badges) {
                if (badge.path("metadataBadgeRenderer").path("style").textValue() == "BADGE_STYLE_TYPE_SHORTS") {
                    return true
                }
            }
        }
        
        // Shorts overlay
        val overlays = node.path("thumbnailOverlays")
        if (overlays.isArray) {
            for (overlay in overlays) {
                if (overlay.has("thumbnailOverlayShortsStatusRenderer")) return true
            }
        }
        
        // Reel navigation endpoint (Shorts use reelWatchEndpoint)
        if (node.path("navigationEndpoint").has("reelWatchEndpoint")) return true
        
        // Duration under 61 seconds with single-part time
        if (duration != null) {
            val parts = duration.trim().split(":")
            if (parts.size == 1) {
                val seconds = parts[0].toIntOrNull() ?: 0
                if (seconds < 61) return true
            }
        }
        
        return false
    }

    /**
     * Recursively find a node by key name.
     */
    private fun findNode(node: JsonNode, name: String): JsonNode? {
        if (node.has(name)) return node.get(name)
        if (node.isArray) {
            for (item in node) {
                findNode(item, name)?.let { return it }
            }
        } else if (node.isObject) {
            for (field in node.fields()) {
                findNode(field.value, name)?.let { return it }
            }
        }
        return null
    }

    /**
     * Recursively find all nodes matching a key name.
     */
    private fun findAllNodes(node: JsonNode, name: String, list: MutableList<JsonNode>) {
        if (node.has(name)) {
            list.add(node.get(name))
        }
        if (node.isArray) {
            for (item in node) {
                findAllNodes(item, name, list)
            }
        } else if (node.isObject) {
            for (field in node.fields()) {
                findAllNodes(field.value, name, list)
            }
        }
    }

    /**
     * Try to extract text from common YouTube text patterns.
     */
    private fun findTextContent(node: JsonNode, key: String): String? {
        val target = findNode(node, key) ?: return null
        return target.path("simpleText").textValue()
            ?: target.path("runs")?.get(0)?.path("text")?.textValue()
            ?: target.path("content").textValue()
    }
}
