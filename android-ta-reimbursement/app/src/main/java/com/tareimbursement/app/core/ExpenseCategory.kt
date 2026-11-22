package com.tareimbursement.app.core

/**
 * Standard accounting heads of a TA annexure (PKF style). The [label] is written verbatim
 * into the Excel statement; the CON-SHEET SUMIF formulas match against it, so it must be
 * identical between the Annexure rows and the summary rows.
 */
enum class ExpenseCategory(val label: String, val shortLabel: String) {
    AIR_FARE("Air Fare", "Air"),
    BUS_FARE("Bus Fare", "Bus"),
    EXPENSES_DURING_TRAVEL("Expenses during travel", "During travel"),
    TRAIN_FARE("Train Fare", "Train"),
    PICKUP_DROP("Pick up & Drop", "Pick up/Drop"),
    BOARDING("Boarding Expenses (Stay & Food)", "Stay & Food"),
    LAUNDRY("Laundry Expenses", "Laundry"),
    LOCAL_CONVEYANCE("Local Conveyance", "Local travel"),
    OTHER("Other Charges", "Other"),
    TELEPHONE("Telephone Charges – Clients", "Telephone"),
    VISA("Visa Handling Charges", "Visa"),
    INSURANCE("Insurance Travel", "Insurance"),
    CAR_HIRE("Car Hire Charges -Clients", "Car hire"),
    CAR_PARKING("Car Parking Charges", "Parking");

    /** Folder-safe name used for the category sub-folders inside the receipts ZIP. */
    val folderName: String
        get() = label.replace('–', '-').replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()

    companion object {
        fun fromName(name: String?): ExpenseCategory? =
            name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}
