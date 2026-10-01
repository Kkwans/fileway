package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.player.NativeTrack
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

@Composable fun ClientApp(model: ClientModel) {
    val state by model.state.collectAsStateWithLifecycle()
    BackHandler(state.connected) { if (!model.back()) model.disconnect() }
    if (state.selected != null) { PlayerScreen(model, state.selected!!); return }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp).then(if (!state.connected) Modifier.verticalScroll(rememberScrollState()).imePadding() else Modifier), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(12.dp))
            if (!state.connected) {
                Text("NAS File Browser", style = MaterialTheme.typography.headlineLarge)
                Text("连接服务器，打开你的文件与影片。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                var url by rememberSaveable { mutableStateOf("") }
                var username by rememberSaveable { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(url, { url = it }, label = { Text("服务器地址") }, placeholder = { Text("https://nas.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(username, { username = it }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Button(onClick = { model.connect(url.trim(), username, password) }, enabled = !state.busy && url.isNotBlank() && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp)) { Text("连接服务器") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(state.serverLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = model::disconnect) { Text("切换服务器") }
                }
                Text(state.path.trimEnd('/').substringAfterLast('/').ifBlank { "文件" }, style = MaterialTheme.typography.headlineLarge)
                Text(state.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row {
                    TextButton(onClick = { model.back() }, enabled = state.path != "/") { Text("上一级") }
                    TextButton(onClick = model::retry, enabled = !state.busy) { Text("刷新") }
                }
            }
            state.error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(message, color = MaterialTheme.colorScheme.onErrorContainer); if (state.connected) TextButton(onClick = model::retry) { Text("重试") } }
                }
            }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(state.stage, modifier = Modifier.weight(1f)); TextButton(onClick = model::cancel) { Text("取消") } }
            }
            if (state.connected && !state.busy && state.files.isEmpty() && state.error == null) Text("这个目录还没有文件。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.connected) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.files, key = { it.wirePath.ifEmpty { it.path } }) { file ->
                    FileRow(file) { model.open(file) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
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

private fun readableSize(size: Long): String {
    if (size >= 1L shl 30) return String.format(Locale.ROOT, "%.1f GB", size.toDouble() / (1L shl 30))
    if (size >= 1L shl 20) return String.format(Locale.ROOT, "%.1f MB", size.toDouble() / (1L shl 20))
    return String.format(Locale.ROOT, "%.0f KB", size.toDouble() / 1024)
}
private fun clock(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) }

@Composable private fun PlayerScreen(model: ClientModel, file: ResourceRef) {
    val state by model.player.state.collectAsStateWithLifecycle()
    var audioMenu by remember { mutableStateOf(false) }; var subtitleMenu by remember { mutableStateOf(false) }; var speedMenu by remember { mutableStateOf(false) }
    DisposableEffect(model.player) { onDispose { model.player.detach() } }
    ClientTheme(darkTheme = true) {
        Scaffold(containerColor = Color.Black) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = model::leavePlayer) { Text("返回") }
                    Text(file.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                AndroidView(factory = { context -> VLCVideoLayout(context).also { model.player.attach(it) } }, modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black))
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.error ?: if (state.phase == "正在缓冲") "正在缓冲 ${state.buffering.toInt()}%" else state.phase, color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.width > 0) Text("${state.width} × ${state.height} · 原生播放", style = MaterialTheme.typography.bodySmall)
                    var seek by remember { mutableStateOf<Float?>(null) }
                    Slider(value = seek ?: state.positionMs.toFloat().coerceIn(0f, state.durationMs.toFloat().coerceAtLeast(1f)), onValueChange = { seek = it }, onValueChangeFinished = { seek?.let { model.player.seek(it.toLong()) }; seek = null }, valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f), enabled = state.seekable && state.durationMs > 0)
                    Row(Modifier.fillMaxWidth()) { Text(clock(state.positionMs), modifier = Modifier.weight(1f)); Text(if (state.durationMs > 0) clock(state.durationMs) else "时长读取中") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { model.player.seek(state.positionMs - 10_000) }, enabled = state.seekable) { Text("−10秒") }
                        FilledTonalButton(onClick = model.player::toggle) { Text(if (state.playing) "暂停" else "播放") }
                        TextButton(onClick = { model.player.seek(state.positionMs + 10_000) }, enabled = state.seekable) { Text("+10秒") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Box { TextButton(onClick = { audioMenu = true }, enabled = state.audio.isNotEmpty()) { Text("音轨") }; TrackMenu(audioMenu, { audioMenu = false }, state.audio, state.selectedAudio) { model.player.audio(it); audioMenu = false } }
                        Box { TextButton(onClick = { subtitleMenu = true }, enabled = state.subtitles.isNotEmpty()) { Text("字幕") }; TrackMenu(subtitleMenu, { subtitleMenu = false }, state.subtitles, state.selectedSubtitle) { model.player.subtitle(it); subtitleMenu = false } }
                        Box { TextButton(onClick = { speedMenu = true }) { Text("${state.rate}×") }; DropdownMenu(speedMenu, { speedMenu = false }) { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value -> DropdownMenuItem(text = { Text("${value}×") }, onClick = { model.player.rate(value); speedMenu = false }) } } }
                    }
                }
            }
        }
    }
}
@Composable private fun TrackMenu(expanded: Boolean, dismiss: () -> Unit, tracks: List<NativeTrack>, selected: Int, choose: (Int) -> Unit) {
    DropdownMenu(expanded, dismiss) {
        tracks.forEach { track -> DropdownMenuItem(text = { Column { Text((if (track.id == selected) "✓ " else "") + track.title); if (track.codec.isNotEmpty()) Text(listOf(track.language, track.codec).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall) } }, onClick = { choose(track.id) }) }
    }
}
