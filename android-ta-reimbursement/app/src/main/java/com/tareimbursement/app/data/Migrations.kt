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

    /**
     * v1.3.0 → v2.0.0: team sync ids, on-device AI state, trust check, chat. The two remarks fields
     * are merged into the single mandatory "Remarks" (description) and payment mode is dropped.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            fun add(table: String, vararg cols: String) = cols.forEach { db.execSQL("ALTER TABLE `$table` ADD COLUMN $it") }
            val uidSql = "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)),2) || '-' || substr('89ab', 1 + (abs(random()) % 4), 1) || substr(hex(randomblob(2)),2) || '-' || hex(randomblob(6)))"
            add("trips", "`uid` TEXT NOT NULL DEFAULT ''", "`ownerUid` TEXT NOT NULL DEFAULT ''", "`shared` INTEGER NOT NULL DEFAULT 0",
                "`myRole` TEXT NOT NULL DEFAULT ''", "`updatedAt` INTEGER NOT NULL DEFAULT 0", "`dirty` INTEGER NOT NULL DEFAULT 0",
                "`syncedAt` INTEGER", "`chatReadAt` INTEGER NOT NULL DEFAULT 0")
            add("expenses", "`uid` TEXT NOT NULL DEFAULT ''", "`updatedAt` INTEGER NOT NULL DEFAULT 0", "`dirty` INTEGER NOT NULL DEFAULT 0",
                "`deleted` INTEGER NOT NULL DEFAULT 0", "`addedBy` TEXT NOT NULL DEFAULT ''", "`addedByUid` TEXT NOT NULL DEFAULT ''",
                "`aiState` TEXT NOT NULL DEFAULT ''", "`aiNote` TEXT NOT NULL DEFAULT ''", "`userEdited` INTEGER NOT NULL DEFAULT 0",
                "`dateFromMetadata` INTEGER NOT NULL DEFAULT 0", "`trustLevel` TEXT NOT NULL DEFAULT 'OK'", "`trustReport` TEXT NOT NULL DEFAULT ''",
                "`trustAck` TEXT NOT NULL DEFAULT ''", "`dupNote` TEXT NOT NULL DEFAULT ''", "`dupStrong` INTEGER NOT NULL DEFAULT 0")
            add("attachments", "`uid` TEXT NOT NULL DEFAULT ''", "`sha256` TEXT NOT NULL DEFAULT ''", "`dHash` INTEGER",
                "`remotePath` TEXT NOT NULL DEFAULT ''", "`dirty` INTEGER NOT NULL DEFAULT 0", "`deleted` INTEGER NOT NULL DEFAULT 0")
            add("travellers", "`uid` TEXT NOT NULL DEFAULT ''", "`contact` TEXT NOT NULL DEFAULT ''", "`userUid` TEXT NOT NULL DEFAULT ''",
                "`inviteStatus` TEXT NOT NULL DEFAULT ''")
            for (t in listOf("trips", "expenses", "attachments", "travellers")) {
                db.execSQL("UPDATE `$t` SET `uid` = $uidSql")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${t}_uid` ON `$t` (`uid`)")
            }
            db.execSQL("UPDATE `trips` SET `updatedAt` = `createdAt`")
            db.execSQL("UPDATE `expenses` SET `updatedAt` = `createdAt`")
            // One remarks field: keep the mandatory remarks and append the old optional one when it adds something.
            db.execSQL(
                """UPDATE `expenses` SET `description` = CASE
                     WHEN TRIM(`remarks`) = '' THEN `description`
                     WHEN TRIM(`description`) = '' THEN TRIM(`remarks`)
                     WHEN instr(lower(`description`), lower(TRIM(`remarks`))) > 0 THEN `description`
                     ELSE TRIM(`description`) || ' - ' || TRIM(`remarks`) END,
                   `remarks` = '', `paymentMode` = ''"""
            )
            db.execSQL(MESSAGES_SQL)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_tripId` ON `messages` (`tripId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_uid` ON `messages` (`uid`)")
        }
    }

    const val MESSAGES_SQL = "CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `uid` TEXT NOT NULL, `expenseUid` TEXT NOT NULL, `authorUid` TEXT NOT NULL, `authorName` TEXT NOT NULL, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `sent` INTEGER NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
