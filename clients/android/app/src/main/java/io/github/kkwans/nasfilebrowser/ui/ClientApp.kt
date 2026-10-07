package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.PlaybackSnapshot
import io.github.kkwans.nasfilebrowser.data.ProgressSync
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ClientApp(model: ClientModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val recent by model.recent.collectAsStateWithLifecycle()
    val search by model.search.state.collectAsStateWithLifecycle()
    val pageState = key(state.previewScope) { rememberSaveableStateHolder() }
    val activity = LocalActivity.current
    BackHandler(state.connected) { if (!model.back()) activity?.finish() }
    BackHandler(state.startupPending) { model.cancel() }
    if (state.startupPending) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(state.stage.ifBlank { "正在打开文件库" }, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = model::cancel) { Text("取消") }
            }
        }
        return
    }
    if (state.image != null) { ImageScreen(model, state.image!!); return }
    if (state.selected != null) { PlayerScreen(model, state.selected!!); return }
    if (!state.connected) { ConnectionScreen(model, state); return }
    pageState.SaveableStateProvider(if (search.open) "search" else state.tab) {
        LibraryTheme {
            when {
                search.open -> SearchScreen(model, state)
                state.tab == "files" -> BrowserScreen(model, state)
                state.tab == "settings" -> SettingsScreen(model, state)
                state.tab == "library" -> FavoritesScreen(model, state)
                else -> RecentScreen(model, state, recent)
            }
        }
    }
}

@Composable private fun RecentScreen(model: ClientModel, state: ClientState, recent: List<PlaybackSnapshot>) {
    val colors = MaterialTheme.colorScheme.copy(surface = MaterialTheme.colorScheme.surfaceContainer)
    var details by remember(state.previewScope) { mutableStateOf<ResourceRef?>(null) }
    MaterialTheme(colorScheme = colors) {
        Scaffold(containerColor = colors.surface, bottomBar = { ClientNavigation(model, "recent") }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("继续观看", style = MaterialTheme.typography.titleLarge, color = colors.onBackground, modifier = Modifier.weight(1f))
                    if (recent.isNotEmpty()) Text("${recent.size} 项", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                state.error?.let { message ->
                    Surface(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = colors.errorContainer, shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) { Text(message, color = colors.onErrorContainer); if (state.connected) TextButton(onClick = model::retry) { Text("重试") } }
                    }
                }
                state.notice?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                if (state.busy) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(state.stage, modifier = Modifier.weight(1f)); TextButton(onClick = model::cancel) { Text("取消") } }
                }
                LazyColumn(Modifier.weight(1f).semantics { contentDescription = "最近播放列表" }, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (recent.isEmpty() && !state.busy) item {
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = colors.background) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(painterResource(R.drawable.ic_history), null, Modifier.size(24.dp), tint = colors.primary)
                                Text("还没有播放记录", style = MaterialTheme.typography.titleMedium)
                                Text("从文件页打开视频，观看进度会保存在这里。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                TextButton(onClick = { model.tab("files") }) { Text("浏览文件") }
                            }
                        }
                    }
                    items(recent, key = { it.resourceKey }) { snapshot ->
                        val file = ResourceRef(snapshot.path, snapshot.wirePath, snapshot.name, false, "video", 0)
                        FileEntry(model, file, FileLayout.DETAIL, enabled = !state.busy,
                            open = { model.openRecent(snapshot) }, details = { details = file }, metadata = { RecentProgress(snapshot) })
                    }
                }
            }
            details?.let { file -> FileDetailsDialog(file, showSize = false, openEnabled = !state.busy,
                actions = { FavoriteFileAction(model, file) },
                onLocation = { details = null; model.openContainingDirectory(file) }, onDismiss = { details = null }) }
        }
    }
}

@Composable private fun RecentProgress(snapshot: PlaybackSnapshot) {
    val colors = MaterialTheme.colorScheme
    Text("${if (snapshot.sync == ProgressSync.IDENTITY_CHANGED) "原记录 " else ""}${clock(snapshot.positionMs)} / ${if (snapshot.durationMs > 0) clock(snapshot.durationMs) else "时长待确认"}",
        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
    Text(when (snapshot.sync) {
            ProgressSync.SYNCED -> "已同步"
            ProgressSync.PENDING -> "待同步"
            ProgressSync.IDENTITY_CHANGED -> "文件已变化"
            ProgressSync.UNSUPPORTED -> "仅本机保存"
        }, style = MaterialTheme.typography.labelSmall, color = if (snapshot.sync == ProgressSync.IDENTITY_CHANGED) colors.error else colors.onSurfaceVariant)
    if (snapshot.durationMs > 0 && snapshot.sync != ProgressSync.IDENTITY_CHANGED) {
        LinearProgressIndicator(progress = { (snapshot.positionMs.toDouble() / snapshot.durationMs).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp), color = colors.primary,
            trackColor = colors.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NetworkCard(model: ClientModel) {
    val network by model.networkState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var actionMessage by remember(network.authUrl) { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("应用内 Tailscale · ${network.label}", style = MaterialTheme.typography.bodyMedium)
            if (network.ips.isNotEmpty()) Text(network.ips.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (network.connected && network.acceptSubnets) Text("已启用子网路由", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (network.state == "NeedsMachineAuth") Text("请在 Tailscale 管理页面批准这台设备。")
            network.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val actionColors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                if (!network.connected && network.authUrl.isEmpty() && network.state != "NeedsMachineAuth") FilledTonalButton(onClick = model::connectNetwork, enabled = network.state != "Starting", colors = actionColors, shape = RoundedCornerShape(8.dp)) { Text(if (network.state == "Starting") "正在连接" else "连接 Tailscale") }
                if (network.authUrl.isNotEmpty()) FilledTonalButton(onClick = {
                    val uri = Uri.parse(network.authUrl)
                    val host = uri.host.orEmpty()
                    if (uri.scheme != "https" || (host != "tailscale.com" && !host.endsWith(".tailscale.com"))) {
                        actionMessage = "登录地址无法验证，请重新连接。"
                    } else try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)); actionMessage = null }
                    catch (_: Exception) { actionMessage = "无法打开浏览器，可复制登录链接后手动打开。" }
                }, colors = actionColors, shape = RoundedCornerShape(8.dp)) { Text("打开登录页") }
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
            if (network.canLogout) TextButton(onClick = { confirmLogout = true }) { Text("退出 Tailscale 账号") }
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出 Tailscale？") }, text = { Text("将停止当前播放并退出内嵌节点，下次连接需要重新登录。") }, confirmButton = { TextButton(onClick = { confirmLogout = false; model.stopNetwork(logout = true) }) { Text("退出账号") } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}

internal fun readableSize(size: Long): String {
    if (size >= 1L shl 30) return String.format(Locale.ROOT, "%.1f GB", size.toDouble() / (1L shl 30))
    if (size >= 1L shl 20) return String.format(Locale.ROOT, "%.1f MB", size.toDouble() / (1L shl 20))
    return String.format(Locale.ROOT, "%.0f KB", size.toDouble() / 1024)
}
internal fun clock(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) }
