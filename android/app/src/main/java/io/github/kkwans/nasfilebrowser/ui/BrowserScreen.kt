package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.app.ResourceRef

/** Compact directory chrome; content, rather than stacked headings, leads. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BrowserScreen(model: ClientModel, state: ClientState) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val colors = MaterialTheme.colorScheme.copy(
        primary = if (dark) Color(0xFFFF80A6) else Color(0xFFC63262),
        background = if (dark) Color(0xFF141416) else Color.White,
        onBackground = if (dark) Color(0xFFF3F3F6) else Color(0xFF202023),
        surface = if (dark) Color(0xFF202023) else Color(0xFFF5F5F7),
        onSurface = if (dark) Color(0xFFF3F3F6) else Color(0xFF202023),
        onSurfaceVariant = if (dark) Color(0xFFB5B5BE) else Color(0xFF63636D),
        outlineVariant = if (dark) Color(0xFF34343A) else Color(0xFFE9E9ED),
    )
    var grid by rememberSaveable(state.profile?.id, state.serverLabel) { mutableStateOf(false) }
    var details by remember(state.wirePath) { mutableStateOf<ResourceRef?>(null) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.wirePath) { listState.scrollToItem(0); gridState.scrollToItem(0) }
    MaterialTheme(colorScheme = colors) {
        Scaffold(containerColor = colors.background, bottomBar = {
            Row(Modifier.fillMaxWidth().background(colors.background).padding(horizontal = 16.dp)) {
                TextButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("文件", style = MaterialTheme.typography.titleSmall) }
                TextButton(onClick = { model.tab("recent") }, modifier = Modifier.weight(1f)) { Text("最近播放", color = colors.onSurfaceVariant) }
            }
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.serverLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onBackground)
                    TextButton(onClick = model::disconnect) { Text("切换服务器") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.back() }, enabled = state.path != "/" && !state.busy) {
                        Icon(painterResource(R.drawable.ic_arrow_back), "上一级", Modifier.size(22.dp))
                    }
                    Text(state.path, modifier = Modifier.weight(1f).padding(end = 16.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onBackground)
                }
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = model::openSearch, enabled = !state.busy) { Text("搜索文件") }
                    TextButton(onClick = model::retry, enabled = !state.busy) { Text("刷新") }
                    val modeColors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, labelColor = colors.onSurfaceVariant,
                        selectedContainerColor = colors.primary.copy(alpha = .08f), selectedLabelColor = colors.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !grid, onClick = { grid = false }, label = { Text("列表") }, colors = modeColors)
                        FilterChip(selected = grid, onClick = { grid = true }, label = { Text("网格") }, colors = modeColors)
                    }
                }
                state.error?.let { message ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, modifier = Modifier.weight(1f), color = colors.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = model::retry) { Text("重试") }
                    }
                }
                state.notice?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                if (state.busy) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = colors.primary, strokeWidth = 2.dp)
                    Text(state.stage, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = model::cancel) { Text("取消") }
                } else Text("${state.files.size} 项", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (!state.busy && state.files.isEmpty() && state.error == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("这个目录还没有文件。", color = colors.onSurfaceVariant)
                } else if (grid) LazyVerticalGrid(GridCells.Adaptive(152.dp), Modifier.weight(1f).semantics { contentDescription = "文件网格" }, state = gridState,
                    contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.files, key = { it.wirePath.ifEmpty { it.path } }) { file ->
                        BrowserEntry(file, true, !state.busy, { model.open(file) }, { details = file })
                    }
                } else LazyColumn(Modifier.weight(1f).semantics { contentDescription = "文件列表" }, state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(state.files, key = { it.wirePath.ifEmpty { it.path } }) { file ->
                        BrowserEntry(file, false, !state.busy, { model.open(file) }, { details = file })
                        HorizontalDivider(color = colors.outlineVariant)
                    }
                }
            }
        }
        details?.let { file -> AlertDialog(onDismissRequest = { details = null }, title = { Text("文件详情") }, text = {
            SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState()).semantics { contentDescription = "文件详情内容" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(file.name); Text(file.path); Text(if (file.directory) "文件夹" else readableSize(file.size))
            } }
        }, confirmButton = { TextButton(onClick = { details = null }) { Text("关闭") } }) }
    }
}

@Composable private fun BrowserEntry(file: ResourceRef, grid: Boolean, enabled: Boolean, open: () -> Unit, details: () -> Unit) {
    val action = Modifier.combinedClickable(enabled = enabled, role = Role.Button, onClick = open, onLongClick = details, onLongClickLabel = "查看完整名称与路径")
    @Composable fun artwork(modifier: Modifier) {
        Box(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            if (file.directory) Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(if (grid) 36.dp else 24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(file.name.substringAfterLast('.', "文件").uppercase().take(5), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    @Composable fun caption(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val titleStyle = MaterialTheme.typography.bodyLarge
            if (grid) Box(Modifier.fillMaxWidth().height(with(LocalDensity.current) { titleStyle.lineHeight.toDp() * 2 })) {
                Text(file.name, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
            } else Text(file.name, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
            Text(if (file.directory) "文件夹" else readableSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (grid) Column(action.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        artwork(Modifier.fillMaxWidth().aspectRatio(16f / 9f)); caption(Modifier.fillMaxWidth())
    } else Row(action.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        artwork(Modifier.size(44.dp)); caption(Modifier.weight(1f))
    }
}
