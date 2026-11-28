package com.tareimbursement.app.service.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tareimbursement.app.MainActivity
import com.tareimbursement.app.container
import java.util.concurrent.TimeUnit

/** Background sync of team projects every ~15 minutes; notifies about new chat messages and bills. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.supabase.signedIn) return Result.success()
        return try {
            notify(applicationContext, c.sync.syncAll())
            Result.success()
        } catch (e: ApiException) {
            if (e.code == 401 || e.code == 0) Result.success() else Result.retry()
        } catch (e: Exception) { Result.retry() }
    }

    companion object {
        private const val WORK = "team-sync"
        private const val CHANNEL = "team"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)

        /** Short notification - names who wrote, never bill amounts or client data. */
        fun notify(context: Context, inc: Incoming) {
            if (inc.messages.isEmpty() && inc.bills == 0) return
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Team projects", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val title = when {
                inc.messages.isNotEmpty() && inc.bills > 0 -> "${inc.messages.size} new message(s), ${inc.bills} new bill(s)"
                inc.messages.isNotEmpty() -> "${inc.messages.size} new message(s) from ${inc.messages.map { it.authorName }.distinct().joinToString()}"
                else -> "${inc.bills} new bill(s) added by your team"
            }
            val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title).setContentText(inc.tripTitles.joinToString()).setAutoCancel(true).setContentIntent(pi)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            try { nm.notify(4201, n) } catch (_: SecurityException) { }
        }
    }
}
