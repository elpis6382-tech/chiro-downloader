package io.github.apostolichiro.videodl.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.apostolichiro.videodl.Browser
import io.github.apostolichiro.videodl.R
import io.github.apostolichiro.videodl.Store
import io.github.apostolichiro.videodl.Texts

/** Two tabs: the download list and the in-app browser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(actions: Actions, browser: () -> Browser, browserActions: BrowserActions) {
    val tab by Store.tab.collectAsStateWithLifecycle()
    val jobs by Store.jobs.collectAsStateWithLifecycle()
    val notice by Store.notice.collectAsStateWithLifecycle()
    val dup by Store.dupAsk.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val colors = MaterialTheme.colorScheme
    val running = jobs.count { it.status.active || it.status == io.github.apostolichiro.videodl.Status.QUEUED }

    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        Store.notice.value = null
        snackbar.showSnackbar(n)
    }

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { Store.tab.value = 0 },
                    icon = {
                        BadgedBox(badge = { if (running > 0) Badge { Text("$running") } }) {
                            Icon(painterResource(R.drawable.ic_stat), contentDescription = null)
                        }
                    },
                    label = { Text("받기") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { Store.tab.value = 1 },
                    icon = { GlobeGlyph(if (tab == 1) colors.onSecondaryContainer else colors.onSurfaceVariant) },
                    label = { Text("브라우저") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 1) BrowserScreen(browser(), browserActions) else MainScreen(actions)
        }
    }

    dup?.let { d ->
        AlertDialog(
            onDismissRequest = { Store.dupAsk.value = null },
            title = { Text("이미 받은 영상이에요") },
            text = { Text(Texts.title(d.existing) + "\n" + Texts.status(d.existing)) },
            confirmButton = {
                TextButton(onClick = {
                    Store.dupAsk.value = null
                    actions.forceDownload(d.pending)
                }) { Text("그래도 받기") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Store.dupAsk.value = null
                    d.existing.files.firstOrNull()?.let(actions.open)
                }) { Text("보기") }
            },
        )
    }
}

@Composable
private fun GlobeGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = 1.8.dp.toPx()
        val s = size
        val r = s.minDimension / 2 - w
        drawCircle(color, radius = r, style = Stroke(width = w))
        drawOval(color, topLeft = Offset(s.width / 2 - r * 0.45f, s.height / 2 - r), size = androidx.compose.ui.geometry.Size(r * 0.9f, r * 2), style = Stroke(width = w))
        drawLine(color, Offset(s.width / 2 - r, s.height / 2), Offset(s.width / 2 + r, s.height / 2), strokeWidth = w)
    }
}
