package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.core.NativeTransport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class SavedSession(val api: NasSession, val verification: JSONObject)

/** Only a read may be retried, and only after a definitive unauthenticated reply.
 * A failed token renewal after a mutation never enters this recovery path. */
internal suspend fun restoreSavedSession(
    store: ProfileStore, profile: ServerProfile, account: AccountRecord,
    verificationEndpoint: String = "/api/resources/",
    native: suspend (JSONObject) -> Any? = NativeTransport::call,
): SavedSession {
    val token = store.token(profile, account) ?: error("保存的登录已失效，请重新输入密码")
    val saved = NasSession.restore(profile, token, account.userId, native)
    try {
        return SavedSession(saved, saved.request("GET", verificationEndpoint))
    } catch (failure: Exception) {
        withContext(NonCancellable) { runCatching { saved.close() }.onFailure(failure::addSuppressed) }
        if (failure !is ServiceException || failure.status != 401) throw failure
        val password = store.password(profile, account) ?: throw failure
        val renewed = try { NasSession.login(profile, saved.identity.username, password, native) }
        catch (loginFailure: Exception) {
            if (loginFailure is ServiceException && loginFailure.status in setOf(401, 403))
                store.forgetRejectedPassword(profile, account, password)
            throw loginFailure
        }
        try {
            if (renewed.identity.id != account.userId) {
                store.forgetRejectedPassword(profile, account, password)
                error("保存的账号身份已变化，请重新确认登录")
            }
            store.refreshToken(profile, account, renewed.token())
            return SavedSession(renewed, renewed.request("GET", verificationEndpoint))
        } catch (renewFailure: Exception) {
            withContext(NonCancellable) {
                if (renewFailure is ServiceException && renewFailure.status == 401)
                    runCatching { store.forgetRejectedPassword(profile, account, password) }.onFailure(renewFailure::addSuppressed)
                runCatching { renewed.close() }.onFailure(renewFailure::addSuppressed)
            }
            throw renewFailure
        }
    }
}
