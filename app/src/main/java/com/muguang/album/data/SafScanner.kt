package com.muguang.album.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.muguang.album.core.FolderSource
import com.muguang.album.core.SourceKind
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Metadata only: never opens or decodes the original image/audio stream. */
internal class SafScanner(private val resolver: ContentResolver) {
    data class Result(val photos: List<PhotoEntity>, val tracks: List<TrackEntity>)

    fun directoryName(tree: Uri): String {
        require(isTreeUriCompat(tree)) {
            "请选择系统文件夹选择器中的本地文件夹"
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cursor = resolver.query(root, arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ), null, null, null) ?: throw IllegalStateException("暂时无法读取文件夹，请检查存储设备是否已连接")
        return cursor.use {
            check(it.moveToFirst()) { "文件夹不存在或存储设备未连接" }
            val mime = it.text(DocumentsContract.Document.COLUMN_MIME_TYPE)
            check(mime == DocumentsContract.Document.MIME_TYPE_DIR) { "请选择文件夹，不是单个文件" }
            it.text(DocumentsContract.Document.COLUMN_DISPLAY_NAME).ifBlank { "本地文件夹" }
        }
    }

    suspend fun scan(source: FolderSource): Result {
        val tree = Uri.parse(source.uri)
        directoryName(tree)
        val authority = checkNotNull(tree.authority) { "文件夹地址无效，请重新选择" }
        val queue = ArrayDeque<String>()
        queue.add(DocumentsContract.getTreeDocumentId(tree))
        val visitedDirectories = HashSet<String>()
        val visitedDocuments = HashSet<String>()
        val photos = ArrayList<PhotoEntity>()
        val tracks = ArrayList<TrackEntity>()
        val context = currentCoroutineContext()

        while (queue.isNotEmpty()) {
            context.ensureActive()
            val parent = queue.removeFirst()
            if (!visitedDirectories.add(parent)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val cursor = resolver.query(children, PROJECTION, null, null, null)
                ?: throw IllegalStateException("无法完整扫描文件夹，已保留上次结果，请重新连接存储设备后重试")
            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (it.moveToNext()) {
                    context.ensureActive()
                    val documentId = it.getString(idColumn) ?: continue
                    val name = it.getString(nameColumn).orEmpty()
                    if (name.startsWith('.')) continue
                    val mime = it.getString(mimeColumn).orEmpty().lowercase(Locale.ROOT)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (source.recursive) queue.add(documentId)
                        continue
                    }
                    if (!visitedDocuments.add(documentId)) continue
                    val kind = supportedKind(name, mime) ?: continue
                    if (kind != source.kind) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString()
                    val stableId = "$authority|$documentId"
                    if (kind == SourceKind.PHOTO) {
                        photos += PhotoEntity(
                            id = stableId,
                            sourceId = source.id,
                            uri = uri,
                            name = name.ifBlank { "未命名图片" },
                            modifiedAt = if (modifiedColumn >= 0 && !it.isNull(modifiedColumn)) it.getLong(modifiedColumn) else 0L,
                        )
                    } else {
                        tracks += TrackEntity(stableId, source.id, uri, name.ifBlank { "未命名音乐" })
                    }
                }
            }
        }
        context.ensureActive()
        return Result(photos, tracks)
    }

    private fun Cursor.text(name: String): String {
        val index = getColumnIndex(name)
        return if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""
    }

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heif", "heic", "avif")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus")

        // DocumentsContract.isTreeUri was added only in API 24. Tree URIs themselves
        // have existed since API 21 and use /tree/<opaque document ID>/... .
        internal fun isTreeUriCompat(uri: Uri): Boolean {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) return false
            val segments = uri.pathSegments
            return segments.size >= 2 && segments[0] == "tree" && segments[1].isNotBlank()
        }

        /** Kept separate from decoding: unsupported device codecs are skipped by the player. */
        internal fun supportedKind(name: String, mime: String): SourceKind? {
            val normalizedMime = mime.lowercase(Locale.ROOT)
            if (normalizedMime.startsWith("video/")) return null
            if (normalizedMime.startsWith("image/") && normalizedMime != "image/svg+xml") return SourceKind.PHOTO
            if (normalizedMime.startsWith("audio/")) return SourceKind.MUSIC
            if (normalizedMime == "image/svg+xml") return null
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (extension) {
                in IMAGE_EXTENSIONS -> SourceKind.PHOTO
                in AUDIO_EXTENSIONS -> SourceKind.MUSIC
                else -> null
            }
        }
    }
}
