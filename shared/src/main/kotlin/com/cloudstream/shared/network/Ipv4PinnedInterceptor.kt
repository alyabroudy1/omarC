package com.cloudstream.shared.network

import com.lagradost.cloudstream3.app
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Forces playback traffic out over IPv4, for CDNs that pin a stream token to the caller's IP.
 *
 * Extraction already pins its own legs (see [PreferIpv4Dns], used by the sniffer's WebView
 * interceptor and by SnifferExtractor's manifest fetch), but none of that reaches the player.
 * CloudStream hands a link with no provider interceptor to a bare `DefaultHttpDataSource.Factory()`
 * — plain `HttpURLConnection`, its own system DNS, none of the OkHttp configuration the token was
 * minted under (`CS3IPlayer.createOnlineSource`, which still carries a `//TODO USE app.baseClient`).
 * On a dual-stack network that means the token can be minted over one address family and played
 * over the other, and the CDN answers 403 on every segment.
 *
 * Returning this from `getVideoInterceptor` flips core onto `OkHttpDataSource` instead, and since an
 * OkHttp application interceptor may serve a response from another client, the call is replayed on a
 * client that does pin IPv4.
 *
 * The inner client is built from `app.baseClient` *without* this interceptor, so there is no
 * recursion.
 */
object Ipv4PinnedInterceptor : Interceptor {

    private val ipv4Client: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(PreferIpv4Dns())
            // baseClient carries a 50 MiB response cache meant for HTML — never route media
            // through it.
            .cache(null)
            // Media reads outlive OkHttp's 10s default; a stalled segment must not kill the stream.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun intercept(chain: Interceptor.Chain): Response =
        ipv4Client.newCall(chain.request()).execute()
}
