package com.muguang.album.playback

import com.muguang.album.core.SlideEffect
import kotlin.random.Random

/** Shuffle bag: every effect appears once per round, without a repeat at the seam. */
class SlideEffectCycle(private val random: Random = Random.Default) {
    private val bag = ArrayDeque<SlideEffect>()
    private var last: SlideEffect? = null
    fun next(requested: SlideEffect): SlideEffect {
        if (requested != SlideEffect.RANDOM) return requested
        if (bag.isEmpty()) {
            val round = listOf(SlideEffect.FADE, SlideEffect.PAGE, SlideEffect.CUBE, SlideEffect.STACK, SlideEffect.FLIP).shuffled(random).toMutableList()
            if (round.first() == last) { val other = round[1]; round[1] = round[0]; round[0] = other }
            bag.addAll(round)
        }
        return bag.removeFirst().also { last = it }
    }
}

data class SlideLayer(
    val alpha: Float = 1f, val x: Float = 0f, val y: Float = 0f,
    val scale: Float = 1f, val rotationY: Float = 0f, val pivotX: Float = .5f,
)

data class SlideLayers(val outgoing: SlideLayer, val incoming: SlideLayer, val outgoingOnTop: Boolean = false)

/** Translations are fractions of the viewport, rotations never expose a mirrored back face. */
fun slideLayers(effect: SlideEffect, progress: Float): SlideLayers {
    val p = progress.coerceIn(0f, 1f)
    return when (effect) {
        SlideEffect.PAGE -> SlideLayers(SlideLayer(alpha = if (p == 1f) 0f else 1f, rotationY = 90f * p, pivotX = 0f), SlideLayer(), true)
        SlideEffect.CUBE -> SlideLayers(SlideLayer(x = -p, rotationY = -90f * p, pivotX = 1f),
            SlideLayer(x = 1f - p, rotationY = 90f * (1f - p), pivotX = 0f))
        SlideEffect.STACK -> SlideLayers(SlideLayer(alpha = 1f - p, scale = 1f - .08f * p, x = -.12f * p),
            SlideLayer(alpha = p, scale = .88f + .12f * p, y = .12f * (1f - p)))
        SlideEffect.FLIP -> SlideLayers(SlideLayer(alpha = if (p < .5f) 1f else 0f, rotationY = 180f * minOf(p, .5f)),
            SlideLayer(alpha = if (p >= .5f) 1f else 0f, rotationY = -180f * (1f - maxOf(p, .5f))))
        SlideEffect.NONE -> SlideLayers(SlideLayer(alpha = 0f), SlideLayer())
        else -> SlideLayers(SlideLayer(), SlideLayer(alpha = p))
    }
}

/** Time, not frame count, drives focus movement. The end frame is held until the slide changes. */
fun focusProgress(previous: Float, elapsedMillis: Float, durationMillis: Int): Float =
    (previous + elapsedMillis.coerceAtLeast(0f) / durationMillis.coerceAtLeast(1)).coerceIn(0f, 1f)
