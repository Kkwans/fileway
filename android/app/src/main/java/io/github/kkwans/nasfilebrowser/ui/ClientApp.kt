package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.PlaybackSnapshot
import io.github.kkwans.nasfilebrowser.data.ProgressSync
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ClientApp(model: ClientModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val recent by model.recent.collectAsStateWithLifecycle()
    val search by model.search.state.collectAsStateWithLifecycle()
    BackHandler(state.connected) { if (!model.back()) model.disconnect() }
    if (state.selected != null) { PlayerScreen(model, state.selected!!); return }
    if (!state.connected) { ConnectionScreen(model, state); return }
    if (search.open) { SearchScreen(model, state); return }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(state.serverLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = model::disconnect) { Text("切换服务器") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = state.tab == "files", onClick = { model.tab("files") }, label = { Text("文件") })
                FilterChip(selected = state.tab == "recent", onClick = { model.tab("recent") }, label = { Text("最近播放") })
            }
            Text(if (state.tab == "recent") "继续观看" else state.path.trimEnd('/').substringAfterLast('/').ifBlank { "文件" }, style = MaterialTheme.typography.headlineLarge)
            if (state.tab == "files") Text(state.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.tab == "files") FlowRow {
                TextButton(onClick = model::openSearch, enabled = !state.busy) { Text("搜索文件") }
                TextButton(onClick = { model.back() }, enabled = state.path != "/") { Text("上一级") }
                TextButton(onClick = model::retry, enabled = !state.busy) { Text("刷新") }
            }
            state.error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(message, color = MaterialTheme.colorScheme.onErrorContainer); if (state.connected) TextButton(onClick = model::retry) { Text("重试") } }
                }
            }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(state.stage, modifier = Modifier.weight(1f)); TextButton(onClick = model::cancel) { Text("取消") } }
            }
            if (state.tab == "recent") {
                if (recent.isEmpty()) Text("开始观看后，进度会保存在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(recent, key = { it.resourceKey }) { snapshot -> RecentRow(snapshot) { model.openRecent(snapshot) } }
                }
            }
            if (state.tab == "files" && !state.busy && state.files.isEmpty() && state.error == null) Text("这个目录还没有文件。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.tab == "files") LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.files, key = { it.wirePath.ifEmpty { it.path } }) { file ->
                    FileRow(file) { model.open(file) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable private fun RecentRow(snapshot: PlaybackSnapshot, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(snapshot.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${clock(snapshot.positionMs)} / ${clock(snapshot.durationMs)} · ${when (snapshot.sync) {
            ProgressSync.SYNCED -> "已同步"
            ProgressSync.PENDING -> "待同步"
            ProgressSync.IDENTITY_CHANGED -> "文件已变化"
            ProgressSync.UNSUPPORTED -> "仅本机保存"
        }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (snapshot.durationMs > 0) LinearProgressIndicator(progress = { (snapshot.positionMs.toFloat() / snapshot.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NetworkCard(model: ClientModel) {
    val network by model.networkState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var actionMessage by remember(network.authUrl) { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("应用内 Tailscale · ${network.label}", style = MaterialTheme.typography.titleMedium)
            if (network.ips.isNotEmpty()) Text(network.ips.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (network.connected && network.acceptSubnets) Text("已接收批准的子网路由", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (network.state == "NeedsMachineAuth") Text("请在 Tailscale 管理页面批准这台设备。")
            network.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!network.connected) TextButton(onClick = model::connectNetwork, enabled = network.state != "Starting") { Text(if (network.state == "Starting") "正在连接" else "连接 Tailscale") }
                if (network.authUrl.isNotEmpty()) TextButton(onClick = {
                    val uri = Uri.parse(network.authUrl)
                    val host = uri.host.orEmpty()
                    if (uri.scheme != "https" || (host != "tailscale.com" && !host.endsWith(".tailscale.com"))) {
                        actionMessage = "登录地址无法验证，请重新连接。"
                    } else try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)); actionMessage = null }
                    catch (_: Exception) { actionMessage = "无法打开浏览器，可复制登录链接后手动打开。" }
                }) { Text("打开登录页") }
                if (network.state in listOf("Starting", "NeedsLogin", "NeedsMachineAuth", "Running")) TextButton(onClick = { model.stopNetwork() }) { Text(if (network.connected) "断开" else "取消连接") }
            }
            if (network.authUrl.isNotEmpty()) TextButton(onClick = {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Tailscale 登录", network.authUrl)
                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    clipboard.setPrimaryClip(clip)
                    actionMessage = "登录链接已复制，请勿分享。"
                } catch (_: Exception) { actionMessage = "无法复制链接，请检查系统权限后重试。" }
            }) { Text("复制登录链接") }
            if (network.state !in listOf("Unconfigured", "Configured", "Closed", "Starting")) TextButton(onClick = { confirmLogout = true }) { Text("退出 Tailscale 账号") }
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出 Tailscale？") }, text = { Text("将停止当前播放并退出内嵌节点，下次连接需要重新登录。") }, confirmButton = { TextButton(onClick = { confirmLogout = false; model.stopNetwork(logout = true) }) { Text("退出账号") } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}

@Composable private fun FileRow(file: ResourceRef, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(if (file.directory) "目录" else file.name.substringAfterLast('.', "文件").uppercase().take(4), style = MaterialTheme.typography.labelSmall) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (file.directory) "文件夹" else readableSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun readableSize(size: Long): String {
    if (size >= 1L shl 30) return String.format(Locale.ROOT, "%.1f GB", size.toDouble() / (1L shl 30))
    if (size >= 1L shl 20) return String.format(Locale.ROOT, "%.1f MB", size.toDouble() / (1L shl 20))
    return String.format(Locale.ROOT, "%.0f KB", size.toDouble() / 1024)
}
internal fun clock(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) }
