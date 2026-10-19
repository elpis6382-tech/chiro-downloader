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
import io.github.apostolichiro.videodl.engine.Fmt
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.fixableByUpdate
import io.github.apostolichiro.videodl.engine.needsLogin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Works through the download list in the foreground (so it keeps going with the screen off),
 * up to [Store.maxParallel] links at the same time.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val running = mutableSetOf<String>()
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        Notifs.ensureChannels(this)
        if (intent?.action == ACTION_CANCEL || intent?.action == ACTION_PAUSE) {
            val id = intent.getStringExtra(EXTRA_ID)
            val stop: (String) -> Unit = if (intent.action == ACTION_PAUSE) ::pause else ::cancel
            if (id != null) stop(id) else Store.jobs.value.filter { it.status == Status.QUEUED || it.status.active }.forEach { stop(it.id) }
            synchronized(lock) { if (running.isEmpty()) stopSelfResult(startId) }
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, Notifs.ID_PROGRESS, Notifs.progress(this, "영상 다운로더", "준비 중…", null, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        synchronized(lock) {
            lastStartId = startId
            if (wakeLock == null) {
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videodl:download").apply { acquire(6 * 60 * 60 * 1000L) }
            }
        }
        pump()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    /** Starts waiting jobs until [Store.maxParallel] run at once; stops the service when nothing is left. */
    private fun pump() {
        synchronized(lock) {
            while (running.size < Store.maxParallel) {
                val job = Store.nextQueued() ?: break
                running += job.id
                // Claim it right away so it isn't picked twice.
                Store.update(job.id) { it.copy(status = Status.PREPARING, step = "STARTING", progress = null, error = null, detail = null) }
                scope.launch {
                    try {
                        process(job)
                    } catch (e: Exception) {
                        Store.update(job.id, persist = true) { it.copy(status = Status.FAILED, error = "앱 안에서 문제가 생겼어요: ${e.message}", progress = null) }
                    } finally {
                        synchronized(lock) { running -= job.id }
                        pump()
                    }
                }
            }
            if (running.isEmpty()) {
                releaseWakeLock()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelfResult(lastStartId)
            }
        }
        notifyProgress(force = true)
    }

    private fun process(job: Job) {
        val id = job.id
        val work = File(cacheDir, "dl/$id")
        val cookieFile = File(cacheDir, "dl/$id.cookies")
        var keepWork = false
        try {
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
            if (isPaused(id)) {
                keepWork = true
                return markPaused(id)
            }
            if (isCancelled(id)) return markCancelled(id)

            val youtube = Links.isYouTube(job.url)
            // Pages opened in the app's browser use its logins right away (except YouTube, which
            // works best logged out); links from elsewhere only when a site asks for a login.
            val firstCookies = if (job.origin != Origin.LINK && !youtube) Cookies.export(job.url, cookieFile) else null
            var req = Engine.Request(
                url = job.url,
                quality = job.quality,
                cookies = firstCookies,
                referer = job.referer,
                userAgent = if (youtube) null else job.userAgent,
                outName = when (job.origin) {
                    Origin.SITE -> job.pageTitle?.substringBeforeLast('.')
                    // A stream has no title of its own: it is saved under the page's.
                    Origin.STREAM -> job.pageTitle ?: job.title ?: "video"
                    else -> null
                },
                singleItem = job.origin == Origin.BROWSER,
            )
            // Streams keep the page's title instead of whatever name the stream file has.
            var fromFallback = job.origin == Origin.STREAM
            var nextFallback = 0
            var updated = false
            // A paused download carries on with the pieces it already has; any later attempt starts clean
            // (a file aria2c left half-written must not be continued by another downloader).
            var outcome = runDownload(job, req, work, fromFallback, fresh = !job.resume)
            var attempts = 1
            while (outcome.files.isEmpty() && outcome.error != null && !outcome.cancelled && !isCancelled(id) && attempts < 6) {
                val err = outcome.error
                var next: Engine.Request? = null
                when {
                    // Many connections at once failed in the middle of the download: one connection.
                    req.fast && outcome.started -> next = req.copy(fast = false)
                    // The site wants a login and the in-app browser has one.
                    Errors.needsLogin(err) && req.cookies == null && Cookies.has(req.url) ->
                        next = req.copy(cookies = Cookies.export(req.url, cookieFile))
                    // The page itself can't be read, but the browser saw it play a video stream.
                    nextFallback < job.fallbacks.size -> {
                        val stream = job.fallbacks[nextFallback++]
                        fromFallback = true
                        next = Engine.Request(
                            url = stream,
                            quality = job.quality,
                            cookies = Cookies.export(stream, cookieFile) ?: req.cookies,
                            referer = job.referer ?: job.url,
                            userAgent = job.userAgent,
                            outName = job.pageTitle ?: job.title ?: "video",
                        )
                    }
                    // Maybe the site changed today: get the newest yt-dlp and try once more.
                    !updated && Errors.fixableByUpdate(err) -> {
                        updated = true
                        Store.update(id) { it.copy(status = Status.PREPARING, step = "UPDATING", progress = null) }
                        notifyProgress(force = true)
                        if (App.updateEngine(this, HOUR)) {
                            fromFallback = false
                            next = req.copy(url = job.url)
                        }
                    }
                }
                if (next == null || isCancelled(id)) break
                req = next
                Store.update(id) { it.copy(status = Status.PREPARING, step = null, progress = null, speed = null, etaSec = null, doneBytes = null, totalBytes = null) }
                outcome = runDownload(job, req, work, fromFallback, fresh = true)
                attempts++
            }
            if (outcome.cancelled || isCancelled(id)) Engine.killLeftovers(work)
            if (isPaused(id)) {
                keepWork = true
                return markPaused(id)
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
                    status = Status.DONE, files = saved, progress = 1f, speed = null, etaSec = null, step = null, resume = false,
                    error = partial?.let { p -> "일부는 못 받았어요. " + Errors.korean(p) },
                    detail = outcome.log?.takeLast(6000),
                )
            }
            Store.get(id)?.let { Notifs.finished(this, it) }
        } finally {
            if (!keepWork) work.deleteRecursively()
            cookieFile.delete()
            cancelRequested.remove(id)
            pauseRequested.remove(id)
        }
    }

    private fun runDownload(job: Job, req: Engine.Request, work: File, fromFallback: Boolean, fresh: Boolean): Engine.Outcome {
        val id = job.id
        Store.update(id) { it.copy(step = null) }
        notifyProgress(force = true)
        return Engine.download(this, id, req, work, fresh) { ev ->
            when (ev) {
                is Event.Looking -> Unit
                is Event.Item -> Store.update(id) {
                    it.copy(
                        title = if (fromFallback) it.title else ev.title ?: it.title, index = ev.index, count = ev.count,
                        thumbnail = it.thumbnail ?: ev.thumbnail,
                    )
                }
                is Event.Part -> Store.update(id) {
                    it.copy(
                        status = Status.DOWNLOADING, progress = null, speed = null, etaSec = null,
                        step = when {
                            ev.audio -> "AUDIO"
                            ev.videoOnly -> "VIDEO_ONLY"
                            else -> null
                        },
                    )
                }
                is Event.Download -> Store.update(id) {
                    it.copy(
                        status = Status.DOWNLOADING,
                        title = if (fromFallback) it.title else ev.title ?: it.title,
                        progress = ev.fraction ?: it.progress.takeIf { _ -> ev.total == null && it.status == Status.DOWNLOADING },
                        doneBytes = ev.done ?: it.doneBytes,
                        totalBytes = ev.total ?: it.totalBytes.takeIf { _ -> ev.done != null && it.status == Status.DOWNLOADING },
                        speed = ev.speed,
                        etaSec = ev.etaSec,
                        step = if (!ev.partKnown) it.step else when {
                            ev.audioOnly -> "AUDIO"
                            ev.videoOnly -> "VIDEO_ONLY"
                            else -> null
                        },
                        index = ev.index ?: it.index,
                        count = ev.count ?: it.count,
                    )
                }
                is Event.Post -> if (ev.step != "MoveFiles") Store.update(id) {
                    it.copy(
                        status = Status.PROCESSING, step = ev.step, title = if (fromFallback) it.title else ev.title ?: it.title,
                        progress = null, speed = null, etaSec = null,
                    )
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
        if (Store.removeAfterStop.remove(id)) {
            Store.remove(id)
            return
        }
        Store.update(id, persist = true) { it.copy(status = Status.CANCELLED, progress = null, speed = null, etaSec = null, step = null, resume = false) }
    }

    private fun markPaused(id: String) {
        Store.update(id, persist = true) { it.copy(status = Status.PAUSED, speed = null, etaSec = null, step = null, resume = true) }
    }

    /** One notification for everything that's running. */
    private fun notifyProgress(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 700) return
        lastNotify = now
        val ids = synchronized(lock) { running.toList() }
        val jobs = ids.mapNotNull { Store.get(it) }
        if (jobs.isEmpty()) return
        val waiting = Store.jobs.value.count { it.status == Status.QUEUED }
        val n = try {
            if (jobs.size == 1) {
                val job = jobs[0]
                val text = Texts.status(job) + if (waiting > 0) " · 대기 ${waiting}개" else ""
                val percent = job.progress?.let { (it * 100).toInt() }?.takeIf { job.status == Status.DOWNLOADING }
                Notifs.progress(this, Texts.title(job), text, percent, job.id)
            } else {
                val known = jobs.mapNotNull { it.progress?.takeIf { _ -> it.status == Status.DOWNLOADING } }
                val percent = if (known.size == jobs.size) (known.average() * 100).toInt() else null
                val speed = jobs.sumOf { it.speed ?: 0.0 }
                val text = buildString {
                    append(jobs.joinToString(", ") { Texts.title(it).take(16) })
                    if (speed > 0) append(" · " + Fmt.speed(speed))
                    if (waiting > 0) append(" · 대기 ${waiting}개")
                }
                Notifs.progress(this, "영상 ${jobs.size}개 받는 중", text, percent, null, cancelAll = true)
            }
        } catch (_: Exception) {
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(Notifs.ID_PROGRESS, n)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val ACTION_CANCEL = "io.github.apostolichiro.videodl.CANCEL"
        const val ACTION_PAUSE = "io.github.apostolichiro.videodl.PAUSE"
        const val EXTRA_ID = "id"
        private const val HOUR = 60 * 60 * 1000L
        private const val DAY = 24 * HOUR
        private val cancelRequested: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
        private val pauseRequested: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        /** Stopped for good, or paused: either way the running yt-dlp is ended. */
        fun isCancelled(id: String) = id in cancelRequested || id in pauseRequested

        fun isPaused(id: String) = id in pauseRequested

        /** Stops a running download, or takes a waiting one off the list. */
        fun cancel(id: String) {
            val job = Store.get(id) ?: return
            when {
                job.status == Status.QUEUED || job.status == Status.PAUSED -> {
                    if (Store.removeAfterStop.remove(id)) Store.remove(id)
                    else Store.update(id, persist = true) { it.copy(status = Status.CANCELLED, resume = false) }
                }
                job.status.active -> {
                    pauseRequested -= id
                    cancelRequested += id
                    Engine.cancel(id)
                }
            }
        }

        /** Stops a download but keeps what it already has, to go on later. */
        fun pause(id: String) {
            val job = Store.get(id) ?: return
            when {
                job.status == Status.QUEUED -> Store.update(id, persist = true) { it.copy(status = Status.PAUSED) }
                // Saving into the Download folder takes a moment and can't be split; let it finish.
                job.status.active && job.status != Status.SAVING -> {
                    pauseRequested += id
                    Engine.cancel(id)
                }
            }
        }
    }
}
