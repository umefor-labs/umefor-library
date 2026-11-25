package io.lightknight.omniread.engine.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class CloudflareChallengeRequest(
    val url: String,
    val onResolved: () -> Unit,
    val onCancelled: () -> Unit
)

/**
 * Loads JS-rendered pages in WebView and detects Cloudflare/anti-bot verification challenges.
 * When a human verification gate appears, it surfaces an interactive WebView sheet for the user
 * to solve manually and saves session cookies via CookieManager.
 */
class CloudflareWebViewFetcher(private val context: Context) {
    private val _pendingChallenge = MutableStateFlow<CloudflareChallengeRequest?>(null)
    val pendingChallenge: StateFlow<CloudflareChallengeRequest?> = _pendingChallenge.asStateFlow()

    suspend fun fetchHtml(url: String, waitMs: Long = 2200L): String = withContext(Dispatchers.Main) {
        val html = loadOffscreen(url, waitMs)
        if (!looksLikeChallenge(html)) {
            return@withContext html
        }

        // Ask user to complete verification manually in visible WebView sheet
        val userDone = CompletableDeferred<Boolean>()
        _pendingChallenge.value = CloudflareChallengeRequest(
            url = url,
            onResolved = {
                _pendingChallenge.value = null
                userDone.complete(true)
            },
            onCancelled = {
                _pendingChallenge.value = null
                userDone.complete(false)
            }
        )
        val ok = userDone.await()
        if (!ok) {
            throw IllegalStateException("Người dùng đã hủy xác minh Cloudflare cho $url")
        }
        loadOffscreen(url, waitMs)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadOffscreen(url: String, waitMs: Long): String {
        val deferred = CompletableDeferred<String>()
        val webView = WebView(context.applicationContext)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                Handler(Looper.getMainLooper()).postDelayed({
                    view?.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                        val unescaped = unescapeJsString(raw)
                        CookieManager.getInstance().flush()
                        if (!deferred.isCompleted) deferred.complete(unescaped)
                    }
                }, waitMs.coerceIn(500L, 6000L))
            }
        }
        webView.loadUrl(url)
        val result = withTimeoutOrNull(25_000L) { deferred.await() }.orEmpty()
        webView.destroy()
        return result
    }

    private fun looksLikeChallenge(html: String): Boolean {
        val lower = html.lowercase()
        return (lower.contains("just a moment") && lower.contains("cloudflare")) ||
            lower.contains("cf-turnstile") ||
            lower.contains("challenge-platform")
    }

    private fun unescapeJsString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        val trimmed = raw.removeSurrounding("\"")
        return trimmed
            .replace("\\u003C", "<")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}
