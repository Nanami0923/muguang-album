@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.muguang.album.ui

import android.graphics.Matrix
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.muguang.album.analysis.OrientedImage
import com.muguang.album.analysis.decodeOriented
import com.muguang.album.core.*
import com.muguang.album.presentation.MotionPicture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
fun FocusEditor(photo: Photo, album: Album, onDismiss: () -> Unit,
    onPresentation: (String?) -> Unit = {}, onAiRecognize: () -> Unit = {},
    onSave: (FocusPoint?, PhotoCategory?, MotionMode?) -> Unit) {
    val context = LocalContext.current
    var image by remember(photo.uri, photo.modifiedAt) { mutableStateOf<OrientedImage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pointX by rememberSaveable(photo.id) { mutableStateOf(photo.metadata.manualX) }
    var pointY by rememberSaveable(photo.id) { mutableStateOf(photo.metadata.manualY) }
    val point = pointX?.let { x -> pointY?.let { FocusPoint(x,it) } }
    var presentation by rememberSaveable(photo.id) { mutableStateOf(photo.metadata.manualPresentation) }
    var confirmAi by remember { mutableStateOf(false) }
    fun updatePoint(value: FocusPoint?) { pointX = value?.x; pointY = value?.y; if(value!=null) presentation=null }
    var category by rememberSaveable(photo.id) { mutableStateOf(photo.metadata.manualCategory?.let { PhotoCategory.valueOf(it) }) }
    var motion by rememberSaveable(photo.id) { mutableStateOf(photo.metadata.customMotionMode?.let { MotionMode.valueOf(it) }) }
    var preview by remember { mutableStateOf<MotionMode?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    val dirty = point != photo.metadata.manualFocus || category?.name != photo.metadata.manualCategory || motion?.name != photo.metadata.customMotionMode || presentation!=photo.metadata.manualPresentation
    fun close() { if (dirty) confirmExit = true else onDismiss() }
    LaunchedEffect(photo.uri, photo.modifiedAt) {
        try { image = withContext(Dispatchers.IO) { decodeOriented(context, photo.uri, 2048) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法读取照片" }
    }
    DisposableEffect(image) { val current = image; onDispose { current?.bitmap?.recycle() } }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, decorFitsSystemWindows = true)) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            // Bound the native window, not only the Compose content. Some OEMs
            // measure a WRAP_CONTENT dialog beyond its visible display frame.
            (dialogView.parent as? DialogWindowProvider)?.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            onDispose { }
        }
        BackHandler { close() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                var footerHeight by remember { mutableStateOf(72.dp) }
                val imageHeight = (maxHeight * .58f).coerceIn(120.dp, 560.dp)
                    .coerceAtMost((maxHeight - footerHeight - 32.dp).coerceAtLeast(80.dp))
                val current = image
                val displayedPoint by rememberUpdatedState(point ?: photo.metadata.autoFocus)
                val density = LocalDensity.current
                val dragRadius = with(density) { 28.dp.toPx() }
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .testTag("focus-editor-scroll").padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(photo.name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                        val sourceName = when (photo.metadata.focusSource) {
                            "FACE" -> "真人脸部"; "PERSON" -> "真人身体"; "ANIME_FACE" -> "动漫脸部"; "ANIME_PERSON" -> "动漫人物";
                            "LEARNED_SUBJECT" -> "主体区域"; "AI" -> "AI 判断"; "SALIENCY" -> "显著区域"; else -> "暂无局部焦点"
                        }
                        Text(photo.metadata.detailLabel, style = MaterialTheme.typography.titleSmall)
                        Text("${photo.metadata.displayStatus} · 焦点来源：$sourceName", style = MaterialTheme.typography.bodySmall)
                        var viewSize by remember { mutableStateOf(IntSize.Zero) }
                        if (current != null) {
                            val bitmap = remember(current) { current.bitmap.asImageBitmap() }
                            val scale = min(viewSize.width.toFloat()/bitmap.width, viewSize.height.toFloat()/bitmap.height)
                            val left = (viewSize.width - bitmap.width*scale)/2; val top = (viewSize.height - bitmap.height*scale)/2
                            val matrix = remember(viewSize, bitmap) { Matrix().apply { setScale(scale, scale); postTranslate(left, top) } }
                            fun setPoint(touch: Offset) {
                                val inverse = Matrix()
                                if (!matrix.invert(inverse)) return
                                val xy = floatArrayOf(touch.x, touch.y); inverse.mapPoints(xy)
                                if (xy[0] in 0f..bitmap.width.toFloat() && xy[1] in 0f..bitmap.height.toFloat()) updatePoint(FocusPoint(xy[0]/bitmap.width, xy[1]/bitmap.height))
                            }
                            Box(Modifier.height(imageHeight).fillMaxWidth().clipToBounds().background(Color.Black).onSizeChanged { viewSize = it }) {
                                if (preview != null) {
                                    val edited = photo.copy(metadata = photo.metadata.copy(manualX = point?.x, manualY = point?.y, customMotionMode = preview!!.name, manualPresentation=presentation))
                                    MotionPicture(BitmapPainter(bitmap), edited, album, false, preview!!)
                                } else Canvas(Modifier.fillMaxSize().testTag("focus-image")
                                    .pointerInput(matrix) { detectTapGestures { setPoint(it) } }
                                    .pointerInput(matrix, dragRadius) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val focus = displayedPoint
                                            val xy = focus?.let { floatArrayOf(it.x*bitmap.width, it.y*bitmap.height).also(matrix::mapPoints) }
                                            if (xy != null && (down.position - Offset(xy[0],xy[1])).getDistance() <= dragRadius) {
                                                val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                                if (start != null) {
                                                    setPoint(start.position)
                                                    drag(start.id) { change -> setPoint(change.position); change.consume() }
                                                }
                                            }
                                        }
                                    }) {
                                    withTransform({ translate(left, top); scale(scale, scale, Offset.Zero) }) { drawImage(bitmap) }
                                    (point ?: photo.metadata.autoFocus)?.let { p ->
                                        val xy = floatArrayOf(p.x*bitmap.width, p.y*bitmap.height); matrix.mapPoints(xy)
                                        val center = Offset(xy[0], xy[1]); val radius = 14.dp.toPx()
                                        drawCircle(Color.Black, radius/2, center, style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx()))
                                        drawCircle(Color.Yellow, radius/2, center, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                                        drawLine(Color.Yellow, center - Offset(radius, 0f), center + Offset(radius, 0f), 2.dp.toPx())
                                        drawLine(Color.Yellow, center - Offset(0f, radius), center + Offset(0f, radius), 2.dp.toPx())
                                    }
                                }
                            }
                        } else Box(Modifier.height(imageHeight).fillMaxWidth()) { Text(error ?: "正在加载完整构图…") }
                        Text(if (preview == null) "点击照片设置焦点，拖动准星微调；上下滑动查看设置。留白区域不会选中。" else "正在预览缩放，点击“返回选点”继续编辑。", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { preview = if (preview == MotionMode.FIT_ZOOM) null else MotionMode.FIT_ZOOM }) { Text(if (preview == MotionMode.FIT_ZOOM) "返回选点" else "预览 A") }
                            TextButton(onClick = { preview = if (preview == MotionMode.CROP_ZOOM) null else MotionMode.CROP_ZOOM }) { Text(if (preview == MotionMode.CROP_ZOOM) "返回选点" else "预览 B") }
                            TextButton(onClick = { updatePoint(null); presentation=null; preview = null }) { Text("恢复自动焦点") }
                            TextButton(onClick = { updatePoint(null); presentation="WHOLE_VIEW"; preview=null }) { Text(if(presentation=="WHOLE_VIEW") "已选整体展示" else "整体展示") }
                            TextButton(onClick = { confirmAi=true }) { Text("AI 重新识别") }
                        }
                        Text("分类", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(category == null, { category = null }, label = { Text("自动分类") })
                            PhotoCategory.entries.forEach { item -> FilterChip(category == item, { category = item }, label = { Text(item.title) }) }
                        }
                        Text("此照片的缩放方式", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(motion == null, { motion = null }, label = { Text("跟随相册") })
                            MotionMode.entries.forEach { item -> FilterChip(motion == item, { motion = item }, label = { Text(item.title) }) }
                        }
                        photo.metadata.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        photo.metadata.aiError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                        if(photo.metadata.disputeReasons.isNotBlank()) Text("本地待核对："+photo.metadata.disputeReasons.split(',').joinToString("、") {
                            when(it) { "MEDIUM_UNCERTAIN" -> "图片类型不明确"; "COMPOSITION_UNCERTAIN" -> "人物与景物主次不明确"; "FOCUS_UNSTABLE" -> "主体位置不稳定"; "NO_RELIABLE_SUBJECT" -> "暂无明确主体"; "LOCAL_COMPONENT_ERROR" -> "本地识别组件异常"; else -> "识别结果有分歧" }
                        },style=MaterialTheme.typography.bodySmall)
                    }
                    Surface(Modifier.onSizeChanged { footerHeight = with(density) { it.height.toDp() } }, tonalElevation = 3.dp, shadowElevation = 4.dp) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = ::close, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消") }
                            Button(onClick = { onPresentation(presentation); onSave(point, category, motion) }, enabled = current != null,
                                modifier = Modifier.weight(2f).heightIn(min = 48.dp).testTag("focus-save")) { Text("保存") }
                        }
                    }
                }
            }
        }
        if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("放弃未保存的修改？") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续编辑") } })
        if(confirmAi) AlertDialog(onDismissRequest={confirmAi=false},title={Text("重新调用 AI？")},text={Text("会使用已保存的 API 配置发送图片，可能产生费用；之前超时的请求也可能已计费。手动修改会保留，当前未保存草稿不会提交。")},
            confirmButton={TextButton({confirmAi=false;onAiRecognize()}) {Text("加入 AI 队列")}},dismissButton={TextButton({confirmAi=false}) {Text("取消")}})
    }
}
