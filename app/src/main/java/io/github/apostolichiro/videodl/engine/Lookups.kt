package io.github.apostolichiro.videodl.engine

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * One Python with yt-dlp already loaded, kept running for looking links up (assets/lookup_worker.py).
 * Starting Python and importing yt-dlp takes seconds on a phone; a running one answers in a fraction of that.
 * Downloads still run as their own process (they need progress lines and can be stopped).
 */
object Lookups {
    private const val TAG = "VideoDl"

    /** The lookup itself failed (unsupported link, private video...): the message is yt-dlp's. */
    class Failed(message: String) : Exception(message)

    /** The helper isn't available; the caller falls back to a one-off yt-dlp. */
    class Unavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

    private val lock = Any()
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var ready = CompletableFuture<Boolean>()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val ids = AtomicLong(1)
    @Volatile private var failures = 0

    private fun base(ctx: Context) = File(ctx.noBackupFilesDir, YoutubeDL.baseName)

    /** Starts the helper in the background if it isn't running (call after the engine is set up). */
    fun warmUp(ctx: Context) {
        try {
            ensure(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "lookup helper not started", e)
        }
    }

    /** Ends the helper (after yt-dlp was updated, so the next one loads the new version). */
    fun stop() {
        synchronized(lock) {
            process?.destroy()
            process = null
            input = null
        }
    }

    private fun ensure(ctx: Context): CompletableFuture<Boolean> {
        synchronized(lock) {
            process?.let { if (it.isAlive) return ready }
            // A helper that keeps dying isn't worth restarting every time.
            if (failures >= 3) throw Unavailable("lookup helper failed too often")
            val app = ctx.applicationContext
            val base = base(app)
            val pythonDir = File(base, "packages/python")
            val python = File(app.applicationInfo.nativeLibraryDir, "libpython.so")
            val ytdlp = File(base, "${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}")
            if (!python.isFile || !ytdlp.isFile || !pythonDir.isDirectory) throw Unavailable("engine not set up yet")
            val script = File(app.filesDir, "lookup_worker.py")
            app.assets.open("lookup_worker.py").use { s -> script.outputStream().use { s.copyTo(it) } }

            val pb = ProcessBuilder(python.absolutePath, "-u", script.absolutePath, ytdlp.absolutePath)
            // The same surroundings youtubedl-android gives the Python it starts.
            pb.environment().apply {
                put("LD_LIBRARY_PATH", "${pythonDir.absolutePath}/usr/lib:${base.absolutePath}/packages/ffmpeg/usr/lib:${base.absolutePath}/packages/aria2c/usr/lib")
                put("SSL_CERT_FILE", "${pythonDir.absolutePath}/usr/etc/tls/cert.pem")
                put("PATH", (System.getenv("PATH") ?: "") + ":" + app.applicationInfo.nativeLibraryDir)
                put("PYTHONHOME", "${pythonDir.absolutePath}/usr")
                put("HOME", "${pythonDir.absolutePath}/usr")
                put("TMPDIR", app.cacheDir.absolutePath)
            }
            val p = pb.start()
            val started = CompletableFuture<Boolean>()
            process = p
            input = p.outputStream.bufferedWriter(Charsets.UTF_8)
            ready = started
            Thread({ readAnswers(p, started) }, "lookup-out").start()
            Thread({
                // Keep its error output flowing (a full pipe would stop it) and in the log for problems.
                try {
                    p.errorStream.bufferedReader().forEachLine { Log.d(TAG, "lookup: ${it.take(300)}") }
                } catch (_: Exception) {
                }
            }, "lookup-err").start()
            return started
        }
    }

    private fun readAnswers(p: Process, started: CompletableFuture<Boolean>) {
        try {
            p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val o = try {
                    JSONObject(line)
                } catch (_: Exception) {
                    return@forEachLine
                }
                val id = o.optLong("id", -1)
                if (id == 0L) {
                    Log.i(TAG, "lookup helper: ${o.optString("out")}")
                    failures = 0
                    started.complete(true)
                } else {
                    pending.remove(id)?.complete(o)
                }
            }
        } catch (_: Exception) {
        } finally {
            synchronized(lock) {
                if (process === p) {
                    process = null
                    input = null
                }
            }
            if (!started.isDone) failures++
            started.complete(false)
            // Everyone still waiting gets a "try the other way".
            val gone = Unavailable("lookup helper stopped")
            pending.keys.toList().forEach { k -> pending.remove(k)?.completeExceptionally(gone) }
        }
    }

    /**
     * What `yt-dlp -J [args] url` would print. Throws [Failed] when yt-dlp couldn't look the link up,
     * [Unavailable] when the helper itself can't be used (then a one-off yt-dlp should be tried).
     */
    fun json(ctx: Context, url: String, args: List<String>, timeoutMs: Long = 90_000): String {
        val ok = try {
            ensure(ctx).get(30, TimeUnit.SECONDS)
        } catch (e: Unavailable) {
            throw e
        } catch (e: Exception) {
            throw Unavailable("lookup helper did not start", e)
        }
        if (!ok) throw Unavailable("lookup helper did not start")
        val id = ids.getAndIncrement()
        val answer = CompletableFuture<JSONObject>()
        pending[id] = answer
        try {
            val line = JSONObject().put("id", id).put("url", url).put("args", JSONArray(args)).toString()
            synchronized(lock) {
                val w = input ?: throw Unavailable("lookup helper not running")
                w.write(line)
                w.newLine()
                w.flush()
            }
            val o = try {
                answer.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw Failed("ERROR: 영상 정보를 확인하는 데 너무 오래 걸려요.")
            } catch (e: java.util.concurrent.ExecutionException) {
                throw (e.cause as? Unavailable) ?: Unavailable("lookup failed", e.cause)
            }
            if (o.optBoolean("ok")) return o.optString("out")
            throw Failed(o.optString("err").ifBlank { "ERROR: 알 수 없는 오류" })
        } catch (e: IOException) {
            if (e is Unavailable) throw e
            throw Unavailable("could not talk to the lookup helper", e)
        } finally {
            pending.remove(id)
        }
    }
}
