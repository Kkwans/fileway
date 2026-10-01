package io.github.kkwans.nasfilebrowser.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.data.*

@Composable internal fun ConnectionScreen(model: ClientModel, state: ClientState) {
    val network by model.networkState.collectAsStateWithLifecycle()
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun hasLocalAccess() = Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    var localAccess by remember { mutableStateOf(hasLocalAccess()) }
    var accessDenied by rememberSaveable { mutableStateOf(false) }
    var accessMessage by remember { mutableStateOf<String?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localAccess = granted
        accessDenied = !granted
        val next = afterPermission; afterPermission = null
        // Permission denial must not block a public service that needs no LAN
        // access. Local connections will report their real network error.
        next?.invoke()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) localAccess = hasLocalAccess() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var name by rememberSaveable(state.editorVersion) { mutableStateOf(state.profile?.name.orEmpty()) }
    var backend by rememberSaveable(state.editorVersion) { mutableStateOf(state.profile?.backend ?: BackendKind.NAS) }
    var url by rememberSaveable(state.editorVersion) { mutableStateOf(state.profile?.address.orEmpty()) }
    var username by rememberSaveable(state.editorVersion) { mutableStateOf("") }
    // Passwords never enter saved instance state or acceptance artifacts.
    var password by remember(state.editorVersion) { mutableStateOf("") }
    var visiblePassword by remember { mutableStateOf(false) }
    var mode by rememberSaveable(state.editorVersion) { mutableStateOf(if (state.profile?.network == ConnectionMode.TAILNET) "tailnet" else "direct") }
    var remove by remember { mutableStateOf<ServerProfile?>(null) }
    val profileName = name.trim().ifBlank { Uri.parse(url).host.orEmpty() }
    val canSave = !state.busy && url.isNotBlank() && profileName.isNotBlank()
    val canConnect = canSave && backend == BackendKind.NAS && username.isNotBlank() && password.isNotEmpty() && (mode == "direct" || network.connected)
    val unchanged = state.profile?.let { it.address == url.trim() && it.backend == backend && (it.network == ConnectionMode.TAILNET) == (mode == "tailnet") } == true
    val connect = {
        if (canConnect) {
            focus.clearFocus()
            val server = url.trim(); val user = username; val secret = password; val networkMode = mode; val serverType = backend
            val action = { model.connectDraft(profileName, server, serverType, user, secret, networkMode) }
            if (Build.VERSION.SDK_INT >= 37 && networkMode == "direct" && !hasLocalAccess() && !accessDenied) {
                afterPermission = action
                permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            } else action()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            val wide = maxWidth >= 840.dp && LocalDensity.current.fontScale <= 1.3f
            val page = Modifier.widthIn(max = if (wide) 1040.dp else 560.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 32.dp else 24.dp, vertical = 24.dp)
            val form: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (profiles.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("服务器档案", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { model.selectProfile(null) }, enabled = !state.busy) { Text("新建") }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(profiles, key = { it.id }) { profile ->
                                Surface(Modifier.width(248.dp), shape = RoundedCornerShape(14.dp), color = if (state.profile?.id == profile.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Column(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { model.selectProfile(profile) }.padding(vertical = 8.dp)) {
                                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                            Text(if (profile.backend == BackendKind.WINDOWS) "Windows · 暂不支持连接" else "NAS · ${if (profile.network == ConnectionMode.TAILNET) "Tailscale" else "直连"}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = { remove = profile }, enabled = !state.busy) { Text("移除档案") }
                                    }
                                }
                            }
                        }
                    }
                    ConnectionField(name, { name = it }, "档案名称", R.drawable.ic_storage, enabled = !state.busy, placeholder = "例如：家里的 NAS")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(selected = backend == BackendKind.NAS, onClick = { backend = BackendKind.NAS }, enabled = !state.busy, label = { Text("NAS") })
                        FilterChip(selected = backend == BackendKind.WINDOWS, onClick = { backend = BackendKind.WINDOWS }, enabled = !state.busy, label = { Text("Windows") })
                    }
                    Text("连接方式", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConnectionMode("本地网络", "IP 或域名", R.drawable.ic_storage, mode == "direct", Modifier.weight(1f), !state.busy) { mode = "direct" }
                        ConnectionMode("Tailscale", "远程连接", R.drawable.ic_network, mode == "tailnet", Modifier.weight(1f), !state.busy) { mode = "tailnet" }
                    }
                    if (mode == "tailnet") NetworkCard(model)
                    if (Build.VERSION.SDK_INT >= 37 && !localAccess) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("本地网络访问", style = MaterialTheme.typography.titleMedium)
                                Text(if (accessDenied) "尚未允许访问局域网。连接本地 NAS 和本地节点会受限。" else "连接局域网 NAS 和本地节点时，需要允许本地网络访问。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row {
                                    TextButton(onClick = { afterPermission = null; permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) { Text("允许访问") }
                                    if (accessDenied) TextButton(onClick = {
                                        try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                                        catch (_: Exception) { accessMessage = "无法打开设置，请在系统应用设置中允许本地网络访问。" }
                                    }) { Text("打开设置") }
                                }
                                accessMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    ConnectionField(url, { url = it }, "服务器地址", R.drawable.ic_link, enabled = !state.busy,
                        placeholder = "https://nas.example.com", keyboardType = KeyboardType.Uri,
                        supporting = "支持 IP、端口、域名和路径前缀")
                    Spacer(Modifier.height(4.dp))
                    Text("服务账号", style = MaterialTheme.typography.titleMedium)
                    if (backend == BackendKind.WINDOWS) Text("可以保存这个档案。Windows 服务适配尚未完成，浏览与播放暂不支持。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (backend == BackendKind.NAS && state.accounts.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("已保存的账号", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            state.accounts.forEach { account -> TextButton(onClick = {
                                focus.clearFocus()
                                val action = { model.restore(account) }
                                if (Build.VERSION.SDK_INT >= 37 && mode == "direct" && !hasLocalAccess() && !accessDenied) { afterPermission = action; permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) } else action()
                            }, enabled = !state.busy && unchanged && (mode == "direct" || network.connected)) { Text("继续使用 ${account.username}") } }
                        }
                    }
                    if (backend == BackendKind.NAS) {
                    ConnectionField(username, { username = it }, "账号", R.drawable.ic_person, enabled = !state.busy)
                    ConnectionField(password, { password = it }, "密码", R.drawable.ic_lock, enabled = !state.busy,
                        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, onDone = connect,
                        visualTransformation = if (visiblePassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            IconButton(onClick = { visiblePassword = !visiblePassword }) {
                                Icon(painterResource(if (visiblePassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                                    if (visiblePassword) "隐藏密码" else "显示密码")
                            }
                        })
                    }
                    state.error?.let { message ->
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                            Text(message, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (backend == BackendKind.NAS) Button(onClick = connect, enabled = canConnect, shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("连接服务器", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(painterResource(R.drawable.ic_arrow_forward), null, Modifier.size(20.dp))
                    }
                    OutlinedButton(onClick = { focus.clearFocus(); model.saveDraft(profileName, url.trim(), backend, mode) }, enabled = canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("保存服务器档案") }
                    if (state.busy) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(state.stage.ifBlank { "正在加载" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::cancel) { Text("取消") }
                    }
                    if (backend == BackendKind.NAS) Text("使用文件服务器的账号登录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (wide) Row(page, horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(0.85f).padding(top = 24.dp)) { ConnectionIntroduction() }
                Column(Modifier.weight(1.15f)) { form() }
            } else Column(page, verticalArrangement = Arrangement.spacedBy(32.dp)) {
                ConnectionIntroduction()
                form()
            }
        }
    }
    remove?.let { profile -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除 ${profile.name}？") }, text = { Text("将移除本机档案和保存的登录信息。服务器文件不会被删除。") }, confirmButton = { TextButton(onClick = { remove = null; model.removeProfile(profile) }) { Text("移除") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } }) }
}

@Composable private fun ConnectionIntroduction() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_storage), null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Text("NAS File Browser", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("连接你的文件库", style = MaterialTheme.typography.headlineLarge)
        Text("浏览文件，继续观看。\n从添加一个服务器开始。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ConnectionMode(title: String, detail: String, icon: Int, selected: Boolean, modifier: Modifier, enabled: Boolean, choose: () -> Unit) {
    Surface(modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose),
        shape = RoundedCornerShape(14.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun ConnectionField(
    value: String, change: (String) -> Unit, label: String, icon: Int, enabled: Boolean,
    placeholder: String = "", supporting: String? = null, keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next, onDone: () -> Unit = {}, visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = enabled, singleLine = true,
        leadingIcon = { Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = trailing, placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        supportingText = if (supporting == null) null else ({ Text(supporting) }),
        visualTransformation = visualTransformation, keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }), shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth())
}
