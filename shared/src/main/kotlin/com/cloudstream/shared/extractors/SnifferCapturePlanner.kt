package com.cloudstream.shared.extractors

import com.cloudstream.shared.webview.CapturedLinkData
import com.cloudstream.shared.webview.VideoUrlClassifier

/**
 * What the sniffer should hand to the player for a captured URL.
 *
 * The distinction matters because a capture is evidence that the URL was actually requested by the
 * page, but it says nothing about whether the URL is *playable*. A JS bridge can report an iframe,
 * a thumbnail and a detail-page URL in the same batch as the real manifest.
 */
enum class SnifferStreamKind {
    /** A master playlist. ExoPlayer can resolve its variants itself. */
    HLS_MASTER,

    /** A quality-specific playlist, identified by a quality marker in its filename. */
    HLS_VARIANT,

    /** An HLS manifest whose role (master or variant) cannot be known without fetching it. */
    HLS_MANIFEST,

    /** A directly playable progressive file (.mp4, .mkv, ...). */
    PROGRESSIVE,

    /** A captured media URL without a conventional extension. */
    VIDEO
}

data class PlannedSnifferLink(
    val capture: CapturedLinkData,
    val kind: SnifferStreamKind,
    val quality: Int?,
    val qualityLabel: String
)

data class RejectedSnifferCapture(
    val url: String,
    val reason: String
)

data class SnifferCapturePlan(
    val links: List<PlannedSnifferLink>,
    val rejected: List<RejectedSnifferCapture>
) {
    val isEmpty: Boolean get() = links.isEmpty()
}

/**
 * Turns raw WebView captures into a deterministic, playable set without making a network request.
 *
 * The sniffer's job ends when it has the URL the page itself used. Re-fetching a master to enumerate
 * its qualities used to add ~10 seconds per server, and on IP-pinned CDNs the second request was
 * rejected or timed out while the first, already-authorized capture was thrown away. Playback must
 * never depend on that optional second request.
 *
 * Rules, in priority order:
 *  1. A complete captured variant ladder is used as-is; it keeps the UI's explicit quality choices.
 *  2. Otherwise, a master playlist wins. It carries the whole ladder and is already authorized.
 *  3. Otherwise, a single captured variant is valid and keeps its filename-derived quality.
 *  4. Otherwise, use a progressive file or a media URL without an extension.
 *
 * Page URLs, iframes, thumbnails and segments are rejected before selection. This is deliberately a
 * pure planner: [SnifferExtractor] only translates its output into CloudStream links.
 */
object SnifferCapturePlanner {

    /**
     * A marker must not be part of a longer number: token ids and unix timestamps are full of digit
     * runs, and `1785270141` must not be read as an `720`/`1080` quality.
     */
    private val QUALITY_PATTERN = Regex("""(?<!\d)(2160|1440|1080|720|480|360|240|144)(?!\d)""")

    private val PROGRESSIVE_EXTENSIONS = listOf(".mp4", ".mkv", ".webm", ".mov", ".m4v")

    fun plan(captures: List<CapturedLinkData>): SnifferCapturePlan {
        val accepted = mutableListOf<PlannedSnifferLink>()
        val rejected = mutableListOf<RejectedSnifferCapture>()
        val seenUrls = mutableSetOf<String>()

        for (capture in captures) {
            val url = capture.url.trim()
            if (url.isBlank()) {
                rejected += RejectedSnifferCapture(capture.url, "blank URL")
                continue
            }
            if (!seenUrls.add(url)) {
                rejected += RejectedSnifferCapture(url, "duplicate capture")
                continue
            }
            if (!VideoUrlClassifier.isPlayableCapture(url)) {
                rejected += RejectedSnifferCapture(url, "not a playable media URL")
                continue
            }

            val quality = qualityOf(capture)
            val kind = kindOf(url, quality)
            accepted += PlannedSnifferLink(
                capture = capture.copy(url = url),
                kind = kind,
                quality = quality,
                qualityLabel = qualityLabel(kind, quality)
            )
        }

        val masters = accepted.filter { it.kind == SnifferStreamKind.HLS_MASTER }
        val variants = accepted
            .filter { it.kind == SnifferStreamKind.HLS_VARIANT }
            .distinctBy { it.quality ?: it.capture.url }
            .sortedByDescending { it.quality ?: -1 }
        val manifests = accepted.filter { it.kind == SnifferStreamKind.HLS_MANIFEST }
        val progressive = accepted.filter { it.kind == SnifferStreamKind.PROGRESSIVE }

        val selected = when {
            // A complete, self-describing ladder is the most useful thing to hand the UI: it keeps
            // explicit quality choices and still needs no second network request.
            variants.size >= 2 -> variants
            // A master carries every variant and is playable on its own.
            masters.isNotEmpty() -> masters.distinctBy { it.capture.url }
            // A single captured variant is valid; this is the case the old code discarded.
            variants.isNotEmpty() -> variants
            manifests.isNotEmpty() -> manifests.distinctBy { it.capture.url }
            progressive.isNotEmpty() -> progressive.distinctBy { it.capture.url }
            else -> accepted.distinctBy { it.capture.url }
        }

        return SnifferCapturePlan(links = selected, rejected = rejected)
    }

    private fun kindOf(url: String, quality: Int?): SnifferStreamKind {
        // Inspect the path, not the query: signed URLs often contain another media type as a query
        // value, and that must not change how the actual URL is played.
        val path = url.substringBefore('?').substringBefore('#')

        if (VideoUrlClassifier.isMasterM3u8(path)) return SnifferStreamKind.HLS_MASTER

        if (path.contains(".m3u8", ignoreCase = true) ||
            VideoUrlClassifier.isLikelyHlsManifest(path)
        ) {
            return if (quality != null) SnifferStreamKind.HLS_VARIANT
            else SnifferStreamKind.HLS_MANIFEST
        }

        if (PROGRESSIVE_EXTENSIONS.any { path.contains(it, ignoreCase = true) }) {
            return SnifferStreamKind.PROGRESSIVE
        }

        return SnifferStreamKind.VIDEO
    }

    /** Quality is read from the filename first, then from an explicit label supplied by the page. */
    private fun qualityOf(capture: CapturedLinkData): Int? {
        val filename = filenameOf(capture.url)
        return QUALITY_PATTERN.find(filename)?.value?.toIntOrNull()
            ?: QUALITY_PATTERN.find(capture.qualityLabel)?.value?.toIntOrNull()
    }

    private fun filenameOf(url: String): String = try {
        java.net.URI(url).path?.substringAfterLast('/').orEmpty().lowercase()
    } catch (_: Exception) {
        url.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
    }

    private fun qualityLabel(kind: SnifferStreamKind, quality: Int?): String = when {
        kind == SnifferStreamKind.HLS_MASTER -> "Auto"
        quality != null -> "${quality}p"
        else -> "Auto"
    }
}
