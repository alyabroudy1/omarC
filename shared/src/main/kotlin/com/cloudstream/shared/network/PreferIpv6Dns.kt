package com.cloudstream.shared.network

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * DNS resolver that prioritizes IPv6 addresses (AAAA records) over IPv4 addresses (A records).
 * 
 * This helps bypass cgNAT-based IPv4 blocks/reputation blocks on CDNs (like Cloudflare)
 * by forcing connections over the user's clean IPv6 address when the local network supports dual-stack.
 */
class PreferIpv6Dns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        return addresses.sortedWith(Comparator { a, b ->
            when {
                a is Inet6Address && b is Inet4Address -> -1
                a is Inet4Address && b is Inet6Address -> 1
                else -> 0
            }
        })
    }
}
