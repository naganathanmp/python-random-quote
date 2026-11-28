package com.tareimbursement.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tareimbursement.app.container
import com.tareimbursement.app.core.AmountWords
import com.tareimbursement.app.core.ClaimStatus
import com.tareimbursement.app.core.InsightPeriod
import com.tareimbursement.app.core.Insights
import com.tareimbursement.app.core.InsightsEngine
import kotlinx.coroutines.flow.combine
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun InsightsScreen(nav: NavHostController, modifier: Modifier) {
    val c = LocalContext.current.container
    var period by rememberSaveable { mutableStateOf(InsightPeriod.ALL) }
    val trigger by remember { combine(c.repo.trips.observeAll(), c.repo.expenses.observeAll()) { a, b -> a.hashCode() to b.hashCode() } }.collectAsState(initial = 0 to 0)
    val insights by produceState<Insights?>(null, trigger, period) {
        val (t, e) = c.repo.insightData()
        value = InsightsEngine.compute(t, e, period)
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Insights", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightPeriod.entries.forEach { p -> FilterChip(period == p, { period = p }, label = { Text(p.label) }) }
            }
        }
        val i = insights ?: return@LazyColumn
        if (i.expenseCount == 0) {
            item { EmptyState(Icons.Outlined.Insights, "No expenses yet", "Insights appear once you add bills to your TA claims.") }
            return@LazyColumn
        }
        item {
            SectionCard {
                Row {
                    Stat("Submitted claims", Fmt.inr(i.totalClaimed), Modifier.weight(1f))
                    Stat("Reimbursed", Fmt.inr(i.totalReimbursed), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Stat("Pending", Fmt.inr(i.pendingAmount), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                    Stat("Claims / drafts", "${i.claimCount} / ${i.draftCount}", Modifier.weight(1f), MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Stat("Avg per claim", Fmt.inr(i.averagePerClaim), Modifier.weight(1f), MaterialTheme.colorScheme.onSurface)
                    Stat("Avg per travel day", Fmt.inr(i.averagePerDay), Modifier.weight(1f), MaterialTheme.colorScheme.onSurface)
                }
                i.averageSettlementDays?.let { Text("Average time to reimbursement: ${"%.0f".format(it)} days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
        item {
            SectionCard(title = "Key insights") {
                i.highlights.forEach { h ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Icon(Icons.Outlined.Lightbulb, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(h, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (i.budgets.isNotEmpty()) item {
            SectionCard(title = "Budget vs actual") {
                i.budgets.take(10).forEach { b ->
                    val level = com.tareimbursement.app.core.BudgetTracker.level(b.spent, b.budget)
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row {
                            Text(b.trip.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Fmt.inr(b.spent)} / ${Fmt.inr(b.budget)}", style = MaterialTheme.typography.labelMedium, color = budgetColor(level))
                        }
                        Bar(b.used.toFloat(), budgetColor(level))
                    }
                }
            }
        }
        item { SectionCard(title = "Monthly trend") { MonthlyBars(i) } }
        item {
            SectionCard(title = "Spend by accounting head") {
                val max = i.categories.maxOfOrNull { it.total } ?: 1.0
                i.categories.forEach { cs ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(categoryIcon(cs.category), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(cs.category.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Fmt.inr(cs.total)} · ${(cs.share * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                        Bar((cs.total / max).toFloat(), MaterialTheme.colorScheme.primary)
                        Text("${cs.count} bills · avg ${Fmt.inr(cs.average)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (i.high.isNotEmpty()) item {
            SectionCard(title = "High expenses") {
                i.high.forEach { h ->
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${h.expense.vendor.ifBlank { h.expense.category.label }} · ${Fmt.date(h.expense.date)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${h.tripTitle} — ${h.reason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Text(Fmt.inr(h.expense.amount), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (i.repeated.isNotEmpty() || i.topVendors.isNotEmpty()) item {
            SectionCard(title = "Repeated & top vendors") {
                (i.repeated + i.topVendors).distinctBy { it.vendor.lowercase() }.take(10).forEach { v ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(v.vendor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${v.count}×", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                        Text(Fmt.inr(v.total), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (i.projects.size > 1) item {
            SectionCard(title = "By project / client") {
                val max = i.projects.maxOf { it.total }.coerceAtLeast(1.0)
                i.projects.take(8).forEach { p ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row {
                            Text("${p.key} · ${p.client}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Fmt.inr(p.total), style = MaterialTheme.typography.labelMedium)
                        }
                        Bar((p.total / max).toFloat(), MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
        item {
            SectionCard(title = "Claim timeline") {
                i.timeline.forEach { te ->
                    Row(Modifier.fillMaxWidth().clickable {
                        nav.navigate(if (te.trip.status == ClaimStatus.DRAFT) Routes.trip(te.trip.id) else Routes.claim(te.trip.id))
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(10.dp).background(statusColor(te.trip.status), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(te.trip.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val parts = buildList {
                                add(Fmt.range(te.trip.start, te.trip.end))
                                te.trip.submittedOn?.let { add("submitted ${Fmt.shortDate(it)}") }
                                te.trip.paidOn?.let { add("paid ${Fmt.shortDate(it)}") }
                                te.settlementDays?.let { add("$it days to settle") }
                            }
                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Fmt.inr(te.total), style = MaterialTheme.typography.labelLarge)
                            Text(te.trip.status.label, style = MaterialTheme.typography.labelSmall, color = statusColor(te.trip.status))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(s: ClaimStatus): Color = when (s) {
    ClaimStatus.DRAFT -> MaterialTheme.colorScheme.outline
    ClaimStatus.SUBMITTED -> MaterialTheme.colorScheme.primary
    ClaimStatus.APPROVED -> MaterialTheme.colorScheme.tertiary
    ClaimStatus.PAID -> MaterialTheme.colorScheme.secondary
    ClaimStatus.REJECTED -> MaterialTheme.colorScheme.error
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).fillMaxHeight().background(color, RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun MonthlyBars(i: Insights) {
    val months = i.months.takeLast(12)
    val max = months.maxOfOrNull { it.total }?.takeIf { it > 0 } ?: 1.0
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fmt = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
    Text("Peak ${AmountWords.compact(max)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(top = 6.dp)) {
        val n = months.size.coerceAtLeast(1)
        val slot = size.width / n
        val w = slot * 0.6f
        months.forEachIndexed { idx, m ->
            val x = idx * slot + (slot - w) / 2
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(6f, 6f))
            val h = (m.total / max).toFloat() * size.height
            if (h > 0) drawRoundRect(bar, Offset(x, size.height - h), Size(w, h), CornerRadius(6f, 6f))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        months.forEach { m -> Text(m.month.format(fmt).take(3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1) }
    }
}
