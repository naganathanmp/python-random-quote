package com.tareimbursement.app.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tareimbursement.app.container
import com.tareimbursement.app.data.AttachmentEntity
import com.tareimbursement.app.data.AttachmentKind
import com.tareimbursement.app.service.Media
import com.tareimbursement.app.service.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun Thumbnail(file: File, kind: AttachmentKind, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            when (kind) {
                AttachmentKind.IMAGE -> Media.decodeSampled(file, 360)
                AttachmentKind.PDF -> Media.renderPdfPage(file, 0, 360)
                AttachmentKind.VIDEO -> Media.videoFrame(file, 360)
                else -> null
            }
        }
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(
            when (kind) { AttachmentKind.PDF -> Icons.Outlined.PictureAsPdf; AttachmentKind.VIDEO -> Icons.Outlined.Videocam; else -> Icons.Outlined.Description },
            null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(attachmentId: Long, nav: NavHostController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val att by produceState<AttachmentEntity?>(null, attachmentId) { value = c.repo.attachments.get(attachmentId) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(att?.originalName ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    att?.let { a ->
                        IconButton(onClick = {
                            val f = c.repo.fileOf(a)
                            if (!Sharing.open(ctx, f, a.mimeType)) Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_LONG).show()
                        }) { Icon(Icons.Outlined.OpenInNew, "Open in another app") }
                    }
                },
            )
        },
    ) { pad ->
        val a = att ?: return@Scaffold
        val file = c.repo.fileOf(a)
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (a.kindEnum) {
                AttachmentKind.IMAGE, AttachmentKind.VIDEO -> {
                    val bmp by produceState<Bitmap?>(null) { value = withContext(Dispatchers.IO) { if (a.kindEnum == AttachmentKind.IMAGE) Media.decodeSampled(file, 2400) else Media.videoFrame(file, 2000) } }
                    bmp?.let { ZoomableImage(it) } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                AttachmentKind.PDF -> {
                    val count by produceState(-2) { value = withContext(Dispatchers.IO) { Media.pdfPageCount(file) } }
                    when {
                        count == -2 -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        count <= 0 -> Text("This PDF could not be displayed (it may be password-protected). Use the open button to view it in another app.", Modifier.padding(24.dp))
                        else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(count) { i ->
                                val page by produceState<Bitmap?>(null, i) { value = withContext(Dispatchers.IO) { Media.renderPdfPage(file, i, 1400) } }
                                page?.let { Image(it.asImageBitmap(), "Page ${i + 1}", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                                    ?: Box(Modifier.fillMaxWidth().size(400.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            }
                        }
                    }
                }
                else -> {
                    val text by produceState("") {
                        value = withContext(Dispatchers.IO) {
                            val expense = c.repo.expenses.get(a.expenseId)
                            expense?.ocrText?.takeIf { it.isNotBlank() && a.isPrimary } ?: runCatching {
                                if (a.kindEnum == AttachmentKind.TEXT) file.readText().take(50_000) else com.tareimbursement.app.core.OfficeText.extract(file)
                            }.getOrDefault("")
                        }
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(text.ifBlank { "No preview available for this file type. Use the open button to view it in another app." }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun ZoomableImage(bmp: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(
        bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}
