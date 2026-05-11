package com.pitstop.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pitstop.MainActivity
import com.pitstop.R

/**
 * 1x1 home-screen widget that taps through to the Fuel tab with a
 * blank fillup form open (#121).
 *
 * Static content — no per-vehicle state in the widget face, no
 * background refresh. The widget is purely a launcher tile; all
 * the form pre-fill (vehicle, GPS lookup, suggested odometer)
 * happens once MainActivity opens the Fuel tab.
 *
 * Click intent uses [MainActivity.ACTION_ADD_FILLUP] which the
 * activity reads in onCreate to set the initial pager page to
 * the Fuel tab (index 3).
 */
class AddFillupWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context))
        }
    }

    companion object {
        /** Trigger a manual refresh when something the widget could
         *  reflect changes — call after install / on locale change. */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, AddFillupWidget::class.java)
            )
            if (ids.isNotEmpty()) {
                val views = buildRemoteViews(context)
                ids.forEach { mgr.updateAppWidget(it, views) }
            }
        }

        private fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_add_fillup)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_ADD_FILLUP
                // FLAG_ACTIVITY_NEW_TASK so launching from the home
                // screen creates a proper task; CLEAR_TOP so an
                // existing MainActivity instance is reused (we don't
                // want two stacked Fuel tabs).
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                /* requestCode = */ 0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // Set the click target on the root layout so the entire
            // widget face is tappable, not just the icon.
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            return views
        }
    }
}
