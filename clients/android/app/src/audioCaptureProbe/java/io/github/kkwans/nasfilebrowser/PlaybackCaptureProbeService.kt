package io.github.kkwans.nasfilebrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** AudioPlaybackCapture only: never configures a microphone or writes PCM files. */
class PlaybackCaptureProbeService : Service() {
    companion object {
        @Volatile var meter: CapturedPlaybackMeter? = null
            private set
        @Volatile var failure: String? = null
            private set
    }
    private var projection: MediaProjection? = null
    private var owned: CapturedPlaybackMeter? = null
    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission") // Checked before constructing AudioRecord; projection requires real consent.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            check(owned == null && meter == null) { "Capture already active" }
            failure = null
            check(ownedAudioProbeDevice()) { "Explicitly supported owned device required" }
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("owned-audio-probe", "自有样本音频采样", NotificationManager.IMPORTANCE_LOW))
            startForeground(417, Notification.Builder(this, "owned-audio-probe")
                .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("自有样本音频采样")
                .setContentText("仅采样本应用播放输出").build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val request = requireNotNull(intent)
            @Suppress("DEPRECATION")
            val token = requireNotNull(request.getParcelableExtra<Intent>("projection"))
            val capture = requireNotNull(getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(request.getIntExtra("resultCode", 0), token))
            projection = capture
            capture.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { owned?.revoked(); stopSelf() }
            }, Handler(Looper.getMainLooper()))
            val config = AudioPlaybackCaptureConfiguration.Builder(capture).addMatchingUid(Process.myUid())
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val recorder = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT), 38_400)).build()
            val next = CapturedPlaybackMeter(recorder)
            owned = next; meter = next
            next.start()
        } catch (error: Exception) { failure = error.javaClass.simpleName + ": " + error.message; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        val value = owned
        value?.close()
        if (meter === value) meter = null
        owned = null
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

data class CapturedTone(val frames: Long, val frequencyHz: Double, val rms: Double,
                        val maximumSilentMs: Double, val maximumReadGapMs: Double)
data class CapturedSlice(val observedAfterMs: Double, val frames: Int, val rms: Double, val readGapMs: Double)

/** Bounded 20ms PCM statistics after Android's playback mix; not proof of speaker/Bluetooth output. */
class CapturedPlaybackMeter(private val recorder: AudioRecord) {
    private data class Window(val endNs: Long, val frames: Int, val crossings: Int, val energy: Double, val readGapNs: Long)
    private val running = AtomicBoolean()
    private val windows = ArrayDeque<Window>()
    private var worker: Thread? = null
    @Volatile var error: String? = null
        private set

    @androidx.annotation.RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        check(recorder.state == AudioRecord.STATE_INITIALIZED)
        check(running.compareAndSet(false, true))
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        worker = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val samples = ShortArray(960 * 2)
            var previous = 0.0
            var lastRead = System.nanoTime()
            try {
                while (running.get()) {
                    val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    val now = System.nanoTime()
                    if (!running.get()) break
                    check(count > 0 && count % 2 == 0) { "AudioRecord read=$count" }
                    var energy = 0.0; var crossings = 0
                    for (i in 0 until count step 2) {
                        val value = samples[i].toDouble() / 32768.0
                        if (previous <= 0 && value > 0) crossings++
                        energy += value * value; previous = value
                    }
                    synchronized(windows) {
                        if (windows.size >= 1500) windows.removeFirst()
                        windows.addLast(Window(now, count / 2, crossings, energy, now - lastRead))
                    }
                    lastRead = now
                }
            } catch (failure: Exception) { if (running.get()) error = failure.javaClass.simpleName + ": " + failure.message }
        }, "OwnedPlaybackMeter").also { it.start() }
    }
    fun snapshot(sinceNs: Long): CapturedTone = synchronized(windows) {
        val relevant = windows.filter { it.endNs >= sinceNs }
        var frames = 0L; var energy = 0.0; var crossings = 0L
        for (window in relevant.asReversed()) {
            frames += window.frames; energy += window.energy; crossings += window.crossings
            if (frames >= 9600) break
        }
        var silent = 0L; var maximum = 0L
        for (window in relevant) {
            silent = if (sqrt(window.energy / window.frames) < .001) silent + window.frames else 0
            maximum = maxOf(maximum, silent)
        }
        CapturedTone(frames, if (frames > 0) crossings * 48_000.0 / frames else 0.0,
            if (frames > 0) sqrt(energy / frames) else 0.0, maximum / 48.0,
            (relevant.maxOfOrNull { it.readGapNs } ?: 0L) / 1_000_000.0)
    }
    fun timeline(sinceNs: Long): List<CapturedSlice> = synchronized(windows) {
        windows.filter { it.endNs >= sinceNs }.takeLast(300).map {
            CapturedSlice((it.endNs - sinceNs) / 1_000_000.0, it.frames,
                sqrt(it.energy / it.frames), it.readGapNs / 1_000_000.0)
        }
    }
    fun revoked() { if (running.get()) error = "MediaProjection revoked" }
    fun close() {
        running.set(false)
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        worker?.join(1000)
        check(worker?.isAlive != true) { "Playback capture worker did not finish" }
        recorder.release()
    }
}
