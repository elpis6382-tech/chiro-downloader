package io.github.apostolichiro.videodl

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.apostolichiro.videodl.engine.Engine
import io.github.apostolichiro.videodl.engine.Errors
import io.github.apostolichiro.videodl.engine.Event
import io.github.apostolichiro.videodl.engine.fixableByUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Works through the download list one link at a time, in the foreground, so it keeps going with the screen off. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var working = false
    private var lastStartId = 0
    @Volatile private var currentId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        Notifs.ensureChannels(this)
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_ID)?.let { cancel(it) }
            synchronized(lock) { if (!working) stopSelfResult(startId) }
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, Notifs.ID_PROGRESS, Notifs.progress(this, "영상 다운로더", "준비 중…", null, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        synchronized(lock) {
            lastStartId = startId
            if (!working) {
                working = true
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videodl:download").apply { acquire(6 * 60 * 60 * 1000L) }
                scope.launch { runQueue() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun runQueue() {
        while (true) {
            val job = synchronized(lock) {
                Store.nextQueued() ?: run {
                    working = false
                    try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelfResult(lastStartId)
                    null
                }
            } ?: return
            try {
                process(job)
            } catch (e: Exception) {
                Store.update(job.id, persist = true) { it.copy(status = Status.FAILED, error = "앱 안에서 문제가 생겼어요: ${e.message}", progress = null) }
            }
        }
    }

    private fun process(job: Job) {
        val id = job.id
        currentId = id
        val work = File(cacheDir, "dl/$id")
        try {
            Store.update(id) { it.copy(status = Status.PREPARING, step = "STARTING", progress = null, error = null, detail = null) }
            notifyProgress(force = true)
            try {
                Engine.init(this)
            } catch (e: Exception) {
                fail(id, "다운로드 엔진을 준비하지 못했어요. 앱을 다시 설치해 보세요.", e.message)
                return
            }
            // Sites change all the time, so a yt-dlp from the last day is worth a short wait.
            if (Store.engineVersion == null || System.currentTimeMillis() - Store.engineCheckedAt > DAY) {
                Store.update(id) { it.copy(step = "UPDATING") }
                notifyProgress(force = true)
                App.updateEngine(this, DAY)
            }
            if (isCancelled(id)) return markCancelled(id)
            Store.update(id) { it.copy(step = null) }
            notifyProgress(force = true)

            var outcome = runDownload(job, work)
            if (!outcome.cancelled && !isCancelled(id) && outcome.files.isEmpty() && outcome.error != null && Errors.fixableByUpdate(outcome.error)) {
                // Maybe the site changed today: get the newest yt-dlp and try once more.
                Store.update(id) { it.copy(status = Status.PREPARING, step = "UPDATING", progress = null) }
                notifyProgress(force = true)
                if (App.updateEngine(this, HOUR) && !isCancelled(id)) {
                    Store.update(id) { it.copy(step = null) }
                    outcome = runDownload(job, work)
                }
            }
            if (outcome.cancelled || isCancelled(id)) return markCancelled(id)
            if (outcome.files.isEmpty()) {
                fail(id, Errors.korean(outcome.error), outcome.log?.takeLast(6000))
                return
            }

            Store.update(id) { it.copy(status = Status.SAVING, progress = null, speed = null, etaSec = null) }
            notifyProgress(force = true)
            val saved = mutableListOf<SavedFile>()
            var saveError: String? = null
            for (f in outcome.files) {
                try {
                    saved += Saver.save(this, f)
                } catch (e: Exception) {
                    saveError = e.message ?: e.toString()
                }
            }
            if (saved.isEmpty()) {
                fail(id, Errors.korean(saveError), saveError)
                return
            }
            val partial = outcome.error ?: saveError
            Store.update(id, persist = true) {
                it.copy(
                    status = Status.DONE, files = saved, progress = 1f, speed = null, etaSec = null, step = null,
                    error = partial?.let { p -> "일부는 못 받았어요. " + Errors.korean(p) },
                    detail = outcome.log?.takeLast(6000),
                )
            }
            Store.get(id)?.let { Notifs.finished(this, it) }
        } finally {
            work.deleteRecursively()
            cancelRequested.remove(id)
            currentId = null
        }
    }

    private fun runDownload(job: Job, work: File): Engine.Outcome {
        val id = job.id
        return Engine.download(this, id, job.url, job.quality, work) { ev ->
            when (ev) {
                is Event.Looking -> Unit
                is Event.Download -> Store.update(id) {
                    it.copy(
                        status = Status.DOWNLOADING,
                        title = ev.title ?: it.title,
                        progress = ev.fraction,
                        speed = ev.speed,
                        etaSec = ev.etaSec,
                        step = when {
                            ev.audioOnly -> "AUDIO"
                            ev.videoOnly -> "VIDEO_ONLY"
                            else -> null
                        },
                        index = ev.index,
                        count = ev.count,
                    )
                }
                is Event.Post -> if (ev.step != "MoveFiles") Store.update(id) {
                    it.copy(status = Status.PROCESSING, step = ev.step, title = ev.title ?: it.title, progress = null, speed = null, etaSec = null)
                }
            }
            notifyProgress()
        }
    }

    private fun fail(id: String, message: String, detail: String?) {
        Store.update(id, persist = true) { it.copy(status = Status.FAILED, error = message, detail = detail, progress = null, speed = null, etaSec = null, step = null) }
        Store.get(id)?.let { Notifs.finished(this, it) }
    }

    private fun markCancelled(id: String) {
        Store.update(id, persist = true) { it.copy(status = Status.CANCELLED, progress = null, speed = null, etaSec = null, step = null) }
    }

    private fun notifyProgress(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 700) return
        lastNotify = now
        val job = currentId?.let { Store.get(it) } ?: return
        val waiting = Store.jobs.value.count { it.status == Status.QUEUED }
        val text = Texts.status(job) + if (waiting > 0) " · 대기 ${waiting}개" else ""
        val percent = job.progress?.let { (it * 100).toInt() }?.takeIf { job.status == Status.DOWNLOADING }
        try {
            NotificationManagerCompat.from(this).notify(Notifs.ID_PROGRESS, Notifs.progress(this, Texts.title(job), text, percent, job.id))
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val ACTION_CANCEL = "io.github.apostolichiro.videodl.CANCEL"
        const val EXTRA_ID = "id"
        private const val HOUR = 60 * 60 * 1000L
        private const val DAY = 24 * HOUR
        private val cancelRequested: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        fun isCancelled(id: String) = id in cancelRequested

        /** Stops a running download, or takes a waiting one off the list. */
        fun cancel(id: String) {
            val job = Store.get(id) ?: return
            when {
                job.status == Status.QUEUED -> Store.update(id, persist = true) { it.copy(status = Status.CANCELLED) }
                job.status.active -> {
                    cancelRequested += id
                    Engine.cancel(id)
                }
            }
        }
    }
}
