package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.kkwans.nasfilebrowser.data.renameNameError
import io.github.kkwans.nasfilebrowser.data.FileTransferAction
import io.github.kkwans.nasfilebrowser.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FileActionIcon(icon: Int, label: String, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(icon), label, Modifier.size(24.dp), tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                else if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun FileActions(model: ClientModel, file: ResourceRef, onMoved: () -> Unit = {}) {
    if (file.downloadId.isNotEmpty()) {
        Text("本机下载 · 收藏和标签在服务器原文件上管理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val tags by model.tags.state.collectAsStateWithLifecycle()
    val trash by model.trash.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val downloads by model.downloads.state.collectAsStateWithLifecycle()
    val favorites by model.favorites.state.collectAsStateWithLifecycle()
    val operations by model.fileOperations.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var labeling by remember(file.mediaKey, tags.scope) { mutableStateOf(false) }
    var moving by remember(file.mediaKey, tags.scope) { mutableStateOf(false) }
    var renaming by remember(file.mediaKey, operations.scope) { mutableStateOf(false) }
    var more by remember(file.mediaKey, operations.scope) { mutableStateOf(false) }
    DisposableEffect(file) { onDispose { if (model.fileChecksum.state.value.file === file) model.fileChecksum.close() } }
    val enabled = !trash.changing && !client.busy && !operations.changing
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        FavoriteFileAction(model, file, enabled)
        val count = tags.items.count { collectionPath(file.path) in it.paths }
        FileActionIcon(R.drawable.ic_tag, "设置文件标签", enabled && !tags.changing, count > 0) { labeling = true }
        if (client.permissions.download) FileActionIcon(R.drawable.ic_download, "下载到本机", enabled && !downloads.busy && downloads.folderPlan == null) {
            model.download(file)
            if (file.directory) onMoved()
            if (!file.directory && android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (client.permissions.delete && file.path != "/") FileActionIcon(R.drawable.ic_trash, "移入回收站", enabled && !tags.changing) { moving = true }
        if (file.path != "/" && (client.permissions.rename || client.permissions.create || client.permissions.download && !file.directory)) Box {
            FileActionIcon(R.drawable.ic_more_vert, "更多文件操作", enabled && operations.transfer == null) { more = true }
            DropdownMenu(more, { more = false }) {
                if (client.permissions.download && !file.directory) DropdownMenuItem({ Text("校验文件") }, {
                    more = false; model.fileChecksum.open(file, client.previewScope)
                }, leadingIcon = { Icon(painterResource(R.drawable.ic_info), null) })
                if (client.permissions.rename) DropdownMenuItem({ Text("重命名") }, { more = false; renaming = true },
                    enabled = !tags.changing && !favorites.changing, leadingIcon = { Icon(painterResource(R.drawable.ic_edit), null) },
                    modifier = Modifier.semantics { contentDescription = "重命名文件" })
                if (client.permissions.create) DropdownMenuItem({ Text("复制到…") }, {
                    more = false; model.startFileTransfer(listOf(file), FileTransferAction.COPY, client.previewScope); onMoved()
                }, leadingIcon = { Icon(painterResource(R.drawable.ic_copy), null) }, modifier = Modifier.semantics { contentDescription = "复制文件" })
                if (client.permissions.create && client.permissions.rename) DropdownMenuItem({ Text("移动到…") }, {
                    more = false; model.startFileTransfer(listOf(file), FileTransferAction.MOVE, client.previewScope); onMoved()
                }, leadingIcon = { Icon(painterResource(R.drawable.ic_move), null) }, modifier = Modifier.semantics { contentDescription = "移动文件" })
            }
        }
        }
        favorites.error?.let { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(model.favorites::refresh) { Text("重试") }
        } }
        downloads.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        downloads.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (!labeling) tags.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (!renaming) operations.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (labeling) FileTagPicker(model, file) { labeling = false }
    if (model.fileChecksum.state.collectAsStateWithLifecycle().value.file?.mediaKey == file.mediaKey) FileChecksumSheet(model.fileChecksum)
    if (renaming) FileRenameDialog(model, file, { renaming = false }) { renaming = false; onMoved() }
    if (moving) AlertDialog(onDismissRequest = { moving = false }, title = { Text("移入回收站？") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(file.name); Text(file.path, style = MaterialTheme.typography.bodySmall)
            Text("可从回收站恢复。收藏和标签会随服务端操作同步。", style = MaterialTheme.typography.bodySmall)
            trash.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({ model.trash.move(file) { moving = false; onMoved() } }, enabled = enabled) { Text(if (trash.changing) "正在移动" else "移入回收站") } },
        dismissButton = { TextButton({ moving = false }, enabled = enabled) { Text("取消") } })
}

@Composable private fun FileRenameDialog(model: ClientModel, file: ResourceRef, dismiss: () -> Unit, saved: () -> Unit) {
    val state by model.fileOperations.state.collectAsStateWithLifecycle()
    val stemEnd = if (file.directory) file.name.length else file.name.lastIndexOf('.').takeIf { it > 0 } ?: file.name.length
    var input by remember { mutableStateOf(TextFieldValue(file.name, TextRange(0, stemEnd))) }
    var attempted by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val error = renameNameError(input.text)
    val canSubmit = !state.changing && error == null && input.text.trim() != file.name
    fun submit() { if (canSubmit) { attempted = true; model.fileOperations.rename(file, input.text, saved) } }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(onDismissRequest = { if (!state.changing) dismiss() }, title = { Text("重命名") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().focusRequester(focus), label = { Text("新名称") },
                enabled = !state.changing, singleLine = true, isError = error != null,
                supportingText = { Text(error ?: if (file.directory) "同名文件夹存在时不会覆盖" else "保留扩展名可继续使用原来的打开方式") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
            if (attempted) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton({ submit() }, enabled = canSubmit) { Text(if (state.changing) "正在重命名" else "保存名称") } },
        dismissButton = { TextButton(dismiss, enabled = !state.changing) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
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
    ModalBottomSheet(onDismissRequest = { if (!state.changing) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("文件标签", style = MaterialTheme.typography.titleLarge)
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading || state.changing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton({ attempt++ }) { Text("重试") } }
            if (error == null) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (state.items.isEmpty() && !loading) item { Text("还没有标签，可先新建一个。", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.items, key = { it.id }) { tag -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(tag.id in selected, enabled = !loading && !state.changing, role = Role.Checkbox) { checked -> selected = if (checked) selected + tag.id else selected - tag.id },
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(metadataColor(tag.color, MaterialTheme.colorScheme.primary), CircleShape))
                    Text(tag.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    Checkbox(tag.id in selected, null)
                } }
            }
            TextButton({ creating = true }, enabled = !loading && !state.changing) { Text("新建标签") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(dismiss, enabled = !state.changing) { Text("取消") }
                Button({ model.tags.assign(file, baseline.orEmpty(), selected, onSaved = dismiss) }, enabled = baseline != null && !loading && error == null && !state.changing) { Text(if (state.changing) "正在保存" else "保存标记") }
            }
        }
    }
    if (creating) TagEditor(model, null, { creating = false })
}
