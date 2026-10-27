package com.faselHDV2

import com.cloudstream.shared.webview.VideoUrlClassifier

/**
 * Chooses which extracted iframe/player URLs deserve a WebView session.
 *
 * FaselHD's detail page contains explicit `video_player?player_token=...` servers and, in the same
 * DOM, a generic `/?p=<id>` link. The generic link redirects back to the detail page, so sniffing it
 * can only waste a full session and end with the page URL itself being reported as a video.
 *
 * The policy is intentionally conservative: when explicit player URLs exist, generic detail-page
 * URLs are dropped; everything unrecognised is kept and ordered after the explicit players. When no
 * explicit player exists, every candidate is preserved so unusual layouts still work.
 */
internal object FaselHDV2WatchUrlPolicy {

    private val EXPLICIT_PLAYER_MARKERS = listOf(
        "video_player",
        "player_token",
        "/embed",
        "/e/",
        "player.",
        "/player",
        "/watch",
        "play=",
        "stream"
    )

    fun select(candidates: List<String>): List<String> {
        val unique = candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (unique.isEmpty()) return emptyList()

        val players = unique.filter(::isExplicitPlayer)
        if (players.isEmpty()) return unique

        val remainder = unique
            .filterNot(::isExplicitPlayer)
            .filterNot(VideoUrlClassifier::isLikelyPageUrl)
        return players + remainder
    }

    private fun isExplicitPlayer(url: String): Boolean =
        EXPLICIT_PLAYER_MARKERS.any { url.contains(it, ignoreCase = true) }
}
