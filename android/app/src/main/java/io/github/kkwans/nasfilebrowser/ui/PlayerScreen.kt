package io.github.kkwans.nasfilebrowser.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.player.NativeTrack
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

private enum class PlayerSheet { AUDIO, SUBTITLE, SPEED, VOLUME, SOURCE }
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun PlayerScreen(model: ClientModel, file: ResourceRef) {
    val state by model.player.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val liveState by rememberUpdatedState(state)
    val context = LocalContext.current
    val landscapeOrientation = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current
    val accessibility = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var exploration by remember { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    var visible by remember(file) { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var seek by remember(file) { mutableStateOf<Float?>(null) }
    val blocked = state.error != null || client.error != null || client.busy || !state.playing || state.phase != "正在播放"
    fun touch() { visible = true; interaction++ }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploration = it }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    LaunchedEffect(visible, interaction, sheet, seek != null, blocked, exploration) {
        if (blocked || exploration || sheet != null || seek != null) visible = true
        else if (visible) {
            val timeout = accessibility.getRecommendedTimeoutMillis(3000,
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS)
            delay(timeout.toLong())
            visible = false
        }
    }
    val window = context.activity()?.window
    DisposableEffect(window, model.player) {
        val oldKeep = view.keepScreenOn
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            model.player.detach()
            view.keepScreenOn = oldKeep
            controller?.show(WindowInsetsCompat.Type.systemBars())
            oldStatus?.let { controller?.isAppearanceLightStatusBars = it }
            oldNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
    SideEffect {
        view.keepScreenOn = state.playing
        window?.let {
            val controller = WindowCompat.getInsetsController(it, view)
            if (!landscapeOrientation || exploration) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    fun fullscreen(landscape: Boolean) {
        touch()
        context.activity()?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    DisposableEffect(context) {
        val owner = context.activity()
        val original = owner?.requestedOrientation
        onDispose { original?.let { owner.requestedOrientation = it } }
    }
    ClientTheme(darkTheme = true) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            BoxWithConstraints(Modifier.fillMaxSize().background(PlayerCanvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
                val landscape = maxWidth > maxHeight
                val portraitStageHeight = maxWidth / (16f / 9f) + 96.dp
                Column(Modifier.fillMaxSize()) {
                    if (!landscape) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerIcon(R.drawable.ic_arrow_back, "返回文件", model::leavePlayer)
                        Text("正在观看", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        PlayerIcon(R.drawable.ic_info, "播放来源", { touch(); sheet = PlayerSheet.SOURCE })
                    }
                    // Portrait keeps transport below the picture. Fullscreen
                    // uses the entire viewport with controls over the video;
                    // hiding the HUD never changes the native surface size.
                    val stage = if (landscape) Modifier.weight(1f) else Modifier.fillMaxWidth().height(portraitStageHeight)
                    Box(stage.background(Color.Black)) {
                        Box(Modifier.fillMaxSize().padding(bottom = if (landscape) 0.dp else 96.dp)) {
                            AndroidView(factory = { VLCVideoLayout(it).also(model.player::attach) }, modifier = Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().semantics {
                                contentDescription = "视频画面"
                                onClick("显示播放控制") { touch(); true }
                            }.pointerInput(file, blocked, exploration) {
                                detectTapGestures(onTap = { if (!blocked && !exploration) visible = !visible else touch(); interaction++ },
                                    onDoubleTap = { offset ->
                                        touch()
                                        if (liveState.seekable) model.player.seek(liveState.positionMs + if (offset.x < size.width / 2) -10_000 else 10_000)
                                    })
                            })
                            if (state.phase in setOf("正在打开视频", "正在缓冲", "正在跳转")) {
                                Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color(0xCC151515)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color = PlayerAccent, strokeWidth = 2.dp)
                                    Text(if (state.phase == "正在缓冲") "缓冲 ${state.buffering.toInt()}%" else state.phase, fontSize = 13.sp)
                                }
                            }
                        }
                        if (visible && landscape) Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            PlayerIcon(R.drawable.ic_arrow_back, "返回文件", model::leavePlayer)
                            Text(file.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            PlayerIcon(R.drawable.ic_info, "播放来源", { touch(); sheet = PlayerSheet.SOURCE })
                        }
                        if (visible) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))).padding(horizontal = if (landscape) 20.dp else 12.dp)) {
                            PlayerSlider(seek ?: state.positionMs.toFloat().coerceIn(0f, state.durationMs.toFloat().coerceAtLeast(1f)), state.durationMs.toFloat().coerceAtLeast(1f),
                                "播放进度", state.seekable && state.durationMs > 0,
                                { seek = it; touch() }, { seek?.let { model.player.seek(it.toLong()) }; seek = null; touch() },
                                clock((seek ?: state.positionMs.toFloat()).toLong()) + "，共 " + clock(state.durationMs))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                PlayerIcon(if (state.playing) R.drawable.art_pause else R.drawable.art_play, if (state.playing) "暂停播放" else "开始播放", { touch(); model.togglePlayback() }, !client.busy)
                                if (landscape) {
                                    PlayerIcon(R.drawable.ic_replay_10, "后退十秒", { touch(); model.player.seek(state.positionMs - 10_000) }, state.seekable)
                                    PlayerIcon(R.drawable.ic_forward_10, "快进十秒", { touch(); model.player.seek(state.positionMs + 10_000) }, state.seekable)
                                }
                                Text(clock((seek ?: state.positionMs.toFloat()).toLong()) + " / " + if (state.durationMs > 0) clock(state.durationMs) else "--:--",
                                    color = Color(0xFFDADADA), fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
                                if (landscape) {
                                    PlayerLabel("音轨", "选择音轨", state.audio.isNotEmpty()) { touch(); sheet = PlayerSheet.AUDIO }
                                    PlayerLabel("字幕", "选择字幕", state.subtitles.isNotEmpty()) { touch(); sheet = PlayerSheet.SUBTITLE }
                                    PlayerLabel("${state.rate}×", "播放速度") { touch(); sheet = PlayerSheet.SPEED }
                                    PlayerIcon(R.drawable.art_volume, "播放器音量", { touch(); sheet = PlayerSheet.VOLUME })
                                } else PlayerLabel("${state.rate}×", "播放速度") { touch(); sheet = PlayerSheet.SPEED }
                                PlayerIcon(if (landscape) R.drawable.art_fullscreen_off else R.drawable.art_fullscreen_on, if (landscape) "退出全屏" else "横屏全屏", { fullscreen(landscape) })
                            }
                        }
                    }
                    if (!landscape) Column(Modifier.weight(1f).semantics { contentDescription = "播放详情" }.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(file.name, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                            Text(client.serverLabel, color = PlayerSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("原生播放", color = PlayerAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                if (state.width > 0) Text("${state.width} × ${state.height}", color = PlayerSecondary, fontSize = 12.sp)
                                Text(state.phase, color = PlayerSecondary, fontSize = 12.sp)
                            }
                        }
                        HorizontalDivider(color = Color(0xFF29292C))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            JumpAction(R.drawable.ic_replay_10, "后退十秒", state.seekable) { touch(); model.player.seek(state.positionMs - 10_000) }
                            Text("双击画面快进 / 后退", color = PlayerSecondary, fontSize = 12.sp)
                            JumpAction(R.drawable.ic_forward_10, "快进十秒", state.seekable) { touch(); model.player.seek(state.positionMs + 10_000) }
                        }
                        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(PlayerPanel)) {
                            DetailAction(R.drawable.ic_audio, "音轨", state.audio.firstOrNull { it.id == state.selectedAudio }?.title ?: "暂无音轨", "选择音轨", state.audio.isNotEmpty()) { touch(); sheet = PlayerSheet.AUDIO }
                            HorizontalDivider(Modifier.padding(start = 52.dp), color = Color(0xFF2B2B2F))
                            DetailAction(R.drawable.ic_subtitles, "字幕", state.subtitles.firstOrNull { it.id == state.selectedSubtitle }?.title ?: "关闭", "选择字幕", state.subtitles.isNotEmpty()) { touch(); sheet = PlayerSheet.SUBTITLE }
                            HorizontalDivider(Modifier.padding(start = 52.dp), color = Color(0xFF2B2B2F))
                            DetailAction(R.drawable.art_volume, "音量", "${state.volume}%", "播放器音量") { touch(); sheet = PlayerSheet.VOLUME }
                        }
                        client.progressStatus?.let { Text(it, color = PlayerSecondary, fontSize = 12.sp) }
                    }
                    if (state.error != null || client.error != null || client.busy || client.progressStatus?.contains("失败") == true || client.progressStatus?.contains("待同步") == true) {
                        Row(Modifier.fillMaxWidth().background(PlayerPanel).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(state.error ?: client.error ?: if (client.busy) client.stage else client.progressStatus.orEmpty(), color = if (state.error != null || client.error != null) Color(0xFFFFA0AC) else PlayerSecondary, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            PlayerLabel(if (client.busy) "取消" else "重试", "处理播放状态") {
                                if (client.busy) model.cancel() else if (state.error != null) model.open(file) else model.retryProgress()
                            }
                        }
                    }
                }
                if (sheet != null) PlayerPanel(landscape, when (sheet) { PlayerSheet.AUDIO -> "音轨"; PlayerSheet.SUBTITLE -> "字幕"; PlayerSheet.SPEED -> "播放速度"; PlayerSheet.VOLUME -> "播放器音量"; else -> "播放来源" }, { sheet = null; touch() }) {
                    when (sheet) {
                        PlayerSheet.AUDIO, PlayerSheet.SUBTITLE -> {
                            val audio = sheet == PlayerSheet.AUDIO
                            TrackChoices(if (audio) state.audio else state.subtitles, if (audio) state.selectedAudio else state.selectedSubtitle) {
                                if (audio) model.player.audio(it) else model.player.subtitle(it)
                                sheet = null; touch()
                            }
                        }
                        PlayerSheet.SPEED -> LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                            itemsIndexed(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) { _, value ->
                                Choice("${value}×", if (value == 1f) "正常速度" else "", state.rate == value) { model.player.rate(value); sheet = null; touch() }
                            }
                        }
                        PlayerSheet.VOLUME -> Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("${state.volume}%", fontSize = 32.sp, fontWeight = FontWeight.Medium)
                            PlayerSlider(state.volume.toFloat(), 100f, "播放器音量", true, { model.player.volume(it.toInt()) })
                            Text("设备音量仍可通过音量键调整。", fontSize = 13.sp, color = PlayerSecondary)
                            Spacer(Modifier.height(8.dp))
                        }
                        PlayerSheet.SOURCE -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { SourceField("文件", file.name) }
                            item { SourceField("服务器与账号", client.serverLabel) }
                            item { SourceField("路径", file.path) }
                            item { SourceField("播放方式", "原生播放") }
                            if (state.width > 0) item { SourceField("画面", "${state.width} × ${state.height}") }
                            if (state.durationMs > 0) item { SourceField("全片时长", clock(state.durationMs)) }
                            client.progressStatus?.let { item { SourceField("续播", it) } }
                        }
                        null -> Unit
                    }
                }
            }
        }
    }
}

private val PlayerCanvas = Color(0xFF141416)
private val PlayerPanel = Color(0xFF202023)
private val PlayerAccent = Color(0xFFFF80A6)
private val PlayerSecondary = Color(0xFFB5B5BE)

@Composable private fun PlayerIcon(icon: Int, label: String, click: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = click, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), label, Modifier.size(22.dp), tint = Color.White.copy(alpha = if (enabled) 0.94f else 0.38f))
    }
}
@Composable private fun PlayerLabel(text: String, label: String, enabled: Boolean = true, click: () -> Unit) {
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = click).clearAndSetSemantics {
        contentDescription = label
        stateDescription = text
        role = Role.Button
        if (!enabled) disabled()
    }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = if (enabled) 0.94f else 0.38f))
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerSlider(value: Float, maximum: Float, label: String, enabled: Boolean, change: (Float) -> Unit, finish: () -> Unit = {}, description: String = "${value.toInt()}%") {
    // Preserve Material's drag and keyboard handling. Export one named range
    // with meaningful time/volume state, rather than split label/range nodes
    // and the default raw millisecond number. Accessibility uses the same
    // change/finish callbacks as direct manipulation.
    Box(Modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = label
        stateDescription = description
        progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, maximum), 0f..maximum)
        if (!enabled) disabled()
        setProgress { target ->
            val next = target.coerceIn(0f, maximum)
            if (!enabled || !next.isFinite() || next == value) false else { change(next); finish(); true }
        }
    }) {
        Slider(value = value.coerceIn(0f, maximum), onValueChange = change, onValueChangeFinished = finish, valueRange = 0f..maximum, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        thumb = { Box(Modifier.size(10.dp).background(if (enabled) PlayerAccent else PlayerSecondary, CircleShape)) },
        track = { Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            val y = size.height / 2
            drawLine(Color.White.copy(alpha = 0.28f), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
            drawLine(if (enabled) PlayerAccent else PlayerSecondary, Offset(0f, y), Offset(size.width * (value / maximum).coerceIn(0f, 1f), y), 3.dp.toPx(), StrokeCap.Round)
        } })
    }
}
@Composable private fun JumpAction(icon: Int, label: String, enabled: Boolean, click: () -> Unit) {
    PlayerIcon(icon, label, click, enabled)
}
@Composable private fun DetailAction(icon: Int, title: String, value: String, label: String, enabled: Boolean = true, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = enabled, role = Role.Button, onClick = click).clearAndSetSemantics {
        contentDescription = label
        stateDescription = value
        role = Role.Button
        if (!enabled) disabled()
    }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = PlayerSecondary)
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(value, modifier = Modifier.weight(1f), fontSize = 13.sp, color = PlayerSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("›", color = PlayerSecondary, fontSize = 20.sp)
    }
}
@Composable private fun PlayerPanel(landscape: Boolean, title: String, dismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = "播放设置" }) {
            Box(Modifier.fillMaxSize().clickable(onClick = dismiss))
            val panel = if (landscape) Modifier.align(Alignment.CenterEnd).width(maxWidth.coerceAtMost(360.dp)).fillMaxHeight()
                else Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.75f)
            Column(panel.clip(if (landscape) RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(PlayerPanel).pointerInput(Unit) { detectTapGestures(onTap = {}) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    PlayerIcon(R.drawable.art_close, "关闭播放设置", dismiss)
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Color(0xFF323236))
                content()
            }
        }
    }
}
@Composable private fun TrackChoices(tracks: List<NativeTrack>, selected: Int, choose: (Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            val duplicate = tracks.count { it.title == track.title } > 1
            Choice(track.title + if (duplicate) " · ${index + 1}" else "", listOf(track.language.takeUnless { it == "und" }.orEmpty(), track.codec).filter { it.isNotBlank() }.joinToString(" · "), selected == track.id) { choose(track.id) }
        }
    }
}
@Composable private fun Choice(title: String, detail: String, selected: Boolean, choose: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) PlayerAccent.copy(alpha = 0.10f) else Color.Transparent)
        .selectable(selected, role = Role.RadioButton, onClick = choose).padding(horizontal = 12.dp, vertical = 14.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, color = if (selected) PlayerAccent else Color.White)
            if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = PlayerSecondary)
        }
        if (selected) Icon(painterResource(R.drawable.art_check), null, Modifier.size(20.dp), tint = PlayerAccent)
    }
}
@Composable private fun SourceField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = PlayerSecondary)
        SelectionContainer { Text(value, fontSize = 15.sp, lineHeight = 22.sp) }
    }
}
