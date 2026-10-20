package io.github.apostolichiro.videodl.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.apostolichiro.videodl.InfoState
import io.github.apostolichiro.videodl.Origin
import io.github.apostolichiro.videodl.SheetRequest
import io.github.apostolichiro.videodl.Status
import io.github.apostolichiro.videodl.Store
import io.github.apostolichiro.videodl.Tabs
import io.github.apostolichiro.videodl.Texts
import io.github.apostolichiro.videodl.WebTab
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Shortcut
import io.github.apostolichiro.videodl.engine.Web
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The 홈 tab: the browser. A new tab shows the home page (search, sites, recent downloads). */
@Composable
fun BrowserScreen(tabs: Tabs, app: AppActions) {
    val tab by tabs.current.collectAsStateWithLifecycle()
    val url by tab.url.collectAsStateWithLifecycle()
    var switcher by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = switcher || url.isNotEmpty()) {
        if (switcher) switcher = false else tab.back()
    }

    if (switcher) {
        TabSwitcher(tabs) { switcher = false }
        return
    }
    Column(Modifier.fillMaxSize()) {
        AddressBar(tab, tabs, app) {
            tabs.capture(tab)
            switcher = true
        }
        val progress by tab.progress.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (url.isNotEmpty() && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f }, modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent, drawStopIndicator = {},
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (url.isEmpty()) {
                HomePage(tabs, app)
            } else {
                key(tab.id) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                val wv = tab.webView()
                                (wv.parent as? ViewGroup)?.removeView(wv)
                                addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                DownloadButton(tab, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 76.dp))
            }
        }
    }
}

@Composable
private fun AddressBar(tab: WebTab, tabs: Tabs, app: AppActions, onTabs: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val url by tab.url.collectAsStateWithLifecycle()
    val progress by tab.progress.collectAsStateWithLifecycle()
    val canGoForward by tab.canGoForward.collectAsStateWithLifecycle()
    val count by tabs.list.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember(tab.id) { mutableStateOf(false) }
    var field by remember(tab.id) { mutableStateOf(TextFieldValue("")) }
    var menu by remember { mutableStateOf(false) }
    var addShortcut by remember { mutableStateOf(false) }
    var selectAll by remember { mutableStateOf(0) }
    val home = url.isEmpty()

    // The tap that focuses the box also drops the cursor where the finger was; select everything again
    // right after, so typing replaces the old address (unless typing already started).
    LaunchedEffect(selectAll) {
        if (selectAll == 0) return@LaunchedEffect
        val before = field.text
        delay(150)
        if (editing && field.text == before) field = field.copy(selection = TextRange(0, before.length))
    }
    val loading = !home && progress in 1..99

    // A new page started loading (a link on the page, or a site from the home page): done typing.
    LaunchedEffect(url) {
        if (editing && !home) {
            keyboard?.hide()
            editing = false
        }
    }

    fun go() {
        val text = field.text
        keyboard?.hide()
        editing = false
        if (text.isNotBlank()) tabs.open(text, tab)
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(colors.surfaceVariant).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (home || editing) Icons.Rounded.Search else if (url.startsWith("https")) Icons.Rounded.Lock else AppIcons.Globe,
                contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val shown = if (home) "" else Web.pretty(url)
                BasicTextField(
                    value = if (editing) field else TextFieldValue(shown),
                    onValueChange = { v ->
                        if (!editing && v.text == shown) {
                            // The first tap on the short address: switch to the whole address, all selected,
                            // so typing replaces it (the tap only moved the cursor).
                            val full = if (home) "" else url
                            field = TextFieldValue(full, TextRange(0, full.length))
                            selectAll++
                        } else {
                            field = v
                        }
                        editing = true
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                        if (f.isFocused && !editing) {
                            // Show the whole address, all selected, so typing replaces it.
                            val full = if (home) "" else url
                            field = TextFieldValue(full, TextRange(0, full.length))
                            editing = true
                            selectAll++
                        } else if (!f.isFocused) {
                            editing = false
                        }
                    }.semantics { contentDescription = "주소창" },
                )
                if (!editing && home) {
                    Text("검색하거나 주소 입력", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1)
                }
            }
            when {
                editing && field.text.isNotEmpty() -> RoundIcon(Icons.Rounded.Close, "지우기", { field = TextFieldValue("") }, size = 36.dp, iconSize = 18.dp, tint = colors.onSurfaceVariant)
                loading -> RoundIcon(Icons.Rounded.Close, "멈추기", { tab.stop() }, size = 36.dp, iconSize = 18.dp, tint = colors.onSurfaceVariant)
                !home -> RoundIcon(Icons.Rounded.Refresh, "새로고침", { tab.reload() }, size = 36.dp, iconSize = 19.dp, tint = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(4.dp))
        // On web pages the bar below is hidden: downloads are one tap away here.
        if (!home) DownloadsChip()
        TabCount(count.size, onTabs)
        Box {
            RoundIcon(Icons.Rounded.MoreVert, "메뉴", { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("새 탭") }, onClick = { menu = false; tabs.newTab() })
                if (!home) {
                    if (canGoForward) DropdownMenuItem(text = { Text("앞으로") }, onClick = { menu = false; tab.forward() })
                    DropdownMenuItem(text = { Text("홈 화면에 추가") }, onClick = { menu = false; addShortcut = true })
                    DropdownMenuItem(text = { Text("링크 공유") }, onClick = { menu = false; app.shareText(url) })
                    DropdownMenuItem(text = { Text("다른 브라우저로 열기") }, onClick = { menu = false; app.openExternal(url) })
                    DropdownMenuItem(text = { Text("홈으로") }, onClick = { menu = false; tab.showHome() })
                    DropdownMenuItem(text = { Text("받는 중") }, onClick = { menu = false; Store.tab.value = Store.TAB_PROGRESS })
                    DropdownMenuItem(text = { Text("파일") }, onClick = { menu = false; Store.tab.value = Store.TAB_FILES })
                }
                var ads by remember { mutableStateOf(Store.adBlock) }
                DropdownMenuItem(
                    text = { Text(if (ads) "광고 차단 끄기" else "광고 차단 켜기") },
                    leadingIcon = { Icon(AppIcons.Shield, null, tint = if (ads) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        ads = !ads
                        Store.adBlock = ads
                        menu = false
                        Store.notice.value = if (ads) "광고와 팝업을 막아요." else "광고 차단을 껐어요."
                        if (!home) tab.reload()
                    },
                )
                DropdownMenuItem(text = { Text("설정") }, onClick = { menu = false; Store.tab.value = Store.TAB_SETTINGS })
            }
        }
    }

    if (addShortcut) {
        val title by tab.title.collectAsStateWithLifecycle()
        ShortcutDialog(initialName = title.take(12), initialUrl = url, onDismiss = { addShortcut = false }) { s ->
            Store.setShortcuts(Store.shortcuts.value + s)
            Store.notice.value = "홈 화면에 추가했어요."
            addShortcut = false
        }
    }
}

/** Next to the address: the downloads (with how many are going), for when the bar below is hidden. */
@Composable
private fun DownloadsChip() {
    val colors = MaterialTheme.colorScheme
    val jobs by Store.jobs.collectAsStateWithLifecycle()
    val running = jobs.count { it.status.active || it.status == Status.QUEUED }
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable { Store.tab.value = if (running > 0) Store.TAB_PROGRESS else Store.TAB_FILES }
            .semantics { contentDescription = if (running > 0) "받는 중 ${running}개" else "파일" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (running > 0) AppIcons.Downloading else AppIcons.Folder, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        if (running > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = (-3).dp, y = 3.dp).size(16.dp).clip(CircleShape).background(LocalAccents.current.badge),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (running > 9) "9+" else "$running", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The big round button over web pages: lights up when the page has a video. */
@Composable
private fun DownloadButton(tab: WebTab, modifier: Modifier) {
    val accents = LocalAccents.current
    val found by tab.found.collectAsStateWithLifecycle()
    val info by tab.info.collectAsStateWithLifecycle()
    val title by tab.title.collectAsStateWithLifecycle()
    val url by tab.url.collectAsStateWithLifecycle()
    val post by tab.post.collectAsStateWithLifecycle()
    val ready = info is InfoState.Ready
    // Something to download: a video found by its address, one the page played, or an Instagram post's photos.
    val count = when {
        post?.hasPhotos == true -> post!!.items.size
        ready || found.isNotEmpty() -> 1
        else -> 0
    }
    val active = count > 0
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (active) {
            bounce.snapTo(0.82f)
            bounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    Box(modifier.scale(bounce.value)) {
        Box(
            Modifier.size(60.dp).shadow(if (active) 10.dp else 4.dp, CircleShape).clip(CircleShape)
                .then(if (active) Modifier.background(accents.fab) else Modifier.background(accents.fabIdle))
                .clickable {
                    tab.lookUpNow()
                    openSheet(
                        SheetRequest(
                            url = url, origin = Origin.BROWSER, pageTitle = title.ifBlank { null }, referer = url,
                            userAgent = tab.view?.settings?.userAgentString, found = found, info = tab.info,
                            pageImage = tab.pageImage.value,
                        ),
                    )
                }
                .semantics { contentDescription = "이 페이지 영상 받기" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Download, contentDescription = null, tint = accents.onFab, modifier = Modifier.size(28.dp))
        }
        if (info is InfoState.Loading && post?.hasPhotos != true) {
            CircularProgressIndicator(Modifier.size(60.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
        }
        if (active) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(22.dp).clip(CircleShape)
                    .background(accents.badge).border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (count > 9) "9+" else "$count", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The page a new tab shows. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HomePage(tabs: Tabs, app: AppActions) {
    val colors = MaterialTheme.colorScheme
    val shortcuts by Store.shortcuts.collectAsStateWithLifecycle()
    val clip by Store.clipLink.collectAsStateWithLifecycle()
    val jobs by Store.jobs.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Shortcut?>(null) }
    val recent = remember(jobs) {
        jobs.filter { it.status == Status.DONE }.flatMap { j -> j.files.filter { it.isVideo || it.isAudio }.map { j to it } }.take(12)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        UpdateBanner(app)
        clip?.let { link -> ClipCard(link, onDismiss = { Store.clipLink.value = null }) {
            Store.clipLink.value = null
            openSheet(SheetRequest(link))
        } }
        if (clip == null) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).clip(RoundedCornerShape(14.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                    .clickable { app.pasteLink() }.padding(horizontal = 14.dp, vertical = 11.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Paste, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("복사한 링크로 받기", style = MaterialTheme.typography.labelLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
                Text("붙여넣기", style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
        }

        Text(
            "사이트", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp),
        )
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val cell = maxWidth / 4
            FlowRow(Modifier.fillMaxWidth()) {
                shortcuts.forEach { s ->
                    Column(
                        Modifier.width(cell).clip(RoundedCornerShape(14.dp))
                            .combinedClickable(onClick = { tabs.open(s.url) }, onLongClick = { removing = s })
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SiteIcon(s.name, s.url)
                        Text(
                            s.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 7.dp, start = 4.dp, end = 4.dp),
                        )
                    }
                }
                Column(
                    Modifier.width(cell).clip(RoundedCornerShape(14.dp)).clickable { adding = true }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(52.dp).clip(CircleShape).border(1.5.dp, colors.outline, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, tint = colors.onSurfaceVariant)
                    }
                    Text("추가", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }

        if (recent.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("최근 받은 영상", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { Store.tab.value = Store.TAB_FILES }) { Text("전체 보기") }
            }
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent, key = { it.second.uri }) { (job, file) ->
                    Column(Modifier.width(150.dp).clip(RoundedCornerShape(10.dp)).clickable { app.play(job, file) }) {
                        val d = fileDuration(file)
                        ThumbBox(fileThumb(file) ?: netImage(job.thumbnail), 150.dp, 84.dp, if (file.isAudio) AppIcons.Music else AppIcons.Movie, d?.let { durationText(it) })
                        Text(
                            Texts.title(job), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp, end = 4.dp),
                        )
                    }
                }
            }
        } else if (jobs.isEmpty()) {
            HowTo()
        }
    }

    if (adding) {
        ShortcutDialog(initialName = "", initialUrl = "", onDismiss = { adding = false }) { s ->
            Store.setShortcuts(Store.shortcuts.value + s)
            adding = false
        }
    }
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(s.name) },
            text = { Text("홈 화면에서 이 사이트를 뺄까요?") },
            confirmButton = {
                TextButton(onClick = {
                    Store.setShortcuts(Store.shortcuts.value - s)
                    removing = null
                }) { Text("빼기") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun ClipCard(link: String, onDismiss: () -> Unit, onDownload: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(colors.primaryContainer).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Link, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("복사한 링크", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer.copy(alpha = 0.75f))
            Text(Web.pretty(link), style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(LocalAccents.current.fab)
                .clickable(onClick = onDownload).padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics { contentDescription = "복사한 링크 받기" },
        ) { Text("받기", color = Color.White, style = MaterialTheme.typography.labelLarge) }
        RoundIcon(Icons.Rounded.Close, "닫기", onDismiss, size = 36.dp, iconSize = 18.dp, tint = colors.onPrimaryContainer)
    }
}

@Composable
private fun HowTo() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("이렇게 받아요", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
        listOf(
            "사이트에서 영상을 열면 오른쪽 아래 파란 버튼이 켜져요. 누르고 화질을 고르면 끝.",
            "인스타·틱톡 같은 앱에서는 [공유] → 영상 다운로더를 고르면 앱을 안 열고 받아요.",
            "링크를 복사해 두면 여기 홈에 [받기]가 떠요.",
        ).forEach { line ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(colors.primary))
                Spacer(Modifier.width(10.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            }
        }
    }
}

@Composable
private fun ShortcutDialog(initialName: String, initialUrl: String, onDismiss: () -> Unit, onSave: (Shortcut) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var address by remember { mutableStateOf(initialUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("홈 화면에 사이트 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("이름") }, singleLine = true)
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, label = { Text("주소") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = address.isNotBlank(),
                onClick = {
                    val target = Web.target(address, Store.searchEngine) ?: return@TextButton
                    onSave(Shortcut(name.trim().ifEmpty { Links.host(target) }, target))
                },
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** All open tabs as cards. */
@Composable
private fun TabSwitcher(tabs: Tabs, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val list by tabs.list.collectAsStateWithLifecycle()
    val current by tabs.current.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(colors.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("탭 ${list.size}개", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { tabs.closeAll(); onDone() }) { Text("모두 닫기") }
            RoundIcon(Icons.Rounded.Add, "새 탭", { scope.launch { tabs.newTab(); onDone() } })
            TextButton(onClick = onDone) { Text("완료") }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list, key = { it.id }) { t -> TabCard(t, t === current, onOpen = { tabs.select(t); onDone() }, onClose = { tabs.close(t) }) }
        }
    }
}

@Composable
private fun TabCard(t: WebTab, selected: Boolean, onOpen: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val url by t.url.collectAsStateWithLifecycle()
    val title by t.title.collectAsStateWithLifecycle()
    val snap by t.snapshot.collectAsStateWithLifecycle()
    val home = url.isEmpty()
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(colors.background)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (home) "홈" else title.ifBlank { Web.pretty(url) },
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            RoundIcon(Icons.Rounded.Close, "탭 닫기", onClose, size = 36.dp, iconSize = 16.dp, tint = colors.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(0.78f).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            val s = snap
            when {
                !home && s != null -> Image(s.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                home -> Icon(Icons.Rounded.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(30.dp))
                else -> SiteIcon(Links.host(url), url, 44.dp)
            }
        }
    }
}
