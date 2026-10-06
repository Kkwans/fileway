package io.github.kkwans.nasfilebrowser.data

import android.app.job.JobService
import android.app.job.JobParameters
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import kotlinx.coroutines.*
import org.json.JSONObject

/** Android schedules maintenance even while the UI process is absent; never starts playback or networking. */
class CacheCleanupService : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var retry = false
            try {
                NativeTransport.call(cacheCommand(applicationContext, readCacheSettings(applicationContext)))
                NativeTransport.call(JSONObject().put("op", "cache_cleanup").put("clear", true))
            } catch (e: Exception) { if (e is CancellationException) throw e; retry = true }
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); work = null; return true }
    override fun onDestroy() { work?.cancel(); super.onDestroy() }
}
