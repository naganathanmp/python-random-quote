package com.tareimbursement.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Remarks in the style of the firm's past annexures (see RemarkWriter). */
class RemarkWriterTest {
    private fun w(c: ExpenseCategory, text: String, vendor: String = "", place: String = "Hosur") = RemarkWriter.write(c, text, vendor, place)

    @Test fun rapidoBikeWithLabelledRoute() {
        val t = """Rapido
            Ride Details
            Bike
            Pickup
            148, Halasuru, Bengaluru, Karnataka 560008, India
            Drop
            Bommasandra Industrial Area, Hosur Road, Bengaluru, Karnataka 560099
            Total Fare ₹ 186.00""".trimIndent()
        assertEquals("Rapido Bike from 148 Halasuru to Bommasandra Industrial Area Hosur Road", w(ExpenseCategory.LOCAL_CONVEYANCE, t))
        assertEquals("Bike", RemarkWriter.mode(ExpenseCategory.LOCAL_CONVEYANCE, t, ""))
    }

    @Test fun uberGoWithTimedAddressesAndToll() {
        val t = """Thanks for riding, Asha
            Uber Go
            Total ₹612.40
            Trip fare ₹540.00
            Tolls, Surcharges and Fees
            Toll ₹ 52.00
            09:12 am | Bommasandra, Hosur Rd, Bengaluru, Karnataka 560099, India
            10:05 am | 96, Cambridge Road, Halasuru, Bengaluru, Karnataka 560008, India""".trimIndent()
        assertEquals("Uber Go from Bommasandra Hosur Rd to 96 Cambridge Road (toll incl.)", w(ExpenseCategory.LOCAL_CONVEYANCE, t))
        assertEquals("Cab", RemarkWriter.mode(ExpenseCategory.LOCAL_CONVEYANCE, t, ""))
    }

    @Test fun airportPickupUberXl() {
        val t = """UberXL
            Total ₹1,450.00
            11:40 pm | Indira Gandhi International Airport, Terminal 3, New Delhi, Delhi 110037
            12:35 am | Dharamshila Narayana Hospital, Vasundhara Enclave, Delhi 110096""".trimIndent()
        assertEquals("UberXL from Indira Gandhi International Airport Terminal 3 to Dharamshila Narayana Hospital Vasundhara Enclave",
            w(ExpenseCategory.PICKUP_DROP, t, place = "Delhi"))
    }

    @Test fun restaurantDinnerBoardingAndDuringTravel() {
        val t = """HOTEL MANGALAM
            TAX INVOICE
            Date: 20/07/2026  Time: 20:45
            Veg Meals x2  790.00
            CGST 2.5% 19.75
            Grand Total 829.50""".trimIndent()
        assertEquals("Dinner - Hotel Mangalam ( Hosur )", w(ExpenseCategory.BOARDING, t, "Hotel Mangalam"))
        assertEquals("Dinner at Hotel Mangalam, Hosur", w(ExpenseCategory.EXPENSES_DURING_TRAVEL, t, "Hotel Mangalam"))
        assertEquals("Food", RemarkWriter.mode(ExpenseCategory.BOARDING, t, "Hotel Mangalam"))
    }

    @Test fun breakfastAndLunchFromBillTime() {
        assertEquals("Breakfast - Amrutha Sagar ( Hosur )", w(ExpenseCategory.BOARDING, "Amrutha Sagar\n21-07-2026 08:12 AM\nIdli Vada 120\nTotal 120", "Amrutha Sagar"))
        assertEquals("Lunch at Shrinidhi Upchar, Bommasandra", w(ExpenseCategory.EXPENSES_DURING_TRAVEL, "Shrinidhi Upchar\nBill Date 22/07/26 01:15 PM\nThali 180\nTotal 180", "Shrinidhi Upchar", "Bommasandra"))
    }

    @Test fun foodDeliveryApp() {
        val t = """Swiggy
            Order delivered on November 26, 2026 at 7:11 PM
            BILL DETAILS
            Item Total ₹454.00
            Taxes ₹22.70
            Bill Total (Paid via Card) ₹476.70""".trimIndent()
        assertEquals("Dinner via Swiggy - Kolkata", w(ExpenseCategory.EXPENSES_DURING_TRAVEL, t, place = "Kolkata"))
        assertEquals("Dinner - Swiggy ( Hosur )", w(ExpenseCategory.BOARDING, t, "Swiggy"))
    }

    @Test fun paymentReceiptInsteadOfBillIsExplained() {
        val t = """PhonePe
            Paid to
            SRI SAI TIFFIN CENTRE
            ₹ 150
            Transaction Successful
            12:48 pm on 23 Jul 2026
            UPI Transaction ID 420567812345""".trimIndent()
        assertEquals("Lunch - food bill not provided, hence attached payment receipt (PhonePe UPI) - Delhi", w(ExpenseCategory.EXPENSES_DURING_TRAVEL, t, place = "Delhi"))
    }

    @Test fun tollMetroLaundryHotel() {
        assertEquals("NHAI Electronic City Toll (two-wheeler) - Bangalore",
            w(ExpenseCategory.LOCAL_CONVEYANCE, "NHAI\nElectronic City Toll Plaza\nVehicle class: Two wheeler\nAmount 25", place = "Bangalore"))
        assertEquals("Bangalore Metro recharge via PhonePe", w(ExpenseCategory.LOCAL_CONVEYANCE, "PhonePe\nNamma Metro BMRCL card recharge\n₹ 500\nSuccessful", place = "Bangalore"))
        assertEquals("Hotel Narayani Kolkata - Laundry bill", w(ExpenseCategory.LAUNDRY, "Laundry\nTotal 1998", "Hotel Narayani Kolkata"))
        assertEquals("Hotel stay - Red Fox Hotel ( Delhi ) - 3 nights",
            w(ExpenseCategory.BOARDING, "Red Fox Hotel\nCheck-in 20 Jul 2026 Check-out 23 Jul 2026\nRoom tariff 3 nights\nTotal 9,450", "Red Fox Hotel", "Delhi"))
    }

    @Test fun placesAreShortened() {
        assertEquals("148 Halasuru", RemarkWriter.shortPlace("148, Halasuru, Bengaluru, Karnataka 560008, India"))
        assertEquals("Bommasandra Hosur Rd", RemarkWriter.shortPlace("Bommasandra, Hosur Rd, Bengaluru, Karnataka 560099, India"))
    }
}
