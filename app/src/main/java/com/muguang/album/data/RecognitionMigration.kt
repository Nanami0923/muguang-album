package com.muguang.album.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val RECOGNITION_MIGRATION = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val defaults = mapOf("medium" to "UNKNOWN", "composition" to "UNKNOWN", "people" to "UNKNOWN",
            "presentation" to "UNRESOLVED", "classificationSource" to "LOCAL", "disputeReasons" to "",
            "fingerprint" to "", "localEvidence" to "", "aiStatus" to "NOT_REQUIRED", "aiCacheKey" to "")
        defaults.forEach { (name, value) -> db.execSQL("ALTER TABLE photos ADD COLUMN analysis_$name TEXT NOT NULL DEFAULT '$value'") }
        db.execSQL("ALTER TABLE photos ADD COLUMN analysis_manualPresentation TEXT")
        db.execSQL("ALTER TABLE photos ADD COLUMN analysis_aiError TEXT")
        db.execSQL("ALTER TABLE photos ADD COLUMN analysis_generation INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_results (cacheKey TEXT NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL, result TEXT NOT NULL, model TEXT NOT NULL, requestId TEXT, inputTokens INTEGER, outputTokens INTEGER, createdAt INTEGER NOT NULL)")
    }
}
