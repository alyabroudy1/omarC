package com.cloudstream.shared.webview

/**
 * Shared URL classification logic for video sniffing.
 *
 * Centralizes all URL validation rules used by both CfBypassEngine and VideoSnifferEngine,
 * eliminating duplication between WebViewEngine and SnifferExtractor.
 */
object VideoUrlClassifier {

    /** Domains whose streams are DRM-protected and cannot be played in ExoPlayer. */
    private val DRM_DOMAIN_KEYWORDS = listOf("mbc")

    /** File extensions that indicate DRM manifests (unplayable without Widevine). */
    private val DRM_EXTENSIONS = listOf(".mpd")

    /** Segment/asset patterns that should never be captured as video links. */
    private val SEGMENT_EXTENSIONS = listOf(".ts", ".key", ".png", ".jpg", ".gif", ".css", ".js")
    private val SEGMENT_KEYWORDS = listOf("favicon")

    /**
     * Determines if a URL is an extractable video stream.
     * Rejects: blacklisted URLs, DRM-protected streams, and segment/asset files.
     * Accepts: .m3u8, .mp4, .mkv, .webm, blob: URLs, and likely HLS manifests
     * served with non-standard extensions (e.g., master.txt, play.php).
     */
    fun isVideoUrl(url: String): Boolean {
        if (isBlacklisted(url)) return false

        // Blob URLs (WebRTC/MediaSource) are always considered video
        if (url.startsWith("blob:")) return true

        // Reject DRM-protected streams (DASH manifests, known DRM CDN domains)
        if (isDrmProtected(url)) return false

        // Reject segments and non-video assets
        if (isSegmentOrAsset(url)) return false

        // Check for video file patterns
        return url.contains(".m3u8", ignoreCase = true) ||
               url.contains(".mp4", ignoreCase = true) ||
               url.contains(".mkv", ignoreCase = true) ||
               url.contains(".webm", ignoreCase = true) ||
               isLikelyHlsManifest(url)
    }

    /** Check if a URL belongs to a DRM-protected domain or uses a DRM format. */
    fun isDrmProtected(url: String): Boolean {
        val lower = url.lowercase()
        return DRM_EXTENSIONS.any { lower.contains(it) } ||
               DRM_DOMAIN_KEYWORDS.any { lower.contains(it) }
    }

    /** Check if a URL is a segment/asset that should be filtered out. */
    fun isSegmentOrAsset(url: String): Boolean {
        val lower = url.lowercase()
        return SEGMENT_EXTENSIONS.any { lower.contains(it) } ||
               SEGMENT_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Analytics/telemetry hosts. These fire constantly on embed pages and must never be mistaken
     * for a stream: a single false positive satisfies ExitCondition.VideoFound and ends the sniff
     * before the real manifest is requested (Upnshare returned a mc.yandex.com beacon as the
     * "video" for exactly this reason).
     */
    private val TRACKER_HOSTS = listOf(
        "mc.yandex.", "yandex.ru/metrika", "yandex.com/metrika", "/metrika/",
        "google-analytics.com", "googletagmanager.com", "doubleclick.net",
        "scorecardresearch.com", "facebook.com/tr", "/collect?", "/gtag/",
        "imasdk.googleapis.com", "2mdn.net"
    )

    /** Check if a URL is a known analytics/tracking endpoint to ignore. */
    fun isBlacklisted(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return lowerUrl.contains("/ping.gif") ||
               lowerUrl.contains("/analytics") ||
               lowerUrl.contains("/google-analytics") ||
               lowerUrl.contains("favicon.ico") ||
               TRACKER_HOSTS.any { lowerUrl.contains(it) }
    }

    /**
     * Detects HLS manifest URLs served without the standard .m3u8 extension.
     * Many embed CDNs serve master playlists as .txt, .php, or with URL rewriting
     * (e.g., /hls3/.../master.txt) to bypass CORS restrictions on .m3u8 files.
     */
    fun isLikelyHlsManifest(url: String): Boolean {
        val lower = url.lowercase()
        // Must contain HLS path patterns
        if (!lower.contains("/hls") && !lower.contains("/hls3")) return false
        // Not already detected as a standard video format
        if (lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".webm")) return false
        // Reject segments and non-video assets
        if (isSegmentOrAsset(url)) return false
        return true
    }

    /**
     * Preview/thumbnail endpoints that live on video CDNs and carry no image extension, so
     * [isSegmentOrAsset] cannot see them.
     *
     * `iv.okcdn.ru/getVideoPreview?id=…&type=39&fn=vid_w` is a JPEG served from the same host family
     * as the stream, and it is requested *before* the stream — hand it to ExoPlayer and playback dies
     * on an image. (Seen 2026-07-30 in a CimaNow VK server capture.)
     */
    private val PREVIEW_KEYWORDS = listOf(
        "getvideopreview", "/preview", "thumb", "poster", "storyboard", "sprite", "/vid_"
    )

    /** True for thumbnail/preview endpoints that are not playable media. */
    fun isPreviewAsset(url: String): Boolean {
        val lower = url.lowercase()
        return PREVIEW_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Whether a URL **already identified as media by a network sniffer** is worth handing to a player.
     *
     * Deliberately a *rejection* filter, not a recognition filter, and that distinction is the whole
     * point of it. [isVideoUrl] answers "does this look like a video?" from the URL text alone, so it
     * needs an extension or an `/hls/` path — and plenty of real streams have neither. VK serves its
     * ladder as `https://vk6-3.vkuser.net/?expires=…&type=1&…`: no extension, no path, nothing to
     * recognise. Filtering sniffer captures through [isVideoUrl] therefore throws away exactly the
     * streams that only a sniffer could have found (2026-07-30: four VK captures discarded, the whole
     * run produced no link).
     *
     * The caller has already decided this request is media — by host, by `Content-Type`, or by
     * watching a player fetch it. All this does is drop the things that are media-adjacent but
     * unplayable: segments, thumbnails, trackers, and DASH manifests ExoPlayer cannot decrypt.
     */
    fun isPlayableCapture(url: String): Boolean {
        if (url.isBlank()) return false
        if (isBlacklisted(url)) return false
        if (isSegmentOrAsset(url)) return false
        if (isPreviewAsset(url)) return false
        if (isDrmProtected(url)) return false
        return true
    }

    /**
     * Ad, consent and popunder networks that serve their payload in an iframe.
     *
     * An ad slot and a player embed are both third-party documents in a subframe, so anything hunting
     * for embeds has to reject these or it will hand an ad frame to an extractor. Drawn from what a
     * CimaNow watch page actually loads (2026-07-30): Google's ad and Funding-Choices endpoints, and
     * the `luugy.com` popunder the page gates playback on.
     */
    private val AD_FRAME_HOSTS = listOf(
        "googlesyndication.com", "doubleclick.net", "googleadservices.com",
        "fundingchoicesmessages.google.com", "google.com/ads", "adservice.google",
        "luugy.com", "popads.net", "popcash", "adsterra", "exoclick", "juicyads",
        "propellerads", "mgid.com", "taboola.com", "outbrain.com", "onclickads",
        "hilltopads", "adnxs.com", "criteo", "yandex.ru/ads", "vk.ru/js", "top-fwz1.mail.ru"
    )

    /** True for iframe documents that are advertising/consent frames rather than player embeds. */
    fun isLikelyAdFrame(url: String): Boolean {
        val lower = url.lowercase()
        if (isBlacklisted(lower)) return true
        return AD_FRAME_HOSTS.any { lower.contains(it) }
    }

    /** Markers that identify a quality-specific (variant) playlist rather than a master. */
    private val QUALITY_MARKERS = listOf(
        "2160", "1440", "1080", "720", "480", "360", "240", "144",
        "low", "mobile", "chunklist"
    )

    /**
     * Determines if a URL is an HLS Master M3U8 playlist.
     * Master playlists contain multiple sub-streams (qualities) rather than chunks.
     * Also detects non-standard manifests (e.g., master.txt) via [isLikelyHlsManifest].
     */
    fun isMasterM3u8(url: String): Boolean {
        val lower = url.lowercase()

        // Standard .m3u8 master detection
        if (lower.contains(".m3u8")) {
            val path = lower.substringBefore('?').substringBefore('#')
            val filename = path.substringAfterLast('/')

            // "master"/"manifest" is only ever a master playlist's name — trust it outright.
            if (filename.contains("master") || filename.contains("manifest")) return true

            // A quality marker means this is a variant, and that must outrank the name: scdns.io
            // names its variants "<id>_<quality>_playlist.m3u8" (e.g. 160_hd1080b_playlist.m3u8),
            // so matching "playlist.m3u8" first classified every FaselHD variant as a master.
            // Scoped to the last two path segments because tokenised CDN paths are full of ids and
            // unix timestamps whose digits trip these markers when the whole URL is searched.
            val scope = path.split('/').takeLast(2).joinToString("/")
            if (QUALITY_MARKERS.any { scope.contains(it) }) return false

            return true
        }

        // Non-standard extension: check if it's a likely HLS manifest with "master" in the name
        if (isLikelyHlsManifest(lower) && lower.contains("master")) return true

        return false
    }
}
