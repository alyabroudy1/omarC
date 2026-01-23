package com.arabseed.utils

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.api.Log
import com.arabseed.ConfigurableCloudflareKiller
import com.arabseed.service.ProviderSessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dialog-based Cloudflare challenge solver.
 */
object CloudflareChallengeDialog {
    private const val TAG = "CFDialog"
    private const val TIMEOUT_MS = 120_000L // 2 minutes

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solve(
        url: String,
        userAgent: String = ProviderSessionManager.UNIFIED_USER_AGENT
    ): ChallengeResult = withContext(Dispatchers.Main) {
        val activity = ActivityProvider.currentActivity
        
        if (activity == null) {
            Log.e(TAG, "No Activity context available")
            return@withContext ChallengeResult.failure("No Activity context")
        }
        
        val deferred = CompletableDeferred<ChallengeResult>()
        var resultDelivered = false
        
        // Create WebView (Minimal setup, Resolver handles logic)
        val webView = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            setBackgroundColor(Color.WHITE)
            webChromeClient = android.webkit.WebChromeClient()
        }
        
        // Create container layout
        val container = LinearLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            
            addView(TextView(activity).apply {
                text = "🔒 Security Check"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(16, 32, 16, 16)
            })
            
            addView(TextView(activity).apply {
                text = "Complete the security check to continue..."
                textSize = 14f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(16, 0, 16, 16)
            })
            
            addView(webView)
        }
        
        // Build Dialog
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.setContentView(container)
        dialog.setCancelable(true)
        
        val killer = ConfigurableCloudflareKiller(
            userAgent = userAgent,
            blockNonHttp = true,
            allowThirdPartyCookies = true,
            onCookiesExtracted = { domain, cookies ->
                if (!resultDelivered) {
                    resultDelivered = true
                    Log.i(TAG, "Challenge solved via Resolver! Cookies: ${cookies.keys}")
                    try {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.stopLoading()
                        webView.destroy()
                    } catch (_: Exception) {}
                    dialog.dismiss()
                    deferred.complete(ChallengeResult.success(cookies, "https://$domain"))
                }
            }
        )
        
        dialog.setOnCancelListener {
            if (!resultDelivered) {
                resultDelivered = true
                try {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.stopLoading()
                    webView.destroy()
                } catch (_: Exception) {}
                deferred.complete(ChallengeResult.cancelled())
            }
        }
        
        Log.i(TAG, "Showing CF challenge for: $url")
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setGravity(Gravity.CENTER)
        
        // Delegate logic to ConfigurableCloudflareKiller
        // This will block until timeout or success (handled by onCookiesExtracted)
        // Note: resolveChallenge is suspend.
        val response = killer.resolveChallenge(url, webView)
        
        // If resolveChallenge returns (timeout or success handled internally), ensure validation
        if (!resultDelivered) {
            if (response != null) {
                // Success case if callback missed? Or response is sufficient.
                // Cookies should be in savedCookies.
                val host = java.net.URI(url).host
                val cookies = killer.savedCookies[host] ?: emptyMap()
                if (cookies.isNotEmpty()) {
                    resultDelivered = true
                    try {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.stopLoading()
                        webView.destroy()
                    } catch (_: Exception) {}
                    dialog.dismiss()
                    deferred.complete(ChallengeResult.success(cookies, url))
                } else {
                    resultDelivered = true
                    try {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.stopLoading()
                        webView.destroy()
                    } catch (_: Exception) {}
                    dialog.dismiss()
                    deferred.complete(ChallengeResult.failure("Resolved but no cookies found"))
                }
            } else {
                resultDelivered = true
                try {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.stopLoading()
                    webView.destroy()
                } catch (_: Exception) {}
                dialog.dismiss()
                deferred.complete(ChallengeResult.timeout())
            }
        }
        
        deferred.await()
    }

    data class ChallengeResult(
        val success: Boolean,
        val cookies: Map<String, String>,
        val finalUrl: String,
        val reason: String
    ) {
        companion object {
            fun success(cookies: Map<String, String>, finalUrl: String) = ChallengeResult(true, cookies, finalUrl, "solved")
            fun timeout() = ChallengeResult(false, emptyMap(), "", "timeout")
            fun cancelled() = ChallengeResult(false, emptyMap(), "", "cancelled")
            fun failure(reason: String) = ChallengeResult(false, emptyMap(), "", reason)
        }
    }
}
