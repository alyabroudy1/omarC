package com.cloudstream.shared.android

import android.content.Context
import java.lang.ref.WeakReference

/**
 * Holds a reference to the plugin Context for use throughout the provider.
 * 
 * Initialize once when the plugin loads via `init(context)`.
 */
object PluginContext {
    var context: Context? = null
        private set

    fun init(ctx: Context) {
        // Holding applicationContext strongly prevents GC without leaking Activity instances
        context = ctx.applicationContext ?: ctx
    }
}
