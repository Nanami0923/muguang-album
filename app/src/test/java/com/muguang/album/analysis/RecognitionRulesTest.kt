package com.muguang.album.analysis

import com.muguang.album.analysis.ai.*
import com.muguang.album.core.*
import org.junit.Assert.*
import org.junit.Test

class RecognitionRulesTest {
    @Test fun mergedArtProbabilityDoesNotInventAnimeSubtype() {
        val result=MediumScores(listOf(.48,.05,.47))
        assertEquals("ART",result.coarse)
        assertEquals("UNKNOWN",result.medium)
    }
    @Test fun routingKeepsSceneryAndPeopleIndependent() {
        assertEquals("ANIME_SCENERY",categoryFor("ANIME_ILLUSTRATION","SCENERY"))
        assertEquals("ANIME_PERSON",categoryFor("ANIME_ILLUSTRATION","PERSON"))
        assertEquals("ANIME_OTHER",categoryFor("ANIME_ILLUSTRATION","OTHER"))
        assertTrue(PhotoCategory.ART.includes(PhotoCategory.ANIME_SCENERY))
        assertFalse(PhotoCategory.ART.includes(PhotoCategory.REAL_OTHER))
    }
    @Test fun compareRequiresSameRegionAndNearbyCenter() {
        assertTrue(subjectAgreement(SafeRegion(.2f,.2f,.3f,.4f),SafeRegion(.21f,.2f,.3f,.4f),1000,800))
        assertFalse(subjectAgreement(SafeRegion(.1f,.1f,.1f,.1f),SafeRegion(.7f,.7f,.1f,.1f),1000,800))
    }
    private val whole="""{"schemaVersion":"0.3.0","medium":"ANIME_ILLUSTRATION","composition":"SCENERY","people":"MULTIPLE","presentation":"WHOLE_VIEW","focus":null,"safeRegion":null,"subject":"山水全景","reason":"小人物不改变整体构图"}"""
    @Test fun wholeViewIsSuccessfulWithoutFocusAndRetainsVisiblePeople() {
        val result=AiResultParser.parse(whole).applyTo(PhotoMetadata(taskState="DONE"))
        assertTrue(result.wholeView); assertNull(result.effectiveFocus)
        assertEquals("SUCCEEDED",result.aiStatus)
        assertEquals(PhotoCategory.ANIME_SCENERY,result.category)
        assertEquals("MULTIPLE",result.people)
        assertEquals("整体展示",result.displayStatus)
    }
    @Test fun manualChangesWinOverArrivingAiResultPerField() {
        val latest=PhotoMetadata(manualX=.8f,manualY=.3f,manualCategory="DESIGN",generation=8)
        val result=preserveManual(AiResultParser.parse(whole).applyTo(PhotoMetadata()),latest)
        assertEquals(FocusPoint(.8f,.3f),result.effectiveFocus)
        assertEquals(PhotoCategory.DESIGN,result.category)
        assertEquals(8L,result.generation)
        assertFalse(result.wholeView)
        assertTrue(result.copy(manualPresentation="WHOLE_VIEW").wholeView)
    }
    @Test fun invalidAiResultsAreRejectedInsteadOfCreatingCenterPoint() {
        val bad=listOf(whole.replace("WHOLE_VIEW","SUBJECT_FOCUS"),whole.replace("SCENERY","LANDSCAPE"),whole.replace("0.3.0","0.2.0"),whole.replace("\"focus\":null,",""),whole+" extra")
        for(text in bad) assertTrue(runCatching { AiResultParser.parse(text) }.isFailure)
        val focused="""{"schemaVersion":"0.3.0","medium":"REAL","composition":"PERSON","people":"SINGLE","presentation":"SUBJECT_FOCUS","focus":{"x":0.9,"y":0.9},"safeRegion":{"x":0.1,"y":0.1,"width":0.3,"height":0.3},"subject":"人","reason":"测试"}"""
        assertTrue(runCatching { AiResultParser.parse(focused) }.isFailure)
        assertTrue(runCatching { AiResultParser.parse(focused.replace("0.9","\"0.2\"")) }.isFailure)
    }
    @Test fun cacheKeyChangesWithImageProviderModelAndPromptContract() {
        val config=AiSettings(endpoint="https://example.test/chat/completions",model="vision",hasKey=true)
        val key=aiCacheKey("image-a",config)
        assertEquals(key,aiCacheKey("image-a",config.copy(enabled=true,sent=20)))
        assertNotEquals(key,aiCacheKey("image-b",config))
        assertNotEquals(key,aiCacheKey("image-a",config.copy(model="other")))
        assertNotEquals(key,aiCacheKey("image-a",config.copy(endpoint="https://other.test/chat/completions")))
    }
    @Test fun oldBroadManualCategoryAndFilterRemainUsable() {
        val metadata=PhotoMetadata(manualCategory="ART",autoCategory="ANIME_SCENERY")
        assertEquals(PhotoCategory.ART,metadata.category)
        val album=Album("a","test",playbackCategories="ART")
        val library=LibraryState(sources=listOf(FolderSource("s","a","test","test",SourceKind.PHOTO)),photos=listOf(Photo("p","s","test","test",metadata=PhotoMetadata(autoCategory="ANIME_PERSON"))))
        assertEquals(1,library.playbackPhotosFor(album).size)
    }
}
