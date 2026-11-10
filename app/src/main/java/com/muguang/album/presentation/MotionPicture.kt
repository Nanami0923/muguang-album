package com.muguang.album.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.muguang.album.core.*
import com.muguang.album.playback.SmartMotion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.muguang.album.analysis.decodeOriented

@Composable
fun MotionPicture(painter: Painter, photo: Photo, album: Album, paused: Boolean, token: Any, modifier: Modifier = Modifier, onFrame: (Long) -> Unit = {}, initialProgress: Float = 0f, onProgress: (Float) -> Unit = {}) {
    val context = LocalContext.current
    var blurred by remember(photo.uri, photo.modifiedAt) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(photo.uri, photo.modifiedAt, album.blurBackground) {
        if (!album.blurBackground) { blurred = null; return@LaunchedEffect }
        blurred = withContext(Dispatchers.IO) {
            try {
                val bitmap = decodeOriented(context, photo.uri, 64).bitmap
                val w = bitmap.width; val h = bitmap.height
                val pixels = IntArray(w*h); bitmap.getPixels(pixels,0,w,0,0,w,h)
                val output = IntArray(pixels.size)
                for (y in 0 until h) for (x in 0 until w) {
                    var r=0; var g=0; var b=0; var count=0
                    for (dy in -4..4) for (dx in -4..4) {
                        val p = pixels[(y+dy).coerceIn(0,h-1)*w+(x+dx).coerceIn(0,w-1)]
                        r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; count++
                    }
                    output[y*w+x] = (255 shl 24) or ((r/count) shl 16) or ((g/count) shl 8) or (b/count)
                }
                bitmap.recycle()
                android.graphics.Bitmap.createBitmap(output,w,h,android.graphics.Bitmap.Config.ARGB_8888)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
    DisposableEffect(blurred) { val bitmap = blurred; onDispose { bitmap?.recycle() } }
    var progress by remember(token, photo.metadata.effectiveFocus, album.motionMode) { mutableFloatStateOf(initialProgress) }
    val requested = photo.metadata.customMotionMode?.let { runCatching { MotionMode.valueOf(it) }.getOrNull() } ?: album.motionMode
    val mode = remember(token, requested) { if (requested == MotionMode.MIXED) listOf(MotionMode.FIT_ZOOM, MotionMode.CROP_ZOOM).random() else requested }
    LaunchedEffect(token, paused, photo.metadata.effectiveFocus, mode, album.effectiveFocusMillis) {
        if (paused) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (isActive && progress < 1f) {
            withFrameNanos { now ->
                onFrame((now - previous)/1_000_000)
                progress = com.muguang.album.playback.focusProgress(progress, (now - previous) / 1_000_000f, album.effectiveFocusMillis)
                onProgress(progress)
                previous = now
            }
        }
    }
    val background = blurred?.let { remember(it) { BitmapPainter(it.asImageBitmap()) } }
    Canvas(modifier.fillMaxSize().clipToBounds().background(Color(0xFF08090B))) {
        background?.let { bg ->
            val s = maxOf(size.width / bg.intrinsicSize.width, size.height / bg.intrinsicSize.height)
            withTransform({ translate((size.width-bg.intrinsicSize.width*s)/2,(size.height-bg.intrinsicSize.height*s)/2); scale(s,s,Offset.Zero) }) {
                with(bg) { draw(intrinsicSize) }
            }
            drawRect(Color.Black.copy(alpha=.45f))
        }
        val intrinsic = painter.intrinsicSize
        if (!intrinsic.width.isFinite() || !intrinsic.height.isFinite() || intrinsic.width <= 0 || intrinsic.height <= 0) return@Canvas
        val path = SmartMotion.plan(intrinsic.width, intrinsic.height, size.width, size.height,
            if (mode == MotionMode.OFF || photo.metadata.wholeView) MotionMode.FIT_ZOOM else mode, photo.metadata.effectiveFocus,
            if (photo.metadata.manualFocus != null) null else photo.metadata.safeRegion,
            if (mode == MotionMode.OFF || photo.metadata.wholeView) 0f else album.motionStrength, album.maxCrop)
        val frame = path.frame(progress * progress * (3f - 2f * progress))
        withTransform({ translate(frame.x, frame.y); scale(frame.scale, frame.scale, Offset.Zero) }) {
            with(painter) { draw(Size(intrinsic.width, intrinsic.height)) }
        }
    }
}
