package com.muguang.album

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muguang.album.core.SourceKind
import com.muguang.album.presentation.PlayerScreen
import com.muguang.album.ui.LibraryActions
import com.muguang.album.ui.MainLibraryScreen
import com.muguang.album.ui.MuguangTheme

class MainActivity : ComponentActivity() {
    private val model: AlbumViewModel by viewModels()
    private var pendingAlbumId: String? = null
    private var pendingSourceKind = SourceKind.PHOTO
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) model.setForeground(false)
            else if (intent.action == Intent.ACTION_USER_PRESENT && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                model.setForeground(true)
            }
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.message("通知未开启，前台播放计划仍可使用")
    }
    private val directoryPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val albumId = pendingAlbumId ?: return@registerForActivityResult
        try {
            val flags = (result.data?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (flags == 0) error("此文件夹未提供读取授权，请重新选择")
            contentResolver.takePersistableUriPermission(uri, flags)
            model.addSource(albumId, uri.toString(), pendingSourceKind)
        } catch (error: Exception) { model.message(error.message ?: "未能保留文件夹授权，请重新选择") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAlbumId = savedInstanceState?.getString("pendingAlbumId")
        pendingSourceKind = savedInstanceState?.getString("pendingKind")?.let { runCatching { SourceKind.valueOf(it) }.getOrNull() } ?: SourceKind.PHOTO
        enableEdgeToEdge()
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (intent.getBooleanExtra("enterDisplay", false)) model.enterDisplay()
        setContent {
            val library by model.library.collectAsStateWithLifecycle()
            val albumId by model.selectedAlbumId.collectAsStateWithLifecycle()
            val session by model.session.collectAsStateWithLifecycle()
            val armed by model.displayArmed.collectAsStateWithLifecycle()
            val music by model.music.state.collectAsStateWithLifecycle()
            val sleep by model.sleepRemaining.collectAsStateWithLifecycle()
            val snackbars = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { model.messages.collect { snackbars.showSnackbar(it) } }
            val inPlayer = session != null || armed
            BackHandler(inPlayer) { model.stopPlayback(leaveDisplay = true) }
            DisposableEffect(inPlayer) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (inPlayer) {
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else controller.show(WindowInsetsCompat.Type.systemBars())
                onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
            }
            DisposableEffect(session != null, armed, library.preferences.keepStandbyAwake, library.preferences.standbyBrightness) {
                val keepAwake = session != null || (armed && library.preferences.keepStandbyAwake)
                if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.attributes = window.attributes.apply {
                    screenBrightness = if (session == null && armed && library.preferences.keepStandbyAwake) library.preferences.standbyBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                }
            }
            MuguangTheme(library.preferences.dynamicColor) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        if (inPlayer) PlayerScreen(
                            session = session, library = library, music = music, sleepRemaining = sleep,
                            onExit = { model.stopPlayback(leaveDisplay = true) },
                            onStop = { model.stopPlayback() }, onPause = model::togglePause,
                            onPrevious = { model.changePhoto(-1) }, onNext = { model.changePhoto(1) },
                            onReady = model::imageReady, onImageError = model::imageFailed,
                            onSleep = model::setSleepMinutes, onMusicPause = model::toggleMusic,
                            onMusicPrevious = { model.music.previous() }, onMusicNext = { model.music.next() },
                            onMusicVolume = { model.music.setVolume(it) },
                            onSavePhoto = { photo, point, category, motion -> model.savePhoto(photo, point, category, motion) },
                            onFrame = model.repository::reportPlaybackFrame,
                        ) else MainLibraryScreen(library, albumId, LibraryActions(
                            onSelectAlbum = model::selectAlbum,
                            onCreateAlbum = { model.createAlbum(it) },
                            onDeleteAlbum = { model.deleteAlbum(it) },
                            onPickPhotos = { pickDirectory(it, SourceKind.PHOTO) },
                            onPickMusic = { pickDirectory(it, SourceKind.MUSIC) },
                            onUpdateAlbum = { model.updateAlbum(it) },
                            onRefreshSource = { model.scanSource(it) },
                            onRemoveSource = { model.removeSource(it) },
                            onUpdateSource = { id, recursive -> model.updateSource(id, recursive) },
                            onPlay = model::playAlbum, onEnterDisplay = model::enterDisplay,
                            onSaveSchedule = { model.saveSchedule(it) },
                            onDeleteSchedule = { model.deleteSchedule(it) },
                            onUpdatePreferences = {
                                if (it.remindersEnabled && !library.preferences.remindersEnabled && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                model.updatePreferences(it)
                            }, onMessage = model::message,
                            onSavePhoto = { photo, point, category, motion -> model.savePhoto(photo, point, category, motion) },
                            onAnalysisCommand = { id, command -> model.analysisCommand(id, command) },
                        ))
                        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
                    }
                }
            }
        }
    }

    private fun pickDirectory(albumId: String, kind: SourceKind) {
        pendingAlbumId = albumId
        pendingSourceKind = kind
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
        try { directoryPicker.launch(intent) }
        catch (_: Exception) { model.message("设备未提供文件夹选择器，请确认系统文件管理可用") }
    }

    override fun onStart() {
        super.onStart()
        model.setForeground(!(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingAlbumId", pendingAlbumId)
        outState.putString("pendingKind", pendingSourceKind.name)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("enterDisplay", false)) model.enterDisplay()
    }
}
