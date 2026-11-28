package com.tareimbursement.app.core.ai

import com.tareimbursement.app.core.ExpenseCategory
import com.tareimbursement.app.core.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Evaluation set for bill reading: realistic, messy OCR text of Indian travel bills with the
 * correct answers. Prompts are written to build/ai-eval/prompts.tsv; if an on-device-model run
 * left responses in build/ai-eval/responses.tsv, they are scored here (rules alone vs rules+AI).
 */
class BillAiEvalTest {
    data class Sample(val id: String, val text: String, val date: LocalDate?, val amount: Double, val cat: ExpenseCategory, val meta: LocalDate? = null)

    private val trip = BillContext(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 29), "Hosur")
    private val today = LocalDate.of(2026, 11, 25)

    val samples = listOf(
        Sample("hotel_food", "HOTEL MANGALAM\nPURE VEG RESTAURANT\nHosur - 635109\nGSTIN 33AABCH1234K1Z5\nBill No 4512 Table 7\nDt:20/07/26 21:14\nMasala Dosa 2 240.00\nVeg Meals 2 400.00\nLime Juice 2 150.00\nSub Total 790.00\nCGST 2.5% 19.75\nSGST 2.5% 19.75\nRound Off 0.50\nNet Amt 830.00\nThank You Visit Again", LocalDate.of(2026, 7, 20), 830.0, ExpenseCategory.BOARDING),
        Sample("uber_airport", "Uber\nThanks for riding, Naveen\nJuly 20, 2026\nTotal ₹845.32\nTrip fare 702.10\nAirport Surcharge 100.00\nBooking fee 43.22\nKempegowda International Airport T1\nto Hosur Road, Electronic City\n46.3 km | 1 h 12 min", LocalDate.of(2026, 7, 20), 845.32, ExpenseCategory.PICKUP_DROP),
        Sample("rapido_auto", "rapido\nAUTO RECEIPT\nRide ID RD17845198430949275\nTime of Ride Jul 20th 2026, 08:42 AM\nPickup Home, Ejipura\nDrop Ejipura Junction\nSelected Price\n% 100\nPaid by Cash", LocalDate.of(2026, 7, 20), 100.0, ExpenseCategory.LOCAL_CONVEYANCE),
        Sample("split_columns", "TUMBLEDRY\nLaundry & Dry Cleaning\nInvoice T7746\n28-07-2026\nShirt wash & iron\nTrouser wash & iron\nBlazer dry clean\nSub Total\nGST 18%\nGrand Total\n2,326.27\n418.73\n2,745.00", LocalDate.of(2026, 7, 28), 2745.0, ExpenseCategory.LAUNDRY),
        Sample("irctc", "IRCTC E-TICKET\nElectronic Reservation Slip (ERS)\nPNR No: 4521367890  Train No./Name: 12007 / SHATABDI EXP\nClass: CHAIR CAR (CC)\nFrom: KSR BENGALURU (SBC)  To: CHENNAI CENTRAL (MAS)\nDate of Journey: 26-Jul-2026\nTicket Fare ₹ 1,055.00\nIRCTC Convenience Fee ₹ 35.40\nTotal Fare ₹ 1,090.40", LocalDate.of(2026, 7, 26), 1090.4, ExpenseCategory.TRAIN_FARE),
        Sample("indigo", "IndiGo\nGST Invoice\nPNR: X7YK2Q\nInvoice Date 18 Jul 2026\nFlight 6E 2134 MAA-BLR 20 Jul 2026\nBase Fare 4,200\nAirport Fees 912\nIGST 5% 256\nTotal Amount INR 5,368.00", LocalDate.of(2026, 7, 18), 5368.0, ExpenseCategory.AIR_FARE),
        Sample("swiggy", "Swiggy\nOrder #187263549912\nDelivered 26 Jul, 9:14 PM\nChickpet Donne Biryani\n2 x Donne Biryani 640\nItem Total ₹640\nDelivery Fee ₹45\nTaxes ₹31\nDiscount -₹40\nPaid via UPI ₹676", LocalDate.of(2026, 7, 26), 676.0, ExpenseCategory.BOARDING),
        Sample("no_date_meta", "AMRUTHA SAGAR\nVEG RESTAURANT HOSUR\nIdli Vada 1 70.00\nCoffee 2 60.00\nPongal 1 80.00\nTotal 210.00\nThank you", null, 210.0, ExpenseCategory.BOARDING, meta = LocalDate.of(2026, 7, 21)),
        Sample("metro", "Bangalore Metro Rail Corporation\nBMRCL QR Ticket\nFrom: Bommasandra To: Indiranagar\nDate: 16/09/2026 18:05\nFare: Rs. 70.00", LocalDate.of(2026, 9, 16), 70.0, ExpenseCategory.LOCAL_CONVEYANCE),
        Sample("hotel_stay", "THE RESIDENCY HOSUR\nTAX INVOICE / FOLIO\nGuest: Mr. Ravi Kumar Room 304\nArrival 20/07/2026 Departure 24/07/2026\nRoom Charges 4 Nights @ 2,800 11,200.00\nCGST 6% 672.00 SGST 6% 672.00\nTotal Amount 12,544.00\nAdvance Paid 0.00\nBalance Payable 12,544.00\nInvoice Date 24/07/2026", LocalDate.of(2026, 7, 24), 12544.0, ExpenseCategory.BOARDING),
        Sample("toll", "NHAI\nATTIBELE TOLL PLAZA\nFASTag Transaction\nVehicle KA03MX1234 Car/Jeep/Van\nDate 21-07-2026 07:55\nAmount Rs 100\nSingle Journey", LocalDate.of(2026, 7, 21), 100.0, ExpenseCategory.LOCAL_CONVEYANCE),
        Sample("ola_cab", "OLA\nInvoice CRN 6821937455\nRide on 22 Jul 2026\nOla Mini\nHosur Bus Stand to Titan Industrial Area\nRide Fare 312.00\nTolls 0.00\nTaxes 15.60\nTotal Bill (rounded) 328", LocalDate.of(2026, 7, 22), 328.0, ExpenseCategory.LOCAL_CONVEYANCE),
        Sample("snack_travel", "Cafe Coffee Day\nKempegowda Intl Airport T1\nInv: CCD/BLR/99812\n25/07/2026 16:20\nCappuccino 1 210\nSandwich 1 190\nTotal Rs 400.00\nCGST incl.", LocalDate.of(2026, 7, 25), 400.0, ExpenseCategory.EXPENSES_DURING_TRAVEL),
        Sample("bus", "redBus\nTicket\nTIN TS8K29XQ\nBengaluru to Hosur\nDoj: 17 Aug 2026  05:30\nSeat L4\nBase Fare 180.00\nGST 9.00\nDiscount -0.00\nTotal Paid 195.00\nIncl. operator charge 6.00", LocalDate.of(2026, 8, 17), 195.0, ExpenseCategory.BUS_FARE),
        Sample("dubai_hotel", "MARRIOTT HOTEL AL JADDAF DUBAI\nTax Invoice TRN 100234567800003\nInvoice No 88214\nDate 12/03/2026\nRoom 2 nights 1,050.00\nTourism Dirham 40.00\nVAT 5% 54.50\nTotal AED 1,144.50", LocalDate.of(2026, 3, 12), 1144.5, ExpenseCategory.BOARDING),
        Sample("garbled", "SRI SELVI CHETTINADU MESS\nH0SUR\n22/O7/2O26\nMeals 3 x 180 540\nChicken Fry 1 220\nTotaI 7 9 5 . 0 0\nNet Amount 795.00", LocalDate.of(2026, 7, 22), 795.0, ExpenseCategory.BOARDING),
    )

    private val dir = File("build/ai-eval").apply { mkdirs() }

    @Test fun writePromptsAndScoreAvailableResponses() {
        File(dir, "prompts.tsv").writeText(samples.joinToString("\n") { s ->
            s.id + "\t" + BillAi.prompt(s.text, trip.copy(metadataDate = s.meta), ModelFamily.QWEN).replace("\\", "\\\\").replace("\n", "\\n").replace("\t", " ")
        })
        // Fresh model run (build/ai-eval/responses.tsv) if present, else the recorded Qwen 2.5 1.5B answers.
        val respFile = File(dir, "responses.tsv").takeIf { it.exists() } ?: File(javaClass.classLoader!!.getResource("ai_eval_responses_qwen15.tsv")!!.toURI())
        var rulesScore = 0; var fusedScore = 0; var catRules = 0; var catFused = 0; var total = 0
        val responses = if (respFile.exists()) respFile.readLines().filter { it.contains('\t') }.associate { it.substringBefore('\t') to it.substringAfter('\t').replace("\\n", "\n").replace("\\\\", "\\") } else emptyMap()
        val report = StringBuilder("id\trules(date,amount,cat)\tfused(date,amount,cat)\n")
        samples.forEach { s ->
            val rules = ReceiptParser.parse(s.text, trip.tripStart, trip.tripEnd, today)
            val ai = responses[s.id]?.let { BillAi.parse(it) }?.let { BillAi.validate(it, s.text, trip.copy(metadataDate = s.meta), today) }
            val fused = BillAi.fuse(rules, ai, s.meta)!!
            fun ok(d: LocalDate?, a: Double?) = (if (d == s.date || (s.date == null && d == s.meta)) 1 else 0) + (if (a != null && kotlin.math.abs(a - s.amount) < 0.01) 1 else 0)
            val r = ok(rules.date, rules.amount); val f = ok(fused.date, fused.amount)
            rulesScore += r; fusedScore += f; total += 2
            if (rules.suggestion.category == s.cat) catRules++
            if (fused.suggestion.category == s.cat) catFused++
            report.append("${s.id}\t${rules.date},${rules.amount},${rules.suggestion.category}\t${fused.date},${fused.amount},${fused.suggestion.category}${ai?.remarks?.let { " | $it" } ?: ""}${ai?.rejected?.takeIf { it.isNotEmpty() }?.let { " rejected=$it" } ?: ""}\n")
        }
        report.append("\nDate+amount correct: rules $rulesScore/$total, with AI $fusedScore/$total (responses: ${responses.size})\nCategory correct: rules $catRules/${samples.size}, with AI $catFused/${samples.size}\n")
        File(dir, "report.txt").writeText(report.toString())
        println(report)
        assertTrue("recorded model answers are present", responses.size == samples.size)
        assertTrue("AI must not make results worse", fusedScore >= rulesScore)
        assertEquals("dates and amounts with AI", total, fusedScore)
    }

    @Test fun validatorRejectsInventedValues() {
        val text = "Shop\nDate 20/07/2026\nGrand Total 830.00"
        val r = BillAi.validate(mapOf("amount" to 1830.0, "date" to "2026-07-21", "category" to "SPACESHIP", "mode" to "Rocket", "remarks" to "Dinner at shop", "bill_no" to "ZZ999"), text, trip, today)
        assertNull(r.amount); assertNull(r.date); assertNull(r.category); assertNull(r.modeOfTravel); assertNull(r.billNo)
        assertEquals(listOf("amount", "date", "category"), r.rejected)
        val good = BillAi.validate(mapOf("amount" to "830", "date" to "2026-07-20", "category" to "BOARDING", "mode" to "food", "remarks" to "Dinner at shop"), text, trip, today)
        assertEquals(830.0, good.amount!!, 0.0); assertEquals(LocalDate.of(2026, 7, 20), good.date)
        assertEquals(ExpenseCategory.BOARDING, good.category); assertEquals("Food", good.modeOfTravel)
    }

    @Test fun parserIsLenient() {
        val m = BillAi.parse("\"date\": \"2026-07-20\", \"amount\": 830.00, 'vendor': 'Hotel Mangalam', category: BOARDING, \"remarks\": \"Dinner\"} trailing words")!!
        assertEquals("2026-07-20", m["date"]); assertEquals(830.0, m["amount"]); assertEquals("Hotel Mangalam", m["vendor"]); assertEquals("BOARDING", m["category"])
        assertEquals(mapOf("a" to 1.0), BillAi.parse("```json\n{\"a\": 1}\n```"))
        assertTrue(BillAi.parse("\"amount\": 5, \"date\": \"2026-0")!!.containsKey("amount")) // truncated output keeps finished keys
    }

    @Test fun metadataDateFillsMissingDate() {
        val s = samples.first { it.id == "no_date_meta" }
        val rules = ReceiptParser.parse(s.text, trip.tripStart, trip.tripEnd, today)
        assertNull(rules.date)
        val fused = BillAi.fuse(rules, null, s.meta)!!
        assertEquals(s.meta, fused.date)
        assertTrue(!fused.dateConfident)
    }
}
