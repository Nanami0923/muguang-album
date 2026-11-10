package com.muguang.album.core

enum class SourceKind { PHOTO, MUSIC }
enum class SortOrder { NAME, MODIFIED }
enum class MusicOrder { SEQUENTIAL, RANDOM }
enum class PhotoScale { FIT, CROP }
enum class MotionMode(val title: String) { OFF("普通播放"), FIT_ZOOM("A 完整后放大"), CROP_ZOOM("B 铺满后放大"), AUTO("自动"), MIXED("混合") }
enum class PhotoCategory(val title: String) { REAL_PERSON("真实人物"), REAL_OTHER("真实非人物"), ART("绘画设计"), UNKNOWN("待分类") }

data class FocusPoint(val x: Float, val y: Float)
data class SafeRegion(val x: Float, val y: Float, val width: Float, val height: Float)

data class PhotoMetadata(
    val orientedWidth: Int = 0, val orientedHeight: Int = 0, val exifOrientation: Int = 1,
    val autoCategory: String = "UNKNOWN", val autoSubCategory: String? = null, val manualCategory: String? = null,
    val classificationStatus: String = "PENDING",
    val autoX: Float? = null, val autoY: Float? = null,
    val manualX: Float? = null, val manualY: Float? = null,
    val regionX: Float? = null, val regionY: Float? = null,
    val regionWidth: Float? = null, val regionHeight: Float? = null,
    val focusSource: String = "NONE", val focusStatus: String = "PENDING",
    val taskState: String = "QUEUED", val modelVersion: String = "",
    val error: String? = null, val customMotionMode: String? = null,
) {
    val category: PhotoCategory get() = runCatching { PhotoCategory.valueOf(manualCategory ?: if (autoCategory == "REAL") autoSubCategory ?: "UNKNOWN" else autoCategory) }.getOrDefault(PhotoCategory.UNKNOWN)
    val manualFocus: FocusPoint? get() = manualX?.let { x -> manualY?.let { FocusPoint(x, it) } }
    val autoFocus: FocusPoint? get() = autoX?.let { x -> autoY?.let { FocusPoint(x, it) } }
    val effectiveFocus: FocusPoint? get() = manualFocus ?: autoFocus
    val safeRegion: SafeRegion? get() = regionX?.let { x -> regionY?.let { y -> regionWidth?.let { w -> regionHeight?.let { SafeRegion(x, y, w, it) } } } }
    val displayStatus: String get() = when {
        manualFocus != null -> "手动焦点"
        taskState == "FAILED" -> "识别错误"
        taskState == "QUEUED" || taskState == "PROCESSING" -> "待识别"
        autoFocus != null -> "自动定位"
        else -> "待选点"
    }
}

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
    val motionMode: MotionMode = MotionMode.OFF,
    val motionStrength: Float = .12f,
    val maxCrop: Float = .4f,
    val blurBackground: Boolean = false,
    val playbackCategories: String = "",
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
    val analysisState: String = "RUNNING",
)

data class Photo(
    val id: String,
    val sourceId: String,
    val uri: String,
    val name: String,
    val modifiedAt: Long = 0,
    val fileSize: Long = 0,
    val trashedAt: Long? = null,
    val unavailableReason: String? = null,
    val metadata: PhotoMetadata = PhotoMetadata(),
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
    val galleryColumns: Int = 2,
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
        return photos.filter { it.sourceId in sourceIds && it.trashedAt == null }.distinctBy { it.id }
    }
    fun playbackPhotosFor(album: Album): List<Photo> {
        val selected = album.playbackCategories.split(',').filter { it.isNotBlank() }.toSet()
        return photosFor(album.id).filter { selected.isEmpty() || it.metadata.category.name in selected }
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
    suspend fun savePhotoEdits(sourceId: String, photoId: String, focus: FocusPoint?, category: PhotoCategory?, motion: MotionMode?) {}
    suspend fun setAnalysisState(sourceId: String, command: String) {}
    suspend fun markUnavailable(sourceId: String, photoId: String) {}
    fun setForeground(value: Boolean) {}
    fun setPlaying(value: Boolean) {}
    fun reportPlaybackFrame(millis: Long) {}
}
