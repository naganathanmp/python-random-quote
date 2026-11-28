package com.tareimbursement.app.core

/**
 * Standard accounting heads of a TA annexure (PKF style). The [label] is written verbatim
 * into the Excel statement; the CON-SHEET SUMIF formulas match against it, so it must be
 * identical between the Annexure rows and the summary rows.
 */
enum class ExpenseCategory(
    val label: String,
    val shortLabel: String,
    /** GL account number used in the firm's TA CON-SHEET. */
    val accountCode: Int,
    /** Exact spelling in the firm's TA template (SUMIF on the CON-SHEET matches it, trailing spaces included). */
    val templateLabel: String,
    /** Heads greyed out in the template as "not applicable for all". */
    val restricted: Boolean = false,
    /** Guidance from the template's accounting-head notes. */
    val guidance: String = "",
) {
    AIR_FARE("Air Fare", "Air", 6535, "Air Fare"),
    BUS_FARE("Bus Fare", "Bus", 6540, "Bus Fare"),
    EXPENSES_DURING_TRAVEL("Expenses during travel", "During travel", 6545, "Expenses during travel "),
    TRAIN_FARE("Train Fare", "Train", 6555, "Train Fare"),
    PICKUP_DROP("Pick up & Drop", "Pick up/Drop", 6560, "Pick up & Drop"),
    BOARDING("Boarding Expenses (Stay & Food)", "Stay & Food", 6565, "Boarding Expenses(Stay & Food)"),
    LAUNDRY("Laundry Expenses", "Laundry", 6578, "Laundry Expenses"),
    LOCAL_CONVEYANCE("Local Conveyance", "Local travel", 6590, "Local Conveyance"),
    OTHER("Other Charges", "Other", 6571, "Other Charges ", true,
        "Printing,Scan / Sprial binding, purchase of harddisk / purchased pendrive for client / test report cost (pandemic period) "),
    TELEPHONE("Telephone Charges – Clients", "Telephone", 6575, "Telephone charges - clients", true,
        "Charges can be claimed only prior approval from the Project manager(TL) or Project Partner "),
    VISA("Visa Handling Charges", "Visa", 6580, "Visa Handling Charges", true,
        "Travelling aboard office will arrange the Visa process, incase you are informed to apply by self you can use this head "),
    INSURANCE("Insurance Travel", "Insurance", 6585, "Insurance Travel", true,
        "Aboard travel -Cost will be borne by office, if spent by self  include in the cost to include in the TA "),
    CAR_HIRE("Car Hire Charges -Clients", "Car hire", 6577, "Car Hire Charges -Clients", true,
        "Taxi / Car arranged for by self / for the team during the audit for long distance to visit client factories / Plant "),
    CAR_PARKING("Car Parking Charges", "Parking", 6595, "Car Parking Charges ", true,
        "Not applicable for Artcile Staffs or Charges can be claimed only prior approval from the Project Manager(TL) or Project Partner ");

    /** Folder-safe name used for the category sub-folders inside the receipts ZIP. */
    val folderName: String
        get() = label.replace('–', '-').replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()

    companion object {
        fun fromName(name: String?): ExpenseCategory? =
            name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}
