package com.arabseed.utils

import android.content.Context
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object ImageCache {

    private const val CACHE_DIR_NAME = "arabseed_images"
    private val client by lazy { 
        Requests().apply { 
            // Use PluginContext.context!! assuming it's initialized by ArabseedNew
            initClient(PluginContext.context!!) 
        } 
    }

    private fun getCacheDir(): File {
        val context = PluginContext.context!!
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun hashUrl(url: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".webp"
    }

    suspend fun getOrDownload(url: String, headers: Map<String, String>): String {
        return withContext(Dispatchers.IO) {
            try {
                // If not an Arabseed protected image, return original
                if (!url.contains("asd.pics")) return@withContext url

                val fileName = hashUrl(url)
                val file = File(getCacheDir(), fileName)

                if (file.exists() && file.length() > 0) {
                    return@withContext "file://${file.absolutePath}"
                }

                // Download
                val response = client.get(url, headers = headers)
                if (response.isSuccessful) {
                    val bytes = response.body.bytes()
                    file.writeBytes(bytes)
                    return@withContext "file://${file.absolutePath}"
                } else {
                    // Fallback to original if download fails
                    return@withContext url
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext url
            }
        }
    }
}
