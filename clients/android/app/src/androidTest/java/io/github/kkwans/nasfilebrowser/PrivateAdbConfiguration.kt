package io.github.kkwans.nasfilebrowser

import android.net.LocalServerSocket
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** A one-shot, shell-only config channel. Credentials never enter adb arguments,
 * application logs or test reports; callers remove tokens after binding them.
 */
internal suspend fun privateAdbConfiguration(marker: String, prefix: String): JSONObject = withContext(Dispatchers.IO) {
    val name = "$prefix${UUID.randomUUID()}"
    val server = LocalServerSocket(name)
    try {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$marker=$name\n") })
        val socket = server.accept()
        try {
            check(socket.peerCredentials.uid in setOf(0, 2000)) { "Configuration requires the authorized ADB host" }
            socket.soTimeout = 20_000
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val value = socket.inputStream.read(); check(value >= 0) { "Incomplete private configuration" }
                if (value == 10) break
                check(bytes.size() < 65_536) { "Configuration too large" }; bytes.write(value)
            }
            val result = JSONObject(bytes.toString("UTF-8"))
            socket.outputStream.write("OK\n".toByteArray())
            result
        } finally { socket.close() }
    } finally { server.close() }
}
