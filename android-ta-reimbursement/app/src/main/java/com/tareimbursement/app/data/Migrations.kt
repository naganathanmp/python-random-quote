package com.tareimbursement.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema upgrades. Existing claims, bills and files are kept. */
object Migrations {
    /** v1.0.0 → v1.1.0: foreign currency, billing split across client entities, multiple travellers. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `trips` ADD COLUMN `currency` TEXT NOT NULL DEFAULT 'INR'")
            db.execSQL("ALTER TABLE `trips` ADD COLUMN `splitMode` TEXT NOT NULL DEFAULT 'NONE'")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `currency` TEXT NOT NULL DEFAULT 'INR'")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `foreignAmount` REAL")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `exchangeRate` REAL")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `entityId` INTEGER")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `entityLocked` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `shares` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `trips` ADD COLUMN `onwardBookedBy` TEXT NOT NULL DEFAULT 'Self Booking'")
            db.execSQL("ALTER TABLE `trips` ADD COLUMN `returnBookedBy` TEXT NOT NULL DEFAULT 'Self Booking'")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `modeOfTravel` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `mileageKm` REAL")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `mileageRate` REAL")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `tollAmount` REAL")
            db.execSQL(TRAVELLERS_SQL)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_travellers_tripId` ON `travellers` (`tripId`)")
            db.execSQL(ENTITIES_SQL)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_billing_entities_tripId` ON `billing_entities` (`tripId`)")
        }
    }

    const val TRAVELLERS_SQL = "CREATE TABLE IF NOT EXISTS `travellers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `name` TEXT NOT NULL, `employeeCode` TEXT NOT NULL, `isSelf` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    const val ENTITIES_SQL = "CREATE TABLE IF NOT EXISTS `billing_entities` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `name` TEXT NOT NULL, `code` TEXT NOT NULL, `sharePercent` REAL NOT NULL, `sortOrder` INTEGER NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    /** v1.1.0 → v1.2.0: budget and optional daily spend limit per claim. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `trips` ADD COLUMN `budget` REAL")
            db.execSQL("ALTER TABLE `trips` ADD COLUMN `dailyLimit` REAL")
            db.execSQL("ALTER TABLE `trips` ADD COLUMN `dailyLimitPerPerson` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
