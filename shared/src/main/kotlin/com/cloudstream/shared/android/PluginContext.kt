package com.cloudstream.shared.android

import android.content.Context
import java.lang.ref.WeakReference

/**
 * Holds a reference to the plugin Context for use throughout the provider.
 * 
 * Initialize once when the plugin loads via `init(context)`.
 */
object PluginContext {
    private var contextRef: WeakReference<Context>? = null

    fun init(context: Context) {
        contextRef = WeakReference(context)
    }

    val context: Context?
        get() = contextRef?.get()
}
