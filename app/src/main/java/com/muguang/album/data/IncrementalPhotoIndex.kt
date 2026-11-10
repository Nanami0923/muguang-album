package com.muguang.album.data

import com.muguang.album.core.PhotoMetadata
import com.muguang.album.core.USER_REMOVED_PHOTO

/** A complete directory listing is committed atomically. Missing records retain
 * their first trash timestamp, while a readable record restores its annotations. */
internal fun reconcilePhotos(old: List<PhotoEntity>, incoming: List<PhotoEntity>, now: Long): List<PhotoEntity> {
    val previous = old.associateBy { it.id }
    val present = incoming.map { photo ->
        val stored = previous[photo.id]
        // A user removal is an album exclusion, not a temporarily missing file.
        if (stored?.unavailableReason == USER_REMOVED_PHOTO) return@map stored.copy(uri = photo.uri, name = photo.name)
        val unchanged = stored != null && stored.modifiedAt == photo.modifiedAt && stored.fileSize == photo.fileSize
        val interruptedRestore = stored?.trashedAt != null && stored.metadata.taskState in setOf("FAILED", "PROCESSING")
        photo.copy(metadata = if (unchanged && !interruptedRestore) stored!!.metadata else stored?.metadata?.copy(taskState = "QUEUED", error = null, generation = stored.metadata.generation + 1, aiStatus = "NOT_REQUIRED") ?: PhotoMetadata())
    }
    val ids = present.map { it.id }.toSet()
    return present + old.filter { it.id !in ids }.map {
        it.copy(trashedAt = it.trashedAt ?: now, unavailableReason = if (it.unavailableReason == USER_REMOVED_PHOTO) USER_REMOVED_PHOTO else "文件已移除或路径失效")
    }
}
