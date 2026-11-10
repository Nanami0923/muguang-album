package com.muguang.album.data

import com.muguang.album.core.PhotoMetadata
import org.junit.Assert.*
import org.junit.Test

class IncrementalPhotoIndexTest {
    private val cached = PhotoMetadata(autoCategory="REAL_PERSON", autoX=.4f,autoY=.3f,manualX=.7f,manualY=.2f,manualCategory="ART",taskState="DONE")
    private fun photo(id: String) = PhotoEntity(id,"s","content://test/$id","$id.jpg",10,100,metadata=cached)
    @Test fun unchangedScanPreservesCompletedCacheWhileNewPhotoIsQueued() {
        val incoming = listOf(photo("old").copy(metadata=PhotoMetadata()), photo("new").copy(metadata=PhotoMetadata()))
        val result = reconcilePhotos(listOf(photo("old")),incoming,1000)
        assertEquals(cached,result.first().metadata)
        assertEquals("QUEUED",result.last().metadata.taskState)
    }
    @Test fun sameNameChangedContentQueuesAnalysisAndRetainsManualEdits() {
        val old=photo("p")
        val result = reconcilePhotos(listOf(old),listOf(old.copy(modifiedAt=20,fileSize=200,metadata=PhotoMetadata())),1000).single()
        assertEquals("QUEUED",result.metadata.taskState)
        assertEquals(.7f,result.metadata.manualX)
        assertEquals("ART",result.metadata.manualCategory)
    }
    @Test fun repeatedMissingScansDoNotExtendTrashLifetime() {
        val old=photo("p")
        val first=reconcilePhotos(listOf(old),emptyList(),1000)
        val second=reconcilePhotos(first,emptyList(),2000).single()
        assertEquals(1000L,second.trashedAt)
        assertEquals(cached,second.metadata)
    }
    @Test fun restoredPathRecoversFocusAndClearsTrash() {
        val old=photo("p").copy(trashedAt=1000,unavailableReason="路径失效")
        val restored=reconcilePhotos(listOf(old),listOf(photo("p").copy(metadata=PhotoMetadata())),2000).single()
        assertNull(restored.trashedAt)
        assertNull(restored.unavailableReason)
        assertEquals(cached,restored.metadata)
    }
    @Test fun restoringAPathRequeuesFailedOrInterruptedAnalysis() {
        for (task in listOf("FAILED", "PROCESSING")) {
            val old = photo("p").copy(trashedAt=1000,metadata=cached.copy(taskState=task))
            val restored = reconcilePhotos(listOf(old),listOf(photo("p").copy(metadata=PhotoMetadata())),2000).single()
            assertEquals("QUEUED",restored.metadata.taskState)
            assertEquals(cached.manualFocus,restored.metadata.manualFocus)
        }
    }
}
