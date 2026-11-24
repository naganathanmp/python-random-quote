package com.tareimbursement.app.core

import java.util.Locale

/** "Mode of travel-Mandatory" values used in the firm's TA annexure, with suggestions. */
object TravelMode {
    val options = listOf(
        "Car - Taxi", "Car - Own", "Auto", "Bike Taxi", "Bus", "Train", "Metro", "Flight",
        "Food", "Hotel Stay", "Laundry", "Car - Hired", "Parking", "Telephone", "Others",
    )

    fun suggest(category: ExpenseCategory?, text: String, vendor: String = ""): String {
        val t = (text + " " + vendor).lowercase(Locale.ROOT)
        fun has(vararg w: String) = w.any { Regex("(?<![a-z])${Regex.escape(it)}(?![a-z])").containsMatchIn(t) }
        return when (category) {
            ExpenseCategory.AIR_FARE -> "Flight"
            ExpenseCategory.TRAIN_FARE -> "Train"
            ExpenseCategory.BUS_FARE -> "Bus"
            ExpenseCategory.LAUNDRY -> "Laundry"
            ExpenseCategory.CAR_HIRE -> "Car - Hired"
            ExpenseCategory.CAR_PARKING -> "Parking"
            ExpenseCategory.TELEPHONE -> "Telephone"
            ExpenseCategory.BOARDING, ExpenseCategory.EXPENSES_DURING_TRAVEL ->
                if (has("room", "stay", "check-in", "check in", "night", "accommodation", "lodge", "resort") && !has("restaurant", "dinner", "lunch", "breakfast", "meal")) "Hotel Stay" else "Food"
            ExpenseCategory.LOCAL_CONVEYANCE, ExpenseCategory.PICKUP_DROP, null -> when {
                has("metro", "bmrcl", "dmrc") -> "Metro"
                has("bike taxi", "bike", "two wheeler") -> "Bike Taxi"
                has("auto", "rickshaw") -> "Auto"
                has("bus", "redbus", "ksrtc", "tnstc") -> "Bus"
                has("train", "irctc") -> "Train"
                has("own vehicle", "own car", "per km") -> "Car - Own"
                has("uber", "ola", "cab", "taxi", "car", "blusmart", "meru", "careem", "grab") -> "Car - Taxi"
                category == null -> ""
                else -> "Car - Taxi"
            }
            else -> "Others"
        }
    }

    /** A readable default for the mandatory Remarks column, e.g. "Meal - Hotel Mangalam". */
    fun defaultRemark(category: ExpenseCategory?, nature: String, vendor: String): String {
        val v = vendor.trim()
        val n = when {
            nature.startsWith("Food") -> "Meal"
            nature.startsWith("Hotel") -> "Hotel stay"
            nature.startsWith("Cab") || nature.startsWith("Airport") -> "Cab"
            nature == "Unknown" || nature.isBlank() -> category?.shortLabel ?: ""
            else -> nature
        }
        return listOf(n, v).filter { it.isNotBlank() }.joinToString(" - ")
    }

    /** Remark for own-vehicle claims in the same style as the template. */
    fun mileageRemark(route: String, km: Double, rate: Double, toll: Double?): String {
        val k = if (km % 1.0 == 0.0) km.toLong().toString() else km.toString()
        val r = if (rate % 1.0 == 0.0) rate.toLong().toString() else rate.toString()
        val tollPart = if (toll != null && toll > 0) " + ${if (toll % 1.0 == 0.0) toll.toLong() else toll} ( Toll )" else ""
        return "Own Vehicle${if (tollPart.isNotEmpty()) ", including Toll charges" else ""}${if (route.isNotBlank()) " ( $route )" else ""} $r per km*${k}kms$tollPart"
    }

    fun mileageAmount(km: Double, rate: Double, toll: Double?): Double = Math.round((km * rate + (toll ?: 0.0)) * 100) / 100.0
}
