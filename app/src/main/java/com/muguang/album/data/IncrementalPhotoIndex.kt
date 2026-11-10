package com.muguang.album.data

import com.muguang.album.core.PhotoMetadata

/** A complete directory listing is committed atomically. Missing records retain
 * their first trash timestamp, while a readable record restores its annotations. */
internal fun reconcilePhotos(old: List<PhotoEntity>, incoming: List<PhotoEntity>, now: Long): List<PhotoEntity> {
    val previous = old.associateBy { it.id }
    val present = incoming.map { photo ->
        val stored = previous[photo.id]
        val unchanged = stored != null && stored.modifiedAt == photo.modifiedAt && stored.fileSize == photo.fileSize
        val interruptedRestore = stored?.trashedAt != null && stored.metadata.taskState in setOf("FAILED", "PROCESSING")
        photo.copy(metadata = if (unchanged && !interruptedRestore) stored!!.metadata else stored?.metadata?.copy(taskState = "QUEUED", error = null) ?: PhotoMetadata())
    }
    val ids = present.map { it.id }.toSet()
    return present + old.filter { it.id !in ids }.map {
        it.copy(trashedAt = it.trashedAt ?: now, unavailableReason = "文件已移除或路径失效")
    }
}
