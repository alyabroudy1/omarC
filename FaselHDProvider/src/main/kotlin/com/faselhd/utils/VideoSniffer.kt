package com.faselhd.utils

import android.app.Activity
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object VideoSniffer {
    
    private const val TAG = "VideoSniffer"
    private const val TIMEOUT_MS = 60_000L
    
    @Volatile
    private var pendingCallback: ((Result?) -> Unit)? = null
    
    data class Result(
        val url: String,
        val headers: Map<String, String>
    )

    suspend fun sniff(url: String, headers: Map<String, String> = emptyMap()): Result? {
        Log.d(TAG, "Sniffing requested for: $url with ${headers.size} headers")
        
        val appContext = PluginContext.context ?: run {
            Log.e(TAG, "Application context is null (PluginContext not initialized)")
            return null
        }
        
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    pendingCallback = { result ->
                        pendingCallback = null
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                    
                    continuation.invokeOnCancellation {
                        pendingCallback = null
                    }
                    
                    val intent = VideoSnifferActivity.createIntent(appContext, url, headers)
                    appContext.startActivity(intent)
                    Log.d(TAG, "Launched VideoSnifferActivity")
                }
            }
        }
    }
    
    fun onResult(resultCode: Int, data: Intent?) {
        Log.d(TAG, "onResult: resultCode=$resultCode")
        
        val result: Result? = if (resultCode == Activity.RESULT_OK && data != null) {
            val videoUrl = data.getStringExtra(VideoSnifferActivity.RESULT_VIDEO_URL)
            @Suppress("UNCHECKED_CAST")
            val headers = data.getSerializableExtra(VideoSnifferActivity.RESULT_HEADERS) as? Map<String, String>
            
            if (videoUrl != null && headers != null) {
                Result(videoUrl, headers)
            } else null
        } else null
        
        pendingCallback?.invoke(result)
    }
}
