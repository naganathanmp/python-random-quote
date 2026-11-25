package com.tareimbursement.app.service.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tareimbursement.app.container
import com.tareimbursement.app.core.ai.ModelCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/** Downloads the AI model in the background (survives leaving the app; resumes after network loss). */
class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val model = ModelCatalog.byId(inputData.getString(KEY_MODEL)) ?: return@coroutineScope Result.failure()
        val mm = applicationContext.container.models
        try { setForeground(info("Downloading ${model.displayName}", 0)) } catch (_: Throwable) { /* background start not allowed: run without notification */ }
        val progress = launch {
            mm.state.collect { s -> if (s is ModelState.Downloading) try { setForeground(info("Downloading ${model.displayName}", s.percent)) } catch (_: Throwable) { } }
        }
        val ok = mm.download(model) { isStopped }
        progress.cancel()
        if (ok) {
            applicationContext.container.ai.kick()
            Result.success()
        } else if (isStopped || (mm.state.first() as? ModelState.Failed)?.message?.contains("resume") == true) Result.retry()
        else if (runAttemptCount < 5 && (mm.state.first() as? ModelState.Failed)?.message?.let { it.contains("denied") || it.contains("space") || it.contains("damaged") } != true) Result.retry()
        else Result.failure()
    }

    private fun info(title: String, percent: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI model download", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText("$percent%")
            .setProgress(100, percent, percent == 0).setOngoing(true).setOnlyAlertOnce(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        const val KEY_MODEL = "model"
        const val CHANNEL = "ai_download"
        const val NOTIFICATION_ID = 4101
        const val WORK = "ai-model-download"

        fun start(context: Context, modelId: String, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL to modelId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}
