package com.muguang.album.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import com.muguang.album.core.PhotoMetadata
import com.muguang.album.playback.TRASH_RETENTION_MILLIS

class SmartStorageTest {
    @Test fun upgradeRetainsOldLibraryAndSupportsPersistentManualFocusAndTrash() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = JSONObject(instrumentation.context.assets.open("com.muguang.album.data.AlbumDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO albums VALUES ('a','保留相册',17,1,1,'NAME','FIT',500,NULL,0,'SEQUENTIAL',0.35)")
            old.execSQL("INSERT INTO sources VALUES ('s','a','content://test/tree','目录','PHOTO',1,1,NULL,0)")
            old.execSQL("INSERT INTO photos VALUES ('p','s','content://test/p','照片.jpg',123)")
            old.version = 1
        }
        var database = Room.databaseBuilder(context, AlbumDatabase::class.java, name).addMigrations(SMART_MIGRATION, RECOGNITION_MIGRATION, PLAYBACK_MIGRATION).build()
        try {
            var dao = database.dao()
            assertEquals(17, dao.album("a")!!.intervalSeconds)
            assertEquals("OFF", dao.album("a")!!.motionMode)
            val photo = dao.photo("s", "p")!!
            dao.insertPhotos(listOf(photo.copy(metadata = PhotoMetadata(manualX = .8f, manualY = .2f, manualCategory = "ART", taskState = "PROCESSING"))))
            database.close()
            database = Room.databaseBuilder(context, AlbumDatabase::class.java, name).addMigrations(SMART_MIGRATION, RECOGNITION_MIGRATION, PLAYBACK_MIGRATION).build()
            dao = database.dao(); dao.recoverAnalysis()
            assertEquals(.8f, dao.photo("s", "p")!!.metadata.manualX)
            assertEquals("QUEUED", dao.photo("s", "p")!!.metadata.taskState)
            dao.insertPhotos(listOf(photo.copy(id = "removed", trashedAt = 1,
                unavailableReason = com.muguang.album.core.USER_REMOVED_PHOTO)))
            val now = System.currentTimeMillis()
            dao.trashSource("s", now, "路径失效"); dao.trashSource("s", now + 1000, "重复失效")
            assertEquals(now, dao.photo("s", "p")!!.trashedAt)
            dao.purgeTrash(now - 1)
            assertNotNull(dao.photo("s", "p"))
            dao.purgeTrash(now)
            assertNull(dao.photo("s", "p"))
            assertEquals(com.muguang.album.core.USER_REMOVED_PHOTO, dao.photo("s", "removed")!!.unavailableReason)
            assertEquals(30L*86400000, TRASH_RETENTION_MILLIS)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
