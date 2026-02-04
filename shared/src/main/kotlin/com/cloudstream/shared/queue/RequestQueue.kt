package com.cloudstream.shared.queue

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
 * the onDomainRedirect callback is invoked to update SessionState with the new domain.
 * This ensures cookies are stored for the correct domain, preventing CF loops.
 */
class RequestQueue(
    private val executeRequest: suspend (String, Map<String, String>) -> RequestResult,
    private val solveCfAndRequest: suspend (String) -> RequestResult,
    private val onDomainRedirect: suspend (String, String) -> Unit = { _, _ -> }
) {
    private val mutex = Mutex()
    
    data class QueuedRequest(
        val url: String,
        val deferred: CompletableDeferred<RequestResult>,
        val action: suspend () -> RequestResult
    )
    
    private val pendingRequests = mutableMapOf<String, MutableList<QueuedRequest>>()
    
    /**
     * Queue a request. Returns when the request is complete.
     */
    suspend fun enqueue(url: String, headers: Map<String, String> = emptyMap()): RequestResult {
        return enqueueAction(url) { executeRequest(url, headers) }
    }
    
    /**
     * Queue a generic action (like a POST request) associated with a URL (for domain grouping).
     */
    suspend fun enqueueAction(url: String, action: suspend () -> RequestResult): RequestResult {
        val domain = extractDomain(url)
        val deferred = CompletableDeferred<RequestResult>()
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
            result.success -> {
                ProviderLogger.i(TAG_QUEUE, "executeAsLeader", "Leader SUCCESS", "domain" to domain)
                leader.deferred.complete(result)
                runFollowersParallel(domain)
            }
            result.isCloudflareBlocked -> {
                ProviderLogger.i(TAG_QUEUE, "executeAsLeader", "Leader CF BLOCKED - solving", "domain" to domain)
                
                // CRITICAL: use the final URL from the direct request if it redirected
                // This prevents solving for the old domain when we've been redirected to a new one
                val solveUrl = result.finalUrl?.takeIf { it.isNotBlank() } ?: leader.url
                
                // Check if we need to update domain before solving
                val requestDomain = extractDomain(leader.url)
                val finalDomain = extractDomain(solveUrl)
                
                if (requestDomain != finalDomain && finalDomain.isNotBlank()) {
                    ProviderLogger.i(TAG_QUEUE, "executeAsLeader", "Domain redirect before CF",
                        "from" to requestDomain, "to" to finalDomain)
                    onDomainRedirect(requestDomain, finalDomain)
                }
                
                val cfResult = solveCfAndRequest(solveUrl)
                
                if (cfResult.success) {
                    ProviderLogger.i(TAG_QUEUE, "executeAsLeader", "CF solved, retrying leader")
                    // Retry original action now that cookies are fresh
                    val retryResult = leader.action()
                    leader.deferred.complete(retryResult)
                    verifyAndRunFollowers(domain)
                } else {
                    ProviderLogger.w(TAG_QUEUE, "executeAsLeader", "CF solve failed")
                    leader.deferred.complete(cfResult)
                    failAllFollowers(domain, "CF solve failed")
                }
            }
            else -> {
                ProviderLogger.w(TAG_QUEUE, "executeAsLeader", "Leader FAILED", "domain" to domain, "error" to result.error?.message)
                leader.deferred.complete(result)
                failAllFollowers(domain, result.error?.message ?: "Leader request failed")
            }
        }
    }
    
    private suspend fun verifyAndRunFollowers(domain: String) {
        val followers = mutex.withLock {
            pendingRequests.remove(domain)?.drop(1) ?: emptyList()
        }
        
        if (followers.isEmpty()) return
        
        // First follower verifies that new cookies work
        val verifier = followers.first()
        ProviderLogger.d(TAG_QUEUE, "verifyAndRunFollowers", "Verifying cookies", "url" to verifier.url.take(80))
        
        val verifyResult = verifier.action()
        
        if (verifyResult.success) {
            ProviderLogger.i(TAG_QUEUE, "verifyAndRunFollowers", "Verification SUCCESS", "domain" to domain)
            verifier.deferred.complete(verifyResult)
            
            // Run remaining followers in parallel
            coroutineScope {
                followers.drop(1).forEach { request ->
                    launch {
                        val result = request.action()
                        request.deferred.complete(result)
                    }
                }
            }
        } else {
            ProviderLogger.w(TAG_QUEUE, "verifyAndRunFollowers", "Verification FAILED", "domain" to domain)
            followers.forEach { request ->
                request.deferred.complete(RequestResult.failure("Cookie verification failed after CF solve"))
            }
        }
    }
    
    private suspend fun runFollowersParallel(domain: String) {
        val followers = mutex.withLock {
            pendingRequests.remove(domain)?.drop(1) ?: emptyList()
        }
        
        if (followers.isEmpty()) return
        
        ProviderLogger.d(TAG_QUEUE, "runFollowersParallel", "Running followers", "count" to followers.size, "domain" to domain)
        
        coroutineScope {
            followers.forEach { request ->
                launch {
                    val result = request.action()
                    request.deferred.complete(result)
                }
            }
        }
    }
    
    private suspend fun failAllFollowers(domain: String, reason: String) {
        mutex.withLock {
            val followers = pendingRequests[domain]?.drop(1) ?: emptyList()
            if (followers.isNotEmpty()) {
                ProviderLogger.w(TAG_QUEUE, "failAllFollowers", "Failing followers", "count" to followers.size, "reason" to reason)
            }
            followers.forEach { request ->
                request.deferred.complete(RequestResult.failure(reason))
            }
            pendingRequests.remove(domain)
        }
    }
    
    private fun extractDomain(url: String): String {
        return try {
            URI(url).host?.removePrefix("www.") ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * Result of a queued request - matches Arabseed's richer implementation.
 */
data class RequestResult(
    val success: Boolean,
    val html: String?,
    val responseCode: Int,
    val finalUrl: String?,
    val error: Throwable? = null,
    val isCloudflareBlocked: Boolean = false
) {
    companion object {
        fun success(html: String, code: Int = 200, finalUrl: String) = RequestResult(
            success = true,
            html = html,
            responseCode = code,
            finalUrl = finalUrl
        )
        
        fun cloudflareBlocked(code: Int = 403, finalUrl: String? = null) = RequestResult(
            success = false,
            html = null,
            responseCode = code,
            finalUrl = finalUrl,
            isCloudflareBlocked = true
        )
        
        fun failure(reason: String, code: Int = -1) = RequestResult(
            success = false,
            html = null,
            responseCode = code,
            finalUrl = null,
            error = Exception(reason)
        )
        
        fun failure(error: Throwable, code: Int = -1) = RequestResult(
            success = false,
            html = null,
            responseCode = code,
            finalUrl = null,
            error = error
        )
    }
}
