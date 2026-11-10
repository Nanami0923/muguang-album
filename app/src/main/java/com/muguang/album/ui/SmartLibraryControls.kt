package com.muguang.album.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.muguang.album.core.*
import com.muguang.album.playback.TRASH_RETENTION_MILLIS
import kotlin.math.ceil

@Composable
fun LibraryFilterBar(category: PhotoCategory?, onCategory: (PhotoCategory?) -> Unit, issue: String, onIssue: (String) -> Unit, onTrash: () -> Unit,people:String?=null,onPeople:(String?)->Unit={}) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { FilterChip(people==null,{onPeople(null)},label={Text("不限人数")}) }
        items(listOf("NONE" to "无人物","SINGLE" to "单人","MULTIPLE" to "多人")) { (value,label) -> FilterChip(people==value,{onPeople(value)},label={Text(label)}) }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { FilterChip(category == null, { onCategory(null) }, label = { Text("全部") }) }
        items(PhotoCategory.entries) { item -> FilterChip(category == item, { onCategory(item) }, label = { Text(item.title) }) }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("library-status-filters")) {
        items(listOf("全部", "待 AI", "AI 调用失败", "整体展示", "待手动选点", "技术错误")) { item -> FilterChip(issue == item, { onIssue(item) }, label = { Text(if (item == "全部") "全部状态" else item) }) }
        item { TextButton(onClick = onTrash) { Text("回收站") } }
    }
}

@Composable
fun AnalysisControls(source: FolderSource, photos: List<Photo>, onCommand: (String, String) -> Unit) {
    val completed = photos.count { it.metadata.taskState == "DONE" }
    val failed = photos.count { it.metadata.taskState == "FAILED" }
    val manual = photos.count { it.metadata.manualFocus == null && !it.metadata.wholeView && it.metadata.focusStatus == "NEEDS_MANUAL" }
    val detected = photos.count { it.metadata.autoFocus != null }
    val pending = photos.count { it.metadata.taskState in setOf("QUEUED", "PROCESSING") }
    val cancelled = photos.count { it.metadata.taskState == "CANCELLED" }
    Text("识别 ${completed + failed}/${photos.size} · 自动定位 $detected · 待选点 $manual · 失败 $failed · 待处理 $pending" +
        if (cancelled > 0) " · 已取消 $cancelled" else "", style = MaterialTheme.typography.bodySmall)
    val status = when {
        photos.isEmpty() -> "暂无可识别照片"
        pending == 0 && cancelled == 0 && failed == 0 -> "识别已完成"
        pending == 0 && cancelled == 0 -> "识别已结束，失败项可重试"
        source.analysisState == "CANCELLED" || pending == 0 -> "已取消剩余识别，已完成的结果已保留"
        source.analysisState == "PAUSED" -> "识别已暂停"
        else -> "前台识别，切后台或锁屏自动暂停"
    }
    Text(status, style = MaterialTheme.typography.labelSmall)
    val aiPending=photos.count { it.metadata.aiStatus in setOf("WAITING_CONFIG","QUEUED","PROCESSING") }
    if(aiPending>0) Text("$aiPending 张待 AI 辅助，前往 AI 栏填写视觉 API；已有结果可正常播放。",style=MaterialTheme.typography.bodySmall)
    LazyRow {
        if (pending > 0 || cancelled > 0) {
            val canPause = pending > 0 && source.analysisState == "RUNNING"
            item { TextButton(onClick = { onCommand(source.id, if (canPause) "PAUSE" else "RESUME") }) { Text(if (canPause) "暂停识别" else "继续识别") } }
            if (pending > 0 && source.analysisState != "CANCELLED") {
                item { TextButton(onClick = { onCommand(source.id, "CANCEL") }) { Text("取消识别") } }
            }
        }
        if (failed > 0) item { TextButton(onClick = { onCommand(source.id, "RETRY") }) { Text("重试失败项") } }
        if (photos.isNotEmpty()) item { TextButton(onClick = { onCommand(source.id, "RESCAN") }) { Text("全部重识别") } }
    }
}

@Composable
fun SmartPlaybackSettings(album: Album, onChange: (Album) -> Unit) {
    Text("智能缩放", style = MaterialTheme.typography.titleSmall)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(MotionMode.entries) { item -> FilterChip(album.motionMode == item, { onChange(album.copy(motionMode = item)) }, label = { Text(item.title) }) }
    }
    Text("动画强度 ${(album.motionStrength*100).toInt()}%")
    Slider(album.motionStrength, { onChange(album.copy(motionStrength = it)) }, valueRange = 0f.. .5f)
    Text("最多裁剪画面面积 ${(album.maxCrop*100).toInt()}%")
    Slider(album.maxCrop, { onChange(album.copy(maxCrop = it)) }, valueRange = 0f.. .85f)
    Row { Text("模糊背景", Modifier.weight(1f)); Switch(album.blurBackground, { onChange(album.copy(blurBackground = it)) }) }
    Text("播放类别（多选取并集；全部为不限制）", style = MaterialTheme.typography.titleSmall)
    val selected = album.playbackCategories.split(',').filter { it.isNotBlank() }.toSet()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { FilterChip(selected.isEmpty(), { onChange(album.copy(playbackCategories = "")) }, label = { Text("全部") }) }
        items(PhotoCategory.entries) { item -> FilterChip(item.name in selected, {
            val updated = if (item.name in selected) selected - item.name else selected + item.name
            onChange(album.copy(playbackCategories = updated.sorted().joinToString(",")))
        }, label = { Text(item.title) }) }
    }
}

@Composable
fun TrashDialog(photos: List<Photo>, onDismiss: () -> Unit, onRestore: (Photo) -> Unit = {}, onRescan: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("回收站 · ${photos.size} 张") }, text = {
        Column {
            Text("原图不会被移动或删除。手动移除的图片可在这里恢复，刷新不会自动恢复；失效路径的索引保留 30 天。")
            if (photos.isEmpty()) Text("回收站为空")
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(photos, key = { it.sourceId + it.id }) { photo ->
                    val days = ceil(((photo.trashedAt ?: 0) + TRASH_RETENTION_MILLIS - System.currentTimeMillis()) / 86_400_000.0).toInt().coerceAtLeast(0)
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(photo.name)
                        val removed = photo.unavailableReason == USER_REMOVED_PHOTO
                        Text(if (removed) USER_REMOVED_PHOTO else "${photo.unavailableReason ?: "路径失效"} · 剩余 $days 天", style = MaterialTheme.typography.bodySmall)
                        if (removed) TextButton(onClick = { onRestore(photo) }, modifier = Modifier.testTag("restore-photo-${photo.id}")) { Text("恢复到相册") }
                        else TextButton(onClick = { onRescan(photo.sourceId) }) { Text("重新扫描来源") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
