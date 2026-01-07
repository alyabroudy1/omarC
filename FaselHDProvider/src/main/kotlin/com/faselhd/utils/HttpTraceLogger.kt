package com.faselhd.utils

import android.util.Log

object HttpTraceLogger {
    private const val TAG = "HTTP_ANALYSIS"

    fun logRequest(source: String, url: String, method: String, headers: Map<String, String>) {
        Log.e(TAG, "[$source] REQUEST: $method $url")
        logHeaders(headers)
    }

    fun logResponse(source: String, url: String, code: Int, headers: Map<String, String>) {
        Log.e(TAG, "[$source] RESPONSE: $code $url")
    }

    private fun logHeaders(headers: Map<String, String>) {
        if (headers.isEmpty()) {
            Log.e(TAG, "  Headers: [None]")
            return
        }
        headers.forEach { (name, value) ->
            if (name.equals("Cookie", ignoreCase = true) || name.equals("User-Agent", ignoreCase = true)) {
                 Log.e(TAG, "  Header: $name = $value")
            }
        }
        headers.forEach { (name, value) ->
            if (!name.equals("Cookie", ignoreCase = true) && !name.equals("User-Agent", ignoreCase = true)) {
                 Log.e(TAG, "  Header: $name = $value")
            }
        }
    }
}
