package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.data.SearchEnding
import io.github.kkwans.nasfilebrowser.data.SearchResult
import io.github.kkwans.nasfilebrowser.data.SearchScope

@Composable internal fun SearchScreen(model: ClientModel, client: ClientState) {
    val state by model.search.state.collectAsStateWithLifecycle()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = MaterialTheme.colorScheme.copy(
        primary = if (dark) Color(0xFFFF80A6) else Color(0xFFC63262),
        onPrimary = if (dark) Color(0xFF30141E) else Color.White,
        background = if (dark) Color(0xFF141416) else Color.White,
        onBackground = if (dark) Color(0xFFF3F3F6) else Color(0xFF202023),
        surface = if (dark) Color(0xFF202023) else Color(0xFFF5F5F7),
        onSurface = if (dark) Color(0xFFF3F3F6) else Color(0xFF202023),
        onSurfaceVariant = if (dark) Color(0xFFB5B5BE) else Color(0xFF63636D),
        outlineVariant = if (dark) Color(0xFF34343A) else Color(0xFFE9E9ED),
    )
    val focus = LocalFocusManager.current
    var details by remember(state.baseWirePath) { mutableStateOf<SearchResult?>(null) }
    fun submit() { if (!model.state.value.busy) { focus.clearFocus(); model.search.submit() } }
    MaterialTheme(colorScheme = colors) {
        Scaffold(containerColor = colors.surface) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 960.dp).fillMaxSize().imePadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { model.back() }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_arrow_back), "返回文件", Modifier.size(22.dp), tint = colors.onBackground)
                        }
                        Row(Modifier.weight(1f).background(colors.surface, RoundedCornerShape(12.dp)).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(value = state.query, onValueChange = { if (!model.state.value.busy) model.search.query(it) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp).semantics { contentDescription = "搜索文件名" },
                                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                                enabled = !client.busy,
                                cursorBrush = SolidColor(colors.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submit() }), decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (state.query.isEmpty()) Text("搜索文件名", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                                        inner()
                                    }
                                })
                            if (state.query.isNotEmpty()) IconButton(onClick = { if (!model.state.value.busy) model.search.query("") }, modifier = Modifier.size(48.dp), enabled = !client.busy) {
                                Icon(painterResource(R.drawable.art_close), "清除关键词", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                            } else Spacer(Modifier.width(12.dp))
                        }
                        TextButton(onClick = ::submit, modifier = Modifier.heightIn(min = 48.dp), enabled = state.query.isNotBlank() && !client.busy) { Text("搜索") }
                    }
                    LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = "搜索结果" }, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
                        item(key = "context") {
                            Text(state.basePath, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        item(key = "scope") {
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                SearchScope.entries.forEach { value ->
                                    val selected = state.scope == value
                                    Column(Modifier.weight(1f).selectable(selected = selected, role = Role.Tab, enabled = !client.busy,
                                        onClick = { if (!model.state.value.busy) model.search.scope(value) }).heightIn(min = 48.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Text(if (value == SearchScope.CURRENT) "当前目录" else "包含子目录", modifier = Modifier.padding(vertical = 8.dp),
                                            color = if (selected) colors.primary else colors.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) colors.primary else Color.Transparent))
                                    }
                                }
                            }
                            HorizontalDivider(color = colors.outlineVariant)
                        }
                        item(key = "status") {
                            val active = state.running || state.openingPath != null || client.busy
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (active) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primary)
                                val label = when {
                                    client.busy -> client.stage.ifBlank { "正在加载" }
                                    state.openingPath != null -> "正在确认文件"
                                    state.running -> if (state.items.isEmpty()) "正在搜索" else "已收到 ${state.items.size} 项，正在搜索"
                                    state.ending == SearchEnding.COMPLETED -> "找到 ${state.items.size} 项"
                                    state.items.isNotEmpty() -> "已保留 ${state.items.size} 项"
                                    else -> "搜索这个目录"
                                }
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                                if (active) TextButton(onClick = { model.search.cancel(); if (client.busy) model.cancel() }) { Text("取消") }
                            }
                            val message = client.error ?: state.message
                            if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge,
                                color = if (state.ending == SearchEnding.FAILED || client.error != null) MaterialTheme.colorScheme.error else colors.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp))
                            if (!active && state.ending in setOf(SearchEnding.FAILED, SearchEnding.TIMEOUT, SearchEnding.LIMIT, SearchEnding.CANCELED)) {
                                TextButton(onClick = ::submit) { Text("重新搜索") }
                            }
                            if (!active && state.items.isEmpty() && state.message == null && client.error == null) {
                                Text(if (state.ending == SearchEnding.COMPLETED) "没有匹配的文件。试试更短的关键词或包含子目录。" else "输入文件名，查找这个目录中的文件。",
                                    style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
                            }
                        }
                        items(state.items, key = { it.relativePath }) { result ->
                            SearchResultRow(model, result, state.basePath, state.baseWirePath, state.openingPath == result.relativePath, enabled = !client.busy,
                                open = { if (!model.state.value.busy) { focus.clearFocus(); model.search.openResult(result) } }, details = { details = result })
                        }
                    }
                }
            }
        }
        details?.let { result ->
            AlertDialog(onDismissRequest = { details = null }, containerColor = colors.surface, title = { Text("文件详情") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()).semantics { contentDescription = "文件详情内容" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(result.name, style = MaterialTheme.typography.bodyLarge)
                        Text(state.basePath.trimEnd('/') + "/" + result.relativePath, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = { if (!model.state.value.busy) { details = null; model.search.openResult(result) } }, enabled = !client.busy) { Text("打开") } },
                dismissButton = { TextButton(onClick = { details = null }) { Text("关闭") } })
        }
    }
}

@Composable private fun SearchResultRow(model: ClientModel, result: SearchResult, basePath: String, baseWirePath: String,
    opening: Boolean, enabled: Boolean, open: () -> Unit, details: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val resource = remember(result, basePath, baseWirePath) { result.resource(basePath, baseWirePath) }
    Column(Modifier.fillMaxWidth()) {
        if (resource != null) FileEntry(model, resource, if (result.directory) FileLayout.LIST else FileLayout.DETAIL,
            enabled, open, details, location = result.relativePath.substringBeforeLast('/', "当前目录"))
        else Column(Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = details,
            onLongClickLabel = "查看完整名称和位置", role = Role.Button, enabled = enabled).padding(vertical = 12.dp)) {
            Text(result.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("文件名编码无法确认", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (opening) Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("正在确认文件", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}
