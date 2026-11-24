package com.tareimbursement.app.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Which incoming URIs the app may read when importing bills.
 *
 * Protects against the "confused deputy" attack: another app shares a link that points at
 * THIS app's private files (database, other claims' bills) so that they get imported and
 * later shared out. Rules:
 *  - content:// URIs are allowed, except ones served by this app's own FileProvider;
 *  - file:// URIs are allowed only for the app's own camera captures in cache/camera/;
 *  - everything else (http, android.resource, …) is rejected.
 */
object UriPolicy {
    fun isAllowedForImport(context: Context, uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        ContentResolver.SCHEME_CONTENT -> uri.authority != context.packageName + ".fileprovider"
        ContentResolver.SCHEME_FILE -> {
            val path = uri.path ?: ""
            val camera = File(context.cacheDir, "camera").canonicalPath + File.separator
            try { File(path).canonicalPath.startsWith(camera) } catch (e: Exception) { false }
        }
        else -> false
    }

    /** Only content:// URIs from other apps may come in through Share / Send. */
    fun isAllowedFromShare(context: Context, uri: Uri): Boolean =
        uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) && isAllowedForImport(context, uri)

    /** File extensions taken from untrusted names: short and alphanumeric only. */
    fun safeExtension(name: String): String? =
        name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } }
}
