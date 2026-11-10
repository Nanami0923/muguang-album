package com.muguang.album.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import com.muguang.album.core.SlideEffect
import com.muguang.album.presentation.SlideTransition
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class SlideTransitionTest {
    private val shell = ShellActivityRule(ComponentActivity::class.java)
    @get:Rule val compose = AndroidComposeTestRule(shell) { it.activity }

    @Test fun focusZoomPausesReachesItsTargetHoldsAndRestartsOnTheNextCycle() {
        val paused = mutableStateOf(false)
        val token = mutableStateOf(0)
        var progress = 0f
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        val painter = BitmapPainter(bitmap.asImageBitmap())
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                Box(Modifier.size(320.dp, 240.dp)) {
                    com.muguang.album.presentation.MotionPicture(painter,
                        com.muguang.album.core.Photo("p", "s", "test", "照片", metadata = com.muguang.album.core.PhotoMetadata(manualX = .8f, manualY = .2f)),
                        com.muguang.album.core.Album("a", "相册", motionMode = com.muguang.album.core.MotionMode.FIT_ZOOM, focusDurationSeconds = 1),
                        paused.value, token.value, onProgress = { progress = it })
                }
            }
            compose.mainClock.advanceTimeBy(320)
            compose.runOnIdle { assertTrue("Animation advances with time: $progress", progress in .1f.. .5f); paused.value = true }
            compose.mainClock.advanceTimeByFrame()
            val frozen = progress
            compose.mainClock.advanceTimeBy(2000)
            compose.runOnIdle { assertEquals(frozen, progress, 0f); paused.value = false }
            compose.mainClock.advanceTimeBy(1500)
            compose.runOnIdle { assertEquals(1f, progress, 0f) }
            compose.mainClock.advanceTimeBy(2000)
            compose.runOnIdle { assertEquals(1f, progress, 0f); token.value++ }
            compose.mainClock.advanceTimeBy(150)
            compose.runOnIdle { assertTrue("A new cycle restarts focus zoom: $progress", progress < .3f) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun everyEffectRendersBothPicturesAndFinishesWithoutMirroredContent() {
        val selected = mutableStateOf(SlideEffect.FADE)
        val progress = mutableStateOf(.25f)
        compose.setContent {
            MuguangTheme(false) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    SlideTransition(selected.value, progress.value,
                        previous = { modifier -> Box(modifier.background(Color(0xFF214E64)), contentAlignment = Alignment.Center) { Text("上一张 · A →", color = Color.White) } },
                        current = { modifier -> Box(modifier.background(Color(0xFFC37048)), contentAlignment = Alignment.Center) { Text("下一张 · B →", color = Color.White) } })
                }
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(1200)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "v031-transitions").apply { mkdirs() }
        listOf(SlideEffect.FADE, SlideEffect.PAGE, SlideEffect.CUBE, SlideEffect.STACK, SlideEffect.FLIP).forEach { effect ->
            compose.runOnIdle { selected.value = effect; progress.value = .25f }
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(300)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory, "${effect.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.runOnIdle { progress.value = 1f }
            compose.onNodeWithText("下一张 · B →").assertIsDisplayed()
        }
    }
}
