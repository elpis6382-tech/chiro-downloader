package io.github.apostolichiro.videodl

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.apostolichiro.videodl.engine.Sniff
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The app's own web browser: one WebView kept alive while the person switches tabs. */
@SuppressLint("SetJavaScriptEnabled")
class Browser(activity: Activity, onSiteDownload: (url: String, userAgent: String, disposition: String?, mime: String?) -> Unit) {
    val url = MutableStateFlow("")
    val title = MutableStateFlow("")
    val progress = MutableStateFlow(100)
    val canGoBack = MutableStateFlow(false)
    /** Video streams/files the current page loaded, best first. */
    val found = MutableStateFlow<List<String>>(emptyList())

    val webView: WebView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportMultipleWindows(false)
        // Some sites refuse logins inside apps; looking like the regular mobile browser avoids that.
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") return false
                // intent://, market://, tel: ... go to the right app if there is one.
                try {
                    val i = if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
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
                url.value = pageUrl
                found.value = emptyList()
                canGoBack.value = view.canGoBack()
            }

            override fun onPageFinished(view: WebView, pageUrl: String) {
                url.value = view.url ?: pageUrl
                title.value = view.title ?: ""
                canGoBack.value = view.canGoBack()
                CookieManager.getInstance().flush()
            }

            override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
                // Single-page sites (YouTube, Instagram...) change the address without loading a new page.
                url.value = pageUrl
                canGoBack.value = view.canGoBack()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (Sniff.kind(u) != null) found.update { Sniff.add(it, u) }
                return null
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.value = newProgress
            }

            override fun onReceivedTitle(view: WebView, t: String?) {
                title.value = t ?: ""
            }
        }
        setDownloadListener { dlUrl, ua, disposition, mime, _ -> onSiteDownload(dlUrl, ua ?: settings.userAgentString, disposition, mime) }
    }

    val userAgent: String get() = webView.settings.userAgentString

    /** A web address as typed ("youtube.com", "https://...") or anything else as a Google search. */
    fun open(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val looksLikeAddress = !t.contains(' ') && (t.startsWith("http://") || t.startsWith("https://") ||
            Regex("""^[\w-]+(\.[\w-]+)+(:\d+)?(/.*)?$""").matches(t))
        val target = when {
            !looksLikeAddress -> "https://www.google.com/search?q=" + Uri.encode(t)
            t.startsWith("http://") || t.startsWith("https://") -> t
            else -> "https://$t"
        }
        webView.loadUrl(target)
    }

    fun detach(): WebView {
        (webView.parent as? ViewGroup)?.removeView(webView)
        return webView
    }

    fun destroy() {
        detach()
        webView.destroy()
    }
}
