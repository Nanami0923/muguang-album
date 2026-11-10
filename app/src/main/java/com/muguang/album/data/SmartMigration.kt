package com.muguang.album.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val SMART_MIGRATION = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val albumColumns = mapOf("motionMode" to "TEXT NOT NULL DEFAULT 'OFF'", "motionStrength" to "REAL NOT NULL DEFAULT 0.12",
            "maxCrop" to "REAL NOT NULL DEFAULT 0.4", "blurBackground" to "INTEGER NOT NULL DEFAULT 0", "playbackCategories" to "TEXT NOT NULL DEFAULT ''")
        albumColumns.forEach { (name, type) -> db.execSQL("ALTER TABLE albums ADD COLUMN $name $type") }
        db.execSQL("ALTER TABLE sources ADD COLUMN analysisState TEXT NOT NULL DEFAULT 'RUNNING'")
        val photoColumns = linkedMapOf("fileSize" to "INTEGER NOT NULL DEFAULT 0", "trashedAt" to "INTEGER", "unavailableReason" to "TEXT",
            "analysis_orientedWidth" to "INTEGER NOT NULL DEFAULT 0", "analysis_orientedHeight" to "INTEGER NOT NULL DEFAULT 0",
            "analysis_exifOrientation" to "INTEGER NOT NULL DEFAULT 1", "analysis_autoCategory" to "TEXT NOT NULL DEFAULT 'UNKNOWN'",
            "analysis_autoSubCategory" to "TEXT", "analysis_manualCategory" to "TEXT", "analysis_classificationStatus" to "TEXT NOT NULL DEFAULT 'PENDING'")
        listOf("autoX", "autoY", "manualX", "manualY", "regionX", "regionY", "regionWidth", "regionHeight").forEach {
            photoColumns["analysis_$it"] = "REAL"
        }
        photoColumns.putAll(mapOf("analysis_focusSource" to "TEXT NOT NULL DEFAULT 'NONE'", "analysis_focusStatus" to "TEXT NOT NULL DEFAULT 'PENDING'",
            "analysis_taskState" to "TEXT NOT NULL DEFAULT 'QUEUED'", "analysis_modelVersion" to "TEXT NOT NULL DEFAULT ''",
            "analysis_error" to "TEXT", "analysis_customMotionMode" to "TEXT"))
        photoColumns.forEach { (name, type) -> db.execSQL("ALTER TABLE photos ADD COLUMN $name $type") }
        db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_analysis_taskState_trashedAt_sourceId ON photos (analysis_taskState, trashedAt, sourceId)")
    }
}
