package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable internal fun CacheSettingsPanel(model: ClientModel, feedback: (String) -> Unit) {
    val state by model.cache.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var quality by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf<String?>(null) }
    var manage by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<CachedImage>>(emptyList()) }
    val enabled = !state.loading && !state.busy
    LaunchedEffect(Unit) { model.cache.refresh() }
    fun edit(key: String, value: Long) { number = key; input = value.toString(); error = null }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup("缩略图缓存") {
            SettingsAction("容量上限", "${settings.thumbnailMB} MB · 已用 ${readableSize(state.thumbnails)}", R.drawable.ic_storage, enabled) { edit("thumbnail", settings.thumbnailMB) }
            SettingsAction("清理缩略图缓存", "仅超限时淘汰最久未读取项；不定时清除", R.drawable.ic_refresh, enabled) { clear = "thumbnail" }
        }
        SettingsGroup("视频播放缓存") {
            SettingsAction("容量上限", "${settings.playbackMB} MB · 已用 ${readableSize(state.playback)} · 0 为关闭", R.drawable.ic_storage, enabled) { edit("playback", settings.playbackMB) }
            SettingsAction("自动清理周期", "${settings.cleanupHours} 小时 · 跳过正在播放的文件", R.drawable.ic_history, enabled) { edit("hours", settings.cleanupHours.toLong()) }
            SettingsAction("清理播放缓存", "有限分块预读；正在播放的缓存会保留", R.drawable.ic_refresh, enabled) { clear = "playback" }
        }
        SettingsGroup("图片缓存与查看") {
            SettingsAction("默认图片质量", settings.imageQuality.label, R.drawable.ic_visibility, enabled) { quality = true }
            SettingsAction("图片缓存容量", "${settings.imageMB} MB · 已用 ${readableSize(state.images)} · 0 为关闭", R.drawable.ic_storage, enabled) { edit("image", settings.imageMB) }
            SettingsAction("缓存图片管理", "查看当前账号的图片缓存，可单独删除", R.drawable.ic_folder, enabled) {
                scope.launch { try { items = model.cache.cachedImages(model.cacheAccount()); manage = true } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { feedback("无法读取图片缓存，请重试") } }
            }
            SettingsAction("清理图片缓存", "清除本机图片缓存，不删除服务器文件", R.drawable.ic_refresh, enabled) { clear = "image" }
        }
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { model.cache.retry() }, enabled = enabled) { Text("重试缓存初始化") }
        }
    }
    number?.let { kind ->
        val title = when (kind) { "thumbnail" -> "缩略图容量上限"; "playback" -> "视频缓存容量上限"; "image" -> "图片缓存容量上限"; else -> "自动清理周期" }
        val maximum = when (kind) { "image" -> 51200L; "hours" -> 720L; else -> 10240L }
        val minimum = if (kind == "hours") 1L else 0L
        AlertDialog(onDismissRequest = { number = null }, shape = RoundedCornerShape(12.dp), title = { Text(title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(input, { input = it; error = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (kind == "hours") "小时" else "MB") }, shape = RoundedCornerShape(8.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(if (kind == "hours") "1–720 小时。系统省电可能延后执行；正在播放的文件下次清理。" else "${minimum}–${maximum} MB；0 关闭磁盘缓存。超限自动淘汰最久未读取项。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            val value = input.toLongOrNull()
            if (value == null || value !in minimum..maximum) error = "请输入 $minimum–$maximum 的整数"
            else {
                model.cache.save(when (kind) { "thumbnail" -> settings.copy(thumbnailMB = value); "playback" -> settings.copy(playbackMB = value); "image" -> settings.copy(imageMB = value); else -> settings.copy(cleanupHours = value.toInt()) }); number = null
            }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { number = null }) { Text("取消") } })
    }
    if (quality) AlertDialog(onDismissRequest = { quality = false }, shape = RoundedCornerShape(12.dp), title = { Text("默认图片质量") }, text = {
        Column { ImageQuality.entries.forEach { option ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(settings.imageQuality == option, onClick = { model.cache.save(settings.copy(imageQuality = option)); quality = false })
                TextButton(onClick = { model.cache.save(settings.copy(imageQuality = option)); quality = false }) { Text(option.label) }
            }
        } }
    }, confirmButton = { TextButton(onClick = { quality = false }) { Text("关闭") } })
    clear?.let { kind -> AlertDialog(onDismissRequest = { clear = null }, shape = RoundedCornerShape(12.dp), title = { Text("清理缓存？") },
        text = { Text(if (kind == "playback") "仅清理未在播放的缓存，服务器文件不受影响。" else "仅清理本机缓存，服务器文件不受影响。") },
        confirmButton = { TextButton(onClick = {
            clear = null
            scope.launch { try { model.cache.clear(kind); feedback(if (kind == "playback" && model.cache.state.value.protected > 0) "已清理，正在播放的缓存已保留" else "缓存已清理") } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { feedback("清理失败，请重试") } }
        }) { Text("清理") } }, dismissButton = { TextButton(onClick = { clear = null }) { Text("取消") } }) }
    if (manage) AlertDialog(onDismissRequest = { manage = false }, shape = RoundedCornerShape(12.dp), title = { Text("缓存图片") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (items.isEmpty()) item { Text("当前账号还没有图片缓存") }
            items(items, key = { it.key }) { image ->
                Column(Modifier.fillMaxWidth()) {
                    Text(image.name, style = MaterialTheme.typography.bodyMedium)
                    Text(readableSize(image.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton(onClick = { manage = false; model.open(ResourceRef(image.path, image.wirePath, image.name, false, "image", image.size, image.modified)) }) { Text("查看") }
                        TextButton(onClick = { scope.launch { try { model.cache.removeImage(image.key, model.cacheAccount()); items = model.cache.cachedImages(model.cacheAccount()) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { feedback("删除缓存失败，请重试") } } }) { Text("删除缓存") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { manage = false }) { Text("关闭") } })
}
