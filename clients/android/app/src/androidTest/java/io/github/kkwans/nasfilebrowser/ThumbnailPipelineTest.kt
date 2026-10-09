package io.github.kkwans.nasfilebrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailPipelineTest {
    @ExternalNetworkAcceptance
    @Test fun realWindowsThumbnailUsesTheProductionLoader(): Unit = runBlocking {
        require(InstrumentationRegistry.getArguments().getString("nfbThumbnailDiagnostic") == "true")
        val config = privateAdbConfiguration("REAL_WINDOWS_SOCKET", "fileway-windows-")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val cache = withContext(Dispatchers.Main) { CacheController(context, owner) }
        val api = NasSession.login(ServerProfile(name = "Owned thumbnail", address = config.getString("baseUrl"), backend = BackendKind.WINDOWS),
            config.getString("username"), config.getString("password"))
        config.remove("password")
        try {
            cache.awaitReady()
            for (name in listOf(config.getString("pngName"), config.getString("videoName"))) {
                val path = "/$name"
                val asset = api.preview(path, SearchResult.encodePath(path))
                try {
                    val reply = cache.thumbnailLoader.value.execute(ImageRequest.Builder(context).data(asset.url).size(512, 512).build())
                    if (reply is ErrorResult) throw AssertionError("Actual thumbnail loader failed: ${reply.throwable.javaClass.simpleName}: ${reply.throwable.message}", reply.throwable)
                    assertTrue(reply is SuccessResult)
                } finally { asset.release() }
            }
        } finally { api.close(); owner.cancel(); withContext(Dispatchers.Main) { cache.close() } }
    }
}
