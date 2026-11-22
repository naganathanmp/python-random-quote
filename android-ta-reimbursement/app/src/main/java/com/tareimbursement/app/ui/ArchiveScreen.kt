package com.tareimbursement.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tareimbursement.app.container
import com.tareimbursement.app.core.ClaimStatus

@Composable
fun ArchiveScreen(nav: NavHostController, modifier: Modifier) {
    val c = LocalContext.current.container
    val all by remember(Unit) { c.repo.trips.observeWithStats() }.collectAsState(initial = null)
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val archived = all?.filter { it.claimStatus.archived }.orEmpty()
    val shown = archived.filter { t ->
        (filter == null || t.status == filter) &&
            (query.isBlank() || listOf(t.title, t.clientName, t.projectCode, t.claimNumber, t.location).any { it.contains(query.trim(), ignoreCase = true) })
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                Text("Archive", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Completed TA statements with their outputs, approval and payment history.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (archived.isNotEmpty()) {
            item {
                SectionCard {
                    Row {
                        Stat("Claims", archived.size.toString(), Modifier.weight(0.7f))
                        Stat("Total claimed", Fmt.inr(archived.sumOf { it.total }), Modifier.weight(1f))
                        Stat("Received", Fmt.inr(archived.sumOf { it.paidAmount ?: 0.0 }), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            item {
                OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search project, client, code, claim no.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == null, { filter = null }, label = { Text("All") })
                    listOf(ClaimStatus.SUBMITTED, ClaimStatus.APPROVED, ClaimStatus.PAID, ClaimStatus.REJECTED).forEach { s ->
                        FilterChip(filter == s.name, { filter = if (filter == s.name) null else s.name }, label = { Text("${s.label} (${archived.count { it.status == s.name }})") })
                    }
                }
            }
        }
        if (all != null && archived.isEmpty()) item {
            EmptyState(Icons.Outlined.Inventory2, "Nothing archived yet", "When you generate a TA statement and mark it completed, it is archived here with the Excel, PDF and receipts for future use.")
        } else if (archived.isNotEmpty() && shown.isEmpty()) item {
            Text("No claims match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(shown, key = { it.id }) { t -> TripCard(t, false) { nav.navigate(Routes.claim(t.id)) } }
    }
}
