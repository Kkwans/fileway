package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.RecentAccessController
import io.github.kkwans.nasfilebrowser.data.RecentAccessEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun RecentAccessContent(
    controller: RecentAccessController, connected: Boolean, onConnect: () -> Unit,
    onOpen: (RecentAccessEntry) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        if (!connected || state.scope.isEmpty()) {
            RecentAccessMessage("连接后查看最近访问", "成功进入目录或打开文件后，与同一账号的网页端同步。", "连接服务器", onConnect)
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("与网页端同步 · 最近 100 条", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { controller.refresh() }, enabled = !state.loading) {
                Icon(painterResource(R.drawable.ic_refresh), "刷新最近访问")
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        state.recordWarning?.let { message ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_info), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        if (state.error != null && state.items.isEmpty()) {
            RecentAccessMessage("无法读取最近访问", state.error!!, "重试", { controller.refresh() })
        } else if (state.loading && !state.loaded && state.items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("正在读取最近访问…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (state.items.isEmpty()) {
            RecentAccessMessage("还没有最近访问", "成功进入目录或打开文件后，会按访问时间出现在这里。", "刷新", { controller.refresh() })
        } else {
            state.error?.let { message ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("刷新失败，当前显示上次读取的记录。$message", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { controller.refresh() }, enabled = !state.loading) { Text("重试") }
                }
            }
            key(state.scope) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "服务端最近访问列表" },
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.items, key = { it.id }) { entry ->
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = entry.openable, role = Role.Button) {
                                if (controller.state.value.scope == state.scope && controller.state.value.items.any { it == entry }) onOpen(entry)
                            }.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(painterResource(if (entry.isDir) R.drawable.ic_folder else R.drawable.ic_history), null,
                                    Modifier.padding(top = 2.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(entry.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    val time = remember(entry.accessedAt) {
                                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())
                                            .format(Instant.ofEpochMilli(entry.accessedAt))
                                    }
                                    Text("${if (entry.isDir) "文件夹" else "文件"} · $time", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!entry.openable) Text("原始路径无法确认，请从文件列表重新访问此项", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                                if (entry.openable) Icon(painterResource(R.drawable.ic_arrow_forward), null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ColumnScope.RecentAccessMessage(title: String, message: String, action: String, onAction: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_history), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onAction) { Text(action) }
        }
    }
}
