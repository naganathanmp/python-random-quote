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
        rule.onNodeWithText("Create claim").performScrollTo().performClick()
        rule.onNodeWithText("Please fill the required fields (*).").assertExists() // client location is mandatory
        input("Client location visited *", "Hosur")
        input("Advance received (if any)", "2500")
        input("Approved budget (optional)", "50000")
        input("Daily spend limit (optional)", "3000")
        rule.onNodeWithText("Add co-traveller", substring = true).performScrollTo().performClick()
        input("Co-traveller name", "Anita Rao")
        rule.onNodeWithText("Create claim").performScrollTo().performClick()
        waitText("0% used")                       // budget card on the claim screen
        waitText("No day is over the limit.")
        rule.onNodeWithText("Scan bill").assertExists()
        rule.onNodeWithText("Upload files").assertExists()
        val trips = runBlocking { app.container.repo.trips.all() }
        assertEquals(1, trips.size)
        assertEquals(2500.0, trips[0].advance, 0.0)
        assertEquals("ACM01", trips[0].projectCode)
        assertEquals("Hosur", trips[0].location)
        assertEquals(50000.0, trips[0].budget!!, 0.0)
        assertEquals(3000.0, trips[0].dailyLimit!!, 0.0)
        assertEquals("Internal Audit", trips[0].purpose)
        assertEquals("Self Booking", trips[0].onwardBookedBy)
        val people = runBlocking { app.container.repo.travellers.forTrip(trips[0].id) }
        assertEquals(listOf("Ravi Kumar", "Anita Rao"), people.map { it.name })
        assertTrue(people.first().isSelf)
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
        rule.onNodeWithText("Uber").assertExists() // mandatory remarks pre-filled (no ride details in this bill's text)
        rule.onNodeWithText("Confirm").performClick()
        rule.onNodeWithText("Choose how this expense should be classified.").assertExists()
        rule.onNodeWithText("Local Conveyance  (suggested)").performScrollTo().performClick()
        rule.onNodeWithText("Confirm").performClick()
        waitText("All reviewed")
        val e = runBlocking { c.repo.expenses.get(expenseId)!! }
        assertTrue(e.confirmed)
        assertEquals(ExpenseCategory.LOCAL_CONVEYANCE, e.categoryEnum)
        assertEquals("Cab", e.modeOfTravel)
        assertEquals("Uber", e.description)

        // Generate outputs and archive.
        rule.onNodeWithText("Generate TA statement").performClick()
        waitText("Generate Excel, PDF & receipts")
        // The button is enabled once the pre-generation checks have loaded.
        rule.waitUntil(15_000) { rule.onAllNodes(hasText("Generate Excel, PDF & receipts") and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Generate Excel, PDF & receipts").performScrollTo().performClick()
        rule.waitUntil(60_000) {
            rule.onAllNodesWithText("Mark completed & archive", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Could not generate", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val job = c.jobs.states.value[c.jobs.generateKey(tripId)]
        assertTrue("generation failed: $job", job !is com.tareimbursement.app.service.JobState.Failed)
        rule.onNodeWithText("TA statement (Excel, PKF format)").assertExists()
        rule.onNodeWithText("Download").assertExists()
        // No mail app on this test phone: E-mail is hidden, Share and Download remain.
        rule.onNodeWithText("E-mail").assertDoesNotExist()
        rule.onNodeWithText("Share").assertExists()
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
        waitText("On-device AI")
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToNode(hasText("Per-bill limits & own-vehicle rate"))
        rule.onNodeWithText("Per-bill limits & own-vehicle rate").performClick()
        waitText("Per-bill limits — leave blank for no limit.")
        rule.onNodeWithText("Cancel").performClick()
    }

    /** Deleting a bill returns to the claim straight away (v2.0.3 feedback: the screen froze). */
    @Test fun deletingAnExpenseGoesBack() {
        val c = app.container
        c.prefs.saveProfile(Profile(name = "Ravi Kumar", employeeCode = "E1023"))
        val tripId = runBlocking {
            val t = c.repo.createTrip(TripEntity(title = "Delete Audit", clientName = "Acme", projectCode = "D1", location = "Pune", purpose = "Audit",
                startEpochDay = LocalDate.of(2026, 3, 24).toEpochDay(), endEpochDay = LocalDate.of(2026, 3, 28).toEpochDay()))
            val e = c.repo.expenses.insert(ExpenseEntity(tripId = t, dateEpochDay = LocalDate.of(2026, 3, 25).toEpochDay(), amount = 300.0, vendor = "Zzz Cafe"))
            val f = File(c.repo.attachmentDir(t), "z.jpg").apply { writeBytes(jpeg) }
            c.repo.attachments.insert(AttachmentEntity(expenseId = e, tripId = t, storedPath = f.relativeTo(app.filesDir).path, originalName = "z.jpg", mimeType = "image/jpeg", kind = "IMAGE", isPrimary = true, sizeBytes = f.length()))
            c.repo.expenses.insert(ExpenseEntity(tripId = t, dateEpochDay = LocalDate.of(2026, 3, 26).toEpochDay(), amount = 120.0, vendor = "Keep Me"))
            t
        }
        start()
        waitText("Delete Audit")
        rule.onNodeWithText("Delete Audit").performClick()
        waitText("Zzz Cafe")
        rule.onNodeWithText("Zzz Cafe").performClick()
        waitText("Bill & supporting documents")
        rule.onNodeWithContentDescription("Delete expense").performClick()
        rule.onNodeWithText("Delete").performClick()
        // Back on the claim, the bill is gone and the other one is still there.
        waitText("Expenses (1)", 5_000)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Zzz Cafe").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Scan bill").assertExists()
        assertEquals(1, runBlocking { c.repo.expenses.forTrip(tripId).size })
    }
    /** Same on a team (shared) claim, where the deleted bill is kept as a hidden tombstone. */
    @Test fun deletingAnExpenseGoesBackOnSharedClaim() {
        val c = app.container
        c.prefs.saveProfile(Profile(name = "Ravi Kumar", employeeCode = "E1023"))
        val tripId = runBlocking {
            val t = c.repo.createTrip(TripEntity(title = "Delete Audit", clientName = "Acme", projectCode = "D1", location = "Pune", purpose = "Audit",
                startEpochDay = LocalDate.of(2026, 3, 24).toEpochDay(), endEpochDay = LocalDate.of(2026, 3, 28).toEpochDay()))
            val e = c.repo.expenses.insert(ExpenseEntity(tripId = t, dateEpochDay = LocalDate.of(2026, 3, 25).toEpochDay(), amount = 300.0, vendor = "Zzz Cafe"))
            val f = File(c.repo.attachmentDir(t), "z.jpg").apply { writeBytes(jpeg) }
            c.repo.attachments.insert(AttachmentEntity(expenseId = e, tripId = t, storedPath = f.relativeTo(app.filesDir).path, originalName = "z.jpg", mimeType = "image/jpeg", kind = "IMAGE", isPrimary = true, sizeBytes = f.length()))
            c.repo.expenses.insert(ExpenseEntity(tripId = t, dateEpochDay = LocalDate.of(2026, 3, 26).toEpochDay(), amount = 120.0, vendor = "Keep Me"))
            c.repo.trips.update(c.repo.trips.get(t)!!.copy(shared = true, myRole = "OWNER"))
            t
        }
        start()
        waitText("Delete Audit")
        rule.onNodeWithText("Delete Audit").performClick()
        waitText("Expenses (2)")
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToNode(hasText("Zzz Cafe"))
        rule.onNodeWithText("Zzz Cafe").performClick()
        waitText("Bill & supporting documents")
        rule.onNodeWithContentDescription("Delete expense").performClick()
        rule.onNodeWithText("Delete").performClick()
        // Back on the claim, the bill is gone and the other one is still there.
        waitText("Expenses (1)", 5_000)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Zzz Cafe").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Scan bill").assertExists()
        assertEquals(1, runBlocking { c.repo.expenses.forTrip(tripId).size })
    }
}
