package com.muguang.album.core

enum class SourceKind { PHOTO, MUSIC }
enum class SortOrder { NAME, MODIFIED }
enum class MusicOrder { SEQUENTIAL, RANDOM }
enum class PhotoScale { FIT, CROP }

data class Album(
    val id: String,
    val name: String,
    val intervalSeconds: Int = 10,
    val shuffle: Boolean = false,
    val loop: Boolean = true,
    val sortOrder: SortOrder = SortOrder.NAME,
    val scale: PhotoScale = PhotoScale.FIT,
    val transitionMillis: Int = 500,
    val musicSourceId: String? = null,
    val musicEnabled: Boolean = false,
    val musicOrder: MusicOrder = MusicOrder.SEQUENTIAL,
    val musicVolume: Float = .35f,
)

data class FolderSource(
    val id: String,
    val albumId: String,
    val uri: String,
    val name: String,
    val kind: SourceKind,
    val recursive: Boolean = true,
    val available: Boolean = true,
    val error: String? = null,
    val lastScanAt: Long = 0,
)

data class Photo(
    val id: String,
    val sourceId: String,
    val uri: String,
    val name: String,
    val modifiedAt: Long = 0,
)

data class MusicTrack(
    val id: String,
    val sourceId: String,
    val uri: String,
    val name: String,
)

data class PlaySchedule(
    val id: String,
    val name: String,
    val albumId: String,
    val startMinute: Int = 8 * 60,
    val endMinute: Int = 22 * 60,
    val daysMask: Int = 127,
    val enabled: Boolean = true,
    val revision: Int = 1,
)

data class AppPreferences(
    val dynamicColor: Boolean = true,
    val keepStandbyAwake: Boolean = true,
    val showClock: Boolean = false,
    val standbyBrightness: Float = .04f,
    val remindersEnabled: Boolean = false,
)

data class LibraryState(
    val albums: List<Album> = emptyList(),
    val sources: List<FolderSource> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val tracks: List<MusicTrack> = emptyList(),
    val schedules: List<PlaySchedule> = emptyList(),
    val preferences: AppPreferences = AppPreferences(),
    val scanningSourceIds: Set<String> = emptySet(),
    val initialized: Boolean = false,
) {
    fun photosFor(albumId: String): List<Photo> {
        val sourceIds = sources.filter { it.albumId == albumId && it.kind == SourceKind.PHOTO && it.available }.map { it.id }.toSet()
        return photos.filter { it.sourceId in sourceIds }.distinctBy { it.id }
    }
    fun tracksFor(album: Album): List<MusicTrack> {
        if (sources.none { it.id == album.musicSourceId && it.available && it.kind == SourceKind.MUSIC }) return emptyList()
        return tracks.filter { it.sourceId == album.musicSourceId }.distinctBy { it.id }
    }
}

interface AlbumRepository {
    val state: kotlinx.coroutines.flow.StateFlow<LibraryState>
    suspend fun initialize()
    suspend fun createAlbum(name: String): String
    suspend fun updateAlbum(album: Album)
    suspend fun deleteAlbum(id: String)
    suspend fun addSource(albumId: String, uri: String, kind: SourceKind, recursive: Boolean): String
    suspend fun updateSource(sourceId: String, recursive: Boolean)
    suspend fun removeSource(sourceId: String)
    suspend fun scan(sourceId: String)
    suspend fun saveSchedule(schedule: PlaySchedule)
    suspend fun deleteSchedule(id: String)
    suspend fun updatePreferences(preferences: AppPreferences)
}
