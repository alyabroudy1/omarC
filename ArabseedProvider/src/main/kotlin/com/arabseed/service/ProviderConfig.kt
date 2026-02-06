package com.arabseed.service

data class ProviderConfig(
    val name: String,
    val fallbackDomain: String,
    val githubConfigUrl: String,
    val userAgent: String? = null,
    val syncWorkerUrl: String? = null,
    val skipHeadless: Boolean = false,
    val webViewEnabled: Boolean = true,
    val trustedDomains: List<String> = emptyList(),
    val validateWithContent: List<String> = emptyList()
)
