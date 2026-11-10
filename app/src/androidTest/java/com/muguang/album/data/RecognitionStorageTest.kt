package com.muguang.album.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.muguang.album.analysis.ai.AiConfiguration
import com.muguang.album.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecognitionStorageTest {
    @Test fun upgradeV2PreservesManualLayerAndRecoversAmbiguousCallsWithoutResending() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
        val name="recognition-${UUID.randomUUID()}.db"; val path=context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema=JSONObject(instrumentation.context.assets.open("com.muguang.album.data.AlbumDatabase/2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i); val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}",table))
            }
            old.execSQL("INSERT INTO albums VALUES ('a','保留相册',17,1,1,'NAME','FIT',500,NULL,0,'SEQUENTIAL',0.35,'AUTO',0.12,0.4,0,'ART')")
            old.execSQL("INSERT INTO sources VALUES ('s','a','content://test/tree','目录','PHOTO',1,1,NULL,0,'RUNNING')")
            val photo=entities.let { list -> (0 until list.length()).map { list.getJSONObject(it) }.first { it.getString("tableName")=="photos" } }
            val fields=photo.getJSONArray("fields")
            val values=android.content.ContentValues()
            for(i in 0 until fields.length()) {
                val f=fields.getJSONObject(i); val column=f.getString("columnName")
                if(!f.optBoolean("notNull", false)) continue
                if(f.getString("affinity")=="TEXT") values.put(column,"") else values.put(column,0)
            }
            values.put("id","p"); values.put("sourceId","s"); values.put("uri","content://test/p"); values.put("name","旧照片")
            values.put("analysis_manualX",.8); values.put("analysis_manualY",.2); values.put("analysis_manualCategory","ART")
            values.put("analysis_taskState","DONE"); values.put("analysis_autoCategory","ART")
            old.insertOrThrow("photos",null,values); old.version=2
        }
        val database=Room.databaseBuilder(context,AlbumDatabase::class.java,name).addMigrations(RECOGNITION_MIGRATION, PLAYBACK_MIGRATION).build()
        try {
            val dao=database.dao(); val photo=dao.photo("s","p")!!
            assertEquals(.8f,photo.metadata.manualX); assertEquals("ART",photo.metadata.manualCategory)
            assertEquals("ART",dao.album("a")!!.playbackCategories)
            dao.insertPhotos(listOf(photo.copy(metadata=photo.metadata.copy(aiStatus="PROCESSING"))))
            dao.recoverAi()
            assertEquals("UNKNOWN_OUTCOME",dao.photo("s","p")!!.metadata.aiStatus)
            assertNull(dao.nextAi())
            dao.saveAiCache(AiResultEntity("key","fingerprint","{}","vision",null,123,45,1))
            assertEquals(123L,dao.aiCache("key")!!.inputTokens)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun credentialsAreEncryptedAndBatchLimitSurvivesRecreation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue("Credential test needs isolated application",context.packageName.endsWith(".smarttest"))
        val prefs=context.getSharedPreferences("ai_recognition",android.content.Context.MODE_PRIVATE)
        // This test is run in the isolated device-test application, never a user's settings.
        prefs.edit().clear().commit()
        try {
            val store=AiConfiguration(context)
            store.save(AiSettings(endpoint="https://example.test/v1/chat/completions",model="vision",enabled=true,maxImagesPerRun=1),"test-secret-value")
            assertEquals("test-secret-value",store.secret())
            assertFalse(prefs.getString("secret","")!!.contains("test-secret-value"))
            assertTrue(store.reserve()); assertFalse(AiConfiguration(context).reserve())
            store.newBatch(); assertTrue(store.reserve())
            assertTrue(runCatching { store.save(store.state.value.copy(endpoint="http://example.test"),null) }.isFailure)
        } finally { prefs.edit().clear().commit() }
    }
}
