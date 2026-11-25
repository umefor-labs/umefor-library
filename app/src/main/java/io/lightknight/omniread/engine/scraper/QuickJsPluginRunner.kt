package io.lightknight.omniread.engine.scraper

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import io.lightknight.omniread.data.local.SourcePluginEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class OkHttpPluginBridge(
    private val httpClient: OkHttpClient,
    private val webViewFetcher: CloudflareWebViewFetcher?,
    private val userAgent: String = DEFAULT_MOBILE_UA
) : PluginHttpBridge {
    private data class CachedHtml(val body: String, val timestampMs: Long)
    private val getCache = object : LinkedHashMap<String, CachedHtml>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedHtml>?): Boolean =
            size > GET_CACHE_MAX_SIZE
    }

    override suspend fun httpGet(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            if (headers.isEmpty()) {
                val now = System.currentTimeMillis()
                synchronized(getCache) {
                    val cached = getCache[url]
                    if (cached != null && now - cached.timestampMs <= GET_CACHE_TTL_MS) {
                        return@withContext cached.body
                    }
                }
            }
            val body = executeGetWithRetry(url, headers, allow429Retry = true)
            if (headers.isEmpty() && body.isNotBlank()) {
                synchronized(getCache) {
                    getCache[url] = CachedHtml(body, System.currentTimeMillis())
                }
            }
            body
        }

    private suspend fun executeGetWithRetry(
        url: String,
        headers: Map<String, String>,
        allow429Retry: Boolean
    ): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
        headers.forEach { (k, v) -> builder.header(k, v) }
        httpClient.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val isCfMitigated = resp.header("Cf-Mitigated")?.contains("challenge", ignoreCase = true) == true
            val isCloudflareGate = isCfMitigated ||
                ((resp.code == 403 || resp.code == 503) &&
                    (body.contains("cloudflare", ignoreCase = true) || body.contains("Just a moment", ignoreCase = true)))

            if (isCloudflareGate && webViewFetcher != null) {
                return webViewFetcher.fetchHtml(url)
            }
            if (resp.code == 429) {
                if (allow429Retry) {
                    val waitSec = resp.header("Retry-After")?.toLongOrNull()?.coerceIn(2L, 5L) ?: 3L
                    delay(waitSec * 1000L)
                    return executeGetWithRetry(url, headers, allow429Retry = false)
                }
                throw IllegalStateException(
                    "Trang web đang giới hạn tốc độ truy cập (429). Vui lòng đợi vài giây rồi thử lại."
                )
            }
            if (!resp.isSuccessful) {
                throw IllegalStateException("Máy chủ trả mã lỗi HTTP ${resp.code} khi tải $url")
            }
            return body
        }
    }

    override suspend fun httpPost(url: String, body: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val contentTypeStr = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                ?: "application/json; charset=UTF-8"
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .post(body.toRequestBody(contentTypeStr.toMediaType()))
            headers.forEach { (k, v) ->
                if (!k.equals("Content-Type", ignoreCase = true)) {
                    builder.header(k, v)
                }
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (resp.code == 429) {
                    throw IllegalStateException("Trang web đang giới hạn tốc độ tìm kiếm (429). Vui lòng đợi vài giây rồi thử lại.")
                }
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Máy chủ trả mã lỗi HTTP ${resp.code} khi gửi yêu cầu tới $url")
                }
                respBody
            }
        }

    override suspend fun webViewGet(url: String, waitMs: Long): String {
        return webViewFetcher?.fetchHtml(url, waitMs) ?: httpGet(url)
    }

    companion object {
        private const val GET_CACHE_TTL_MS = 30_000L
        private const val GET_CACHE_MAX_SIZE = 12
        const val DEFAULT_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}

class QuickJsPluginRunner(
    private val bridge: PluginHttpBridge
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun popular(plugin: SourcePluginEntity, page: Int = 1): Result<List<RemoteBookSummary>> =
        runFriendly(plugin) {
            val raw = evalPluginCall(plugin, "popular($page)")
            json.decodeFromString(raw)
        }

    suspend fun search(plugin: SourcePluginEntity, query: String, page: Int = 1): Result<List<RemoteBookSummary>> =
        runFriendly(plugin) {
            val qEsc = escapeJs(query)
            val raw = evalPluginCall(plugin, """search("$qEsc", $page)""")
            json.decodeFromString(raw)
        }

    suspend fun getDetail(plugin: SourcePluginEntity, bookUrl: String): Result<RemoteBookDetail> =
        runFriendly(plugin) {
            val uEsc = escapeJs(bookUrl)
            val raw = evalPluginCall(plugin, """getDetail("$uEsc")""")
            json.decodeFromString(raw)
        }

    suspend fun getChapters(plugin: SourcePluginEntity, bookUrl: String): Result<List<RemoteChapter>> =
        runFriendly(plugin) {
            val uEsc = escapeJs(bookUrl)
            val raw = evalPluginCall(plugin, """getChapters("$uEsc")""")
            json.decodeFromString(raw)
        }

    suspend fun getChapterContent(plugin: SourcePluginEntity, chapterUrl: String): Result<RemoteChapterPayload> =
        runFriendly(plugin) {
            val uEsc = escapeJs(chapterUrl)
            val raw = evalPluginCall(plugin, """getChapterContent("$uEsc")""")
            json.decodeFromString(raw)
        }

    private suspend inline fun <T> runFriendly(plugin: SourcePluginEntity, block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(formatFriendlyPluginError(t, plugin.baseUrl), t))
        }
    }

    private suspend fun evalPluginCall(plugin: SourcePluginEntity, callExpr: String): String =
        withContext(Dispatchers.Default) {
            quickJs(Dispatchers.Default) {
                asyncFunction("httpGet") { args ->
                    val url = args.getOrNull(0)?.toString().orEmpty()
                    val headers = parseHeadersJson(args.getOrNull(1)?.toString())
                    bridge.httpGet(url, headers)
                }
                asyncFunction("httpPost") { args ->
                    val url = args.getOrNull(0)?.toString().orEmpty()
                    val body = args.getOrNull(1)?.toString().orEmpty()
                    val headers = parseHeadersJson(args.getOrNull(2)?.toString())
                    bridge.httpPost(url, body, headers)
                }
                asyncFunction("webViewGet") { args ->
                    val url = args.getOrNull(0)?.toString().orEmpty()
                    val waitMs = args.getOrNull(1)?.toString()?.toLongOrNull() ?: 2200L
                    bridge.webViewGet(url, waitMs)
                }
                function("htmlSelect") { args ->
                    val html = args.getOrNull(0)?.toString().orEmpty()
                    val selector = args.getOrNull(1)?.toString().orEmpty()
                    val doc = Jsoup.parse(html)
                    val list = doc.select(selector).map { el ->
                        val text = escapeJson(el.text())
                        val innerHtml = escapeJson(el.html())
                        val href = escapeJson(el.attr("href"))
                        val src = escapeJson(
                            el.attr("data-original")
                                .ifBlank { el.attr("data-src") }
                                .ifBlank { el.attr("src") }
                                .ifBlank { el.attr("data-fb") }
                                .ifBlank { el.attr("data-cdn") }
                        )
                        val alt = escapeJson(el.attr("alt"))
                        """{"text":"$text","html":"$innerHtml","href":"$href","src":"$src","alt":"$alt"}"""
                    }
                    "[${list.joinToString(",")}]"
                }
                function("htmlSelectAttr") { args ->
                    val html = args.getOrNull(0)?.toString().orEmpty()
                    val selector = args.getOrNull(1)?.toString().orEmpty()
                    val attr = args.getOrNull(2)?.toString().orEmpty()
                    val doc = Jsoup.parse(html)
                    val list = doc.select(selector).map { el -> """"${escapeJson(el.attr(attr))}"""" }
                    "[${list.joinToString(",")}]"
                }
                function("htmlSelectText") { args ->
                    val html = args.getOrNull(0)?.toString().orEmpty()
                    val selector = args.getOrNull(1)?.toString().orEmpty()
                    Jsoup.parse(html).selectFirst(selector)?.text().orEmpty()
                }
                function("htmlStripToParagraphs") { args ->
                    val html = args.getOrNull(0)?.toString().orEmpty()
                    val paras = extractCleanParagraphsFromHtml(html)
                    "[${paras.joinToString(",") { """"${escapeJson(it)}"""" }}]"
                }

                val deferred = CompletableDeferred<String>()
                function("__omniResolve") { args ->
                    if (!deferred.isCompleted) deferred.complete(args.getOrNull(0)?.toString().orEmpty())
                }
                function("__omniReject") { args ->
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(
                            IllegalStateException(args.getOrNull(0)?.toString() ?: "Lỗi chạy plugin JS")
                        )
                    }
                }

                val baseUrlEsc = escapeJs(plugin.baseUrl.trimEnd('/'))
                val wrapperScript = """
                    const BASE_URL = "$baseUrlEsc";
                    ${plugin.scriptContent}
                    (async () => {
                        const res = await $callExpr;
                        return typeof res === "string" ? res : JSON.stringify(res);
                    })().then(v => __omniResolve(v)).catch(e => __omniReject((e && e.message) ? e.message : String(e)));
                """.trimIndent()

                evaluate<Any?>(wrapperScript)
                deferred.await()
            }
        }

    private fun parseHeadersJson(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank() || raw == "undefined" || raw == "null") return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun escapeJson(s: String): String = Companion.escapeJson(s)

    companion object {
        fun escapeJson(s: String): String = buildString(s.length) {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\u2028' -> append("\\u2028")
                    '\u2029' -> append("\\u2029")
                    else -> if (c.code < 0x20) {
                        append("\\u%04x".format(c.code))
                    } else {
                        append(c)
                    }
                }
            }
        }
        fun extractCleanParagraphsFromHtml(html: String): List<String> {
            val doc = Jsoup.parse(html)
            doc.select("script, style, ins, .ads, .advertisement, .d-none, .hidden, [style*=display:none], [style*=display: none]")
                .remove()
            // Preserve <br> breaks before reading text
            doc.select("br").after("\n")
            doc.select("p, div, h1, h2, h3, h4, li, blockquote").forEach { el ->
                if (el.tagName() == "p" || el.children().none { it.tagName() in setOf("p", "div") }) {
                    el.after("\n")
                }
            }
            val blockTexts = doc.select("p, h1, h2, h3, h4, li, blockquote")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
            if (blockTexts.size >= 2) return blockTexts

            val raw = doc.body().wholeText()
            val lines = raw.split(Regex("\n+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return lines.ifEmpty {
                blockTexts
            }
        }

        fun formatFriendlyPluginError(throwable: Throwable, baseUrl: String): String {
            val raw = generateSequence(throwable) { it.cause }
                .mapNotNull { it.message }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

            return when {
                throwable is UnknownHostException || raw.contains("lookupHostByName", ignoreCase = true) ||
                    raw.contains("Unable to resolve host", ignoreCase = true) ->
                    "Không tìm thấy tên miền ($baseUrl). Kiểm tra mạng hoặc đổi Base URL trong Quản lý Plugin."
                throwable is ConnectException || throwable is SocketTimeoutException ||
                    raw.contains("connectErrno", ignoreCase = true) ||
                    raw.contains("Failed to connect", ignoreCase = true) ||
                    raw.contains("timed out", ignoreCase = true) ->
                    "Không kết nối được tới $baseUrl (có thể bị chặn mạng hoặc trang đang bận). Hãy thử Mở trình duyệt nguồn hoặc đổi tên miền."
                raw.startsWith("[") && raw.contains("java.") ->
                    "Lỗi mạng khi truy cập $baseUrl. Hãy thử Mở trình duyệt nguồn."
                raw.isNotBlank() -> raw.take(260)
                else -> "Không thể tải dữ liệu từ $baseUrl."
            }
        }
    }
}
