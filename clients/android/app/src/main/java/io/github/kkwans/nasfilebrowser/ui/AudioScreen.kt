package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AudioScreen(model: ClientModel, file: ResourceRef) {
    val state by model.player.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val queue = client.mediaQueue
    var dragging by remember(file) { mutableStateOf<Float?>(null) }
    var menu by remember(file) { mutableStateOf<String?>(null) }
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false; controller?.isAppearanceLightNavigationBars = false
        onDispose { oldStatus?.let { controller?.isAppearanceLightStatusBars = it }; oldNavigation?.let { controller?.isAppearanceLightNavigationBars = it } }
    }
    BackHandler { model.leavePlayer() }
    Column(Modifier.fillMaxSize().background(Color(0xFF10141D)).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(model::leavePlayer, Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_arrow_back), "关闭音频", tint = Color.White) }
            Text("正在播放", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium)
            IconButton({ menu = "queue" }, Modifier.size(48.dp), enabled = queue != null) {
                Icon(painterResource(R.drawable.ic_playlist_play), "音频播放列表", tint = Color.White)
            }
        }
        Box(Modifier.size(180.dp).background(Color(0xFF1D2D48), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_audio), null, Modifier.size(84.dp), tint = Color(0xFF75B6FF))
        }
        Text(file.name, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(if (file.downloadId.isNotEmpty()) "本机下载" else client.serverLabel, color = Color(0xFFB8C3D5))
        if (client.busy || state.waitingForBuffer) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color(0xFF75B6FF), strokeWidth = 2.dp)
            Text(if (client.busy) client.stage else "正在缓冲 ${state.buffering.toInt()}%", color = Color.White)
        }
        (client.error ?: state.error)?.let { message ->
            Text(message, color = Color(0xFFFFB4AB)); OutlinedButton(model::retryPlayback) { Text("重试播放", color = Color.White) }
        }
        Column(Modifier.fillMaxWidth()) {
            PlayerSlider(dragging ?: state.positionMs.toFloat(), state.durationMs.coerceAtLeast(1).toFloat(), "音频播放进度", !client.busy && state.seekable,
                { dragging = it }, { dragging?.let { model.player.seek(it.toLong()) }; dragging = null },
                description = "${audioTime(state.positionMs)} / ${audioTime(state.durationMs)}", downloadedValue = state.bufferedPositionMs.toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(audioTime(dragging?.toLong() ?: state.positionMs), color = Color.White)
                Text(audioTime(state.durationMs), color = Color(0xFFB8C3D5))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(model::previousMedia, Modifier.size(56.dp), enabled = queue?.hasPrevious == true) {
                Icon(painterResource(R.drawable.ic_skip_previous), "上一首", tint = if (queue?.hasPrevious == true) Color.White else Color.Gray)
            }
            FilledIconButton({ model.player.toggle() }, Modifier.size(72.dp), enabled = !client.busy && state.error == null,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF4A9CFF), contentColor = Color.White)) {
                Icon(painterResource(if (state.playing) R.drawable.art_pause else R.drawable.art_play), if (state.playing) "暂停音频" else "播放音频", Modifier.size(36.dp))
            }
            IconButton(model::nextMedia, Modifier.size(56.dp), enabled = queue?.hasNext == true) {
                Icon(painterResource(R.drawable.ic_skip_next), "下一首", tint = if (queue?.hasNext == true) Color.White else Color.Gray)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton({ menu = "speed" }) { Text("${state.rate}× 倍速", color = Color.White) }
            TextButton({ menu = "audio" }, enabled = state.audio.any { it.id >= 0 }) { Text("音轨", color = Color.White) }
        }
        Text("离开应用时暂停播放", color = Color(0xFFB8C3D5), style = MaterialTheme.typography.bodySmall)
    }
    if (menu != null) ModalBottomSheet({ menu = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        when (menu) {
            "queue" -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(bottom = 24.dp)) {
                item { Text("播放列表 · ${queue?.items?.size ?: 0} 首", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) }
                itemsIndexed(queue?.items.orEmpty()) { index, item -> ListItem(headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Text("${index + 1}") }, trailingContent = { if (index == queue?.index) Icon(painterResource(R.drawable.ic_audio), "当前音频") },
                    modifier = Modifier.clickable { model.navigateMedia(index); menu = null }) }
            }
            "speed" -> Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("播放速度", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
                listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { rate -> TextButton({ model.player.rate(rate); menu = null }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$rate×") } }
            }
            "audio" -> Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("音轨", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
                state.audio.filter { it.id >= 0 }.forEach { track -> TextButton({ model.player.audio(track.id); menu = null }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(track.title) } }
            }
        }
    }
}

private fun audioTime(value: Long): String {
    val seconds = value.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}
