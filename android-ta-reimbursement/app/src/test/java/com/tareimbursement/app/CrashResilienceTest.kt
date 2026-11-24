package com.tareimbursement.app

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.tareimbursement.app.service.CrashLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Walks the real app (real Activity, real on-disk database) through its screens and recreates
 * the Activity on each one — as happens on rotation, dark-mode switch or when Android reclaims
 * memory — checking nothing crashes and typed input survives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TaApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrashResilienceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitText(t: String, timeout: Long = 15_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun input(label: String, value: String) = rule.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextInput(value)

    private fun recreate() { rule.activityRule.scenario.recreate(); rule.waitForIdle() }

    @Test fun everyScreenSurvivesRecreation() {
        waitText("Get started")
        input("Full name *", "Ravi Kumar")
        recreate()                                   // onboarding: typed name survives
        waitText("Ravi Kumar")
        input("Employee code *", "E1")
        rule.onNodeWithText("Get started").performScrollTo().performClick()
        waitText("Hi Ravi")
        recreate()                                   // claims list

        rule.onNodeWithText("New TA claim", useUnmergedTree = true).performClick()
        waitText("Create claim")
        input("Project / assignment name *", "Rotation Audit")
        input("Approved budget (optional)", "1000")
        recreate()                                   // claim form keeps typed values
        waitText("Rotation Audit")
        input("Client name *", "Acme")
        input("Project / engagement code *", "R1")
        input("Client location visited *", "Pune")
        rule.onNodeWithText("Add co-traveller", substring = true).performScrollTo().performClick()
        input("Co-traveller name", "Anita")
        recreate()                                   // co-traveller rows (custom saver) survive
        waitText("Anita")
        rule.onNodeWithText("Create claim").performScrollTo().performClick()
        waitText("Scan bill")
        val tripNow = runBlocking { rule.activity.container.repo.trips.all() }.single()
        val people = runBlocking { rule.activity.container.repo.travellers.forTrip(tripNow.id) }
        assertEquals(listOf("Ravi Kumar", "Anita"), people.map { it.name })
        recreate()                                   // claim screen with budget card
        waitText("0% used")

        // Own-vehicle expense → expense screen with mileage inputs, shares, currency.
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Own vehicle trip", substring = true).performClick()
        waitText("Own vehicle (no bill)")
        input("Total km *", "134")
        recreate()                                   // expense editor keeps km
        waitText("134")
        waitText("Travellers' shares")             // co-traveller → shares section
        rule.onNodeWithText("Travellers' shares").performScrollTo()
        rule.onNodeWithContentDescription("Back").performClick()
        waitText("Generate TA statement")
        rule.onNodeWithText("Generate TA statement").performClick()
        waitText("Fix before generating")
        recreate()                                   // generate screen
        waitText("Fix before generating")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        for (tab in listOf("Insights", "Archive", "Settings")) {
            waitText(tab)
            rule.onAllNodesWithText(tab)[0].performClick()
            rule.waitForIdle()
            recreate()
        }
        waitText("Back up all data")
        val trips = runBlocking { rule.activity.container.repo.trips.all() }
        assertEquals(1, trips.size)
        assertEquals(1000.0, trips[0].budget!!, 0.0)
        assertEquals(134.0, runBlocking { rule.activity.container.repo.expenses.forTrip(trips[0].id) }.single().mileageKm!!, 0.0)
    }

    @Test fun crashLogRecordsUnexpectedErrors() {
        val ctx = rule.activity
        CrashLog.record(ctx, "test", "main", IllegalStateException("boom"))
        assertTrue(CrashLog.exists(ctx))
        val text = CrashLog.file(ctx).readText()
        assertTrue(text.contains("IllegalStateException: boom") && text.contains("TA Reimburse test"))
        CrashLog.clear(ctx)
        assertTrue(!CrashLog.exists(ctx))
    }
}
