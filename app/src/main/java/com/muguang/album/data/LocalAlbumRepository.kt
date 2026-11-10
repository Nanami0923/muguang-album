package com.muguang.album.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    private val database = Room.databaseBuilder(appContext, AlbumDatabase::class.java, "muguang-album.db").build()
    private val dao = database.dao()
    private val scanner = SafScanner(resolver)
    private val mutationMutex = Mutex()
    private val initializeMutex = Mutex()
    private val scanMutexes = ConcurrentHashMap<String, Mutex>()
    private val scanning = MutableStateFlow<Set<String>>(emptySet())
    private val initialized = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(LibraryState())
    override val state: StateFlow<LibraryState> = mutableState.asStateFlow()

    init {
        val storedLibrary = combine(
            dao.observeAlbums(), dao.observeSources(), dao.observePhotos(), dao.observeTracks(), dao.observeSchedules(),
        ) { albums, sources, photos, tracks, schedules ->
            LibraryState(
                albums = albums.map { it.model() },
                sources = sources.map { it.model() },
                photos = photos.map { Photo(it.id, it.sourceId, it.uri, it.name, it.modifiedAt) },
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
            )
        }
        scope.launch(Dispatchers.IO) {
            combine(storedLibrary, preferences, scanning, initialized) { library, prefs, scanningIds, ready ->
                library.copy(preferences = prefs, scanningSourceIds = scanningIds, initialized = ready)
            }.collect { mutableState.value = it }
        }
    }

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        initializeMutex.withLock {
            if (initialized.value) return@withLock
            mutationMutex.withLock {
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
        mutationMutex.withLock {
            check(dao.album(album.id) != null) { "此相册已不存在" }
            val musicSource = album.musicSourceId?.let { dao.source(it) }
            require(album.musicSourceId == null || (musicSource != null && musicSource.albumId == album.id && musicSource.kind == SourceKind.MUSIC.name)) {
                "音乐文件夹已失效，请重新选择"
            }
            require(!album.musicEnabled || musicSource != null) { "请先选择音乐文件夹" }
            val safeAlbum = album.copy(name = cleanName, transitionMillis = minOf(album.transitionMillis, album.intervalSeconds * 500))
            dao.updateAlbum(
                safeAlbum.id, safeAlbum.name, safeAlbum.intervalSeconds, safeAlbum.shuffle, safeAlbum.loop,
                safeAlbum.sortOrder.name, safeAlbum.scale.name, safeAlbum.transitionMillis,
                safeAlbum.musicSourceId, safeAlbum.musicEnabled, safeAlbum.musicOrder.name, safeAlbum.musicVolume,
            )
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
                            dao.deleteSourcePhotos(sourceId)
                            dao.deleteSourceTracks(sourceId)
                            for (chunk in result.photos.chunked(200)) {
                                currentCoroutineContext().ensureActive()
                                dao.insertPhotos(chunk)
                            }
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
                mutationMutex.withLock { dao.updateAvailability(sourceId, false, error) }
                throw IllegalStateException(error, failure)
            } finally {
                scanning.update { it - sourceId }
            }
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
)

private fun AlbumEntity.model() = Album(
    id, name, intervalSeconds, shuffle, loop, SortOrder.valueOf(sortOrder), PhotoScale.valueOf(scale), transitionMillis,
    musicSourceId, musicEnabled, MusicOrder.valueOf(musicOrder), musicVolume,
)

private fun SourceEntity.model() = FolderSource(
    id, albumId, uri, name, SourceKind.valueOf(kind), recursive, available, error, lastScanAt,
)

private fun PlaySchedule.entity() = ScheduleEntity(
    id, name, albumId, startMinute, endMinute, daysMask, enabled, revision,
)

private fun ScheduleEntity.model() = PlaySchedule(
    id, name, albumId, startMinute, endMinute, daysMask, enabled, revision,
)
