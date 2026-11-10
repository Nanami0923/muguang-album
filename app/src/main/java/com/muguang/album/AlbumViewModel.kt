package com.muguang.album

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muguang.album.core.*
import com.muguang.album.playback.MusicController
import com.muguang.album.playback.ScheduleCalculator
import com.muguang.album.playback.compareNaturally
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class PauseReason { USER, LIFECYCLE, END }

data class PlayerSession(
    val token: Long,
    val album: Album,
    val photos: List<Photo>,
    val index: Int = 0,
    val paused: Boolean = false,
    val pauseReason: PauseReason? = null,
    val ready: Boolean = false,
    val occurrenceKey: String? = null,
    val motionCycle: Long = 0,
) {
    val photo: Photo get() = photos[index.coerceIn(photos.indices)]
}

class AlbumViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MuguangApplication
    val repository = app.repository
    val library = repository.state
    private val _selectedAlbumId = MutableStateFlow<String?>(null)
    val selectedAlbumId = _selectedAlbumId.asStateFlow()
    private val _session = MutableStateFlow<PlayerSession?>(null)
    val session = _session.asStateFlow()
    private val _displayArmed = MutableStateFlow(false)
    val displayArmed = _displayArmed.asStateFlow()
    private val _sleepRemaining = MutableStateFlow<Long?>(null)
    val sleepRemaining = _sleepRemaining.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    val music = MusicController(application)
    private val guards = application.getSharedPreferences("occurrence_guards", Context.MODE_PRIVATE)
    private var foreground = false
    private var sleepDeadline: Long? = null
    private var dwellDeadline: Long? = null
    private var dwellRemaining = 10_000L
    private var generation = 0L
    private val badPhotos = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            library.collect { state ->
                if (state.initialized && state.albums.none { it.id == _selectedAlbumId.value }) {
                    _selectedAlbumId.value = state.albums.firstOrNull()?.id
                }
                val current = _session.value
                if (current != null) {
                    val available = state.playbackPhotosFor(current.album).associateBy { it.id }
                    val latest = available[current.photo.id]
                    if (latest != null && latest != current.photo) {
                        _session.value = current.copy(photos = current.photos.map { available[it.id] ?: it })
                    }
                    if (latest == null && state.initialized) advance(1, automatic = false)
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                if (foreground && library.value.initialized) tick()
                delay(400)
            }
        }
    }

    fun message(text: String) { _messages.tryEmit(text) }
    fun selectAlbum(id: String) { _selectedAlbumId.value = id }
    fun launchOperation(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (error: CancellationException) { throw error }
        catch (error: Exception) { message(error.message ?: "操作未完成，请重试") }
    }

    fun createAlbum(name: String) = launchOperation {
        if (name.isBlank()) return@launchOperation
        _selectedAlbumId.value = repository.createAlbum(name.trim())
    }
    fun deleteAlbum(id: String) = launchOperation {
        if (_session.value?.album?.id == id) stopPlayback()
        repository.deleteAlbum(id)
    }
    fun updateAlbum(album: Album) = launchOperation { repository.updateAlbum(album) }
    fun addSource(albumId: String, uri: String, kind: SourceKind) = launchOperation {
        repository.addSource(albumId, uri, kind, recursive = kind == SourceKind.PHOTO)
    }
    fun scanSource(id: String) = launchOperation { repository.scan(id) }
    fun analysisCommand(id: String, command: String) = launchOperation { repository.setAnalysisState(id, command) }
    fun saveAiSettings(settings: AiSettings, key: String?) = launchOperation { repository.saveAiSettings(settings,key); message("AI 配置已保存") }
    fun aiCommand(command: String, photoId: String?) = launchOperation { repository.aiCommand(command,photoId) }
    fun testAiConnection() = launchOperation { message(repository.testAiConnection()) }
    fun setPhotoPresentation(photo:Photo,presentation:String?) = launchOperation { repository.setPhotoPresentation(photo.sourceId,photo.id,presentation) }
    fun savePhoto(photo: Photo, point: FocusPoint?, category: PhotoCategory?, motion: MotionMode?) = launchOperation {
        repository.savePhotoEdits(photo.sourceId, photo.id, point, category, motion)
    }
    fun removeSource(id: String) = launchOperation { repository.removeSource(id) }
    fun reanalyzePhoto(photo: Photo) = launchOperation {
        repository.reanalyzePhoto(photo.sourceId, photo.id)
        message("已加入重新识别队列，手动调整会保留")
    }
    fun removePhoto(photo: Photo) = launchOperation {
        repository.removePhoto(photo.sourceId, photo.id)
        message("已从当前相册移除，原图保留，可在回收站恢复")
    }
    fun restorePhoto(photo: Photo) = launchOperation {
        repository.restorePhoto(photo.sourceId, photo.id)
        message("图片已恢复到相册")
    }
    fun updateSource(id: String, recursive: Boolean) = launchOperation {
        repository.updateSource(id, recursive)
    }
    fun updatePreferences(preferences: AppPreferences) = launchOperation { repository.updatePreferences(preferences) }

    fun saveSchedule(schedule: PlaySchedule) = launchOperation {
        ScheduleCalculator.validate(schedule)?.let { message(it); return@launchOperation }
        if (library.value.albums.none { it.id == schedule.albumId }) {
            message("请先选择相册"); return@launchOperation
        }
        ScheduleCalculator.conflict(schedule, library.value.schedules)?.let {
            message("与“${it.name.ifBlank { "播放计划" }}”时段重叠，请调整时间")
            return@launchOperation
        }
        val old = library.value.schedules.find { it.id == schedule.id }
        repository.saveSchedule(schedule.copy(revision = (old?.revision ?: 0) + 1))
        if (_session.value?.occurrenceKey?.startsWith("${schedule.id}:") == true) stopPlayback(suppress = false)
    }
    fun deleteSchedule(id: String) = launchOperation { repository.deleteSchedule(id) }

    fun playAlbum(albumId: String) {
        val album = library.value.albums.find { it.id == albumId } ?: return
        start(album, null)
    }

    fun enterDisplay() {
        stopPlayback(suppress = false)
        _displayArmed.value = true
        if (foreground) tick()
    }

    private fun sortedPhotos(album: Album): List<Photo> {
        val comparator = Comparator<Photo> { a, b ->
            val order = if (album.sortOrder == SortOrder.MODIFIED) a.modifiedAt.compareTo(b.modifiedAt) else 0
            if (order != 0) order else compareNaturally(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }
        return library.value.playbackPhotosFor(album).sortedWith(comparator)
    }

    private fun start(album: Album, occurrenceKey: String?) {
        val photos = sortedPhotos(album)
        if (photos.isEmpty()) {
            message("“${album.name}”没有可读取的图片，请添加文件夹或刷新")
            return
        }
        music.stop()
        badPhotos.clear()
        sleepDeadline = null
        _sleepRemaining.value = null
        dwellDeadline = null
        dwellRemaining = album.intervalSeconds * 1000L
        _session.value = PlayerSession(++generation, album, if (album.shuffle) photos.shuffled() else photos, occurrenceKey = occurrenceKey)
        repository.setPlaying(true)
        if (album.musicEnabled) music.start(album, library.value.tracksFor(album))
    }

    fun imageReady(token: Long, photoId: String) {
        val current = _session.value ?: return
        if (current.token != token || current.photo.id != photoId || current.ready) return
        _session.value = current.copy(ready = true)
        if (!current.paused) dwellDeadline = SystemClock.elapsedRealtime() + dwellRemaining + current.album.effectiveTransitionMillis
    }

    fun imageFailed(token: Long, photoId: String) {
        val current = _session.value ?: return
        if (current.token != token || current.photo.id != photoId) return
        badPhotos += photoId
        launchOperation { repository.markUnavailable(current.photo.sourceId, photoId) }
        if (current.photos.all { it.id in badPhotos }) {
            message("此相册的图片暂时都无法读取，请检查文件夹授权或存储设备")
            stopPlayback()
        } else advance(1, automatic = true)
    }

    fun changePhoto(direction: Int) = advance(direction, automatic = false)

    private fun advance(direction: Int, automatic: Boolean) {
        val current = _session.value ?: return
        if (automatic && current.paused && current.ready) return
        var photos = current.photos
        var index = current.index
        repeat(photos.size + 1) {
            index += direction
            if (index >= photos.size) {
                if (!current.album.loop && automatic && current.ready) {
                    pause(PauseReason.END); return
                }
                val refreshed = sortedPhotos(current.album).filterNot { it.id in badPhotos }
                if (refreshed.isEmpty()) { stopPlayback(); return }
                photos = if (current.album.shuffle) refreshed.shuffled().let { list ->
                    if (list.size > 1 && list.first().id == current.photo.id) list.drop(1) + list.first() else list
                } else refreshed
                index = 0
            }
            if (index < 0) index = photos.lastIndex
            if (photos[index].id !in badPhotos && library.value.playbackPhotosFor(current.album).any { it.id == photos[index].id }) {
                dwellRemaining = current.album.intervalSeconds * 1000L
                dwellDeadline = null
                // A single-image album keeps its loaded frame without another transition.
                val sameImage = photos[index].id == current.photo.id
                _session.value = current.copy(token = if (sameImage) current.token else ++generation, photos = photos, index = index, ready = sameImage && current.ready,
                    motionCycle = current.motionCycle + 1)
                if (sameImage && current.ready && !current.paused) dwellDeadline = SystemClock.elapsedRealtime() + dwellRemaining
                return
            }
        }
        stopPlayback()
    }

    fun togglePause() {
        val current = _session.value ?: return
        if (current.paused) {
            current.occurrenceKey?.let { guards.edit().remove(it).apply() }
            if (current.pauseReason == PauseReason.END) {
                dwellRemaining = current.album.intervalSeconds * 1000L
                _session.value = current.copy(token = ++generation, index = 0, ready = false, paused = false, pauseReason = null)
            } else {
                _session.value = current.copy(paused = false, pauseReason = null)
                if (current.ready) dwellDeadline = SystemClock.elapsedRealtime() + dwellRemaining
            }
            if (foreground) music.resumeForSession()
        } else {
            pause(PauseReason.USER)
            current.occurrenceKey?.let { rememberSuppression(it) }
        }
    }

    private fun pause(reason: PauseReason) {
        val current = _session.value ?: return
        if (current.paused) return
        dwellDeadline?.let { dwellRemaining = (it - SystemClock.elapsedRealtime()).coerceAtLeast(0) }
        dwellDeadline = null
        _session.value = current.copy(paused = true, pauseReason = reason)
        music.pauseForSession()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        repository.setForeground(value)
        if (!value) {
            pause(PauseReason.LIFECYCLE)
            music.pauseForSession()
        } else {
            tick()
            val current = _session.value
            if (current?.pauseReason == PauseReason.LIFECYCLE) {
                _session.value = current.copy(paused = false, pauseReason = null)
                if (current.ready) dwellDeadline = SystemClock.elapsedRealtime() + dwellRemaining
                music.resumeForSession()
            }
        }
    }

    fun setSleepMinutes(minutes: Int?) {
        sleepDeadline = minutes?.coerceIn(1, 1440)?.let { SystemClock.elapsedRealtime() + it * 60_000L }
        _sleepRemaining.value = minutes?.times(60L)
    }

    fun toggleMusic() {
        if (_session.value?.paused == true) { message("请先继续幻灯片，再恢复音乐"); return }
        music.toggleUserPause()
    }

    private fun rememberSuppression(key: String) {
        val occurrence = ScheduleCalculator.current(library.value.schedules)
        if (occurrence?.key == key) guards.edit().putLong(key, occurrence.end.toEpochMilli()).apply()
    }

    fun stopPlayback(suppress: Boolean = true, leaveDisplay: Boolean = false) {
        if (suppress) {
            val key = _session.value?.occurrenceKey ?: if (_displayArmed.value) ScheduleCalculator.current(library.value.schedules)?.key else null
            key?.let { rememberSuppression(it) }
        }
        ++generation
        _session.value = null
        repository.setPlaying(false)
        dwellDeadline = null
        sleepDeadline = null
        _sleepRemaining.value = null
        music.stop()
        if (leaveDisplay) _displayArmed.value = false
    }

    private fun tick() {
        val nowElapsed = SystemClock.elapsedRealtime()
        sleepDeadline?.let { deadline ->
            if (deadline <= nowElapsed) { stopPlayback(); message("停止倒计时已到，图片与音乐已停止"); return }
            _sleepRemaining.value = ((deadline - nowElapsed + 999) / 1000).coerceAtLeast(0)
        }
        val now = Instant.now()
        if (guards.all.size > 32) {
            val edit = guards.edit()
            guards.all.forEach { (key, expires) -> if ((expires as? Long ?: 0) <= now.toEpochMilli()) edit.remove(key) }
            edit.apply()
        }
        var current = _session.value
        if (current?.occurrenceKey != null) {
            val occurrence = ScheduleCalculator.current(library.value.schedules, now)
            if (occurrence?.key != current.occurrenceKey) {
                stopPlayback(suppress = false)
                current = null
            }
        }
        if (_displayArmed.value && current == null) {
            val occurrence = ScheduleCalculator.current(library.value.schedules, now)
            if (occurrence != null && guards.getLong(occurrence.key, 0) <= now.toEpochMilli()) {
                val album = library.value.albums.find { it.id == occurrence.albumId }
                if (album != null && library.value.photosFor(album.id).isNotEmpty()) start(album, occurrence.key)
                else guards.edit().putLong(occurrence.key, occurrence.end.toEpochMilli()).apply()
            }
        }
        current = _session.value
        if (current != null && current.ready && !current.paused && (dwellDeadline ?: Long.MAX_VALUE) <= nowElapsed) advance(1, automatic = true)
    }

    override fun onCleared() {
        music.release()
        super.onCleared()
    }
}
