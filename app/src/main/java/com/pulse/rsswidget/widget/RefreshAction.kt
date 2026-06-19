package com.pulse.rsswidget.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.pulse.rsswidget.work.RefreshScheduler

/** Manual refresh button: enqueue the refresh; the worker owns the overlay flag. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        RefreshScheduler.refreshNow(context, showIndicator = true)
    }
}
