package io.github.apostolichiro.videodl

import android.content.Context
import android.content.SharedPreferences
import io.github.apostolichiro.videodl.engine.Preview
import io.github.apostolichiro.videodl.engine.Quality
import io.github.apostolichiro.videodl.engine.SearchEngine
import io.github.apostolichiro.videodl.engine.Shortcut
import io.github.apostolichiro.videodl.engine.Web
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class Status { QUEUED, PREPARING, DOWNLOADING, PROCESSING, SAVING, PAUSED, DONE, FAILED, CANCELLED;
    val active get() = this == PREPARING || this == DOWNLOADING || this == PROCESSING || this == SAVING
    val finished get() = this == DONE || this == FAILED || this == CANCELLED
}

data class SavedFile(val uri: String, val name: String, val mime: String, val size: Long) {
    val isVideo get() = mime.startsWith("video/")
    val isAudio get() = mime.startsWith("audio/")
}

/**
 * Where a download came from: a link (shared, pasted), a page open in the browser, a site's own
 * download button, or a video stream the browser saw a page play.
 */
enum class Origin { LINK, BROWSER, SITE, STREAM }

/** A download about to be added to the list. */
data class NewJob(
    val url: String,
    val quality: Quality,
    val origin: Origin = Origin.LINK,
    val referer: String? = null,
    val pageTitle: String? = null,
    val userAgent: String? = null,
    val fallbacks: List<String> = emptyList(),
    /** Known from the preview, so the list looks right before the download starts. */
    val title: String? = null,
    val thumbnail: String? = null,
)

data class Job(
    val id: String,
    val url: String,
    val quality: Quality,
    val createdAt: Long,
    val status: Status = Status.QUEUED,
    val title: String? = null,
    /** 0..1, or null while the size isn't known. */
    val progress: Float? = null,
    val step: String? = null,
    val speed: Double? = null,
    val etaSec: Long? = null,
    val index: Int? = null,
    val count: Int? = null,
    val files: List<SavedFile> = emptyList(),
    val error: String? = null,
    val detail: String? = null,
    val origin: Origin = Origin.LINK,
    /** The page the link was found on (browser downloads). */
    val referer: String? = null,
    val pageTitle: String? = null,
    val userAgent: String? = null,
    /** Video streams the browser saw the page play, tried when the page itself can't be read. */
    val fallbacks: List<String> = emptyList(),
    val thumbnail: String? = null,
    val doneBytes: Long? = null,
    val totalBytes: Long? = null,
    /** Paused part-way: the pieces already downloaded are kept and used when it goes on. */
    val resume: Boolean = false,
)

/** What the app knows about a link before downloading it (title, picture, sizes). */
sealed interface InfoState {
    data object Loading : InfoState
    data class Ready(val preview: Preview) : InfoState
    data class Failed(val message: String) : InfoState
}

/** The download sheet: which link, and what the browser already knows about the page. */
data class SheetRequest(
    val url: String,
    val origin: Origin = Origin.LINK,
    val pageTitle: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    /** Video streams the page played (browser only). */
    val found: List<String> = emptyList(),
    /** A lookup the browser already started for this page. */
    val info: StateFlow<InfoState?>? = null,
    /** The page's own picture (og:image), shown until the lookup is done. */
    val pageImage: String? = null,
)

sealed interface EngineState {
    data object Preparing : EngineState
    data object Updating : EngineState
    data class Ready(val version: String?, val checkedAt: Long, val updateFailed: Boolean = false) : EngineState
    data class Broken(val message: String) : EngineState
}

/** App-wide state: the download list (kept on disk) and the settings. */
object Store {
    const val TAB_HOME = 0
    const val TAB_PROGRESS = 1
    const val TAB_FILES = 2
    const val TAB_SETTINGS = 3

    private lateinit var prefs: SharedPreferences
    private lateinit var historyFile: File
    private val lock = Any()

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    val engine = MutableStateFlow<EngineState>(EngineState.Preparing)

    /** A short message for the person (shown at the bottom of the screen). */
    val notice = MutableStateFlow<String?>(null)

    /** Bottom tab: [TAB_HOME], [TAB_PROGRESS], [TAB_FILES], [TAB_SETTINGS]. */
    val tab = MutableStateFlow(TAB_HOME)

    /** A newer version of the app itself. */
    val update = MutableStateFlow<UpdateState>(UpdateState.None)

    /** The download sheet, when open. */
    val sheet = MutableStateFlow<SheetRequest?>(null)

    /** A video link the person copied in another app (offered on the home page). */
    val clipLink = MutableStateFlow<String?>(null)

    val shortcuts = MutableStateFlow<List<Shortcut>>(emptyList())

    /** Downloads to drop from the list once they have stopped (the ✕ button on a running download). */
    val removeAfterStop: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("videodl", Context.MODE_PRIVATE)
        historyFile = File(ctx.filesDir, "history.json")
        _jobs.value = load()
        shortcuts.value = loadShortcuts()
        // YouTube left the home page (version 1.1.9); someone who kept the old list loses only that one, once.
        if (!prefs.getBoolean("droppedYouTube", false)) {
            prefs.edit().putBoolean("droppedYouTube", true).apply()
            val list = shortcuts.value
            if (list.any { it.url == "https://m.youtube.com" }) setShortcuts(list.filterNot { it.url == "https://m.youtube.com" })
        }
    }

    var quality: Quality
        get() = Quality.of(prefs.getString("quality", null))
        set(v) = prefs.edit().putString("quality", v.name).apply()

    var engineVersion: String?
        get() = prefs.getString("engineVersion", null)
        set(v) = prefs.edit().putString("engineVersion", v).apply()

    var engineCheckedAt: Long
        get() = prefs.getLong("engineCheckedAt", 0L)
        set(v) = prefs.edit().putLong("engineCheckedAt", v).apply()

    var askedNotifications: Boolean
        get() = prefs.getBoolean("askedNotifications", false)
        set(v) = prefs.edit().putBoolean("askedNotifications", v).apply()

    var updateCheckedAt: Long
        get() = prefs.getLong("updateCheckedAt", 0L)
        set(v) = prefs.edit().putLong("updateCheckedAt", v).apply()

    var adBlock: Boolean
        get() = prefs.getBoolean("adBlock", true)
        set(v) = prefs.edit().putBoolean("adBlock", v).apply()

    var searchEngine: SearchEngine
        get() = SearchEngine.of(prefs.getString("searchEngine", null))
        set(v) = prefs.edit().putString("searchEngine", v.name).apply()

    /** How many downloads run at the same time. */
    var maxParallel: Int
        get() = prefs.getInt("maxParallel", 3).coerceIn(1, 5)
        set(v) = prefs.edit().putInt("maxParallel", v.coerceIn(1, 5)).apply()

    /** When the clipboard content last looked at was copied (so the same link isn't offered twice). */
    var clipSeenAt: Long
        get() = prefs.getLong("clipSeenAt", 0L)
        set(v) = prefs.edit().putLong("clipSeenAt", v).apply()

    /** Browser tabs open when the app was last closed: their addresses ("" = home page) and the one in front. */
    var savedTabs: List<String>
        get() = try {
            val a = JSONArray(prefs.getString("tabs", "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
        set(v) = prefs.edit().putString("tabs", JSONArray(v).toString()).apply()

    var savedTabIndex: Int
        get() = prefs.getInt("tabIndex", 0)
        set(v) = prefs.edit().putInt("tabIndex", v).apply()

    private fun loadShortcuts(): List<Shortcut> {
        val raw = prefs.getString("shortcuts", null) ?: return Web.defaultShortcuts
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map { Shortcut(it.getString("name"), it.getString("url")) }
        } catch (_: Exception) {
            Web.defaultShortcuts
        }
    }

    fun setShortcuts(list: List<Shortcut>) {
        shortcuts.value = list
        prefs.edit().putString("shortcuts", JSONArray().apply {
            list.forEach { s -> put(JSONObject().apply { put("name", s.name); put("url", s.url) }) }
        }.toString()).apply()
    }

    fun add(n: NewJob): Job {
        val job = Job(
            id = UUID.randomUUID().toString(), url = n.url, quality = n.quality, createdAt = System.currentTimeMillis(),
            origin = n.origin, referer = n.referer, pageTitle = n.pageTitle, userAgent = n.userAgent, fallbacks = n.fallbacks,
            title = n.title ?: n.pageTitle, thumbnail = n.thumbnail,
        )
        _jobs.update { listOf(job) + it }
        save()
        return job
    }

    fun get(id: String): Job? = _jobs.value.firstOrNull { it.id == id }

    fun update(id: String, persist: Boolean = false, f: (Job) -> Job) {
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }
        if (persist) save()
    }

    /** Starts over from the beginning. */
    fun retry(id: String) {
        update(id, persist = true) {
            it.copy(
                status = Status.QUEUED, progress = null, step = null, speed = null, etaSec = null, error = null, detail = null,
                index = null, count = null, doneBytes = null, totalBytes = null, resume = false,
            )
        }
    }

    /** Goes on from where it was paused. */
    fun resume(id: String) {
        update(id, persist = true) { it.copy(status = Status.QUEUED, step = null, speed = null, etaSec = null, error = null) }
    }

    fun remove(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id } }
        save()
    }

    /** Takes one file off the list (deleted, or gone from the phone); the download goes when it has none left. */
    fun removeFile(jobId: String, uri: String) {
        _jobs.update { list ->
            list.mapNotNull { j ->
                if (j.id != jobId) j else {
                    val left = j.files.filterNot { it.uri == uri }
                    if (left.isEmpty()) null else j.copy(files = left)
                }
            }
        }
        save()
    }

    fun renameFile(jobId: String, uri: String, name: String) {
        update(jobId, persist = true) { j ->
            j.copy(
                files = j.files.map { if (it.uri == uri) it.copy(name = name) else it },
                title = if (j.files.size == 1) name.substringBeforeLast('.') else j.title,
            )
        }
    }

    /** Oldest waiting job first. */
    fun nextQueued(): Job? = _jobs.value.lastOrNull { it.status == Status.QUEUED }

    fun hasWork(): Boolean = _jobs.value.any { it.status == Status.QUEUED || it.status.active }

    fun save() = synchronized(lock) {
        try {
            val arr = JSONArray()
            _jobs.value.take(500).forEach { arr.put(it.toJson()) }
            val tmp = File(historyFile.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(historyFile)
        } catch (_: Exception) {
        }
    }

    private fun load(): List<Job> = try {
        if (!historyFile.exists()) emptyList() else {
            val arr = JSONArray(historyFile.readText())
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toJob() }.map {
                // The app was closed in the middle of a download: it waits for the person to go on.
                if (it.status.active || it.status == Status.QUEUED) {
                    it.copy(status = Status.PAUSED, step = "INTERRUPTED", resume = it.resume || it.status.active, speed = null, etaSec = null)
                } else it
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun Job.toJson() = JSONObject().apply {
        put("id", id); put("url", url); put("quality", quality.name); put("createdAt", createdAt)
        put("status", status.name); title?.let { put("title", it) }; error?.let { put("error", it) }; detail?.let { put("detail", it) }
        count?.let { put("count", it) }
        put("origin", origin.name); referer?.let { put("referer", it) }; pageTitle?.let { put("pageTitle", it) }
        userAgent?.let { put("userAgent", it) }
        thumbnail?.let { put("thumbnail", it) }
        doneBytes?.let { put("doneBytes", it) }
        totalBytes?.let { put("totalBytes", it) }
        progress?.let { put("progress", it.toDouble()) }
        if (resume) put("resume", true)
        if (fallbacks.isNotEmpty()) put("fallbacks", JSONArray(fallbacks))
        put("files", JSONArray().apply {
            files.forEach { f -> put(JSONObject().apply { put("uri", f.uri); put("name", f.name); put("mime", f.mime); put("size", f.size) }) }
        })
    }

    private fun JSONObject.toJob(): Job? = try {
        val fa = optJSONArray("files") ?: JSONArray()
        Job(
            id = getString("id"), url = getString("url"), quality = Quality.of(optString("quality")),
            createdAt = optLong("createdAt"), status = Status.valueOf(getString("status")),
            title = optString("title").ifEmpty { null }, error = optString("error").ifEmpty { null },
            detail = optString("detail").ifEmpty { null }, count = if (has("count")) optInt("count") else null,
            files = (0 until fa.length()).map { i ->
                val o = fa.getJSONObject(i)
                SavedFile(o.getString("uri"), o.getString("name"), o.getString("mime"), o.optLong("size"))
            },
            origin = runCatching { Origin.valueOf(optString("origin")) }.getOrDefault(Origin.LINK),
            referer = optString("referer").ifEmpty { null },
            pageTitle = optString("pageTitle").ifEmpty { null },
            userAgent = optString("userAgent").ifEmpty { null },
            fallbacks = optJSONArray("fallbacks")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            thumbnail = optString("thumbnail").ifEmpty { null },
            doneBytes = if (has("doneBytes")) optLong("doneBytes") else null,
            totalBytes = if (has("totalBytes")) optLong("totalBytes") else null,
            progress = if (has("progress")) optDouble("progress").toFloat() else null,
            resume = optBoolean("resume", false),
        )
    } catch (_: Exception) {
        null
    }
}
