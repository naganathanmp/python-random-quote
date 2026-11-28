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
import kotlinx.coroutines.launch

/** Sync positions per project/table, in private preferences. */
class PrefsCursorStore(context: Context) : com.tareimbursement.app.service.sync.CursorStore {
    private val sp = context.getSharedPreferences("ta_sync", Context.MODE_PRIVATE)
    override fun get(key: String): String = sp.getString(key, "")!!
    override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
    override fun clear(prefix: String) { sp.edit().apply { sp.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) } }.apply() }
}

/** Simple manual dependency container. */
class AppContainer(
    val context: Context,
    val db: AppDatabase,
    extractor: TextExtractor = OnDeviceTextExtractor(),
    renderer: com.tareimbursement.app.service.PageRenderer = com.tareimbursement.app.service.AndroidPageRenderer(),
    cipher: com.tareimbursement.app.service.SecretCipher = com.tareimbursement.app.service.KeystoreCipher(),
) {
    companion object { const val HF_TOKEN = "hf_token"; const val SESSION = "server_session" }

    val prefs = Prefs(context)
    val repo = Repository(db, context.filesDir)
    /** App-wide background scope (AI reading, sync). */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    val secrets = com.tareimbursement.app.service.SecretStore(context, cipher)
    val models = com.tareimbursement.app.service.ai.ModelManager(context) { secrets.get(HF_TOKEN) }
    val ai = com.tareimbursement.app.service.ai.AiRunner(context, repo, models, { prefs.aiEnabled }, appScope)
    val importer = FileImporter(
        context.contentResolver, repo, extractor, context.cacheDir,
        uriAllowed = { com.tareimbursement.app.service.UriPolicy.isAllowedForImport(context, it) },
        aiEnabled = { prefs.aiEnabled && models.installed() != null },
        whoAmI = { prefs.profile.value.name to (supabase.session?.userId ?: "") },
    )
    val signing = com.tareimbursement.app.service.SigningIdentity(context)
    val exporter = ExportManager(repo, renderer, identity = { e -> signing.get(e.name, e.employeeCode, e.organisation) }, appVersion = BuildConfig.VERSION_NAME)
    val backup = BackupManager(context) { db }
    val jobs = com.tareimbursement.app.service.Jobs(importer, exporter) { ai.kick() }

    // ---- Team sharing (Supabase). Off until the person enters a server and signs in.
    val supabase = com.tareimbursement.app.service.sync.SupabaseClient(
        { com.tareimbursement.app.service.sync.ServerConfig(prefs.serverUrl, prefs.serverKey) },
        object : com.tareimbursement.app.service.sync.SessionStore {
            override fun load() = com.tareimbursement.app.service.sync.Session.fromJson(secrets.get(SESSION))
            override fun save(s: com.tareimbursement.app.service.sync.Session?) { secrets.put(SESSION, s?.toJson()) }
        },
    )
    val sync = com.tareimbursement.app.service.sync.SyncEngine(repo, supabase, PrefsCursorStore(context), { prefs.profile.value.name })
    private var syncJob: kotlinx.coroutines.Job? = null

    /** Uploads local changes on shared claims a few seconds after the last edit. */
    fun scheduleSync(tripId: Long) {
        if (!supabase.signedIn) return
        syncJob?.cancel()
        syncJob = appScope.launch {
            kotlinx.coroutines.delay(3000)
            try { sync.syncTrip(tripId) } catch (_: Exception) { }
        }
    }

    init {
        repo.onLocalChange = { scheduleSync(it) }
    }
    /** Files shared into the app from other apps, waiting for the user to pick a claim. */
    val pendingShared = kotlinx.coroutines.flow.MutableStateFlow<List<android.net.Uri>>(emptyList())
}

class TaApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        com.tareimbursement.app.service.CrashLog.install(this, BuildConfig.VERSION_NAME)
        if (!::container.isInitialized) container = AppContainer(this, AppDatabase.build(this))
        // Finish any AI reading that was interrupted, and retry bills where the AI couldn't start.
        container.appScope.launch {
            try { if (container.models.installed() != null) container.repo.expenses.requeueAiLoadFailures() } catch (_: Exception) { }
            container.ai.runQueue()
        }
        // Daily reminders and the weekly automatic backup.
        try { com.tareimbursement.app.service.HousekeepingWorker.schedule(this) } catch (_: Exception) { }
        if (container.supabase.signedIn) {
            com.tareimbursement.app.service.sync.SyncWorker.schedule(this)
            container.appScope.launch { try { container.sync.syncAll() } catch (_: Exception) { } }
        }
        // Clean stale import staging / restore leftovers and old camera captures (> 2 days).
        try {
            cacheDir.resolve("import").deleteRecursively()
            cacheDir.resolve("restore").deleteRecursively()
            val cutoff = System.currentTimeMillis() - 2L * 24 * 3600 * 1000
            cacheDir.resolve("camera").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        } catch (_: Exception) { }
    }
}

val Context.container: AppContainer get() = (applicationContext as TaApp).container
