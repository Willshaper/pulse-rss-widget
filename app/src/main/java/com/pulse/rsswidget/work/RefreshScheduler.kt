package com.pulse.rsswidget.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object RefreshScheduler {

    private const val PERIODIC = "pulse_periodic_refresh"
    private const val ONE_SHOT = "pulse_refresh_now"

    /** Android enforces a 15-minute minimum for periodic work. */
    const val MIN_INTERVAL = 15

    /** Periodic background refresh. Honors the Wi-Fi-only preference. */
    fun schedule(context: Context, minutes: Int, wifiOnly: Boolean) {
        val interval = maxOf(MIN_INTERVAL, minutes).toLong()
        val net = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Manual/triggered refresh — always runs, even on a metered connection.
     * [showIndicator] = true means a user pressed Refresh, so show the progress overlay.
     */
    fun refreshNow(context: Context, showIndicator: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInputData(workDataOf(RefreshWorker.KEY_SHOW_INDICATOR to showIndicator))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
    }
}
