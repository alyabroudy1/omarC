package com.cloudstream.shared.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [classify] is the one place a response becomes a decision. These tests pin the two mistakes
 * the old string-matched `RequestResult` made: treating a body marker as a challenge regardless
 * of status code, and comparing human-readable error text.
 */
class FetchOutcomeTest {

    private val challengeBody =
        "<html><head><title>Just a moment...</title></head>" +
            "<body><div id=\"cf-wrapper\">challenge-platform</div></body></html>"

    @Test
    fun cfChallengeIsBlocked() {
        val outcome = classify(403, challengeBody, "https://example.com/")
        assertTrue("expected CloudflareBlocked, got $outcome", outcome is FetchOutcome.CloudflareBlocked)
        assertEquals(403, (outcome as FetchOutcome.CloudflareBlocked).code)
        assertFalse(outcome.solveAttempted)
    }

    @Test
    fun plainCfClearanceCookieIsNotBlocked() {
        val body = "<html><body>document.cookie contains cf_clearance for this host</body></html>"
        val outcome = classify(200, body, "https://example.com/")
        assertTrue("expected Success, got $outcome", outcome is FetchOutcome.Success)
    }

    @Test
    fun responseCodeIsActuallyUsed() {
        val ok = classify(200, challengeBody, "https://example.com/")
        val blocked = classify(503, challengeBody, "https://example.com/")
        assertTrue(ok is FetchOutcome.Success)
        assertTrue(blocked is FetchOutcome.CloudflareBlocked)
    }

    @Test
    fun noStringMatchingOnErrorMessages() {
        val notFound = classify(404, "Forbidden", "https://example.com/")
        val forbidden = classify(403, "Forbidden", "https://example.com/")
        assertNotEquals(notFound, forbidden)
        assertEquals(FetchOutcome.HttpError(404, "https://example.com/", "Forbidden"), notFound)
        assertEquals(FetchOutcome.HttpError(403, "https://example.com/", "Forbidden"), forbidden)
    }

    @Test
    fun serverHeaderAloneBlocksOnCfCode() {
        val outcome = classify(503, "service unavailable", "https://example.com/", serverHeader = "cloudflare")
        assertTrue("expected CloudflareBlocked, got $outcome", outcome is FetchOutcome.CloudflareBlocked)
    }

    @Test
    fun solveAttemptedSuppressesResolve() {
        val fresh = FetchOutcome.CloudflareBlocked(403, "https://example.com/", solveAttempted = false)
        val spent = FetchOutcome.CloudflareBlocked(403, "https://example.com/", solveAttempted = true)
        assertTrue(fresh.needsCfSolve())
        assertFalse(spent.needsCfSolve())
        assertFalse(FetchOutcome.Cancelled.needsCfSolve())
        assertFalse(FetchOutcome.Success("<html/>", 200, "https://example.com/").needsCfSolve())
    }

    /**
     * The text/post accessors handed a non-CF error body to the caller before [FetchOutcome]
     * existed (an AJAX endpoint answering 404 with usable content). [bodyOrNull] is what keeps
     * that behaviour; only CF blocks, transport failures and cancellation withhold a body.
     */
    @Test
    fun bodyOrNullReturnsHttpErrorBody() {
        assertEquals(
            "<html/>",
            FetchOutcome.Success("<html/>", 200, "https://example.com/").bodyOrNull()
        )
        assertEquals(
            "{\"episodes\":[]}",
            FetchOutcome.HttpError(404, "https://example.com/", "{\"episodes\":[]}").bodyOrNull()
        )
        assertNull(FetchOutcome.HttpError(500, "https://example.com/", null).bodyOrNull())
        assertNull(FetchOutcome.Transport(java.io.IOException("boom")).bodyOrNull())
        assertNull(FetchOutcome.Cancelled.bodyOrNull())
        assertNull(
            FetchOutcome.CloudflareBlocked(403, "https://example.com/", solveAttempted = false)
                .bodyOrNull()
        )
    }
}
