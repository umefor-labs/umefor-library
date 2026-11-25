package io.lightknight.omniread.engine.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

enum class WebDavPreset(
    val id: String,
    val displayName: String,
    val defaultUrl: String,
    val hint: String
) {
    KOOFR(
        id = "KOOFR",
        displayName = "Koofr (10 GB)",
        defaultUrl = "https://app.koofr.net/dav/Koofr",
        hint = "Tạo mật khẩu ứng dụng tại Koofr → Preferences → Password → App passwords rồi đăng nhập bằng Email + App password."
    ),
    INFINICLOUD(
        id = "INFINICLOUD",
        displayName = "InfiniCLOUD (20 GB)",
        defaultUrl = "https://YOUR_NODE.teracloud.jp/dav",
        hint = "Đăng nhập InfiniCLOUD → My Page → bật Apps Connection để lấy URL node cá nhân & App Password."
    ),
    CUSTOM(
        id = "CUSTOM",
        displayName = "Nextcloud / Khác",
        defaultUrl = "",
        hint = "Nhập URL WebDAV đầy đủ (ví dụ https://cloud.domain/remote.php/dav/files/ten_tai_khoan)."
    );

    companion object {
        fun fromId(id: String): WebDavPreset =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: KOOFR
    }
}

data class WebDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    val rootFolder: String = "UmeforLibrary"
)

data class WebDavEntry(
    val name: String,
    val href: String,
    val isDirectory: Boolean,
    val size: Long = 0L
)

class WebDavSyncClient(
    private val httpClient: OkHttpClient
) {
    private fun authHeader(config: WebDavConfig): String =
        Credentials.basic(config.username.trim(), config.password, Charsets.UTF_8)

    suspend fun testConnection(config: WebDavConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildPathUrl(config.baseUrl, "")
            val propBody = PROPFIND_ALL.toRequestBody(XML_MEDIA_TYPE)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(config))
                .header("Depth", "0")
                .method("PROPFIND", propBody)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200, 207 -> "Kết nối WebDAV thành công!"
                    401, 403 -> throw IllegalStateException("Sai tên đăng nhập hoặc mật khẩu ứng dụng (HTTP ${resp.code}).")
                    404 -> throw IllegalStateException("Không tìm thấy đường dẫn WebDAV (HTTP 404). Hãy kiểm tra lại URL máy chủ.")
                    else -> throw IllegalStateException("Máy chủ WebDAV trả lỗi HTTP ${resp.code}.")
                }
            }
        }
    }

    suspend fun ensureDirectory(config: WebDavConfig, relativePath: String) = withContext(Dispatchers.IO) {
        val segments = relativePath.trim('/').split('/').filter { it.isNotBlank() }
        var current = ""
        for (seg in segments) {
            current = if (current.isEmpty()) seg else "$current/$seg"
            val url = buildPathUrl(config.baseUrl, current)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(config))
                .method("MKCOL", ByteArray(0).toRequestBody(null))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                // 201 Created, 405 Method Not Allowed (already exists), 301/200/204/409 (exists on some servers)
                if (!resp.isSuccessful && resp.code !in setOf(405, 301, 302)) {
                    throw IOException("Không tạo được thư mục WebDAV $current (HTTP ${resp.code})")
                }
            }
        }
    }

    suspend fun listDirectory(config: WebDavConfig, relativePath: String): List<WebDavEntry> =
        withContext(Dispatchers.IO) {
            val url = buildPathUrl(config.baseUrl, relativePath)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(config))
                .header("Depth", "1")
                .method("PROPFIND", PROPFIND_ALL.toRequestBody(XML_MEDIA_TYPE))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val xml = resp.body?.string().orEmpty()
                if (resp.code == 404) return@withContext emptyList()
                if (!resp.isSuccessful && resp.code != 207) {
                    throw IOException("WebDAV PROPFIND $relativePath HTTP ${resp.code}")
                }
                val reqPath = runCatching { URI(url).path }.getOrDefault("")
                parsePropfindXml(xml, reqPath)
            }
        }

    suspend fun downloadText(config: WebDavConfig, relativePath: String): String? =
        withContext(Dispatchers.IO) {
            val url = buildPathUrl(config.baseUrl, relativePath)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(config))
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@withContext null
                if (!resp.isSuccessful) throw IOException("WebDAV GET $relativePath HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }

    suspend fun downloadFile(config: WebDavConfig, relativePath: String, destFile: File): File =
        withContext(Dispatchers.IO) {
            val url = buildPathUrl(config.baseUrl, relativePath)
            val req = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(config))
                .get()
                .build()
            destFile.parentFile?.mkdirs()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("WebDAV tải file $relativePath thất bại (HTTP ${resp.code})")
                val body = resp.body ?: throw IOException("WebDAV body rỗng")
                body.byteStream().use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            destFile
        }

    suspend fun uploadText(
        config: WebDavConfig,
        relativePath: String,
        content: String,
        mimeType: String = "application/json; charset=utf-8"
    ) = withContext(Dispatchers.IO) {
        val url = buildPathUrl(config.baseUrl, relativePath)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(config))
            .put(content.toRequestBody(mimeType.toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("WebDAV PUT $relativePath HTTP ${resp.code}")
        }
    }

    suspend fun uploadFile(
        config: WebDavConfig,
        relativePath: String,
        file: File,
        mimeType: String = "application/octet-stream"
    ) = withContext(Dispatchers.IO) {
        val url = buildPathUrl(config.baseUrl, relativePath)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(config))
            .put(file.asRequestBody(mimeType.toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("WebDAV upload $relativePath HTTP ${resp.code}")
        }
    }

    suspend fun moveFile(config: WebDavConfig, fromPath: String, toPath: String) =
        withContext(Dispatchers.IO) {
            val srcUrl = buildPathUrl(config.baseUrl, fromPath)
            val dstUrl = buildPathUrl(config.baseUrl, toPath)
            val req = Request.Builder()
                .url(srcUrl)
                .header("Authorization", authHeader(config))
                .header("Destination", dstUrl)
                .header("Overwrite", "T")
                .method("MOVE", null)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Fallback to DELETE so inbox does not re-import the same file repeatedly
                    val delReq = Request.Builder()
                        .url(srcUrl)
                        .header("Authorization", authHeader(config))
                        .delete()
                        .build()
                    httpClient.newCall(delReq).execute().close()
                }
            }
        }

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private const val PROPFIND_ALL = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:resourcetype/>
    <D:getcontentlength/>
  </D:prop>
</D:propfind>"""

        fun buildPathUrl(baseUrl: String, relativePath: String): String {
            val cleanBase = baseUrl.trim().trimEnd('/')
            val segments = relativePath.trim('/').split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return cleanBase
            val encodedSuffix = segments.joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }
            return "$cleanBase/$encodedSuffix"
        }

        private fun localTag(el: org.jsoup.nodes.Element): String =
            el.tagName().substringAfter(':').lowercase(java.util.Locale.US)

        fun parsePropfindXml(xml: String, requestedPath: String): List<WebDavEntry> {
            if (xml.isBlank()) return emptyList()
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            val normRequested = normalizeHrefPath(requestedPath)

            val responses = doc.select("*").filter { localTag(it) == "response" }
            val entries = mutableListOf<WebDavEntry>()
            for (respEl in responses) {
                val hrefRaw = respEl.children()
                    .firstOrNull { localTag(it) == "href" }
                    ?.text()
                    ?.trim()
                    .orEmpty()
                if (hrefRaw.isBlank()) continue

                val normHref = normalizeHrefPath(hrefRaw)
                if (normRequested.isNotBlank() && normHref == normRequested) continue

                val isDir = respEl.select("*").any { localTag(it) == "collection" } || hrefRaw.endsWith("/")
                val size = respEl.select("*")
                    .firstOrNull { localTag(it) == "getcontentlength" }
                    ?.text()
                    ?.trim()
                    ?.toLongOrNull() ?: 0L

                val decodedSegment = normHref.substringAfterLast('/')
                val name = runCatching { URLDecoder.decode(decodedSegment, "UTF-8") }.getOrDefault(decodedSegment)
                if (name.isBlank()) continue

                entries += WebDavEntry(
                    name = name,
                    href = hrefRaw,
                    isDirectory = isDir,
                    size = size
                )
            }
            return entries
        }

        private fun normalizeHrefPath(raw: String): String {
            val pathOnly = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
                runCatching { URI(raw).rawPath }.getOrDefault(raw)
            } else {
                raw
            }
            return runCatching { URLDecoder.decode(pathOnly, "UTF-8") }
                .getOrDefault(pathOnly)
                .trimEnd('/')
        }
    }
}
