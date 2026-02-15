package com.cloudstream.shared.extractors

/**
 * Configuration for selecting and interacting with elements in WebView.
 * Used by SnifferExtractor to click server buttons before video sniffing.
 */
data class SnifferSelector(
    /** CSS selector to find the element (e.g., "li[data-embed-id='1']") */
    val query: String,
    /** Attribute to validate (e.g., "data-embed-url") */
    val attr: String? = null,
    /** Optional regex pattern to match against attribute value */
    val regex: String? = null,
    /** Wait time in ms after clicking (default: 3000ms for player to load) */
    val waitAfterClick: Long = 3000L
) {
    /**
     * Serialize to JSON for URL encoding
     */
    fun toJson(): String {
        return buildString {
            append("""{"query":"${escapeJson(query)}""" )
            if (attr != null) append(",\"attr\":\"${escapeJson(attr)}\"")
            if (regex != null) append(",\"regex\":\"${escapeJson(regex)}\"")
            if (waitAfterClick != 3000L) append(",\"wait\":$waitAfterClick")
            append("}")
        }
    }

    companion object {
        /**
         * Deserialize from JSON
         */
        fun fromJson(json: String): SnifferSelector? {
            return try {
                val obj = org.json.JSONObject(json)
                SnifferSelector(
                    query = obj.getString("query"),
                    attr = obj.optString("attr", null),
                    regex = obj.optString("regex", null),
                    waitAfterClick = obj.optLong("wait", 3000L)
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun escapeJson(str: String): String {
            return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
    }
}
