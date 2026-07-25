package com.cloudstream.shared.network

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * DNS resolver that prioritizes IPv4 addresses (A records) over IPv6 addresses (AAAA records) —
 * the mirror of [PreferIpv6Dns].
 *
 * Needed whenever a site hands out **IP-pinned tokens** and its media CDN is IPv4-only. FaselHD is
 * the reference case: `www.fasel-hd.cam` is dual-stack, so on a dual-stack network RFC 6724 makes
 * the token-minting request go out over IPv6, and the CDN embeds that IPv6 in the stream path:
 *
 *     https://r467--….c.scdns.io/stream/v1/hls/<id>/<exp>/www.fasel-hd.cam/all/2001:16b8:…/yes/…
 *
 * But `c.scdns.io` has **no AAAA record**, so the stream can only ever be fetched over IPv4 — the
 * client can never present the address the token was minted for, and every request 403s. Pinning
 * the minting leg to IPv4 makes the embedded address the same NAT'd IPv4 the player will use.
 *
 * Rule of thumb: pick the family the **stream CDN** supports, not the one the website supports.
 */
class PreferIpv4Dns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: Exception) {
            return emptyList()
        }
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        return if (ipv4.isNotEmpty()) {
            ipv4 + addresses.filterIsInstance<Inet6Address>()
        } else {
            addresses
        }
    }
}
