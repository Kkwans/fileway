package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.DirectoryCrumb
import io.github.kkwans.nasfilebrowser.data.directoryTrail

@Composable internal fun DirectoryBreadcrumbs(path: String, wirePath: String, busy: Boolean, back: () -> Unit, jump: (DirectoryCrumb) -> Unit) {
    val trail = remember(path, wirePath) { directoryTrail(path, wirePath) }
    var expanded by remember(path, wirePath) { mutableStateOf(false) }
    val collapsed = trail.size > 2
    val visible = if (collapsed) trail.takeLast(2) else trail
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back, enabled = !busy) { Icon(painterResource(R.drawable.ic_arrow_back), "上一级", Modifier.size(22.dp)) }
        if (collapsed) {
            TextButton(onClick = { expanded = true }, enabled = !busy,
                modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = "展开完整路径" }) { Text("…") }
            Text("/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        visible.forEachIndexed { index, crumb ->
            if (index > 0) Text("/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { if (crumb.path == path) expanded = true else jump(crumb) },
                enabled = !busy && crumb.wirePath != null, modifier = Modifier.weight(1f)) {
                Text(crumb.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = if (crumb.path == path) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, shape = RoundedCornerShape(12.dp),
        title = { Text("目录路径", style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "full-path") { SelectionContainer { Text(path, style = MaterialTheme.typography.bodySmall) } }
                items(trail, key = { it.path }) { crumb ->
                    TextButton(onClick = { expanded = false; jump(crumb) }, enabled = !busy && crumb.wirePath != null && crumb.path != path,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(crumb.label, style = MaterialTheme.typography.bodyMedium)
                            if (crumb.path != "/") Text(crumb.path, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (crumb.wirePath == null) Text("此路径无法安全跳转", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } })
}
