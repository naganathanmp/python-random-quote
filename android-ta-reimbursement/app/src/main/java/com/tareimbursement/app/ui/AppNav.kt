package com.tareimbursement.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tareimbursement.app.container
import com.tareimbursement.app.core.ClaimStatus

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val NEW_TRIP = "trip/new"
    fun trip(id: Long) = "trip/$id"
    fun editTrip(id: Long) = "trip/$id/edit"
    fun expense(id: Long) = "expense/$id"
    fun generate(id: Long) = "generate/$id"
    fun claim(id: Long) = "claim/$id"
    fun viewer(id: Long) = "viewer/$id"
}

@Composable
fun AppNav(nav: NavHostController = rememberNavController()) {
    val c = LocalContext.current.container
    val profile by c.prefs.profile.collectAsState()
    val start = if (profile.isComplete) Routes.HOME else Routes.ONBOARDING
    val idArg = listOf(navArgument("id") { type = NavType.LongType })
    NavHost(nav, start) {
        composable(Routes.ONBOARDING) {
            ProfileScreen(onboarding = true, onBack = {}, onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
        }
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.NEW_TRIP) { TripFormScreen(null, nav) }
        composable("trip/{id}/edit", idArg) { TripFormScreen(it.arguments!!.getLong("id"), nav) }
        composable("trip/{id}", idArg) { TripDetailScreen(it.arguments!!.getLong("id"), nav) }
        composable("expense/{id}", idArg) { ExpenseScreen(it.arguments!!.getLong("id"), nav) }
        composable("generate/{id}", idArg) { GenerateScreen(it.arguments!!.getLong("id"), nav) }
        composable("claim/{id}", idArg) { ClaimScreen(it.arguments!!.getLong("id"), nav) }
        composable("viewer/{id}", idArg) { ViewerScreen(it.arguments!!.getLong("id"), nav) }
        composable("profile") { ProfileScreen(onboarding = false, onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() }) }
    }
    if (profile.isComplete) SharedFilesDialog(nav)
}

@Composable
fun HomeScreen(nav: NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("Claims", Icons.Outlined.ReceiptLong, 0), Triple("Insights", Icons.Outlined.Analytics, 1),
                    Triple("Archive", Icons.Outlined.Inventory2, 2), Triple("Settings", Icons.Outlined.Settings, 3),
                ).forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, label) }, label = { Text(label) })
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            0 -> ClaimsScreen(nav, m)
            1 -> InsightsScreen(nav, m)
            2 -> ArchiveScreen(nav, m)
            else -> SettingsScreen(nav, m)
        }
    }
}

/** When files are shared into the app from Gallery / Files / Gmail, ask which claim they belong to. */
@Composable
fun SharedFilesDialog(nav: NavHostController) {
    val c = LocalContext.current.container
    val pending by c.pendingShared.collectAsState()
    val entry by nav.currentBackStackEntryAsState()
    if (pending.isEmpty() || entry?.destination?.route == Routes.NEW_TRIP) return
    val trips by remember(Unit) { c.repo.trips.observeWithStats() }.collectAsState(initial = emptyList())
    val drafts = trips.filter { it.claimStatus == ClaimStatus.DRAFT }
    AlertDialog(
        onDismissRequest = { c.pendingShared.value = emptyList() },
        title = { Text("Add ${pending.size} shared file(s)") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("Choose the TA claim these bills belong to:", style = MaterialTheme.typography.bodyMedium)
                drafts.forEach { t ->
                    Text(
                        "${t.title} · ${t.clientName}", style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable {
                            c.jobs.startImport(t.id, pending); c.pendingShared.value = emptyList(); nav.navigate(Routes.trip(t.id))
                        }.padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
                if (drafts.isEmpty()) Text("No open claims yet — create one.", modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { nav.navigate(Routes.NEW_TRIP) }) { Text("New claim") } },
        dismissButton = { TextButton(onClick = { c.pendingShared.value = emptyList() }) { Text("Cancel") } },
    )
}
