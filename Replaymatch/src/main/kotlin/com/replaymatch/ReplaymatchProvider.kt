package com.replaymatch

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlin.getValue
import androidx.preference.PreferenceManager
import com.cloudstream.shared.android.PluginContext
@CloudstreamPlugin
class ReplaymatchProvider : Plugin() {
    override fun load(context: Context) {
        PluginContext.init(context)
        val provider = FullMatchShowsProvider()
        provider.context = context
        registerMainAPI(provider)
        val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(context) }

        openSettings = { activityContext ->
            (activityContext as? AppCompatActivity)?.let { activity ->

                ReplaymatchSettings().show(activity.supportFragmentManager, "ReplaymatchSettings")
            }
        }
    }
}