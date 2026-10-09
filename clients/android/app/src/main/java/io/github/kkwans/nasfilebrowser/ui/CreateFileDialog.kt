package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.DocumentEditController
import io.github.kkwans.nasfilebrowser.data.DirectoryCrumb
import io.github.kkwans.nasfilebrowser.data.createdDocumentTarget
import io.github.kkwans.nasfilebrowser.data.renameNameError

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CreateFileDialog(controller: DocumentEditController, onVerifyDirectory: (DirectoryCrumb) -> Unit, onClose: () -> Unit = {}) {
    val state by controller.state.collectAsStateWithLifecycle()
    val draft = state.creation ?: return
    val busy = state.saving || state.loading
    val nameError = renameNameError(draft.name)
    val target = remember(draft.parent, draft.name) { runCatching { createdDocumentTarget(draft.parent, draft.name) }.getOrNull() }
    val maximum = LocalConfiguration.current.screenHeightDp.dp * .92f
    ModalBottomSheet(onDismissRequest = { if (!busy && !state.unknownWrite) { controller.closeCreation(); onClose() } },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maximum).imePadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("新建文件", style = MaterialTheme.typography.titleLarge)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(draft.parent.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("UTF-8 · 同名文件或文件夹不会被覆盖", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.unknownWrite) {
                    SelectionContainer { Text("待核对文件：${draft.target?.path ?: draft.name}", style = MaterialTheme.typography.bodyMedium) }
                    Text("请先核对结果，面板保留本次名称和内容，不会重复提交。", style = MaterialTheme.typography.bodyMedium)
                    TextButton(controller::verifyCreation, enabled = !busy) { Text("核对创建结果") }
                    TextButton({ controller.closeCreation(verifyUnknown = true); onVerifyDirectory(draft.parent); onClose() }, enabled = !busy) { Text("返回原目录核对") }
                } else {
                    OutlinedTextField(draft.name, controller::createName, modifier = Modifier.fillMaxWidth(), label = { Text("文件名") }, singleLine = true,
                        enabled = !busy, isError = draft.name.isNotEmpty() && nameError != null,
                        supportingText = { Text(if (draft.name.isNotEmpty() && nameError != null) nameError else "例如 notes.txt 或 README.md") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                    target?.let { SelectionContainer { Text("将创建：${it.path}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    OutlinedTextField(draft.content, controller::createContent, modifier = Modifier.fillMaxWidth(), label = { Text("初始内容（可留空）") },
                        enabled = !busy, minLines = 4, maxLines = 8, textStyle = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!state.unknownWrite) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ controller.closeCreation(); onClose() }, enabled = !busy) { Text("取消") }
                Button(controller::create, enabled = !busy && nameError == null && target != null, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.saving) "正在创建" else "创建文件") }
            }
        }
    }
}
