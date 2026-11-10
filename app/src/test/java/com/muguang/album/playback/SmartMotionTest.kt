package com.muguang.album.playback

import com.muguang.album.core.*
import org.junit.Assert.*
import org.junit.Test

class SmartMotionTest {
    @Test fun nearAutomaticPointRemainsStableButLargeChangeWins() {
        val old = FocusPoint(.5f, .5f)
        assertEquals(old, stableAutoFocus(old, FocusPoint(.52f, .51f), 4000, 3000))
        assertEquals(old, stableAutoFocus(old, FocusPoint(.55f, .55f), 4000, 3000))
        assertEquals(FocusPoint(.8f, .7f), stableAutoFocus(old, FocusPoint(.8f, .7f), 4000, 3000))
        assertNull(stableAutoFocus(old, null, 4000, 3000))
    }
    @Test fun focusDistanceUsesImageAspectRatio() {
        val old = FocusPoint(.5f, .5f)
        assertEquals(old, stableAutoFocus(old, FocusPoint(.5f, .7f), 10000, 1000))
        assertNotEquals(old, stableAutoFocus(old, FocusPoint(.7f, .5f), 10000, 1000))
    }
    @Test fun cropFramesNeverExposeEdgesWhenFillIsSafe() {
        val path = SmartMotion.plan(4000f, 3000f, 1200f, 800f, MotionMode.CROP_ZOOM, FocusPoint(.98f, .02f), null, .2f, .6f)
        for (i in 0..100) {
            val f = path.frame(i / 100f)
            assertTrue(f.x <= .001f && f.y <= .001f)
            assertTrue(f.x + 4000*f.scale >= 1199.99f && f.y + 3000*f.scale >= 799.99f)
        }
    }
    @Test fun panoramaDoesNotForceExcessiveCropping() {
        val path = SmartMotion.plan(12000f, 1000f, 800f, 1200f, MotionMode.CROP_ZOOM, FocusPoint(.8f, .5f), null, .5f, .3f)
        assertTrue(path.end.scale < 1.2f)
        val retained = minOf(1f,800/(12000*path.end.scale))*minOf(1f,1200/(1000*path.end.scale))
        assertTrue(retained >= .69999f)
        assertEquals((1200 - 1000*path.end.scale)/2, path.end.y, .001f)
    }
    @Test fun fitModeStartsWithCompleteImage() {
        val path = SmartMotion.plan(4000f, 3000f, 1200f, 800f, MotionMode.FIT_ZOOM, FocusPoint(.9f, .1f), null, .2f, .5f)
        assertEquals(800f/3000, path.start.scale, .00001f)
        assertTrue(path.start.x >= -.001f && path.start.y >= -.001f)
        assertTrue(path.end.scale >= path.start.scale)
    }
    @Test fun majorPeopleStayInsideScreen() {
        val region = SafeRegion(.1f, .2f, .8f, .6f)
        val path = SmartMotion.plan(4000f, 3000f, 1200f, 800f, MotionMode.CROP_ZOOM, FocusPoint(.8f, .25f), region, .5f, .8f)
        for (i in 0..100) {
            val f = path.frame(i/100f)
            assertTrue(f.x + region.x*4000*f.scale >= -.01f)
            assertTrue(f.x + (region.x+region.width)*4000*f.scale <= 1200.01f)
            assertTrue(f.y + region.y*3000*f.scale >= -.01f)
            assertTrue(f.y + (region.y+region.height)*3000*f.scale <= 800.01f)
        }
    }
    @Test fun manualFocusAndCategoryOverrideAutomaticValues() {
        val metadata = PhotoMetadata(autoX = .1f, autoY = .1f, manualX = .7f, manualY = .8f, autoCategory = "REAL_OTHER", manualCategory = "ART")
        assertEquals(FocusPoint(.7f, .8f), metadata.effectiveFocus)
        assertEquals(PhotoCategory.ART, metadata.category)
        assertEquals(metadata.autoFocus, metadata.copy(manualX = null, manualY = null).effectiveFocus)
    }
    @Test fun variedAspectRatiosAlwaysRespectAreaBudgetAndSubjectBounds() {
        val random = java.util.Random(17)
        repeat(1000) {
            val iw = 100f + random.nextInt(20000); val ih = 100f + random.nextInt(20000)
            val vw = 240f + random.nextInt(2500); val vh = 240f + random.nextInt(2500)
            val x = random.nextFloat()*.8f; val y = random.nextFloat()*.8f
            val region = SafeRegion(x,y,(1-x)*(.1f+random.nextFloat()*.9f),(1-y)*(.1f+random.nextFloat()*.9f))
            val budget = random.nextFloat()*.85f
            val path = SmartMotion.plan(iw,ih,vw,vh,MotionMode.CROP_ZOOM,FocusPoint(random.nextFloat(),random.nextFloat()),region,.5f,budget)
            val f = path.end
            val retained = minOf(1f,vw/(iw*f.scale))*minOf(1f,vh/(ih*f.scale))
            assertTrue(retained >= 1-budget-.00001f)
            assertTrue(f.x + region.x*iw*f.scale >= -.01f)
            assertTrue(f.x + (region.x+region.width)*iw*f.scale <= vw+.01f)
            assertTrue(f.y + region.y*ih*f.scale >= -.01f)
            assertTrue(f.y + (region.y+region.height)*ih*f.scale <= vh+.01f)
        }
    }
    @Test fun categoryUnionDeduplicatesOverlappingSourcesAndExcludesTrash() {
        val album = Album("a", "a", playbackCategories = "ART,REAL_PERSON")
        val source = FolderSource("s", "a", "content://a", "a", SourceKind.PHOTO)
        val art = Photo("one", "s", "content://one", "one", metadata = PhotoMetadata(autoCategory = "ART"))
        val state = LibraryState(albums = listOf(album), sources = listOf(source), photos = listOf(art, art,
            art.copy(id = "two", metadata = PhotoMetadata(autoCategory = "REAL_OTHER")), art.copy(id = "gone", trashedAt = 1)))
        assertEquals(listOf("one"), state.playbackPhotosFor(album).map { it.id })
    }
}
