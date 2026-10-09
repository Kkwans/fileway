package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.FileChecksumController
import io.github.kkwans.nasfilebrowser.data.ChecksumAlgorithm

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FileChecksumSheet(controller: FileChecksumController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val file = state.file ?: return
    val clipboard = LocalClipboardManager.current
    var copied by remember(state.result) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = controller::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("文件校验", style = MaterialTheme.typography.titleLarge)
            Text(file.name, style = MaterialTheme.typography.bodyMedium)
            Text("在服务器读取此文件并计算摘要，不下载文件内容。大文件需要更长时间。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChecksumAlgorithm.entries.forEach { algorithm -> FilterChip(state.algorithm == algorithm, { controller.algorithm(algorithm) },
                    label = { Text(algorithm.label) }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) }
            }
            OutlinedTextField(state.expected, controller::expected, Modifier.fillMaxWidth(), label = { Text("预期校验值（可选）") },
                isError = state.expectedInvalid, supportingText = { Text(if (state.expectedInvalid) "请输入 ${state.algorithm.digits} 位十六进制值" else "粘贴已有校验值可直接比较") })
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在计算 ${state.algorithm.label}…", style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.result?.let { digest ->
                SelectionContainer { Text(digest, style = MaterialTheme.typography.bodyMedium) }
                state.matches?.let { matches -> Text(if (matches) "校验值一致" else "校验值不一致", color = if (matches)
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                OutlinedButton({ clipboard.setText(AnnotatedString(digest)); copied = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (copied) "已复制" else "复制校验值")
                }
            }
            Button(if (state.busy) controller::cancel else controller::calculate, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (state.busy) "取消计算" else if (state.result != null) "重新计算" else "开始计算")
            }
        }
    }
}
