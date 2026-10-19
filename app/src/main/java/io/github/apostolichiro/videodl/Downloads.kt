package io.github.apostolichiro.videodl

import android.content.Context
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Quality

/** Adding downloads, with the "already downloaded" check shared by every way in (link box, share, browser). */
object Downloads {
    /** A finished download of the same video (and the same kind: video, or sound only) whose file is still there. */
    fun alreadyHave(ctx: Context, url: String, quality: Quality): Job? {
        val key = Links.normalize(url)
        return Store.jobs.value.firstOrNull {
            it.status == Status.DONE && it.files.isNotEmpty() && it.quality.audioOnly == quality.audioOnly &&
                Links.normalize(it.url) == key && Saver.exists(ctx, it.files[0].uri)
        }
    }

    fun start(ctx: Context, n: NewJob): Job {
        val job = Store.add(n)
        DownloadService.start(ctx)
        return job
    }
}
