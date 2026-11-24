package com.tareimbursement.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.tareimbursement.app.core.Currencies
import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.Shares
import com.tareimbursement.app.data.AppDatabase
import com.tareimbursement.app.data.BillingEntity
import com.tareimbursement.app.data.ExpenseEntity
import com.tareimbursement.app.data.Profile
import com.tareimbursement.app.data.Repository
import com.tareimbursement.app.data.SplitMode
import com.tareimbursement.app.data.TravellerEntity
import com.tareimbursement.app.data.TripEntity
import com.tareimbursement.app.service.ExportManager
import com.tareimbursement.app.service.FileImporter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TaApp::class)
class SplitWorkflowTest {
    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private val jpeg get() = javaClass.classLoader!!.getResourceAsStream("receipt_portrait.jpg")!!.readBytes()
    private val employee = Profile("Ravi Kumar", "E1023", "Manager", "Audit", "Mumbai", "PKF Test", "").toEmployee()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = AppDatabase.inMemory(ctx)
        repo = Repository(db, ctx.filesDir)
    }

    @After fun tearDown() { db.close(); File(ctx.filesDir, "trips").deleteRecursively() }

    private suspend fun trip(currency: String = "INR") = repo.createTrip(
        TripEntity(title = "Group Audit", clientName = "Acme Group", projectCode = "ACM-GRP", location = "Dubai", purpose = "Audit",
            startEpochDay = LocalDate.of(2026, 3, 10).toEpochDay(), endEpochDay = LocalDate.of(2026, 3, 14).toEpochDay(), advance = 10000.0, currency = currency),
        "Ravi Kumar", "E1023",
    )

    private suspend fun bill(tripId: Long, amount: Double?, day: Int, cat: ExpenseCategory, vendor: String, currency: String = "INR", foreign: Double? = null, rate: Double? = null): Long {
        val id = repo.expenses.insert(ExpenseEntity(tripId = tripId, dateEpochDay = LocalDate.of(2026, 3, day).toEpochDay(), amount = amount, vendor = vendor,
            category = cat.name, confirmed = true, currency = currency, foreignAmount = foreign, exchangeRate = rate, description = "$vendor bill"))
        val f = File(repo.attachmentDir(tripId), "$id.jpg").apply { writeBytes(jpeg) }
        repo.attachments.insert(com.tareimbursement.app.data.AttachmentEntity(expenseId = id, tripId = tripId, storedPath = f.relativeTo(ctx.filesDir).path,
            originalName = "$vendor.jpg", mimeType = "image/jpeg", kind = "IMAGE", isPrimary = true, sizeBytes = f.length()))
        repo.saveExpense(repo.expenses.get(id)!!)
        return id
    }

    @Test fun travellersSharesForeignCurrencyAndAutoSplit() = runBlocking {
        val tripId = trip(currency = "AED")
        val self = repo.travellers.forTrip(tripId).single()
        assertTrue(self.isSelf)
        repo.saveTravellers(tripId, "Ravi Kumar", "E1023", listOf(TravellerEntity(tripId = tripId, name = "Anita Rao", employeeCode = "E2001"), TravellerEntity(tripId = tripId, name = "Sam Joseph")))
        val people = repo.travellers.forTrip(tripId)
        assertEquals(3, people.size)

        val air = bill(tripId, 54000.0, 10, ExpenseCategory.AIR_FARE, "Emirates")
        val hotel = bill(tripId, Currencies.toInr(1250.0, 8.32), 12, ExpenseCategory.BOARDING, "Marriott", "AED", 1250.0, 8.32)
        val cab = bill(tripId, Currencies.toInr(85.0, 8.32), 13, ExpenseCategory.LOCAL_CONVEYANCE, "Careem", "AED", 85.0, 8.32)
        val food = bill(tripId, 3000.0, 13, ExpenseCategory.EXPENSES_DURING_TRAVEL, "Cafe")

        // Default: every bill equally shared by all three travellers.
        val airShares = Shares.parse(repo.expenses.get(air)!!.shares)
        assertEquals(3, airShares.size); assertEquals(54000.0, airShares.values.sum(), 0.001)
        // Cab used only by the claimant.
        val c = repo.expenses.get(cab)!!
        repo.saveExpense(c.copy(shares = Shares.format(mapOf(self.id to c.amount!!))))
        assertEquals(1, Shares.parse(repo.expenses.get(cab)!!.shares).size)
        // Amount change on an equally-shared bill keeps it equal.
        repo.saveExpense(repo.expenses.get(food)!!.copy(amount = 3300.0))
        assertEquals(1100.0, Shares.parse(repo.expenses.get(food)!!.shares).values.first(), 0.001)
        // Unbalanced custom shares are blocking.
        repo.expenses.update(repo.expenses.get(food)!!.copy(shares = Shares.format(mapOf(self.id to 100.0, people[1].id to 200.0))))
        assertTrue(repo.validate(tripId, emptyMap()).any { it.blocking && it.message.contains("don't add up") })
        repo.expenses.update(repo.expenses.get(food)!!.copy(shares = Shares.format(Shares.equal(3300.0, people.map { it.id }))))

        // Foreign bill without a rate is blocking; last rate is remembered for the next bill.
        assertEquals(8.32, repo.lastRate(tripId, "AED")!!, 0.0001)
        val noRate = repo.expenses.insert(ExpenseEntity(tripId = tripId, dateEpochDay = LocalDate.of(2026, 3, 13).toEpochDay(), currency = "USD", foreignAmount = 20.0, category = "OTHER", confirmed = true))
        assertTrue(repo.validate(tripId, emptyMap()).any { it.blocking && it.message.contains("exchange rate for USD") })
        repo.deleteExpense(noRate)

        // Split across two client entities, auto-balanced, with the hotel locked to the Gulf entity.
        repo.saveEntities(tripId, SplitMode.AUTO, listOf(BillingEntity(tripId = tripId, name = "Acme India Pvt Ltd", code = "ACM-IN"), BillingEntity(tripId = tripId, name = "Acme Gulf FZE", code = "ACM-AE")))
        val ents = repo.entities.forTrip(tripId)
        repo.expenses.update(repo.expenses.get(hotel)!!.copy(entityId = ents[1].id, entityLocked = true))
        assertTrue(repo.validate(tripId, emptyMap()).joinToString { it.message }, repo.validate(tripId, emptyMap()).none { it.blocking })
        val r = repo.autoAllocate(tripId)!!
        assertEquals(ents[1].id, repo.expenses.get(hotel)!!.entityId)
        assertTrue(repo.expenses.forTrip(tripId).all { it.entityId != null })
        assertEquals(r.totals.sum(), repo.expenses.forTrip(tripId).sumOf { it.amount!! }, 0.01)

        // Generate: consolidated + per-entity outputs, ZIP with one folder per entity.
        val out = ExportManager(repo, FakeRenderer(jpeg)).generate(tripId, employee)
        assertTrue(out.exist())
        assertEquals(out.extras.map { it.name }.toString(), 4, out.extras.size)
        assertTrue(out.extras.any { it.name.contains("ACM-IN") && it.name.endsWith(".xlsx") })
        assertTrue(out.extras.any { it.name.contains("ACM-AE") && it.name.endsWith(".pdf") })
        val names = mutableListOf<String>()
        ZipInputStream(out.zip.inputStream()).use { z -> while (true) names += (z.nextEntry ?: break).name }
        assertTrue(names.toString(), names.any { it.startsWith("Acme Gulf FZE [ACM-AE]/Boarding Expenses (Stay & Food)/12th Mar 2026_10400") })
        assertTrue(names.toString(), names.any { it.startsWith("Acme India Pvt Ltd [ACM-IN]/") && it.endsWith(".xlsx") })
        val keep = File(System.getProperty("java.io.tmpdir"), "ta_test_out/v11app").apply { deleteRecursively(); mkdirs() }
        out.all.forEach { it.copyTo(File(keep, it.name), overwrite = true) }

        // Per-entity statements add up to the claim and pro-rate the advance.
        val exporter = ExportManager(repo, FakeRenderer(jpeg))
        val p = exporter.prepare(tripId, employee)
        val parts = p.data.entities.map { exporter.forEntity(p, it).first }
        assertEquals(p.data.total, parts.sumOf { it.total }, 0.01)
        assertEquals(10000.0, parts.sumOf { it.claim.advance }, 0.02)
        assertTrue(parts.all { it.lines.first().serial == 1 })
        val hotelLine = p.data.lines.first { it.vendor == "Marriott" }
        assertEquals("AED", hotelLine.currency); assertEquals(1250.0, hotelLine.foreignAmount!!, 0.0); assertEquals(10400.0, hotelLine.amount, 0.001)
        assertEquals(3, hotelLine.shares.size)

        // Removing a traveller re-splits their shares among the rest.
        repo.saveTravellers(tripId, "Ravi Kumar", "E1023", listOf(people[1]))
        val after = repo.travellers.forTrip(tripId).map { it.id }
        val airAfter = Shares.parse(repo.expenses.get(air)!!.shares)
        assertEquals(after.toSet(), airAfter.keys)
        assertTrue(abs(airAfter.values.sum() - 54000.0) < 0.01 || !Shares.isBalanced(airAfter, 54000.0))
    }

    @Test fun templateFormatWithMileageAndMandatoryFields() = runBlocking {
        val tripId = repo.createTrip(TripEntity(title = "IA_Hosur_Q1_2026", clientName = "Sample Electricals Ltd", projectCode = "SEL-IA", location = "Hosur", purpose = "Internal Audit",
            startEpochDay = LocalDate.of(2026, 7, 20).toEpochDay(), endEpochDay = LocalDate.of(2026, 9, 17).toEpochDay(), onwardBookedBy = "Self Booking", returnBookedBy = "Office",
            notes = "Anita joined directly from the Chennai client."), "Ravi Kumar", "E1")
        repo.saveTravellers(tripId, "Ravi Kumar", "E1", listOf(TravellerEntity(tripId = tripId, name = "Anita Rao"), TravellerEntity(tripId = tripId, name = "Sam Joseph")))
        val people = repo.travellers.forTrip(tripId)
        // Own vehicle trip: 134 km x 7.5 + 100 toll = 1105, no bill, only Anita.
        val m = repo.addMileageExpense(tripId, LocalDate.of(2026, 7, 21), 7.5)
        assertTrue(repo.validate(tripId, emptyMap()).any { it.blocking && it.message.contains("kilometres") })
        repo.saveExpense(repo.expenses.get(m)!!.copy(mileageKm = 134.0, tollAmount = 100.0, confirmed = true,
            description = com.tareimbursement.app.core.TravelMode.mileageRemark("Rammurthy nagar, BLR to Hosur", 134.0, 7.5, 100.0),
            shares = Shares.format(mapOf(people.first { it.name == "Anita Rao" }.id to 1105.0))))
        val mileage = repo.expenses.get(m)!!
        assertEquals(1105.0, mileage.amount!!, 0.001)
        assertEquals("Car - Own", mileage.modeOfTravel)
        // Normal bills; one without remarks is blocked.
        val dinner = bill(tripId, 830.0, 20, ExpenseCategory.BOARDING, "Hotel Mangalam")
        assertEquals("Food", repo.expenses.get(dinner)!!.modeOfTravel)
        val laundry = bill(tripId, 270.0, 24, ExpenseCategory.LAUNDRY, "Tumbledry")
        repo.expenses.update(repo.expenses.get(laundry)!!.copy(description = ""))
        assertTrue(repo.validate(tripId, emptyMap()).any { it.blocking && it.message.contains("remarks are mandatory") })
        repo.saveExpense(repo.expenses.get(laundry)!!.copy(description = "Laundry Service"))
        assertTrue(repo.validate(tripId, emptyMap()).joinToString { it.message }, repo.validate(tripId, emptyMap()).none { it.blocking })

        val exporter = ExportManager(repo, FakeRenderer(jpeg))
        val p = exporter.prepare(tripId, employee)
        val ml = p.data.lines.first { it.isMileage }
        assertTrue(!ml.billAvailable)
        assertEquals("Anita Rao", ml.teamMembers)
        assertEquals("Ravi Kumar, Anita Rao, Sam Joseph", p.data.lines.first { it.vendor == "Hotel Mangalam" }.teamMembers)
        assertEquals("20th Mar 2026_830.jpg", p.data.lines.first { it.vendor == "Hotel Mangalam" }.fileReference)
        assertEquals("", ml.fileReference)
        assertEquals("Office", p.data.claim.returnBookedBy)
        val out = exporter.generate(tripId, employee)
        val keep = File(System.getProperty("java.io.tmpdir"), "ta_test_out/template").apply { deleteRecursively(); mkdirs() }
        out.excel.copyTo(File(keep, "template_format.xlsx"), overwrite = true)

        // Check the workbook XML for the template's structure.
        val xml = HashMap<String, String>()
        ZipInputStream(out.excel.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; xml[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        val wbXml = xml.getValue("xl/workbook.xml")
        assertTrue(wbXml.contains("name=\"TA CON-SHEET \""))
        assertTrue(wbXml.contains("name=\"Annexure-Breakup daywise\""))
        assertTrue(wbXml.contains("name=\"Team member-wise\""))
        val con = xml.getValue("xl/worksheets/sheet1.xml")
        assertTrue(con.contains("SUMIF('Annexure-Breakup daywise'!B:B,'TA CON-SHEET '!C12,'Annexure-Breakup daywise'!C:C)"))
        assertTrue(con.contains("<v>6535</v>") && con.contains("<v>6595</v>"))
        assertTrue(con.contains("Boarding Expenses(Stay &amp; Food)"))
        assertTrue(con.contains("Team Members Name ") && con.contains("Ravi Kumar, Anita Rao, Sam Joseph"))
        assertTrue(con.contains("Balance to receive from office "))
        val annx = xml.getValue("xl/worksheets/sheet2.xml")
        assertTrue(annx.contains("<f>(134*7.5)+100</f>"))
        assertTrue(annx.contains("Remarks -Mandatory ") && annx.contains(" Mode of travel-Mandatory ") && annx.contains("Sup Bills  Y/N"))
        assertTrue(annx.contains("Own Vehicle, including Toll charges ( Rammurthy nagar, BLR to Hosur ) 7.5 per km*134kms + 100 ( Toll )"))
        assertTrue(annx.contains("Anita joined directly from the Chennai client."))
        assertTrue(annx.contains("Mandatory Fields "))
    }

    @Test fun manualSplitRequiresAssignment() = runBlocking {
        val tripId = trip()
        val a = bill(tripId, 1000.0, 11, ExpenseCategory.LOCAL_CONVEYANCE, "Uber")
        repo.saveEntities(tripId, SplitMode.MANUAL, listOf(BillingEntity(tripId = tripId, name = "Entity A", code = "A"), BillingEntity(tripId = tripId, name = "Entity B", code = "B")))
        assertTrue(repo.validate(tripId, emptyMap()).any { it.blocking && it.message.contains("choose which client entity") })
        val ents = repo.entities.forTrip(tripId)
        repo.expenses.update(repo.expenses.get(a)!!.copy(entityId = ents[0].id))
        assertTrue(repo.validate(tripId, emptyMap()).none { it.blocking })
        // Deleting an entity unassigns its bills.
        repo.saveEntities(tripId, SplitMode.MANUAL, listOf(ents[1]))
        assertEquals(null, repo.expenses.get(a)!!.entityId)
        assertTrue(repo.validate(tripId, emptyMap()).any { it.blocking && it.message.contains("at least two client entities") })
    }

    @Test fun importDetectsForeignCurrencyAndUsesRememberedRate() = runBlocking {
        val tripId = trip(currency = "INR")
        val ex = FakeExtractor().apply { texts["*"] = "Marriott Dubai\nInvoice Date 12/03/2026\nTotal Amount AED 1,250.00" }
        val importer = FileImporter(ctx.contentResolver, repo, ex, ctx.cacheDir)
        val src = File(ctx.cacheDir, "aed.jpg").apply { writeBytes(jpeg) }
        val r1 = importer.importFiles(tripId, listOf(FileImporter.Source(src, "aed.jpg")), null, File(ctx.cacheDir, "st1"))
        val e1 = repo.expenses.get(r1.createdExpenseIds.single())!!
        assertEquals("AED", e1.currency); assertEquals(1250.0, e1.foreignAmount!!, 0.0); assertEquals(null, e1.amount)
        repo.saveExpense(e1.copy(exchangeRate = 22.75, amount = Currencies.toInr(1250.0, 22.75)))
        val r2 = importer.importFiles(tripId, listOf(FileImporter.Source(src, "aed2.jpg")), null, File(ctx.cacheDir, "st2"))
        val e2 = repo.expenses.get(r2.createdExpenseIds.single())!!
        assertEquals(22.75, e2.exchangeRate!!, 0.0)
        assertEquals(28437.5, e2.amount!!, 0.001)
        assertTrue("same date & INR amount flagged", (e2.duplicateOfId ?: 0) > 0)
    }

    /** A database created by v1.0.0 opens in v1.1.0 with all data intact. */
    @Test fun migrationFrom1To2KeepsData() {
        ctx.deleteDatabase("migrate.db")
        val schema = JSONObject(File("schemas/com.tareimbursement.app.data.AppDatabase/1.json").readText()).getJSONObject("database")
        val path = ctx.getDatabasePath("migrate.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { raw ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val idx = e.optJSONArray("indices")
                if (idx != null) for (j in 0 until idx.length()) raw.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            raw.execSQL("INSERT INTO trips (id,title,clientName,projectCode,location,purpose,startEpochDay,endEpochDay,advance,status,claimNumber,createdAt,notes) VALUES (1,'Old trip','Client','P1','Pune','x',20000,20002,500.0,'SUBMITTED','TA-202401-001',0,'')")
            raw.execSQL("INSERT INTO expenses (id,tripId,amount,vendor,billNo,suggestionReason,suggestionAlternatives,nature,description,paymentMode,remarks,confirmed,noBill,dateConfident,amountConfident,ocrText,createdAt,category) VALUES (1,1,820.5,'Uber','','','','','','','',1,0,1,1,'',0,'LOCAL_CONVEYANCE')")
            raw.version = 1
        }
        val migrated = androidx.room.Room.databaseBuilder(ctx, AppDatabase::class.java, "migrate.db").addMigrations(*com.tareimbursement.app.data.Migrations.ALL).allowMainThreadQueries().build()
        runBlocking {
            val t = migrated.trips().get(1)
            assertNotNull(t)
            assertEquals("INR", t!!.currency); assertEquals(SplitMode.NONE, t.split); assertEquals("TA-202401-001", t.claimNumber)
            val e = migrated.expenses().get(1)!!
            assertEquals(820.5, e.amount!!, 0.0); assertEquals("INR", e.currency); assertFalse(e.entityLocked); assertEquals("", e.shares)
            assertTrue(migrated.travellers().forTrip(1).isEmpty())
            migrated.billingEntities().insert(BillingEntity(tripId = 1, name = "E"))
            assertEquals(1, migrated.billingEntities().forTrip(1).size)
        }
        migrated.close()
        ctx.deleteDatabase("migrate.db")
    }
}
