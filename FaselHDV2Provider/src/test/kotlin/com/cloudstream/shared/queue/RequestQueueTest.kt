package com.cloudstream.shared.queue

import com.cloudstream.shared.core.FetchOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leader/follower contract, driven through a fake [RequestQueue.Host].
 *
 * kotlinx-coroutines-test is not on this module's test classpath, so concurrency is staged by
 * hand: `runBlocking`'s event loop is single-threaded, so a `yield()` after an `async` lets that
 * coroutine run until it parks inside the fake host, which is exactly the window in which a
 * second caller must become a follower rather than a second leader.
 */
class RequestQueueTest {

    private class FakeHost : RequestQueue.Host {
        /** `var` so a test can model the host applying a redirect the queue reported. */
        override var currentDomain = "h.test"

        val executeUrls = mutableListOf<String>()
        val solveUrls = mutableListOf<String>()
        val redirects = mutableListOf<Pair<String, String>>()

        /** Set to auto-answer; null parks the call in [parked] for the test to release. */
        var executeResponse: ((String) -> FetchOutcome)? = null
        var solveResponse: FetchOutcome = FetchOutcome.Cancelled

        /** Consumed in order when a test needs successive solves to answer differently. */
        val solveResponses = mutableListOf<FetchOutcome>()

        /** Model the host adopting the new domain, as the HTTP service does. */
        var adoptRedirectedDomain = false

        val parked = mutableListOf<CompletableDeferred<FetchOutcome>>()

        override suspend fun execute(url: String, headers: Map<String, String>): FetchOutcome {
            executeUrls += url
            executeResponse?.let { return it(url) }
            val gate = CompletableDeferred<FetchOutcome>()
            parked += gate
            return gate.await()
        }

        override suspend fun solveCloudflare(url: String, allowedDomains: Set<String>): FetchOutcome {
            solveUrls += url
            return if (solveResponses.isNotEmpty()) solveResponses.removeAt(0) else solveResponse
        }

        override suspend fun onDomainRedirect(oldHost: String, newHost: String) {
            redirects += oldHost to newHost
            if (adoptRedirectedDomain) currentDomain = newHost
        }
    }

    private fun ok(url: String) = FetchOutcome.Success("<html>$url</html>", 200, url)

    @Test
    fun secondCallerForSameHostParks() = runBlocking {
        val host = FakeHost()
        val queue = RequestQueue(host)

        val leader = async { queue.enqueue("https://h.test/1") }
        yield()
        val follower = async { queue.enqueue("https://h.test/2") }
        yield()

        // One execute so far: the follower is parked behind the leader, not racing it.
        assertEquals(listOf("https://h.test/1"), host.executeUrls)

        host.executeResponse = { ok(it) }
        host.parked[0].complete(ok("https://h.test/1"))

        assertEquals(ok("https://h.test/1"), leader.await())
        assertEquals(ok("https://h.test/2"), follower.await())
        assertEquals(listOf("https://h.test/1", "https://h.test/2"), host.executeUrls)
    }

    @Test
    fun followersRunAfterLeaderSuccess() = runBlocking {
        val host = FakeHost()
        val queue = RequestQueue(host)

        val leader = async { queue.enqueue("https://h.test/1") }
        yield()
        val f1 = async { queue.enqueue("https://h.test/2") }
        val f2 = async { queue.enqueue("https://h.test/3") }
        yield()

        assertEquals(1, host.executeUrls.size)

        host.executeResponse = { ok(it) }
        host.parked[0].complete(ok("https://h.test/1"))

        assertEquals(ok("https://h.test/1"), leader.await())
        assertEquals(ok("https://h.test/2"), f1.await())
        assertEquals(ok("https://h.test/3"), f2.await())
        // Exactly once each — no follower re-ran and no follower was dropped.
        assertEquals(
            listOf("https://h.test/1", "https://h.test/2", "https://h.test/3"),
            host.executeUrls
        )
        assertEquals(emptyList<String>(), host.solveUrls)
    }

    @Test
    fun cfBlockedLeaderSolvesOnceThenVerifies() = runBlocking {
        val host = FakeHost()
        host.solveResponse = ok("https://h.test/1")
        val queue = RequestQueue(host)

        val leader = async { queue.enqueue("https://h.test/1") }
        yield()
        val f1 = async { queue.enqueue("https://h.test/2") }
        val f2 = async { queue.enqueue("https://h.test/3") }
        yield()

        host.executeResponse = { ok(it) }
        host.parked[0].complete(FetchOutcome.CloudflareBlocked(403, "https://h.test/1"))

        assertEquals(ok("https://h.test/1"), leader.await())
        assertEquals(ok("https://h.test/2"), f1.await())
        assertEquals(ok("https://h.test/3"), f2.await())

        // One solve for the whole group…
        assertEquals(listOf("https://h.test/1"), host.solveUrls)
        // …and the first follower verified the new cookies before the rest were released.
        assertEquals(
            listOf("https://h.test/1", "https://h.test/2", "https://h.test/3"),
            host.executeUrls
        )
    }

    @Test
    fun leaderTransportFailurePropagatesToFollowers() = runBlocking {
        val host = FakeHost()
        val queue = RequestQueue(host)

        val leader = async { queue.enqueue("https://h.test/1") }
        yield()
        val f1 = async { queue.enqueue("https://h.test/2") }
        val f2 = async { queue.enqueue("https://h.test/3") }
        yield()

        val boom = FetchOutcome.Transport(IllegalStateException("socket"))
        host.parked[0].complete(boom)

        assertSame(boom, leader.await())
        assertSame(boom, f1.await())
        assertSame(boom, f2.await())
        // Followers inherited the leader's outcome; nobody re-issued the request.
        assertEquals(listOf("https://h.test/1"), host.executeUrls)
        assertTrue(host.solveUrls.isEmpty())
    }

    /**
     * A solve that fails must stamp `solveAttempted` on every follower's outcome: the whole
     * point of the flag is that nobody further up the stack launches a second WebView for a
     * block this group already spent a solve on.
     */
    @Test
    fun solveFailureStampsSolveAttemptedForFollowers() = runBlocking {
        val host = FakeHost()
        host.solveResponse =
            FetchOutcome.CloudflareBlocked(403, "https://h.test/1", solveAttempted = false)
        val queue = RequestQueue(host)

        val leader = async { queue.enqueue("https://h.test/1") }
        yield()
        val f1 = async { queue.enqueue("https://h.test/2") }
        val f2 = async { queue.enqueue("https://h.test/3") }
        yield()

        host.parked[0].complete(
            FetchOutcome.CloudflareBlocked(403, "https://h.test/1", solveAttempted = false)
        )

        listOf(leader, f1, f2).forEach { pending ->
            val outcome = pending.await()
            assertTrue("expected CloudflareBlocked, got $outcome", outcome is FetchOutcome.CloudflareBlocked)
            assertTrue(
                "solveAttempted must be stamped, got $outcome",
                (outcome as FetchOutcome.CloudflareBlocked).solveAttempted
            )
        }

        // Exactly one solve for the group, and no follower re-issued its request.
        assertEquals(listOf("https://h.test/1"), host.solveUrls)
        assertEquals(listOf("https://h.test/1"), host.executeUrls)
    }

    /**
     * The verifier is the follower that proves the fresh cookies work. If it is CF-blocked
     * again the queue re-solves ONCE more — not once per follower — and a second failure
     * stamps `solveAttempted` on everyone still waiting.
     */
    @Test
    fun verifyFailureResolvesOnce() = runBlocking {
        val host = FakeHost()
        host.solveResponses += ok("https://h.test/1")
        host.solveResponses +=
            FetchOutcome.CloudflareBlocked(403, "https://h.test/2", solveAttempted = false)
        val queue = RequestQueue(host)

        val leader = async { queue.enqueue("https://h.test/1") }
        yield()
        val verifier = async { queue.enqueue("https://h.test/2") }
        val f2 = async { queue.enqueue("https://h.test/3") }
        yield()

        // The verifier (/2) is blocked again; anything else would succeed.
        host.executeResponse = { url ->
            if (url.endsWith("/2")) {
                FetchOutcome.CloudflareBlocked(403, url, solveAttempted = false)
            } else {
                ok(url)
            }
        }
        host.parked[0].complete(
            FetchOutcome.CloudflareBlocked(403, "https://h.test/1", solveAttempted = false)
        )

        // The first solve got through, so the leader carries the WebView's HTML.
        assertEquals(ok("https://h.test/1"), leader.await())

        listOf(verifier, f2).forEach { pending ->
            val outcome = pending.await()
            assertTrue("expected CloudflareBlocked, got $outcome", outcome is FetchOutcome.CloudflareBlocked)
            assertTrue(
                "solveAttempted must be stamped, got $outcome",
                (outcome as FetchOutcome.CloudflareBlocked).solveAttempted
            )
        }

        // Two solves total: the leader's block, then the verifier's — not one per follower.
        assertEquals(listOf("https://h.test/1", "https://h.test/2"), host.solveUrls)
    }

    /**
     * A redirect the leader discovers is reported once and then applied to every follower's
     * URL, so the group never re-walks the redirect chain.
     */
    @Test
    fun leaderRedirectRewritesFollowerUrlsAndNotifiesHost() = runBlocking {
        val host = FakeHost()
        host.adoptRedirectedDomain = true
        val queue = RequestQueue(host)
        host.currentDomain = "old.test"

        val leader = async { queue.enqueue("https://old.test/1") }
        yield()
        val f1 = async { queue.enqueue("https://old.test/2") }
        val f2 = async { queue.enqueue("https://old.test/3") }
        yield()

        assertEquals(listOf("https://old.test/1"), host.executeUrls)

        host.executeResponse = { ok(it) }
        // Leader's response landed on a different host than the one requested.
        host.parked[0].complete(FetchOutcome.Success("<html/>", 200, "https://new.test/1"))

        assertEquals(FetchOutcome.Success("<html/>", 200, "https://new.test/1"), leader.await())
        assertEquals(ok("https://new.test/2"), f1.await())
        assertEquals(ok("https://new.test/3"), f2.await())

        // Reported exactly once, before the followers were released…
        assertEquals(listOf("old.test" to "new.test"), host.redirects)
        // …and every follower went out on the new host.
        assertEquals(
            listOf("https://old.test/1", "https://new.test/2", "https://new.test/3"),
            host.executeUrls
        )
    }
}
