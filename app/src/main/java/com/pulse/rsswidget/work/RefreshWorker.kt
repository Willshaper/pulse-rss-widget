package com.pulse.rsswidget.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pulse.rsswidget.data.FeedRepository
import com.pulse.rsswidget.data.SettingsStore
import com.pulse.rsswidget.widget.PulseWidget
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Refreshes all feeds. The refresh overlay flag is owned entirely by this worker:
 * it is set true only when the worker actually starts, and always cleared in a
 * non-cancellable block. The widget observes the flag reactively, so the overlay
 * appears and clears as the flag flips.
 */
class RefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        val show = inputData.getBoolean(KEY_SHOW_INDICATOR, false)
        val start = System.currentTimeMillis()

        if (show) {
            store.setRefreshing(true)
            PulseWidget().updateAll(applicationContext)
        }

        val result = try {
            FeedRepository(applicationContext).refresh()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }

        withContext(NonCancellable) {
            if (show && !isStopped) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed < MIN_VISIBLE_MS) delay(MIN_VISIBLE_MS - elapsed)
            }
            store.setRefreshing(false)
            PulseWidget().updateAll(applicationContext)
        }
        return result
    }

    companion object {
        const val KEY_SHOW_INDICATOR = "show_indicator"
        private const val MIN_VISIBLE_MS = 800L
    }
}
