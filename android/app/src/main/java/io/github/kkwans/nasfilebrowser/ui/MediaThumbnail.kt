package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.PreviewLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

/** NAS generates the preview; Coil fetches/decodes only the local capability. */
@Composable internal fun MediaThumbnail(model: ClientModel, file: ResourceRef, modifier: Modifier, showStatusText: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop, naturalAspect: Boolean = false) {
    val client by model.state.collectAsStateWithLifecycle()
    key(client.previewScope, file.wirePath, file.path, file.size, file.modified) {
        var asset by remember { mutableStateOf<PreviewLease?>(null) }
        var phase by remember { mutableStateOf("预览加载中") }
        var aspect by remember { mutableFloatStateOf(1f) }
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            var owned: PreviewLease? = null
            try {
                if (client.previewScope.isEmpty()) { phase = "暂无预览"; return@LaunchedEffect }
                owned = model.preview(file)
                asset = owned
                awaitCancellation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { phase = "暂无预览" }
            finally { owned?.let { runCatching { it.release() } } }
        }
        val frame = if (naturalAspect) modifier.aspectRatio(aspect) else modifier
        Box(frame.background(MaterialTheme.colorScheme.surface).semantics {
            contentDescription = "${file.name} 预览"
            stateDescription = phase
        }, contentAlignment = Alignment.Center) {
            val lease = asset
            if (lease != null) {
                val request = remember(lease.url) { ImageRequest.Builder(context.applicationContext)
                    .data(lease.url).diskCachePolicy(CachePolicy.DISABLED).size(512, 512).build() }
                AsyncImage(model = request, imageLoader = model.previewImageLoader, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = contentScale,
                    onSuccess = { result ->
                        val loaded = result.result.image
                        if (loaded.width > 0 && loaded.height > 0) aspect = loaded.width.toFloat() / loaded.height
                        phase = "预览已加载"
                    }, onError = { phase = "暂无预览" })
            }
            if (phase != "预览已加载") {
                Icon(painterResource(R.drawable.art_play), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showStatusText && phase == "暂无预览") Text(phase, modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
