package com.tareimbursement.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tareimbursement.app.container
import com.tareimbursement.app.core.ClaimStatus
import com.tareimbursement.app.data.TripWithStats
import com.tareimbursement.app.service.JobState

@Composable
fun ClaimsScreen(nav: NavHostController, modifier: Modifier) {
    val c = LocalContext.current.container
    val all by remember(Unit) { c.repo.trips.observeWithStats() }.collectAsState(initial = null)
    val jobs by c.jobs.states.collectAsState()
    val profile by c.prefs.profile.collectAsState()
    val drafts = all?.filter { it.claimStatus == ClaimStatus.DRAFT }.orEmpty()
    val awaiting = all?.filter { it.claimStatus == ClaimStatus.SUBMITTED || it.claimStatus == ClaimStatus.APPROVED }.orEmpty()

    Box(modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column {
                    Text("Hi ${profile.name.substringBefore(' ').ifBlank { "there" }}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Your open TA claims", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (all != null && drafts.isEmpty()) {
                item { HowItWorks() }
            }
            items(drafts, key = { it.id }) { t ->
                TripCard(t, jobs[c.jobs.importKey(t.id)] is JobState.Running) { nav.navigate(Routes.trip(t.id)) }
            }
            if (awaiting.isNotEmpty()) {
                item { Text("Awaiting reimbursement", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                items(awaiting, key = { "a${it.id}" }) { t -> TripCard(t, false) { nav.navigate(Routes.claim(t.id)) } }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { nav.navigate(Routes.NEW_TRIP) },
            icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New TA claim") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun HowItWorks() {
    SectionCard(title = "How it works") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Step(Icons.Outlined.WorkOutline, "1. Create a claim", "One TA statement per project assignment / trip.")
            Step(Icons.Outlined.CameraAlt, "2. Add bills", "Snap invoices or upload photos, PDFs, screenshots, documents or a ZIP — many at once.")
            Step(Icons.Outlined.TaskAlt, "3. Review", "Check the date, amount and suggested accounting head for each bill.")
            Step(Icons.Outlined.Description, "4. Generate & submit", "Get the PKF-format Excel statement, one combined PDF and renamed receipts. Download or e-mail; the claim is archived.")
        }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TripCard(t: TripWithStats, processing: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.5.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${t.clientName} · ${t.projectCode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusChip(t.claimStatus)
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.range(t.startDate, t.endDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(Fmt.inr(t.total), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${t.expenseCount} bill${if (t.expenseCount == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (t.pendingCount > 0 && t.claimStatus == ClaimStatus.DRAFT)
                    Text("· ${t.pendingCount} to review", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                if (t.shared) Text("· Team", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                if (t.unread > 0) Text("· ${t.unread} new message${if (t.unread == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                t.submittedAt?.let { if (t.claimStatus != ClaimStatus.DRAFT) Text("· submitted ${Fmt.date(com.tareimbursement.app.data.Repository.toDate(it))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            t.budget?.takeIf { it > 0 }?.let { b ->
                val level = com.tareimbursement.app.core.BudgetTracker.level(t.total, b)
                val color = budgetColor(level)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { (t.total / b).toFloat().coerceIn(0f, 1f) }, color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth())
                Text("Budget ${Fmt.inr(b)} · ${(t.total / b * 100).toInt()}% used" + if (level == com.tareimbursement.app.core.BudgetTracker.Level.OVER) " · over budget" else "",
                    style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(top = 2.dp))
            }
            if (processing) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Reading bills…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable(onClick = onClick))
            }
        }
    }
}
