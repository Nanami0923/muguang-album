@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.muguang.album.ui

import android.app.TimePickerDialog
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.muguang.album.core.*
import com.muguang.album.playback.naturalComparator
import com.muguang.album.playback.ScheduleCalculator
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class LibraryActions(
    val onSelectAlbum: (String) -> Unit,
    val onCreateAlbum: (String) -> Unit,
    val onDeleteAlbum: (String) -> Unit,
    val onPickPhotos: (String) -> Unit,
    val onPickMusic: (String) -> Unit,
    val onUpdateAlbum: (Album) -> Unit,
    val onRefreshSource: (String) -> Unit,
    val onRemoveSource: (String) -> Unit,
    val onUpdateSource: (String, Boolean) -> Unit,
    val onPlay: (String) -> Unit,
    val onEnterDisplay: () -> Unit,
    val onSaveSchedule: (PlaySchedule) -> Unit,
    val onDeleteSchedule: (String) -> Unit,
    val onUpdatePreferences: (AppPreferences) -> Unit,
    val onMessage: (String) -> Unit,
)

private enum class Destination(val title: String, val icon: ImageVector) {
    ALBUMS("相册", Icons.Outlined.PhotoLibrary),
    SCHEDULE("定时", Icons.Outlined.Schedule),
    SETTINGS("设置", Icons.Outlined.Tune),
}

@Composable
fun MainLibraryScreen(library: LibraryState, selectedAlbumId: String?, actions: LibraryActions) {
    var destination by rememberSaveable { mutableStateOf(Destination.ALBUMS) }
    val selected = library.albums.firstOrNull { it.id == selectedAlbumId } ?: library.albums.firstOrNull()
    Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val wide = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxHeight().width(100.dp).padding(top = 20.dp),
                        header = {
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Outlined.FilterVintage, "暮光相册", Modifier.padding(14.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(48.dp))
                        },
                    ) {
                        Destination.entries.forEach { item ->
                            NavigationRailItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Icon(item.icon, null) },
                                label = { Text(item.title) },
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (!library.initialized) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        } else when (destination) {
                            Destination.ALBUMS -> AlbumPage(library, selected, actions, wide)
                            Destination.SCHEDULE -> SchedulePage(library, actions)
                            Destination.SETTINGS -> SettingsPage(library, actions)
                        }
                    }
                    if (!wide) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Destination.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = destination == item,
                                    onClick = { destination = item },
                                    icon = { Icon(item.icon, null) },
                                    label = { Text(item.title) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun AlbumPage(library: LibraryState, album: Album?, actions: LibraryActions, wide: Boolean) {
    var createDialog by rememberSaveable { mutableStateOf(false) }
    var settingsDialog by rememberSaveable { mutableStateOf(false) }
    var musicDialog by rememberSaveable { mutableStateOf(false) }
    var sourceDialog by rememberSaveable { mutableStateOf(false) }
    var deleteDialog by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Photo?>(null) }
    val photos = remember(library.photos, library.sources, album?.id, album?.sortOrder) {
        album?.let { item ->
            val list = library.photosFor(item.id)
            if (item.sortOrder == SortOrder.MODIFIED) list.sortedBy { it.modifiedAt }
            else list.sortedWith(naturalComparator { it.name })
        } ?: emptyList()
    }
    val sources = library.sources.filter { it.albumId == album?.id && it.kind == SourceKind.PHOTO }
    val scanning = sources.any { it.id in library.scanningSourceIds }
    val padding = if (wide) 28.dp else 20.dp
    Column(Modifier.fillMaxSize().padding(horizontal = padding).padding(top = 24.dp)) {
        PageHeading("暮光相册", "把喜欢的日常，留在眼前。") {
            IconButton(onClick = { createDialog = true }) { Icon(Icons.Outlined.Add, "新建相册") }
        }
        Spacer(Modifier.height(20.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(library.albums, key = { it.id }) { item ->
                FilterChip(
                    selected = album?.id == item.id,
                    onClick = { actions.onSelectAlbum(item.id) },
                    label = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (album?.id == item.id) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null,
                )
            }
        }
        if (album == null) {
            EmptyState(Icons.Outlined.Collections, "创建第一本相册", "为家中的回忆起个名字，再选择照片文件夹。", Modifier.weight(1f), scrollable = true) {
                Button(onClick = { createDialog = true }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("新建相册") }
            }
        } else {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    AlbumSummary(album, photos, sources.size, scanning, onPlay = { actions.onPlay(album.id) }, onSettings = { settingsDialog = true })
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("全部照片", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        if (!wide) {
                            IconButton(onClick = { sourceDialog = true }) { Icon(Icons.Outlined.FolderOpen, "管理图片文件夹") }
                            IconButton(onClick = { musicDialog = true }) { Icon(Icons.Outlined.MusicNote, "背景音乐") }
                        }
                        IconButton(onClick = { actions.onPickPhotos(album.id) }) { Icon(Icons.Outlined.CreateNewFolder, "添加图片文件夹") }
                    }
                    if (photos.isEmpty()) {
                        EmptyState(
                            Icons.Outlined.AddPhotoAlternate,
                            if (scanning) "正在发现照片" else "从一个文件夹开始",
                            if (scanning) "照片会陆续出现在这里，可以继续操作。" else "选择平板或存储卡中的图片文件夹。\n原图会一直留在原处。",
                            Modifier.weight(1f),
                            scrollable = true,
                        ) {
                            Button(onClick = { actions.onPickPhotos(album.id) }, enabled = !scanning) {
                                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("选择图片文件夹")
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(140.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            items(photos, key = { it.id }) { photo -> PhotoTile(photo) { preview = photo } }
                        }
                    }
                }
                if (wide) {
                    Column(Modifier.width(290.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SidePanel("图片来源", Icons.Outlined.FolderOpen, action = { actions.onPickPhotos(album.id) }) {
                            if (sources.isEmpty()) Text("还没有添加图片文件夹", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            sources.forEach { source -> SourceRow(source, library, actions) }
                        }
                        MusicSummary(library, album, onOpen = { musicDialog = true })
                        SidePanel("播放设置", Icons.Outlined.Tune, action = { settingsDialog = true }) {
                            InfoRow("换片间隔", "${album.intervalSeconds} 秒")
                            InfoRow("图片顺序", if (album.shuffle) "随机播放" else "顺序播放")
                            InfoRow("显示方式", if (album.scale == PhotoScale.FIT) "完整显示" else "填满画面")
                            InfoRow("循环播放", if (album.loop) "已开启" else "已关闭")
                        }
                        TextButton(onClick = { deleteDialog = true }) { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("移除此相册") }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
            if (settingsDialog) AlbumSettingsDialog(album, onDismiss = { settingsDialog = false }, onSave = actions.onUpdateAlbum, onDelete = { settingsDialog = false; deleteDialog = true })
            if (musicDialog) MusicSettingsDialog(library, album, actions) { musicDialog = false }
            if (sourceDialog) ContentDialog("图片文件夹", { sourceDialog = false }) {
                sources.forEach { SourceRow(it, library, actions) }
                if (sources.isEmpty()) Text("请选择一个存放照片的本地文件夹。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { actions.onPickPhotos(album.id) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("添加文件夹") }
            }
            if (deleteDialog) ConfirmDelete("移除“${album.name}”？", "相册内的原图和音乐不会被删除，相关播放计划将停用。", { deleteDialog = false }) {
                actions.onDeleteAlbum(album.id); deleteDialog = false
            }
            preview?.let { photo -> PhotoPreview(photo, onDismiss = { preview = null }, onPlay = { preview = null; actions.onPlay(album.id) }) }
        }
    }
    if (createDialog) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createDialog = false },
            icon = { Icon(Icons.Outlined.CollectionsBookmark, null) },
            title = { Text("新建相册") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("相册名称") }, placeholder = { Text("例如：家庭回忆") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { actions.onCreateAlbum(name.trim()); createDialog = false }, enabled = name.isNotBlank()) { Text("创建") } },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AlbumSummary(album: Album, photos: List<Photo>, sourceCount: Int, scanning: Boolean, onPlay: () -> Unit, onSettings: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (photos.isNotEmpty()) {
                AsyncImage(photos.first().uri, "相册封面", Modifier.size(82.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(66.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Landscape, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(album.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(if (scanning) "正在扫描文件夹…" else "${photos.size} 张照片 · $sourceCount 个文件夹", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onPlay, enabled = photos.isNotEmpty(), contentPadding = PaddingValues(horizontal = 16.dp)) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)); Text("开始播放")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Tune, "播放设置") }
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(photo: Photo, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick)) {
        AsyncImage(photo.uri, photo.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(50.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .52f)))))
        Text(photo.name, Modifier.align(Alignment.BottomStart).padding(10.dp), style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SourceRow(source: FolderSource, library: LibraryState, actions: LibraryActions) {
    var confirmRemove by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (source.available) Icons.Outlined.Folder else Icons.Outlined.FolderOff, null, Modifier.size(20.dp), tint = if (source.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(source.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(if (source.id in library.scanningSourceIds) "正在扫描…" else if (!source.available) source.error ?: "需要重新选择文件夹" else "${if (source.kind == SourceKind.PHOTO) library.photos.count { it.sourceId == source.id } else library.tracks.count { it.sourceId == source.id }} ${if (source.kind == SourceKind.PHOTO) "张照片" else "首音乐"}", style = MaterialTheme.typography.bodySmall, color = if (source.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, maxLines = 2)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("包含子文件夹", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Checkbox(checked = source.recursive, onCheckedChange = { actions.onUpdateSource(source.id, it) }, enabled = source.id !in library.scanningSourceIds)
            IconButton(onClick = { actions.onRefreshSource(source.id) }, enabled = source.id !in library.scanningSourceIds) { Icon(Icons.Outlined.Refresh, "刷新${source.name}", Modifier.size(19.dp)) }
            IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Outlined.Close, "移除${source.name}", Modifier.size(19.dp)) }
        }
        if (!source.available) {
            TextButton(onClick = {
                if (source.kind == SourceKind.PHOTO) actions.onPickPhotos(source.albumId)
                else actions.onPickMusic(source.albumId)
            }) { Text("重新选择文件夹") }
        }
    }
    if (confirmRemove) ConfirmDelete("移除此文件夹？", "只会解除此相册的引用，不会删除平板上的文件。", { confirmRemove = false }) { actions.onRemoveSource(source.id); confirmRemove = false }
}

@Composable
private fun MusicSummary(library: LibraryState, album: Album, onOpen: () -> Unit) {
    val source = library.sources.firstOrNull { it.id == album.musicSourceId }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, onClick = onOpen) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(10.dp)); Text("背景音乐", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ChevronRight, "音乐设置", Modifier.size(20.dp))
            }
            Text(source?.name ?: "为相册选一份配乐", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (source == null) "从本地文件夹播放，随相册一起启停。" else "${library.tracksFor(album).size} 首 · ${if (album.musicOrder == MusicOrder.SEQUENTIAL) "顺序循环" else "随机循环"} · ${if (album.musicEnabled) "已开启" else "已关闭"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SidePanel(title: String, icon: ImageVector, action: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp)); Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (action != null) IconButton(onClick = action, modifier = Modifier.size(32.dp)) { Icon(if (title == "图片来源") Icons.Outlined.Add else Icons.Outlined.ChevronRight, "管理$title", Modifier.size(20.dp)) }
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier, scrollable: Boolean = false, action: @Composable () -> Unit = {}) {
    val contentModifier = if (scrollable) modifier.verticalScroll(rememberScrollState()) else modifier
    Column(contentModifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(22.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp)); action()
    }
}

@Composable
private fun ContentDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(.94f).heightIn(max = 720.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") }
                }
                content()
            }
        }
    }
}

@Composable
private fun AlbumSettingsDialog(album: Album, onDismiss: () -> Unit, onSave: (Album) -> Unit, onDelete: () -> Unit) {
    var draft by remember(album) { mutableStateOf(album) }
    var interval by remember(album) { mutableStateOf(album.intervalSeconds.toString()) }
    ContentDialog("播放设置", onDismiss) {
        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("相册名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("每张照片停留", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 30).forEach { seconds -> FilterChip(selected = interval == seconds.toString(), onClick = { interval = seconds.toString() }, label = { Text("${seconds}秒") }) }
        }
        OutlinedTextField(interval, { if (it.length <= 4 && it.all(Char::isDigit)) interval = it }, label = { Text("自定义间隔（1～3600 秒）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ToggleRow("随机播放图片", "每轮看完所有照片后再重新洗牌", draft.shuffle, { draft = draft.copy(shuffle = it) })
        ToggleRow("循环播放", "最后一张结束后回到第一张", draft.loop, { draft = draft.copy(loop = it) })
        Text("照片排序", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(draft.sortOrder == SortOrder.NAME, { draft = draft.copy(sortOrder = SortOrder.NAME) }, label = { Text("文件名") })
            FilterChip(draft.sortOrder == SortOrder.MODIFIED, { draft = draft.copy(sortOrder = SortOrder.MODIFIED) }, label = { Text("修改时间") })
        }
        Text("画面适配", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(draft.scale == PhotoScale.FIT, { draft = draft.copy(scale = PhotoScale.FIT) }, label = { Text("完整显示") })
            FilterChip(draft.scale == PhotoScale.CROP, { draft = draft.copy(scale = PhotoScale.CROP) }, label = { Text("填满裁切") })
        }
        ToggleRow("淡入淡出", "关闭后直接切换，更适合较旧的平板", draft.transitionMillis > 0, { draft = draft.copy(transitionMillis = if (it) 500 else 0) })
        Button(onClick = {
            val seconds = interval.toIntOrNull()
            if (seconds != null && seconds in 1..3600 && draft.name.isNotBlank()) { onSave(draft.copy(intervalSeconds = seconds, name = draft.name.trim())); onDismiss() }
        }, enabled = interval.toIntOrNull() in 1..3600 && draft.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("保存设置") }
        TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("移除此相册", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MusicSettingsDialog(library: LibraryState, album: Album, actions: LibraryActions, onDismiss: () -> Unit) {
    val source = library.sources.firstOrNull { it.id == album.musicSourceId }
    var draft by remember(album) { mutableStateOf(album) }
    ContentDialog("背景音乐", onDismiss) {
        Text("只在相册展示时播放。切换照片不会打断音乐，暂停和到时停止会一起停声。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (source != null) SourceRow(source, library, actions)
        OutlinedButton(onClick = { actions.onPickMusic(album.id) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(if (source == null) "选择音乐文件夹" else "更换音乐文件夹") }
        ToggleRow("启用背景音乐", if (source == null) "先选择一个包含音乐的文件夹" else "${library.tracksFor(album).size} 首本地音乐", draft.musicEnabled, { draft = draft.copy(musicEnabled = it) }, enabled = source != null, controlTag = "music-enabled")
        Text("播放方式", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(draft.musicOrder == MusicOrder.SEQUENTIAL, { draft = draft.copy(musicOrder = MusicOrder.SEQUENTIAL) }, label = { Text("顺序循环") }, leadingIcon = { Icon(Icons.Outlined.Repeat, null, Modifier.size(17.dp)) })
            FilterChip(draft.musicOrder == MusicOrder.RANDOM, { draft = draft.copy(musicOrder = MusicOrder.RANDOM) }, label = { Text("随机循环") }, leadingIcon = { Icon(Icons.Outlined.Shuffle, null, Modifier.size(17.dp)) })
        }
        InfoRow("配乐音量", "${(draft.musicVolume * 100).toInt()}%")
        Slider(value = draft.musicVolume, onValueChange = { draft = draft.copy(musicVolume = it) }, valueRange = 0f..1f)
        Text("此音量叠加平板的媒体音量，不会修改系统音量。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { actions.onUpdateAlbum(draft); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("保存音乐设置") }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, controlTag: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title }.then(if (controlTag == null) Modifier else Modifier.testTag(controlTag)))
    }
}

@Composable
private fun ConfirmDelete(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = onConfirm) { Text("移除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun PhotoPreview(photo: Photo, onDismiss: () -> Unit, onPlay: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.9f), color = Color(0xFF08090B), shape = RoundedCornerShape(24.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(photo.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭预览") }
                }
                AsyncImage(photo.uri, photo.name, Modifier.weight(1f).fillMaxWidth(), contentScale = ContentScale.Fit)
                FilledTonalButton(onClick = onPlay, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("播放此相册") }
            }
        }
    }
}

@Composable
private fun SchedulePage(library: LibraryState, actions: LibraryActions) {
    var edit by remember { mutableStateOf<PlaySchedule?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var delete by remember { mutableStateOf<PlaySchedule?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            PageHeading("播放计划", "让照片在合适的时间，陪伴家中的日常。") {
                IconButton(onClick = { if (library.albums.isEmpty()) actions.onMessage("请先创建一本相册") else creating = true }) { Icon(Icons.Outlined.Add, "新建播放计划") }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(10.dp)); Text("让平板成为相框", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(if (library.preferences.keepStandbyAwake) "进入展示模式后，到点自动播放。时段外低亮度待机，屏幕仍保持开启。" else "进入展示模式后，到点播放。停止后允许系统熄屏，之后需要手动唤醒。", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = actions.onEnterDisplay, modifier = Modifier.align(Alignment.Start)) { Icon(Icons.Outlined.Slideshow, null); Spacer(Modifier.width(8.dp)); Text("进入展示模式") }
                }
            }
        }
        if (library.schedules.isEmpty()) {
            item { EmptyState(Icons.Outlined.Schedule, "还没有播放计划", "选择相册、时间和重复星期，\n也可以设置跨午夜的播放时段。") { FilledTonalButton(onClick = { if (library.albums.isEmpty()) actions.onMessage("请先创建一本相册") else creating = true }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("添加计划") } } }
        }
        items(library.schedules, key = { it.id }) { schedule ->
            ScheduleCard(schedule, library.albums.firstOrNull { it.id == schedule.albumId }?.name ?: "需要选择相册", onEdit = { edit = schedule }, onToggle = { actions.onSaveSchedule(schedule.copy(enabled = it, revision = schedule.revision + 1)) }, onDelete = { delete = schedule })
        }
        item {
            Text("计划只有在展示模式中自动执行。应用在后台或平板锁屏时，不能保证自动亮屏播放。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (creating || edit != null) ScheduleEditor(edit, library.albums, library.schedules, onDismiss = { creating = false; edit = null }, onSave = { actions.onSaveSchedule(it); creating = false; edit = null })
    delete?.let { item -> ConfirmDelete("移除播放计划？", "相册、图片和音乐都会保留。", { delete = null }) { actions.onDeleteSchedule(item.id); delete = null } }
}

@Composable
private fun ScheduleCard(schedule: PlaySchedule, albumName: String, onEdit: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, onClick = onEdit) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(schedule.name.ifBlank { "播放计划" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("${timeLabel(schedule.startMinute)} — ${if (schedule.endMinute < schedule.startMinute) "次日 " else ""}${timeLabel(schedule.endMinute)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                }
                Switch(schedule.enabled, onToggle)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CollectionsBookmark, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(7.dp)); Text(albumName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onDelete) { Text("移除", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(daysLabel(schedule.daysMask), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (schedule.enabled) ScheduleCalculator.upcoming(schedule, count = 1).firstOrNull()?.let { next ->
                Text("下次开始  ${next.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ScheduleEditor(existing: PlaySchedule?, albums: List<Album>, schedules: List<PlaySchedule>, onDismiss: () -> Unit, onSave: (PlaySchedule) -> Unit) {
    val context = LocalContext.current
    var draft by remember(existing) { mutableStateOf(existing ?: PlaySchedule(UUID.randomUUID().toString(), "", albums.firstOrNull()?.id ?: "")) }
    var error by remember { mutableStateOf<String?>(null) }
    ContentDialog(if (existing == null) "新建播放计划" else "编辑播放计划", onDismiss) {
        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("计划名称（可选）") }, placeholder = { Text("例如：白天的家庭相册") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("播放相册", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(albums, key = { it.id }) { album -> FilterChip(draft.albumId == album.id, { draft = draft.copy(albumId = album.id) }, label = { Text(album.name) }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeBlock("开始", timeLabel(draft.startMinute), Modifier.weight(1f)) {
                TimePickerDialog(context, { _, hour, minute -> draft = draft.copy(startMinute = hour * 60 + minute) }, draft.startMinute / 60, draft.startMinute % 60, true).show()
            }
            TimeBlock(if (draft.endMinute < draft.startMinute) "次日结束" else "结束", timeLabel(draft.endMinute), Modifier.weight(1f)) {
                TimePickerDialog(context, { _, hour, minute -> draft = draft.copy(endMinute = hour * 60 + minute) }, draft.endMinute / 60, draft.endMinute % 60, true).show()
            }
        }
        if (draft.endMinute < draft.startMinute) Text("此计划跨午夜，重复星期以开始日为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        Text("重复星期", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                val checked = draft.daysMask and (1 shl index) != 0
                Surface(onClick = { draft = draft.copy(daysMask = draft.daysMask xor (1 shl index)) }, shape = CircleShape, color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).height(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(label, color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { draft = draft.copy(daysMask = 127) }) { Text("每天") }
            TextButton(onClick = { draft = draft.copy(daysMask = 31) }) { Text("工作日") }
            TextButton(onClick = { draft = draft.copy(daysMask = 96) }) { Text("周末") }
        }
        ToggleRow("启用此计划", "保存后需进入展示模式才会自动播放", draft.enabled, { draft = draft.copy(enabled = it) })
        val previews = remember(draft) { ScheduleCalculator.upcoming(draft, count = 3) }
        if (previews.isNotEmpty()) {
            Text("接下来三次播放", style = MaterialTheme.typography.titleSmall)
            previews.forEach { item ->
                val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
                Text("${item.start.atZone(ZoneId.systemDefault()).format(formatter)} — ${item.end.atZone(ZoneId.systemDefault()).format(formatter)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = {
            error = when {
                albums.none { it.id == draft.albumId } -> "请选择有效的相册"
                draft.startMinute == draft.endMinute -> "开始和结束时间不能相同"
                draft.daysMask == 0 -> "请至少选择一个重复星期"
                ScheduleCalculator.conflict(draft, schedules) != null -> "与已有计划重叠，请调整时间或重复星期"
                else -> null
            }
            if (error == null) onSave(draft.copy(name = draft.name.trim(), revision = (existing?.revision ?: 0) + 1))
        }, modifier = Modifier.fillMaxWidth()) { Text("保存计划") }
    }
}

@Composable
private fun TimeBlock(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SettingsPage(library: LibraryState, actions: LibraryActions) {
    val prefs = library.preferences
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp).widthIn(max = 900.dp).testTag("settings-list"), contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageHeading("相框设置", "让它适合家里的节奏。") }
        item {
            SettingsSection("外观", Icons.Outlined.Palette) {
                ToggleRow("系统动态配色", if (Build.VERSION.SDK_INT >= 31) "从系统壁纸获取深色主题" else "此平板使用固定深色主题，核心功能不受影响", prefs.dynamicColor, { actions.onUpdatePreferences(prefs.copy(dynamicColor = it)) }, enabled = Build.VERSION.SDK_INT >= 31)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("显示时钟", "在展示画面上显示时间", prefs.showClock, { actions.onUpdatePreferences(prefs.copy(showClock = it)) }, controlTag = "setting-clock")
            }
        }
        item {
            SettingsSection("展示模式", Icons.Outlined.Slideshow) {
                ToggleRow("常驻低亮度待机", "计划外保持屏幕开启，下一时段可自动播放；关闭后允许系统熄屏", prefs.keepStandbyAwake, { actions.onUpdatePreferences(prefs.copy(keepStandbyAwake = it)) }, controlTag = "setting-standby")
                InfoRow("待机亮度", "${(prefs.standbyBrightness * 100).toInt()}%")
                var brightness by remember(prefs.standbyBrightness) { mutableFloatStateOf(prefs.standbyBrightness) }
                Slider(value = brightness, onValueChange = { brightness = it }, onValueChangeFinished = { actions.onUpdatePreferences(prefs.copy(standbyBrightness = brightness)) }, valueRange = .01f.. .25f, enabled = prefs.keepStandbyAwake)
                Text("仅影响此应用的待机画面，退出后恢复系统亮度。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsSection("播放提醒", Icons.Outlined.NotificationsNone) {
                ToggleRow("后台开始提醒", "可选通知提醒，可能受平板省电策略影响；不会自动解锁或打开播放", prefs.remindersEnabled, { actions.onUpdatePreferences(prefs.copy(remindersEnabled = it)) }, controlTag = "setting-reminders")
            }
        }
        item {
            SettingsSection("本地存储", Icons.Outlined.SdStorage) {
                InfoRow("相册", "${library.albums.size} 本")
                InfoRow("图片文件夹", "${library.sources.count { it.kind == SourceKind.PHOTO }} 个")
                InfoRow("本地照片", "${library.photos.distinctBy { it.id }.size} 张")
                InfoRow("本地音乐", "${library.tracks.distinctBy { it.id }.size} 首")
                Text("文件夹只读访问。移除相册和目录不会删除原图或音乐。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Outlined.FilterVintage, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                Column { Text("暮光相册 · 家庭离线版", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(4.dp)); Text("照片、音乐与回忆，都留在这台平板。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

private fun timeLabel(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

private fun daysLabel(mask: Int): String = when (mask) {
    127 -> "每天"
    31 -> "周一至周五"
    96 -> "周六、周日"
    0 -> "未选择重复星期"
    else -> listOf("一", "二", "三", "四", "五", "六", "日").mapIndexedNotNull { index, day -> if (mask and (1 shl index) != 0) "周$day" else null }.joinToString("、")
}
