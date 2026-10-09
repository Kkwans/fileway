package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.upload.UploadRecord

@Composable internal fun UploadsScreen(model: ClientModel) {
    val state by model.uploads.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("全部") }
    var remove by remember { mutableStateOf<UploadRecord?>(null) }
    val items = state.items.filter { when (filter) { "已完成" -> it.complete; "未完成" -> !it.complete; else -> true } }
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer, bottomBar = { ClientNavigation(model, "downloads") }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("本机上传", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(model::openDownloads) { Text("下载") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "未完成", "已完成").forEach { label -> FilterChip(filter == label, { filter = label }, { Text(label) }) }
            }
            state.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.weight(1f).semantics { contentDescription = "本机上传列表" }, contentPadding = PaddingValues(bottom = 16.dp)) {
                if (items.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_upload), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (state.items.isEmpty()) "把本机文件带回文件库" else "这里还没有${filter}的上传", style = MaterialTheme.typography.titleMedium)
                        Text("在服务器文件页选择上传文件或文件夹，确认目标后开始。本机原文件不会被删除。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ model.tab("files") }) { Text("选择服务器目录") }
                    }
                }
                items(items, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.sourceLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("服务器目录：" + item.targetPath.substringBeforeLast('/').ifEmpty { "/" }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (item.targetPath.substringAfterLast('/') != item.name) Text("服务器名称：" + item.targetPath.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val transferred = maxOf(item.uploaded, state.sent[item.id] ?: 0).coerceAtMost(item.expectedSize)
                        val fraction = if (item.expectedSize > 0) (transferred.toDouble() / item.expectedSize).toFloat().coerceIn(0f, 1f) else if (item.complete) 1f else 0f
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(when (item.status) { "completed" -> "上传完成"; "queued" -> "等待上传"; "running" -> "正在上传"; "paused" -> "已暂停"; "interrupted" -> "上传中断"; "expired" -> "服务器片段已过期"; else -> "上传失败" },
                                style = MaterialTheme.typography.labelMedium, color = if (item.status in setOf("failed", "expired")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (item.complete) readableSize(item.expectedSize) else "${(fraction * 100).toInt().coerceAtMost(99)}% · ${readableSize(transferred)} / ${readableSize(item.expectedSize)}", style = MaterialTheme.typography.labelMedium)
                        }
                        if (!item.complete) LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().height(3.dp), gapSize = 0.dp, drawStopIndicator = {})
                        if (item.active) Text(state.speeds[item.id]?.let { readableSize(it) + "/s" } ?: "正在获取上传速度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.error.isNotEmpty()) Text(item.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!item.complete) TextButton({ if (item.active) model.uploads.pause(item) else model.uploads.resume(item) }, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = "${if (item.active) "暂停上传" else "继续上传"}：${item.name}" }) { Text(if (item.active) "暂停" else "继续") }
                            TextButton({ model.openUploadedFile(item) }, enabled = item.complete && !state.busy) { Text("定位服务器文件") }
                            if (item.complete) TextButton({ remove = item }, enabled = !state.busy) { Text("移除记录") }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    remove?.let { item -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除上传记录？") }, text = { Text("${item.name}\n\n本机原文件和服务器上的文件均保留。") },
        confirmButton = { TextButton({ model.uploads.remove(item); remove = null }) { Text("移除记录") } }, dismissButton = { TextButton({ remove = null }) { Text("取消") } }) }
}
