package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.app.FileOrder

@Composable internal fun FileCategoryMenu(value: FileCategory, enabled: Boolean = true, select: (FileCategory) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "筛选文件类型" }) { Text("类型 · ${value.label}") }
        DropdownMenu(expanded, { expanded = false }) {
            FileCategory.entries.forEach { item -> DropdownMenuItem(text = { Text((if (item == value) "✓ " else "") + item.label) }, onClick = { expanded = false; select(item) }) }
        }
    }
}

@Composable internal fun FileOrderMenu(value: FileOrder, enabled: Boolean = true, select: (FileOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "文件排序" }) { Text(value.label) }
        DropdownMenu(expanded, { expanded = false }) {
            FileOrder.entries.forEach { item -> DropdownMenuItem(text = { Text((if (item == value) "✓ " else "") + item.label) }, onClick = { expanded = false; select(item) }) }
        }
    }
}
