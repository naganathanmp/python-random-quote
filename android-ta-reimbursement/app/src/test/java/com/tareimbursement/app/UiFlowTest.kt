package com.tareimbursement.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.tareimbursement.app.core.ClaimStatus
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.data.AppDatabase
import com.tareimbursement.app.data.AttachmentEntity
import com.tareimbursement.app.data.ExpenseEntity
import com.tareimbursement.app.data.Profile
import com.tareimbursement.app.data.TripEntity
import com.tareimbursement.app.ui.AppNav
import com.tareimbursement.app.ui.Routes
import com.tareimbursement.app.ui.TaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TaApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiFlowTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var app: TaApp
    private lateinit var nav: NavHostController
    private val jpeg get() = javaClass.classLoader!!.getResourceAsStream("receipt_portrait.jpg")!!.readBytes()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("ta_prefs", 0).edit().clear().commit()
        app.container = AppContainer(app, AppDatabase.inMemory(app), FakeExtractor(), FakeRenderer(jpeg))
    }

    private fun start() {
        rule.setContent { TaTheme { nav = rememberNavController(); AppNav(nav) } }
        rule.waitForIdle()
    }

    private fun waitText(text: String, timeout: Long = 15_000) {
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun input(label: String, value: String) {
        rule.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextInput(value)
    }

    @Test fun onboardingThenCreateClaim() {
        start()
        waitText("Get started")
        rule.onNodeWithText("Get started").performScrollTo().performClick()
        rule.onNodeWithText("Name and employee code are required.").assertExists()
        input("Full name *", "Ravi Kumar")
        input("Employee code *", "E1023")
        input("Send claims to (approver / accounts e-mail)", "accounts@example.com")
        rule.onNodeWithText("Get started").performScrollTo().performClick()
        waitText("Hi Ravi")
        waitText("How it works")
        assertEquals("accounts@example.com", app.container.prefs.profile.value.approverEmail)

        rule.onNodeWithText("New TA claim", useUnmergedTree = true).performClick()
        waitText("Create claim")
        rule.onNodeWithText("Create claim").performScrollTo().performClick()
        rule.onNodeWithText("Please fill the required fields (*).").assertExists()
        input("Project / assignment name *", "Stat Audit FY26")
        input("Client name *", "Acme Ltd")
        input("Project / engagement code *", "ACM01")
        input("Advance received (if any)", "2500")
        rule.onNodeWithText("Create claim").performScrollTo().performClick()
        waitText("No bills yet")
        rule.onNodeWithText("Scan bill").assertExists()
        rule.onNodeWithText("Upload files").assertExists()
        val trips = runBlocking { app.container.repo.trips.all() }
        assertEquals(1, trips.size)
        assertEquals(2500.0, trips[0].advance, 0.0)
        assertEquals("ACM01", trips[0].projectCode)
    }

    @Test fun reviewGenerateArchiveAndTrack() {
        val c = app.container
        c.prefs.saveProfile(Profile(name = "Ravi Kumar", employeeCode = "E1023", approverEmail = "a@b.com", organisation = "PKF Test"))
        val (tripId, expenseId) = runBlocking {
            val t = c.repo.createTrip(TripEntity(title = "Stat Audit", clientName = "Acme", projectCode = "ACM01", location = "Delhi", purpose = "Audit",
                startEpochDay = LocalDate.of(2026, 3, 24).toEpochDay(), endEpochDay = LocalDate.of(2026, 3, 28).toEpochDay()))
            val e = c.repo.expenses.insert(ExpenseEntity(tripId = t, dateEpochDay = LocalDate.of(2026, 3, 25).toEpochDay(), amount = 820.16, vendor = "Uber",
                suggestedCategory = ExpenseCategory.LOCAL_CONVEYANCE.name, suggestionReason = "Cab / auto / metro ride (found: uber, ride).", nature = "Cab / auto / metro ride",
                suggestionAlternatives = ExpenseCategory.PICKUP_DROP.name))
            val f = File(c.repo.attachmentDir(t), "u.jpg").apply { writeBytes(jpeg) }
            c.repo.attachments.insert(AttachmentEntity(expenseId = e, tripId = t, storedPath = f.relativeTo(app.filesDir).path, originalName = "uber.jpg", mimeType = "image/jpeg", kind = "IMAGE", isPrimary = true, sizeBytes = f.length()))
            t to e
        }
        start()
        waitText("Hi Ravi")
        waitText("Stat Audit")
        rule.onNodeWithText("Stat Audit").performClick()
        waitText("1 to review")
        rule.onNodeWithText("Uber").assertExists()
        rule.onNodeWithText("Generate TA statement").performClick()
        waitText("accounting head not chosen")
        rule.onNodeWithContentDescription("Back").performClick()
        waitText("Scan bill")

        // Review the bill: user must pick the accounting head.
        rule.onNodeWithText("Uber").performClick()
        waitText("Accounting head *")
        rule.onNodeWithText("Nature of expense: Cab / auto / metro ride").assertExists()
        rule.onNodeWithText("Confirm").performClick()
        rule.onNodeWithText("Choose how this expense should be classified.").assertExists()
        rule.onNodeWithText("Local Conveyance  (suggested)").performScrollTo().performClick()
        rule.onNodeWithText("Confirm").performClick()
        waitText("All reviewed")
        val e = runBlocking { c.repo.expenses.get(expenseId)!! }
        assertTrue(e.confirmed)
        assertEquals(ExpenseCategory.LOCAL_CONVEYANCE, e.categoryEnum)

        // Generate outputs and archive.
        rule.onNodeWithText("Generate TA statement").performClick()
        waitText("Generate Excel, PDF & receipts")
        rule.onNodeWithText("Generate Excel, PDF & receipts").performScrollTo().performClick()
        waitText("Mark completed & archive", 30_000)
        rule.onNodeWithText("TA statement (Excel, PKF format)").assertExists()
        rule.onNodeWithText("Download").assertExists()
        rule.onNodeWithText("E-mail").assertExists()
        rule.onNodeWithText("Mark completed & archive").performScrollTo().performClick()
        rule.onNodeWithText("Complete & archive").performClick()
        waitText("Mark reimbursed")
        assertEquals(ClaimStatus.SUBMITTED, runBlocking { c.repo.trips.get(tripId)!!.claimStatus })
        rule.onNodeWithText("Mark reimbursed").performClick()
        waitText("Reimbursement received")
        rule.onNodeWithText("Save").performClick()
        waitText("Reimbursed")
        assertEquals(ClaimStatus.PAID, runBlocking { c.repo.trips.get(tripId)!!.claimStatus })
        assertEquals(820.16, runBlocking { c.repo.trips.get(tripId)!!.paidAmount!! }, 0.001)

        // Dashboard, archive and settings tabs render with data.
        rule.onNodeWithContentDescription("Back").performClick()
        waitText("Insights")
        rule.onAllNodesWithText("Insights")[0].performClick()
        waitText("Key insights")
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToNode(hasText("Spend by accounting head"))
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToNode(hasText("Claim timeline"))
        rule.onNodeWithText("Archive").performClick()
        waitText("Completed TA statements")
        waitText("Stat Audit")
        rule.onNodeWithText("Settings").performClick()
        waitText("Back up all data")
        rule.onNodeWithText("Per-bill limits by accounting head").performClick()
        waitText("Leave blank for no limit.")
        rule.onNodeWithText("Cancel").performClick()
    }
}
