package com.pulse.rsswidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.pulse.rsswidget.data.SettingsStore
import com.pulse.rsswidget.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PulseWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = PulseWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Ensure background refresh is scheduled and pull fresh content for newly placed widgets.
        CoroutineScope(Dispatchers.IO).launch {
            val store = SettingsStore(context)
            RefreshScheduler.schedule(context, store.getInterval(), store.getWifiOnly())
            RefreshScheduler.refreshNow(context)
        }
    }
}
