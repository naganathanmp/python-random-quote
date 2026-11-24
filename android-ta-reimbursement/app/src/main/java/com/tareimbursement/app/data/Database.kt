package com.tareimbursement.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert suspend fun insert(t: TripEntity): Long
    @Update suspend fun update(t: TripEntity)
    @Delete suspend fun delete(t: TripEntity)
    @Query("SELECT * FROM trips WHERE id = :id") suspend fun get(id: Long): TripEntity?
    @Query("SELECT * FROM trips WHERE id = :id") fun observe(id: Long): Flow<TripEntity?>
    @Query("SELECT * FROM trips ORDER BY startEpochDay DESC") suspend fun all(): List<TripEntity>
    @Query("SELECT * FROM trips ORDER BY startEpochDay DESC") fun observeAll(): Flow<List<TripEntity>>

    @Query(
        """SELECT t.id, t.title, t.clientName, t.projectCode, t.location, t.startEpochDay, t.endEpochDay, t.advance, t.status,
           t.claimNumber, t.submittedAt, t.paidAt, t.paidAmount, t.createdAt,
           COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE e.tripId = t.id), 0) AS total,
           (SELECT COUNT(*) FROM expenses e WHERE e.tripId = t.id) AS expenseCount,
           (SELECT COUNT(*) FROM expenses e WHERE e.tripId = t.id AND e.confirmed = 0) AS pendingCount
           FROM trips t ORDER BY CASE WHEN t.status = 'DRAFT' THEN t.createdAt ELSE COALESCE(t.submittedAt, t.createdAt) END DESC"""
    )
    fun observeWithStats(): Flow<List<TripWithStats>>

    @Query("SELECT COUNT(*) FROM trips WHERE claimNumber LIKE :prefix || '%'") suspend fun countClaimPrefix(prefix: String): Int
}

@Dao
interface ExpenseDao {
    @Insert suspend fun insert(e: ExpenseEntity): Long
    @Update suspend fun update(e: ExpenseEntity)
    @Delete suspend fun delete(e: ExpenseEntity)
    @Query("SELECT * FROM expenses WHERE id = :id") suspend fun get(id: Long): ExpenseEntity?
    @Query("SELECT * FROM expenses WHERE id = :id") fun observe(id: Long): Flow<ExpenseEntity?>
    @Query("SELECT * FROM expenses WHERE tripId = :tripId ORDER BY COALESCE(dateEpochDay, 99999999), createdAt") suspend fun forTrip(tripId: Long): List<ExpenseEntity>
    @Query("SELECT * FROM expenses WHERE tripId = :tripId ORDER BY COALESCE(dateEpochDay, 99999999), createdAt") fun observeForTrip(tripId: Long): Flow<List<ExpenseEntity>>
    @Query("SELECT * FROM expenses") fun observeAll(): Flow<List<ExpenseEntity>>
    @Query("SELECT * FROM expenses") suspend fun all(): List<ExpenseEntity>
    @Query("UPDATE expenses SET duplicateOfId = NULL WHERE duplicateOfId = :id") suspend fun clearDuplicateRefs(id: Long)
}

@Dao
interface AttachmentDao {
    @Insert suspend fun insert(a: AttachmentEntity): Long
    @Update suspend fun update(a: AttachmentEntity)
    @Delete suspend fun delete(a: AttachmentEntity)
    @Query("SELECT * FROM attachments WHERE id = :id") suspend fun get(id: Long): AttachmentEntity?
    @Query("SELECT * FROM attachments WHERE expenseId = :expenseId ORDER BY isPrimary DESC, addedAt") suspend fun forExpense(expenseId: Long): List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE expenseId = :expenseId ORDER BY isPrimary DESC, addedAt") fun observeForExpense(expenseId: Long): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM attachments WHERE tripId = :tripId ORDER BY isPrimary DESC, addedAt") suspend fun forTrip(tripId: Long): List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE tripId = :tripId ORDER BY isPrimary DESC, addedAt") fun observeForTrip(tripId: Long): Flow<List<AttachmentEntity>>
    @Query("UPDATE attachments SET expenseId = :to, isPrimary = 0 WHERE expenseId = :from") suspend fun moveAll(from: Long, to: Long)
}

@Dao
interface EventDao {
    @Insert suspend fun insert(e: TripEventEntity): Long
    @Query("SELECT * FROM trip_events WHERE tripId = :tripId ORDER BY timestamp DESC, id DESC") fun observeForTrip(tripId: Long): Flow<List<TripEventEntity>>
    @Query("SELECT * FROM trip_events WHERE tripId = :tripId ORDER BY timestamp DESC, id DESC") suspend fun forTrip(tripId: Long): List<TripEventEntity>
}

@Dao
interface TravellerDao {
    @Insert suspend fun insert(t: TravellerEntity): Long
    @Update suspend fun update(t: TravellerEntity)
    @Delete suspend fun delete(t: TravellerEntity)
    @Query("SELECT * FROM travellers WHERE tripId = :tripId ORDER BY isSelf DESC, sortOrder, id") suspend fun forTrip(tripId: Long): List<TravellerEntity>
    @Query("SELECT * FROM travellers WHERE tripId = :tripId ORDER BY isSelf DESC, sortOrder, id") fun observeForTrip(tripId: Long): Flow<List<TravellerEntity>>
}

@Dao
interface BillingEntityDao {
    @Insert suspend fun insert(e: BillingEntity): Long
    @Update suspend fun update(e: BillingEntity)
    @Delete suspend fun delete(e: BillingEntity)
    @Query("SELECT * FROM billing_entities WHERE id = :id") suspend fun get(id: Long): BillingEntity?
    @Query("SELECT * FROM billing_entities WHERE tripId = :tripId ORDER BY sortOrder, id") suspend fun forTrip(tripId: Long): List<BillingEntity>
    @Query("SELECT * FROM billing_entities WHERE tripId = :tripId ORDER BY sortOrder, id") fun observeForTrip(tripId: Long): Flow<List<BillingEntity>>
    @Query("UPDATE expenses SET entityId = NULL, entityLocked = 0 WHERE entityId = :entityId") suspend fun unassign(entityId: Long)
}

@Database(
    entities = [TripEntity::class, ExpenseEntity::class, AttachmentEntity::class, TripEventEntity::class, TravellerEntity::class, BillingEntity::class],
    version = 2, exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun expenses(): ExpenseDao
    abstract fun attachments(): AttachmentDao
    abstract fun events(): EventDao
    abstract fun travellers(): TravellerDao
    abstract fun billingEntities(): BillingEntityDao

    companion object {
        const val NAME = "ta.db"
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).addMigrations(*Migrations.ALL).build()
        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
}
