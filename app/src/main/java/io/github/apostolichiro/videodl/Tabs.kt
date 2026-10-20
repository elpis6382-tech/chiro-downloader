package io.github.apostolichiro.videodl

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.apostolichiro.videodl.engine.AdBlock
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Sniff
import io.github.apostolichiro.videodl.engine.Web
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/** Full-screen video from a web page (the player's own full-screen button). */
interface FullscreenHost {
    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
    fun hideCustomView()
}

/** One browser tab. Its WebView is only made once the tab shows a web page. */
class WebTab internal constructor(val id: Long, private val owner: Tabs, startUrl: String?) {
    /** "" while the tab shows the app's home page. */
    val url = MutableStateFlow(startUrl ?: "")
    val title = MutableStateFlow("")
    val progress = MutableStateFlow(100)
    val canGoBack = MutableStateFlow(false)
    val canGoForward = MutableStateFlow(false)
    /** Video streams/files the page loaded, best first. */
    val found = MutableStateFlow<List<String>>(emptyList())
    /** Looked up ahead of time on pages known to hold one video, so the download sheet opens ready. */
    val info = MutableStateFlow<InfoState?>(null)
    val snapshot = MutableStateFlow<Bitmap?>(null)
    /** The page's own picture (og:image), for the download sheet. */
    val pageImage = MutableStateFlow<String?>(null)

    private var pending: String? = startUrl?.takeIf { it.isNotBlank() }
    internal var view: WebView? = null
        private set
    private var lookup: Job? = null
    private var lookedUp: String? = null

    val isHome get() = url.value.isEmpty()

    /** The tab's WebView, made (and the remembered page loaded) on first use. */
    fun webView(): WebView {
        view?.let { return it }
        val v = owner.makeWebView(this)
        view = v
        pending?.let { pending = null; v.loadUrl(it) }
        return v
    }

    fun load(target: String) {
        url.value = target
        found.value = emptyList()
        webView().loadUrl(target)
        onUrl(target)
    }

    /** Back inside the tab; from the first page back to the home page. False when there is nowhere to go. */
    fun back(): Boolean {
        val v = view
        if (v != null && v.canGoBack()) {
            v.goBack()
            return true
        }
        if (!isHome) {
            showHome()
            return true
        }
        return false
    }

    fun forward() {
        view?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        view?.reload()
    }

    fun stop() {
        view?.stopLoading()
    }

    /** Back to the app's home page in this tab (the WebView is let go). */
    fun showHome() {
        lookup?.cancel()
        view?.let { owner.release(it) }
        view = null
        pending = null
        url.value = ""
        title.value = ""
        progress.value = 100
        found.value = emptyList()
        info.value = null
        lookedUp = null
        canGoBack.value = false
        canGoForward.value = false
    }

    internal fun onUrl(u: String) {
        if (u.isBlank() || u == "about:blank") return
        url.value = u
        view?.let {
            canGoBack.value = it.canGoBack()
            canGoForward.value = it.canGoForward()
        }
        val key = Links.normalize(u)
        if (key == lookedUp) return
        // Another page (also when a site changes pages without reloading): the videos seen so far were the old page's.
        if (lookedUp != null) found.value = emptyList()
        lookedUp = key
        lookup?.cancel()
        info.value = null
        if (!Web.isVideoPage(u)) return
        lookup = owner.scope.launch {
            delay(600)
            info.value = InfoState.Loading
            info.value = Infos.fetch(owner.activity, u, referer = u, userAgent = view?.settings?.userAgentString)
        }
    }

    /** Reads the page's og:image (pages that change without reloading get a moment to update it first). */
    internal fun readMeta(delayMs: Long = 0) {
        val v = view ?: return
        v.postDelayed({
            if (view !== v) return@postDelayed
            v.evaluateJavascript(META_JS) { r ->
                val img = try {
                    if (r == null || r == "null") null else org.json.JSONTokener(r).nextValue() as? String
                } catch (_: Exception) {
                    null
                }
                pageImage.value = img?.takeIf { it.startsWith("http") }
            }
        }, delayMs)
    }

    /** Starts the lookup now (the person pressed the download button on a page that wasn't looked up yet). */
    fun lookUpNow() {
        val u = url.value
        if (u.isBlank() || info.value is InfoState.Loading || info.value is InfoState.Ready) return
        lookedUp = Links.normalize(u)
        lookup?.cancel()
        info.value = InfoState.Loading
        lookup = owner.scope.launch {
            info.value = Infos.fetch(owner.activity, u, referer = u, userAgent = view?.settings?.userAgentString)
        }
    }

    internal fun destroy() {
        lookup?.cancel()
        view?.let { owner.release(it) }
        view = null
    }
}

private const val META_JS =
    "(function(){var q=function(n){var e=document.querySelector('meta[property=\"'+n+'\"],meta[name=\"'+n+'\"]');" +
        "return e&&e.content?e.content:null};var u=q('og:image')||q('twitter:image');" +
        "return u?new URL(u,location.href).href:null})()"

/** The app's browser: any number of tabs, one in front. */
@SuppressLint("SetJavaScriptEnabled")
class Tabs(
    internal val activity: Activity,
    internal val scope: CoroutineScope,
    private val fullscreen: FullscreenHost,
    private val onSiteDownload: (url: String, userAgent: String, disposition: String?, mime: String?, page: String?) -> Unit,
) {
    private var nextId = 1L
    val list = MutableStateFlow<List<WebTab>>(emptyList())
    val current: MutableStateFlow<WebTab>

    init {
        val saved = Store.savedTabs.take(20)
        val tabs = if (saved.isEmpty()) listOf(WebTab(nextId++, this, null)) else saved.map { WebTab(nextId++, this, it.ifBlank { null }) }
        list.value = tabs
        current = MutableStateFlow(tabs[Store.savedTabIndex.coerceIn(0, tabs.size - 1)])
    }

    fun newTab(url: String? = null, select: Boolean = true): WebTab {
        val t = WebTab(nextId++, this, null)
        list.update { it + t }
        if (select) select(t)
        if (url != null) t.load(url)
        return t
    }

    fun select(t: WebTab) {
        val old = current.value
        if (old !== t) {
            capture(old)
            old.view?.let { pause(it) }
        }
        current.value = t
        t.view?.onResume()
    }

    fun close(t: WebTab) {
        val tabs = list.value
        val i = tabs.indexOf(t)
        if (i < 0) return
        t.destroy()
        val left = tabs - t
        if (left.isEmpty()) {
            val fresh = WebTab(nextId++, this, null)
            list.value = listOf(fresh)
            current.value = fresh
        } else {
            list.value = left
            if (current.value === t) select(left[(i - 1).coerceAtLeast(0)])
        }
    }

    fun closeAll() {
        list.value.forEach { it.destroy() }
        val fresh = WebTab(nextId++, this, null)
        list.value = listOf(fresh)
        current.value = fresh
    }

    /** What was typed in the address bar: a web address, or a search. */
    fun open(input: String, tab: WebTab = current.value) {
        val target = Web.target(input, Store.searchEngine) ?: return
        tab.load(target)
    }

    fun save() {
        Store.savedTabs = list.value.map { it.url.value.takeUnless { u -> u == "about:blank" } ?: "" }
        Store.savedTabIndex = list.value.indexOf(current.value).coerceAtLeast(0)
    }

    /** Takes a small picture of the tab for the tab list. */
    fun capture(t: WebTab) {
        val v = t.view ?: return
        if (!v.isAttachedToWindow || v.width == 0 || v.height == 0) return
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val bmp = Bitmap.createBitmap((v.width / 3).coerceAtLeast(1), (v.height / 3).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                activity.window, Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height), bmp,
                { r -> if (r == PixelCopy.SUCCESS) t.snapshot.value = bmp },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: Exception) {
        }
    }

    fun pauseAll() {
        list.value.forEach { t -> t.view?.let { pause(it) } }
    }

    fun resumeCurrent() {
        current.value.view?.onResume()
    }

    fun destroy() {
        list.value.forEach { it.destroy() }
    }

    private fun pause(v: WebView) {
        v.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null)
        v.onPause()
    }

    internal fun release(v: WebView) {
        (v.parent as? ViewGroup)?.removeView(v)
        v.stopLoading()
        v.destroy()
    }

    private fun tabOf(v: WebView): WebTab? = list.value.firstOrNull { it.view === v }

    internal fun makeWebView(tab: WebTab): WebView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // Links that open a new window go to a new tab; windows a page opens by itself (pop-up ads) don't open at all.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        // Some sites refuse logins inside apps; looking like the regular mobile browser avoids that.
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase()
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
                // Ads like to jump to the store or another app on their own; only a tap may do that.
                if (!request.hasGesture()) return true
                try {
                    val i = if (scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    activity.startActivity(i)
                } catch (_: ActivityNotFoundException) {
                } catch (_: Exception) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                tab.found.value = emptyList()
                tab.pageImage.value = null
                tab.onUrl(pageUrl)
            }

            override fun onPageFinished(view: WebView, pageUrl: String) {
                tab.onUrl(view.url ?: pageUrl)
                view.title?.takeIf { it.isNotBlank() }?.let { tab.title.value = it }
                tab.readMeta()
                CookieManager.getInstance().flush()
            }

            override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
                // Single-page sites (YouTube, Instagram...) change the address without loading a new page.
                tab.onUrl(pageUrl)
                tab.readMeta(1500)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                val host = u.host
                if (host != null && !request.isForMainFrame && Store.adBlock && AdBlock.blocks(host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                val s = u.toString()
                if (Sniff.kind(s) != null) tab.found.update { Sniff.add(it, s) }
                return null
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress.value = newProgress
            }

            override fun onReceivedTitle(view: WebView, t: String?) {
                if (!t.isNullOrBlank()) tab.title.value = t
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture && Store.adBlock) return false
                val t = newTab(null, select = true)
                t.url.value = "about:blank"
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = t.webView()
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabOf(window)?.let { close(it) }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreen.showCustomView(view, callback)
            }

            override fun onHideCustomView() {
                fullscreen.hideCustomView()
            }
        }
        setDownloadListener { dlUrl, ua, disposition, mime, _ ->
            onSiteDownload(dlUrl, ua ?: settings.userAgentString, disposition, mime, url)
        }
    }
}
