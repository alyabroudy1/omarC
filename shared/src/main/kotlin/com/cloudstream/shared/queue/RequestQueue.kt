package com.cloudstream.shared.queue

import com.cloudstream.shared.core.FetchOutcome
import com.cloudstream.shared.logging.ProviderLogger
import com.cloudstream.shared.logging.ProviderLogger.TAG_QUEUE
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/**
 * Request queue with leader-follower pattern.
 *
 * When multiple requests arrive for the same domain:
 * 1. First request (leader) executes immediately
 * 2. Followers wait for leader to complete
 * 3. If leader succeeds, all followers run in parallel
 * 4. If leader hits CF, it solves CF, then first follower verifies cookies
 * 5. If verification passes, remaining followers run in parallel
 *
 * Domain Redirect Handling:
 * When a redirect is detected (e.g., arabseed.show → asd.pics) BEFORE CF solve,
 * the [Host.onDomainRedirect] callback is invoked to update SessionState with the new domain.
 * This ensures cookies are stored for the correct domain, preventing CF loops.
 */
class RequestQueue(private val host: Host) {

    /**
     * Everything the queue needs from its owner. One nested interface with a single
     * implementer (the HTTP service) instead of four constructor lambdas that had to close
     * over a half-constructed `this`.
     */
    interface Host {
        suspend fun execute(url: String, headers: Map<String, String>): FetchOutcome
        suspend fun solveCloudflare(url: String, allowedDomains: Set<String>): FetchOutcome
        suspend fun onDomainRedirect(oldHost: String, newHost: String)
        val currentDomain: String
    }

    private val mutex = Mutex()

    data class QueuedRequest(
        val url: String,
        val deferred: CompletableDeferred<FetchOutcome>,
        val action: suspend () -> FetchOutcome
    )

    private val pendingRequests = mutableMapOf<String, MutableList<QueuedRequest>>()

    /**
     * Queue a request. Returns when the request is complete.
     */
    suspend fun enqueue(url: String, headers: Map<String, String> = emptyMap()): FetchOutcome {
        return enqueueAction(url) { host.execute(url, headers) }
    }

    /**
     * Queue a generic action (like a POST request) associated with a URL (for domain grouping).
     */
    suspend fun enqueueAction(url: String, action: suspend () -> FetchOutcome): FetchOutcome {
        val domain = extractDomain(url)
        val deferred = CompletableDeferred<FetchOutcome>()
        val request = QueuedRequest(url, deferred, action)

        val isLeader = mutex.withLock {
            val queue = pendingRequests.getOrPut(domain) { mutableListOf() }
            queue.add(request)
            queue.size == 1
        }

        if (isLeader) {
            ProviderLogger.d(TAG_QUEUE, "enqueue", "Request is LEADER", "domain" to domain, "url" to url.take(80))
            executeAsLeader(domain, request)
        } else {
            ProviderLogger.d(TAG_QUEUE, "enqueue", "Request is FOLLOWER", "domain" to domain, "url" to url.take(80))
        }

        return deferred.await()
    }

    private suspend fun executeAsLeader(domain: String, leader: QueuedRequest) {
        // Step 1: Execute leader request
        val result = leader.action()

        when {
            result is FetchOutcome.Success -> {
                // Detect domain redirect from leader's response BEFORE releasing followers.
                // This ensures followers use the updated domain, eliminating redundant redirects.
                val leaderDomain = extractDomain(leader.url)
                val finalDomain = extractDomain(result.finalUrl)
                if (finalDomain != leaderDomain && finalDomain.isNotBlank()) {
                    ProviderLogger.i(TAG_QUEUE, "executeAsLeader", "Leader detected domain redirect",
                        "from" to leaderDomain, "to" to finalDomain)
                    host.onDomainRedirect(leaderDomain, finalDomain)
                }

                ProviderLogger.i(TAG_QUEUE, "executeAsLeader", "Leader SUCCESS", "domain" to domain)
                leader.deferred.complete(result)
                runFollowersParallel(domain)
            }
            result is FetchOutcome.CloudflareBlocked && !result.solveAttempted -> {
                ProviderLogger.i(TAG_QUEUE, "executeAsLeader", "Leader CF BLOCKED - solving", "domain" to domain)

                // CRITICAL: use the final URL from the direct request if it redirected
                // This prevents solving for the old domain when we've been redirected to a new one
                val solveUrl = result.finalUrl?.takeIf { it.isNotBlank() } ?: leader.url

                val requestDomain = extractDomain(leader.url)
                val finalDomain = extractDomain(solveUrl)

                // DON'T update domain here — the redirect target may be a CF proxy domain
                // (e.g., fasel-hd.cam), NOT the actual content domain. Domain will be updated
                // AFTER CF solve using the actual content URL from cfResult.finalUrl.
                if (requestDomain != finalDomain && finalDomain.isNotBlank()) {
                    ProviderLogger.i(TAG_QUEUE, "executeAsLeader", "Domain redirect detected (deferred until CF solve)",
                        "from" to requestDomain, "to" to finalDomain)
                }

                // Compute allowed domains: include both the original request domain AND
                // the CF proxy domain so CfBypassEngine allows redirects between them
                val allowedDomains = buildSet {
                    if (requestDomain.isNotBlank()) {
                        val parts = requestDomain.split(".")
                        add(if (parts.size >= 2) parts.takeLast(2).joinToString(".") else requestDomain)
                    }
                    if (finalDomain.isNotBlank()) {
                        val parts = finalDomain.split(".")
                        add(if (parts.size >= 2) parts.takeLast(2).joinToString(".") else finalDomain)
                    }
                }

                val cfResult = host.solveCloudflare(solveUrl, allowedDomains)

                if (cfResult is FetchOutcome.Success) {
                    // NOW update domain from the CF result's finalUrl — this is the actual
                    // content domain (e.g., web3196x.faselhdx.xyz), not the CF proxy
                    val contentDomain = extractDomain(cfResult.finalUrl)
                    if (contentDomain.isNotBlank() && contentDomain != requestDomain) {
                        ProviderLogger.i(TAG_QUEUE, "executeAsLeader", "Post-CF domain update",
                            "from" to requestDomain, "to" to contentDomain)
                        host.onDomainRedirect(requestDomain, contentDomain)
                    }

                    // CRITICAL: Use the CF solve result directly as the leader result.
                    // The WebView already loaded the real content page (e.g., search results).
                    // Retrying via OkHttp would often get 403 because:
                    // 1. cf_clearance may be domain-bound to the CF proxy (fasel-hd.cam), not content domain
                    // 2. OkHttp has a different TLS fingerprint than WebView
                    // 3. The extracted cookies may be session cookies without cf_clearance
                    ProviderLogger.i(TAG_QUEUE, "executeAsLeader", "CF solved, using WebView HTML directly")
                    leader.deferred.complete(cfResult)
                    verifyAndRunFollowers(domain)
                } else {
                    ProviderLogger.w(TAG_QUEUE, "executeAsLeader", "CF solve did not get through")
                    val outcome = cfResult.withSolveAttempted()
                    leader.deferred.complete(outcome)
                    failAllFollowers(domain, outcome)
                }
            }
            else -> {
                ProviderLogger.w(TAG_QUEUE, "executeAsLeader", "Leader FAILED", "domain" to domain, "outcome" to result)
                leader.deferred.complete(result)
                failAllFollowers(domain, result)
            }
        }
    }

    private suspend fun verifyAndRunFollowers(domain: String) {
        val followers = mutex.withLock {
            pendingRequests.remove(domain)?.drop(1) ?: emptyList()
        }

        if (followers.isEmpty()) return

        val activeDomain = host.currentDomain

        // First follower verifies that new cookies work
        val verifier = followers.first()
        val verifierUrl = rewriteFollowerUrl(verifier.url, activeDomain)
        ProviderLogger.d(TAG_QUEUE, "verifyAndRunFollowers", "Verifying cookies", "url" to verifierUrl.take(80))

        val verifyResult = if (verifierUrl != verifier.url) {
            host.execute(verifierUrl, emptyMap())
        } else {
            verifier.action()
        }

        if (verifyResult is FetchOutcome.Success) {
            ProviderLogger.i(TAG_QUEUE, "verifyAndRunFollowers", "Verification SUCCESS", "domain" to domain)
            verifier.deferred.complete(verifyResult)

            // Run remaining followers in parallel with URL rewriting
            runRemainingFollowers(followers.drop(1), activeDomain)
        } else {
            ProviderLogger.w(TAG_QUEUE, "verifyAndRunFollowers", "Verification FAILED - retrying CF", "domain" to domain)
            // Re-solve CF with the verifier URL instead of failing all followers
            // This handles edge cases where cookies are invalid for the new domain
            val cfResult = host.solveCloudflare(verifierUrl, emptySet())
            if (cfResult is FetchOutcome.Success) {
                ProviderLogger.i(TAG_QUEUE, "verifyAndRunFollowers", "CF re-solve SUCCESS after verify fail")
                verifier.deferred.complete(cfResult)

                // Run remaining followers in parallel with URL rewriting
                runRemainingFollowers(followers.drop(1), activeDomain)
            } else {
                ProviderLogger.w(TAG_QUEUE, "verifyAndRunFollowers", "CF re-solve did not get through", "domain" to domain)
                val outcome = cfResult.withSolveAttempted()
                followers.forEach { request ->
                    request.deferred.complete(outcome)
                }
            }
        }
    }

    private suspend fun runRemainingFollowers(followers: List<QueuedRequest>, activeDomain: String) {
        coroutineScope {
            followers.forEach { request ->
                launch {
                    val rewrittenUrl = rewriteFollowerUrl(request.url, activeDomain)
                    val result = if (rewrittenUrl != request.url) {
                        host.execute(rewrittenUrl, emptyMap())
                    } else {
                        request.action()
                    }
                    request.deferred.complete(result)
                }
            }
        }
    }

    private suspend fun runFollowersParallel(domain: String) {
        val followers = mutex.withLock {
            pendingRequests.remove(domain)?.drop(1) ?: emptyList()
        }

        if (followers.isEmpty()) return

        val activeDomain = host.currentDomain
        ProviderLogger.d(TAG_QUEUE, "runFollowersParallel", "Running followers",
            "count" to followers.size, "domain" to domain,
            "activeDomain" to activeDomain)

        coroutineScope {
            followers.forEach { request ->
                launch {
                    val rewrittenUrl = rewriteFollowerUrl(request.url, activeDomain)
                    val result = if (rewrittenUrl != request.url) {
                        ProviderLogger.d(TAG_QUEUE, "runFollowersParallel",
                            "Rewrote follower URL",
                            "from" to extractDomain(request.url),
                            "to" to extractDomain(rewrittenUrl))
                        host.execute(rewrittenUrl, emptyMap())
                    } else {
                        request.action()
                    }
                    request.deferred.complete(result)
                }
            }
        }
    }

    /**
     * Hand the leader's own outcome to every follower. They were queued behind a request that
     * failed, so they inherit exactly why it failed — no restringified reason.
     */
    private suspend fun failAllFollowers(domain: String, outcome: FetchOutcome) {
        mutex.withLock {
            val followers = pendingRequests[domain]?.drop(1) ?: emptyList()
            if (followers.isNotEmpty()) {
                ProviderLogger.w(TAG_QUEUE, "failAllFollowers", "Failing followers", "count" to followers.size, "outcome" to outcome)
            }
            followers.forEach { request ->
                request.deferred.complete(outcome)
            }
            pendingRequests.remove(domain)
        }
    }

    /**
     * A CF block that survived a solve must not trigger another one further up the stack.
     */
    private fun FetchOutcome.withSolveAttempted(): FetchOutcome =
        if (this is FetchOutcome.CloudflareBlocked && !solveAttempted) copy(solveAttempted = true)
        else this

    /**
     * Rewrite a follower's URL to use the current active domain.
     * Called when the leader's response revealed a domain redirect.
     */
    private fun rewriteFollowerUrl(url: String, currentDomain: String): String {
        if (currentDomain.isBlank()) return url
        val urlDomain = extractDomain(url)
        return if (urlDomain.isNotBlank() && urlDomain != currentDomain) {
            url.replace(urlDomain, currentDomain)
        } else url
    }

    private fun extractDomain(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.") ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
