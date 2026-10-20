package io.github.apostolichiro.videodl

import android.content.Context
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Quality

/** Adding downloads. The same video is never downloaded twice (every way in goes through [duplicateOf]). */
object Downloads {
    /**
     * A download of the same video (and the same kind: video, or sound only) that is finished with its
     * file still on the phone, or still going. Pages and the streams the browser saw them play count as the same.
     */
    fun duplicateOf(ctx: Context, url: String, quality: Quality, origin: Origin = Origin.LINK, page: String? = null): Job? {
        val key = Links.normalize(url)
        val pageKey = page?.let { Links.normalize(it) }
        return Store.jobs.value.firstOrNull { j ->
            if (j.quality.audioOnly != quality.audioOnly) return@firstOrNull false
            if (j.status == Status.FAILED || j.status == Status.CANCELLED) return@firstOrNull false
            val jobKey = Links.normalize(j.url)
            val same = jobKey == key ||
                // The page again, after one of its streams was downloaded...
                (origin != Origin.STREAM && j.origin == Origin.STREAM && j.referer?.let { Links.normalize(it) } == key) ||
                // ...or a stream of a page that was downloaded as a whole.
                (origin == Origin.STREAM && pageKey != null && j.origin != Origin.STREAM && j.origin != Origin.SITE && jobKey == pageKey)
            same && (j.status != Status.DONE || (j.files.isNotEmpty() && Saver.exists(ctx, j.files[0].uri)))
        }
    }

    fun duplicateOf(ctx: Context, n: NewJob): Job? = duplicateOf(ctx, n.url, n.quality, n.origin, n.referer)

    /** Finished and still on the phone (as opposed to still downloading). */
    fun alreadyHave(ctx: Context, url: String, quality: Quality): Job? =
        duplicateOf(ctx, url, quality)?.takeIf { it.status == Status.DONE }

    /** What to tell the person instead of downloading [dup] again. */
    fun duplicateNotice(dup: Job): String =
        if (dup.status == Status.DONE) "이미 받은 영상이에요: ${Texts.title(dup)}" else "이미 받고 있어요: ${Texts.title(dup)}"

    fun start(ctx: Context, n: NewJob): Job {
        val job = Store.add(n)
        DownloadService.start(ctx)
        return job
    }
}
