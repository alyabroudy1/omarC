package com.cloudstream.shared.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
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
 *
 * ## Why this drops AAAA instead of just ordering it last
 *
 * It used to return `ipv4 + ipv6`, on the assumption that OkHttp dials the list in order. It does
 * not. OkHttp 5 enables **fast fallback** (Happy Eyeballs, RFC 8305) by default: it re-interleaves
 * the resolved addresses by family and races connections 250 ms apart, first one to complete wins.
 * A trailing IPv6 address therefore still wins the race often enough to be a coin flip — observed
 * 2026-07-28 minting an IPv4-pinned token and 2026-07-29 an IPv6-pinned one, same code, same
 * network. Returning IPv4 only leaves nothing to race. Pair it with [pinToIpv4], which also turns
 * fast fallback off.
 *
 * Hosts with no A record at all still resolve — an empty list would be a hard failure, and a
 * v6-only host is not the case this guards against.
 */
class PreferIpv4Dns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: Exception) {
            return emptyList()
        }
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        return if (ipv4.isNotEmpty()) ipv4 else addresses
    }
}

/**
 * Binds a client to IPv4 for hosts that pin tokens to the caller's address.
 *
 * Both halves matter: [PreferIpv4Dns] removes the IPv6 candidates, and `fastFallback(false)`
 * stops OkHttp racing address families in the first place.
 */
fun OkHttpClient.Builder.pinToIpv4(): OkHttpClient.Builder =
    dns(PreferIpv4Dns()).fastFallback(false)

/**
 * Header stamped by [reportPeerAddress] with the address a response was actually fetched over.
 *
 * Needed because a log line that merely says "served over IPv4" proves nothing — the old one was
 * printed unconditionally after any successful call on the pinned client, so it kept claiming IPv4
 * while the token being minted carried an IPv6 address. Read the peer, don't assume it.
 */
const val PEER_ADDRESS_HEADER = "X-Cs-Peer-Address"

/** Records the connected peer address on every response as [PEER_ADDRESS_HEADER]. */
fun OkHttpClient.Builder.reportPeerAddress(): OkHttpClient.Builder =
    addNetworkInterceptor { chain ->
        val peer = chain.connection()?.socket()?.inetAddress?.hostAddress ?: "unknown"
        chain.proceed(chain.request()).newBuilder()
            .header(PEER_ADDRESS_HEADER, peer)
            .build()
    }
