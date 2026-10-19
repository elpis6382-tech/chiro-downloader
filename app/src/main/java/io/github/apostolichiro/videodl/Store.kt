package io.github.apostolichiro.videodl

import android.content.Context
import android.content.SharedPreferences
import io.github.apostolichiro.videodl.engine.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Status { QUEUED, PREPARING, DOWNLOADING, PROCESSING, SAVING, DONE, FAILED, CANCELLED;
    val active get() = this == PREPARING || this == DOWNLOADING || this == PROCESSING || this == SAVING
    val finished get() = this == DONE || this == FAILED || this == CANCELLED
}

data class SavedFile(val uri: String, val name: String, val mime: String, val size: Long)

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
)

sealed interface EngineState {
    data object Preparing : EngineState
    data object Updating : EngineState
    data class Ready(val version: String?, val checkedAt: Long, val updateFailed: Boolean = false) : EngineState
    data class Broken(val message: String) : EngineState
}

/** App-wide state: the download list (kept on disk) and a few settings. */
object Store {
    private lateinit var prefs: SharedPreferences
    private lateinit var historyFile: File
    private val lock = Any()

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    val engine = MutableStateFlow<EngineState>(EngineState.Preparing)

    /** A shared link that needs the person's attention (e.g. no link found in it). */
    val notice = MutableStateFlow<String?>(null)

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("videodl", Context.MODE_PRIVATE)
        historyFile = File(ctx.filesDir, "history.json")
        _jobs.value = load()
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

    fun add(url: String, quality: Quality): Job {
        val job = Job(id = UUID.randomUUID().toString(), url = url, quality = quality, createdAt = System.currentTimeMillis())
        _jobs.update { listOf(job) + it }
        save()
        return job
    }

    fun get(id: String): Job? = _jobs.value.firstOrNull { it.id == id }

    fun update(id: String, persist: Boolean = false, f: (Job) -> Job) {
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }
        if (persist) save()
    }

    fun retry(id: String) {
        update(id, persist = true) {
            it.copy(status = Status.QUEUED, progress = null, step = null, speed = null, etaSec = null, error = null, detail = null, index = null, count = null)
        }
    }

    fun remove(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id } }
        save()
    }

    fun clearFinished() {
        _jobs.update { list -> list.filterNot { it.status == Status.DONE } }
        save()
    }

    /** Oldest waiting job first. */
    fun nextQueued(): Job? = _jobs.value.lastOrNull { it.status == Status.QUEUED }

    fun hasWork(): Boolean = _jobs.value.any { it.status == Status.QUEUED || it.status.active }

    fun save() = synchronized(lock) {
        try {
            val arr = JSONArray()
            _jobs.value.take(300).forEach { arr.put(it.toJson()) }
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
                // The app was closed in the middle of a download.
                if (it.status.active || it.status == Status.QUEUED) it.copy(status = Status.FAILED, error = "앱이 꺼져서 멈췄어요. [다시]를 누르면 처음부터 받아요.", progress = null)
                else it
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun Job.toJson() = JSONObject().apply {
        put("id", id); put("url", url); put("quality", quality.name); put("createdAt", createdAt)
        put("status", status.name); title?.let { put("title", it) }; error?.let { put("error", it) }; detail?.let { put("detail", it) }
        count?.let { put("count", it) }
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
        )
    } catch (_: Exception) {
        null
    }
}
