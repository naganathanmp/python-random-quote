package com.tareimbursement.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tareimbursement.app.core.ClaimStatus
import com.tareimbursement.app.core.ExpenseCategory
import java.time.LocalDate

/** One TA statement = one project assignment / trip. */
@Entity(tableName = "trips", indices = [Index(value = ["uid"], unique = true)])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val clientName: String,
    val projectCode: String,
    val location: String,
    val purpose: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val advance: Double = 0.0,
    val status: String = ClaimStatus.DRAFT.name,
    val claimNumber: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val submittedAt: Long? = null,
    val approvedAt: Long? = null,
    val paidAt: Long? = null,
    val paidAmount: Double? = null,
    val notes: String = "",
    val outputsGeneratedAt: Long? = null,
    /** Default currency for new bills on this claim (foreign trips). */
    @ColumnInfo(defaultValue = "'INR'") val currency: String = "INR",
    /** Billing split across client entities: NONE, AUTO (balanced / by share %), MANUAL. */
    @ColumnInfo(defaultValue = "'NONE'") val splitMode: String = SplitMode.NONE.name,
    /** Template "To & Fro - Tickets booked": Self Booking / Office / Client. */
    @ColumnInfo(defaultValue = "'Self Booking'") val onwardBookedBy: String = "Self Booking",
    @ColumnInfo(defaultValue = "'Self Booking'") val returnBookedBy: String = "Self Booking",
    /** Approved budget for the assignment (optional). */
    val budget: Double? = null,
    /** Optional daily spend limit, for the whole team or per traveller. */
    val dailyLimit: Double? = null,
    @ColumnInfo(defaultValue = "0") val dailyLimitPerPerson: Boolean = false,
    // ---- v2.0 team sync (Supabase). uid is the global id shared by every phone on the project.
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    /** Signed-in user id of the project owner ('' = local-only claim). */
    @ColumnInfo(defaultValue = "''") val ownerUid: String = "",
    /** Shared with team members through the sync server. */
    @ColumnInfo(defaultValue = "0") val shared: Boolean = false,
    /** OWNER or MEMBER on shared claims. */
    @ColumnInfo(defaultValue = "''") val myRole: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
    /** Last time the claim was pulled from the server (millis). */
    val syncedAt: Long? = null,
    /** Chat messages newer than this are unread. */
    @ColumnInfo(defaultValue = "0") val chatReadAt: Long = 0,
) {
    val isMember: Boolean get() = shared && myRole == "MEMBER"
    val split: SplitMode get() = runCatching { SplitMode.valueOf(splitMode) }.getOrDefault(SplitMode.NONE)
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startEpochDay)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(endEpochDay)
    val claimStatus: ClaimStatus get() = ClaimStatus.of(status)
    val isArchived: Boolean get() = claimStatus.archived
}

@Entity(
    tableName = "expenses",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index(value = ["uid"], unique = true)],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val dateEpochDay: Long? = null,
    val amount: Double? = null,
    val vendor: String = "",
    val billNo: String = "",
    /** Confirmed accounting head (null until the user picks / confirms one). */
    val category: String? = null,
    val suggestedCategory: String? = null,
    val suggestionReason: String = "",
    val suggestionAlternatives: String = "",
    val nature: String = "",
    val description: String = "",
    val paymentMode: String = "",
    val remarks: String = "",
    val confirmed: Boolean = false,
    val noBill: Boolean = false,
    val duplicateOfId: Long? = null,
    val dateConfident: Boolean = false,
    val amountConfident: Boolean = false,
    val ocrText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** Bill currency. [amount] always holds the INR value; for foreign bills it is foreignAmount x exchangeRate. */
    @ColumnInfo(defaultValue = "'INR'") val currency: String = "INR",
    val foreignAmount: Double? = null,
    val exchangeRate: Double? = null,
    /** Client entity this bill is billed to (split claims). */
    val entityId: Long? = null,
    /** true = user fixed the entity; auto-allocation will not move it. */
    @ColumnInfo(defaultValue = "0") val entityLocked: Boolean = false,
    /** Traveller shares "travellerId=amount;..." in INR. Empty = whole amount to the claimant. */
    @ColumnInfo(defaultValue = "''") val shares: String = "",
    /** Template "Mode of travel-Mandatory". */
    @ColumnInfo(defaultValue = "''") val modeOfTravel: String = "",
    /** Own vehicle: amount = km × rate + toll. */
    val mileageKm: Double? = null,
    val mileageRate: Double? = null,
    val tollAmount: Double? = null,
    // ---- v2.0
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
    /** Soft delete so the deletion reaches the team's phones. */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /** Who added the bill (team projects). */
    @ColumnInfo(defaultValue = "''") val addedBy: String = "",
    @ColumnInfo(defaultValue = "''") val addedByUid: String = "",
    /** On-device AI: '' (not needed / off), QUEUED, DONE, FAILED. */
    @ColumnInfo(defaultValue = "''") val aiState: String = "",
    @ColumnInfo(defaultValue = "''") val aiNote: String = "",
    /** The person changed date/amount/head/remarks by hand: AI never overwrites them. */
    @ColumnInfo(defaultValue = "0") val userEdited: Boolean = false,
    /** Date was not printed on the bill and was taken from the file's metadata. */
    @ColumnInfo(defaultValue = "0") val dateFromMetadata: Boolean = false,
    /** Trust check (see TrustCheck): OK / INFO / WARN / BLOCK and its findings. */
    @ColumnInfo(defaultValue = "'OK'") val trustLevel: String = "OK",
    @ColumnInfo(defaultValue = "''") val trustReport: String = "",
    /** Reason the person gave to keep a flagged bill (warning or possible duplicate). */
    @ColumnInfo(defaultValue = "''") val trustAck: String = "",
    /** Possible duplicate found in this or another claim (strong match blocks until acknowledged). */
    @ColumnInfo(defaultValue = "''") val dupNote: String = "",
    @ColumnInfo(defaultValue = "0") val dupStrong: Boolean = false,
) {
    val trust: com.tareimbursement.app.core.trust.TrustReport get() = com.tareimbursement.app.core.trust.TrustReport.decode(trustReport)
    val trustBlocked: Boolean get() = trustLevel == "BLOCK"
    val needsTrustAck: Boolean get() = (trustLevel == "WARN" || (dupStrong && dupNote.isNotBlank())) && trustAck.isBlank()
    val isMileage: Boolean get() = mileageKm != null && mileageRate != null
    val isForeign: Boolean get() = currency != "INR"
    val date: LocalDate? get() = dateEpochDay?.let { LocalDate.ofEpochDay(it) }
    val categoryEnum: ExpenseCategory? get() = ExpenseCategory.fromName(category)
    val suggestedEnum: ExpenseCategory? get() = ExpenseCategory.fromName(suggestedCategory)
    val alternatives: List<ExpenseCategory> get() = suggestionAlternatives.split(',').mapNotNull { ExpenseCategory.fromName(it.trim()) }
}

enum class SplitMode(val label: String) {
    NONE("Not split"), AUTO("Auto - balanced / by share %"), MANUAL("Manual - I assign each bill")
}

/** A person travelling on the claim; the TA claim covers everyone's expenses. */
@Entity(
    tableName = "travellers",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index(value = ["uid"], unique = true)],
)
data class TravellerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    val employeeCode: String = "",
    /** The phone owner / claimant. */
    val isSelf: Boolean = false,
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    /** E-mail or mobile number used to invite this person to the project. */
    @ColumnInfo(defaultValue = "''") val contact: String = "",
    /** Signed-in user id once they joined ('' = not on the app / not joined yet). */
    @ColumnInfo(defaultValue = "''") val userUid: String = "",
    /** '' (not invited), INVITED, JOINED. */
    @ColumnInfo(defaultValue = "''") val inviteStatus: String = "",
) {
    val label: String get() = if (employeeCode.isBlank()) name else "$name ($employeeCode)"
}

/** A client entity / project code that part of the claim is billed to. */
@Entity(
    tableName = "billing_entities",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class BillingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    val code: String = "",
    /** Target share of the claim in % (0 = equal split). */
    val sharePercent: Double = 0.0,
    val sortOrder: Int = 0,
) {
    val label: String get() = if (code.isBlank()) name else "$name [$code]"
}

enum class AttachmentKind { IMAGE, PDF, TEXT, VIDEO, OTHER }

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(entity = ExpenseEntity::class, parentColumns = ["id"], childColumns = ["expenseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("expenseId"), Index("tripId"), Index(value = ["uid"], unique = true)],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    val tripId: Long,
    /** Path relative to the app's filesDir. */
    val storedPath: String,
    val originalName: String,
    val mimeType: String,
    val kind: String,
    /** true = the invoice / bill itself, false = supporting document. */
    val isPrimary: Boolean,
    val sizeBytes: Long,
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val uid: String = newUid(),
    /** SHA-256 of the file (exact-duplicate detection, upload integrity). */
    @ColumnInfo(defaultValue = "''") val sha256: String = "",
    /** Perceptual hash of the picture / first page (re-photographed duplicates). */
    val dHash: Long? = null,
    /** Path in the server's "bills" storage bucket once uploaded. */
    @ColumnInfo(defaultValue = "''") val remotePath: String = "",
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
) {
    val kindEnum: AttachmentKind get() = runCatching { AttachmentKind.valueOf(kind) }.getOrDefault(AttachmentKind.OTHER)
    val extension: String get() = originalName.substringAfterLast('.', "").lowercase().takeIf { e -> e.length in 1..5 && e.all { it in 'a'..'z' || it in '0'..'9' } }.orEmpty().ifEmpty {
        when (kindEnum) { AttachmentKind.IMAGE -> "jpg"; AttachmentKind.PDF -> "pdf"; AttachmentKind.TEXT -> "txt"; AttachmentKind.VIDEO -> "mp4"; else -> "bin" }
    }
}

/** Audit trail / timeline of a claim (created, files added, generated, submitted, approved, paid…). */
@Entity(
    tableName = "trip_events",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class TripEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val type: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/** Team chat on a shared project; [expenseUid] links a message to a bill ("Discuss this bill"). */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index(value = ["uid"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val uid: String = newUid(),
    val expenseUid: String = "",
    val authorUid: String = "",
    val authorName: String = "",
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** false until the server has it. */
    val sent: Boolean = false,
)

fun newUid(): String = java.util.UUID.randomUUID().toString()

data class TripWithStats(
    val id: Long,
    val title: String,
    val clientName: String,
    val projectCode: String,
    val location: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val advance: Double,
    val status: String,
    val claimNumber: String,
    val submittedAt: Long?,
    val paidAt: Long?,
    val paidAmount: Double?,
    val createdAt: Long,
    val budget: Double?,
    val shared: Boolean,
    val unread: Int,
    val total: Double,
    val expenseCount: Int,
    val pendingCount: Int,
) {
    val claimStatus: ClaimStatus get() = ClaimStatus.of(status)
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startEpochDay)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(endEpochDay)
}
