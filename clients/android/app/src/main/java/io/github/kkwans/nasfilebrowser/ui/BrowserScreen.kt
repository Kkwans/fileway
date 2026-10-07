package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.app.mediaKind

/** Official reference: restrained chrome, cover-led content and compact directory entries. */
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
    val layout = state.fileLayout
    val displayed = remember(state.files, state.fileCategory, state.fileOrder) { model.directoryItems() }
    var details by remember(state.wirePath) { mutableStateOf<ResourceRef?>(null) }
    MaterialTheme(colorScheme = colors) {
        Scaffold(containerColor = colors.surface, bottomBar = { ClientNavigation(model, "files") }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("文件", modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
                    IconButton(onClick = model::retry, enabled = !state.busy) {
                        Icon(painterResource(R.drawable.ic_refresh), "刷新", Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                    }
                    FileLayoutMenu(layout, select = model::fileLayout)
                    IconButton(onClick = model::openSearch, enabled = !state.busy) {
                        Icon(painterResource(R.drawable.ic_search), "搜索文件", Modifier.size(22.dp), tint = colors.onBackground)
                    }
                }
                if (state.path != "/") DirectoryBreadcrumbs(state.path, state.wirePath, state.busy, { model.back() }, model::jumpDirectory)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileCategoryMenu(state.fileCategory, !state.busy, model::fileCategory)
                    Spacer(Modifier.weight(1f))
                    FileOrderMenu(state.fileOrder, !state.busy, model::fileOrder)
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
                } else Text(if (state.fileCategory != FileCategory.ALL) "${state.fileCategory.label} · ${displayed.size} / ${state.files.size} 项" else if (state.path == "/") "全部文件 · ${state.files.size} 项" else "${state.files.size} 项", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (!state.busy && displayed.isEmpty() && state.error == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (state.files.isEmpty()) "这个目录还没有文件。" else "当前目录没有此类型的文件。", color = colors.onSurfaceVariant)
                        if (state.fileCategory != FileCategory.ALL) TextButton(onClick = { model.fileCategory(FileCategory.ALL) }) { Text("显示全部文件") }
                    }
                } else FileCollection(displayed, layout, Modifier.weight(1f), layout.collectionDescription(),
                    resetKey = listOf(state.previewScope, state.wirePath, state.fileCategory, state.fileOrder), keyOf = { it.wirePath.ifEmpty { it.path } }) { file ->
                    FileEntry(model, file, layout, !state.busy, { model.open(file) }, { details = file })
                }
            }
        }
        details?.let { file -> FileDetailsDialog(file, onDismiss = { details = null }) }
    }
}

/** Shared file card for directory and verified search results. */
@Composable internal fun FileEntry(model: ClientModel, file: ResourceRef, layout: FileLayout, enabled: Boolean,
    open: () -> Unit, details: () -> Unit, location: String? = null, metadata: @Composable (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val action = Modifier.combinedClickable(enabled = enabled, role = Role.Button, onClick = open,
        onLongClick = details, onLongClickLabel = "查看完整名称与路径")
    @Composable fun artwork(modifier: Modifier) {
        val media = file.mediaKind() != null
        if (media) MediaThumbnail(model, file, modifier.clip(RoundedCornerShape(6.dp)),
            showStatusText = layout == FileLayout.COVER || layout == FileLayout.DETAIL,
            contentScale = if (layout == FileLayout.UNBOUNDED) ContentScale.Fit else ContentScale.Crop,
            naturalAspect = layout == FileLayout.UNBOUNDED)
        else Box((if (layout == FileLayout.UNBOUNDED) modifier.height(84.dp) else modifier)
            .background(if (file.directory) colors.primary.copy(alpha = .07f) else colors.surface, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            if (file.directory) Icon(painterResource(R.drawable.ic_folder), null,
                Modifier.size(when (layout) { FileLayout.COVER, FileLayout.UNBOUNDED -> 56.dp; FileLayout.DETAIL -> 44.dp; else -> 26.dp }), tint = colors.primary)
            else Text(file.name.substringAfterLast('.', "文件").uppercase().take(5),
                style = if (layout == FileLayout.COVER || layout == FileLayout.DETAIL) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant)
        }
    }
    @Composable fun caption(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val grid = layout == FileLayout.COVER || layout == FileLayout.COMPACT || layout == FileLayout.UNBOUNDED
            val titleStyle = if (grid) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
            if (layout == FileLayout.UNBOUNDED) {
                Text(file.name, modifier = Modifier.fillMaxWidth(), style = titleStyle, textAlign = TextAlign.Start, color = colors.onBackground)
            } else if (grid) {
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                // Include actual Latin/CJK fallback metrics, rather than multiplying declared lineHeight.
                val measured = measurer.measure("国Ag\n国Ag", style = titleStyle).size.height
                Box(Modifier.fillMaxWidth().height(with(density) { measured.toDp() })) {
                    Text(file.name, modifier = Modifier.fillMaxWidth(), style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = if (layout == FileLayout.UNBOUNDED) TextAlign.Center else TextAlign.Start, color = colors.onBackground)
                }
            } else Text(file.name, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onBackground)
            if (metadata != null) metadata() else if (layout != FileLayout.UNBOUNDED) {
                val label = (if (file.directory) "文件夹" else readableSize(file.size)) + (location?.let { " · $it" } ?: "")
                val modified = displayModified(file.modified)
                if (layout == FileLayout.COVER || layout == FileLayout.COMPACT) {
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    val measured = measurer.measure("国Ag", style = MaterialTheme.typography.bodySmall).size.height
                    Box(Modifier.fillMaxWidth().height(with(density) { measured.toDp() })) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (layout == FileLayout.COVER) Box(Modifier.fillMaxWidth().height(with(density) { measured.toDp() })) {
                        Text(modified ?: "时间未提供", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(if (layout == FileLayout.LIST && modified != null) "$label · $modified" else label,
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (layout == FileLayout.DETAIL && modified != null) Text(modified, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
    Surface(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(action),
        shape = RoundedCornerShape(10.dp), color = colors.background) {
        when (layout) {
            FileLayout.COVER -> Column(Modifier.fillMaxWidth()) {
                artwork(Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                caption(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp))
            }
            FileLayout.COMPACT -> Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                artwork(Modifier.size(56.dp)); caption(Modifier.fillMaxWidth())
            }
            FileLayout.UNBOUNDED -> Column(Modifier.fillMaxWidth()) {
                artwork(Modifier.fillMaxWidth()); caption(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp))
            }
            FileLayout.DETAIL -> Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                artwork(Modifier.fillMaxWidth(.44f).aspectRatio(16f / 9f)); caption(Modifier.weight(1f))
            }
            FileLayout.LIST -> Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                artwork(Modifier.size(44.dp)); caption(Modifier.weight(1f))
            }
        }
    }
}

@Composable internal fun FileDetailsDialog(file: ResourceRef, showSize: Boolean = true, onOpen: (() -> Unit)? = null,
    openEnabled: Boolean = true, onLocation: (() -> Unit)? = null, onDismiss: () -> Unit) {
    @Composable fun field(label: String, value: String) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        }
    }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(12.dp), containerColor = MaterialTheme.colorScheme.background,
        title = { Text("文件详情", style = MaterialTheme.typography.titleLarge) }, text = {
        SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState()).semantics { contentDescription = "文件详情内容" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            field("名称", file.name); field("位置", file.path); field("类型", fileTypeLabel(file))
            if (showSize && !file.directory) field("大小", readableSize(file.size))
            field("修改时间", displayModified(file.modified) ?: "未提供")
        } }
    }, confirmButton = {
        if (onLocation != null) TextButton(onClick = onLocation, enabled = openEnabled) { Text("打开所在目录") }
        if (onOpen == null) TextButton(onClick = onDismiss) { Text("关闭") }
        else TextButton(onClick = onOpen, enabled = openEnabled) { Text("打开") }
    }, dismissButton = { if (onOpen != null) TextButton(onClick = onDismiss) { Text("关闭") } })
}
