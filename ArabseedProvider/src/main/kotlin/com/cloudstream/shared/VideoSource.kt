package com.cloudstream.shared

/**
 * Video source extracted from player page.
 */
data class VideoSource(
    val url: String,
    val label: String,
    val headers: Map<String, String>
)
