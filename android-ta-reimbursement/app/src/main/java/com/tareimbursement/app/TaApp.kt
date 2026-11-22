package com.tareimbursement.app

import android.app.Application
import android.content.Context
import com.tareimbursement.app.data.AppDatabase
import com.tareimbursement.app.data.Prefs
import com.tareimbursement.app.data.Repository
import com.tareimbursement.app.service.BackupManager
import com.tareimbursement.app.service.ExportManager
import com.tareimbursement.app.service.FileImporter
import com.tareimbursement.app.service.OnDeviceTextExtractor
import com.tareimbursement.app.service.TextExtractor

/** Simple manual dependency container. */
class AppContainer(
    val context: Context,
    val db: AppDatabase,
    extractor: TextExtractor = OnDeviceTextExtractor(),
    renderer: com.tareimbursement.app.service.PageRenderer = com.tareimbursement.app.service.AndroidPageRenderer(),
) {
    val prefs = Prefs(context)
    val repo = Repository(db, context.filesDir)
    val importer = FileImporter(context.contentResolver, repo, extractor, context.cacheDir)
    val exporter = ExportManager(repo, renderer)
    val backup = BackupManager(context) { db }
    val jobs = com.tareimbursement.app.service.Jobs(importer, exporter)
    /** Files shared into the app from other apps, waiting for the user to pick a claim. */
    val pendingShared = kotlinx.coroutines.flow.MutableStateFlow<List<android.net.Uri>>(emptyList())
}

class TaApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        if (!::container.isInitialized) container = AppContainer(this, AppDatabase.build(this))
        // Clean stale import staging left by a crash.
        cacheDir.resolve("import").deleteRecursively()
    }
}

val Context.container: AppContainer get() = (applicationContext as TaApp).container
