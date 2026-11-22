package com.tareimbursement.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.tareimbursement.app.data.AppDatabase
import com.tareimbursement.app.data.ExpenseEntity
import com.tareimbursement.app.data.Profile
import com.tareimbursement.app.data.TripEntity
import com.tareimbursement.app.ui.AppNav
import com.tareimbursement.app.ui.TaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TaApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClaimStatusDialogsTest {
    @get:Rule val rule = createComposeRule()
    @Test fun approveAndPaidDialogsOpenOnArchivedClaim() {
        val app = ApplicationProvider.getApplicationContext<TaApp>()
        app.container = AppContainer(app, AppDatabase.inMemory(app), FakeExtractor())
        val c = app.container
        c.prefs.saveProfile(Profile(name = "Ravi", employeeCode = "E1"))
        runBlocking {
            val t = c.repo.createTrip(TripEntity(title = "Probe", clientName = "C", projectCode = "P", location = "", purpose = "", startEpochDay = 20000, endEpochDay = 20001))
            c.repo.expenses.insert(ExpenseEntity(tripId = t, amount = 100.0, dateEpochDay = 20000, vendor = "V", category = "OTHER", confirmed = true))
            c.exporter.generate(t, c.prefs.profile.value.toEmployee())
            c.repo.submit(t)
        }
        rule.setContent { TaTheme { AppNav(rememberNavController()) } }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Archive").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Archive").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("Probe").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Probe").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("Mark reimbursed").fetchSemanticsNodes().isNotEmpty() }
        println("PROBE: on claim screen")
        rule.waitForIdle()
        println("PROBE: claim idle ok")
        rule.onNodeWithText("Mark approved").performClick()
        println("PROBE: clicked approve")
        rule.waitForIdle()
        println("PROBE: approve dialog idle ok")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Mark reimbursed").performClick()
        println("PROBE: clicked paid")
        rule.waitForIdle()
        println("PROBE: paid dialog idle ok")
    }
}
