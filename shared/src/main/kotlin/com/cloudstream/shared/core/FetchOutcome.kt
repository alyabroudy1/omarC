package com.cloudstream.shared.core

import com.cloudstream.shared.cloudflare.CloudflareDetector

/**
 * The single result type for an HTTP fetch attempt.
 *
 * Replaces the old result type, whose success/error pair forced callers to
 * re-inspect stringified failure messages to decide what to do next.
 */
sealed interface FetchOutcome {
    data class Success(val html: String, val code: Int, val finalUrl: String) : FetchOutcome

    /** solveAttempted = a CF solve already ran and failed for this URL; do not launch another. */
    data class CloudflareBlocked(
        val code: Int,
        val finalUrl: String?,
        val solveAttempted: Boolean = false
    ) : FetchOutcome

    data class HttpError(val code: Int, val finalUrl: String?, val body: String?) : FetchOutcome

    data class Transport(val cause: Throwable) : FetchOutcome

    data object Cancelled : FetchOutcome
}

/**
 * Classify one HTTP response. Pure: no Android, no OkHttp.
 *
 * Order of the rules matters:
 * 1. a non-2xx/3xx code that either carries CF markers in the body
 *    ([CloudflareDetector.isBlocked], so the marker list stays in one place) or is a
 *    CF code served by a `Server: cloudflare` host is [FetchOutcome.CloudflareBlocked];
 * 2. `200..399` is [FetchOutcome.Success];
 * 3. anything else is [FetchOutcome.HttpError].
 *
 * The code gate means a 200 whose body merely mentions `cf_clearance` or `ray_id` is
 * content, not a challenge.
 */
fun classify(
    code: Int,
    body: String,
    finalUrl: String,
    serverHeader: String? = null
): FetchOutcome {
    val isCfServerBlock = CloudflareDetector.isCloudflareResponse(code) &&
        serverHeader?.contains("cloudflare", ignoreCase = true) == true
    if (code !in 200..399 && (CloudflareDetector.isBlocked(code, body) || isCfServerBlock)) {
        return FetchOutcome.CloudflareBlocked(code, finalUrl, solveAttempted = false)
    }
    if (code in 200..399) return FetchOutcome.Success(body, code, finalUrl)
    return FetchOutcome.HttpError(code, finalUrl, body)
}

/**
 * The `document()` CF-fallback gate: a WebView solve is worth launching only for a CF block
 * that no solve has been spent on yet.
 */
fun FetchOutcome.needsCfSolve(): Boolean =
    this is FetchOutcome.CloudflareBlocked && !solveAttempted

/**
 * The response body for any outcome that carries one, CF blocks excluded.
 *
 * Mirrors the pre-[FetchOutcome] behaviour of the text/post accessors: a non-CF error page
 * (a 404 or 500 whose body an AJAX endpoint still fills with usable content) reaches the
 * caller. Only a CF block, a transport failure or a cancellation yields null.
 */
fun FetchOutcome.bodyOrNull(): String? = when (this) {
    is FetchOutcome.Success -> html
    is FetchOutcome.HttpError -> body
    else -> null
}
