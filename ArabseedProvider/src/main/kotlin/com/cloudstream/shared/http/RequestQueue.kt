package com.cloudstream.shared.http

import com.lagradost.api.Log
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
 */
class RequestQueue(
    private val executeRequest: suspend (String) -> RequestResult,
    private val solveCfAndRequest: suspend (String) -> RequestResult
) {
    private val TAG = "RequestQueue"
    private val mutex = Mutex()
    
    data class QueuedRequest(
        val url: String,
        val deferred: CompletableDeferred<RequestResult>
    )
    
    private val pendingRequests = mutableMapOf<String, MutableList<QueuedRequest>>()
    
    /**
     * Queue a request. Returns when the request is complete.
     */
    suspend fun enqueue(url: String): RequestResult {
        val domain = extractDomain(url)
        val deferred = CompletableDeferred<RequestResult>()
        val request = QueuedRequest(url, deferred)
        
        val isLeader = mutex.withLock {
            val queue = pendingRequests.getOrPut(domain) { mutableListOf() }
            queue.add(request)
            queue.size == 1
        }
        
        if (isLeader) {
            Log.d(TAG, "Request is LEADER for $domain: $url")
            executeAsLeader(domain, request)
        } else {
            Log.d(TAG, "Request is FOLLOWER for $domain: $url")
        }
        
        return deferred.await()
    }
    
    private suspend fun executeAsLeader(domain: String, leader: QueuedRequest) {
        // Step 1: Execute leader request
        val result = executeRequest(leader.url)
        
        when {
            result.success -> {
                Log.i(TAG, "Leader SUCCESS for $domain")
                leader.deferred.complete(result)
                runFollowersParallel(domain)
            }
            result.isCloudflareBlocked -> {
                Log.i(TAG, "Leader CF BLOCKED for $domain - solving...")
                val cfResult = solveCfAndRequest(leader.url)
                leader.deferred.complete(cfResult)
                
                if (cfResult.success) {
                    verifyAndRunFollowers(domain)
                } else {
                    failAllFollowers(domain, "CF solve failed")
                }
            }
            else -> {
                Log.w(TAG, "Leader FAILED for $domain: ${result.error?.message}")
                leader.deferred.complete(result)
                failAllFollowers(domain, result.error?.message ?: "Leader request failed")
            }
        }
    }
    
    private suspend fun verifyAndRunFollowers(domain: String) {
        val followers = mutex.withLock {
            val list = pendingRequests[domain]
            // We DO NOT remove yet, because if verification fails, we might want to try something else?
            // Actually, if verification fails, we failAllFollowers, which removes it.
            // If verification succeeds, we continue.
            // To be safe against incoming requests, we should probably "claim" this batch.
            // Simplest safe strategy: Remove from map immediately. 
            // If verify fails, we fail THESE followers. New requests start fresh.
            pendingRequests.remove(domain)
            list?.drop(1) ?: emptyList()
        }
        
        if (followers.isEmpty()) {
            return
        }
        
        // First follower verifies that new cookies work
        val verifier = followers.first()
        Log.d(TAG, "Verifying cookies with first follower: ${verifier.url}")
        
        val verifyResult = executeRequest(verifier.url)
        
        if (verifyResult.success) {
            Log.i(TAG, "Cookie verification SUCCESS for $domain")
            verifier.deferred.complete(verifyResult)
            
            // Run remaining followers in parallel
            coroutineScope {
                followers.drop(1).forEach { request ->
                    launch {
                        val result = executeRequest(request.url)
                        request.deferred.complete(result)
                    }
                }
            }
        } else {
            Log.w(TAG, "Cookie verification FAILED for $domain")
            // Apply failure to the batch we extracted
            followers.forEach { request ->
                 request.deferred.complete(RequestResult.failure("Cookie verification failed after CF solve"))
            }
        }
    }
    
    private suspend fun runFollowersParallel(domain: String) {
        val followers = mutex.withLock {
            val list = pendingRequests[domain]
            pendingRequests.remove(domain) // Remove immediately!
            list?.drop(1) ?: emptyList()
        }
        
        if (followers.isEmpty()) {
            return
        }
        
        Log.d(TAG, "Running ${followers.size} followers in parallel for $domain")
        
        coroutineScope {
            followers.forEach { request ->
                launch {
                    val result = executeRequest(request.url)
                    request.deferred.complete(result)
                }
            }
        }
    }
    
    private suspend fun failAllFollowers(domain: String, reason: String) {
        mutex.withLock {
            val followers = pendingRequests[domain]?.drop(1) ?: emptyList()
            if (followers.isNotEmpty()) {
                Log.w(TAG, "Failing ${followers.size} followers for $domain: $reason")
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
