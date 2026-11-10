package com.muguang.album.playback

import com.muguang.album.core.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SlideTransitionsTest {
    @Test fun randomRoundsCoverEveryEffectAndNeverRepeatAtTheSeam() {
        val cycle = SlideEffectCycle(Random(42))
        val choices = List(100) { cycle.next(SlideEffect.RANDOM) }
        assertTrue(choices.zipWithNext().all { (a, b) -> a != b })
        choices.chunked(5).forEach { assertEquals(5, it.toSet().size) }
        assertFalse(choices.contains(SlideEffect.NONE))
        assertFalse(choices.contains(SlideEffect.RANDOM))
        assertEquals(SlideEffect.CUBE, cycle.next(SlideEffect.CUBE))
    }

    @Test fun allEffectsFinishAtAnUntransformedIncomingImage() {
        SlideEffect.entries.forEach { effect ->
            val end = slideLayers(effect, 1f).incoming
            assertEquals(1f, end.alpha, 0f); assertEquals(1f, end.scale, 0f)
            assertEquals(0f, end.x, 0f); assertEquals(0f, end.y, 0f); assertEquals(0f, end.rotationY, 0f)
        }
    }

    @Test fun allVisibleFacesStayWithinNinetyDegreesAndAllLayersStayFinite() {
        SlideEffect.entries.forEach { effect -> (0..100).forEach { step ->
            val layers = slideLayers(effect, step / 100f)
            listOf(layers.incoming, layers.outgoing).forEach {
                assertTrue(listOf(it.alpha, it.x, it.y, it.rotationY, it.scale).all(Float::isFinite))
                assertTrue(it.alpha in 0f..1f)
                assertTrue(it.rotationY in -90f..90f)
            }
        } }
        assertTrue(slideLayers(SlideEffect.PAGE, .5f).outgoingOnTop)
        assertEquals(0f, slideLayers(SlideEffect.FLIP, .4f).incoming.alpha, 0f)
        assertEquals(0f, slideLayers(SlideEffect.FLIP, .6f).outgoing.alpha, 0f)
    }

    @Test fun focusReachesTheTargetThenHoldsWithoutDependingOnFrameRate() {
        val oneFrame = focusProgress(0f, 3000f, 6000)
        val manyFrames = (1..30).fold(0f) { p, _ -> focusProgress(p, 100f, 6000) }
        assertEquals(.5f, oneFrame, .0001f); assertEquals(oneFrame, manyFrames, .0001f)
        assertEquals(1f, focusProgress(.9f, 5000f, 6000), 0f)
        assertEquals(1f, focusProgress(1f, 1000f, 6000), 0f)
        val album = Album("a", "测试", intervalSeconds = 1, focusDurationSeconds = 10)
        assertEquals(1000, album.effectiveFocusMillis)
        assertEquals(500, album.effectiveTransitionMillis)
        assertEquals(0, album.copy(transitionEffect = SlideEffect.NONE).effectiveTransitionMillis)
    }
}
