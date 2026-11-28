package com.tareimbursement.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tareimbursement.app.TaApp
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.data.AppDatabase
import com.tareimbursement.app.data.AttachmentEntity
import com.tareimbursement.app.data.Repository
import com.tareimbursement.app.data.TravellerEntity
import com.tareimbursement.app.data.TripEntity
import com.tareimbursement.app.service.sync.ApiException
import com.tareimbursement.app.service.sync.CursorStore
import com.tareimbursement.app.service.sync.ServerConfig
import com.tareimbursement.app.service.sync.Session
import com.tareimbursement.app.service.sync.SessionStore
import com.tareimbursement.app.service.sync.SupabaseClient
import com.tareimbursement.app.service.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Two phones (owner and invited member) working on one project through the sync server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TaApp::class)
class TeamSyncTest {
    private lateinit var server: MockWebServer
    private val fake = FakeSupabase()
    private lateinit var ctx: Context

    inner class Phone(name: String, val person: String) {
        val db = AppDatabase.inMemory(ctx)
        val dir = File(ctx.filesDir, "phone-$name").apply { deleteRecursively(); mkdirs() }
        val repo = Repository(db, dir)
        var stored: Session? = null
        val client = SupabaseClient({ ServerConfig(server.url("/").toString().trimEnd('/'), "anon-key-for-tests-0123456789") }, object : SessionStore {
            override fun load() = stored
            override fun save(s: Session?) { stored = s }
        })
        val cursors = object : CursorStore {
            val m = mutableMapOf<String, String>()
            override fun get(key: String) = m[key].orEmpty()
            override fun put(key: String, value: String) { m[key] = value }
            override fun clear(prefix: String) { m.keys.removeAll { it.startsWith(prefix) } }
        }
        val sync = SyncEngine(repo, client, cursors) { person }
        fun signIn(contact: String) { client.sendCode(contact); client.verifyCode(contact, "123456") }
    }

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { dispatcher = fake; start() }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun ownerAndMemberShareBillsFilesAndChat() = runBlocking {
        val a = Phone("a", "Asha"); val b = Phone("b", "Mira")
        // --- Owner creates a claim with a co-traveller and one bill with a photo.
        a.signIn("owner@x.com")
        try { a.client.verifyCode("owner@x.com", "000000"); fail("wrong code accepted") } catch (e: ApiException) { assertTrue(e.message!!.contains("wrong") || e.message!!.contains("expired")) }
        val tripId = a.repo.createTrip(TripEntity(title = "Hosur audit", clientName = "Titan", projectCode = "P-77", location = "Hosur", purpose = "Audit",
            startEpochDay = LocalDate.of(2026, 7, 20).toEpochDay(), endEpochDay = LocalDate.of(2026, 7, 22).toEpochDay()), "Asha", "E1")
        a.repo.saveTravellers(tripId, "Asha", "E1", listOf(TravellerEntity(tripId = tripId, name = "Mira", employeeCode = "E2")))
        val e1 = a.repo.addManualExpense(tripId, LocalDate.of(2026, 7, 21))
        a.repo.saveExpense(a.repo.expenses.get(e1)!!.copy(amount = 1200.0, description = "Hotel Sunrise, Hosur - 1 night", category = ExpenseCategory.BOARDING.name, confirmed = true, modeOfTravel = "NA"))
        val photo = File(a.repo.attachmentDir(tripId), "bill.jpg").apply { writeBytes(ByteArray(3000) { (it % 251).toByte() }) }
        a.repo.attachments.insert(AttachmentEntity(expenseId = e1, tripId = tripId, storedPath = photo.relativeTo(a.dir).path, originalName = "hotel.jpg", mimeType = "image/jpeg", kind = "IMAGE", isPrimary = true, sizeBytes = photo.length()))

        a.sync.shareTrip(tripId)
        val trip = a.repo.trips.get(tripId)!!
        assertTrue(trip.shared); assertEquals("OWNER", trip.myRole)
        assertEquals(1, fake.tables.getValue("expenses").size)
        assertEquals(1, fake.files.size)
        assertTrue(a.repo.expenses.forTrip(tripId).none { it.dirty })

        // --- Invite Mira by e-mail; she signs in on her phone and the project appears.
        val mira = a.repo.travellers.forTrip(tripId).first { it.name == "Mira" }
        val text = a.sync.invite(tripId, mira.id, " Mira@X.com ")
        assertTrue(text.contains("mira@x.com"))
        b.signIn("mira@x.com")
        b.sync.syncAll()
        val bTrip = b.repo.trips.byUid(trip.uid)
        assertNotNull(bTrip); bTrip!!
        assertEquals("MEMBER", bTrip.myRole); assertEquals("Hosur audit", bTrip.title); assertEquals(trip.claimNumber, bTrip.claimNumber)
        val bPeople = b.repo.travellers.forTrip(bTrip.id)
        assertEquals(setOf("Asha", "Mira"), bPeople.map { it.name }.toSet())
        assertEquals("Mira", bPeople.first { it.isSelf }.name)
        val bBills = b.repo.expenses.forTrip(bTrip.id)
        assertEquals(1, bBills.size); assertEquals(1200.0, bBills[0].amount!!, 0.0)
        // Shares were split between Asha & Mira on A; on B they map to B's local traveller ids.
        assertEquals(2, com.tareimbursement.app.core.Shares.parse(bBills[0].shares).size)
        val bAtt = b.repo.attachments.forExpense(bBills[0].id).single()
        assertArrayEquals(photo.readBytes(), b.repo.fileOf(bAtt).readBytes())

        // --- Owner learns Mira joined.
        a.sync.syncAll()
        assertEquals("JOINED", a.repo.travellers.forTrip(tripId).first { it.name == "Mira" }.inviteStatus)

        // --- Mira adds her own bill; Asha receives it.
        val m1 = b.repo.addManualExpense(bTrip.id, LocalDate.of(2026, 7, 22))
        b.repo.saveExpense(b.repo.expenses.get(m1)!!.copy(amount = 450.0, description = "Auto to client office", category = ExpenseCategory.LOCAL_CONVEYANCE.name, addedBy = "Mira", confirmed = true, modeOfTravel = "Auto"))
        b.sync.syncTrip(bTrip.id)
        val incomingA = a.sync.syncAll()
        assertEquals(1, incomingA.bills)
        val aBills = a.repo.expenses.forTrip(tripId)
        assertEquals(2, aBills.size)
        assertEquals("Mira", aBills.first { it.amount == 450.0 }.addedBy)

        // --- Mira can't change Asha's bill: the server refuses and her phone takes the server copy back.
        val asha = b.repo.expenses.forTrip(bTrip.id).first { it.amount == 1200.0 }
        b.repo.saveExpense(asha.copy(amount = 9999.0))
        b.sync.syncTrip(bTrip.id)
        b.sync.syncTrip(bTrip.id)
        assertEquals(1200.0, b.repo.expenses.byUid(asha.uid)!!.amount!!, 0.0)
        assertEquals(1200.0, a.repo.expenses.byUid(asha.uid)!!.amount!!, 0.0)

        // --- Chat about a bill.
        b.sync.sendMessage(bTrip.id, "Is the hotel bill with GST?", asha.uid)
        val inc = a.sync.syncAll()
        assertEquals(1, inc.messages.size)
        assertEquals("Mira", inc.messages[0].authorName)
        assertEquals(asha.uid, a.repo.messages.forTrip(tripId).single().expenseUid)
        a.sync.sendMessage(tripId, "Yes, GST invoice attached")
        b.sync.syncAll()
        assertEquals(2, b.repo.messages.forTrip(bTrip.id).size)
        assertTrue(b.repo.messages.forTrip(bTrip.id).all { it.sent })

        // --- Owner deletes Mira's bill; it disappears from Mira's phone too.
        a.repo.deleteExpense(aBills.first { it.amount == 450.0 }.id)
        a.sync.syncTrip(tripId)
        b.sync.syncTrip(bTrip.id)
        assertEquals(listOf(1200.0), b.repo.expenses.forTrip(bTrip.id).map { it.amount })

        // --- Owner renames the claim; member sees it. Member's own claim edits never upload.
        a.repo.updateTrip(a.repo.trips.get(tripId)!!.copy(title = "Hosur audit Q2"))
        a.sync.syncTrip(tripId)
        b.sync.syncTrip(bTrip.id)
        assertEquals("Hosur audit Q2", b.repo.trips.get(bTrip.id)!!.title)

        // --- A stranger sees nothing.
        val c = Phone("c", "Sam"); c.signIn("sam@x.com"); c.sync.syncAll()
        assertTrue(c.repo.trips.all().isEmpty())

        // --- Sign out clears the session.
        b.client.signOut()
        assertFalse(b.client.signedIn)
    }

    @Test fun signInFromTheEmailLink() {
        val a = Phone("l", "Asha")
        a.client.sendCode("asha@x.com")
        assertTrue(fake.lastOtpPath.contains("redirect_to=tareimburse%3A%2F%2Fauth"))
        // 1. Pasted verify link from the default Supabase e-mail.
        fake.links["pkce_abc123"] = "asha@x.com"
        val s1 = a.client.signInWithLink("https://proj.supabase.co/auth/v1/verify?token=pkce_abc123&type=magiclink&redirect_to=tareimburse://auth")
        assertEquals("asha@x.com", s1.email)
        try { a.client.signInWithLink("https://proj.supabase.co/auth/v1/verify?token=pkce_abc123&type=magiclink"); fail("reused link accepted") } catch (e: ApiException) { }
        // 2. The app link the server redirects to after the link is tapped.
        val uid = fake.users.getValue("asha@x.com").id
        val s2 = a.client.signInWithLink("tareimburse://auth#access_token=tok-$uid&expires_in=3600&refresh_token=ref-$uid&token_type=bearer&type=magiclink")
        assertEquals(uid, s2.userId); assertTrue(a.client.signedIn)
        // 3. A forged token is refused by the server.
        try { a.client.signInWithLink("tareimburse://auth#access_token=forged&refresh_token=x"); fail("forged token accepted") } catch (e: ApiException) { }
        // 4. Expired link message.
        try { a.client.signInWithLink("tareimburse://auth#error=access_denied&error_code=otp_expired&error_description=Email+link+is+invalid+or+has+expired"); fail() } catch (e: ApiException) { assertTrue(e.message!!.contains("expired")) }
    }

    @Test fun phoneNumbersAreNormalised() {
        assertEquals("+919812345678", SupabaseClient.normalizePhone("98123 45678"))
        assertEquals("+919812345678", SupabaseClient.normalizePhone("098123-45678"))
        assertEquals("+919812345678", SupabaseClient.normalizePhone("+91 98123 45678"))
        assertEquals("+971501234567", SupabaseClient.normalizePhone("+971 50 123 4567"))
        assertTrue(SupabaseClient.isValidContact("a.b@firm.co.in"))
        assertFalse(SupabaseClient.isValidContact("a.b@firm"))
        assertFalse(ServerConfig("http://example.com", "anon-key-for-tests-0123456789").valid)
        assertTrue(ServerConfig("https://abc.supabase.co", "anon-key-for-tests-0123456789").valid)
    }
}
