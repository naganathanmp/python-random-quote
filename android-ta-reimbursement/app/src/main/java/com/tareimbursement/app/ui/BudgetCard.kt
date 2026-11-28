package com.tareimbursement.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.StrokeCap
import com.tareimbursement.app.core.BudgetTracker

@Composable
fun budgetColor(level: BudgetTracker.Level): Color = when (level) {
    BudgetTracker.Level.OVER -> MaterialTheme.colorScheme.error
    BudgetTracker.Level.WARNING -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

/** Budget vs actual (with forecast) and the days that crossed the daily limit. */
@Composable
fun BudgetCard(status: BudgetTracker.Status, travellerNames: Map<Long, String>) {
    var showDays by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        status.budget?.let { budget ->
            val color = budgetColor(status.level)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountBalanceWallet, null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Budget", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${Fmt.inr(status.spent)} of ${Fmt.inr(budget)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (status.usedFraction ?: 0.0).toFloat().coerceIn(0f, 1f) }, color = color, strokeCap = StrokeCap.Round,
                trackColor = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Spacer(Modifier.height(6.dp))
            val pct = ((status.usedFraction ?: 0.0) * 100).toInt()
            val remaining = status.remaining ?: 0.0
            Text(
                when (status.level) {
                    BudgetTracker.Level.OVER -> "$pct% used · over budget by ${Fmt.inr(-remaining)}"
                    BudgetTracker.Level.WARNING -> "$pct% used · ${Fmt.inr(remaining)} left — nearing the budget"
                    else -> "$pct% used · ${Fmt.inr(remaining)} left"
                },
                style = MaterialTheme.typography.bodySmall, color = color,
            )
            status.forecast?.let { f ->
                if (status.level != BudgetTracker.Level.OVER && f > status.spent + 1) Text(
                    "At the current pace the trip will cost about ${Fmt.inr(f)}" + if (status.forecastOver) " — above budget." else ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.forecastOver) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        status.dailyLimit?.let { limit ->
            if (status.budget != null) Spacer(Modifier.height(12.dp))
            val n = status.daysOver.size
            val color = if (n > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
            Row(Modifier.fillMaxWidth().clickable(enabled = n > 0) { showDays = !showDays }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Today, null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Daily limit ${Fmt.inr(limit)}${if (status.perPerson) " per traveller" else ""}", style = MaterialTheme.typography.titleSmall)
                    Text(if (n == 0) "No day is over the limit." else "$n ${if (status.perPerson) "traveller-day" else "day"}${if (n == 1) "" else "s"} over the limit",
                        style = MaterialTheme.typography.bodySmall, color = color)
                }
                if (n > 0) Icon(if (showDays) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (showDays) status.daysOver.forEach { d ->
                val who = d.person?.let { travellerNames[it] }?.let { "$it · " }.orEmpty()
                Text("$who${Fmt.date(d.date)}: ${Fmt.inr(d.spent)} (+${Fmt.inr(d.excess)})",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 28.dp, top = 4.dp))
            }
        }
    }
}
