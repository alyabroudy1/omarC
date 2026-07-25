package com.cloudstream.shared.network

/**
 * Helpers for CDNs that pin a stream URL to the IP of whoever requested it.
 *
 * Several hosts (scdns.io as used by FaselHD, among others) write the caller's IP straight into
 * the media path:
 *
 *     https://r467--….c.scdns.io/stream/v1/hls/<id>/<exp>/<site>/all/2001:16b8:…:7efa/yes/…
 *
 * If that address is IPv6 while the media host only has A records, the link is dead on arrival:
 * the player must connect over IPv4 and the CDN rejects the source-address mismatch with 403.
 * Detecting it lets a provider fail loudly — or re-mint over IPv4 — instead of handing the player
 * a link that cannot work. See [PreferIpv4Dns].
 *
 * Pure Kotlin on purpose: no Android or CloudStream types, so it is unit-testable on the JVM.
 */
object IpPinnedUrl {

    private val HEX_GROUPS = Regex("^[0-9a-fA-F:]+$")

    /**
     * True if any path segment of [url] is an IPv6 literal, i.e. the URL is pinned to an IPv6
     * client address.
     *
     * Only the path is examined, so the scheme's "//" and an authority port never match, and a
     * segment must hold at least two ':' separators to count — a single colon appears in ordinary
     * text and query values.
     */
    fun containsIpv6Literal(url: String): Boolean {
        val afterScheme = url.substringAfter("://", url)
        val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
        return path.split('/').any { seg ->
            seg.count { it == ':' } >= 2 && HEX_GROUPS.matches(seg)
        }
    }
}
