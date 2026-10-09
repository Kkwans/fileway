package io.github.kkwans.nasfilebrowser.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TransportException(message: String) : Exception(message)

object NativeTransport {
    init { System.loadLibrary("nfbbridge") }
    private external fun nativeCall(command: ByteArray): ByteArray?
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancellations = Executors.newSingleThreadExecutor()

    suspend fun call(command: JSONObject): Any? = suspendCancellableCoroutine { continuation ->
        val request = JSONObject(command.toString())
        val id = UUID.randomUUID().toString()
        request.put("requestId", id)
        continuation.invokeOnCancellation {
            cancellations.execute {
                nativeCall(JSONObject().put("op", "cancel").put("requestId", id).toString().toByteArray(Charsets.UTF_8))
            }
        }
        requests.launch {
            if (!continuation.isActive) return@launch
            try {
                val bytes = nativeCall(request.toString().toByteArray(Charsets.UTF_8))
                    ?: throw TransportException("无法读取传输响应")
                val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
                if (!envelope.optBoolean("ok")) throw TransportException(envelope.optString("error", "连接失败"))
                val result = envelope.opt("result").takeUnless { it == JSONObject.NULL }
                if (!continuation.isActive && result is String) {
                    val cleanup = when (request.optString("op")) {
                        "open" -> JSONObject().put("op", "close_session").put("session", result)
                        "lease", "asset", "preview", "upload_lease" -> JSONObject().put("op", "revoke").put("url", result)
                        "search_start" -> JSONObject().put("op", "search_cancel").put("session", request.optString("session")).put("search", result)
                        "command_start" -> JSONObject().put("op", "command_cancel").put("session", request.optString("session")).put("commandHandle", result)
                        else -> null
                    }
                    cleanup?.let { nativeCall(it.toString().toByteArray(Charsets.UTF_8)) }
                }
                continuation.resume(result)
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    }
}
