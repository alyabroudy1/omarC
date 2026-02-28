package com.youtube.innertube

import com.lagradost.api.Log

/**
 * Generates a DASH MPD (Media Presentation Description) manifest from
 * YouTube adaptive format streams.
 *
 * Single responsibility: take parsed [YouTubeStreamFormat] objects and
 * produce a standards-compliant MPD XML that ExoPlayer can consume.
 *
 * The generated manifest uses SegmentBase with byte-range indexing,
 * which matches YouTube's adaptive stream layout (no separate segments).
 */
object DashManifestGenerator {

    private const val TAG = "DashManifestGen"

    /**
     * Build a DASH MPD manifest from adaptive formats and serve it
     * via a local HTTP server that CloudStream's HttpDataSource can reach.
     *
     * @param adaptiveFormats List of video-only and audio-only streams
     * @return An http://127.0.0.1:{port}/manifest.mpd URL,
     *         or null if there are no usable formats.
     */
    fun generateUrl(adaptiveFormats: List<YouTubeStreamFormat>): String? {
        val mpd = generateMpd(adaptiveFormats) ?: return null
        return LocalMpdServer.serve(mpd)
    }

    /**
     * Build the raw MPD XML string.
     *
     * @return MPD XML or null if there are no usable video formats.
     */
    fun generateMpd(adaptiveFormats: List<YouTubeStreamFormat>): String? {
        val videoFormats = adaptiveFormats.filter { it.isVideo && it.codecs != null }
        val audioFormats = adaptiveFormats.filter { it.isAudio && it.codecs != null }

        Log.d(TAG, "generateMpd: ${videoFormats.size} video, ${audioFormats.size} audio formats")

        if (videoFormats.isEmpty()) {
            Log.w(TAG, "generateMpd: No video formats — cannot build manifest")
            return null
        }
        if (audioFormats.isEmpty()) {
            Log.w(TAG, "generateMpd: No audio formats — skipping DASH (would be silent)")
            return null
        }

        // Duration from the first video format (all formats share the same duration)
        val durationMs = videoFormats.firstNotNullOfOrNull { it.approxDurationMs }
            ?: audioFormats.firstNotNullOfOrNull { it.approxDurationMs }
            ?: return null
        val durationSeconds = durationMs / 1000.0

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:full:2011" type="static" mediaPresentationDuration="PT${durationSeconds}S" minBufferTime="PT1.5S">""")
        sb.appendLine("  <Period>")

        // ── Video AdaptationSet ──
        appendAdaptationSet(sb, videoFormats, "video")

        // ── Audio AdaptationSet ──
        appendAdaptationSet(sb, audioFormats, "audio")

        sb.appendLine("  </Period>")
        sb.appendLine("</MPD>")

        Log.d(TAG, "generateMpd: Built MPD with ${videoFormats.size}V + ${audioFormats.size}A streams")
        return sb.toString()
    }

    private fun appendAdaptationSet(
        sb: StringBuilder,
        formats: List<YouTubeStreamFormat>,
        mediaType: String // "video" or "audio"
    ) {
        // Group by base mime type to create separate AdaptationSets if needed
        // (e.g., video/mp4 vs video/webm)
        val byMime = formats.groupBy { it.baseMimeType }

        for ((mimeType, group) in byMime) {
            sb.append("""    <AdaptationSet mimeType="$mimeType" subsegmentAlignment="true" """)
            if (mediaType == "video") {
                sb.append("""scanType="progressive" """)
            }
            sb.appendLine(""">""")

            for (fmt in group.sortedByDescending { it.bitrate ?: 0 }) {
                appendRepresentation(sb, fmt)
            }

            sb.appendLine("    </AdaptationSet>")
        }
    }

    private fun appendRepresentation(sb: StringBuilder, fmt: YouTubeStreamFormat) {
        sb.append("""      <Representation id="${fmt.itag}" """)
        sb.append("""codecs="${escapeXml(fmt.codecs ?: "")}" """)
        fmt.bitrate?.let { sb.append("""bandwidth="$it" """) }
        fmt.width?.let { sb.append("""width="$it" """) }
        fmt.height?.let { sb.append("""height="$it" """) }
        fmt.contentLength?.let { sb.append("""contentLength="$it" """) }
        sb.appendLine(">")

        // BaseURL — the full authenticated stream URL
        sb.appendLine("""        <BaseURL>${escapeXml(fmt.url)}</BaseURL>""")

        // SegmentBase with byte-range indexing
        if (fmt.indexRange != null && fmt.initRange != null) {
            sb.appendLine("""        <SegmentBase indexRange="${fmt.indexRange}">""")
            sb.appendLine("""          <Initialization range="${fmt.initRange}"/>""")
            sb.appendLine("        </SegmentBase>")
        }

        sb.appendLine("      </Representation>")
    }

    /** Escape XML special characters in attribute values and text content. */
    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
