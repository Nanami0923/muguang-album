package com.muguang.album.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import com.muguang.album.core.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FocusEditorTest {
    private val shell = ShellActivityRule(ComponentActivity::class.java)
    @get:Rule val compose = AndroidComposeTestRule(shell) { it.activity }
    @Test fun touchMapsImageCoordinatesAndIgnoresLetterbox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "focus-editor-synthetic.png")
        val bitmap = Bitmap.createBitmap(400,20,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        var saved: FocusPoint? = null
        try {
            val photo = Photo("p","s",android.net.Uri.fromFile(file).toString(),"触控测试",metadata=PhotoMetadata(manualX=.2f,manualY=.3f))
            compose.setContent { MuguangTheme(false) { FocusEditor(photo, Album("a","测试"), {}) { point, _, _ -> saved = point } } }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("focus-image").fetchSemanticsNodes().isNotEmpty() }
            settleEditorWindow()
            checkAndCaptureSave("focus-portrait")
            compose.onNodeWithTag("focus-image").performTouchInput { click(Offset(width*.75f,height*.5f)) }
            compose.onNodeWithTag("focus-image").performTouchInput { click(Offset(1f,1f)) }
            // Swiping away from the crosshair scrolls the page without moving the focus.
            compose.onNodeWithTag("focus-image").performTouchInput {
                swipe(Offset(width*.1f,height*.9f),Offset(width*.1f,height*.1f),500)
            }
            compose.onNodeWithTag("focus-save").assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertNotNull(saved)
                assertEquals(.75f,saved!!.x,.02f)
                assertEquals(.5f,saved!!.y,.03f)
            }
        } finally { file.delete() }
    }

    private fun checkAndCaptureSave(name: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(1000)
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"layout-checks").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
            File(directory,"$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
            screenshot.recycle()
        }
        compose.onNodeWithTag("focus-save").assertIsDisplayed()
        val frame=Rect()
        compose.runOnIdle { shell.activity.window.decorView.getWindowVisibleDisplayFrame(frame) }
        val button=compose.onNodeWithTag("focus-save").getUnclippedBoundsInRoot()
        val bottom=with(compose.density) { button.bottom.toPx() }
        assertTrue("Save button $button is outside visible frame $frame", bottom <= frame.height()+1)
        assertTrue(with(compose.density) { button.right.toPx() } <= frame.width()+1)
        assertTrue(button.left.value >= 0 && button.top.value >= 0)

    }

    private fun settleEditorWindow() {
        // A resumed activity can still be inside the OEM task-switch animation;
        // injecting a swipe then can reach the launcher instead of the dialog.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(1200)
    }

    @Test fun saveStaysFullyVisibleInLandscape() {
        shell.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"focus-landscape.png")
        val bitmap=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        var saved=false
        try {
            val photo=Photo("landscape","s",android.net.Uri.fromFile(file).toString(),"横屏选点测试")
            compose.setContent { MuguangTheme(false) { FocusEditor(photo,Album("a","测试"),{}) { _,_,_ -> saved=true } } }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("focus-image").fetchSemanticsNodes().isNotEmpty() }
            settleEditorWindow()
            compose.onNodeWithTag("focus-editor-scroll").performTouchInput { swipeUp() }
            checkAndCaptureSave("focus-landscape")
            compose.onNodeWithTag("focus-save").performClick()
            compose.runOnIdle { assertTrue(saved) }
        } finally {
            file.delete()
            shell.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun saveStaysVisibleWithLargeText() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"focus-large-text.png")
        val bitmap=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        var saved=false
        try {
            val photo=Photo("large-text","s",android.net.Uri.fromFile(file).toString(),"大字体选点与保存按钮测试")
            compose.setContent {
                MuguangTheme(false) {
                    val type=MaterialTheme.typography
                    MaterialTheme(typography=type.copy(
                        titleLarge=type.titleLarge.copy(fontSize=type.titleLarge.fontSize*2,lineHeight=type.titleLarge.lineHeight*2),
                        bodySmall=type.bodySmall.copy(fontSize=type.bodySmall.fontSize*2,lineHeight=type.bodySmall.lineHeight*2),
                        labelLarge=type.labelLarge.copy(fontSize=type.labelLarge.fontSize*2,lineHeight=type.labelLarge.lineHeight*2),
                        labelMedium=type.labelMedium.copy(fontSize=type.labelMedium.fontSize*2,lineHeight=type.labelMedium.lineHeight*2),
                    )) { FocusEditor(photo,Album("a","测试"),{}) { _,_,_ -> saved=true } }
                }
            }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("focus-image").fetchSemanticsNodes().isNotEmpty() }
            settleEditorWindow()
            checkAndCaptureSave("focus-large-text")
            compose.onNodeWithTag("focus-save").performClick()
            compose.runOnIdle { assertTrue(saved) }
        } finally { file.delete() }
    }
}
