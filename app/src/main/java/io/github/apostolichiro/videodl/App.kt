package io.github.apostolichiro.videodl

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.apostolichiro.videodl.engine.Engine
import io.github.apostolichiro.videodl.engine.Lookups

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifs.ensureChannels(this)
        // Unpack the engine and fetch the newest yt-dlp in the background, so the first download starts quickly.
        Thread({ prepare(this) }, "engine-prepare").start()
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L

        fun prepare(ctx: Context) {
            try {
                Store.engine.value = EngineState.Preparing
                Engine.init(ctx)
            } catch (e: Exception) {
                Log.e("VideoDl", "engine init failed", e)
                Store.engine.value = EngineState.Broken("다운로드 엔진을 준비하지 못했어요.")
                return
            }
            updateEngine(ctx, DAY)
            // Keep a yt-dlp loaded for looking links up.
            Lookups.warmUp(ctx)
            Updater.check(ctx, 3 * 60 * 60 * 1000L)
        }

        /** Updates yt-dlp if the last check is older than [maxAgeMs] (0 = always check). Never throws. */
        fun updateEngine(ctx: Context, maxAgeMs: Long): Boolean {
            Store.engine.value = EngineState.Updating
            var failed = false
            val changed = try {
                Engine.update(ctx, maxAgeMs)
            } catch (e: Exception) {
                Log.w("VideoDl", "yt-dlp update failed", e)
                failed = true
                false
            }
            Store.engine.value = EngineState.Ready(Store.engineVersion, Store.engineCheckedAt, failed)
            // The running lookup helper still has the old yt-dlp loaded.
            if (changed) {
                Lookups.stop()
                Lookups.warmUp(ctx)
            }
            return changed
        }
    }
}
