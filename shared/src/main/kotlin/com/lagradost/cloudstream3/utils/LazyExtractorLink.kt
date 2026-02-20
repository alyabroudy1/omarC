package com.lagradost.cloudstream3.utils

/**
 * A special ExtractorLink that defers extraction until the link is actually selected by the player.
 * Very useful for providers where extracting all links via Sniffer WebView takes too much time.
 */
abstract class LazyExtractorLink(
    override val source: String,
    override val name: String,
    override var referer: String,
    override var quality: Int,
    override var type: ExtractorLinkType = ExtractorLinkType.VIDEO,
) : ExtractorLink(source, name, "", referer, quality, type = type) {
    /** Called by the player right before playback. Must return the true ExtractorLink. */
    abstract suspend fun getRealLink(): ExtractorLink?
}
