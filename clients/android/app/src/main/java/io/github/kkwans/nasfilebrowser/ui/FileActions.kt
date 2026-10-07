package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.collectionPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

@Composable internal fun FileActions(model: ClientModel, file: ResourceRef, onMoved: () -> Unit = {}) {
    if (file.downloadId.isNotEmpty()) {
        Text("本机下载 · 收藏和标签在服务器原文件上管理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val tags by model.tags.state.collectAsStateWithLifecycle()
    val trash by model.trash.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val downloads by model.downloads.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var labeling by remember(file.mediaKey, tags.scope) { mutableStateOf(false) }
    var moving by remember(file.mediaKey, tags.scope) { mutableStateOf(false) }
    val enabled = !trash.changing && !client.busy
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FavoriteFileAction(model, file, enabled)
        val count = tags.items.count { collectionPath(file.path) in it.paths }
        OutlinedButton({ labeling = true }, Modifier.fillMaxWidth().semantics {
            contentDescription = "设置文件标签"
            stateDescription = if (tags.loaded) "已关联 $count 个标签" else "尚未读取标签"
        }, enabled = enabled && !tags.changing) { Text(if (tags.loaded) "标签 · $count" else "设置标签") }
        if (!file.directory && client.permissions.download) OutlinedButton({
            model.download(file)
            if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }, Modifier.fillMaxWidth(), enabled = enabled && !downloads.busy) { Text("下载到本机") }
        downloads.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        downloads.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (client.permissions.delete && file.path != "/") TextButton({ moving = true }, Modifier.fillMaxWidth(), enabled = enabled && !tags.changing) { Text("移入回收站") }
        if (!labeling) tags.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (labeling) FileTagPicker(model, file) { labeling = false }
    if (moving) AlertDialog(onDismissRequest = { moving = false }, title = { Text("移入回收站？") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(file.name); Text(file.path, style = MaterialTheme.typography.bodySmall)
            Text("可从回收站恢复。收藏和标签会随服务端操作同步。", style = MaterialTheme.typography.bodySmall)
            trash.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({ model.trash.move(file) { moving = false; onMoved() } }, enabled = enabled) { Text(if (trash.changing) "正在移动" else "移入回收站") } },
        dismissButton = { TextButton({ moving = false }, enabled = enabled) { Text("取消") } })
}

@Composable private fun FileTagPicker(model: ClientModel, file: ResourceRef, dismiss: () -> Unit) {
    val state by model.tags.state.collectAsStateWithLifecycle()
    var baseline by remember { mutableStateOf<Set<String>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var attempt by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(file, state.scope, attempt) {
        loading = true; error = null
        try {
            model.tags.refresh()
            val current = withTimeout(10_000) { model.tags.state.first { !it.loading && !it.changing && (it.loaded || it.error != null) } }
            check(current.error == null) { current.error.orEmpty() }
            if (baseline == null) {
                baseline = current.items.filter { collectionPath(file.path) in it.paths }.map { it.id }.toSet()
                selected = baseline!!
            }
        } catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = failure.message ?: "标签读取失败，请重试" }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("文件标签") }, text = {
        Column {
            Text(file.name, style = MaterialTheme.typography.bodyLarge)
            if (loading || state.changing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton({ attempt++ }) { Text("重试") } }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (state.items.isEmpty() && !loading) item { Text("还没有标签，可先新建一个。", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.items, key = { it.id }) { tag -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(tag.id in selected, enabled = !loading && !state.changing, role = Role.Checkbox) { checked -> selected = if (checked) selected + tag.id else selected - tag.id },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(tag.id in selected, null)
                    Text(tag.name, Modifier.weight(1f).padding(start = 8.dp))
                } }
            }
            TextButton({ creating = true }, enabled = !loading && !state.changing) { Text("新建标签") }
        }
    }, confirmButton = { TextButton({ model.tags.assign(file, baseline.orEmpty(), selected); dismiss() }, enabled = baseline != null && !loading && error == null && !state.changing) { Text("保存标记") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } })
    if (creating) TagEditor(model, null, { creating = false })
}
