package io.github.apostolichiro.videodl

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.apostolichiro.videodl.engine.Engine
import io.github.apostolichiro.videodl.engine.Event
import io.github.apostolichiro.videodl.engine.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Runs the real engine (Python + yt-dlp + ffmpeg + QuickJS) on the emulator against a small
 * web server on the CI machine (tools/make_test_media.sh).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EngineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val host = InstrumentationRegistry.getArguments().getString("mediaHost") ?: "http://10.0.2.2:8000"

    private fun log(msg: String) = Log.i("EngineTest", msg)

    @Test
    fun a_quickJsIsInstalledAndRuns() {
        val qjs = Engine.qjs(ctx)
        assertNotNull("libqjs.so is missing from the APK", qjs)
        val p = ProcessBuilder(qjs!!.absolutePath, "--help").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        log("qjs: ${out.lineSequence().first()}")
        assertTrue(out, out.startsWith("QuickJS-ng version"))
    }

    @Test
    fun b_engineUpdatesAndSeesTheJsRuntime() {
        Engine.init(ctx)
        val updated = Engine.update(ctx, 0)
        log("update: changed=$updated version=${Store.engineVersion}")
        assertNotNull("yt-dlp update failed", Store.engineVersion)
        // -v prints the detected JS runtimes; a URL-less run just ends with "no URL" after that.
        val out = try {
            Engine.run(ctx, listOf("-v", "--simulate"), "$host/clip.mp4").err
        } catch (e: Exception) {
            e.message ?: ""
        }
        val line = out.lineSequence().firstOrNull { it.contains("JS runtimes") } ?: ""
        log("yt-dlp says: $line")
        log(out.lineSequence().filter { it.startsWith("[debug]") }.take(8).joinToString("\n"))
        assertTrue("yt-dlp did not find QuickJS: $out", line.contains("quickjs-ng"))
    }

    private fun download(url: String, q: Quality): Pair<Engine.Outcome, List<Event>> {
        val events = mutableListOf<Event>()
        val work = File(ctx.cacheDir, "test-${q.name}")
        val outcome = Engine.download(ctx, "test-${System.nanoTime()}", url, q, work) { events += it }
        log("download $url ($q): files=${outcome.files.map { it.name }} error=${outcome.error}")
        if (outcome.error != null) log(outcome.log?.takeLast(3000) ?: "")
        return outcome to events
    }

    private fun headOf(uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)!!.use { s -> ByteArray(12).also { s.read(it) } }

    @Test
    fun c_hlsStreamOnAWebPageBecomesARealMp4() {
        val (outcome, events) = download("$host/page.html", Quality.BEST)
        assertEquals(null, outcome.error)
        assertEquals(1, outcome.files.size)
        assertTrue("no progress reported", events.any { it is Event.Download })
        assertTrue("the stream was not fixed into MP4", events.any { it is Event.Post && it.step == "FixupM3u8" })

        val saved = Saver.save(ctx, outcome.files[0])
        log("saved as ${saved.name} (${saved.size} bytes) ${saved.uri}")
        assertTrue(saved.name, saved.name.startsWith("테스트 영상") && saved.name.endsWith(".mp4"))
        val uri = Uri.parse(saved.uri)
        assertEquals("ftyp", String(headOf(uri), 4, 4, Charsets.US_ASCII))
        val r = MediaMetadataRetriever()
        ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            r.setDataSource(pfd.fileDescriptor)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            log("duration ${ms}ms video=${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)} audio=${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)}")
            assertTrue("duration $ms", ms in 9_500..10_500)
            assertEquals("yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            assertEquals("yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
        }
        r.release()
        // Keep one copy under a fixed name so CI can check it with ffprobe.
        Saver.save(ctx, File(ctx.cacheDir, "ci-copy.mp4").also { out ->
            ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        }, "vdl-test-output.mp4")
    }

    @Test
    fun d_audioOnlyGivesM4a() {
        val (outcome, _) = download("$host/clip.mp4", Quality.AUDIO)
        assertEquals(null, outcome.error)
        val f = outcome.files.single()
        assertTrue(f.name, f.name.endsWith(".m4a"))
        val head = f.readBytes().copyOfRange(4, 8)
        assertEquals("ftyp", String(head, Charsets.US_ASCII))
    }

    @Test
    fun e_badLinkGivesAClearError() {
        val (outcome, _) = download("$host/nothing-here.html", Quality.BEST)
        assertTrue(outcome.files.isEmpty())
        assertNotNull(outcome.error)
    }

    /** Real sites from a data-center IP may be blocked, so this only reports what happened. */
    @Test
    fun f_realSitesForTheRecord() {
        for (url in listOf("https://www.youtube.com/watch?v=jNQXAC9IVRw", "https://vimeo.com/76979871")) {
            val (outcome, _) = download(url, Quality.P480)
            val sizes = outcome.files.map { "${it.name} ${it.length()}B" }
            Log.i("EngineTest", "REAL $url -> ${if (outcome.files.isNotEmpty()) "OK $sizes" else "FAIL ${outcome.error}"}")
            outcome.files.forEach { it.delete() }
        }
    }
}
