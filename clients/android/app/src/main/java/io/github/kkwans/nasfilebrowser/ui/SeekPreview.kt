package io.github.kkwans.nasfilebrowser.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.VideoSprite
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/** Fetch at most once per playback generation, only after the first timeline drag. */
@Composable internal fun rememberSeekPreview(model: ClientModel, file: ResourceRef, generation: Long, dragging: Boolean): Pair<VideoSprite, Bitmap>? {
    var requested by remember(file, generation) { mutableStateOf(false) }
    var asset by remember(file, generation) { mutableStateOf<Pair<VideoSprite, Bitmap>?>(null) }
    LaunchedEffect(dragging, file, generation) { if (dragging) requested = true }
    LaunchedEffect(requested, file, generation) {
        if (!requested) return@LaunchedEffect
        try { asset = model.videoSprite(file) }
        catch (error: Exception) { if (error is CancellationException) throw error }
    }
    return asset
}

@Composable internal fun SeekPreview(asset: Pair<VideoSprite, Bitmap>?, positionMs: Long?) {
    if (positionMs == null) return
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xE6202023)).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SeekThumbnail(asset, positionMs)
            Text(clock(positionMs), color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable internal fun PictureSeekFeedback(targetMs: Long, startMs: Long, durationMs: Long,
    asset: Pair<VideoSprite, Bitmap>?, modifier: Modifier = Modifier) {
    val delta = targetMs - startMs
    val change = (if (delta < 0) "后退 " else "前进 ") + clock(abs(delta))
    Column(modifier.width(240.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xEB10151E))
        .semantics { contentDescription = "画面拖动进度"; stateDescription = "${clock(targetMs)}，$change" }
        .padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SeekThumbnail(asset, targetMs)
        Text(change, color = Color(0xFF69A8FF), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(clock(targetMs), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 24.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.alignByBaseline())
            Text("/ ${clock(durationMs)}", color = Color(0xFFADB9CB), fontSize = 12.sp, modifier = Modifier.alignByBaseline())
        }
        LinearProgressIndicator(progress = { (targetMs.toDouble() / durationMs.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp), color = Color(0xFF69A8FF), trackColor = Color.White.copy(alpha = .2f),
            drawStopIndicator = {})
        Text("松手跳转", color = Color(0xFFADB9CB), fontSize = 12.sp)
    }
}

@Composable private fun SeekThumbnail(asset: Pair<VideoSprite, Bitmap>?, positionMs: Long) {
    asset?.let { (sprite, bitmap) ->
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        val (x, y) = sprite.tileAt(positionMs)
        Canvas(Modifier.width(144.dp).aspectRatio(sprite.width.toFloat() / sprite.height).clip(RoundedCornerShape(6.dp))
            .semantics { contentDescription = "当前拖动时间附近的视频缩略图" }) {
            drawImage(image, srcOffset = IntOffset(x, y), srcSize = IntSize(sprite.width, sprite.height),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
    }
}
