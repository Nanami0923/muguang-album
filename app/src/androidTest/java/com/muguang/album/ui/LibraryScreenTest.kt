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
import com.muguang.album.core.SourceKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises user decisions against the same screen and callbacks used by MainActivity. */
class LibraryScreenTest {
    private val shellActivity = ShellActivityRule(ComponentActivity::class.java)
    @get:Rule val compose = AndroidComposeTestRule(shellActivity) { it.activity }

    private val album = Album(id = "family", name = "家庭相册")

    @Test
    fun emptyGalleryCanChooseTheCurrentAlbumsFolderInANarrowWindow() {
        val recorder = show(LibraryState(albums = listOf(album), initialized = true))

        compose.onNodeWithText("从一个文件夹开始").assertExists()
        compose.onNodeWithText("选择图片文件夹").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf("pick-photos:family"), recorder.calls) }
    }

    @Test
    fun settingsNavigationChangesClockAndCanReachRemindersByScrolling() {
        val recorder = show(LibraryState(albums = listOf(album), initialized = true))

        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("相框设置").assertIsDisplayed()
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

        compose.onNodeWithText("定时").performClick()
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

        compose.onNodeWithText("定时").performClick()
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
