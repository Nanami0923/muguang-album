package com.muguang.album.data

import com.muguang.album.core.PlaySchedule
import com.muguang.album.core.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageRulesTest {
    private fun schedule(start: Int, end: Int, days: Int = 127) = PlaySchedule(
        id = "test", name = "测试", albumId = "family", startMinute = start, endMinute = end, daysMask = days,
    )

    @Test fun touchingBoundariesAreNotOverlapping() {
        assertFalse(LocalAlbumRepository.weeklyOverlap(schedule(480, 720), schedule(720, 1080)))
    }

    @Test fun overlappingWindowsAreRejected() {
        assertTrue(LocalAlbumRepository.weeklyOverlap(schedule(480, 721), schedule(720, 1080)))
    }

    @Test fun fridayOvernightIncludesSaturdayMorning() {
        val friday = 1 shl 4
        val saturday = 1 shl 5
        assertTrue(LocalAlbumRepository.weeklyOverlap(schedule(22 * 60, 7 * 60, friday), schedule(6 * 60, 8 * 60, saturday)))
        assertFalse(LocalAlbumRepository.weeklyOverlap(schedule(22 * 60, 7 * 60, friday), schedule(7 * 60, 8 * 60, saturday)))
    }

    @Test fun sundayOvernightWrapsToMonday() {
        assertTrue(LocalAlbumRepository.weeklyOverlap(schedule(22 * 60, 60, 1 shl 6), schedule(30, 120, 1)))
        assertFalse(LocalAlbumRepository.weeklyOverlap(schedule(22 * 60, 60, 1 shl 6), schedule(60, 120, 1)))
    }

    @Test fun sameClockTimesOnDifferentDaysDoNotOverlap() {
        assertFalse(LocalAlbumRepository.weeklyOverlap(schedule(480, 1080, 1), schedule(480, 1080, 2)))
    }

    @Test fun pictureAndMusicMimeTypesStaySeparate() {
        assertEquals(SourceKind.PHOTO, SafScanner.supportedKind("照片.JPG", "image/jpeg"))
        assertEquals(SourceKind.MUSIC, SafScanner.supportedKind("配乐.MP3", "audio/mpeg"))
        assertNull(SafScanner.supportedKind("视频.jpg", "video/mp4"))
        assertNull(SafScanner.supportedKind("icon.svg", "image/svg+xml"))
    }

    @Test fun genericMimeUsesKnownExtensionButUnknownFilesAreIgnored() {
        assertEquals(SourceKind.PHOTO, SafScanner.supportedKind("照片.JPEG", "application/octet-stream"))
        assertEquals(SourceKind.MUSIC, SafScanner.supportedKind("曲目.FLAC", ""))
        assertNull(SafScanner.supportedKind("readme.txt", "text/plain"))
        assertNull(SafScanner.supportedKind("archive.zip", "application/zip"))
    }
}
