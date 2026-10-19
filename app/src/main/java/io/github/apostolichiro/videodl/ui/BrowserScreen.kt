package io.github.apostolichiro.videodl.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.apostolichiro.videodl.Browser

class BrowserActions(
    val download: () -> Unit,
    val clearLogins: () -> Unit,
    val leave: () -> Unit,
)

private val quickSites = listOf(
    "유튜브" to "https://m.youtube.com",
    "인스타그램" to "https://www.instagram.com",
    "틱톡" to "https://www.tiktok.com",
    "X" to "https://x.com",
    "페이스북" to "https://m.facebook.com",
    "네이버 TV" to "https://tv.naver.com",
    "치지직" to "https://chzzk.naver.com",
    "구글" to "https://www.google.com",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(browser: Browser, actions: BrowserActions) {
    val url by browser.url.collectAsStateWithLifecycle()
    val progress by browser.progress.collectAsStateWithLifecycle()
    val canGoBack by browser.canGoBack.collectAsStateWithLifecycle()
    val found by browser.found.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var address by remember { mutableStateOf(url) }
    var editing by remember { mutableStateOf(false) }
    val home = url.isEmpty()

    LaunchedEffect(url, editing) { if (!editing) address = url }
    BackHandler { if (canGoBack) browser.webView.goBack() else actions.leave() }

    fun go() {
        // Put the keyboard away so the page can be seen and tapped.
        keyboard?.hide()
        focus.clearFocus(force = true)
        browser.open(address)
    }

    // A new page started loading (typed address, quick link, or a link on the page): done typing.
    LaunchedEffect(progress < 100) {
        if (progress < 100 && !home) {
            keyboard?.hide()
            focus.clearFocus(force = true)
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(enabled = canGoBack) { browser.webView.goBack() },
                contentAlignment = Alignment.Center,
            ) { ArrowGlyph(14.dp, if (canGoBack) colors.onSurface else colors.outline) }
            OutlinedTextField(
                value = if (editing) address else prettyAddress(address),
                onValueChange = { address = it },
                modifier = Modifier.weight(1f).onFocusChanged { editing = it.isFocused },
                placeholder = { Text("주소나 검색어", maxLines = 1) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
            )
            if (!home) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { browser.webView.reload() },
                    contentAlignment = Alignment.Center,
                ) { ReloadGlyph(16.dp, colors.onSurface) }
            }
        }
        if (progress in 1..99 && !home) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        } else {
            Spacer(Modifier.height(2.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (home) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("사이트에서 영상 받기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "위에 주소나 검색어를 넣고, 영상이 있는 페이지에서 아래 [이 영상 받기]를 누르면 돼요. 사이트에 있는 다운로드 버튼을 눌러도 여기서 받아요.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickSites.forEach { (name, link) ->
                            Box(
                                Modifier.clip(RoundedCornerShape(14.dp)).background(colors.surface)
                                    .clickable { keyboard?.hide(); focus.clearFocus(force = true); browser.open(link) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) { Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                        }
                    }
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp)) {
                            Text("로그인이 필요한 영상", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "인스타그램처럼 로그인해야 보이는 영상은 여기서 그 사이트에 로그인해 두면 받을 수 있어요. 로그인 정보는 이 폰 안에만 있어요.",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer,
                            )
                            TextButton(onClick = actions.clearLogins) { Text("브라우저 로그인 모두 지우기") }
                        }
                    }
                }
            } else {
                AndroidView(factory = { browser.detach() }, modifier = Modifier.fillMaxSize())
                ExtendedFloatingActionButton(
                    onClick = actions.download,
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Column {
                        Text("이 영상 받기", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        if (found.isNotEmpty()) {
                            Text("재생된 영상 ${found.size}개 찾음", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun prettyAddress(u: String): String = u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

@Composable
private fun ArrowGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = 2.dp.toPx()
        val s = this.size
        drawLine(color, Offset(s.width * 0.55f, 0f), Offset(0f, s.height / 2), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(0f, s.height / 2), Offset(s.width * 0.55f, s.height), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(0f, s.height / 2), Offset(s.width, s.height / 2), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
private fun ReloadGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = 2.dp.toPx()
        drawArc(color, startAngle = -60f, sweepAngle = 300f, useCenter = false, style = Stroke(width = w, cap = StrokeCap.Round))
        val s = this.size
        // arrow head at the end of the arc (top right)
        val tipX = s.width * 0.75f
        val tipY = s.height * 0.07f
        drawLine(color, Offset(tipX, tipY), Offset(tipX + s.width * 0.28f, tipY - s.height * 0.02f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(tipX, tipY), Offset(tipX + s.width * 0.05f, tipY + s.height * 0.28f), strokeWidth = w, cap = StrokeCap.Round)
    }
}
