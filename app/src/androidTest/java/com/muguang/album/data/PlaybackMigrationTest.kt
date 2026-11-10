package com.muguang.album.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.muguang.album.core.USER_REMOVED_PHOTO
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PlaybackMigrationTest {
    @Test fun v3UpgradePreservesLibraryAndAnimationSettingsSurviveReopening() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "playback-upgrade-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets.open("com.muguang.album.data.AlbumDatabase/3.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}", table))
            }
            db.execSQL("INSERT INTO albums VALUES ('a','旧版相册',17,1,1,'NAME','FIT',500,NULL,0,'SEQUENTIAL',0.35,'FIT_ZOOM',0.12,0.4,0,'ART')")
            db.execSQL("INSERT INTO sources VALUES ('s','a','content://test/tree','目录','PHOTO',1,1,NULL,0,'RUNNING')")
            db.execSQL("INSERT INTO schedules VALUES ('plan','晚间','a',1140,1320,127,1,2)")
            val photo = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == "photos" }
            val fields = photo.getJSONArray("fields")
            val values = ContentValues()
            for (i in 0 until fields.length()) {
                val field = fields.getJSONObject(i)
                if (field.optBoolean("notNull", false)) {
                    if (field.getString("affinity") == "TEXT") values.put(field.getString("columnName"), "")
                    else values.put(field.getString("columnName"), 0)
                }
            }
            values.put("id", "p"); values.put("sourceId", "s"); values.put("uri", "content://test/p")
            values.put("name", "照片.jpg"); values.put("trashedAt", 123L); values.put("unavailableReason", USER_REMOVED_PHOTO)
            values.put("analysis_manualX", .8f); values.put("analysis_manualY", .2f)
            values.put("analysis_manualCategory", "ART"); values.put("analysis_aiStatus", "SUCCEEDED")
            db.insertOrThrow("photos", null, values)
            db.version = 3
        }
        var db = Room.databaseBuilder(context, AlbumDatabase::class.java, name).addMigrations(PLAYBACK_MIGRATION).build()
        try {
            val dao = db.dao()
            val album = dao.album("a")!!
            assertEquals("RANDOM", album.transitionEffect); assertEquals(6, album.focusDurationSeconds)
            assertEquals(500, album.transitionMillis); assertEquals("FIT_ZOOM", album.motionMode)
            assertEquals(17, album.intervalSeconds); assertEquals(2, dao.schedules().single().revision)
            val photo = dao.photo("s", "p")!!
            assertEquals(.8f, photo.metadata.manualX); assertEquals("SUCCEEDED", photo.metadata.aiStatus)
            assertEquals(USER_REMOVED_PHOTO, photo.unavailableReason); assertEquals(123L, photo.trashedAt)
            dao.animationSettings("a", "CUBE", 4)
            db.close()
            db = Room.databaseBuilder(context, AlbumDatabase::class.java, name).build()
            assertEquals("CUBE", db.dao().album("a")!!.transitionEffect)
            assertEquals(4, db.dao().album("a")!!.focusDurationSeconds)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
