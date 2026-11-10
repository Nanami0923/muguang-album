package com.muguang.album.playback

import com.muguang.album.core.*
import kotlin.math.*

data class MotionFrame(val scale: Float, val x: Float, val y: Float)
data class MotionPath(val start: MotionFrame, val end: MotionFrame) {
    fun frame(progress: Float): MotionFrame {
        val t = progress.coerceIn(0f, 1f)
        return MotionFrame(start.scale + (end.scale - start.scale) * t, start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
    }
}

object SmartMotion {
    fun plan(iw: Float, ih: Float, vw: Float, vh: Float, mode: MotionMode, focus: FocusPoint?,
             region: SafeRegion?, strength: Float, maxCrop: Float): MotionPath {
        require(listOf(iw, ih, vw, vh).all { it.isFinite() && it > 0 })
        val fit = min(vw / iw, vh / ih)
        val fill = max(vw / iw, vh / ih)
        // Limit loss of image area and protect the entire subject rectangle.
        val retained = 1f - maxCrop.coerceIn(0f, .85f)
        // Before fill, only one axis is cropped; letterbox area must not count
        // as visible image. Past fill, both axes contribute to visible area.
        val cropLimit = (if (retained >= fit / fill) fit / retained
            else sqrt(vw * vh / (iw * ih * retained))).coerceAtLeast(fit)
        val regionLimit = region?.let { min(vw / (iw * it.width.coerceAtLeast(.001f)), vh / (ih * it.height.coerceAtLeast(.001f))) } ?: Float.MAX_VALUE
        val limit = min(cropLimit, regionLimit).coerceAtLeast(fit)
        val chosen = if (mode == MotionMode.AUTO) { if (fill <= limit && fill / fit < 1.35f) MotionMode.CROP_ZOOM else MotionMode.FIT_ZOOM } else mode
        val start = if (chosen == MotionMode.CROP_ZOOM) min(fill, limit) else fit
        val end = min(if (chosen == MotionMode.CROP_ZOOM) start * (1 + strength) else min(fill, fit * (1 + strength * 3)), limit).coerceAtLeast(start)
        val target = focus ?: FocusPoint(.5f, .5f)
        fun axis(image: Float, view: Float, s: Float, f: Float, r0: Float?, rw: Float?): Float {
            val extent = image * s
            if (extent <= view) return (view - extent) / 2
            var low = view - extent
            var high = 0f
            if (r0 != null && rw != null) {
                low = max(low, -r0 * extent)
                high = min(high, view - (r0 + rw) * extent)
            }
            if (low > high) return ((low + high) / 2).coerceIn(view - extent, 0f)
            return (view / 2 - f.coerceIn(0f, 1f) * extent).coerceIn(low, high)
        }
        fun at(s: Float, point: FocusPoint) = MotionFrame(s,
            axis(iw, vw, s, point.x, region?.x, region?.width), axis(ih, vh, s, point.y, region?.y, region?.height))
        return MotionPath(at(start, if (chosen == MotionMode.FIT_ZOOM) FocusPoint(.5f, .5f) else target), at(end, target))
    }
}

/** Distances are measured in corrected-image pixels then divided by its diagonal. */
fun stableAutoFocus(old: FocusPoint?, fresh: FocusPoint?, width: Int, height: Int, threshold: Float = .05f): FocusPoint? {
    if (fresh == null || old == null) return fresh
    val w = width.toDouble().coerceAtLeast(1.0); val h = height.toDouble().coerceAtLeast(1.0)
    val d = hypot((fresh.x - old.x) * w, (fresh.y - old.y) * h) / hypot(w, h)
    return if (d <= threshold + 1e-7) old else fresh
}

const val TRASH_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
