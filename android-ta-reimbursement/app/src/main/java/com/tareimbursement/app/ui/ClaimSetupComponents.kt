package com.tareimbursement.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tareimbursement.app.core.Currencies

/** Editable row used for co-travellers (a = name, b = employee code) and client entities (a = name, b = code, c = share %). */
data class EditRow(val id: Long = 0, val a: String = "", val b: String = "", val c: String = "")

/** Survives rotation / process death. */
val EditRowsSaver: Saver<List<EditRow>, ArrayList<String>> = Saver(
    save = { rows -> ArrayList(rows.map { listOf(it.id.toString(), it.a, it.b, it.c).joinToString("\u0001") }) },
    restore = { list -> list.map { s -> s.split("\u0001").let { p -> EditRow(p[0].toLong(), p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "" }) } } },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyDropdown(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String = "Currency", enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { if (enabled) open = it }, modifier) {
        OutlinedTextField(
            value = Currencies.of(value).let { "${it.code} – ${it.name}" }, onValueChange = {}, readOnly = true, enabled = enabled, singleLine = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(open, { open = false }) {
            Currencies.all.forEach { c -> DropdownMenuItem(text = { Text("${c.code} – ${c.name}") }, onClick = { onChange(c.code); open = false }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookedByField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField(value, {}, readOnly = true, singleLine = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(open, { open = false }) {
            listOf("Self Booking", "Office", "Client", "Not applicable").forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onChange(o); open = false }) }
        }
    }
}

@Composable
fun TravellerRows(rows: List<EditRow>, onChange: (List<EditRow>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(r.a, { v -> onChange(rows.toMutableList().also { it[i] = r.copy(a = v) }) }, label = { Text("Co-traveller name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.weight(1.4f))
                OutlinedTextField(r.b, { v -> onChange(rows.toMutableList().also { it[i] = r.copy(b = v) }) }, label = { Text("Emp. code") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { onChange(rows.toMutableList().also { it.removeAt(i) }) }) { Icon(Icons.Outlined.Close, "Remove traveller") }
            }
        }
        TextButton(onClick = { onChange(rows + EditRow()) }) { Icon(Icons.Outlined.Add, null); Text(" Add co-traveller") }
    }
}

@Composable
fun EntityRows(rows: List<EditRow>, showShare: Boolean, onChange: (List<EditRow>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(r.a, { v -> onChange(rows.toMutableList().also { it[i] = r.copy(a = v) }) }, label = { Text("Client entity") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.weight(1.5f))
                OutlinedTextField(r.b, { v -> onChange(rows.toMutableList().also { it[i] = r.copy(b = v) }) }, label = { Text("Code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.weight(1f))
                if (showShare) OutlinedTextField(r.c, { v -> if (v.length <= 5 && v.all { ch -> ch.isDigit() || ch == '.' }) onChange(rows.toMutableList().also { it[i] = r.copy(c = v) }) },
                    label = { Text("%") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(0.7f))
                IconButton(onClick = { onChange(rows.toMutableList().also { it.removeAt(i) }) }) { Icon(Icons.Outlined.Close, "Remove entity") }
            }
        }
        TextButton(onClick = { onChange(rows + EditRow()) }) { Icon(Icons.Outlined.Add, null); Text(" Add client entity") }
        if (showShare) Text("Leave % blank to split as evenly as possible. If you enter percentages, they must add up to 100.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}
