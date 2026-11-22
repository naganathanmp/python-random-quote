package com.tareimbursement.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tareimbursement.app.core.ClaimStatus
import com.tareimbursement.app.core.ExpenseCategory
import java.time.LocalDate

/** One TA statement = one project assignment / trip. */
@Entity(tableName = "trips")
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
) {
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startEpochDay)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(endEpochDay)
    val claimStatus: ClaimStatus get() = ClaimStatus.of(status)
    val isArchived: Boolean get() = claimStatus.archived
}

@Entity(
    tableName = "expenses",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
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
) {
    val date: LocalDate? get() = dateEpochDay?.let { LocalDate.ofEpochDay(it) }
    val categoryEnum: ExpenseCategory? get() = ExpenseCategory.fromName(category)
    val suggestedEnum: ExpenseCategory? get() = ExpenseCategory.fromName(suggestedCategory)
    val alternatives: List<ExpenseCategory> get() = suggestionAlternatives.split(',').mapNotNull { ExpenseCategory.fromName(it.trim()) }
}

enum class AttachmentKind { IMAGE, PDF, TEXT, VIDEO, OTHER }

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(entity = ExpenseEntity::class, parentColumns = ["id"], childColumns = ["expenseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("expenseId"), Index("tripId")],
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
) {
    val kindEnum: AttachmentKind get() = runCatching { AttachmentKind.valueOf(kind) }.getOrDefault(AttachmentKind.OTHER)
    val extension: String get() = originalName.substringAfterLast('.', "").lowercase().ifEmpty {
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
    val total: Double,
    val expenseCount: Int,
    val pendingCount: Int,
) {
    val claimStatus: ClaimStatus get() = ClaimStatus.of(status)
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startEpochDay)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(endEpochDay)
}
