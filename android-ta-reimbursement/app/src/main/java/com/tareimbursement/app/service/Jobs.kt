package com.tareimbursement.app.service

import android.net.Uri
import com.tareimbursement.app.core.EmployeeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class JobState {
    data class Running(val message: String, val done: Int = 0, val total: Int = 0) : JobState()
    data class ImportDone(val result: ImportResult) : JobState()
    data class GenerateDone(val outputs: ClaimOutputs) : JobState()
    data class Failed(val message: String) : JobState()
}

/**
 * Long-running work (reading bills, generating outputs) runs in an app-wide scope so it
 * survives screen rotation and navigation; screens observe progress by key.
 */
class Jobs(private val importer: FileImporter, private val exporter: ExportManager, private val afterImport: (Long) -> Unit = {}) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _states = MutableStateFlow<Map<String, JobState>>(emptyMap())
    val states: StateFlow<Map<String, JobState>> = _states

    fun importKey(tripId: Long) = "import-$tripId"
    fun generateKey(tripId: Long) = "generate-$tripId"

    private fun set(key: String, s: JobState?) = _states.update { if (s == null) it - key else it + (key to s) }
    fun clear(key: String) = set(key, null)
    fun isRunning(key: String) = _states.value[key] is JobState.Running

    fun startImport(tripId: Long, uris: List<Uri>, attachTo: Long? = null) {
        val key = importKey(tripId)
        if (isRunning(key) || uris.isEmpty()) return
        set(key, JobState.Running("Preparing ${uris.size} file(s)…", 0, uris.size))
        scope.launch {
            try {
                val r = importer.importUris(tripId, uris, attachTo) { p -> set(key, JobState.Running(p.current, p.done, p.total)) }
                set(key, JobState.ImportDone(r))
                afterImport(tripId)
            } catch (e: Throwable) {
                set(key, JobState.Failed(e.message ?: "Import failed"))
            }
        }
    }

    fun startGenerate(tripId: Long, employee: EmployeeInfo) {
        val key = generateKey(tripId)
        if (isRunning(key)) return
        set(key, JobState.Running("Starting…"))
        scope.launch {
            try {
                val out = exporter.generate(tripId, employee) { step -> set(key, JobState.Running(step)) }
                set(key, JobState.GenerateDone(out))
            } catch (e: OutOfMemoryError) {
                set(key, JobState.Failed("Not enough memory to render all documents. Try removing very large files."))
            } catch (e: Throwable) {
                set(key, JobState.Failed(e.message ?: "Could not generate the statement"))
            }
        }
    }
}
