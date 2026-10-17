package com.edgarguitarist.audiopc.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.edgarguitarist.audiopc.data.ServiceLocator
import java.util.concurrent.TimeUnit

/** Keeps widgets fresh when the app is not running (the WebSocket only lives with the app). */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ok = runCatching { ServiceLocator.repository(applicationContext).refresh().isSuccess }.getOrDefault(false)
        WidgetStatus.setOffline(applicationContext, !ok)
        Widgets.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "audiopc-widget-refresh"

        /**
         * Two receivers share one worker, so each one's onEnabled/onDisabled just re-evaluates:
         * work exists exactly while either widget has an instance.
         */
        fun sync(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (Widgets.hasInstances(context)) {
                val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                wm.cancelUniqueWork(NAME)
            }
        }
    }
}
