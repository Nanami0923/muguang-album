package com.muguang.album.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muguang.album.core.Album
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlbumDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: AlbumDatabase
    private lateinit var name: String

    @Before fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        name = "storage-test-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, AlbumDatabase::class.java, name).build()
    }

    @After fun closeDatabase() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun metadataSurvivesDatabaseReopen() = runBlocking {
        val dao = database.dao()
        insertAlbumAndSource(dao)
        dao.insertPhotos(listOf(photo("photo-1")))
        dao.insertTracks(listOf(TrackEntity("track-1", "source", "content://test/song", "配乐.mp3")))
        dao.saveSchedule(ScheduleEntity("plan", "晚间", "family", 1140, 1320, 127, true, 3))
        database.close()
        database = Room.databaseBuilder(context, AlbumDatabase::class.java, name).build()
        val reopened = database.dao()
        assertEquals("家庭相册", reopened.albums().single().name)
        assertEquals("photo-1", reopened.observePhotos().first().single().id)
        assertEquals("配乐.mp3", reopened.observeTracks().first().single().name)
        assertEquals(3, reopened.schedules().single().revision)
    }

    @Test fun albumUpdatePreservesChildIndexes() = runBlocking {
        val dao = database.dao()
        insertAlbumAndSource(dao)
        dao.insertPhotos(listOf(photo("photo-1")))
        dao.updateAlbum("family", "旅行", 5, true, true, "NAME", "FIT", 0, null, false, "SEQUENTIAL", .35f)
        assertEquals("旅行", dao.albums().single().name)
        assertEquals(1, dao.sources().size)
        assertEquals(1, dao.observePhotos().first().size)
    }

    @Test fun failedIndexReplacementRollsBackOldPhotos() = runBlocking {
        val dao = database.dao()
        insertAlbumAndSource(dao)
        dao.insertPhotos(listOf(photo("old")))
        try {
            database.withTransaction {
                dao.deleteSourcePhotos("source")
                dao.insertPhotos(listOf(photo("partial-new")))
                throw IllegalStateException("模拟扫描提交中断")
            }
        } catch (_: IllegalStateException) {
            // Deliberately exercise rollback rather than publishing a partial scan.
        }
        assertEquals(listOf("old"), dao.observePhotos().first().map { it.id })
    }

    @Test fun removingAlbumCascadesOnlyItsMetadataAndSchedules() = runBlocking {
        val dao = database.dao()
        insertAlbumAndSource(dao)
        dao.insertPhotos(listOf(photo("photo-1")))
        dao.insertTracks(listOf(TrackEntity("track-1", "source", "content://test/song", "配乐.mp3")))
        dao.saveSchedule(ScheduleEntity("plan", "晚间", "family", 1140, 1320, 127, true, 1))
        dao.deleteAlbum("family")
        assertTrue(dao.sources().isEmpty())
        assertTrue(dao.observePhotos().first().isEmpty())
        assertTrue(dao.observeTracks().first().isEmpty())
        assertTrue(dao.schedules().isEmpty())
    }

    @Test fun duplicateSourceDoesNotReplaceExistingIndex() = runBlocking {
        val dao = database.dao()
        insertAlbumAndSource(dao)
        dao.insertPhotos(listOf(photo("photo-1")))
        val duplicate = SourceEntity("different-id", "family", "content://test/tree/photos", "重复", "PHOTO", true, true, null, 0)
        assertEquals(-1L, dao.insertSource(duplicate))
        assertEquals("source", dao.sources().single().id)
        assertFalse(dao.observePhotos().first().isEmpty())
    }

    private suspend fun insertAlbumAndSource(dao: AlbumDao) {
        val album = Album("family", "家庭相册")
        dao.insertAlbum(AlbumEntity(
            album.id, album.name, album.intervalSeconds, album.shuffle, album.loop,
            album.sortOrder.name, album.scale.name, album.transitionMillis, album.musicSourceId,
            album.musicEnabled, album.musicOrder.name, album.musicVolume,
        ))
        dao.insertSource(SourceEntity("source", "family", "content://test/tree/photos", "照片", "PHOTO", true, true, null, 0))
    }

    private fun photo(id: String) = PhotoEntity(id, "source", "content://test/$id", "$id.jpg", 0)
}
