package io.github.kkwans.nasfilebrowser.upload

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import io.github.kkwans.nasfilebrowser.MainActivity
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.ClientDatabase
import kotlinx.coroutines.*

internal object UploadNotice {
    const val CHANNEL = "fileway-uploads"
    fun notification(context: Context, record: UploadRecord?, jobId: Int = record?.jobId ?: 0): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "文件上传", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, jobId, Intent(context, MainActivity::class.java).putExtra("open_uploads", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val active = record == null || record.active
        val percent = record?.let { if (it.expectedSize > 0) (it.uploaded.toDouble() / it.expectedSize * 100).toInt().coerceIn(0, if (it.complete) 100 else 99) else 0 } ?: 0
        val text = when (record?.status) { "completed" -> "上传完成"; "paused" -> "已暂停"; "failed" -> "上传失败，可在应用中重试"; "interrupted" -> "上传中断，已保存部分保留";
            "canceling" -> "正在清理未完成片段"; "cancel_failed" -> "已停止，清理待重试"; "canceled" -> "已取消上传"; else -> "$percent% · 文件上传" }
        return Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_folder).setContentTitle(record?.name ?: "正在准备上传")
            .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active)
            .apply {
                if (active) setProgress(100, percent, record == null || record.expectedSize <= 0)
                if (record != null && active) {
                    val pause = PendingIntent.getBroadcast(context, jobId, Intent(context, UploadCommandsReceiver::class.java).putExtra("id", record.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(Notification.Action.Builder(null, "暂停", pause).build())
                }
            }.build()
    }
}

object UploadScheduler {
    fun start(context: Context, record: UploadRecord) {
        if (Build.VERSION.SDK_INT >= 34) {
            val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            val info = JobInfo.Builder(record.jobId, ComponentName(context, UploadJobService::class.java)).setUserInitiated(true)
                .setRequiredNetwork(request).setEstimatedNetworkBytes(0, (record.expectedSize - record.uploaded).coerceAtLeast(1))
                .setExtras(PersistableBundle().apply { putString("id", record.id) }).build()
            check(context.getSystemService(JobScheduler::class.java).schedule(info) == JobScheduler.RESULT_SUCCESS) { "系统暂时无法启动上传，请保持应用可见后重试" }
        } else context.startForegroundService(Intent(context, UploadForegroundService::class.java).putExtra("id", record.id))
    }
    suspend fun pause(context: Context, id: String) {
        val dao = ClientDatabase.get(context).uploads(); val record = dao.get(id) ?: return
        if (dao.pause(id, System.currentTimeMillis()) != 1) return
        stop(context, record)
    }
    internal fun stop(context: Context, record: UploadRecord) {
        UploadRuntime.get(context).cancel(record.id)
        context.getSystemService(JobScheduler::class.java).cancel(record.jobId)
        context.getSystemService(NotificationManager::class.java).cancel(record.jobId)
    }
}

class UploadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private data class Run(val params: JobParameters, var observer: Job? = null)
    private val runs = mutableMapOf<Int, Run>()
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString("id") ?: return false
        val run = Run(params); runs[params.jobId] = run
        // UIDT requires a notification promptly, before suspended database IO.
        if (Build.VERSION.SDK_INT >= 34) setNotification(params, params.jobId, UploadNotice.notification(applicationContext, null, params.jobId), JOB_END_NOTIFICATION_POLICY_REMOVE)
        scope.launch {
            try {
                val dao = ClientDatabase.get(applicationContext).uploads(); val record = dao.get(id)
                if (runs[params.jobId] !== run) return@launch
                if (record == null || !record.active && record.status != "interrupted") { finish(run, false); return@launch }
                run.observer = scope.launch {
                    try { while (isActive) {
                        val current = dao.get(id) ?: break
                        runCatching { getSystemService(NotificationManager::class.java).notify(record.jobId, UploadNotice.notification(applicationContext, current)) }
                        delay(1000)
                    } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* The writer owns the terminal state. */ }
                }
                val started = UploadRuntime.get(applicationContext).launch(id) { scope.launch { finish(run, false) } }
                if (!started) finish(run, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                runCatching {
                    val dao = ClientDatabase.get(applicationContext).uploads()
                    if (runs[params.jobId] === run && dao.get(id)?.active == true) dao.command(id, "failed", System.currentTimeMillis())
                }
                finish(run, false)
            }
        }
        return true
    }
    private fun finish(run: Run, retry: Boolean) {
        if (runs[run.params.jobId] !== run) return
        runs.remove(run.params.jobId); run.observer?.cancel(); jobFinished(run.params, retry)
    }
    override fun onStopJob(params: JobParameters): Boolean {
        val run = runs[params.jobId]?.takeIf { it.params === params } ?: return false
        runs.remove(params.jobId); run.observer?.cancel()
        params.extras.getString("id")?.let { UploadRuntime.get(applicationContext).cancel(it) }
        return true
    }
    override fun onDestroy() { runs.values.forEach { it.observer?.cancel(); it.params.extras.getString("id")?.let(UploadRuntime.get(applicationContext)::cancel) }; runs.clear(); scope.cancel(); super.onDestroy() }
}

class UploadForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ids = mutableSetOf<String>()
    private val updates = mutableMapOf<String, Job>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id") ?: return START_NOT_STICKY
        startForeground(7900000, UploadNotice.notification(applicationContext, null, 7900000), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!ids.add(id)) return START_NOT_STICKY
        scope.launch {
            try {
                val dao = ClientDatabase.get(applicationContext).uploads(); val record = dao.get(id)
                if (record == null || !record.active) { finish(id); return@launch }
                updates[id] = scope.launch {
                    try { while (isActive) {
                        val current = dao.get(id) ?: break
                        runCatching { getSystemService(NotificationManager::class.java).notify(record.jobId, UploadNotice.notification(applicationContext, current)) }
                        if (ids.firstOrNull() == id) runCatching { getSystemService(NotificationManager::class.java).notify(7900000, UploadNotice.notification(applicationContext, current, 7900000)) }
                        delay(1000)
                    } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                }
                if (!UploadRuntime.get(applicationContext).launch(id) { scope.launch { finish(id) } }) finish(id)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                runCatching { ClientDatabase.get(applicationContext).uploads().command(id, "failed", System.currentTimeMillis()) }; finish(id)
            }
        }
        return START_NOT_STICKY
    }
    private fun finish(id: String) { updates.remove(id)?.cancel(); ids.remove(id); if (ids.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
    override fun onDestroy() { ids.forEach { UploadRuntime.get(applicationContext).cancel(it) }; updates.values.forEach(Job::cancel); scope.cancel(); super.onDestroy() }
}

class UploadCommandsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { runCatching { UploadScheduler.pause(context.applicationContext, id) } } finally { pending.finish() } }
    }
}
