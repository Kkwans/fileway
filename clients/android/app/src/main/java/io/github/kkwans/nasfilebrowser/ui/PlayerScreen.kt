package io.github.kkwans.nasfilebrowser.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Display
import android.view.accessibility.AccessibilityManager
import android.os.SystemClock
import android.media.AudioManager
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import io.github.kkwans.nasfilebrowser.data.parsePlaybackRate
import io.github.kkwans.nasfilebrowser.data.TextSubtitleAppearance
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.roundToInt
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.player.NativeTrack
import io.github.kkwans.nasfilebrowser.player.SeekGestureAccumulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import io.github.kkwans.nasfilebrowser.player.PlayerViewport

private enum class PlayerSheet { AUDIO, SUBTITLE, SPEED, VOLUME, BRIGHTNESS, SOURCE, EXTERNAL, QUEUE }
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun PlayerScreen(model: ClientModel, file: ResourceRef) {
    val state by model.player.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val downloads by model.downloads.state.collectAsStateWithLifecycle()
    val queue = client.mediaQueue
    val liveState by rememberUpdatedState(state)
    val holdRate by model.playbackPreferences.holdRate.collectAsStateWithLifecycle()
    val savedSubtitleAppearance by model.playbackPreferences.textSubtitleAppearance.collectAsStateWithLifecycle()
    var subtitlePreview by remember(savedSubtitleAppearance) { mutableStateOf(savedSubtitleAppearance) }
    LaunchedEffect(subtitlePreview) { model.player.textSubtitleAppearance(subtitlePreview) }
    val liveHoldRate by rememberUpdatedState(holdRate)
    var holding by remember(file) { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    var customRate by remember { mutableStateOf("") }
    var customHold by remember { mutableStateOf("") }
    var speedError by remember { mutableStateOf<String?>(null) }
    var changingDecoder by remember { mutableStateOf(false) }
    var subtitleOffset by remember(file) { mutableStateOf("") }
    var subtitleOffsetError by remember(file) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val maximumVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    var mediaVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val gestureEdge = with(LocalDensity.current) { 24.dp.toPx() }
    var draggingVertical by remember(file) { mutableStateOf(false) }
    var dragLeft by remember(file) { mutableStateOf(false) }
    var dragStart by remember(file) { mutableFloatStateOf(0f) }
    val landscapeOrientation = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current
    val accessibility = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var exploration by remember { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    var visible by remember(file) { mutableStateOf(true) }
    var touchLocked by remember(file) { mutableStateOf(false) }
    var orientationBeforeLock by remember { mutableStateOf<Int?>(null) }
    val gestureSeek = remember(file) { SeekGestureAccumulator() }
    var gestureMessage by remember(file) { mutableStateOf<String?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var expandedTitle by remember(file) { mutableStateOf(false) }
    var seek by remember(file) { mutableStateOf<Float?>(null) }
    val seekPreview = rememberSeekPreview(model, file, state.mediaGeneration, seek != null)
    var showRequest by remember(file) { mutableStateOf(false) }
    val feedback = remember { SnackbarHostState() }
    var documentGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
    var readingDocument by remember(file) { mutableStateOf(false) }
    val subtitleDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requestedGeneration = documentGeneration
        documentGeneration = null
        if (uri != null && requestedGeneration == model.player.state.value.mediaGeneration) {
            uiScope.launch {
                readingDocument = true
                try {
                    // Only the URI selected by the user is read. No storage-wide
                    // permission or persisted URI grant is needed for this session.
                    val name = withContext(Dispatchers.IO) {
                        require(uri.scheme == "content")
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                        } ?: error("Missing subtitle filename")
                    }
                    if (requestedGeneration != model.player.state.value.mediaGeneration) return@launch
                    if (name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) !in setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")) {
                        feedback.showSnackbar("请选择 SRT、VTT、ASS、SSA 或 TTML 字幕")
                    } else if (!model.player.addSubtitle(uri.toString(), name)) {
                        feedback.showSnackbar("视频仍在加载，请稍后重试添加字幕")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (requestedGeneration == model.player.state.value.mediaGeneration) feedback.showSnackbar("无法读取所选字幕，请重新选择文件")
                } finally { readingDocument = false }
            }
        }
    }
    LaunchedEffect(state.operationError) {
        state.operationError?.let { message -> feedback.showSnackbar(message); model.player.clearOperationError(message) }
    }
    LaunchedEffect(client.busy, file) {
        showRequest = false
        if (client.busy) { delay(200); showRequest = true }
    }
    LaunchedEffect(client.progressStatus) {
        if (client.progressStatus == "续播保存失败，请重试" &&
            feedback.showSnackbar("续播未能保存", actionLabel = "重试", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
            model.retryProgress()
        }
    }
    val blocked = state.error != null || client.error != null || client.busy || !state.playing || state.phase != "正在播放"
    val liveBlocked by rememberUpdatedState(blocked)
    fun touch() { visible = true; interaction++ }
    fun step(delta: Long) { gestureSeek.reset(); touch(); model.player.seek(liveState.positionMs + delta) }
    fun lockTouch() { touchLocked = true; sheet = null; gestureSeek.reset() }
    fun lockOrientation() {
        val owner = context.activity() ?: return
        val previous = orientationBeforeLock
        if (previous == null) {
            orientationBeforeLock = owner.requestedOrientation
            owner.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            owner.requestedOrientation = previous
            orientationBeforeLock = null
        }
        touch()
    }
    BackHandler(touchLocked) { touchLocked = false; touch() }
    LaunchedEffect(exploration) { if (exploration) touchLocked = false }
    LaunchedEffect(gestureMessage, interaction) { if (gestureMessage != null) { delay(1_000); gestureMessage = null } }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploration = it }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    LaunchedEffect(visible, interaction, sheet, seek != null, blocked, exploration, draggingVertical) {
        if (blocked || exploration || sheet != null || seek != null || draggingVertical) visible = true
        else if (visible) {
            val timeout = accessibility.getRecommendedTimeoutMillis(3000,
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS)
            delay(timeout.toLong())
            visible = false
        }
    }
    val window = context.activity()?.window
    var brightness by remember(window) { mutableFloatStateOf(window?.attributes?.screenBrightness ?: -1f) }
    fun actualBrightness(): Float = brightness.takeIf { it >= 0f }
        ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(.02f, 1f)
    fun changeBrightness(value: Float) {
        window?.let { owner ->
            brightness = if (value < 0) -1f else value.coerceIn(.02f, 1f)
            owner.attributes = owner.attributes.apply { screenBrightness = brightness }
        }
    }
    fun changeVolume(value: Float) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value.roundToInt().coerceIn(0, maximumVolume), 0)
        mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    LaunchedEffect(audioManager, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC); delay(500) }
        }
    }
    DisposableEffect(window) {
        val previous = window?.attributes?.screenBrightness
        val owner = context.activity()
        val oldStream = owner?.volumeControlStream
        owner?.volumeControlStream = AudioManager.STREAM_MUSIC
        onDispose {
            if (window != null && previous != null) window.attributes = window.attributes.apply { screenBrightness = previous }
            if (owner != null && oldStream != null) owner.volumeControlStream = oldStream
        }
    }
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
        orientationBeforeLock = null
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
                val portraitStageHeight = maxWidth / (16f / 9f) + 116.dp
                Column(Modifier.fillMaxSize()) {
                    if (!landscape) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerIcon(R.drawable.ic_arrow_back, "返回文件", model::leavePlayer)
                        Text("正在观看", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        PlayerLabel(if (orientationBeforeLock == null) "锁定方向" else "方向已锁", if (orientationBeforeLock == null) "锁定屏幕方向" else "解除方向锁定", click = ::lockOrientation)
                        PlayerIcon(R.drawable.ic_lock, "锁定触控", ::lockTouch, !exploration)
                        PlayerIcon(R.drawable.ic_info, "播放来源", { touch(); sheet = PlayerSheet.SOURCE })
                    }
                    // Portrait keeps transport below the picture. Fullscreen
                    // uses the entire viewport with controls over the video;
                    // hiding the HUD never changes the native surface size.
                    val stage = if (landscape) Modifier.weight(1f) else Modifier.fillMaxWidth().height(portraitStageHeight)
                    Box(stage.background(Color.Black)) {
                        Box(Modifier.fillMaxSize().padding(bottom = if (landscape) 0.dp else 116.dp)) {
                            AndroidView(factory = { PlayerViewport(it).also(model.player::attach) }, modifier = Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().semantics {
                                contentDescription = "视频画面"
                                onClick("显示播放控制") { touch(); true }
                            }.playerVerticalGestures(file.mediaKey, !exploration && !touchLocked && !holding && !client.busy, gestureEdge,
                                start = { left ->
                                    dragLeft = left; draggingVertical = true; touch()
                                    dragStart = if (left) actualBrightness() else audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximumVolume
                                }, change = { delta ->
                                    val next = (dragStart + delta).coerceIn(0f, 1f)
                                    if (dragLeft) {
                                        changeBrightness(next)
                                        gestureMessage = "亮度 ${(brightness * 100).roundToInt()}%"
                                    } else if (!audioManager.isVolumeFixed) {
                                        changeVolume(next * maximumVolume)
                                        gestureMessage = "媒体音量 ${(mediaVolume * 100f / maximumVolume).roundToInt()}%"
                                    } else gestureMessage = "此设备使用固定音量"
                                    interaction++
                                }, finish = { draggingVertical = false; touch() })
                                .pointerInput(file, exploration, touchLocked) {
                                detectTapGestures(
                                    onTap = { if (!liveBlocked && !exploration) visible = !visible else touch(); interaction++ },
                                    onDoubleTap = { point ->
                                        if (!exploration && !touchLocked && !model.state.value.busy && model.state.value.selected == file) {
                                            touch()
                                            val delta = when {
                                                point.x < size.width / 3f -> -10_000L
                                                point.x > size.width * 2f / 3f -> 10_000L
                                                else -> 0L
                                            }
                                            if (delta == 0L) { gestureSeek.reset(); model.togglePlayback() }
                                            else if (liveState.seekable && liveState.durationMs > 0) {
                                                val target = gestureSeek.next(liveState.positionMs, liveState.durationMs, delta, SystemClock.elapsedRealtime())
                                                model.player.seek(target)
                                                gestureMessage = (if (delta < 0) "后退" else "快进") + " · " + clock(target)
                                            }
                                        }
                                    },
                                    onLongPress = {},
                                    onPress = {
                                        if (!exploration && !touchLocked && model.state.value.selected == file && liveState.playing && !model.state.value.busy) coroutineScope {
                                            var temporaryEpoch: Long? = null
                                            val hold = launch {
                                                delay(viewConfiguration.longPressTimeoutMillis)
                                                if (model.state.value.selected == file && liveState.playing && !model.state.value.busy) {
                                                    temporaryEpoch = model.player.beginTemporaryRate(liveHoldRate)
                                                    holding = temporaryEpoch != null
                                                }
                                            }
                                            try { tryAwaitRelease() }
                                            finally {
                                                hold.cancel()
                                                temporaryEpoch?.let(model.player::restoreRate)
                                                holding = false
                                            }
                                        }
                                    })
                            })
                            if (holding) Text("${holdRate}× 倍速播放", Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                                .clip(RoundedCornerShape(8.dp)).background(PlayerPanel).padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp)
                            gestureMessage?.let { message -> Text(message, Modifier.align(Alignment.Center)
                                .clip(RoundedCornerShape(8.dp)).background(PlayerPanel).padding(12.dp), fontSize = 14.sp) }
                            val failure = if (client.busy) null else client.error ?: state.error
                            if (failure != null) {
                                Column(Modifier.align(Alignment.Center).widthIn(max = 360.dp).padding(20.dp)
                                    .clip(RoundedCornerShape(10.dp)).background(PlayerPanel).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(failure, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFFFA0AC))
                                    PlayerLabel("重试", "重试播放") {
                                        model.retryPlayback()
                                    }
                                }
                            } else if (client.busy) {
                                Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(10.dp)).background(PlayerPanel).padding(start = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (showRequest) CircularProgressIndicator(Modifier.size(18.dp), color = PlayerAccent, strokeWidth = 2.dp)
                                    Text("正在加载", fontSize = 13.sp)
                                    PlayerLabel("取消", "取消播放请求") { model.cancel() }
                                }
                            } else if (state.waitingForBuffer) {
                                Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color(0xCC151515)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color = PlayerAccent, strokeWidth = 2.dp)
                                    Text("${state.phase} ${state.buffering.toInt()}%", fontSize = 13.sp)
                                    PlayerLabel("取消", "取消播放等待") { model.leavePlayer() }
                                }
                            }
                        }
                        Text(client.downloadBytesPerSecond?.let { speed ->
                            when {
                                speed >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB/s", speed / 1_000_000.0)
                                speed >= 1000 -> String.format(java.util.Locale.ROOT, "%.1f KB/s", speed / 1000.0)
                                else -> "$speed B/s"
                            }
                        } ?: "— B/s", color = Color.White, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = if (landscape && visible) 56.dp else 8.dp, end = 12.dp)
                                .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp)
                                .semantics { contentDescription = "实际网络下载速度" })
                        if (visible && landscape) Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            PlayerIcon(R.drawable.ic_arrow_back, "返回文件", model::leavePlayer)
                            Text(file.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            PlayerLabel(if (orientationBeforeLock == null) "锁定方向" else "方向已锁", if (orientationBeforeLock == null) "锁定屏幕方向" else "解除方向锁定", click = ::lockOrientation)
                            PlayerIcon(R.drawable.ic_lock, "锁定触控", ::lockTouch, !exploration)
                            PlayerIcon(R.drawable.ic_info, "播放来源", { touch(); sheet = PlayerSheet.SOURCE })
                        }
                        if (visible) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))).padding(horizontal = if (landscape) 20.dp else 12.dp)) {
                            SeekPreview(seekPreview, seek?.toLong())
                            PlayerSlider(seek ?: state.positionMs.toFloat().coerceIn(0f, state.durationMs.toFloat().coerceAtLeast(1f)), state.durationMs.toFloat().coerceAtLeast(1f),
                                "播放进度", state.seekable && state.durationMs > 0,
                                { gestureSeek.reset(); seek = it; touch() }, { seek?.let { model.player.seek(it.toLong()) }; seek = null; touch() },
                                clock((seek ?: state.positionMs.toFloat()).toLong()) + "，共 " + clock(state.durationMs))
                            if (!landscape) Row(Modifier.fillMaxWidth().height(20.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(clock((seek ?: state.positionMs.toFloat()).toLong()), color = PlayerSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                Text(if (state.durationMs > 0) clock(state.durationMs) else "--:--", color = PlayerSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                PlayerIcon(R.drawable.ic_skip_previous, "上一个视频", { touch(); model.previousMedia() }, queue?.hasPrevious == true)
                                PlayerIcon(R.drawable.ic_replay_10, "后退十秒", { step(-10_000) }, state.seekable && !client.busy)
                                PlayerIcon(if (state.playing) R.drawable.art_pause else R.drawable.art_play, if (state.playing) "暂停播放" else "开始播放", { touch(); model.togglePlayback() }, !client.busy)
                                PlayerIcon(R.drawable.ic_forward_10, "快进十秒", { step(10_000) }, state.seekable && !client.busy)
                                PlayerIcon(R.drawable.ic_skip_next, "下一个视频", { touch(); model.nextMedia() }, queue?.hasNext == true)
                                if (landscape) Text(clock((seek ?: state.positionMs.toFloat()).toLong()) + " / " + if (state.durationMs > 0) clock(state.durationMs) else "--:--",
                                    color = Color(0xFFDADADA), fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
                                else Spacer(Modifier.weight(1f))
                                if (landscape) {
                                    PlayerIcon(R.drawable.ic_playlist_play, "播放列表", { touch(); sheet = PlayerSheet.QUEUE }, queue != null)
                                    PlayerLabel("音轨", "选择音轨", state.audio.isNotEmpty()) { touch(); sheet = PlayerSheet.AUDIO }
                                    PlayerLabel("字幕", "选择字幕", !client.busy) { touch(); sheet = PlayerSheet.SUBTITLE }
                                    PlayerLabel("${state.rate}×", "播放速度") { touch(); sheet = PlayerSheet.SPEED }
                                    PlayerIcon(R.drawable.art_volume, "媒体系统音量", { touch(); sheet = PlayerSheet.VOLUME })
                                    PlayerLabel("亮度", "窗口亮度") { touch(); sheet = PlayerSheet.BRIGHTNESS }
                                }
                                PlayerIcon(if (landscape) R.drawable.art_fullscreen_off else R.drawable.art_fullscreen_on, if (landscape) "退出全屏" else "横屏全屏", { fullscreen(landscape) })
                            }
                        }
                    }
                    if (!landscape) Column(Modifier.weight(1f).semantics { contentDescription = "播放详情" }.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.name, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = if (expandedTitle) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button,
                                    onClickLabel = if (expandedTitle) "收起完整名称" else "展开完整名称") { expandedTitle = !expandedTitle; touch() })
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(client.serverLabel, Modifier.weight(1f), color = PlayerSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (state.width > 0) Text("${state.width} × ${state.height}", color = PlayerSecondary, fontSize = 12.sp)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PlayerIcon(R.drawable.ic_playlist_play, "播放列表", { touch(); sheet = PlayerSheet.QUEUE }, queue != null)
                                Text(queue?.let { "${it.index + 1} / ${it.items.size}" } ?: "单个视频", fontSize = 13.sp, color = PlayerSecondary)
                            }
                            PlayerLabel("${state.rate}× 倍速", "播放速度") { touch(); sheet = PlayerSheet.SPEED }
                        }
                        HorizontalDivider(color = PlayerDivider)
                        Column {
                            DetailAction(R.drawable.ic_audio, "音轨", state.pendingAudio?.let { id -> "正在切换 · ${state.audio.firstOrNull { it.id == id }?.title ?: "音轨"}" }
                                ?: state.audio.firstOrNull { it.id == state.selectedAudio }?.title ?: "暂无音轨", "选择音轨", state.audio.isNotEmpty()) { touch(); sheet = PlayerSheet.AUDIO }
                            HorizontalDivider(Modifier.padding(start = 32.dp), color = PlayerDivider)
                            DetailAction(R.drawable.ic_subtitles, "字幕", state.pendingSubtitle?.let { id -> "正在切换 · ${state.subtitles.firstOrNull { it.id == id }?.title ?: "字幕"}" }
                                ?: state.subtitles.firstOrNull { it.id == state.selectedSubtitle }?.title ?: "关闭", "选择字幕", !client.busy) { touch(); sheet = PlayerSheet.SUBTITLE }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            PlayerLabel("音量 ${(mediaVolume * 100f / maximumVolume).roundToInt()}%", "媒体系统音量") { touch(); sheet = PlayerSheet.VOLUME }
                            PlayerLabel(if (brightness < 0) "亮度 · 自动" else "亮度 ${(brightness * 100).roundToInt()}%", "窗口亮度") { touch(); sheet = PlayerSheet.BRIGHTNESS }
                        }
                    }
                }
                SnackbarHost(feedback, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
                if (touchLocked) {
                    // The topmost input layer covers controls as well as the picture. System Back unlocks first.
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .08f))
                        .clearAndSetSemantics { contentDescription = "触控已锁定" }
                        .pointerInput(Unit) { detectTapGestures(onTap = {}, onDoubleTap = {}, onLongPress = {}) })
                    Box(Modifier.align(Alignment.CenterEnd).padding(12.dp).clip(RoundedCornerShape(12.dp)).background(PlayerPanel)) {
                        PlayerLabel("解锁", "解除触控锁定") { touchLocked = false; touch() }
                    }
                }
                if (sheet != null) PlayerPanel(landscape, when (sheet) { PlayerSheet.AUDIO -> "音轨"; PlayerSheet.SUBTITLE -> "字幕"; PlayerSheet.SPEED -> "播放速度"; PlayerSheet.VOLUME -> "媒体系统音量"; PlayerSheet.BRIGHTNESS -> "窗口亮度"; PlayerSheet.EXTERNAL -> "外挂字幕"; PlayerSheet.QUEUE -> "播放列表"; else -> "播放来源" }, { sheet = null; touch() }) {
                    when (sheet) {
                        PlayerSheet.QUEUE -> queue?.let { snapshot ->
                            Column {
                                Text("${snapshot.source.label} · ${snapshot.index + 1} / ${snapshot.items.size}", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = PlayerSecondary, fontSize = 13.sp)
                                LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = snapshot.index), contentPadding = PaddingValues(bottom = 20.dp)) {
                                    itemsIndexed(snapshot.items, key = { _, item -> item.mediaKey }) { index, item ->
                                        Choice(item.name, "${index + 1} · ${item.path.substringBeforeLast('/').ifEmpty { "/" }}", index == snapshot.index) {
                                            model.navigateMedia(index); sheet = null; touch()
                                        }
                                    }
                                }
                            }
                        }
                        PlayerSheet.AUDIO, PlayerSheet.SUBTITLE -> {
                            val audio = sheet == PlayerSheet.AUDIO
                            TrackChoices(if (audio) state.audio else state.subtitles, if (audio) state.selectedAudio else state.selectedSubtitle,
                                header = if (audio) null else { {
                                    if (file.downloadId.isEmpty()) DetailAction(R.drawable.ic_folder, "选择外挂字幕", if (state.subtitleLoading) "正在读取字幕" else "浏览当前服务器的字幕文件", "选择外挂字幕", !state.subtitleLoading) { sheet = PlayerSheet.EXTERNAL; touch() }
                                    DetailAction(R.drawable.ic_subtitles, "选择本地字幕", "从手机或文档提供方选择字幕", "选择本地字幕", !readingDocument && !state.subtitleLoading) {
                                        documentGeneration = model.player.state.value.mediaGeneration
                                        touch()
                                        try { subtitleDocument.launch(arrayOf("*/*")) }
                                        catch (_: android.content.ActivityNotFoundException) {
                                            documentGeneration = null
                                            uiScope.launch { feedback.showSnackbar("此设备没有可用的文件选择器") }
                                        }
                                    }
                                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("字幕时间 · " + when {
                                            state.subtitleDelayMs > 0 -> "延后 ${state.subtitleDelayMs / 1000.0} 秒"
                                            state.subtitleDelayMs < 0 -> "提前 ${-state.subtitleDelayMs / 1000.0} 秒"
                                            else -> "无偏移"
                                        }, fontSize = 14.sp)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            TextButton(onClick = { model.player.subtitleDelay(state.subtitleDelayMs - 500) }) { Text("提前 0.5 秒") }
                                            TextButton(onClick = { model.player.subtitleDelay(0); subtitleOffset = "" }) { Text("归零") }
                                            TextButton(onClick = { model.player.subtitleDelay(state.subtitleDelayMs + 500) }) { Text("延后 0.5 秒") }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedTextField(subtitleOffset, { subtitleOffset = it; subtitleOffsetError = null }, Modifier.weight(1f),
                                                label = { Text("偏移秒数，正数延后") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                            TextButton(onClick = {
                                                val seconds = subtitleOffset.trim().toDoubleOrNull()
                                                if (seconds == null || !seconds.isFinite() || seconds !in -600.0..600.0) subtitleOffsetError = "请输入 -600 到 600 秒"
                                                else { model.player.subtitleDelay((seconds * 1000).toLong()); subtitleOffsetError = null }
                                            }) { Text("应用") }
                                        }
                                        subtitleOffsetError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                        val codec = state.subtitles.firstOrNull { it.id == state.selectedSubtitle }?.codec.orEmpty()
                                        when {
                                            codec.contains("ssa", ignoreCase = true) -> Text("ASS / SSA 使用作者字体、位置与动画，保留原始样式。", fontSize = 12.sp, color = PlayerSecondary)
                                            listOf("pgs", "dvbsub", "vobsub").any { codec.contains(it, ignoreCase = true) } -> Text("位图字幕使用片源图像，不支持文字字号调整。", fontSize = 12.sp, color = PlayerSecondary)
                                            else -> {
                                                Text("文本字号 ${(subtitlePreview.scale * 100).roundToInt()}% · 底边距 ${(subtitlePreview.bottomPadding * 100).roundToInt()}%", fontSize = 14.sp)
                                                Slider(subtitlePreview.scale, { subtitlePreview = subtitlePreview.copy(scale = it) },
                                                    Modifier.semantics { contentDescription = "文本字幕字号" }, valueRange = .5f..2f)
                                                Slider(subtitlePreview.bottomPadding, { subtitlePreview = subtitlePreview.copy(bottomPadding = it) },
                                                    Modifier.semantics { contentDescription = "文本字幕底边距" }, valueRange = 0f.. .4f)
                                                Text("字幕外观预览", fontSize = (18 * subtitlePreview.scale).sp, color = Color.White)
                                                Row {
                                                    TextButton(onClick = { subtitlePreview = TextSubtitleAppearance() }) { Text("重置预览") }
                                                    TextButton(onClick = {
                                                        val value = subtitlePreview
                                                        uiScope.launch {
                                                            try { model.playbackPreferences.saveTextSubtitleAppearance(value) }
                                                            catch (cancelled: CancellationException) { throw cancelled }
                                                            catch (_: Exception) { feedback.showSnackbar("字幕外观保存失败，请重试") }
                                                        }
                                                    }) { Text("保存到此设备") }
                                                }
                                            }
                                        }
                                    }
                                } }) {
                                if (audio) model.player.audio(it) else model.player.subtitle(it)
                                sheet = null; touch()
                            }
                        }
                        PlayerSheet.EXTERNAL -> NasSubtitlePicker(model, file, chosen = { sheet = PlayerSheet.SUBTITLE; touch() })
                        PlayerSheet.SPEED -> LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                            item {
                                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(customRate, { customRate = it; speedError = null }, Modifier.weight(1f), label = { Text("自定义倍速") }, placeholder = { Text("0.1–5") },
                                            singleLine = true, shape = RoundedCornerShape(8.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                        TextButton(onClick = { val value = parsePlaybackRate(customRate); if (value == null) speedError = "请输入 0.1–5 之间的倍速" else { model.player.rate(value); sheet = null; touch() } }) { Text("应用") }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(customHold, { customHold = it; speedError = null }, Modifier.weight(1f), label = { Text("长按倍速 · 当前 ${holdRate}×") }, placeholder = { Text("默认 3，支持 0.1–5") },
                                            singleLine = true, shape = RoundedCornerShape(8.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                        TextButton(onClick = {
                                            val value = parsePlaybackRate(customHold)
                                            if (value == null) speedError = "请输入 0.1–5 之间的倍速"
                                            else uiScope.launch { try { model.playbackPreferences.saveHoldRate(value); customHold = "" } catch (_: Exception) { speedError = "长按倍速未能保存，请重试" } }
                                        }) { Text("保存") }
                                    }
                                    Text("松手立即恢复长按前的速度", fontSize = 12.sp, color = PlayerSecondary)
                                    speedError?.let { Text(it, color = Color(0xFFFFA0AC), fontSize = 13.sp) }
                                }
                            }
                            itemsIndexed(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f)) { _, value ->
                                Choice("${value}×", if (value == 1f) "正常速度" else "", state.rate == value) { model.player.rate(value); sheet = null; touch() }
                            }
                        }
                        PlayerSheet.VOLUME -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("${(mediaVolume * 100f / maximumVolume).roundToInt()}%", fontSize = 32.sp, fontWeight = FontWeight.Medium)
                            PlayerSlider(mediaVolume.toFloat(), maximumVolume.toFloat(), "媒体系统音量", !audioManager.isVolumeFixed,
                                { changeVolume(it) }, description = "${mediaVolume}，共 $maximumVolume 档")
                            Text(if (audioManager.isVolumeFixed) "此设备使用固定音量。" else "与设备媒体音量键同步，右侧上下滑动也可调整。", fontSize = 13.sp, color = PlayerSecondary)
                            Spacer(Modifier.height(8.dp))
                        }
                        PlayerSheet.BRIGHTNESS -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(if (brightness < 0) "跟随系统" else "${(brightness * 100).roundToInt()}%", fontSize = 28.sp)
                            PlayerSlider(actualBrightness() * 100, 100f, "窗口亮度", window != null, { changeBrightness(it / 100) })
                            Text("仅调整当前播放窗口，退出后恢复。左侧上下滑动也可调整。", fontSize = 13.sp, color = PlayerSecondary)
                            TextButton(onClick = { changeBrightness(-1f) }) { Text("恢复系统亮度") }
                        }
                        PlayerSheet.SOURCE -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { SourceField("文件", file.name) }
                            item { SourceField(if (file.downloadId.isEmpty()) "服务器与账号" else "来源", if (file.downloadId.isEmpty()) client.serverLabel
                                else if (downloads.items.firstOrNull { it.id == file.downloadId }?.complete == true) "本机下载 · 离线读取"
                                else "本机下载 · 未下载片段按需读取服务器") }
                            item { SourceField("路径", file.path) }
                            item { SourceField("播放方式", "原生播放 · Media3") }
                            item { SourceField("画质", "原画 · 直接读取原文件") }
                            if (state.width > 0) item { SourceField("源分辨率", "${state.width} × ${state.height}") }
                            item { SourceField("视频编码", state.sourceVideoCodec) }
                            item { SourceField("请求的视频解码策略", state.videoDecodePolicy.label) }
                            item { SourceField("实际视频解码方式", state.videoDecoderKind) }
                            item { SourceField("实际视频解码器", state.videoDecoder) }
                            item {
                                Column {
                                    Text("切换策略会保存进度并重新打开当前视频，后续视频也使用此设置。", style = MaterialTheme.typography.bodySmall, color = PlayerSecondary)
                                    VideoDecodePolicy.entries.forEach { policy ->
                                        Choice(policy.label, when (policy) {
                                            VideoDecodePolicy.AUTO -> "优先硬件，解码器故障时尝试可用的软件解码"
                                            VideoDecodePolicy.HARDWARE -> "只使用硬件视频解码器"
                                            VideoDecodePolicy.SOFTWARE -> "使用设备提供的软件视频解码器"
                                        }, state.videoDecodePolicy == policy, enabled = !changingDecoder && !client.busy && state.videoDecodePolicy != policy) {
                                            changingDecoder = true
                                            uiScope.launch {
                                                try { model.changeVideoDecoder(policy); sheet = null }
                                                catch (cancelled: CancellationException) { throw cancelled }
                                                catch (_: Exception) { feedback.showSnackbar("无法切换解码策略，请重试") }
                                                finally { changingDecoder = false }
                                            }
                                        }
                                    }
                                }
                            }
                            item { SourceField("实际音频解码器", state.audioDecoder) }
                            state.decoderRecovery?.let { recovery -> item { SourceField("解码恢复", recovery) } }
                            item { SourceField("片源动态范围", state.sourceDynamicRange) }
                            item { SourceField("片源色彩空间", state.sourceColorSpace) }
                            item {
                                val supported = view.display?.hdrCapabilities?.supportedHdrTypes
                                val names = supported?.map { type -> when (type) {
                                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                                    else -> "类型 $type"
                                } }
                                SourceField("当前屏幕 HDR 能力", names?.joinToString(" / ")?.ifEmpty { "系统未报告 HDR 支持" } ?: "未知")
                            }
                            item { SourceField("实际 HDR 输出", "尚未确认；片源标记和屏幕支持不代表已启用 HDR") }
                            item { SourceField("引擎报告的缓冲位置", if (state.bufferedPositionMs > 0) clock(state.bufferedPositionMs) else "未知") }
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

private val PlayerCanvas = Color(0xFF0D1015)
private val PlayerPanel = Color(0xFF1B212B)
private val PlayerAccent = Color(0xFF69A8FF)
private val PlayerSecondary = Color(0xFFADB9CB)
private val PlayerDivider = Color(0xFF293342)

@Composable private fun PlayerIcon(icon: Int, label: String, click: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = click, enabled = enabled, modifier = Modifier.size(48.dp).clearAndSetSemantics {
        contentDescription = label
        role = Role.Button
        if (enabled) onClick { click(); true } else disabled()
    }) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = Color.White.copy(alpha = if (enabled) 0.94f else 0.38f))
    }
}
@Composable private fun PlayerLabel(text: String, label: String, enabled: Boolean = true, click: () -> Unit) {
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = click).clearAndSetSemantics {
        contentDescription = label
        stateDescription = text
        role = Role.Button
        if (enabled) onClick { click(); true } else disabled()
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
@Composable private fun DetailAction(icon: Int, title: String, value: String, label: String, enabled: Boolean = true, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = click).clearAndSetSemantics {
        contentDescription = label
        stateDescription = value
        role = Role.Button
        if (enabled) onClick { click(); true } else disabled()
    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = PlayerSecondary)
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(value, modifier = Modifier.weight(1f), fontSize = 13.sp, color = PlayerSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(painterResource(R.drawable.ic_arrow_forward), null, Modifier.size(18.dp), tint = PlayerSecondary)
    }
}
@Composable private fun PlayerPanel(landscape: Boolean, title: String, dismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .semantics { contentDescription = "播放设置" }) {
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
@Composable private fun TrackChoices(tracks: List<NativeTrack>, selected: Int, header: @Composable (() -> Unit)? = null, choose: (Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
        if (header != null) item { header() }
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            val duplicate = tracks.count { it.title == track.title } > 1
            Choice(track.title + if (duplicate) " · ${index + 1}" else "", listOf(track.language.takeUnless { it == "und" }.orEmpty(), track.codec).filter { it.isNotBlank() }.joinToString(" · "), selected == track.id) { choose(track.id) }
        }
    }
}
@Composable private fun Choice(title: String, detail: String, selected: Boolean, enabled: Boolean = true, choose: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) PlayerAccent.copy(alpha = 0.10f) else Color.Transparent)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose).padding(horizontal = 12.dp, vertical = 14.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
