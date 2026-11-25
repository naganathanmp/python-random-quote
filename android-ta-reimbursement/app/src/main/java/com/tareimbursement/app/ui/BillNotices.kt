package com.tareimbursement.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tareimbursement.app.core.trust.TrustLevel
import com.tareimbursement.app.data.ExpenseEntity

/** Everything the app noticed about a bill: AI reading, metadata date, trust check, duplicates. */
@Composable
fun BillNotices(
    e: ExpenseEntity, readOnly: Boolean, reason: String, onReason: (String) -> Unit,
    onAck: () -> Unit, onDelete: () -> Unit, onDiscuss: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (e.aiState) {
            "QUEUED" -> Notice(Icons.Outlined.AutoAwesome, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                "On-device AI is reading this bill… You can edit now; the AI never overwrites what you type.") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            "DONE", "FAILED" -> if (e.aiNote.isNotBlank()) Text("AI: ${e.aiNote}", style = MaterialTheme.typography.bodySmall,
                color = if (e.aiNote.contains("verify")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (e.dateFromMetadata && !e.confirmed) Text("No date is printed on this bill - the date was taken from when the photo/PDF was made. Please check it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)

        val report = e.trust
        val serious = report.findings.filter { it.level >= TrustLevel.WARN }
        if (e.trustBlocked) {
            Notice(Icons.Outlined.Block, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                "This file looks AI-generated, so it can't be claimed:\n" + serious.filter { it.level == TrustLevel.BLOCK }.joinToString("\n") { "• ${it.message}" } +
                    "\nDelete it and upload the original bill.") {
                if (!readOnly) TextButton(onClick = onDelete) { Text("Delete this bill") }
            }
        } else if (serious.isNotEmpty() || (e.dupStrong && e.dupNote.isNotBlank())) {
            val lines = serious.map { it.message } + listOfNotNull(e.dupNote.takeIf { e.dupStrong && it.isNotBlank() })
            val acked = e.trustAck.isNotBlank()
            Notice(Icons.Outlined.Warning, if (acked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
                if (acked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
                (if (acked) "Checked - kept because: ${e.trustAck}\n" else "Please check this bill:\n") + lines.joinToString("\n") { "• $it" }) {
                if (!acked && !readOnly) {
                    OutlinedTextField(reason, onReason, label = { Text("Why is this bill genuine? *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row {
                        onDiscuss?.let { TextButton(onClick = it) { Text("Discuss with team") } }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) { Text("Delete") }
                        TextButton(enabled = reason.trim().length >= 5, onClick = onAck) { Text("Keep bill") }
                    }
                }
            }
        } else if (e.dupNote.isNotBlank()) {
            Text("Note: ${e.dupNote}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        report.findings.filter { it.level == TrustLevel.INFO }.takeIf { it.isNotEmpty() && !e.trustBlocked }?.let { info ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("File check: " + info.joinToString(" ") { it.message }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onDiscuss != null && serious.isEmpty() && !e.dupStrong) TextButton(onClick = onDiscuss) { Text("Discuss this bill with the team") }
    }
}

@Composable
private fun Notice(icon: ImageVector, bg: Color, fg: Color, text: String, extra: @Composable () -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, null, tint = fg); Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = fg)
            }
            extra()
        }
    }
}
