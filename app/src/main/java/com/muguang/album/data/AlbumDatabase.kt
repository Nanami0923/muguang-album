package com.muguang.album.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "albums")
internal data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val intervalSeconds: Int,
    val shuffle: Boolean,
    val loop: Boolean,
    val sortOrder: String,
    val scale: String,
    val transitionMillis: Int,
    val musicSourceId: String?,
    val musicEnabled: Boolean,
    val musicOrder: String,
    val musicVolume: Float,
)

@Entity(
    tableName = "sources",
    foreignKeys = [ForeignKey(
        entity = AlbumEntity::class,
        parentColumns = ["id"],
        childColumns = ["albumId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("albumId"), Index(value = ["albumId", "uri", "kind"], unique = true)],
)
internal data class SourceEntity(
    @PrimaryKey val id: String,
    val albumId: String,
    val uri: String,
    val name: String,
    val kind: String,
    val recursive: Boolean,
    val available: Boolean,
    val error: String?,
    val lastScanAt: Long,
)

@Entity(
    tableName = "photos",
    primaryKeys = ["sourceId", "id"],
    foreignKeys = [ForeignKey(
        entity = SourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sourceId")],
)
internal data class PhotoEntity(
    val id: String,
    val sourceId: String,
    val uri: String,
    val name: String,
    val modifiedAt: Long,
)

@Entity(
    tableName = "music_tracks",
    primaryKeys = ["sourceId", "id"],
    foreignKeys = [ForeignKey(
        entity = SourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sourceId")],
)
internal data class TrackEntity(
    val id: String,
    val sourceId: String,
    val uri: String,
    val name: String,
)

@Entity(
    tableName = "schedules",
    foreignKeys = [ForeignKey(
        entity = AlbumEntity::class,
        parentColumns = ["id"],
        childColumns = ["albumId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("albumId")],
)
internal data class ScheduleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val albumId: String,
    val startMinute: Int,
    val endMinute: Int,
    val daysMask: Int,
    val enabled: Boolean,
    val revision: Int,
)

@Dao
internal interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY rowid")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM sources ORDER BY rowid")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM photos ORDER BY sourceId, name COLLATE NOCASE")
    fun observePhotos(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM music_tracks ORDER BY sourceId, name COLLATE NOCASE")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM schedules ORDER BY startMinute, rowid")
    fun observeSchedules(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM albums ORDER BY rowid")
    suspend fun albums(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun album(id: String): AlbumEntity?

    @Query("SELECT * FROM sources ORDER BY rowid")
    suspend fun sources(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun source(id: String): SourceEntity?

    @Query("SELECT * FROM schedules ORDER BY startMinute")
    suspend fun schedules(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun schedule(id: String): ScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbum(album: AlbumEntity): Long

    // UPDATE, rather than REPLACE, preserves children under the foreign keys.
    @Query("""UPDATE albums SET name = :name, intervalSeconds = :intervalSeconds,
        shuffle = :shuffle, loop = :loop, sortOrder = :sortOrder, scale = :scale,
        transitionMillis = :transitionMillis, musicSourceId = :musicSourceId,
        musicEnabled = :musicEnabled, musicOrder = :musicOrder, musicVolume = :musicVolume
        WHERE id = :id""")
    suspend fun updateAlbum(
        id: String,
        name: String,
        intervalSeconds: Int,
        shuffle: Boolean,
        loop: Boolean,
        sortOrder: String,
        scale: String,
        transitionMillis: Int,
        musicSourceId: String?,
        musicEnabled: Boolean,
        musicOrder: String,
        musicVolume: Float,
    )

    @Query("UPDATE albums SET musicSourceId = :sourceId, musicEnabled = :enabled WHERE id = :albumId")
    suspend fun bindMusic(albumId: String, sourceId: String?, enabled: Boolean)

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteAlbum(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: SourceEntity): Long

    @Query("UPDATE sources SET recursive = :recursive WHERE id = :id")
    suspend fun updateRecursive(id: String, recursive: Boolean)

    @Query("UPDATE sources SET available = :available, error = :error WHERE id = :id")
    suspend fun updateAvailability(id: String, available: Boolean, error: String?)

    @Query("UPDATE sources SET available = 1, error = NULL, lastScanAt = :scannedAt WHERE id = :id")
    suspend fun completeScan(id: String, scannedAt: Long)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteSource(id: String)

    @Query("DELETE FROM photos WHERE sourceId = :sourceId")
    suspend fun deleteSourcePhotos(sourceId: String)

    @Query("DELETE FROM music_tracks WHERE sourceId = :sourceId")
    suspend fun deleteSourceTracks(sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhotos(photos: List<PhotoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSchedule(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteSchedule(id: String)
}

@Database(
    entities = [AlbumEntity::class, SourceEntity::class, PhotoEntity::class, TrackEntity::class, ScheduleEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class AlbumDatabase : RoomDatabase() {
    abstract fun dao(): AlbumDao
}
