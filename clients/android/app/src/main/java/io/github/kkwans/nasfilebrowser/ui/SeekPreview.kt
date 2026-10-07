package io.github.kkwans.nasfilebrowser.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.VideoSprite
import kotlinx.coroutines.CancellationException

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
            asset?.let { (sprite, bitmap) ->
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                val (x, y) = sprite.tileAt(positionMs)
                Canvas(Modifier.width(144.dp).aspectRatio(sprite.width.toFloat() / sprite.height)
                    .semantics { contentDescription = "当前拖动时间附近的视频缩略图" }) {
                    drawImage(image, srcOffset = IntOffset(x, y), srcSize = IntSize(sprite.width, sprite.height),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                }
            }
            Text(clock(positionMs), color = Color.White, fontSize = 12.sp)
        }
    }
}
