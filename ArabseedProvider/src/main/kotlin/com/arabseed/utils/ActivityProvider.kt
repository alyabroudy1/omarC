package com.arabseed.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.lagradost.api.Log
import java.lang.ref.WeakReference

/**
 * Provides access to the current foreground Activity.
 * 
 * This is necessary for showing Dialogs from provider code, which runs
 * on background threads without direct access to an Activity.
 * 
 * ## Initialization:
 * Call `init(application)` once when the provider first loads.
 * 
 * ## Usage:
 * ```kotlin
 * val activity = ActivityProvider.currentActivity
 * if (activity != null) {
 *     // Show dialog
 * }
 * ```
 */
object ActivityProvider {
    private const val TAG = "ActivityProvider"
    
    private var activityRef: WeakReference<Activity>? = null
    private var isInitialized = false
    
    /**
     * Get the current foreground Activity, or null if none is in foreground.
     */
    val currentActivity: Activity?
        get() {
            var activity = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            if (activity == null) {
                activity = getTopActivityReflection()
                if (activity != null) {
                    setActivity(activity) // Cache it
                }
            }
            Log.d(TAG, "currentActivity requested: ${activity?.javaClass?.simpleName ?: "null"}")
            return activity
        }
    
    private fun getTopActivityReflection(): Activity? {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(activityThread) as Map<*, *>
            
            for (activityRecord in activities.values) {
                val activityRecordClass = activityRecord!!.javaClass
                val pausedField = activityRecordClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(activityRecord)) {
                    val activityField = activityRecordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    return activityField.get(activityRecord) as Activity
                }
            }
        } catch (e: Exception) {
            // Log.w(TAG, "Reflection for activity failed: ${e.message}") // Silence to avoid spam
        }
        return null
    }
    
    /**
     * Manually set the current Activity. Useful when init() is called
     * after an Activity has already resumed.
     */
    fun setActivity(activity: Activity?) {
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            Log.i(TAG, "Activity manually set: ${activity.javaClass.simpleName}")
            activityRef = WeakReference(activity)
        }
    }
    
    /**
     * Initialize the Activity provider by registering lifecycle callbacks.
     * Call this once when the provider first loads.
     * 
     * @param application The Application instance (can be obtained from AcraApplication.context)
     */
    fun init(application: Application) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }
        isInitialized = true
        
        Log.i(TAG, "Initializing ActivityProvider with lifecycle callbacks")
        
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                Log.d(TAG, "Activity created: ${activity.javaClass.simpleName}")
                // Set on create as well to catch already-running Activities
                activityRef = WeakReference(activity)
            }
            
            override fun onActivityStarted(activity: Activity) {
                Log.d(TAG, "Activity started: ${activity.javaClass.simpleName}")
                activityRef = WeakReference(activity)
            }
            
            override fun onActivityResumed(activity: Activity) {
                Log.d(TAG, "Activity resumed: ${activity.javaClass.simpleName}")
                activityRef = WeakReference(activity)
            }
            
            override fun onActivityPaused(activity: Activity) {}
            
            override fun onActivityStopped(activity: Activity) {
                // Only clear if this is the currently tracked activity
                if (activityRef?.get() === activity) {
                    Log.d(TAG, "Activity stopped, clearing ref: ${activity.javaClass.simpleName}")
                    activityRef = null
                }
            }
            
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            
            override fun onActivityDestroyed(activity: Activity) {
                if (activityRef?.get() === activity) {
                    Log.d(TAG, "Activity destroyed, clearing ref: ${activity.javaClass.simpleName}")
                    activityRef = null
                }
            }
        })
    }
    
    /**
     * Initialize with compatibility for Plugin usage.
     * Safely gets Application context.
     */
    fun initCompat(context: Context) {
         val app = context.applicationContext as? Application
         if (app != null) {
             init(app)
         } else {
             Log.e(TAG, "Failed to get Application context from $context")
         }
    }
}
