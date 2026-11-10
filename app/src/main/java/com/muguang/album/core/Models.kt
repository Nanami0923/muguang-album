package com.muguang.album.core

enum class SourceKind { PHOTO, MUSIC }
const val USER_REMOVED_PHOTO = "已从当前相册移除"
enum class SortOrder { NAME, MODIFIED }
enum class MusicOrder { SEQUENTIAL, RANDOM }
enum class PhotoScale { FIT, CROP }
enum class SlideEffect(val title: String) {
    RANDOM("随机轮换"), FADE("渐入渐出"), PAGE("翻页"), CUBE("方块转动"), STACK("叠层"), FLIP("单面旋转"), NONE("无动画")
}
enum class AnimationSpeed(val title: String, val transitionMillis: Int, val focusSeconds: Int) {
    HIGH("高", 350, 3), MEDIUM("中", 700, 6), LOW("低", 1200, 10)
}
enum class MotionMode(val title: String) { OFF("普通播放"), FIT_ZOOM("A 完整后放大"), CROP_ZOOM("B 铺满后放大"), AUTO("自动"), MIXED("混合") }
enum class PhotoCategory(val title: String) {
    REAL_PERSON("照片 · 人物"), REAL_OTHER("照片 · 景物与其他"),
    ANIME_PERSON("动漫插画 · 人物"), ANIME_SCENERY("动漫插画 · 景物"), ANIME_OTHER("动漫插画 · 其他"),
    RENDERED("游戏与三维渲染"), DESIGN("设计与排版"), MIXED("混合媒介"),
    ART("绘画与设计（未细分）"), UNKNOWN("待确认类别");
    fun includes(other: PhotoCategory) = this == other || (this == ART && other in setOf(ANIME_PERSON, ANIME_SCENERY, ANIME_OTHER, RENDERED, DESIGN))
}

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
    val medium: String = "UNKNOWN", val composition: String = "UNKNOWN", val people: String = "UNKNOWN",
    val presentation: String = "UNRESOLVED", val manualPresentation: String? = null,
    val classificationSource: String = "LOCAL", val disputeReasons: String = "",
    val fingerprint: String = "", val localEvidence: String = "", val generation: Long = 0,
    val aiStatus: String = "NOT_REQUIRED", val aiError: String? = null, val aiCacheKey: String = "",
) {
    val category: PhotoCategory get() = runCatching { PhotoCategory.valueOf(manualCategory ?: if (autoCategory == "REAL") autoSubCategory ?: "UNKNOWN" else autoCategory) }.getOrDefault(PhotoCategory.UNKNOWN)
    val manualFocus: FocusPoint? get() = manualX?.let { x -> manualY?.let { FocusPoint(x, it) } }
    val autoFocus: FocusPoint? get() = autoX?.let { x -> autoY?.let { FocusPoint(x, it) } }
    val wholeView: Boolean get() = manualPresentation == "WHOLE_VIEW" || (manualPresentation == null && manualFocus == null && presentation == "WHOLE_VIEW")
    val effectiveFocus: FocusPoint? get() = if (wholeView) null else manualFocus ?: autoFocus
    val safeRegion: SafeRegion? get() = regionX?.let { x -> regionY?.let { y -> regionWidth?.let { w -> regionHeight?.let { SafeRegion(x, y, w, it) } } } }
    val displayStatus: String get() = when {
        wholeView -> "整体展示"
        manualFocus != null -> "手动焦点"
        taskState == "FAILED" -> "识别错误"
        taskState == "QUEUED" || taskState == "PROCESSING" -> "待识别"
        aiStatus == "PROCESSING" -> "AI 识别中"
        aiStatus in setOf("QUEUED", "WAITING_CONFIG") -> "待 AI 识别"
        aiStatus == "UNKNOWN_OUTCOME" -> "调用结果待确认"
        aiStatus == "FAILED" -> "AI 调用失败"
        aiStatus == "UNRESOLVED" -> "待人工调整"
        aiStatus == "SUCCEEDED" -> "AI 已识别"
        autoFocus != null -> "自动定位"
        else -> "待选点"
    }
    val categoryLabel: String get() = if(manualCategory==null && composition=="UNKNOWN") when(medium) {
        "ANIME_ILLUSTRATION" -> "动漫插画 · 待细分"
        "REAL" -> "照片 · 待细分"
        else -> category.title
    } else category.title
    val detailLabel: String get() = listOfNotNull(
        categoryLabel,
        when (people) { "SINGLE" -> "单人"; "MULTIPLE" -> "多人"; else -> null },
        when (composition) { "BALANCED" -> "人物与景物并重"; else -> null },
    ).joinToString(" · ")
}

data class Album(
    val id: String,
    val name: String,
    val intervalSeconds: Int = 10,
    val shuffle: Boolean = false,
    val loop: Boolean = true,
    val sortOrder: SortOrder = SortOrder.NAME,
    val scale: PhotoScale = PhotoScale.FIT,
    val transitionMillis: Int = 700,
    val musicSourceId: String? = null,
    val musicEnabled: Boolean = false,
    val musicOrder: MusicOrder = MusicOrder.SEQUENTIAL,
    val musicVolume: Float = .35f,
    val motionMode: MotionMode = MotionMode.OFF,
    val motionStrength: Float = .12f,
    val maxCrop: Float = .4f,
    val blurBackground: Boolean = false,
    val playbackCategories: String = "",
    val transitionEffect: SlideEffect = SlideEffect.RANDOM,
    val focusDurationSeconds: Int = 6,
) {
    val effectiveTransitionMillis: Int get() = if (transitionEffect == SlideEffect.NONE) 0 else minOf(transitionMillis, intervalSeconds * 500)
    val effectiveFocusMillis: Int get() = minOf(focusDurationSeconds, intervalSeconds) * 1000
}

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
    val ai: AiSettings = AiSettings(),
) {
    fun photosFor(albumId: String): List<Photo> {
        val sourceIds = sources.filter { it.albumId == albumId && it.kind == SourceKind.PHOTO && it.available }.map { it.id }.toSet()
        return photos.filter { it.sourceId in sourceIds && it.trashedAt == null }.distinctBy { it.id }
    }
    fun playbackPhotosFor(album: Album): List<Photo> {
        val selected = album.playbackCategories.split(',').filter { it.isNotBlank() }.toSet()
        return photosFor(album.id).filter { photo -> selected.isEmpty() || selected.any { name -> PhotoCategory.entries.firstOrNull { it.name == name }?.includes(photo.metadata.category) == true } }
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
    suspend fun saveAiSettings(settings: AiSettings, apiKey: String?) {}
    suspend fun aiCommand(command: String, photoId: String? = null) {}
    suspend fun testAiConnection(): String = "AI 尚未配置"
    suspend fun setPhotoPresentation(sourceId: String, photoId: String, presentation: String?) {}
    suspend fun reanalyzePhoto(sourceId: String, photoId: String) {}
    suspend fun removePhoto(sourceId: String, photoId: String) {}
    suspend fun restorePhoto(sourceId: String, photoId: String) {}
    suspend fun markUnavailable(sourceId: String, photoId: String) {}
    fun setForeground(value: Boolean) {}
    fun setPlaying(value: Boolean) {}
    fun reportPlaybackFrame(millis: Long) {}
}
