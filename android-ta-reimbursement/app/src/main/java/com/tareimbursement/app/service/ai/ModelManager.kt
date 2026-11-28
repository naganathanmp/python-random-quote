package com.tareimbursement.app.service.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.tareimbursement.app.core.ai.AiModel
import com.tareimbursement.app.core.ai.ModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed class ModelState {
    data object None : ModelState()
    data class Downloading(val model: AiModel, val done: Long, val total: Long) : ModelState() {
        val percent: Int get() = if (total > 0) (done * 100 / total).toInt() else 0
    }
    data class Verifying(val model: AiModel) : ModelState()
    data class Ready(val model: AiModel) : ModelState()
    data class Failed(val model: AiModel?, val message: String) : ModelState()
}

/**
 * Keeps the on-device model: picks one for the phone (RAM), downloads it with resume support,
 * verifies its SHA-256, or imports a model file the person already has. Models live in
 * filesDir/models and never leave the phone.
 */
class ModelManager(private val context: Context, private val hfToken: () -> String? = { null }) {
    val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    private val _state = MutableStateFlow<ModelState>(ModelState.None)
    val state: StateFlow<ModelState> = _state

    init { refresh() }

    fun fileFor(m: AiModel) = File(dir, m.fileName)
    private fun partFor(m: AiModel) = File(dir, m.fileName + ".part")
    private fun okMarker(m: AiModel) = File(dir, m.fileName + ".ok")

    fun installed(): AiModel? = ModelCatalog.all.firstOrNull { m ->
        okMarker(m).exists() && fileFor(m).length() > 0 && (m.sha256 == null || fileFor(m).length() == m.sizeBytes)
    }

    fun refresh() {
        val cur = _state.value
        if (cur is ModelState.Downloading || cur is ModelState.Verifying) return
        _state.value = installed()?.let { ModelState.Ready(it) } ?: if (cur is ModelState.Failed) cur else ModelState.None
    }

    fun totalRamBytes(): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    } catch (e: Exception) { 0L }

    fun recommended(): AiModel? = ModelCatalog.recommend(totalRamBytes(), !hfToken().isNullOrBlank())

    fun freeBytes(): Long = try { StatFs(dir.path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }

    /** Space check: the model plus 300 MB headroom for the app's own files. */
    fun hasSpaceFor(m: AiModel): Boolean = freeBytes() + partFor(m).length() > m.sizeBytes + 300L * 1024 * 1024

    /** Downloads [m] (resuming a partial download). Blocking; call from a worker. */
    suspend fun download(m: AiModel, isStopped: () -> Boolean = { false }): Boolean = withContext(Dispatchers.IO) {
        if (!hasSpaceFor(m)) { _state.value = ModelState.Failed(m, "Not enough free space: ${m.sizeLabel} needed."); return@withContext false }
        val part = partFor(m)
        try {
            var url = URL(m.url)
            var redirects = 0
            while (true) {
                val have = part.length()
                if (have >= m.sizeBytes && m.sizeBytes > 0) break
                val c = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000; readTimeout = 60_000
                    setRequestProperty("User-Agent", "TA-Reimburse-Android")
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                    // The token is only sent to Hugging Face itself, never to the CDN it redirects to.
                    if (m.gated && url.host == "huggingface.co") hfToken()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                    c.disconnect()
                    url = URL(url, loc)
                    if (url.protocol != "https") throw IOException("Insecure redirect refused")
                    if (++redirects > 8) throw IOException("Too many redirects")
                    continue
                }
                if (code == 401 || code == 403) throw IOException(if (m.gated) "Access denied: accept the model licence on huggingface.co and enter your access token in Settings." else "Access denied ($code)")
                if (code == 416) { c.disconnect(); break }
                if (code != 200 && code != 206) throw IOException("Server returned $code")
                val append = code == 206
                if (!append && have > 0) part.delete()
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(if (append) have else 0)
                    c.inputStream.use { ins ->
                        val buf = ByteArray(256 * 1024)
                        var done = if (append) have else 0L
                        var lastUi = 0L
                        while (true) {
                            if (isStopped()) throw InterruptedException("stopped")
                            val r = ins.read(buf); if (r < 0) break
                            raf.write(buf, 0, r); done += r
                            if (done - lastUi > 4 * 1024 * 1024) { lastUi = done; _state.value = ModelState.Downloading(m, done, m.sizeBytes) }
                        }
                    }
                }
                c.disconnect()
                break
            }
            _state.value = ModelState.Verifying(m)
            if (!verify(part, m)) { part.delete(); throw IOException("Downloaded file is damaged (checksum mismatch). Please try again.") }
            install(part, m)
            true
        } catch (e: InterruptedException) {
            _state.value = ModelState.Failed(m, "Download paused - it will resume where it stopped.")
            false
        } catch (e: Exception) {
            _state.value = ModelState.Failed(m, e.message ?: "Download failed")
            false
        }
    }

    private fun verify(f: File, m: AiModel): Boolean {
        if (m.sizeBytes > 0 && m.sha256 != null && f.length() != m.sizeBytes) return false
        val expected = m.sha256 ?: return f.length() > 1_000_000
        return sha256(f) == expected
    }

    private fun install(f: File, m: AiModel) {
        ModelCatalog.all.filter { it != m }.forEach { delete(it, refresh = false) }
        val target = fileFor(m)
        if (target.exists()) target.delete()
        if (!f.renameTo(target)) { f.copyTo(target, overwrite = true); f.delete() }
        okMarker(m).writeText(m.sha256 ?: "imported")
        _state.value = ModelState.Ready(m)
    }

    /** Imports a model file the person downloaded on a computer (e.g. the gated Gemma file). */
    suspend fun importFile(uri: Uri): Result<AiModel> = withContext(Dispatchers.IO) {
        val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val tmp = File(dir, "import.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it, 256 * 1024) } } ?: throw IOException("Cannot open the file")
            val size = tmp.length()
            val m = ModelCatalog.all.firstOrNull { it.sizeBytes == size } ?: ModelCatalog.all.firstOrNull { name.contains(it.fileName.substringBefore('.'), true) }
                ?: throw IOException("Not a supported model file. Supported: ${ModelCatalog.all.joinToString { it.fileName }}")
            _state.value = ModelState.Verifying(m)
            if (m.sha256 != null && sha256(tmp) != m.sha256) throw IOException("This file does not match ${m.displayName} (checksum differs).")
            if (!isTaskBundle(tmp)) throw IOException("This is not a MediaPipe .task model file.")
            install(tmp, m)
            Result.success(m)
        } catch (e: Exception) {
            tmp.delete()
            _state.value = ModelState.Failed(null, e.message ?: "Import failed")
            Result.failure(e)
        }
    }

    /** .task files are ZIP bundles. */
    private fun isTaskBundle(f: File) = try { f.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() } } catch (e: Exception) { false }

    fun delete(m: AiModel, refresh: Boolean = true) {
        fileFor(m).delete(); partFor(m).delete(); okMarker(m).delete()
        if (refresh) { _state.value = ModelState.None; refresh() }
    }

    fun partialBytes(m: AiModel) = partFor(m).length()

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins -> val buf = ByteArray(1 shl 20); while (true) { val r = ins.read(buf); if (r < 0) break; md.update(buf, 0, r) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
