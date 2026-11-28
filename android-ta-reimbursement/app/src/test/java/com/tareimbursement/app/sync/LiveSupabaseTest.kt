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
import com.tareimbursement.app.service.sync.CursorStore
import com.tareimbursement.app.service.sync.ServerConfig
import com.tareimbursement.app.service.sync.Session
import com.tareimbursement.app.service.sync.SessionStore
import com.tareimbursement.app.service.sync.SupabaseClient
import com.tareimbursement.app.service.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Optional live check against a real Supabase project. Runs only when TA_LIVE_DIR points to a
 * folder with files "url", "anon" and "pw" (password of two pre-created test accounts);
 * otherwise it is skipped. Prints the project id it created so it can be cleaned up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TaApp::class)
class LiveSupabaseTest {
    private val dir = System.getenv("TA_LIVE_DIR")?.let(::File)

    private fun passwordSession(url: String, anon: String, email: String, pw: String): Session {
        val c = URL("$url/auth/v1/token?grant_type=password").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("apikey", anon); c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("email", email).put("password", pw).toString().toByteArray()) }
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        val u = j.getJSONObject("user")
        return Session(j.getString("access_token"), j.getString("refresh_token"), System.currentTimeMillis() + 3_000_000, u.getString("id"), u.getString("email"), "")
    }

    @Test fun ownerAndMemberAgainstRealServer() = runBlocking {
        assumeTrue("live test not configured", dir != null && File(dir, "anon").exists())
        val url = File(dir, "url").readText().trim(); val anon = File(dir, "anon").readText().trim(); val pw = File(dir, "pw").readText().trim()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        class Phone(name: String, email: String, person: String) {
            val dirP = File(ctx.filesDir, "live-$name").apply { deleteRecursively(); mkdirs() }
            val repo = Repository(AppDatabase.inMemory(ctx), dirP)
            var s: Session? = passwordSession(url, anon, email, pw)
            val client = SupabaseClient({ ServerConfig(url, anon) }, object : SessionStore { override fun load() = s; override fun save(x: Session?) { s = x } })
            val sync = SyncEngine(repo, client, object : CursorStore {
                val m = mutableMapOf<String, String>()
                override fun get(key: String) = m[key].orEmpty(); override fun put(key: String, value: String) { m[key] = value }
                override fun clear(prefix: String) { m.keys.removeAll { it.startsWith(prefix) } }
            }) { person }
        }
        val a = Phone("a", "ta.owner.livetest@example.com", "Owner Test")
        val b = Phone("b", "ta.member.livetest@example.com", "Member Test")

        val tripId = a.repo.createTrip(TripEntity(title = "LIVE TEST - delete me", clientName = "Test", projectCode = "LT", location = "Hosur", purpose = "Test",
            startEpochDay = LocalDate.of(2026, 11, 20).toEpochDay(), endEpochDay = LocalDate.of(2026, 11, 22).toEpochDay()), "Owner Test", "T1")
        a.repo.saveTravellers(tripId, "Owner Test", "T1", listOf(TravellerEntity(tripId = tripId, name = "Member Test", employeeCode = "T2")))
        val e1 = a.repo.addManualExpense(tripId, LocalDate.of(2026, 11, 21))
        a.repo.saveExpense(a.repo.expenses.get(e1)!!.copy(amount = 1200.0, description = "Hotel test", category = ExpenseCategory.BOARDING.name, confirmed = true, modeOfTravel = "NA"))
        val photo = File(a.repo.attachmentDir(tripId), "bill.jpg").apply { writeBytes(ByteArray(5000) { (it * 7 % 251).toByte() }) }
        a.repo.attachments.insert(AttachmentEntity(expenseId = e1, tripId = tripId, storedPath = photo.relativeTo(a.dirP).path, originalName = "hotel.jpg", mimeType = "image/jpeg", kind = "IMAGE", isPrimary = true, sizeBytes = photo.length()))

        a.sync.shareTrip(tripId)
        val uid = a.repo.trips.get(tripId)!!.uid
        println("LIVE-PROJECT $uid")
        val mira = a.repo.travellers.forTrip(tripId).first { it.name == "Member Test" }
        a.sync.invite(tripId, mira.id, "ta.member.livetest@example.com")

        b.sync.syncAll()
        val bTrip = b.repo.trips.byUid(uid)!!
        assertEquals("MEMBER", bTrip.myRole)
        val bBill = b.repo.expenses.forTrip(bTrip.id).single()
        assertEquals(1200.0, bBill.amount!!, 0.0)
        assertArrayEquals(photo.readBytes(), b.repo.fileOf(b.repo.attachments.forExpense(bBill.id).single()).readBytes())

        val m1 = b.repo.addManualExpense(bTrip.id, LocalDate.of(2026, 11, 22))
        b.repo.saveExpense(b.repo.expenses.get(m1)!!.copy(amount = 450.0, description = "Auto test", category = ExpenseCategory.LOCAL_CONVEYANCE.name, confirmed = true, modeOfTravel = "Auto", addedBy = "Member Test"))
        b.sync.syncTrip(bTrip.id)
        b.repo.saveExpense(bBill.copy(amount = 9999.0))          // not allowed: owner's bill
        b.sync.syncTrip(bTrip.id); b.sync.syncTrip(bTrip.id)
        assertEquals(1200.0, b.repo.expenses.byUid(bBill.uid)!!.amount!!, 0.0)
        b.sync.sendMessage(bTrip.id, "Live test message", bBill.uid)

        val inc = a.sync.syncAll()
        assertEquals(1, inc.bills); assertEquals(1, inc.messages.size)
        assertEquals(setOf(1200.0, 450.0), a.repo.expenses.forTrip(tripId).map { it.amount }.toSet())
        assertEquals("JOINED", a.repo.travellers.forTrip(tripId).first { it.name == "Member Test" }.inviteStatus)
        assertTrue(a.repo.messages.forTrip(tripId).single().body == "Live test message")
        println("LIVE-OK")
    }
}
