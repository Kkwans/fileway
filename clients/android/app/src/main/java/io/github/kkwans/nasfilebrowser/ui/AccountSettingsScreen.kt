package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.AccountSettingsController
import io.github.kkwans.nasfilebrowser.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AccountSettingsScreen(controller: AccountSettingsController, onBack: () -> Unit, onReconnect: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val prefs = state.draft
    var prefixesOpen by remember(state.scope) { mutableStateOf(false) }
    var passwordOpen by remember(state.scope) { mutableStateOf(false) }
    DisposableEffect(controller, state.scope) {
        onDispose { if (!controller.state.value.passwordUnknown && !controller.state.value.saving) controller.clearPasswordDraft() }
    }
    var controls by remember(state.scope, prefs?.player?.controlsTimeoutSec) { mutableStateOf(prefs?.player?.controlsTimeoutSec?.toString().orEmpty()) }
    var rate by remember(state.scope, prefs?.player?.playbackRate) { mutableStateOf(prefs?.player?.playbackRate?.toString().orEmpty()) }
    var threshold by remember(state.scope, prefs?.player?.resumeMinSec) { mutableStateOf(prefs?.player?.resumeMinSec?.toString().orEmpty()) }
    val controlsValid = controls.toIntOrNull()?.let { it in 0..20 } == true
    val rateValid = rate.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it in .1..5.0 } == true
    val thresholdValid = threshold.toIntOrNull()?.let { it in 5..600 } == true
    val enabled = !state.loading && !state.saving && !state.preferencesUnknown
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer, topBar = {
        TopAppBar(title = { Text("账户设置") }, navigationIcon = {
            IconButton(onBack) { Icon(painterResource(R.drawable.ic_arrow_back), "返回设置") }
        }, actions = { IconButton(controller::refresh, enabled = !state.loading && !state.saving) {
            Icon(painterResource(R.drawable.ic_refresh), "刷新账户资料")
        } })
    }, bottomBar = {
        if (prefs != null) Surface {
            Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(controller::savePreferences, enabled = enabled && state.dirty && controlsValid && rateValid && thresholdValid,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.saving) "正在保存" else "保存账户设置") }
            }
        }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).semantics { contentDescription = "账户设置内容" },
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.profile?.username ?: "当前账号", style = MaterialTheme.typography.titleLarge)
                Text("与网页端共用账户偏好。本机解码和缓存仍在应用设置中管理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            if (state.loading || state.saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, color = MaterialTheme.colorScheme.error)
                if (state.passwordUnknown) OutlinedButton(onReconnect, Modifier.heightIn(min = 48.dp)) { Text("重新连接核对") }
                else TextButton(controller::refresh, enabled = !state.loading && !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.preferencesUnknown) "核对服务器设置" else "重新读取资料")
                }
            } } }
            state.notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            if (prefs != null) {
                item { SettingsGroup("交互与日期") {
                    AccountToggle("桌面端单击打开", "控制网页端桌面列表的打开方式", prefs.singleClick, enabled) { value -> controller.editPreferences { it.copy(singleClick = value) } }
                    AccountToggle("复制或移动后跳转", "操作完成后进入目标目录", prefs.redirectAfterCopyMove, enabled) { value -> controller.editPreferences { it.copy(redirectAfterCopyMove = value) } }
                    AccountToggle("使用绝对日期", "关闭时使用相对时间", prefs.dateFormat, enabled) { value -> controller.editPreferences { it.copy(dateFormat = value) } }
                } }
                item { SettingsGroup("播放偏好") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AccountNumberField("控件自动隐藏秒数", "0 为不隐藏，1–20 秒", controls, controlsValid, enabled) { text ->
                            controls = text; text.toIntOrNull()?.takeIf { it in 0..20 }?.let { number -> controller.editPreferences { it.copy(player = it.player.copy(controlsTimeoutSec = number)) } }
                        }
                        AccountChoice("默认播放策略", prefs.player.playbackMode, listOf("native" to "原生播放", "compat" to "兼容播放", "ask" to "每次询问"), enabled) { value ->
                            controller.editPreferences { it.copy(player = it.player.copy(playbackMode = value)) }
                        }
                        AccountChoice("进页续播", prefs.player.resumeMode, listOf("resume" to "继续上次播放", "from-start" to "从头播放", "ask" to "每次询问"), enabled) { value ->
                            controller.editPreferences { it.copy(player = it.player.copy(resumeMode = value)) }
                        }
                        AccountNumberField("续播提示阈值秒数", "5–600 秒", threshold, thresholdValid, enabled) { text ->
                            threshold = text; text.toIntOrNull()?.takeIf { it in 5..600 }?.let { number -> controller.editPreferences { it.copy(player = it.player.copy(resumeMinSec = number)) } }
                        }
                        AccountNumberField("默认倍速", "0.10–5.00", rate, rateValid, enabled, decimal = true) { text ->
                            rate = text; text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in .1..5.0 }?.let { number ->
                                controller.editPreferences { it.copy(player = it.player.copy(playbackRate = number)) }
                            }
                        }
                    }
                } }
                item { SettingsGroup("文件列表") {
                    SettingsAction("特殊前缀", "${prefs.prefixes.size} 条显示与分组规则", R.drawable.ic_folder, enabled) { prefixesOpen = true }
                } }
                item { SettingsGroup("网页编辑器") {
                    Column(Modifier.padding(16.dp)) {
                        val themes = listOf("" to "默认", "chrome" to "Chrome", "clouds" to "Clouds", "crimson_editor" to "Crimson Editor",
                            "dawn" to "Dawn", "github" to "GitHub", "monokai" to "Monokai", "solarized_light" to "Solarized Light",
                            "solarized_dark" to "Solarized Dark", "terminal" to "Terminal", "tomorrow_night" to "Tomorrow Night", "twilight" to "Twilight", "xcode" to "XCode")
                        AccountChoice("编辑器主题", prefs.aceEditorTheme, if (themes.any { it.first == prefs.aceEditorTheme }) themes else themes + (prefs.aceEditorTheme to prefs.aceEditorTheme), enabled) { value ->
                            controller.editPreferences { it.copy(aceEditorTheme = value) }
                        }
                    }
                } }
                item { SettingsGroup("账户安全") {
                    if (state.canChangePassword) SettingsAction("修改密码", "单独确认，不会更改其他账户设置", R.drawable.ic_lock,
                        !state.saving && !state.loading && !state.passwordUnknown) { passwordOpen = true }
                    else Text(when {
                        state.capabilities == null -> "账户认证能力尚未确认"
                        state.capabilities?.passwordChangesAvailable == false -> "服务器使用无密码认证"
                        else -> "密码已由管理员锁定"
                    }, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                if (state.dirty) item { TextButton(controller::discardPreferences, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("放弃设置草稿") } }
            }
        }
    }
    if (prefixesOpen && prefs != null) ModalBottomSheet(onDismissRequest = { prefixesOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        var newPrefix by remember(state.scope) { mutableStateOf("") }
        var prefixError by remember(state.scope) { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).imePadding().padding(horizontal = 16.dp)) {
            Text("特殊前缀", style = MaterialTheme.typography.titleLarge)
            Text("仅控制目录列表的显示和展开，不限制直接路径、搜索或最近访问。", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(prefs.prefixes, key = { _, item -> item.prefix }) { index, rule ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rule.prefix, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("显示", style = MaterialTheme.typography.labelSmall)
                                Checkbox(rule.visible, { value -> controller.editPreferences { it.copy(prefixes = it.prefixes.map { old -> if (old.prefix == rule.prefix) old.copy(visible = value) else old }) } }, enabled = enabled,
                                    modifier = Modifier.semantics { contentDescription = "显示前缀 ${rule.prefix}" }) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("展开", style = MaterialTheme.typography.labelSmall)
                                Checkbox(rule.expanded, { value -> controller.editPreferences { it.copy(prefixes = it.prefixes.map { old -> if (old.prefix == rule.prefix) old.copy(expanded = value) else old }) } }, enabled = enabled,
                                    modifier = Modifier.semantics { contentDescription = "展开前缀 ${rule.prefix}" }) }
                        }
                        Row {
                            TextButton({ controller.editPreferences { it.copy(prefixes = it.prefixes.toMutableList().apply { add(index - 1, removeAt(index)) }) } },
                                enabled = enabled && index > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("上移") }
                            TextButton({ controller.editPreferences { it.copy(prefixes = it.prefixes.toMutableList().apply { add(index + 1, removeAt(index)) }) } },
                                enabled = enabled && index < prefs.prefixes.lastIndex, modifier = Modifier.heightIn(min = 48.dp)) { Text("下移") }
                            if (rule.prefix !in BUILT_IN_ACCOUNT_PREFIXES) TextButton({ controller.editPreferences { it.copy(prefixes = it.prefixes.filterNot { old -> old.prefix == rule.prefix }) } },
                                enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除") }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newPrefix, { newPrefix = it; prefixError = null }, label = { Text("添加自定义前缀") }, singleLine = true, enabled = enabled, modifier = Modifier.weight(1f))
                TextButton({
                    val next = prefs.copy(prefixes = prefs.prefixes + AccountPrefixRule(newPrefix))
                    try { next.validate(); controller.editPreferences { next }; newPrefix = ""; prefixError = null }
                    catch (_: IllegalArgumentException) { prefixError = "前缀须为 1–8 个可见字符，不重复，最多 20 条自定义规则" }
                }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("添加") }
            }
            prefixError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            TextButton({ prefixesOpen = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("完成") }
        }
    }
    if (passwordOpen) ModalBottomSheet(onDismissRequest = {
        if (!state.saving) { passwordOpen = false; if (!state.passwordUnknown) controller.clearPasswordDraft() }
    }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("修改密码", style = MaterialTheme.typography.titleLarge) }
            item { Text("至少 ${state.capabilities?.minimumPasswordLength ?: "—"} 字节；密码只用于本次提交。", style = MaterialTheme.typography.bodySmall) }
            if (state.capabilities?.currentPasswordRequired == true) item {
                AccountPasswordField("当前密码", state.password.current, !state.saving && !state.passwordUnknown) { value -> controller.editPassword { it.copy(current = value) } }
            }
            item { AccountPasswordField("新密码", state.password.replacement, !state.saving && !state.passwordUnknown) { value -> controller.editPassword { it.copy(replacement = value) } } }
            item { AccountPasswordField("确认新密码", state.password.confirmation, !state.saving && !state.passwordUnknown) { value -> controller.editPassword { it.copy(confirmation = value) } } }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            item { Button(controller::savePassword, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = state.canChangePassword && !state.loading && !state.saving && !state.passwordUnknown) { Text(if (state.saving) "正在修改" else "确认修改密码") } }
            if (state.passwordUnknown) item {
                OutlinedButton(onReconnect, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新连接核对") }
                TextButton(controller::clearPasswordDraft, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清除密码草稿") }
            }
        }
    }
}

@Composable private fun AccountToggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
@Composable private fun AccountNumberField(label: String, hint: String, value: String, valid: Boolean, enabled: Boolean, decimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 12) onChange(it) }, label = { Text(label) }, supportingText = { Text(hint) }, singleLine = true,
        isError = !valid, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
@Composable private fun AccountChoice(label: String, selected: String, choices: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(label, Modifier.weight(1f)); Text(choices.firstOrNull { it.first == selected }?.second ?: selected)
        }
        DropdownMenu(open, { open = false }) { choices.forEach { (value, title) -> DropdownMenuItem({ Text(title) }, { open = false; onSelect(value) }) } }
    }
}
@Composable private fun AccountPasswordField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}
