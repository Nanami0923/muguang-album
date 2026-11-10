package com.muguang.album.integration

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muguang.album.AlbumViewModel
import com.muguang.album.MainActivity
import com.muguang.album.PauseReason
import com.muguang.album.core.Album
import com.muguang.album.core.PlaySchedule
import com.muguang.album.core.SourceKind
import com.muguang.album.playback.ScheduleCalculator
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the actual activity, Room repository, SAF reader, Coil callbacks and ExoPlayer.
 * All images are synthetic and both tracks are PCM silence; player gain is additionally zero.
 */
@RunWith(AndroidJUnit4::class)
class AlbumPlaybackIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ShellActivitySession? = null
    private lateinit var model: AlbumViewModel
    private val createdAlbums = mutableListOf<String>()
    private val createdSchedules = mutableListOf<String>()

    @Before fun launchRealActivityAndGrantSyntheticFolders() {
        if (InstrumentationRegistry.getArguments().getString("debugStacks") == "true") {
            Thread {
                SystemClock.sleep(8000)
                val output = File(instrumentation.targetContext.getExternalFilesDir(null), "instrumentation-threads.txt")
                output.writeText(Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                    thread.name + " " + thread.state + "\n" + stack.joinToString("\n")
                })
            }.apply { isDaemon = true; start() }
        }
        scenario = ShellActivitySession.launch()
        scenario!!.onActivity { activity ->
            model = ViewModelProvider(activity)[AlbumViewModel::class.java]
        }
        ShellActivitySession.grantSyntheticFolders()
        await("synthetic provider URI grant") {
            instrumentation.targetContext.checkUriPermission(
                TestDocumentsProvider.PHOTOS_TREE, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        }
        instrumentation.waitForIdleSync()
        await("library initialization") { model.library.value.initialized }
        onMain { model.stopPlayback(leaveDisplay = true) }
    }

    @After fun removeOnlyThisTestsMetadataAndGrants() {
        if (::model.isInitialized) {
            instrumentation.runOnMainSync { model.stopPlayback(leaveDisplay = true) }
            runBlocking {
                withTimeout(15_000) {
                    createdSchedules.forEach { model.repository.deleteSchedule(it) }
                    createdAlbums.forEach { model.repository.deleteAlbum(it) }
                }
            }
            // Remove only this test's occurrence suppression records, leaving user records intact.
            val guards = instrumentation.targetContext.getSharedPreferences("occurrence_guards", 0)
            val edit = guards.edit()
            guards.all.keys.filter { key -> createdSchedules.any { key.startsWith("$it:") } }.forEach { edit.remove(it) }
            edit.commit()
        }
        if (scenario != null) {
            ShellActivitySession.grantSyntheticFolders(revoke = true)
            await("synthetic provider URI grant revoked", 10_000) {
                instrumentation.targetContext.checkUriPermission(
                    TestDocumentsProvider.PHOTOS_TREE, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ) != PackageManager.PERMISSION_GRANTED
            }
        }
        instrumentation.waitForIdleSync()
        scenario?.close()
        scenario = null
    }

    @Test fun safImagesAutoAdvanceAndSilentMusicFollowsPauseWithoutRestartOnNextPhoto() {
        val album = createFixtureAlbum(intervalSeconds = 1)
        assertEquals(3, model.library.value.photosFor(album.id).size)
        assertEquals(2, model.library.value.tracksFor(album).size)
        onMain { model.selectAlbum(album.id) }
        saveSyntheticScreenshot("03-gallery.png")

        onMain { model.playAlbum(album.id) }
        await("Coil photo ready") { model.session.value?.ready == true }
        await("silent WAV actually playing") { model.music.state.value.isPlaying }
        assertEquals(0f, model.music.state.value.volume, 0f)
        saveSyntheticScreenshot("02-player.png")

        val firstPhoto = model.session.value!!.photo.id
        val track = model.music.state.value.currentTrackId
        val position = model.music.state.value.positionMs
        onMain { model.changePhoto(1) }
        await("manual photo change") { model.session.value?.let { it.ready && it.photo.id != firstPhoto } == true }
        assertEquals("Changing pictures must not restart or switch the music", track, model.music.state.value.currentTrackId)
        assertTrue(model.music.state.value.positionMs + 100 >= position)

        val secondPhoto = model.session.value!!.photo.id
        await("automatic slideshow advance", 6000) {
            model.session.value?.let { it.ready && it.photo.id != secondPhoto } == true
        }
        onMain { model.togglePause() }
        await("music paused with slideshow") { model.session.value?.paused == true && !model.music.state.value.isPlaying }
        val pausedPhoto = model.session.value!!.photo.id
        holdFor(1250)
        assertEquals(pausedPhoto, model.session.value!!.photo.id)

        // A lifecycle refresh must not override a user's explicit pause.
        onMain { model.setForeground(false); model.setForeground(true) }
        assertEquals(PauseReason.USER, model.session.value!!.pauseReason)
        assertFalse(model.music.state.value.isPlaying)
        onMain { model.togglePause() }
        await("music resumed with slideshow") { model.music.state.value.isPlaying && model.session.value?.paused == false }

        val stale = model.session.value!!
        onMain {
            model.stopPlayback(leaveDisplay = true)
            model.imageReady(stale.token, stale.photo.id)
            model.imageFailed(stale.token, stale.photo.id)
            model.music.next()
        }
        holdFor(500)
        assertNull(model.session.value)
        assertFalse(model.music.state.value.isPlaying)
    }

    @Test fun scheduledManualStopSuppressesThisWindowAndLateCallbacksCannotRestartIt() {
        val album = createFixtureAlbum(intervalSeconds = 30)
        val now = ZonedDateTime.now()
        val minute = now.hour * 60 + now.minute
        val schedule = PlaySchedule(
            id = "integration-${UUID.randomUUID()}", name = "合成素材计划测试", albumId = album.id,
            startMinute = (minute + 1439) % 1440, endMinute = (minute + 3) % 1440,
        )
        // Existing user schedules are never changed to make a test pass.
        assumeTrue("Current user schedules overlap the test window", ScheduleCalculator.conflict(schedule, model.library.value.schedules) == null)
        createdSchedules += schedule.id
        runBlocking { withTimeout(15_000) { model.repository.saveSchedule(schedule) } }
        await("new schedule saved") { model.library.value.schedules.any { it.id == schedule.id } }
        onMain { model.enterDisplay() }
        await("scheduled photo playback") {
            model.session.value?.let { it.ready && it.occurrenceKey?.startsWith("${schedule.id}:") == true } == true
        }
        await("scheduled silent music") { model.music.state.value.isPlaying }
        val stale = model.session.value!!
        onMain {
            model.stopPlayback()
            model.imageReady(stale.token, stale.photo.id)
            model.imageFailed(stale.token, stale.photo.id)
            model.setForeground(false)
            model.setForeground(true)
        }
        holdFor(1100)
        assertTrue(model.displayArmed.value)
        assertNull("The current stopped occurrence must stay suppressed", model.session.value)
        assertFalse(model.music.state.value.isPlaying)

        // Explicit manual playback is allowed, and a schedule change must not interrupt it.
        onMain { model.playAlbum(album.id) }
        await("manual playback after stopping plan") { model.session.value?.let { it.ready && it.occurrenceKey == null } == true }
        await("manual silent music") { model.music.state.value.isPlaying }
        val saved = model.library.value.schedules.single { it.id == schedule.id }
        runBlocking { withTimeout(15_000) { model.repository.saveSchedule(saved.copy(enabled = false)) } }
        await("schedule disabled") { model.library.value.schedules.find { it.id == schedule.id }?.enabled == false }
        holdFor(500)
        assertNotNull(model.session.value)
        assertNull(model.session.value!!.occurrenceKey)
        assertTrue(model.music.state.value.isPlaying)
    }

    @Test fun nonLoopingQueueStopsMusicAndCanRestartFromItsFirstPhoto() {
        val album = createFixtureAlbum(intervalSeconds = 1, loop = false)
        val firstId = model.library.value.photosFor(album.id).minBy { it.name }.id
        onMain { model.playAlbum(album.id) }
        await("silent music before natural slideshow end") { model.music.state.value.isPlaying }
        await("non-looping queue end", 10_000) { model.session.value?.pauseReason == PauseReason.END && !model.music.state.value.isPlaying }
        assertFalse(model.music.state.value.isPlaying)
        val oldToken = model.session.value!!.token
        onMain { model.togglePause() }
        await("restart loads first photo") { model.session.value?.let { it.ready && !it.paused && it.photo.id == firstId && it.token != oldToken } == true }
        await("music resumes after queue restart") { model.music.state.value.isPlaying }
        assertEquals(0f, model.music.state.value.volume, 0f)
    }

    @Test fun realOneMinuteCountdownStopsBothPlayersAndLateEventsCannotRestartThem() {
        val album = createFixtureAlbum(intervalSeconds = 30)
        onMain { model.playAlbum(album.id) }
        await("photo before real countdown") { model.session.value?.ready == true }
        await("silent music before real countdown") { model.music.state.value.isPlaying }
        assertEquals(0f, model.music.state.value.volume, 0f)
        val stale = model.session.value!!
        var startedAt = 0L
        onMain {
            startedAt = SystemClock.elapsedRealtime()
            model.setSleepMinutes(1)
        }
        await("real countdown armed") { model.sleepRemaining.value?.let { it in 1L..60L } == true }
        await("real one-minute expiry stops images and music", 70_000) {
            model.session.value == null && !model.music.state.value.isPlaying
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        assertTrue("The one-minute timer must not stop early: elapsed=$elapsed", elapsed >= 59_000)
        assertTrue("The timer must stop within the requested tolerance: elapsed=$elapsed", elapsed <= 70_000)
        assertNull(model.sleepRemaining.value)
        onMain {
            model.imageReady(stale.token, stale.photo.id)
            model.imageFailed(stale.token, stale.photo.id)
            model.music.resumeForSession()
            model.music.next()
        }
        holdFor(800)
        assertNull(model.session.value)
        assertFalse(model.music.state.value.isPlaying)
    }

    private fun createFixtureAlbum(intervalSeconds: Int, loop: Boolean = true): Album {
        val id = runBlocking { withTimeout(15_000) { model.repository.createAlbum("合成素材测试-${UUID.randomUUID().toString().take(6)}") } }
        createdAlbums += id
        runBlocking {
            withTimeout(15_000) {
                model.repository.addSource(id, TestDocumentsProvider.PHOTOS_TREE.toString(), SourceKind.PHOTO, false)
                model.repository.addSource(id, TestDocumentsProvider.MUSIC_TREE.toString(), SourceKind.MUSIC, false)
            }
        }
        await("fixture indexes") {
            val album = model.library.value.albums.find { it.id == id }
            album != null && model.library.value.photosFor(id).size == 3 && model.library.value.tracksFor(album).size == 2
        }
        val configured = model.library.value.albums.single { it.id == id }.copy(
            intervalSeconds = intervalSeconds, transitionMillis = 0, loop = loop, musicEnabled = true, musicVolume = 0f,
        )
        runBlocking { withTimeout(15_000) { model.repository.updateAlbum(configured) } }
        await("zero-gain configuration") { model.library.value.albums.find { it.id == id } == configured }
        return configured
    }

    private fun onMain(block: () -> Unit) {
        scenario!!.onActivity { block() }
    }

    private fun saveSyntheticScreenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(180)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Unable to capture the synthetic fixture UI" }
        val parent = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "screenshots")
        check(parent.isDirectory || parent.mkdirs()) { "Unable to create screenshot folder" }
        File(parent, name).outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to save screenshot" }
        }
        bitmap.recycle()
    }

    private fun await(label: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) fail("Timed out waiting for $label")
            SystemClock.sleep(30)
        }
    }

    private fun holdFor(durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
    }
}
