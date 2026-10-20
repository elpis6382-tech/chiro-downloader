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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val quick by produceState<QuickMeta.Meta?>(null, req.url) {
        value = withContext(Dispatchers.IO) { QuickMeta.fetch(req.url) }
    }

    var quality by remember { mutableStateOf(Store.quality) }
    val jobs by Store.jobs.collectAsStateWithLifecycle()
    val doneCount = jobs.count { it.status == Status.DONE }
    val already by produceState<Job?>(null, req.url, quality, doneCount) {
        value = withContext(Dispatchers.IO) { Downloads.alreadyHave(ctx, req.url, quality) }
    }

    fun close(after: () -> Unit = {}) {
        scope.launch { state.hide() }.invokeOnCompletion {
            Store.sheet.value = null
            after()
        }
    }

    // The choice here is for this download only; the default (also used for shared links) is in 설정.
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
            Header(info, req, quick)
            Spacer(Modifier.height(18.dp))

            val shown = Quality.entries.filter { preview == null || preview.isPlaylist || preview.shows(it) }
            // A hidden quality gives the same file as "최고 화질", so that one shows as chosen.
            val chosen = if (preview != null && !preview.isPlaylist && !preview.shows(quality)) Quality.BEST else quality
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)),
            ) {
                shown.forEachIndexed { i, q ->
                    if (i > 0) HorizontalDivider(color = colors.outlineVariant, thickness = 1.dp, modifier = Modifier.padding(start = 52.dp))
                    QualityRow(q, preview, selected = q == chosen) { quality = q }
                }
            }

            already?.let { a ->
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "이미 받은 영상이에요", style = MaterialTheme.typography.bodySmall, color = colors.tertiary,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { close { a.files.firstOrNull()?.let { f -> app.play(a, f) } } }) { Text("보기") }
                }
            }

            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(LocalAccents.current.fab)
                    .clickable {
                        start(
                            NewJob(
                                url = req.url, quality = chosen, origin = req.origin, referer = req.referer, pageTitle = req.pageTitle,
                                userAgent = req.userAgent, fallbacks = req.found, title = preview?.title, thumbnail = preview?.thumbnail,
                            ),
                        )
                    }
                    .semantics { contentDescription = "다운로드" },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Download, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            already != null -> "그래도 다운로드"
                            preview?.isPlaylist == true -> "${preview.playlistCount}개 모두 다운로드"
                            else -> "다운로드"
                        },
                        color = Color.White, style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            if (req.found.isNotEmpty()) {
                Text(
                    "이 페이지에서 재생된 영상", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
                )
                req.found.take(6).forEachIndexed { i, stream ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Movie, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (Sniff.kind(stream) == 2) "스트리밍 영상 ${i + 1}" else "영상 파일 ${i + 1}",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            )
                            Text(
                                Links.host(stream) + " · " + stream.substringBefore('?').substringAfterLast('/').take(40),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = {
                            start(
                                NewJob(
                                    url = stream, quality = chosen, origin = Origin.STREAM, referer = req.referer ?: req.url,
                                    pageTitle = req.pageTitle ?: preview?.title, userAgent = req.userAgent,
                                    title = req.pageTitle ?: preview?.title, thumbnail = preview?.thumbnail,
                                ),
                            )
                        }) { Text("받기") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(info: InfoState, req: SheetRequest, quick: QuickMeta.Meta?) {
    val colors = MaterialTheme.colorScheme
    val p = (info as? InfoState.Ready)?.preview
    // While yt-dlp is still looking, the page's own title and picture stand in.
    val title = p?.title ?: quick?.title ?: req.pageTitle ?: Links.host(req.url)
    val picture = p?.thumbnail ?: req.pageImage ?: quick?.image
    Row(verticalAlignment = Alignment.CenterVertically) {
        ThumbBox(netImage(picture), 128.dp, 72.dp, AppIcons.Movie, p?.duration?.let { durationText(it.toLong()) })
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).heightIn(min = 72.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (info) {
                InfoState.Loading -> Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    if (req.found.isNotEmpty()) "페이지 주소로는 영상을 못 찾았어요. 아래 재생된 영상에서 골라 받을 수 있어요."
                    else "바로 받을 영상을 못 찾았어요. 영상을 한 번 재생한 뒤 다시 눌러 보거나, 그래도 [다운로드]를 눌러 보세요.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun QualityRow(q: Quality, preview: io.github.apostolichiro.videodl.engine.Preview?, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val c = preview?.choices?.get(q)
    val label = when (q) {
        Quality.BEST -> "최고 화질"
        Quality.AUDIO -> "소리만 (M4A)"
        else -> q.label
    }
    val detail = listOfNotNull(
        if (q == Quality.BEST) Estimate.resLabel(c?.res) else null,
        c?.bytes?.let { "약 " + Fmt.size(it) },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp)
            .semantics(mergeDescendants = true) { contentDescription = q.label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .border(2.dp, if (selected) colors.primary else colors.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(colors.primary))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
