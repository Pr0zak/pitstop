package com.pitstop.util

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/**
 * Walk a Compose `LocalContext` back to the hosting [ComponentActivity].
 *
 * Needed to scope a ViewModel to the Activity from inside a NavHost,
 * where `LocalViewModelStoreOwner` resolves to the NavBackStackEntry
 * instead. `LocalActivity` would do the same job, but it only arrives
 * in activity-compose 1.10 and this module is on 1.9.3.
 *
 * Throws rather than returning null: every Compose surface in this app
 * is hosted by MainActivity, so a miss is a programming error and a
 * silent fallback would hide it.
 */
fun Context.requireActivity(): ComponentActivity {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    error("No ComponentActivity in the context chain for $this")
}
