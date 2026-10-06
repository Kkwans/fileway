package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppearanceState(val theme: AppTheme = AppTheme.SYSTEM, val loaded: Boolean = false,
    val loading: Boolean = false, val saving: Boolean = false, val error: String? = null)

/** One activity-owned writer; only a successful Room commit changes the effective preference. */
class AppearanceController(private val scope: CoroutineScope, private val read: suspend () -> AppTheme,
    private val write: suspend (AppTheme) -> Unit) {
    private val mutable = MutableStateFlow(AppearanceState())
    val state = mutable.asStateFlow()
    init { reload() }
    fun reload() {
        if (mutable.value.loading || mutable.value.saving) return
        mutable.value = mutable.value.copy(loading = true, error = null)
        scope.launch {
            try { mutable.value = AppearanceState(theme = read(), loaded = true) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = mutable.value.copy(loading = false, error = "无法读取主题设置，请重试")
            }
        }
    }
    fun save(theme: AppTheme) {
        if (!mutable.value.loaded || mutable.value.loading || mutable.value.saving) return
        mutable.value = mutable.value.copy(saving = true, error = null)
        scope.launch {
            try { write(theme); mutable.value = mutable.value.copy(theme = theme, saving = false) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = mutable.value.copy(saving = false, error = "主题设置未能保存，请重试")
            }
        }
    }
}
