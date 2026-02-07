package com.cloudstream.shared.service

import android.content.Context
import com.cloudstream.shared.logging.ProviderLogger

/**
 * SINGLETON: Global access to ProviderHttpService
 * 
 * This allows extractors and other components to access the HTTP service
 * without needing a reference to the provider instance.
 * 
 * Critical for:
 * - Extractors making authenticated requests with CF session
 * - Avoiding 403 errors by using shared session cookies
 * - Centralized session management across the app
 */
object ProviderHttpServiceHolder {
    private const val TAG = "ProviderHttpServiceHolder"
    
    @Volatile
    private var instance: ProviderHttpService? = null
    
    /**
     * Initialize the singleton with a ProviderHttpService instance.
     * Should be called once during provider initialization.
     */
    fun initialize(service: ProviderHttpService) {
        ProviderLogger.i(TAG, "initialize", "HTTP Service singleton initialized")
        instance = service
    }
    
    /**
     * Get the singleton instance.
     * Returns null if not initialized.
     */
    fun getInstance(): ProviderHttpService? {
        return instance
    }
    
    /**
     * Check if the service is initialized.
     */
    fun isInitialized(): Boolean {
        return instance != null
    }
    
    /**
     * Clear the instance (for testing or logout).
     */
    fun clear() {
        ProviderLogger.i(TAG, "clear", "HTTP Service singleton cleared")
        instance = null
    }
}
