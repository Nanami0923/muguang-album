package com.muguang.album.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.muguang.album.core.Album
import com.muguang.album.core.MusicOrder
import com.muguang.album.core.MusicTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MusicState(
    val enabled: Boolean = false,
    val isPlaying: Boolean = false,
    val userPaused: Boolean = false,
    val currentName: String = "",
    val currentTrackId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = .35f,
    val error: String? = null,
    val blockingReason: String? = null,
)

/**
 * One audio player per display session. No background service or network data source is added.
 * Methods may be called from any thread; all player operations are dispatched to the main looper.
 */
class MusicController(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = mutableState.asStateFlow()

    private var player: ExoPlayer? = null
    private var tracks: List<MusicTrack> = emptyList()
    private var queue: List<MusicTrack> = emptyList()
    private val failedTrackIds = mutableSetOf<String>()
    private val history = mutableListOf<String>()
    private var historyCursor = -1
    private var historyNavigationId: String? = null
    private var order = MusicOrder.SEQUENTIAL
    private var active = false
    private var released = false
    private var sessionPaused = false
    private var userPaused = false
    private var outputSuppressed = false
    private var suppressionReason: String? = null
    private var signature: String? = null
    private var generation = 0L
    private var recovering = false
    private var roundEnded = false

    private val progressTick = object : Runnable {
        override fun run() {
            if (!released && active) {
                publish()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && active && order == MusicOrder.RANDOM) {
                roundEnded = true
                if (canPlay()) scheduleNextRound()
            }
            publish()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            if (active && id != null) {
                if (historyNavigationId == id) historyNavigationId = null
                else if (history.getOrNull(historyCursor) != id) {
                    while (history.size > historyCursor + 1) history.removeAt(history.lastIndex)
                    history.add(id)
                    if (history.size > 100) history.removeAt(0)
                    historyCursor = history.lastIndex
                }
            }
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && active &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                outputSuppressed = true
                suppressionReason = "音频输出已断开，点击音乐继续"
            } else if (!playWhenReady && active &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                outputSuppressed = true
                suppressionReason = "音频焦点已转移，点击音乐继续"
            }
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!active || recovering) return
            val failedId = player?.currentMediaItem?.mediaId ?: return
            failedTrackIds += failedId
            recovering = true
            mutableState.value = mutableState.value.copy(error = "已跳过无法播放的音乐：${player?.currentMediaItem?.mediaMetadata?.title ?: "未知曲目"}")
            val token = generation
            handler.post {
                if (active && generation == token) recoverQueue(failedId)
                recovering = false
            }
        }
    }

    fun start(album: Album, tracks: List<MusicTrack>) = onMain {
        if (released) return@onMain
        if (!album.musicEnabled) {
            stopInternal()
            return@onMain
        }
        val unique = tracks.distinctBy { it.id }.sortedWith(naturalComparator { it.name })
        if (unique.isEmpty()) {
            stopInternal()
            mutableState.value = MusicState(enabled = true, volume = album.musicVolume.coerceIn(0f, 1f), error = "音乐目录为空或暂不可用，照片将继续播放")
            return@onMain
        }
        val newSignature = buildString {
            append(album.id).append('|').append(album.musicSourceId).append('|').append(album.musicOrder)
            unique.forEach { append('|').append(it.id).append('@').append(it.uri) }
        }
        if (active && newSignature == signature) {
            setVolumeInternal(album.musicVolume)
            return@onMain
        }
        stopInternal()
        active = true
        signature = newSignature
        this.tracks = unique
        order = album.musicOrder
        sessionPaused = false
        userPaused = false
        outputSuppressed = false
        suppressionReason = null
        mutableState.value = MusicState(enabled = true, volume = album.musicVolume.coerceIn(0f, 1f))
        val audio = ensurePlayer()
        audio.volume = mutableState.value.volume
        val firstQueue = if (order == MusicOrder.RANDOM) shuffledRound(unique) else unique
        loadQueue(firstQueue)
        handler.removeCallbacks(progressTick)
        handler.post(progressTick)
    }

    fun pauseForSession() = onMain {
        sessionPaused = true
        player?.pause()
        publish()
    }

    /** A user's own pause, disconnected output, and permanent focus loss survive session resume. */
    fun resumeForSession() = onMain {
        if (!active || released) return@onMain
        sessionPaused = false
        if (canPlay()) {
            if (roundEnded) scheduleNextRound() else player?.play()
        }
        publish()
    }

    fun toggleUserPause() = onMain {
        if (!active || released) return@onMain
        if (userPaused || outputSuppressed) {
            userPaused = false
            outputSuppressed = false
            suppressionReason = null
            mutableState.value = mutableState.value.copy(error = null)
            if (canPlay()) {
                if (roundEnded) scheduleNextRound() else player?.play()
            }
        } else {
            userPaused = true
            player?.pause()
        }
        publish()
    }

    fun next() = onMain {
        val audio = player ?: return@onMain
        if (!active) return@onMain
        if (historyCursor in 0 until history.lastIndex && seekHistory(historyCursor + 1)) return@onMain
        if (audio.hasNextMediaItem()) audio.seekToNextMediaItem()
        else if (order == MusicOrder.RANDOM) {
            roundEnded = true
            // Manual next should change the visible track even while paused.
            scheduleNextRound()
        } else audio.seekTo(0, 0)
        publish()
    }

    fun previous() = onMain {
        val audio = player ?: return@onMain
        if (!active) return@onMain
        if (audio.currentPosition > 3000) audio.seekTo(0)
        else if (historyCursor > 0 && seekHistory(historyCursor - 1)) return@onMain
        else if (audio.hasPreviousMediaItem()) audio.seekToPreviousMediaItem()
        else audio.seekTo(0)
        publish()
    }

    fun setVolume(volume: Float) = onMain { setVolumeInternal(volume) }

    fun stop() = onMain { stopInternal() }

    fun release() = onMain {
        stopInternal()
        released = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also {
            player = it
            it.addListener(listener)
        }

    private fun loadQueue(items: List<MusicTrack>, startIndex: Int = 0) {
        if (!active || items.isEmpty()) return
        val audio = ensurePlayer()
        roundEnded = false
        queue = items
        audio.shuffleModeEnabled = false
        audio.repeatMode = if (order == MusicOrder.SEQUENTIAL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        val media = items.map { track ->
            MediaItem.Builder().setMediaId(track.id).setUri(track.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.name).build()).build()
        }
        audio.setMediaItems(media, startIndex.coerceIn(0, media.lastIndex), 0)
        audio.prepare()
        audio.playWhenReady = canPlay()
        publish()
    }

    private fun scheduleNextRound() {
        if (!active || !roundEnded || recovering) return
        roundEnded = false
        val previousId = player?.currentMediaItem?.mediaId
        val token = generation
        handler.post {
            if (active && generation == token) {
                val valid = tracks.filterNot { it.id in failedTrackIds }
                if (valid.isNotEmpty()) {
                    val previous = valid.firstOrNull { it.id == previousId }
                    loadQueue(shuffledRound(valid, previous))
                } else stopWithError("所有音乐都无法播放，请检查文件或重新授权")
            }
        }
    }

    private fun recoverQueue(failedId: String) {
        val valid = tracks.filterNot { it.id in failedTrackIds }
        if (valid.isEmpty()) {
            stopWithError("所有音乐都无法播放，请检查文件或重新授权")
            return
        }
        val oldIndex = queue.indexOfFirst { it.id == failedId }.coerceAtLeast(0)
        val remainingQueue = queue.filterNot { it.id in failedTrackIds }
        if (oldIndex < remainingQueue.size) loadQueue(remainingQueue, oldIndex)
        else if (order == MusicOrder.RANDOM) loadQueue(shuffledRound(valid))
        else loadQueue(valid)
    }

    private fun seekHistory(index: Int): Boolean {
        val targetId = history.getOrNull(index) ?: return false
        val targetIndex = queue.indexOfFirst { it.id == targetId && it.id !in failedTrackIds }
        if (targetIndex < 0) return false
        historyCursor = index
        historyNavigationId = targetId
        player?.seekTo(targetIndex, 0)
        publish()
        return true
    }

    private fun setVolumeInternal(volume: Float) {
        val clamped = if (volume.isFinite()) volume.coerceIn(0f, 1f) else .35f
        player?.volume = clamped
        mutableState.value = mutableState.value.copy(volume = clamped)
    }

    private fun stopWithError(message: String) {
        val volume = mutableState.value.volume
        stopInternal()
        mutableState.value = MusicState(enabled = true, volume = volume, error = message)
    }

    private fun stopInternal() {
        generation++
        active = false
        sessionPaused = false
        userPaused = false
        outputSuppressed = false
        suppressionReason = null
        signature = null
        recovering = false
        roundEnded = false
        tracks = emptyList()
        queue = emptyList()
        failedTrackIds.clear()
        history.clear()
        historyCursor = -1
        historyNavigationId = null
        handler.removeCallbacks(progressTick)
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        mutableState.value = MusicState(volume = mutableState.value.volume)
    }

    private fun canPlay(): Boolean = active && !released && !sessionPaused && !userPaused && !outputSuppressed

    private fun publish() {
        if (released) return
        val audio = player
        mutableState.value = mutableState.value.copy(
            isPlaying = active && audio?.isPlaying == true,
            userPaused = userPaused,
            currentName = audio?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty(),
            currentTrackId = audio?.currentMediaItem?.mediaId,
            positionMs = audio?.currentPosition?.coerceAtLeast(0) ?: 0,
            durationMs = audio?.duration?.takeIf { it != C.TIME_UNSET && it >= 0 } ?: 0,
            blockingReason = suppressionReason,
        )
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block()
        else handler.post { block() }
    }
}
