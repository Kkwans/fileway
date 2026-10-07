package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.app.FileLayout

@Composable internal fun FileLayoutMenu(layout: FileLayout, enabled: Boolean = true, select: (FileLayout) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "切换文件布局" }) {
            Text(layout.label, style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FileLayout.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label, color = if (layout == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground) },
                    enabled = enabled, onClick = { select(option); expanded = false })
            }
        }
    }
}

internal fun FileLayout.collectionDescription() = when (this) {
    FileLayout.COVER -> "文件网格"
    FileLayout.COMPACT -> "紧凑文件网格"
    FileLayout.UNBOUNDED -> "无界文件网格"
    FileLayout.LIST -> "文件列表"
    FileLayout.DETAIL -> "大图文件列表"
}

/** Shared lazy layout keeps browser and search cards, spacing and scroll behavior consistent. */
@Composable internal fun <T> FileCollection(entries: List<T>, layout: FileLayout, modifier: Modifier,
    description: String, resetKey: Any?, keyOf: (T) -> String,
    header: @Composable (() -> Unit)? = null, entry: @Composable (T) -> Unit) {
    key(resetKey, layout) {
    val list = rememberLazyListState()
    val grid = rememberLazyGridState()
    val waterfall = rememberLazyStaggeredGridState()
    val cell = when (layout) { FileLayout.COVER -> 148.dp; FileLayout.COMPACT -> 96.dp; FileLayout.UNBOUNDED -> 112.dp; else -> null }
    val area = modifier.background(MaterialTheme.colorScheme.surface).semantics { contentDescription = description }
    if (layout == FileLayout.UNBOUNDED) BoxWithConstraints(area) {
        LazyVerticalStaggeredGrid(if (maxWidth < 600.dp) StaggeredGridCells.Fixed(2) else StaggeredGridCells.Adaptive(180.dp),
            Modifier.fillMaxSize(), state = waterfall, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp) {
            if (header != null) item(key = "collection-header", span = StaggeredGridItemSpan.FullLine) { header() }
            items(entries, key = { "entry:${keyOf(it)}" }) { entry(it) }
        }
    } else if (cell != null) LazyVerticalGrid(GridCells.Adaptive(cell), area, state = grid,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header != null) item(key = "collection-header", span = { GridItemSpan(maxLineSpan) }) { header() }
        items(entries, key = { "entry:${keyOf(it)}" }) { entry(it) }
    } else LazyColumn(area, state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header != null) item(key = "collection-header") { header() }
        items(entries, key = { "entry:${keyOf(it)}" }) { entry(it) }
    }
    }
}
