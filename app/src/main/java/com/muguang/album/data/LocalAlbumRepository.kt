package com.muguang.album.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.withTransaction
import com.muguang.album.core.Album
import com.muguang.album.core.AlbumRepository
import com.muguang.album.core.AppPreferences
import com.muguang.album.core.FolderSource
import com.muguang.album.core.LibraryState
import com.muguang.album.core.MusicOrder
import com.muguang.album.core.MusicTrack
import com.muguang.album.core.Photo
import com.muguang.album.core.PhotoScale
import com.muguang.album.core.PlaySchedule
import com.muguang.album.core.SortOrder
import com.muguang.album.core.SourceKind
import com.muguang.album.core.FocusPoint
import com.muguang.album.core.PhotoCategory
import com.muguang.album.core.MotionMode
import com.muguang.album.core.PhotoMetadata
import com.muguang.album.analysis.OfflinePhotoAnalyzer
import com.muguang.album.analysis.ai.*
import com.muguang.album.core.AiSettings
import com.muguang.album.core.preserveManual
import com.muguang.album.core.USER_REMOVED_PHOTO
import com.muguang.album.playback.stableAutoFocus
import com.muguang.album.playback.TRASH_RETENTION_MILLIS
import kotlinx.coroutines.delay
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.albumPreferences by preferencesDataStore(name = "album_preferences")

/** The only writer of app metadata. Original files are never modified or deleted. */
class LocalAlbumRepository(context: Context, scope: CoroutineScope) : AlbumRepository {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val database = Room.databaseBuilder(appContext, AlbumDatabase::class.java, "muguang-album.db").addMigrations(SMART_MIGRATION, RECOGNITION_MIGRATION, PLAYBACK_MIGRATION).build()
    private val dao = database.dao()
    private val scanner = SafScanner(resolver)
    private val mutationMutex = Mutex()
    private val initializeMutex = Mutex()
    private val scanMutexes = ConcurrentHashMap<String, Mutex>()
    private val scanning = MutableStateFlow<Set<String>>(emptySet())
    private val initialized = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(LibraryState())
    override val state: StateFlow<LibraryState> = mutableState.asStateFlow()
    @Volatile private var foreground = false
    @Volatile private var playing = false
    @Volatile private var playbackBackoffUntil = 0L
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "photo-analysis")
    }.asCoroutineDispatcher()
    private val analyzer by lazy { OfflinePhotoAnalyzer(appContext) }
    private val aiConfig = AiConfiguration(appContext)
    private val aiRequestMutex = Mutex()
    private val aiRecognizer: AiPhotoRecognizer = ChatCompletionsPhotoRecognizer()

    override fun setForeground(value: Boolean) { foreground = value }
    override fun setPlaying(value: Boolean) { playing = value }
    override fun reportPlaybackFrame(millis: Long) {
        if (millis > 80) playbackBackoffUntil = android.os.SystemClock.elapsedRealtime() + 5000
    }

    init {
        val storedLibrary = combine(
            dao.observeAlbums(), dao.observeSources(), dao.observePhotos(), dao.observeTracks(), dao.observeSchedules(),
        ) { albums, sources, photos, tracks, schedules ->
            LibraryState(
                albums = albums.map { it.model() },
                sources = sources.map { it.model() },
                photos = photos.map { Photo(it.id, it.sourceId, it.uri, it.name, it.modifiedAt, it.fileSize, it.trashedAt, it.unavailableReason, it.metadata) },
                tracks = tracks.map { MusicTrack(it.id, it.sourceId, it.uri, it.name) },
                schedules = schedules.map { it.model() },
            )
        }
        val preferences = appContext.albumPreferences.data.catch { exception ->
            if (exception is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw exception
        }.map {
            AppPreferences(
                dynamicColor = it[DYNAMIC_COLOR] ?: true,
                keepStandbyAwake = it[KEEP_STANDBY_AWAKE] ?: true,
                showClock = it[SHOW_CLOCK] ?: false,
                standbyBrightness = (it[STANDBY_BRIGHTNESS] ?: .04f).coerceIn(.01f, 1f),
                remindersEnabled = it[REMINDERS_ENABLED] ?: false,
                galleryColumns = (it[GALLERY_COLUMNS] ?: 2).coerceIn(1, 2),
            )
        }
        scope.launch(Dispatchers.IO) {
            combine(storedLibrary, preferences, scanning, initialized, aiConfig.state) { library, prefs, scanningIds, ready, ai ->
                library.copy(preferences = prefs, scanningSourceIds = scanningIds, initialized = ready, ai = ai)
            }.collect { mutableState.value = it }
        }
        scope.launch(Dispatchers.IO) {
            var lastPurge = 0L
            while (true) {
                if (!foreground || !initialized.value) { delay(500); continue }
                if (playing && android.os.SystemClock.elapsedRealtime() < playbackBackoffUntil) { delay(500); continue }
                if (android.os.Build.VERSION.SDK_INT >= 29 &&
                    (appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE) { delay(2000); continue }
                if (System.currentTimeMillis() - lastPurge > 60_000) {
                    mutationMutex.withLock { dao.purgeTrash(System.currentTimeMillis() - TRASH_RETENTION_MILLIS) }
                    lastPurge = System.currentTimeMillis()
                }
                val photo = mutationMutex.withLock {
                    dao.nextAnalysis()?.also { dao.insertPhotos(listOf(it.copy(metadata = it.metadata.copy(taskState = "PROCESSING")))) }
                }
                if (photo == null) { delay(700); continue }
                val cached = if (photo.metadata.classificationStatus == "REANALYZE") null else dao.photosById(photo.id).firstOrNull { it.sourceId != photo.sourceId && it.trashedAt == null &&
                    it.modifiedAt == photo.modifiedAt && it.fileSize == photo.fileSize && it.metadata.taskState == "DONE" && it.metadata.modelVersion == OfflinePhotoAnalyzer.VERSION }
                var missing = false
                val result = try { cached?.metadata ?: withContext(inferenceDispatcher) { analyzer.analyze(photo.uri) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    missing = error is SecurityException || error is java.io.FileNotFoundException
                    PhotoMetadata(taskState = "FAILED", focusStatus = "ERROR", classificationStatus = "ERROR", error = error.message?.take(200) ?: "识别失败")
                }
                catch (error: LinkageError) { PhotoMetadata(taskState = "FAILED", focusStatus = "ERROR", classificationStatus = "ERROR", error = "设备不支持当前离线识别组件") }
                mutationMutex.withLock {
                    val latest = dao.photo(photo.sourceId, photo.id)
                    if (latest != null && latest.trashedAt == null && latest.modifiedAt == photo.modifiedAt && latest.fileSize == photo.fileSize && latest.metadata.generation == photo.metadata.generation && latest.metadata.taskState == "PROCESSING") {
                        val manual = latest.metadata
                        val point = if(manual.modelVersion==result.modelVersion && manual.classificationSource!="AI") stableAutoFocus(manual.autoFocus, result.autoFocus, result.orientedWidth, result.orientedHeight) else result.autoFocus
                        val source = dao.source(photo.sourceId)
                        val merged = if (!foreground || source?.analysisState == "CANCELLED") manual.copy(taskState = if (source?.analysisState == "CANCELLED") "CANCELLED" else "QUEUED")
                        else result.copy(autoX = point?.x, autoY = point?.y, manualX = manual.manualX, manualY = manual.manualY,
                            manualCategory = manual.manualCategory, customMotionMode = manual.customMotionMode,
                            focusStatus = when { manual.manualFocus != null -> "MANUAL"; result.focusStatus != "MANUAL" -> result.focusStatus;
                                point != null -> "DETECTED"; else -> "NEEDS_MANUAL" })
                        val retained = if(manual.classificationSource=="AI" && manual.fingerprint.isNotEmpty() && manual.fingerprint==result.fingerprint && merged.taskState=="DONE")
                            manual.copy(taskState="DONE",modelVersion=result.modelVersion) else merged
                        val final = preserveManual(retained,manual).let { fresh ->
                            if(fresh.aiStatus=="WAITING_CONFIG" && aiConfig.state.value.configured && aiConfig.state.value.enabled) fresh.copy(aiStatus="QUEUED") else fresh
                        }
                        dao.insertPhotos(listOf(latest.copy(metadata = final, trashedAt = if (missing) System.currentTimeMillis() else latest.trashedAt,
                            unavailableReason = if (missing) "文件不存在或授权失效" else latest.unavailableReason)))
                    }
                }
                // Yield between pictures; playback never waits for model inference.
                delay(if (playing) 1500 else 100)
            }
        }
        scope.launch(Dispatchers.IO) { runAiQueue() }
    }

    private suspend fun runAiQueue() {
        while(true) {
            val config=aiConfig.state.value
            if(!foreground || !initialized.value || !config.enabled || !config.configured || config.paused) { delay(700); continue }
            if(config.sent>=config.maxImagesPerRun) { aiConfig.status("本轮数量已用完，开始新一轮可继续",true); continue }
            val photo=mutationMutex.withLock {
                dao.queueConfiguredAi()
                dao.nextAi()?.let { it.copy(metadata=it.metadata.copy(aiStatus="PROCESSING",aiError=null)).also { claimed -> dao.insertPhotos(listOf(claimed)) } }
            }
            if(photo==null) { delay(700); continue }
            processAi(photo,config)
            delay(150)
        }
    }

    private fun aiImage(uri:String,maxSide:Int):ByteArray {
        val decoded=com.muguang.album.analysis.decodeOriented(appContext,uri,maxSide)
        val bitmap=decoded.bitmap
        try {
            val factor=minOf(1f,maxSide.toFloat()/maxOf(bitmap.width,bitmap.height))
            val output=android.graphics.Bitmap.createBitmap((bitmap.width*factor).toInt().coerceAtLeast(1),(bitmap.height*factor).toInt().coerceAtLeast(1),android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val canvas=android.graphics.Canvas(output); canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(bitmap,null,android.graphics.Rect(0,0,output.width,output.height),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                return java.io.ByteArrayOutputStream().use { stream -> check(output.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,stream)); stream.toByteArray() }
            } finally { output.recycle() }
        } finally { bitmap.recycle() }
    }

    private suspend fun processAi(photo:PhotoEntity,config:AiSettings) {
        val key=aiCacheKey(photo.metadata.fingerprint,config)
        try {
            check(photo.metadata.fingerprint.isNotBlank()) { "请先完成本地识别" }
            if(analyzer.fingerprint(photo.uri)!=photo.metadata.fingerprint) { requeueChanged(photo); return }
            val cached=dao.aiCache(key)
            val response=if(cached!=null) AiResponse(AiResultParser.parse(cached.result),cached.result,cached.requestId,cached.inputTokens,cached.outputTokens) else {
                val image=aiImage(photo.uri,config.maxImageSide)
                if(analyzer.fingerprint(photo.uri)!=photo.metadata.fingerprint) { requeueChanged(photo); return }
                aiRequestMutex.withLock {
                    val permitted = mutationMutex.withLock {
                        val latest = dao.photo(photo.sourceId, photo.id)
                        if (latest == null || latest.trashedAt != null || latest.metadata.generation != photo.metadata.generation || latest.metadata.aiStatus != "PROCESSING") false
                        else if (!foreground || !aiConfig.reserve()) {
                            dao.insertPhotos(listOf(latest.copy(metadata = latest.metadata.copy(aiStatus = "QUEUED"))))
                            false
                        } else true
                    }
                    if (!permitted) return
                    aiConfig.status("正在识别争议图片")
                    aiRecognizer.analyze(config, aiConfig.secret(), image)
                }
            }
            if(analyzer.fingerprint(photo.uri)!=photo.metadata.fingerprint) { requeueChanged(photo); return }
            mutationMutex.withLock {
                val latest=dao.photo(photo.sourceId,photo.id)
                if(latest!=null && latest.trashedAt==null && latest.modifiedAt==photo.modifiedAt && latest.fileSize==photo.fileSize && latest.metadata.generation==photo.metadata.generation && latest.metadata.aiStatus=="PROCESSING") {
                    dao.saveAiCache(AiResultEntity(key,photo.metadata.fingerprint,response.content,config.model,response.requestId,response.inputTokens,response.outputTokens,System.currentTimeMillis()))
                    val fresh=preserveManual(response.result.applyTo(latest.metadata),latest.metadata).copy(aiCacheKey=key)
                    dao.insertPhotos(listOf(latest.copy(metadata=fresh)))
                }
            }
            aiConfig.status(if(cached!=null) "已复用识别结果，未发起付费请求" else "AI 结果已保存")
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(failure:Exception) {
            val kind=(failure as? AiFailure)?.kind ?: "LOCAL"
            val message=if(failure is AiFailure) failure.message!! else "图片读取或密钥读取失败，请检查后重试"
            mutationMutex.withLock {
                val latest=dao.photo(photo.sourceId,photo.id)
                if(latest!=null && latest.metadata.generation==photo.metadata.generation && latest.metadata.aiStatus=="PROCESSING")
                    dao.insertPhotos(listOf(latest.copy(metadata=latest.metadata.copy(aiStatus=if(kind=="UNKNOWN_OUTCOME") kind else "FAILED",aiError=message))))
            }
            aiConfig.status(message,if(kind in setOf("AUTH","CONFIG","RATE_LIMIT","UNKNOWN_OUTCOME","LOCAL")) true else null)
        }
    }

    private suspend fun requeueChanged(photo:PhotoEntity) = mutationMutex.withLock {
        dao.photo(photo.sourceId,photo.id)?.takeIf { it.metadata.generation==photo.metadata.generation }?.let {
            dao.insertPhotos(listOf(it.copy(metadata=it.metadata.copy(taskState="QUEUED",aiStatus="NOT_REQUIRED",generation=it.metadata.generation+1))))
        }
    }

    override suspend fun saveAiSettings(settings:AiSettings,apiKey:String?) = withContext(Dispatchers.IO) {
        aiConfig.save(settings,apiKey)
        if(settings.enabled) mutationMutex.withLock { dao.queueConfiguredAi() }
    }

    override suspend fun aiCommand(command:String,photoId:String?):Unit = withContext(Dispatchers.IO) {
        when(command) {
            "PAUSE" -> aiConfig.status("AI 队列已暂停，在途请求仍可能完成",true)
            "RESUME" -> aiConfig.status("AI 队列已继续",false)
            "NEW_BATCH" -> aiConfig.newBatch()
            "CANCEL" -> mutationMutex.withLock {
                for(source in dao.sources()) dao.insertPhotos(dao.sourcePhotos(source.id).filter { it.metadata.aiStatus in setOf("QUEUED","WAITING_CONFIG") }.map { it.copy(metadata=it.metadata.copy(aiStatus="CANCELLED")) })
            }
            "RETRY", "REANALYZE" -> mutationMutex.withLock {
                val targets=if(photoId!=null) dao.photosById(photoId) else dao.sources().flatMap { dao.sourcePhotos(it.id) }.filter { it.metadata.aiStatus=="FAILED" }
                for(photo in targets.filter { it.trashedAt==null }) {
                    require(photo.metadata.taskState=="DONE" && photo.metadata.fingerprint.isNotBlank()) { "请先等待本地识别完成；旧图片可先执行全部重识别" }
                    if(command=="REANALYZE" && photo.metadata.fingerprint.isNotBlank()) dao.deleteAiCache(aiCacheKey(photo.metadata.fingerprint,aiConfig.state.value))
                    dao.insertPhotos(listOf(photo.copy(metadata=photo.metadata.copy(generation=photo.metadata.generation+1,aiError=null,
                        taskState=if(photo.metadata.fingerprint.isBlank()) "QUEUED" else photo.metadata.taskState,
                        aiStatus=if(aiConfig.state.value.configured && aiConfig.state.value.enabled) "QUEUED" else "WAITING_CONFIG"))))
                }
            }
        }
    }

    override suspend fun testAiConnection():String = withContext(Dispatchers.IO) {
        val config=aiConfig.state.value
        require(config.configured && config.enabled) { "请保存并启用完整 API 配置" }
        check(aiConfig.reserve()) { "AI 已暂停或本轮数量已用完" }
        val bitmap=android.graphics.Bitmap.createBitmap(160,120,android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas=android.graphics.Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawRect(40f,20f,120f,100f,android.graphics.Paint().apply { color=android.graphics.Color.BLUE })
            val bytes=java.io.ByteArrayOutputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,it); it.toByteArray() }
            aiRequestMutex.withLock { aiRecognizer.analyze(config,aiConfig.secret(),bytes) }
            "连接、图片输入和结果格式均通过（已计入本轮调用数量）".also { aiConfig.status(it) }
        } finally { bitmap.recycle() }
    }

    override suspend fun setPhotoPresentation(sourceId:String,photoId:String,presentation:String?) = withContext(Dispatchers.IO) {
        require(presentation==null || presentation=="WHOLE_VIEW")
        mutationMutex.withLock {
            dao.insertPhotos(dao.photosById(photoId).map { it.copy(metadata=it.metadata.copy(manualPresentation=presentation)) })
        }
    }

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        initializeMutex.withLock {
            if (initialized.value) return@withLock
            mutationMutex.withLock {
                dao.purgeTrash(System.currentTimeMillis() - TRASH_RETENTION_MILLIS)
                dao.recoverAnalysis()
                dao.recoverAi()
                for (source in dao.sources()) {
                    val stale = dao.sourcePhotos(source.id).filter { it.metadata.taskState == "DONE" && it.metadata.modelVersion != OfflinePhotoAnalyzer.VERSION }
                    stale.chunked(200).forEach { chunk -> dao.insertPhotos(chunk.map { it.copy(metadata = it.metadata.copy(taskState = "QUEUED")) }) }
                }
                if (dao.albums().isEmpty()) dao.insertAlbum(Album(DEFAULT_ALBUM_ID, "家庭相册").entity())
                for (source in dao.sources()) {
                    currentCoroutineContext().ensureActive()
                    try {
                        scanner.directoryName(Uri.parse(source.uri))
                        dao.updateAvailability(source.id, true, null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        dao.updateAvailability(source.id, false, readableError(failure))
                        if (failure is SecurityException || failure is java.io.FileNotFoundException) dao.trashSource(source.id, System.currentTimeMillis(), readableError(failure))
                    }
                }
            }
            initialized.value = true
        }
    }

    override suspend fun createAlbum(name: String): String = withContext(Dispatchers.IO) {
        val cleanName = validateName(name)
        val id = UUID.randomUUID().toString()
        mutationMutex.withLock { dao.insertAlbum(Album(id, cleanName).entity()) }
        id
    }

    override suspend fun updateAlbum(album: Album): Unit = withContext(Dispatchers.IO) {
        val cleanName = validateName(album.name)
        require(album.intervalSeconds in 1..3600) { "换片间隔应为 1～3600 秒" }
        require(album.musicVolume.isFinite() && album.musicVolume in 0f..1f) { "音乐音量应在 0～100% 之间" }
        require(album.transitionMillis in 0..2000) { "转场时长应为 0～2000 毫秒" }
        require(album.focusDurationSeconds in 1..3600) { "焦点放大时长应为 1～3600 秒" }
        require(album.motionStrength.isFinite() && album.motionStrength in 0f..0.5f) { "动画强度无效" }
        require(album.maxCrop.isFinite() && album.maxCrop in 0f..0.85f) { "裁剪上限无效" }
        require(album.playbackCategories.split(',').filter { it.isNotBlank() }.all { name -> PhotoCategory.entries.any { it.name == name } }) { "播放类别无效" }
        mutationMutex.withLock {
            check(dao.album(album.id) != null) { "此相册已不存在" }
            val musicSource = album.musicSourceId?.let { dao.source(it) }
            require(album.musicSourceId == null || (musicSource != null && musicSource.albumId == album.id && musicSource.kind == SourceKind.MUSIC.name)) {
                "音乐文件夹已失效，请重新选择"
            }
            require(!album.musicEnabled || musicSource != null) { "请先选择音乐文件夹" }
            val safeAlbum = album.copy(name = cleanName)
            database.withTransaction {
            dao.updateAlbum(
                safeAlbum.id, safeAlbum.name, safeAlbum.intervalSeconds, safeAlbum.shuffle, safeAlbum.loop,
                safeAlbum.sortOrder.name, safeAlbum.scale.name, safeAlbum.transitionMillis,
                safeAlbum.musicSourceId, safeAlbum.musicEnabled, safeAlbum.musicOrder.name, safeAlbum.musicVolume,
            )
            dao.smartSettings(album.id, album.motionMode.name, album.motionStrength, album.maxCrop, album.blurBackground, album.playbackCategories)
            dao.animationSettings(album.id, album.transitionEffect.name, album.focusDurationSeconds)
            }
        }
    }

    override suspend fun deleteAlbum(id: String): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            check(dao.album(id) != null) { "此相册已不存在" }
            val previousUris = dao.sources().filter { it.albumId == id }.map { it.uri }
            // FK cascades remove only metadata and associated schedules, not external documents.
            database.withTransaction {
                dao.deleteAlbum(id)
                if (dao.albums().isEmpty()) dao.insertAlbum(Album(DEFAULT_ALBUM_ID, "家庭相册").entity())
            }
            releaseUnusedGrants(previousUris)
        }
    }

    override suspend fun addSource(albumId: String, uri: String, kind: SourceKind, recursive: Boolean): String = withContext(Dispatchers.IO) {
        val tree = Uri.parse(uri)
        val displayName = try {
            scanner.directoryName(tree)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IllegalStateException(readableError(failure), failure)
        }
        val sourceId = mutationMutex.withLock {
            check(dao.album(albumId) != null) { "此相册已不存在" }
            val sources = dao.sources()
            val existing = sources.firstOrNull { it.albumId == albumId && it.uri == uri && it.kind == kind.name }
            val id = existing?.id ?: UUID.randomUUID().toString()
            val removedMusicUris = ArrayList<String>()
            database.withTransaction {
                if (kind == SourceKind.MUSIC) {
                    sources.filter { it.albumId == albumId && it.kind == SourceKind.MUSIC.name && it.id != id }.forEach {
                        removedMusicUris += it.uri
                        dao.deleteSource(it.id)
                    }
                }
                if (existing == null) {
                    dao.insertSource(SourceEntity(id, albumId, uri, displayName, kind.name, recursive, true, null, 0))
                } else {
                    dao.updateRecursive(id, recursive)
                    dao.updateAvailability(id, true, null)
                }
                if (kind == SourceKind.MUSIC) dao.bindMusic(albumId, id, true)
            }
            releaseUnusedGrants(removedMusicUris)
            id
        }
        scan(sourceId)
        sourceId
    }

    override suspend fun updateSource(sourceId: String, recursive: Boolean): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            check(dao.source(sourceId) != null) { "此文件夹来源已移除" }
            dao.updateRecursive(sourceId, recursive)
        }
        scan(sourceId)
    }

    override suspend fun removeSource(sourceId: String): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val source = dao.source(sourceId) ?: return@withLock
            database.withTransaction {
                val album = dao.album(source.albumId)
                if (album?.musicSourceId == sourceId) dao.bindMusic(source.albumId, null, false)
                dao.deleteSource(sourceId)
            }
            releaseUnusedGrants(listOf(source.uri))
        }
    }

    override suspend fun scan(sourceId: String): Unit = withContext(Dispatchers.IO) {
        val lock = scanMutexes.getOrPut(sourceId) { Mutex() }
        lock.withLock {
            val source = dao.source(sourceId)?.model() ?: return@withLock
            scanning.update { it + sourceId }
            try {
                val result = scanner.scan(source)
                currentCoroutineContext().ensureActive()
                mutationMutex.withLock {
                    val latest = dao.source(sourceId)
                    // A changed/removed source invalidates a result even if the provider finished late.
                    if (latest != null && latest.uri == source.uri && latest.recursive == source.recursive) {
                        database.withTransaction {
                            val updated = reconcilePhotos(dao.sourcePhotos(sourceId), result.photos, System.currentTimeMillis())
                            for (chunk in updated.chunked(200)) {
                                currentCoroutineContext().ensureActive()
                                dao.insertPhotos(chunk)
                            }
                            dao.deleteSourceTracks(sourceId)
                            for (chunk in result.tracks.chunked(200)) {
                                currentCoroutineContext().ensureActive()
                                dao.insertTracks(chunk)
                            }
                            dao.completeScan(sourceId, System.currentTimeMillis())
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // Cancellation retains the last successfully committed index and availability.
                throw cancelled
            } catch (failure: Exception) {
                val error = readableError(failure)
                mutationMutex.withLock {
                    dao.updateAvailability(sourceId, false, error)
                    if (failure is SecurityException || failure is java.io.FileNotFoundException) dao.trashSource(sourceId, System.currentTimeMillis(), error)
                }
                throw IllegalStateException(error, failure)
            } finally {
                scanning.update { it - sourceId }
            }
        }
    }

    override suspend fun savePhotoEdits(sourceId: String, photoId: String, focus: FocusPoint?, category: PhotoCategory?, motion: MotionMode?) = withContext(Dispatchers.IO) {
        require(focus == null || (focus.x.isFinite() && focus.y.isFinite() && focus.x in 0f..1f && focus.y in 0f..1f))
        mutationMutex.withLock {
            val old = checkNotNull(dao.photo(sourceId, photoId)) { "照片索引已不存在" }
            dao.insertPhotos(dao.photosById(old.id).map { shared -> shared.copy(metadata = shared.metadata.copy(manualX = focus?.x, manualY = focus?.y,
                manualCategory = category?.name, customMotionMode = motion?.name, manualPresentation = if (focus != null) null else shared.metadata.manualPresentation,
                focusStatus = when { focus != null -> "MANUAL"; shared.metadata.autoFocus != null -> "DETECTED";
                    shared.metadata.taskState == "FAILED" -> "ERROR"; shared.metadata.taskState in setOf("QUEUED", "PROCESSING") -> "PENDING"; else -> "NEEDS_MANUAL" })) })
        }
    }

    override suspend fun setAnalysisState(sourceId: String, command: String) = withContext(Dispatchers.IO) {
        if (command == "RESCAN") scan(sourceId)
        mutationMutex.withLock {
            val photos = dao.sourcePhotos(sourceId)
            val state = when (command) { "PAUSE" -> "PAUSED"; "CANCEL" -> "CANCELLED"; else -> "RUNNING" }
            dao.analysisState(sourceId, state)
            val updates = photos.filter { it.trashedAt == null }.mapNotNull { photo ->
                val task = photo.metadata.taskState
                val next = when {
                    command == "CANCEL" && task in setOf("QUEUED", "PROCESSING") -> "CANCELLED"
                    command == "RESCAN" || (command == "RETRY" && task == "FAILED") || (command == "RESUME" && task == "CANCELLED") -> "QUEUED"
                    else -> null
                }
                next?.let { photo.copy(metadata = photo.metadata.copy(taskState = it, error = null, generation = photo.metadata.generation + 1)) }
            }
                    updates.chunked(200).forEach { dao.insertPhotos(it) }
        }
    }

    override suspend fun reanalyzePhoto(sourceId: String, photoId: String): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val photo = checkNotNull(dao.photo(sourceId, photoId)) { "照片索引已不存在" }
            check(photo.trashedAt == null) { "请先从回收站恢复图片" }
            check(dao.source(sourceId)?.available == true) { "图片文件夹暂时无法读取，请重新授权" }
            database.withTransaction {
                // Explicit reanalysis bypasses the local cross-album cache. AI may reuse a paid result.
                val fresh = preserveManual(PhotoMetadata(classificationStatus = "REANALYZE"), photo.metadata)
                    .copy(generation = photo.metadata.generation + 1)
                dao.insertPhotos(listOf(photo.copy(metadata = fresh)))
                dao.analysisState(sourceId, "RUNNING")
            }
        }
    }

    override suspend fun removePhoto(sourceId: String, photoId: String): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val albumId = checkNotNull(dao.source(sourceId)) { "相册来源已不存在" }.albumId
            val sourceIds = dao.sources().filter { it.albumId == albumId }.map { it.id }.toSet()
            val now = System.currentTimeMillis()
            database.withTransaction {
                // The same document can be referenced by overlapping folders in this album.
                val targets = dao.photosById(photoId).filter { it.sourceId in sourceIds }
                check(targets.isNotEmpty()) { "照片索引已不存在" }
                dao.insertPhotos(targets.map { it.copy(trashedAt = now, unavailableReason = USER_REMOVED_PHOTO,
                    metadata = it.metadata.copy(generation = it.metadata.generation + 1, taskState = "CANCELLED", aiStatus = "CANCELLED")) })
            }
        }
    }

    override suspend fun restorePhoto(sourceId: String, photoId: String): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val albumId = checkNotNull(dao.source(sourceId)) { "相册来源已不存在" }.albumId
            val sourceIds = dao.sources().filter { it.albumId == albumId }.map { it.id }.toSet()
            database.withTransaction {
                val targets = dao.photosById(photoId).filter { it.sourceId in sourceIds && it.unavailableReason == USER_REMOVED_PHOTO }
                check(targets.isNotEmpty()) { "此图片无需恢复" }
                dao.insertPhotos(targets.map { it.copy(trashedAt = null, unavailableReason = null,
                    metadata = it.metadata.copy(generation = it.metadata.generation + 1, taskState = "QUEUED", aiStatus = "NOT_REQUIRED")) })
            }
        }
        scan(sourceId)
    }

    override suspend fun markUnavailable(sourceId: String, photoId: String): Unit = withContext(Dispatchers.IO) {
        val photo = dao.photo(sourceId, photoId) ?: return@withContext
        // Unsupported codecs/corrupt images remain technical errors, not missing files.
        try { resolver.openInputStream(Uri.parse(photo.uri))?.use { it.read() }; return@withContext }
        catch (_: SecurityException) { }
        catch (_: java.io.FileNotFoundException) { }
        catch (_: Exception) { return@withContext }
        mutationMutex.withLock {
            dao.photo(sourceId, photoId)?.takeIf { it.unavailableReason != USER_REMOVED_PHOTO }?.let { dao.insertPhotos(listOf(it.copy(trashedAt = it.trashedAt ?: System.currentTimeMillis(), unavailableReason = "文件不存在或授权失效"))) }
        }
    }

    override suspend fun saveSchedule(schedule: PlaySchedule): Unit = withContext(Dispatchers.IO) {
        require(schedule.startMinute in 0..1439 && schedule.endMinute in 0..1439) { "计划时间无效" }
        require(schedule.startMinute != schedule.endMinute) { "开始和结束时间不能相同" }
        require(schedule.daysMask in 1..127) { "请至少选择一个重复星期" }
        mutationMutex.withLock {
            check(dao.album(schedule.albumId) != null) { "请选择有效的相册" }
            val allSchedules = dao.schedules()
            if (schedule.enabled) {
                val conflict = allSchedules.firstOrNull {
                    it.enabled && it.id != schedule.id && weeklyOverlap(schedule, it.model())
                }
                require(conflict == null) { "此时间与“${conflict?.name?.ifBlank { "播放计划" }}”重叠，请调整时间或重复星期" }
            }
            val previous = allSchedules.firstOrNull { it.id == schedule.id }
            val saved = schedule.copy(
                name = schedule.name.trim().ifBlank { "播放计划" }.take(50),
                revision = if (previous == null) maxOf(1, schedule.revision) else previous.revision + 1,
            )
            dao.saveSchedule(saved.entity())
        }
    }

    override suspend fun deleteSchedule(id: String): Unit = withContext(Dispatchers.IO) {
        mutationMutex.withLock { dao.deleteSchedule(id) }
    }

    override suspend fun updatePreferences(preferences: AppPreferences) {
        require(preferences.standbyBrightness.isFinite()) { "待机亮度无效" }
        appContext.albumPreferences.edit {
            it[DYNAMIC_COLOR] = preferences.dynamicColor
            it[KEEP_STANDBY_AWAKE] = preferences.keepStandbyAwake
            it[SHOW_CLOCK] = preferences.showClock
            it[STANDBY_BRIGHTNESS] = preferences.standbyBrightness.coerceIn(.01f, 1f)
            it[REMINDERS_ENABLED] = preferences.remindersEnabled
            it[GALLERY_COLUMNS] = preferences.galleryColumns.coerceIn(1, 2)
        }
    }

    private suspend fun releaseUnusedGrants(uris: Collection<String>) {
        val referenced = dao.sources().map { it.uri }.toSet()
        for (uri in uris.distinct().filter { it !in referenced }) {
            try {
                val parsed = Uri.parse(uri)
                if (resolver.persistedUriPermissions.any { it.uri == parsed && it.isReadPermission }) {
                    resolver.releasePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: SecurityException) {
                // The user/provider may have revoked the grant already.
            }
        }
    }

    private fun validateName(name: String): String = name.trim().also {
        require(it.isNotEmpty()) { "请输入相册名称" }
        require(it.length <= 50) { "相册名称最多 50 个字符" }
    }

    private fun readableError(failure: Exception): String = when (failure) {
        is SecurityException -> "文件夹授权已失效，请重新选择并授权此文件夹"
        is java.io.FileNotFoundException -> "文件夹不存在或存储设备未连接，请检查后重试"
        else -> failure.message?.takeIf { it.isNotBlank() && it.any { character -> character.code in 0x4E00..0x9FFF } }
            ?: "暂时无法读取文件夹，已保留原有索引，请检查存储设备或重新授权"
    }

    companion object {
        private const val DEFAULT_ALBUM_ID = "family"
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEEP_STANDBY_AWAKE = booleanPreferencesKey("keep_standby_awake")
        private val SHOW_CLOCK = booleanPreferencesKey("show_clock")
        private val STANDBY_BRIGHTNESS = floatPreferencesKey("standby_brightness")
        private val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        private val GALLERY_COLUMNS = intPreferencesKey("gallery_columns")

        /** Monday is bit 0. Split wrap-around ranges before comparing half-open intervals. */
        internal fun weeklyOverlap(first: PlaySchedule, second: PlaySchedule): Boolean {
            val week = 7 * 1440
            fun ranges(schedule: PlaySchedule): List<Pair<Int, Int>> = buildList {
                for (day in 0..6) {
                    if (schedule.daysMask and (1 shl day) == 0) continue
                    val start = day * 1440 + schedule.startMinute
                    val end = day * 1440 + schedule.endMinute + if (schedule.endMinute < schedule.startMinute) 1440 else 0
                    if (end <= week) add(start to end) else {
                        add(start to week)
                        add(0 to end - week)
                    }
                }
            }
            return ranges(first).any { a -> ranges(second).any { b -> a.first < b.second && b.first < a.second } }
        }
    }
}

private fun Album.entity() = AlbumEntity(
    id, name, intervalSeconds, shuffle, loop, sortOrder.name, scale.name, transitionMillis,
    musicSourceId, musicEnabled, musicOrder.name, musicVolume,
    motionMode.name, motionStrength, maxCrop, blurBackground, playbackCategories,
    transitionEffect.name, focusDurationSeconds,
)

private fun AlbumEntity.model() = Album(
    id, name, intervalSeconds, shuffle, loop, SortOrder.valueOf(sortOrder), PhotoScale.valueOf(scale), transitionMillis,
    musicSourceId, musicEnabled, MusicOrder.valueOf(musicOrder), musicVolume,
    MotionMode.valueOf(motionMode), motionStrength, maxCrop, blurBackground, playbackCategories,
    com.muguang.album.core.SlideEffect.valueOf(transitionEffect), focusDurationSeconds,
)

private fun SourceEntity.model() = FolderSource(
    id, albumId, uri, name, SourceKind.valueOf(kind), recursive, available, error, lastScanAt, analysisState,
)

private fun PlaySchedule.entity() = ScheduleEntity(
    id, name, albumId, startMinute, endMinute, daysMask, enabled, revision,
)

private fun ScheduleEntity.model() = PlaySchedule(
    id, name, albumId, startMinute, endMinute, daysMask, enabled, revision,
)
