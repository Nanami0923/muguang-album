package com.muguang.album.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import com.muguang.album.core.Album
import com.muguang.album.core.AppPreferences
import com.muguang.album.core.FolderSource
import com.muguang.album.core.LibraryState
import com.muguang.album.core.MusicOrder
import com.muguang.album.core.MusicTrack
import com.muguang.album.core.PlaySchedule
import com.muguang.album.core.Photo
import com.muguang.album.core.SourceKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises user decisions against the same screen and callbacks used by MainActivity. */
class LibraryScreenTest {
    private val shellActivity = ShellActivityRule(ComponentActivity::class.java)
    @get:Rule val compose = AndroidComposeTestRule(shellActivity) { it.activity }

    private val album = Album(id = "family", name = "家庭相册")

    @Test fun longPressShowsActionsAndReanalysisTargetsOnlyTheChosenPhoto() {
        val source = FolderSource("photos", album.id, "content://test/photos", "测试照片", SourceKind.PHOTO)
        val photo = Photo("photo-1", source.id, "content://test/photo-1", "选中的照片.jpg")
        val recorder = show(LibraryState(albums = listOf(album), sources = listOf(source), photos = listOf(photo), initialized = true))
        compose.onNodeWithTag("photo-library-grid").performScrollToIndex(5)
        compose.onNodeWithTag("photo-tile-photo-1").performTouchInput { longClick() }
        compose.onNodeWithText("图片操作").assertIsDisplayed()
        compose.onNodeWithText("仅从当前相册移除，保留原图。").assertIsDisplayed()
        compose.onNodeWithTag("focus-image").assertDoesNotExist()
        captureActions("photo-long-press.png")
        compose.onNodeWithTag("photo-action-reanalyze").performClick()
        compose.onNodeWithText("图片操作").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("reanalyze:photos:photo-1"), recorder.calls) }
    }

    @Test fun removalRequiresConfirmationAndCanBeRestoredFromTrash() {
        val source = FolderSource("photos", album.id, "content://test/photos", "测试照片", SourceKind.PHOTO)
        val photo = Photo("photo-1", source.id, "content://test/photo-1", "选中的照片.jpg")
        val recorder = show(LibraryState(albums = listOf(album), sources = listOf(source), photos = listOf(photo), initialized = true))
        val grid = compose.onNodeWithTag("photo-library-grid")
        grid.performScrollToIndex(5)
        compose.onNodeWithTag("photo-tile-photo-1").performTouchInput { longClick() }
        compose.onNodeWithTag("photo-action-remove").performClick()
        compose.onNodeWithText("从当前相册删除？").assertIsDisplayed()
        captureActions("photo-remove-confirmation.png")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(recorder.calls.isEmpty()) }
        compose.onNodeWithTag("photo-tile-photo-1").assertExists().performTouchInput { longClick() }
        compose.onNodeWithTag("photo-action-remove").performClick()
        compose.onNodeWithTag("confirm-remove-photo").performClick()
        compose.onNodeWithTag("photo-tile-photo-1").assertDoesNotExist()
        grid.performScrollToIndex(4)
        compose.onNodeWithTag("library-status-filters").performScrollToNode(hasText("回收站"))
        compose.onNodeWithText("回收站").performClick()
        compose.onNodeWithTag("restore-photo-photo-1").performClick()
        compose.onNodeWithText("关闭").performClick()
        grid.performScrollToIndex(5)
        compose.onNodeWithTag("photo-tile-photo-1").assertExists()
        compose.runOnIdle { assertEquals(listOf("remove:photos:photo-1", "restore:photos:photo-1"), recorder.calls) }
    }

    @Test fun fiveSlotsSeparateAiAndTheCenterActionStartsTheCurrentAlbumWithoutNavigating() {
        val source = FolderSource("photos", album.id, "content://test/photos", "测试照片", SourceKind.PHOTO)
        val photo = Photo("p", source.id, "content://test/p", "照片.jpg")
        val recorder = show(LibraryState(albums = listOf(album), sources = listOf(source), photos = listOf(photo), initialized = true))
        listOf("nav-albums", "nav-ai", "start-slideshow", "nav-playback", "nav-settings").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        val slots = listOf("nav-albums", "nav-ai", "start-slideshow", "nav-playback", "nav-settings").map {
            compose.onNodeWithTag(it).getUnclippedBoundsInRoot()
        }
        assertTrue(slots.zipWithNext().all { (a, b) -> a.left < b.left })
        captureActions("v031-navigation.png")
        compose.onNodeWithTag("nav-ai").performClick().assertIsSelected()
        compose.onNodeWithText("AI 图片识别").assertIsDisplayed()
        captureActions("v031-ai.png")
        compose.onNodeWithTag("start-slideshow").performClick()
        compose.onNodeWithTag("nav-ai").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf("play:family"), recorder.calls) }
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("ai-enabled").assertDoesNotExist()
        compose.onNodeWithTag("nav-albums").performClick()
        compose.onNodeWithContentDescription("移除此相册").performClick()
        compose.onNodeWithText("移除“家庭相册”？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun centerActionExplainsEmptyAlbumsAndDoesNotStartPlayback() {
        val recorder = show(LibraryState(albums = listOf(album), initialized = true))
        compose.onNodeWithTag("start-slideshow").performClick()
        compose.onNodeWithTag("nav-albums").assertIsSelected()
        compose.runOnIdle {
            assertTrue(recorder.calls.single().startsWith("message:当前相册没有可播放的照片"))
        }
    }

    @Test fun playbackSettingsSaveEffectBothSpeedsAndCustomFocusDuration() {
        val recorder = show(LibraryState(albums = listOf(album), initialized = true))
        compose.onNodeWithTag("nav-playback").performClick()
        compose.onNodeWithTag("effect-RANDOM").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("effect-CUBE").performScrollTo().performClick()
        compose.onNodeWithTag("transition-speed-LOW").performScrollTo().performClick()
        captureActions("v031-transitions.png")
        compose.onNodeWithTag("focus-animation-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("focus-speed-HIGH").performScrollTo().performClick()
        compose.onNodeWithTag("focus-duration").performScrollTo().performTextClearance()
        compose.onNodeWithText("保存设置").assertIsNotEnabled()
        compose.onNodeWithTag("focus-duration").performTextInput("4")
        captureActions("v031-focus.png")
        compose.onNodeWithText("保存设置").performScrollTo().performClick()
        compose.runOnIdle {
            val saved = recorder.updatedAlbum!!
            assertEquals(com.muguang.album.core.SlideEffect.CUBE, saved.transitionEffect)
            assertEquals(1200, saved.transitionMillis)
            assertEquals(com.muguang.album.core.MotionMode.FIT_ZOOM, saved.motionMode)
            assertEquals(4, saved.focusDurationSeconds)
        }
    }

    private fun captureActions(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(750) // Let the native dialog window animation settle.
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "photo-actions").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            java.io.File(directory, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test fun previewLayoutSwitchesBetweenOneAndTwoColumns() {
        val source=FolderSource("photos",album.id,"content://test/photos","测试照片",SourceKind.PHOTO)
        val photos=(1..20).map { Photo("photo-$it",source.id,"content://test/photo-$it","照片$it.jpg") }
        val recorder=show(LibraryState(albums=listOf(album),sources=listOf(source),photos=photos,initialized=true))
        val grid=compose.onNodeWithTag("photo-library-grid")
        grid.performScrollToIndex(4)
        compose.onNodeWithTag("gallery-columns-1").performClick()
        compose.runOnIdle { assertEquals(1,recorder.latestPreferences!!.galleryColumns) }
        grid.performScrollToIndex(5)
        val firstSingle=compose.onNodeWithTag("photo-tile-photo-1").getUnclippedBoundsInRoot()
        val secondSingle=compose.onNodeWithTag("photo-tile-photo-2").getUnclippedBoundsInRoot()
        assertEquals(firstSingle.left.value,secondSingle.left.value,.1f)
        assertTrue(secondSingle.top>firstSingle.top)
        grid.performScrollToIndex(4)
        compose.onNodeWithTag("gallery-columns-2").performClick()
        compose.runOnIdle { assertEquals(2,recorder.latestPreferences!!.galleryColumns) }
        grid.performScrollToIndex(5)
        val firstDouble=compose.onNodeWithTag("photo-tile-photo-1").getUnclippedBoundsInRoot()
        val secondDouble=compose.onNodeWithTag("photo-tile-photo-2").getUnclippedBoundsInRoot()
        assertEquals(firstDouble.top.value,secondDouble.top.value,.1f)
        assertTrue(secondDouble.left>firstDouble.left)
    }

    @Test fun scrollingPhotosKeepsTheCenterPlayActionVisible() {
        val source = FolderSource("photos",album.id,"content://test/photos","测试照片",SourceKind.PHOTO)
        val photos = (1..80).map { Photo("photo-$it",source.id,"content://test/photo-$it","照片$it.jpg") }
        show(LibraryState(albums=listOf(album),sources=listOf(source),photos=photos,initialized=true))
        compose.onNodeWithText("开始播放").assertDoesNotExist()
        compose.onNodeWithTag("start-slideshow").assertIsDisplayed()
        compose.onNodeWithTag("photo-library-grid").performScrollToIndex(40)
        compose.onNodeWithTag("start-slideshow").assertIsDisplayed()
        compose.onNodeWithTag("photo-library-grid").assertIsDisplayed()
    }

    @Test
    fun emptyGalleryCanChooseTheCurrentAlbumsFolderInANarrowWindow() {
        val recorder = show(LibraryState(albums = listOf(album), initialized = true))

        compose.onNodeWithTag("photo-library-grid").performScrollToIndex(5)
        compose.onNodeWithText("从一个文件夹开始").assertExists()
        compose.onNodeWithText("选择图片文件夹").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf("pick-photos:family"), recorder.calls) }
    }

    @Test
    fun settingsNavigationChangesClockAndCanReachRemindersByScrolling() {
        val recorder = show(LibraryState(albums = listOf(album), initialized = true))

        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("相框设置").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("setting-clock"))
        compose.onNodeWithTag("setting-clock").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("setting-reminders"))
        compose.onNodeWithTag("setting-reminders").assertIsOff().performClick().assertIsOn()

        compose.runOnIdle {
            assertTrue(recorder.latestPreferences!!.showClock)
            assertTrue(recorder.latestPreferences!!.remindersEnabled)
            assertEquals(2, recorder.calls.count { it == "update-preferences" })
        }
        compose.onNode(hasText("相册") and hasClickAction()).performClick()
        compose.onNodeWithText("暮光相册").assertIsDisplayed()
    }

    @Test
    fun creatingAWeekdayScheduleSendsTheSelectedAlbumAndRepeatDays() {
        val recorder = show(LibraryState(albums = listOf(album), initialized = true))

        compose.onNodeWithTag("nav-playback").performClick()
        compose.onNodeWithText("定时计划").performClick()
        compose.onNodeWithContentDescription("新建播放计划").performClick()
        compose.onNodeWithText("计划名称（可选）").performTextInput("白天展示")
        compose.onNodeWithText("工作日").performScrollTo().performClick()
        compose.onNodeWithText("保存计划").performScrollTo().performClick()

        compose.runOnIdle {
            val saved = recorder.savedSchedule!!
            assertEquals("白天展示", saved.name)
            assertEquals("family", saved.albumId)
            assertEquals(31, saved.daysMask)
            assertEquals(8 * 60, saved.startMinute)
            assertEquals(22 * 60, saved.endMinute)
            assertTrue(saved.enabled)
        }
        compose.onNodeWithText("白天展示").assertExists()
    }

    @Test
    fun aScheduleWithNoSelectedDaysIsNotSaved() {
        val recorder = show(LibraryState(albums = listOf(album), initialized = true))

        compose.onNodeWithTag("nav-playback").performClick()
        compose.onNodeWithText("定时计划").performClick()
        compose.onNodeWithContentDescription("新建播放计划").performClick()
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
            compose.onNodeWithText(day).performScrollTo().performClick()
        }
        compose.onNodeWithText("保存计划").performScrollTo().performClick()

        compose.onNodeWithText("请至少选择一个重复星期").assertIsDisplayed()
        compose.runOnIdle { assertNull(recorder.savedSchedule) }
    }

    @Test
    fun musicSettingsSaveRandomPlaybackWithoutLosingItsFolder() {
        val musicAlbum = album.copy(musicSourceId = "piano", musicEnabled = false)
        val recorder = show(
            LibraryState(
                albums = listOf(musicAlbum),
                sources = listOf(FolderSource("piano", "family", "content://test/tree/music", "钢琴配乐", SourceKind.MUSIC, recursive = false)),
                tracks = listOf(MusicTrack("one", "piano", "content://test/document/one", "01.mp3")),
                initialized = true,
            ),
        )

        compose.onNodeWithContentDescription("背景音乐").performClick()
        compose.onNodeWithText("钢琴配乐").assertExists()
        compose.onNodeWithTag("music-enabled").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("随机循环").performScrollTo().performClick()
        compose.onNodeWithText("保存音乐设置").performScrollTo().performClick()

        compose.runOnIdle {
            val saved = recorder.updatedAlbum!!
            assertEquals("piano", saved.musicSourceId)
            assertTrue(saved.musicEnabled)
            assertEquals(MusicOrder.RANDOM, saved.musicOrder)
            assertEquals(.35f, saved.musicVolume, .001f)
        }
    }

    private fun show(initial: LibraryState): Recorder {
        val state = mutableStateOf(initial)
        val recorder = Recorder()
        val actions = LibraryActions(
            onSelectAlbum = { recorder.calls += "select:$it" },
            onCreateAlbum = { recorder.calls += "create:$it" },
            onDeleteAlbum = { recorder.calls += "delete:$it" },
            onPickPhotos = { recorder.calls += "pick-photos:$it" },
            onPickMusic = { recorder.calls += "pick-music:$it" },
            onUpdateAlbum = { updated ->
                recorder.calls += "update-album"
                recorder.updatedAlbum = updated
                state.value = state.value.copy(albums = state.value.albums.map { if (it.id == updated.id) updated else it })
            },
            onRefreshSource = { recorder.calls += "refresh:$it" },
            onRemoveSource = { recorder.calls += "remove-source:$it" },
            onUpdateSource = { id, recursive -> recorder.calls += "recursive:$id:$recursive" },
            onPlay = { recorder.calls += "play:$it" },
            onEnterDisplay = { recorder.calls += "display" },
            onSaveSchedule = { saved ->
                recorder.calls += "save-schedule"
                recorder.savedSchedule = saved
                state.value = state.value.copy(schedules = state.value.schedules.filterNot { it.id == saved.id } + saved)
            },
            onDeleteSchedule = { recorder.calls += "delete-schedule:$it" },
            onUpdatePreferences = { preferences ->
                recorder.calls += "update-preferences"
                recorder.latestPreferences = preferences
                state.value = state.value.copy(preferences = preferences)
            },
            onMessage = { recorder.calls += "message:$it" },
            onReanalyzePhoto = { recorder.calls += "reanalyze:${it.sourceId}:${it.id}" },
            onRemovePhoto = { photo ->
                recorder.calls += "remove:${photo.sourceId}:${photo.id}"
                state.value = state.value.copy(photos = state.value.photos.map { if (it.id == photo.id && it.sourceId == photo.sourceId) it.copy(trashedAt = 1, unavailableReason = com.muguang.album.core.USER_REMOVED_PHOTO) else it })
            },
            onRestorePhoto = { photo ->
                recorder.calls += "restore:${photo.sourceId}:${photo.id}"
                state.value = state.value.copy(photos = state.value.photos.map { if (it.id == photo.id && it.sourceId == photo.sourceId) it.copy(trashedAt = null, unavailableReason = null) else it })
            },
        )
        compose.setContent {
            MuguangTheme(dynamicColor = false) {
                // Exercise compact navigation even when the test device itself is a large tablet.
                Box(Modifier.width(420.dp).fillMaxHeight()) {
                    MainLibraryScreen(state.value, "family", actions)
                }
            }
        }
        return recorder
    }

    private class Recorder {
        val calls = mutableListOf<String>()
        var latestPreferences: AppPreferences? = null
        var savedSchedule: PlaySchedule? = null
        var updatedAlbum: Album? = null
    }
}
