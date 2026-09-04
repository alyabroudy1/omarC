package com.cloudstream.shared.core

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The two operations OkHttp needs from Android's cookie store, behind an interface so the mapping
 * in [SystemCookieJar] is testable on the JVM.
 */
interface CookieStorage {
    /** The `"a=1; b=2"` header string this store holds for [url], or null/blank if none. */
    fun get(url: String): String?

    /** Writes one `Set-Cookie`-shaped [header] for [url], attributes and all. */
    fun set(url: String, header: String)
}

/**
 * The one cookie store in the process: `android.webkit.CookieManager`. Every WebView already reads
 * and writes it, so routing OkHttp through it as well means one store instead of four, and cookie
 * scope is whatever `Set-Cookie` said rather than something we compute.
 */
object AndroidCookieStorage : CookieStorage {
    override fun get(url: String): String? = try {
        android.webkit.CookieManager.getInstance().getCookie(url)
    } catch (_: Throwable) {
        null
    }

    override fun set(url: String, header: String) {
        try {
            val manager = android.webkit.CookieManager.getInstance()
            manager.setAcceptCookie(true)
            manager.setCookie(url, header)
            manager.flush()
        } catch (_: Throwable) {
            // A cookie we cannot store is not a reason to fail the request.
        }
    }
}

/**
 * Bridges OkHttp onto [CookieStorage]. Nothing here rewrites a host: a response's cookies are
 * written back against the response URL, so the store decides scope exactly as a browser would.
 */
class SystemCookieJar(
    private val storage: CookieStorage = AndroidCookieStorage
) : CookieJar {

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = storage.get(url.toString())
        if (header.isNullOrBlank()) return emptyList()
        return header.split(";").mapNotNull { pair ->
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) null else Cookie.parse(url, trimmed)
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val target = url.toString()
        for (cookie in cookies) {
            storage.set(target, cookie.toString())
        }
    }
}

/**
 * Expires the cookies the store returns for [url], and only for [url]: per-host invalidation
 * before a Cloudflare re-solve. Never clears the whole store; another provider's session lives
 * in there too.
 *
 * Limitation: the expiry is written as a host-only cookie with `Path=/`. CookieManager keys a
 * cookie by (name, domain, path), so a cookie the site set with `Domain=.example.com` or a
 * non-root path is NOT removed by this call; it stays until the site overwrites it. Widening
 * the scope needs registrable-domain logic, which is deliberately out of scope until Wave 6.
 */
fun expireCookiesFor(url: String, storage: CookieStorage) {
    val header = storage.get(url) ?: return
    if (header.isBlank()) return
    for (pair in header.split(";")) {
        val name = pair.substringBefore("=").trim()
        if (name.isEmpty()) continue
        storage.set(url, "$name=; Max-Age=0; Path=/")
    }
}
