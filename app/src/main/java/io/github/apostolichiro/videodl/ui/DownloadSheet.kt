package io.github.apostolichiro.videodl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.webkit.CookieManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.apostolichiro.videodl.Downloads
import io.github.apostolichiro.videodl.InfoState
import io.github.apostolichiro.videodl.Infos
import io.github.apostolichiro.videodl.Job
import io.github.apostolichiro.videodl.NewJob
import io.github.apostolichiro.videodl.Origin
import io.github.apostolichiro.videodl.QuickMeta
import io.github.apostolichiro.videodl.SheetRequest
import io.github.apostolichiro.videodl.Status
import io.github.apostolichiro.videodl.Store
import io.github.apostolichiro.videodl.engine.Estimate
import io.github.apostolichiro.videodl.engine.Fmt
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Quality
import io.github.apostolichiro.videodl.engine.Sniff
import io.github.apostolichiro.videodl.engine.Streams
import io.github.apostolichiro.videodl.engine.Web
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Bottom sheet: what the link holds, which quality, and [다운로드]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(req: SheetRequest, app: AppActions) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // The browser may already be looking the page up; otherwise the sheet does it.
    val flow = remember(req) { req.info ?: MutableStateFlow<InfoState?>(null) }
    val shared by flow.collectAsStateWithLifecycle()
    var own by remember(req) { mutableStateOf<InfoState?>(null) }
    LaunchedEffect(req) {
        if (req.info == null) {
            own = InfoState.Loading
            own = Infos.fetch(ctx, req.url, req.referer, req.userAgent)
        }
    }
    val info = if (req.info != null) shared ?: InfoState.Loading else own ?: InfoState.Loading
    val preview = (info as? InfoState.Ready)?.preview
    // An ordinary page whose video the browser already saw playing: that video can be downloaded right away,
    // no need to wait for yt-dlp to read the page (sites it knows, like Instagram, still wait: their
    // played streams are often picture-only pieces).
    val streamNow = preview == null && req.found.isNotEmpty() && !Web.isVideoPage(req.url)
    // Of the videos the page played, the one that is the page's own (the longest; not an ad or a preview).
    suspend fun choose(): Streams.Pick? = withContext(Dispatchers.IO) {
        Streams.pick(req.found) { u ->
            val cookie = try { CookieManager.getInstance().getCookie(u) } catch (_: Exception) { null }
            Streams.fetchText(u, req.url, req.userAgent, cookie)
        }
    }
    val pick by produceState<Streams.Pick?>(null, req.found) {
        if (req.found.isNotEmpty()) value = choose()
    }
    fun order(p: Streams.Pick?) = p?.let { listOf(it.url) + req.found.filterNot { u -> u == it.url } } ?: req.found
    val streamOrder = order(pick)
    val quick by produceState<QuickMeta.Meta?>(null, req.url) {
        value = withContext(Dispatchers.IO) { QuickMeta.fetch(req.url) }
    }

    // Always the best there is.
    val quality = Quality.BEST
    val jobs by Store.jobs.collectAsStateWithLifecycle()
    val jobsKey = jobs.map { it.id to it.status }.hashCode()
    // The same video is never downloaded twice: finished (and still on the phone), or still going.
    val dup by produceState<Job?>(null, req.url, quality, jobsKey) {
        value = withContext(Dispatchers.IO) { Downloads.duplicateOf(ctx, req.url, quality, req.origin, req.referer) }
    }
    val done = dup?.status == Status.DONE

    fun close(after: () -> Unit = {}) {
        scope.launch { state.hide() }.invokeOnCompletion {
            Store.sheet.value = null
            after()
        }
    }

    fun start(n: NewJob) {
        close { app.download(n) }
    }

    ModalBottomSheet(
        onDismissRequest = { Store.sheet.value = null },
        sheetState = state,
        containerColor = colors.background,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(colors.outline))
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Header(info, req, quick, streamNow, pick?.seconds)
            Spacer(Modifier.height(18.dp))

            // What "최고 화질" comes to for this video, once the lookup knows.
            val best = preview?.choices?.get(Quality.BEST)
            val detail = when {
                streamNow -> listOfNotNull(pick?.height?.let { "${it}p" }, pick?.seconds?.let { "길이 " + durationText(it.toLong()) }).joinToString(" · ")
                info is InfoState.Loading -> "확인 중…"
                best != null -> listOfNotNull(Estimate.resLabel(best.res), best.bytes?.let { "약 " + Fmt.size(it) }).joinToString(" · ")
                else -> ""
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("최고 화질로 받아요", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }

            dup?.let {
                Text(
                    if (done) "이미 받은 영상이에요" else "지금 받고 있어요",
                    style = MaterialTheme.typography.bodySmall, color = colors.tertiary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            val label = when {
                dup != null && done -> "받은 영상 보기"
                dup != null -> "받는 중 보기"
                preview?.isPlaylist == true -> "${preview.playlistCount}개 모두 다운로드"
                else -> "다운로드"
            }
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(LocalAccents.current.fab)
                    .clickable {
                        val d = dup
                        when {
                            d != null && done -> close { d.files.firstOrNull()?.let { f -> app.play(d, f) } }
                            d != null -> close { Store.tab.value = Store.TAB_PROGRESS }
                            streamNow -> scope.launch {
                                // Pressed before the right video was picked: pick it now (a moment at most).
                                val chosen = order(pick ?: withTimeoutOrNull(5_000) { choose() })
                                val name = quick?.title ?: req.pageTitle
                                start(
                                    NewJob(
                                        url = chosen.first(), quality = quality, origin = Origin.STREAM, referer = req.url,
                                        pageTitle = name, userAgent = req.userAgent, fallbacks = chosen.drop(1),
                                        title = name, thumbnail = req.pageImage ?: quick?.image,
                                    ),
                                )
                            }
                            else -> start(
                                NewJob(
                                    url = req.url, quality = quality, origin = req.origin, referer = req.referer, pageTitle = req.pageTitle,
                                    userAgent = req.userAgent, fallbacks = streamOrder, title = preview?.title, thumbnail = preview?.thumbnail,
                                ),
                            )
                        }
                    }
                    .semantics { contentDescription = if (dup == null) "다운로드" else label },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (dup != null) Icons.Rounded.PlayArrow else AppIcons.Download, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun Header(info: InfoState, req: SheetRequest, quick: QuickMeta.Meta?, streamNow: Boolean, streamSeconds: Double?) {
    val colors = MaterialTheme.colorScheme
    val p = (info as? InfoState.Ready)?.preview
    // While yt-dlp is still looking, the page's own title and picture stand in.
    val title = p?.title ?: quick?.title ?: req.pageTitle ?: Links.host(req.url)
    val picture = p?.thumbnail ?: req.pageImage ?: quick?.image
    Row(verticalAlignment = Alignment.CenterVertically) {
        ThumbBox(netImage(picture), 128.dp, 72.dp, AppIcons.Movie, (p?.duration ?: streamSeconds)?.let { durationText(it.toLong()) })
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).heightIn(min = 72.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (info) {
                InfoState.Loading -> if (streamNow) {
                    Text("페이지에서 재생된 영상을 바로 받을 수 있어요", style = MaterialTheme.typography.bodySmall, color = colors.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                } else Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("용량 확인 중 · 기다리지 않고 받아도 돼요", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                is InfoState.Ready -> {
                    val pv = info.preview
                    val meta = listOfNotNull(
                        pv.uploader,
                        pv.playlistCount?.let { "재생목록 · 영상 ${it}개" },
                        Links.host(req.url).takeIf { pv.uploader == null },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                is InfoState.Failed -> Text(
                    if (req.found.isNotEmpty()) "[다운로드]를 누르면 이 페이지가 재생한 영상으로 받아요."
                    else "바로 받을 영상을 못 찾았어요. 영상을 한 번 재생한 뒤 다시 눌러 보거나, 그래도 [다운로드]를 눌러 보세요.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
