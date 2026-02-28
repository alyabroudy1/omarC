package com.youtube.innertube

/**
 * InnerTube API configuration constants.
 * 
 * InnerTube is YouTube's internal JSON API used by all YouTube clients.
 * Using the WEB client context provides the most complete search/browse results.
 */
object InnerTubeConfig {
    
    /** Public InnerTube API key (embedded in youtube.com, not secret) */
    const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    
    /** Base URL for all InnerTube API endpoints */
    const val BASE_URL = "https://www.youtube.com/youtubei/v1"
    
    /** API endpoints */
    const val SEARCH_ENDPOINT = "$BASE_URL/search?key=$API_KEY&prettyPrint=false"
    const val BROWSE_ENDPOINT = "$BASE_URL/browse?key=$API_KEY&prettyPrint=false"
    const val NEXT_ENDPOINT   = "$BASE_URL/next?key=$API_KEY&prettyPrint=false"
    const val PLAYER_ENDPOINT = "$BASE_URL/player?key=$API_KEY&prettyPrint=false"
    
    /** WEB client version — update periodically when YouTube changes */
    const val CLIENT_VERSION = "2.20250220.01.00"
    const val CLIENT_NAME = "WEB"

    /** ANDROID_VR client (bypasses strict PO-Token/n-sig checks for streaming) */
    const val VR_CLIENT_VERSION = "1.56.29"
    const val VR_CLIENT_NAME = "ANDROID_VR"
    
    /** User agent matching the client version */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    
    /** Consent cookie to bypass YouTube consent screen in EU */
    const val CONSENT_COOKIE = "SOCS=CAISNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjMwODI5LjA3X3AxGgJlbiACGgYIgJnPpwY"
    
    /**
     * Build the InnerTube client context JSON body.
     * This is required for every API call.
     */
    fun buildContext(
        hl: String = "ar", 
        gl: String = "SA",
        clientName: String = CLIENT_NAME,
        clientVersion: String = CLIENT_VERSION
    ): Map<String, Any> {
        return mapOf(
            "context" to mapOf(
                "client" to mapOf(
                    "clientName" to clientName,
                    "clientVersion" to clientVersion,
                    "hl" to hl,
                    "gl" to gl,
                    "userAgent" to USER_AGENT
                )
            )
        )
    }
    
    /** Search filter params (pre-encoded protobuf) */
    object SearchFilters {
        /** Filter: Videos only (excludes Shorts, channels, playlists) */
        const val VIDEOS_ONLY = "EgIQAQ%3D%3D"
        /** Filter: Playlists only */
        const val PLAYLISTS_ONLY = "EgIQAw%3D%3D"
        /** Filter: Videos, sorted by upload date */
        const val VIDEOS_BY_DATE = "CAISAhAB"
    }
    
    /** Browse IDs for main page sections */
    object BrowseIds {
        const val TRENDING = "FEtrending"
        const val MUSIC_TRENDING = "FEmusic_trending"
    }
}
