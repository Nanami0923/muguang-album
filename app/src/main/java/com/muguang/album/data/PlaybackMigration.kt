package com.muguang.album.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val PLAYBACK_MIGRATION = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE albums ADD COLUMN transitionEffect TEXT NOT NULL DEFAULT 'RANDOM'")
        db.execSQL("ALTER TABLE albums ADD COLUMN focusDurationSeconds INTEGER NOT NULL DEFAULT 6")
    }
}
