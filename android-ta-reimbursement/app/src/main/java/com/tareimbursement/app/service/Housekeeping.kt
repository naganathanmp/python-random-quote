package com.tareimbursement.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tareimbursement.app.MainActivity
import com.tareimbursement.app.container
import com.tareimbursement.app.core.ClaimStatus
import com.tareimbursement.app.data.Repository
import com.tareimbursement.app.data.TripEntity
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** What the daily reminder says (pure, testable). */
object Reminders {
    data class Item(val tripId: Long, val title: String, val text: String)

    /**
     * Draft claims whose trip ended 7+ days ago ("not submitted yet") and draft claims with bills
     * still to review. One line per claim; nothing for archived claims.
     */
    fun due(trips: List<TripEntity>, toReview: Map<Long, Int>, today: LocalDate): List<Item> = trips
        .filter { it.claimStatus == ClaimStatus.DRAFT && it.myRole != "REMOVED" && it.myRole != "MEMBER" }
        .mapNotNull { t ->
            val days = today.toEpochDay() - t.endEpochDay
            val review = toReview[t.id] ?: 0
            when {
                days >= 7 -> Item(t.id, t.title, "Trip ended $days days ago - not submitted yet" + if (review > 0) " ($review bills to review)" else "")
                review > 0 && days >= 0 -> Item(t.id, t.title, "$review bill${if (review == 1) "" else "s"} still to review")
                else -> null
            }
        }
}

/** Daily check for reminders and the weekly automatic backup. */
class HousekeepingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val ui = c.prefs.ui.value
        try { if (ui.reminders) remind(applicationContext, c.repo) } catch (_: Exception) { }
        if (ui.backupFolder.isNotBlank() && System.currentTimeMillis() - ui.lastAutoBackup >= 6.5 * 24 * 3600 * 1000) {
            try { AutoBackup.run(applicationContext) } catch (_: Exception) { return Result.retry() }
        }
        return Result.success()
    }

    private suspend fun remind(ctx: Context, repo: Repository) {
        val trips = repo.trips.all()
        val review = trips.associate { t -> t.id to repo.expenses.forTrip(t.id).count { !it.confirmed } }
        val items = Reminders.due(trips, review, LocalDate.now())
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Claim reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val title = if (items.size == 1) items[0].title else "${items.size} claims need attention"
        val body = items.take(4).joinToString("\n") { if (items.size == 1) it.text else "${it.title}: ${it.text}" }
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title)
            .setContentText(body.lineSequence().first()).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(pi).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { nm.notify(4301, n) } catch (_: SecurityException) { }
    }

    companion object {
        private const val CHANNEL = "reminders"
        private const val WORK = "housekeeping"
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HousekeepingWorker>(1, TimeUnit.DAYS).build())
        }
    }
}

/** Weekly backup ZIP into a folder the person picked (phone storage, Google Drive…); keeps the last 4. */
object AutoBackup {
    private const val PREFIX = "TA-Reimburse-autobackup-"

    fun run(ctx: Context): String {
        val c = ctx.container
        val tree = Uri.parse(c.prefs.ui.value.backupFolder)
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = PREFIX + LocalDate.now() + ".zip"
        val file = DocumentsContract.createDocument(ctx.contentResolver, dir, "application/zip", name) ?: throw IllegalStateException("Can't write to the backup folder")
        c.backup.backup(file)
        c.prefs.updateUi { it.copy(lastAutoBackup = System.currentTimeMillis()) }
        pruneOld(ctx, tree)
        return name
    }

    private fun pruneOld(ctx: Context, tree: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
            while (cur.moveToNext()) { val n = cur.getString(1) ?: continue; if (n.startsWith(PREFIX)) found += cur.getString(0) to n }
        }
        found.sortedByDescending { it.second }.drop(4).forEach { (id, _) ->
            try { DocumentsContract.deleteDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } catch (_: Exception) { }
        }
    }
}
