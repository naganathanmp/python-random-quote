package com.tareimbursement.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tareimbursement.app.ui.AppNav
import com.tareimbursement.app.ui.TaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) { handleShare(intent); handleSignInLink(intent) }
        setContent { TaTheme { AppNav() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        handleSignInLink(intent)
    }

    /** tareimburse://auth#access_token=… from the sign-in e-mail: finish signing in to Team sharing. */
    internal fun handleSignInLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || data.scheme != "tareimburse" || data.host != "auth") return
        val c = container
        c.appScope.launch {
            val msg = try {
                c.supabase.signInWithLink(data.toString())
                com.tareimbursement.app.service.sync.SyncWorker.schedule(this@MainActivity)
                try { c.sync.syncAll() } catch (_: Exception) { }
                "Signed in to Team sharing as ${c.supabase.session?.contact}"
            } catch (e: Exception) { e.message ?: "Sign-in failed" }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    /**
     * Files shared into the app from other apps. Only content:// URIs from OTHER apps are
     * accepted (never file:// or this app's own FileProvider — see UriPolicy), at most 200,
     * and malformed intents from misbehaving apps are ignored instead of crashing.
     */
    @Suppress("DEPRECATION")
    internal fun handleShare(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = try {
            when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(
                    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                )
                Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
                else -> emptyList()
            }
        } catch (e: RuntimeException) { emptyList() }
        val allowed = uris.filter { com.tareimbursement.app.service.UriPolicy.isAllowedFromShare(this, it) }.take(200)
        if (allowed.isNotEmpty()) container.pendingShared.value = allowed
        else if (uris.isNotEmpty()) android.widget.Toast.makeText(this, "These files can't be imported. Share them from your gallery or files app.", android.widget.Toast.LENGTH_LONG).show()
    }
}
