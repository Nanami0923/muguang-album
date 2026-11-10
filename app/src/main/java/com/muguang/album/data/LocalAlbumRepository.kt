package com.muguang.album.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.withTransaction
import com.muguang.album.core.Album
import com.muguang.album.core.AlbumRepository
import com.muguang.album.core.AppPreferences
import com.muguang.album.core.FolderSource
import com.muguang.album.core.LibraryState
import com.muguang.album.core.MusicOrder
import com.muguang.album.core.MusicTrack
import com.muguang.album.core.Photo
import com.muguang.album.core.PhotoScale
import com.muguang.album.core.PlaySchedule
import com.muguang.album.core.SortOrder
import com.muguang.album.core.SourceKind
import com.muguang.album.core.FocusPoint
import com.muguang.album.core.PhotoCategory
import com.muguang.album.core.MotionMode
import com.muguang.album.core.PhotoMetadata
import com.muguang.album.analysis.OfflinePhotoAnalyzer
import com.muguang.album.playback.stableAutoFocus
import com.muguang.album.playback.TRASH_RETENTION_MILLIS
import kotlinx.coroutines.delay
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.albumPreferences by preferencesDataStore(name = "album_preferences")

/** The only writer of app metadata. Original files are never modified or deleted. */
class LocalAlbumRepository(context: Context, scope: CoroutineScope) : AlbumRepository {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val database = Room.databaseBuilder(appContext, AlbumDatabase::class.java, "muguang-album.db").addMigrations(SMART_MIGRATION).build()
    private val dao = database.dao()
    private val scanner = SafScanner(resolver)
    private val mutationMutex = Mutex()
    private val initializeMutex = Mutex()
    private val scanMutexes = ConcurrentHashMap<String, Mutex>()
    private val scanning = MutableStateFlow<Set<String>>(emptySet())
    private val initialized = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(LibraryState())
    override val state: StateFlow<LibraryState> = mutableState.asStateFlow()
    @Volatile private var foreground = false
    @Volatile private var playing = false
    @Volatile private var playbackBackoffUntil = 0L
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "photo-analysis")
    }.asCoroutineDispatcher()
    private val analyzer by lazy { OfflinePhotoAnalyzer(appContext) }

    override fun setForeground(value: Boolean) { foreground = value }
    override fun setPlaying(value: Boolean) { playing = value }
    override fun reportPlaybackFrame(millis: Long) {
        if (millis > 80) playbackBackoffUntil = android.os.SystemClock.elapsedRealtime() + 5000
    }

    init {
        val storedLibrary = combine(
            dao.observeAlbums(), dao.observeSources(), dao.observePhotos(), dao.observeTracks(), dao.observeSchedules(),
        ) { albums, sources, photos, tracks, schedules ->
            LibraryState(
                albums = albums.map { it.model() },
                sources = sources.map { it.model() },
                photos = photos.map { Photo(it.id, it.sourceId, it.uri, it.name, it.modifiedAt, it.fileSize, it.trashedAt, it.unavailableReason, it.metadata) },
                tracks = tracks.map { MusicTrack(it.id, it.sourceId, it.uri, it.name) },
                schedules = schedules.map { it.model() },
            )
        }
        val preferences = appContext.albumPreferences.data.catch { exception ->
            if (exception is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw exception
        }.map {
            AppPreferences(
                dynamicColor = it[DYNAMIC_COLOR] ?: true,
                keepStandbyAwake = it[KEEP_STANDBY_AWAKE] ?: true,
                showClock = it[SHOW_CLOCK] ?: false,
                standbyBrightness = (it[STANDBY_BRIGHTNESS] ?: .04f).coerceIn(.01f, 1f),
                remindersEnabled = it[REMINDERS_ENABLED] ?: false,
                galleryColumns = (it[GALLERY_COLUMNS] ?: 2).coerceIn(1, 2),
            )
        }
        scope.launch(Dispatchers.IO) {
            combine(storedLibrary, preferences, scanning, initialized) { library, prefs, scanningIds, ready ->
                library.copy(preferences = prefs, scanningSourceIds = scanningIds, initialized = ready)
            }.collect { mutableState.value = it }
        }
        scope.launch(Dispatchers.IO) {
            var lastPurge = 0L
            while (true) {
                if (!foreground || !initialized.value) { delay(500); continue }
                if (playing && android.os.SystemClock.elapsedRealtime() < playbackBackoffUntil) { delay(500); continue }
                if (android.os.Build.VERSION.SDK_INT >= 29 &&
                    (appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE) { delay(2000); continue }
                if (System.currentTimeMillis() - lastPurge > 60_000) {
                    mutationMutex.withLock { dao.purgeTrash(System.currentTimeMillis() - TRASH_RETENTION_MILLIS) }
                    lastPurge = System.currentTimeMillis()
                }
                val photo = mutationMutex.withLock {
                    dao.nextAnalysis()?.also { dao.insertPhotos(listOf(it.copy(metadata = it.metadata.copy(taskState = "PROCESSING")))) }
                }
                if (photo == null) { delay(700); continue }
                val cached = dao.photosById(photo.id).firstOrNull { it.sourceId != photo.sourceId && it.trashedAt == null &&
                    it.modifiedAt == photo.modifiedAt && it.fileSize == photo.fileSize && it.metadata.taskState == "DONE" && it.metadata.modelVersion == OfflinePhotoAnalyzer.VERSION }
                var missing = false
                val result = try { cached?.metadata ?: withContext(inferenceDispatcher) { analyzer.analyze(photo.uri) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    missing = error is SecurityException || error is java.io.FileNotFoundException
                    PhotoMetadata(taskState = "FAILED", focusStatus = "ERROR", classificationStatus = "ERROR", error = error.message?.take(200) ?: "识别失败")
                }
                catch (error: LinkageError) { PhotoMetadata(taskState = "FAILED", focusStatus = "ERROR", classificationStatus = "ERROR", error = "设备不支持当前离线识别组件") }
                mutationMutex.withLock {
                    val latest = dao.photo(photo.sourceId, photo.id)
                    if (latest != null && latest.trashedAt == null && latest.modifiedAt == photo.modifiedAt && latest.fileSize == photo.fileSize && latest.metadata.taskState == "PROCESSING") {
                        val manual = latest.metadata
                        val point = stableAutoFocus(manual.autoFocus, result.autoFocus, result.orientedWidth, result.orientedHeight)
                        val source = dao.source(photo.sourceId)
                        val merged = if (!foreground || source?.analysisState == "CANCELLED") manual.copy(taskState = if (source?.analysisState == "CANCELLED") "CANCELLED" else "QUEUED")
                        else result.copy(autoX = point?.x, autoY = point?.y, manualX = manual.manualX, manualY = manual.manualY,
                            manualCategory = manual.manualCategory, customMotionMode = manual.customMotionMode,
                            focusStatus = when { manual.manualFocus != null -> "MANUAL"; result.focusStatus != "MANUAL" -> result.focusStatus;
                                point != null -> "DETECTED"; else -> "NEEDS_MANUAL" })
                        dao.insertPhotos(listOf(latest.copy(metadata = merged, trashedAt = if (missing) System.currentTimeMillis() else latest.trashedAt,
                            unavailableReason = if (missing) "文件不存在或授权失效" else latest.unavailableReason)))
                    }
                }
                // Yield between pictures; playback never waits for model inference.
                delay(if (playing) 1500 else 100)
            }
        }
    }

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        initializeMutex.withLock {
            if (initialized.value) return@withLock
            mutationMutex.withLock {
                dao.purgeTrash(System.currentTimeMillis() - TRASH_RETENTION_MILLIS)
                dao.recoverAnalysis()
                for (source in dao.sources()) {
                    val stale = dao.sourcePhotos(source.id).filter { it.metadata.taskState == "DONE" && it.metadata.modelVersion != OfflinePhotoAnalyzer.VERSION }
                    stale.chunked(200).forEach { chunk -> dao.insertPhotos(chunk.map { it.copy(metadata = it.metadata.copy(taskState = "QUEUED")) }) }
                }
                if (dao.albums().isEmpty()) dao.insertAlbum(Album(DEFAULT_ALBUM_ID, "家庭相册").entity())
                for (source in dao.sources()) {
                    currentCoroutineContext().ensureActive()
                    try {
                        scanner.directoryName(Uri.parse(source.uri))
                        dao.updateAvailability(source.id, true, null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        dao.updateAvailability(source.id, false, readableError(failure))
                        if (failure is SecurityException || failure is java.io.FileNotFoundException) dao.trashSource(source.id, System.currentTimeMillis(), readableError(failure))
                    }
                }
            }
            initialized.value = true
        }
    }

    override suspend fun createAlbum(name: String): String = withContext(Dispatchers.IO) {
        val cleanName = validateName(name)
        val id = UUID.randomUUID().toString()
        mutationMutex.withLock { dao.insertAlbum(Album(id, cleanName).entity()) }
        id
    }

    override suspend fun updateAlbum(album: Album): Unit = withContext(Dispatchers.IO) {
        val cleanName = validateName(album.name)
        require(album.intervalSeconds in 1..3600) { "换片间隔应为 1～3600 秒" }
        require(album.musicVolume.isFinite() && album.musicVolume in 0f..1f) { "音乐音量应在 0～100% 之间" }
        require(album.transitionMillis in 0..2000) { "转场时长应为 0～2000 毫秒" }
        require(album.motionStrength.isFinite() && album.motionStrength in 0f..0.5f) { "动画强度无效" }
        require(album.maxCrop.isFinite() && album.maxCrop in 0f..0.85f) { "裁剪上限无效" }
        require(album.playbackCategories.split(',').filter { it.isNotBlank() }.all { name -> PhotoCategory.entries.any { it.name == name } }) { "播放类别无效" }
        mutationMutex.withLock {
            check(dao.album(album.id) != null) { "此相册已不存在" }
            val musicSource = album.musicSourceId?.let { dao.source(it) }
            require(album.musicSourceId == null || (musicSource != null && musicSource.albumId == album.id && musicSource.kind == SourceKind.MUSIC.name)) {
                "音乐文件夹已失效，请重新选择"
            }
            require(!album.musicEnabled || musicSource != null) { "请先选择音乐文件夹" }
            val safeAlbum = album.copy(name = cleanName, transitionMillis = minOf(album.transitionMillis, album.intervalSeconds * 500))
            database.withTransaction {
            dao.updateAlbum(
                safeAlbum.id, safeAlbum.name, safeAlbum.intervalSeconds, safeAlbum.shuffle, safeAlbum.loop,
                safeAlbum.sortOrder.name, safeAlbum.scale.name, safeAlbum.transitionMillis,
                safeAlbum.musicSourceId, safeAlbum.musicEnabled, safeAlbum.musicOrder.name, safeAlbum.musicVolume,
            )
            dao.smartSettings(album.id, album.motionMode.name, album.motionStrength, album.maxCrop, album.blurBackground, album.playbackCategories)
            }
        }
    }

    override suspend fun deleteAlbum(id: String): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            check(dao.album(id) != null) { "此相册已不存在" }
            val previousUris = dao.sources().filter { it.albumId == id }.map { it.uri }
            // FK cascades remove only metadata and associated schedules, not external documents.
            database.withTransaction {
                dao.deleteAlbum(id)
                if (dao.albums().isEmpty()) dao.insertAlbum(Album(DEFAULT_ALBUM_ID, "家庭相册").entity())
            }
            releaseUnusedGrants(previousUris)
        }
    }

    override suspend fun addSource(albumId: String, uri: String, kind: SourceKind, recursive: Boolean): String = withContext(Dispatchers.IO) {
        val tree = Uri.parse(uri)
        val displayName = try {
            scanner.directoryName(tree)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IllegalStateException(readableError(failure), failure)
        }
        val sourceId = mutationMutex.withLock {
            check(dao.album(albumId) != null) { "此相册已不存在" }
            val sources = dao.sources()
            val existing = sources.firstOrNull { it.albumId == albumId && it.uri == uri && it.kind == kind.name }
            val id = existing?.id ?: UUID.randomUUID().toString()
            val removedMusicUris = ArrayList<String>()
            database.withTransaction {
                if (kind == SourceKind.MUSIC) {
                    sources.filter { it.albumId == albumId && it.kind == SourceKind.MUSIC.name && it.id != id }.forEach {
                        removedMusicUris += it.uri
                        dao.deleteSource(it.id)
                    }
                }
                if (existing == null) {
                    dao.insertSource(SourceEntity(id, albumId, uri, displayName, kind.name, recursive, true, null, 0))
                } else {
                    dao.updateRecursive(id, recursive)
                    dao.updateAvailability(id, true, null)
                }
                if (kind == SourceKind.MUSIC) dao.bindMusic(albumId, id, true)
            }
            releaseUnusedGrants(removedMusicUris)
            id
        }
        scan(sourceId)
        sourceId
    }

    override suspend fun updateSource(sourceId: String, recursive: Boolean): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            check(dao.source(sourceId) != null) { "此文件夹来源已移除" }
            dao.updateRecursive(sourceId, recursive)
        }
        scan(sourceId)
    }

    override suspend fun removeSource(sourceId: String): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val source = dao.source(sourceId) ?: return@withLock
            database.withTransaction {
                val album = dao.album(source.albumId)
                if (album?.musicSourceId == sourceId) dao.bindMusic(source.albumId, null, false)
                dao.deleteSource(sourceId)
            }
            releaseUnusedGrants(listOf(source.uri))
        }
    }

    override suspend fun scan(sourceId: String): Unit = withContext(Dispatchers.IO) {
        val lock = scanMutexes.getOrPut(sourceId) { Mutex() }
        lock.withLock {
            val source = dao.source(sourceId)?.model() ?: return@withLock
            scanning.update { it + sourceId }
            try {
                val result = scanner.scan(source)
                currentCoroutineContext().ensureActive()
                mutationMutex.withLock {
                    val latest = dao.source(sourceId)
                    // A changed/removed source invalidates a result even if the provider finished late.
                    if (latest != null && latest.uri == source.uri && latest.recursive == source.recursive) {
                        database.withTransaction {
                            val updated = reconcilePhotos(dao.sourcePhotos(sourceId), result.photos, System.currentTimeMillis())
                            for (chunk in updated.chunked(200)) {
                                currentCoroutineContext().ensureActive()
                                dao.insertPhotos(chunk)
                            }
                            dao.deleteSourceTracks(sourceId)
                            for (chunk in result.tracks.chunked(200)) {
                                currentCoroutineContext().ensureActive()
                                dao.insertTracks(chunk)
                            }
                            dao.completeScan(sourceId, System.currentTimeMillis())
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // Cancellation retains the last successfully committed index and availability.
                throw cancelled
            } catch (failure: Exception) {
                val error = readableError(failure)
                mutationMutex.withLock {
                    dao.updateAvailability(sourceId, false, error)
                    if (failure is SecurityException || failure is java.io.FileNotFoundException) dao.trashSource(sourceId, System.currentTimeMillis(), error)
                }
                throw IllegalStateException(error, failure)
            } finally {
                scanning.update { it - sourceId }
            }
        }
    }

    override suspend fun savePhotoEdits(sourceId: String, photoId: String, focus: FocusPoint?, category: PhotoCategory?, motion: MotionMode?) = withContext(Dispatchers.IO) {
        require(focus == null || (focus.x.isFinite() && focus.y.isFinite() && focus.x in 0f..1f && focus.y in 0f..1f))
        mutationMutex.withLock {
            val old = checkNotNull(dao.photo(sourceId, photoId)) { "照片索引已不存在" }
            dao.insertPhotos(dao.photosById(old.id).map { shared -> shared.copy(metadata = shared.metadata.copy(manualX = focus?.x, manualY = focus?.y,
                manualCategory = category?.name, customMotionMode = motion?.name,
                focusStatus = when { focus != null -> "MANUAL"; shared.metadata.autoFocus != null -> "DETECTED";
                    shared.metadata.taskState == "FAILED" -> "ERROR"; shared.metadata.taskState in setOf("QUEUED", "PROCESSING") -> "PENDING"; else -> "NEEDS_MANUAL" })) })
        }
    }

    override suspend fun setAnalysisState(sourceId: String, command: String) = withContext(Dispatchers.IO) {
        if (command == "RESCAN") scan(sourceId)
        mutationMutex.withLock {
            val photos = dao.sourcePhotos(sourceId)
            val state = when (command) { "PAUSE" -> "PAUSED"; "CANCEL" -> "CANCELLED"; else -> "RUNNING" }
            dao.analysisState(sourceId, state)
            val updates = photos.filter { it.trashedAt == null }.mapNotNull { photo ->
                val task = photo.metadata.taskState
                val next = when {
                    command == "CANCEL" && task in setOf("QUEUED", "PROCESSING") -> "CANCELLED"
                    command == "RESCAN" || (command == "RETRY" && task == "FAILED") || (command == "RESUME" && task == "CANCELLED") -> "QUEUED"
                    else -> null
                }
                next?.let { photo.copy(metadata = photo.metadata.copy(taskState = it, error = null)) }
            }
            updates.chunked(200).forEach { dao.insertPhotos(it) }
        }
    }

    override suspend fun markUnavailable(sourceId: String, photoId: String): Unit = withContext(Dispatchers.IO) {
        val photo = dao.photo(sourceId, photoId) ?: return@withContext
        // Unsupported codecs/corrupt images remain technical errors, not missing files.
        try { resolver.openInputStream(Uri.parse(photo.uri))?.use { it.read() }; return@withContext }
        catch (_: SecurityException) { }
        catch (_: java.io.FileNotFoundException) { }
        catch (_: Exception) { return@withContext }
        mutationMutex.withLock {
            dao.photo(sourceId, photoId)?.let { dao.insertPhotos(listOf(it.copy(trashedAt = it.trashedAt ?: System.currentTimeMillis(), unavailableReason = "文件不存在或授权失效"))) }
        }
    }

    override suspend fun saveSchedule(schedule: PlaySchedule): Unit = withContext(Dispatchers.IO) {
        require(schedule.startMinute in 0..1439 && schedule.endMinute in 0..1439) { "计划时间无效" }
        require(schedule.startMinute != schedule.endMinute) { "开始和结束时间不能相同" }
        require(schedule.daysMask in 1..127) { "请至少选择一个重复星期" }
        mutationMutex.withLock {
            check(dao.album(schedule.albumId) != null) { "请选择有效的相册" }
            val allSchedules = dao.schedules()
            if (schedule.enabled) {
                val conflict = allSchedules.firstOrNull {
                    it.enabled && it.id != schedule.id && weeklyOverlap(schedule, it.model())
                }
                require(conflict == null) { "此时间与“${conflict?.name?.ifBlank { "播放计划" }}”重叠，请调整时间或重复星期" }
            }
            val previous = allSchedules.firstOrNull { it.id == schedule.id }
            val saved = schedule.copy(
                name = schedule.name.trim().ifBlank { "播放计划" }.take(50),
                revision = if (previous == null) maxOf(1, schedule.revision) else previous.revision + 1,
            )
            dao.saveSchedule(saved.entity())
        }
    }

    override suspend fun deleteSchedule(id: String): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock { dao.deleteSchedule(id) }
    }

    override suspend fun updatePreferences(preferences: AppPreferences) {
        require(preferences.standbyBrightness.isFinite()) { "待机亮度无效" }
        appContext.albumPreferences.edit {
            it[DYNAMIC_COLOR] = preferences.dynamicColor
            it[KEEP_STANDBY_AWAKE] = preferences.keepStandbyAwake
            it[SHOW_CLOCK] = preferences.showClock
            it[STANDBY_BRIGHTNESS] = preferences.standbyBrightness.coerceIn(.01f, 1f)
            it[REMINDERS_ENABLED] = preferences.remindersEnabled
            it[GALLERY_COLUMNS] = preferences.galleryColumns.coerceIn(1, 2)
        }
    }

    private suspend fun releaseUnusedGrants(uris: Collection<String>) {
        val referenced = dao.sources().map { it.uri }.toSet()
        for (uri in uris.distinct().filter { it !in referenced }) {
            try {
                val parsed = Uri.parse(uri)
                if (resolver.persistedUriPermissions.any { it.uri == parsed && it.isReadPermission }) {
                    resolver.releasePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: SecurityException) {
                // The user/provider may have revoked the grant already.
            }
        }
    }

    private fun validateName(name: String): String = name.trim().also {
        require(it.isNotEmpty()) { "请输入相册名称" }
        require(it.length <= 50) { "相册名称最多 50 个字符" }
    }

    private fun readableError(failure: Exception): String = when (failure) {
        is SecurityException -> "文件夹授权已失效，请重新选择并授权此文件夹"
        is java.io.FileNotFoundException -> "文件夹不存在或存储设备未连接，请检查后重试"
        else -> failure.message?.takeIf { it.isNotBlank() && it.any { character -> character.code in 0x4E00..0x9FFF } }
            ?: "暂时无法读取文件夹，已保留原有索引，请检查存储设备或重新授权"
    }

    companion object {
        private const val DEFAULT_ALBUM_ID = "family"
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEEP_STANDBY_AWAKE = booleanPreferencesKey("keep_standby_awake")
        private val SHOW_CLOCK = booleanPreferencesKey("show_clock")
        private val STANDBY_BRIGHTNESS = floatPreferencesKey("standby_brightness")
        private val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        private val GALLERY_COLUMNS = intPreferencesKey("gallery_columns")

        /** Monday is bit 0. Split wrap-around ranges before comparing half-open intervals. */
        internal fun weeklyOverlap(first: PlaySchedule, second: PlaySchedule): Boolean {
            val week = 7 * 1440
            fun ranges(schedule: PlaySchedule): List<Pair<Int, Int>> = buildList {
                for (day in 0..6) {
                    if (schedule.daysMask and (1 shl day) == 0) continue
                    val start = day * 1440 + schedule.startMinute
                    val end = day * 1440 + schedule.endMinute + if (schedule.endMinute < schedule.startMinute) 1440 else 0
                    if (end <= week) add(start to end) else {
                        add(start to week)
                        add(0 to end - week)
                    }
                }
            }
            return ranges(first).any { a -> ranges(second).any { b -> a.first < b.second && b.first < a.second } }
        }
    }
}

private fun Album.entity() = AlbumEntity(
    id, name, intervalSeconds, shuffle, loop, sortOrder.name, scale.name, transitionMillis,
    musicSourceId, musicEnabled, musicOrder.name, musicVolume,
    motionMode.name, motionStrength, maxCrop, blurBackground, playbackCategories,
)

private fun AlbumEntity.model() = Album(
    id, name, intervalSeconds, shuffle, loop, SortOrder.valueOf(sortOrder), PhotoScale.valueOf(scale), transitionMillis,
    musicSourceId, musicEnabled, MusicOrder.valueOf(musicOrder), musicVolume,
    MotionMode.valueOf(motionMode), motionStrength, maxCrop, blurBackground, playbackCategories,
)

private fun SourceEntity.model() = FolderSource(
    id, albumId, uri, name, SourceKind.valueOf(kind), recursive, available, error, lastScanAt, analysisState,
)

private fun PlaySchedule.entity() = ScheduleEntity(
    id, name, albumId, startMinute, endMinute, daysMask, enabled, revision,
)

private fun ScheduleEntity.model() = PlaySchedule(
    id, name, albumId, startMinute, endMinute, daysMask, enabled, revision,
)
