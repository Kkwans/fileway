package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.size.Size
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.CachedImage
import io.github.kkwans.nasfilebrowser.data.PreviewLease
import kotlinx.coroutines.*

@Composable internal fun ImageScreen(model: ClientModel, file: ResourceRef) {
    val cache by model.cache.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var asset by remember { mutableStateOf<PreviewLease?>(null) }
    var current by remember { mutableStateOf(file) }
    var phase by remember { mutableStateOf("正在加载图片") }
    var failed by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val quality = cache.settings.imageQuality
    val account = model.cacheAccount()
    LaunchedEffect(file, quality, client.previewScope, cache.revision, attempt) {
        var owned: PreviewLease? = null
        asset = null; failed = false; phase = "正在加载图片"; zoom = 1f; pan = Offset.Zero
        try {
            val result = model.image(file, quality); owned = result.first; current = result.second; asset = owned
            awaitCancellation()
        } catch (e: Exception) { if (e is CancellationException) throw e; failed = true; phase = "图片读取失败，请重试" }
        finally { owned?.release() }
    }
    val cacheKey = model.cache.key(account, current.wirePath.ifEmpty { current.path }, "${current.size}/${current.modified}/${quality.name}")
    val gesture = rememberTransformableState { change, offset, _ -> zoom = (zoom * change).coerceIn(1f, 8f); pan = if (zoom == 1f) Offset.Zero else pan + offset }
    Scaffold(containerColor = Color(0xFF141416)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = model::closeImage) { Icon(painterResource(R.drawable.ic_arrow_back), "返回文件", tint = Color.White) }
                Text(file.name, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(quality.label, color = Color(0xFFB5B5BE), modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).transformable(gesture), contentAlignment = Alignment.Center) {
                asset?.let { lease ->
                    val request = remember(lease.url, cacheKey, cache.settings.imageMB) {
                        ImageRequest.Builder(context.applicationContext).data(lease.url).diskCacheKey(cacheKey).memoryCacheKey(cacheKey)
                            .diskCachePolicy(if (cache.settings.imageMB == 0L) CachePolicy.DISABLED else CachePolicy.ENABLED)
                            .apply { val pixels = quality.pixels; if (pixels == null) size(Size.ORIGINAL) else size(pixels, pixels) }.build()
                    }
                    AsyncImage(request, file.name, imageLoader = model.cache.imageLoader.value,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, contentScale = ContentScale.Fit,
                        onSuccess = {
                            phase = "图片已加载"
                            scope.launch { runCatching { model.cache.recordImage(CachedImage(cacheKey, account, current.name, current.path, current.wirePath, current.modified, current.size, 0)) } }
                        }, onError = { failed = true; phase = "图片读取失败，请重试" })
                }
                if (phase != "图片已加载") Surface(shape = RoundedCornerShape(10.dp), color = Color(0xE6202023)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!failed) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(phase, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Row {
                            if (failed) TextButton(onClick = { attempt++ }) { Text("重试") }
                            TextButton(onClick = model::closeImage) { Text(if (failed) "返回" else "取消") }
                        }
                    }
                }
            }
        }
    }
}
