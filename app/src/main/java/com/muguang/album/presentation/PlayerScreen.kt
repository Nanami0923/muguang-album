package com.muguang.album.presentation

import android.app.ActivityManager
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.muguang.album.PlayerSession
import com.muguang.album.core.LibraryState
import com.muguang.album.core.PhotoScale
import com.muguang.album.core.*
import com.muguang.album.ui.FocusEditor
import com.muguang.album.playback.MusicState
import com.muguang.album.playback.ScheduleCalculator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    session: PlayerSession?, library: LibraryState, music: MusicState, sleepRemaining: Long?,
    onExit: () -> Unit, onStop: () -> Unit, onPause: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit,
    onReady: (Long, String) -> Unit, onImageError: (Long, String) -> Unit,
    onSleep: (Int?) -> Unit, onMusicPause: () -> Unit,
    onMusicPrevious: () -> Unit, onMusicNext: () -> Unit, onMusicVolume: (Float) -> Unit,
    onSavePhoto: (Photo, FocusPoint?, PhotoCategory?, MotionMode?) -> Unit = { _, _, _, _ -> },
    onFrame: (Long) -> Unit = {},
    onPresentation: (Photo, String?) -> Unit = { _, _ -> },
    onAiRecognize: (String) -> Unit = {},
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var resumeAfterEdit by rememberSaveable { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var sleepDialog by remember { mutableStateOf(false) }
    var musicDialog by remember { mutableStateOf(false) }
    var interaction by remember { mutableLongStateOf(0) }
    var now by remember { mutableStateOf(Instant.now()) }
    val context = LocalContext.current
    val accessible = (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus(); while (true) { now = Instant.now(); delay(1000) } }
    LaunchedEffect(controls, interaction, session?.paused, sleepDialog, musicDialog, accessible) {
        if (controls && session != null && !session.paused && !sleepDialog && !musicDialog && !accessible) { delay(4000); controls = false }
    }
    fun interact() { controls = true; interaction++ }
    var drag by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().background(Color(0xFF08090B)).focusRequester(focus).onKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp) when (event.key) {
            Key.DirectionRight -> { onNext(); interact(); true }
            Key.DirectionLeft -> { onPrevious(); interact(); true }
            Key.Spacebar -> { onPause(); interact(); true }
            else -> false
        } else false
    }.focusable().pointerInput(session?.token) {
        detectTapGestures(onTap = { controls = !controls; interaction++ })
    }.pointerInput(session?.token) {
        detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
            if (drag > 100) onPrevious() else if (drag < -100) onNext()
            interact()
        }) { change, amount -> change.consume(); drag += amount }
    }) {
        if (session != null) PhotoCanvas(session, onReady, onImageError, onFrame)
        else {
            val next = library.schedules.filter { it.enabled }.mapNotNull { ScheduleCalculator.upcoming(it, now, count = 1).firstOrNull() }.minByOrNull { it.start }
            Column(Modifier.align(Alignment.Center).padding(32.dp).widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Icon(Icons.Rounded.NightsStay, null, tint = Color(0xFF8C849A), modifier = Modifier.size(40.dp))
                Text(now.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 72.sp, color = Color(0xFFB0A9BB), fontWeight = FontWeight.Light)
                Text("等待下个播放时段", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFB0A9BB))
                Text(if (next != null) "下次开始  ${next.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}" else "还没有启用的计划，可返回播放设置的定时计划添加", color = Color(0xFF8C849A), textAlign = TextAlign.Center)
                Text(if (library.preferences.keepStandbyAwake) "常驻展示 · 屏幕仍保持开启 · 配乐已停止" else "省电等待 · 允许系统正常熄屏", style = MaterialTheme.typography.bodySmall, color = Color(0xFF756F80), textAlign = TextAlign.Center)
                OutlinedButton(onClick = onExit) { Text("返回相册") }
            }
        }
        if (library.preferences.showClock && session != null && !controls) Text(now.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), Modifier.align(Alignment.TopEnd).padding(28.dp), color = Color.White.copy(.7f), fontSize = 26.sp)
        if ((controls || session?.paused == true) && session != null) {
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(.7f), Color.Transparent))).statusBarsPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "退出播放", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(session.album.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(if (session.occurrenceKey == null) "手动播放" else "跟随播放计划", color = Color.White.copy(.6f), style = MaterialTheme.typography.labelMedium)
                    }
                    Text("${session.index + 1} / ${session.photos.size}", color = Color.White.copy(.8f))
                    IconButton(onClick = { resumeAfterEdit = !session.paused; if (!session.paused) onPause(); editing = true }) { Icon(Icons.Rounded.MyLocation, "设置当前照片焦点", tint = Color.White) }
                    IconButton(onClick = onStop) { Icon(Icons.Rounded.Stop, "停止当前会话", tint = Color.White) }
                }
            }
            Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp).widthIn(max = 720.dp), color = Color(0xF2232029), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(session.photo.name, Modifier.fillMaxWidth(), color = Color.White.copy(.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onPrevious(); interact() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "上一张", tint = Color.White) }
                        FilledIconButton(onClick = { onPause(); interact() }, Modifier.size(64.dp), shape = CircleShape) { Icon(if (session.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (session.paused) "继续幻灯片" else "暂停幻灯片", modifier = Modifier.size(32.dp)) }
                        IconButton(onClick = { onNext(); interact() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "下一张", tint = Color.White) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { musicDialog = true; interact() }) {
                            Icon(if (music.isPlaying) Icons.Rounded.MusicNote else Icons.Rounded.MusicOff, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text(if (music.enabled) "背景音乐" else "未启用配乐")
                        }
                        TextButton(onClick = { sleepDialog = true; interact() }) {
                            Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(sleepRemaining?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')} 后停止" } ?: "停止倒计时")
                        }
                    }
                }
            }
        }
    }
    if (editing && session != null) {
        fun closeEditor() { editing = false; if (resumeAfterEdit && session.paused) onPause(); resumeAfterEdit = false }
        FocusEditor(session.photo, session.album, ::closeEditor, onPresentation={onPresentation(session.photo,it)}, onAiRecognize={onAiRecognize(session.photo.id)}) { point, category, motion ->
            onSavePhoto(session.photo, point, category, motion); closeEditor()
        }
    }
    if (sleepDialog) SleepDialog(sleepRemaining, onDismiss = { sleepDialog = false }, onSet = { onSleep(it); sleepDialog = false })
    if (musicDialog) AlertDialog(
        onDismissRequest = { musicDialog = false },
        title = { Text("背景音乐") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(music.currentName.ifBlank { "可返回相册，在“背景音乐”中选择音乐文件夹并开启" })
                music.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                music.blockingReason?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                if (music.enabled) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        IconButton(onClick = onMusicPrevious) { Icon(Icons.Rounded.SkipPrevious, "上一首") }
                        IconButton(onClick = onMusicPause) { Icon(if (music.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (music.isPlaying) "暂停音乐" else "继续音乐") }
                        IconButton(onClick = onMusicNext) { Icon(Icons.Rounded.SkipNext, "下一首") }
                    }
                    Text("配乐音量 ${(music.volume * 100).toInt()}%")
                    Slider(value = music.volume, onValueChange = onMusicVolume)
                }
            }
        }, confirmButton = { TextButton(onClick = { musicDialog = false }) { Text("完成") } },
    )
}

private class LoadedSlide(val painter: Painter, val session: PlayerSession) {
    var focusProgress = 0f
    var focusCycle = session.motionCycle
}

@Composable
private fun PhotoCanvas(session: PlayerSession, onReady: (Long, String) -> Unit, onError: (Long, String) -> Unit, onFrame: (Long) -> Unit) {
    var loaded by remember { mutableStateOf<LoadedSlide?>(null) }
    var current by remember { mutableStateOf<LoadedSlide?>(null) }
    var previous by remember { mutableStateOf<LoadedSlide?>(null) }
    var progress by remember { mutableFloatStateOf(1f) }
    var effect by remember { mutableStateOf(SlideEffect.FADE) }
    var transitioning by remember { mutableStateOf(false) }
    val cycle = remember { com.muguang.album.playback.SlideEffectCycle() }
    val latestOnFrame by rememberUpdatedState(onFrame)
    LaunchedEffect(session.token, loaded, session.paused) {
        val next = loaded
        if (next == null || next.session.token != session.token) {
            previous = null; progress = 1f; transitioning = false
            return@LaunchedEffect
        }
        if (current !== next) {
            previous = current
            effect = cycle.next(session.album.transitionEffect)
            progress = if (previous == null || session.album.effectiveTransitionMillis == 0) 1f else 0f
            current = next
            transitioning = progress < 1f
        }
        if (session.paused) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (progress < 1f) {
            withFrameNanos { now ->
                latestOnFrame((now - last) / 1_000_000)
                progress = (progress + (now - last) / (session.album.effectiveTransitionMillis.coerceAtLeast(1) * 1_000_000f)).coerceAtMost(1f)
                last = now
            }
        }
        previous = null; transitioning = false
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val context = LocalContext.current
        val memory = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val height = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val cap = if (memory <= 128) 1920 else 3072
        val factor = (cap.toFloat() / maxOf(width, height)).coerceAtMost(1f)
        val old = previous
        val active = current
        if (active != null) SlideTransition(effect, if (old == null) 1f else progress,
            previous = old?.let { frame -> { modifier ->
                key(frame.session.token) { SlidePicture(frame, frame.session.copy(motionCycle = frame.focusCycle), true, modifier) {} }
            } },
            current = { modifier ->
                key(active.session.token) {
                    val live = if (active.session.token == session.token) session else active.session
                    SlidePicture(active, live, session.paused || !session.ready || transitioning || active.session.token != session.token,
                        modifier, onFrame)
                }
            })
        else CircularProgressIndicator(Modifier.align(Alignment.Center))
        key(session.token, session.photo.uri, session.photo.modifiedAt, session.photo.fileSize) {
            LoadPhoto(session.photo.uri, (width * factor).toInt(), (height * factor).toInt(), onLoaded = { painter ->
                loaded = LoadedSlide(painter, session)
                onReady(session.token, session.photo.id)
            }, onError = { onError(session.token, session.photo.id) })
        }
    }
}

@Composable
private fun SlidePicture(frame: LoadedSlide, session: PlayerSession, paused: Boolean, modifier: Modifier, onFrame: (Long) -> Unit) {
    if (frame.focusCycle != session.motionCycle) { frame.focusCycle = session.motionCycle; frame.focusProgress = 0f }
    val photo = session.photo
    val mode = photo.metadata.customMotionMode ?: session.album.motionMode.name
    when {
        photo.metadata.wholeView -> Image(frame.painter, photo.name, modifier.background(Color(0xFF08090B)), contentScale = ContentScale.Fit)
        mode == MotionMode.OFF.name -> Image(frame.painter, photo.name, modifier.background(Color(0xFF08090B)),
            contentScale = if (session.album.scale == PhotoScale.CROP) ContentScale.Crop else ContentScale.Fit)
        else -> MotionPicture(frame.painter, photo, session.album, paused, session.token to session.motionCycle,
            modifier, onFrame, frame.focusProgress) { frame.focusProgress = it }
    }
}

@Composable
private fun LoadPhoto(uri: String, width: Int, height: Int, onLoaded: (Painter) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val request = remember(uri, width, height) { ImageRequest.Builder(context).data(uri).size(width.coerceAtLeast(1), height.coerceAtLeast(1)).build() }
    val painter = rememberAsyncImagePainter(request)
    val state by painter.state.collectAsState()
    LaunchedEffect(state) {
        when (val result = state) {
            is AsyncImagePainter.State.Success -> onLoaded(result.painter)
            is AsyncImagePainter.State.Error -> onError()
            else -> Unit
        }
    }
}

@Composable
private fun SleepDialog(remaining: Long?, onDismiss: () -> Unit, onSet: (Int?) -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("停止倒计时") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("到时一起停止图片与配乐。暂停幻灯片不会延长倒计时。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(15, 30, 60, 120).forEach { minutes -> TextButton(onClick = { onSet(minutes) }) { Text("${minutes}分") } }
            }
            OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(4) }, label = { Text("自定义分钟（1～1440）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        }
    }, confirmButton = {
        TextButton(onClick = { onSet(custom.toIntOrNull()) }, enabled = custom.toIntOrNull() in 1..1440) { Text("开始计时") }
    }, dismissButton = {
        TextButton(onClick = { if (remaining != null) onSet(null) else onDismiss() }) { Text(if (remaining != null) "取消倒计时" else "返回") }
    })
}
