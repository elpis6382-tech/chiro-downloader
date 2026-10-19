package io.github.apostolichiro.videodl.engine

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import io.github.apostolichiro.videodl.Store
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wraps yt-dlp (Python + ffmpeg bundled by youtubedl-android) and our QuickJS build,
 * which yt-dlp needs to solve YouTube's JavaScript challenges.
 */
object Engine {
    private const val TAG = "VideoDl"
    private const val RELEASES = "https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest"
    private val initLock = Any()
    private val updateLock = Any()
    @Volatile private var inited = false

    /** Unpacks Python and ffmpeg on first run (a few seconds, once per app version). */
    fun init(ctx: Context) {
        synchronized(initLock) {
            if (inited) return
            YoutubeDL.getInstance().init(ctx.applicationContext)
            FFmpeg.getInstance().init(ctx.applicationContext)
            inited = true
        }
    }

    fun qjs(ctx: Context): File? = File(ctx.applicationInfo.nativeLibraryDir, "libqjs.so").takeIf { it.isFile }

    private fun ytdlpFile(ctx: Context) =
        File(ctx.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}")

    /** "2026.10.18.233927" -> "2026.10.18" */
    fun shortVersion(tag: String?): String? = tag?.split('.')?.take(3)?.joinToString(".")

    /**
     * Fetches the newest yt-dlp from GitHub and swaps it in atomically (a download that is already
     * running keeps using the old file). Skipped when the last check is younger than [maxAgeMs].
     * Returns true when a new version was installed.
     */
    fun update(ctx: Context, maxAgeMs: Long = 0): Boolean {
        synchronized(updateLock) { return updateLocked(ctx, maxAgeMs) }
    }

    private fun updateLocked(ctx: Context, maxAgeMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (maxAgeMs > 0 && Store.engineVersion != null && now - Store.engineCheckedAt < maxAgeMs) return false
        init(ctx)
        val json = JSONObject(httpGet(RELEASES))
        val tag = json.getString("tag_name")
        val target = ytdlpFile(ctx)
        if (tag == Store.engineVersion && target.isFile) {
            Store.engineCheckedAt = now
            return false
        }
        val assets = json.getJSONArray("assets")
        val url = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == "yt-dlp" }?.optString("browser_download_url")
            ?: throw IOException("no yt-dlp file in release $tag")
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "yt-dlp.download")
        try {
            httpDownload(url, tmp)
            // A good build is a Python zip app: a "#!" line followed by a zip archive.
            val head = ByteArray(2)
            tmp.inputStream().use { it.read(head) }
            if (tmp.length() < 1_000_000 || head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) {
                throw IOException("downloaded yt-dlp looks broken (${tmp.length()} bytes)")
            }
            if (!tmp.renameTo(target)) throw IOException("could not replace yt-dlp")
        } finally {
            tmp.delete()
        }
        Store.engineVersion = tag
        Store.engineCheckedAt = now
        Log.i(TAG, "yt-dlp updated to $tag")
        return true
    }

    class Outcome(val files: List<File>, val error: String?, val cancelled: Boolean, val log: String?)

    /**
     * Downloads [url] into [workDir]. Finished files are listed by yt-dlp itself (after every
     * merge/fix step), so whatever is returned is final and ready to be saved.
     */
    fun download(ctx: Context, id: String, url: String, quality: Quality, workDir: File, onEvent: (Event) -> Unit): Outcome {
        init(ctx)
        workDir.deleteRecursively()
        workDir.mkdirs()
        val list = File(workDir, ".files.txt")
        val req = YoutubeDLRequest(url)
        req.addOption("--cache-dir", File(ctx.filesDir, "yt-dlp-cache").absolutePath)
        req.addCommands(baseArgs(ctx) + listOf(
            "-P", workDir.absolutePath,
            "--print-to-file", "after_move:filepath", list.absolutePath,
        ) + quality.options())
        return try {
            YoutubeDL.getInstance().execute(req, id) { _, _, line -> Lines.parse(line)?.let(onEvent) }
            Outcome(readList(list), null, false, null)
        } catch (e: YoutubeDL.CanceledException) {
            Outcome(emptyList(), null, true, null)
        } catch (e: YoutubeDLException) {
            Log.w(TAG, "yt-dlp failed: ${e.message?.takeLast(2000)}")
            Outcome(readList(list), Errors.lastErrorLine(e.message) ?: e.message, false, e.message)
        } catch (e: InterruptedException) {
            Outcome(emptyList(), null, true, null)
        }
    }

    fun cancel(id: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(id)
        } catch (_: Exception) {
        }
    }

    /** Runs yt-dlp with plain arguments (used by tests and for the version check). */
    fun run(ctx: Context, args: List<String>, url: String? = null): YoutubeDLResponse {
        init(ctx)
        val req = if (url != null) YoutubeDLRequest(url) else YoutubeDLRequest(emptyList<String>())
        req.addOption("--cache-dir", File(ctx.filesDir, "yt-dlp-cache").absolutePath)
        req.addCommands(baseArgs(ctx) + args)
        return YoutubeDL.getInstance().execute(req)
    }

    private fun baseArgs(ctx: Context): List<String> = buildList {
        addAll(listOf(
            "--ignore-config",
            "--no-playlist",
            "--newline",
            // Keep "now" as the file date so it shows up on top in the gallery.
            "--no-mtime",
            "--concurrent-fragments", "4",
            "--retries", "5",
            "--fragment-retries", "10",
            "-o", "%(title,id).80B.%(ext)s",
            "--progress-template", Lines.downloadTemplate,
            "--progress-template", Lines.postTemplate,
        ))
        qjs(ctx)?.let { addAll(listOf("--js-runtimes", "quickjs:" + it.absolutePath)) }
    }

    private fun readList(list: File): List<File> =
        if (!list.isFile) emptyList()
        else list.readLines().map { it.trim() }.filter { it.isNotEmpty() }.map { File(it) }.filter { it.isFile }.distinct()

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "video-downloader-android")
        setRequestProperty("Accept", "application/vnd.github+json, */*")
    }

    private fun httpGet(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun httpDownload(url: String, to: File) {
        var c = open(url)
        try {
            // HttpURLConnection won't follow a redirect to another host on its own in every Android version.
            var hops = 0
            while (c.responseCode in 300..399 && hops < 5) {
                val next = c.getHeaderField("Location") ?: break
                c.disconnect()
                c = open(URL(URL(url), next).toString())
                hops++
            }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            val expected = c.contentLengthLong
            c.inputStream.use { input -> to.outputStream().use { input.copyTo(it, 1 shl 16) } }
            if (expected > 0 && to.length() != expected) throw IOException("download cut short")
        } finally {
            c.disconnect()
        }
    }
}
