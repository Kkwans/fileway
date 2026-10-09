package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class AdminUsersState(val scope: String = "", val users: List<ManagedUser> = emptyList(),
    val capabilities: ServerCapabilities? = null, val draft: ManagedUserDraft? = null,
    val query: String = "", val loading: Boolean = false, val saving: Boolean = false, val authorized: Boolean = false,
    val confirmation: String? = null, val currentPassword: String = "", val unknown: Boolean = false,
    val error: String? = null, val notice: String? = null) {
    override fun toString() = "AdminUsersState(scope=$scope, saving=$saving, unknown=$unknown, secrets=redacted)"
}
class AdminUsersController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean,
    private val onOwnAccountChanged: suspend (SessionContext, ManagedUser, String?) -> Unit = { _, _, _ -> },
    private val onOwnAccountDeleted: (SessionContext) -> Unit = {}) {
    private val mutable = MutableStateFlow(AdminUsersState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var read: Job? = null
    private var write: Job? = null
    private var revision = 0L
    private var readIntent = 0L
    private var visible = false
    fun bind(context: SessionContext?) {
        if (bound === context) return
        invalidateRead(); write?.cancel(); revision++
        bound = context; visible = false; mutable.value = AdminUsersState(scope = context?.owner.orEmpty())
    }
    private fun current(context: SessionContext, epoch: Long) = bound === context && isCurrent(context) && revision == epoch
    private fun currentRead(context: SessionContext, epoch: Long, intent: Long) = current(context, epoch) && readIntent == intent
    private fun invalidateRead() { readIntent++; read?.cancel(); read = null }
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) { refresh(); return }
        invalidateRead()
        val before = mutable.value
        mutable.value = if (before.saving || before.unknown) before.copy(loading = false)
            else before.copy(loading = false, draft = null, confirmation = null, currentPassword = "", error = null)
    }
    fun query(value: String) { mutable.value = mutable.value.copy(query = value) }
    private suspend fun users(context: SessionContext): List<ManagedUser> {
        val rows = context.api.array("/api/users")
        return (0 until rows.length()).map { ManagedUser.from(rows.getJSONObject(it)) }.sortedBy { it.id }.also { list ->
            check(list.map { it.id }.distinct().size == list.size) { "服务器用户标识重复" }
        }
    }
    fun refresh() {
        val context = bound ?: return
        if (!isCurrent(context) || mutable.value.loading || mutable.value.saving) return
        invalidateRead(); val intent = readIntent
        val epoch = ++revision; mutable.value = mutable.value.copy(loading = true, error = mutable.value.error.takeIf { mutable.value.unknown }, notice = null)
        read = scope.launch {
            try {
                val (rows, capabilities) = coroutineScope {
                    val list = async { users(context) }; val policy = async { context.api.clientCapabilities() }
                    list.await() to policy.await()
                }
                if (currentRead(context, epoch, intent)) mutable.value = mutable.value.copy(users = rows, capabilities = capabilities,
                    loading = false, authorized = true, notice = if (mutable.value.unknown) "用户列表已更新；未知写结果仍须核实，未自动重发" else null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentRead(context, epoch, intent)) mutable.value = mutable.value.copy(loading = false,
                    authorized = if (error is ServiceException && error.status in setOf(401, 403)) false else mutable.value.authorized,
                    error = if (error is ServiceException && error.status == 403) "当前账号没有用户管理权限" else "用户列表读取失败，请重试")
            }
        }
    }
    fun select(id: Long) {
        val context = bound ?: return
        if (!isCurrent(context) || !mutable.value.authorized || mutable.value.saving || mutable.value.unknown) return
        invalidateRead(); val intent = readIntent; val epoch = ++revision
        mutable.value = mutable.value.copy(loading = true, error = null, notice = null, currentPassword = "", confirmation = null)
        read = scope.launch {
            try {
                val user = ManagedUser.from(context.api.request("GET", "/api/users/$id"))
                check(user.id == id) { "服务器返回了其他用户" }
                if (currentRead(context, epoch, intent)) mutable.value = mutable.value.copy(draft = user.draft(), loading = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentRead(context, epoch, intent)) mutable.value = mutable.value.copy(loading = false, error = "用户详情读取失败，请重试")
            }
        }
    }
    fun create() {
        val context = bound ?: return
        if (!isCurrent(context) || !mutable.value.authorized || mutable.value.saving || mutable.value.unknown) return
        invalidateRead(); val intent = readIntent; val epoch = ++revision
        mutable.value = mutable.value.copy(loading = true, error = null, notice = null, currentPassword = "", confirmation = null)
        read = scope.launch {
            try {
                val draft = ManagedUser.newDraft(context.api.request("GET", "/api/settings"))
                if (currentRead(context, epoch, intent)) mutable.value = mutable.value.copy(draft = draft, loading = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentRead(context, epoch, intent)) mutable.value = mutable.value.copy(loading = false, error = "默认用户设置读取失败，请重试")
            }
        }
    }
    fun edit(transform: (ManagedUserDraft) -> ManagedUserDraft) {
        val before = mutable.value
        if (!before.authorized || before.loading || before.saving || before.unknown) return
        before.draft?.let { mutable.value = before.copy(draft = transform(it), error = null, notice = null) }
    }
    fun closeEditor() {
        val before = mutable.value
        if (!before.saving && !before.unknown) {
            invalidateRead()
            mutable.value = before.copy(loading = false, draft = null, confirmation = null, currentPassword = "", error = null)
        }
    }
    fun clearSecrets() {
        val before = mutable.value
        if (!before.saving) mutable.value = before.copy(currentPassword = "", draft = before.draft?.copy(password = ""))
    }
    fun currentPassword(value: String) { if (!mutable.value.saving && !mutable.value.unknown) mutable.value = mutable.value.copy(currentPassword = value, error = null) }
    fun cancelConfirmation() { if (!mutable.value.saving) mutable.value = mutable.value.copy(confirmation = null, currentPassword = "") }
    fun requestSave() {
        val before = mutable.value; val draft = before.draft ?: return; val capabilities = before.capabilities ?: return
        if (!before.authorized || before.saving || before.loading || before.unknown) return
        try { draft.data(capabilities.minimumPasswordLength) }
        catch (_: IllegalArgumentException) { mutable.value = before.copy(error = "请检查用户名、密码长度、命令白名单和权限依赖"); return }
        mutable.value = before.copy(confirmation = "save", currentPassword = "", error = null)
    }
    fun requestDelete() {
        val before = mutable.value; val draft = before.draft ?: return
        if (before.authorized && !draft.creating && !before.saving && !before.loading && !before.unknown)
            mutable.value = before.copy(confirmation = "delete", currentPassword = "", error = null)
    }
    fun confirm() {
        val context = bound ?: return
        val before = mutable.value; val draft = before.draft ?: return; val capabilities = before.capabilities ?: return
        val action = before.confirmation ?: return
        if (!isCurrent(context) || !before.authorized || before.loading || before.saving || before.unknown) return
        if (capabilities.currentPasswordRequired && before.currentPassword.isEmpty()) {
            mutable.value = before.copy(error = "请输入当前管理员的密码"); return
        }
        val data = try { if (action == "delete") null else draft.data(capabilities.minimumPasswordLength) }
        catch (_: IllegalArgumentException) { mutable.value = before.copy(error = "请检查用户设置后重试"); return }
        val payload = JSONObject().apply {
            if (capabilities.currentPasswordRequired) put("current_password", before.currentPassword)
            if (data != null) { put("what", "user"); put("which", if (draft.creating) JSONArray() else JSONArray(listOf("all"))); put("data", data) }
        }
        read?.cancel(); val epoch = ++revision
        mutable.value = before.copy(saving = true, error = null, notice = null)
        write = scope.launch {
            var accepted = false
            try {
                if (action == "delete") context.api.action("DELETE", "/api/users/${draft.id}", payload)
                else context.api.action(if (draft.creating) "POST" else "PUT", if (draft.creating) "/api/users" else "/api/users/${draft.id}", payload)
                accepted = true
                if (!current(context, epoch)) return@launch
                if (action == "delete" && draft.id == context.account.userId) {
                    mutable.value = mutable.value.copy(saving = false, confirmation = null, currentPassword = "", draft = null,
                        authorized = false, notice = "当前账号已删除，需要重新连接")
                    onOwnAccountDeleted(context); return@launch
                }
                if (action != "delete" && !draft.creating && draft.id == context.account.userId) {
                    val fresh = ManagedUser.from(context.api.request("GET", "/api/users/${draft.id}"))
                    check(fresh.id == draft.id)
                    if (!current(context, epoch)) return@launch
                    onOwnAccountChanged(context, fresh, draft.password.takeIf { it.isNotEmpty() })
                    if (!current(context, epoch)) return@launch
                    mutable.value = mutable.value.copy(saving = false, confirmation = null, currentPassword = "", draft = null,
                        authorized = false, notice = "当前账号设置已更新，需要重新连接")
                    return@launch
                }
                val rows = users(context)
                if (current(context, epoch)) mutable.value = mutable.value.copy(users = rows, saving = false, draft = null,
                    confirmation = null, currentPassword = "", notice = if (action == "delete") "用户已删除" else if (draft.creating) "用户已创建" else "用户已更新")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context, epoch)) {
                    val rejected = !accepted && error is ServiceException && error.status in setOf(400, 401, 403, 404, 409)
                    mutable.value = mutable.value.copy(saving = false, unknown = !rejected,
                        authorized = before.authorized && !(error is ServiceException && error.status in setOf(401, 403)),
                        error = if (rejected) "服务器未接受操作，请检查当前管理员密码、输入和权限。最后一位管理员不能删除。"
                            else if (accepted) "服务器已接受操作，后续核对未完成；请重新连接核实，勿重复提交"
                            else "操作结果未知，草稿保留；请核实用户列表和登录情况，勿重复提交")
                }
            }
        }
    }
}
