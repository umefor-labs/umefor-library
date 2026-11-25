package io.lightknight.omniread.ui.explore

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.engine.importer.WebDownloadFileDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceWebBrowserScreen(
    initialUrl: String,
    container: OmniAppContainer,
    onBack: () -> Unit,
    onImportedBook: (String) -> Unit
) {
    var currentUrl by remember { mutableStateOf(initialUrl.ifBlank { "https://truyenqqko.com" }) }
    var pageTitle by remember { mutableStateOf("Trình duyệt nguồn") }
    var isDownloading by remember { mutableStateOf(false) }
    var pendingPdfFile by remember { mutableStateOf<File?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    fun importDownloadedFile(file: File, pdfMode: MediaType) {
        scope.launch {
            isDownloading = true
            val res = container.localImportManager.importLocalFile(file, pdfMode)
            file.delete()
            isDownloading = false
            res.onSuccess {
                snackbarHostState.showSnackbar("Đã nhập vào Thư viện: ${it.item.title}")
                onImportedBook(it.item.id)
            }.onFailure { err ->
                snackbarHostState.showSnackbar("Lỗi nhập file: ${err.message}")
            }
        }
    }

    fun handleWebDownload(downloadUrl: String, userAgent: String, contentDisposition: String, mimeType: String) {
        scope.launch {
            isDownloading = true
            val downloadedResult = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder()
                        .url(downloadUrl)
                        .header("User-Agent", userAgent.ifBlank { container.webUserAgent })
                        .header("Referer", currentUrl)
                        .build()
                    container.webHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("Tải file thất bại (HTTP ${resp.code}).")
                        }
                        val cdHeader = resp.header("Content-Disposition") ?: contentDisposition
                        val respMime = resp.header("Content-Type")?.substringBefore(';')?.trim() ?: mimeType
                        val guessedName = WebDownloadFileDetector.extractFileNameFromHeaders(downloadUrl, cdHeader, respMime)
                            ?: URLUtil.guessFileName(downloadUrl, cdHeader, respMime)
                        val safeName = WebDownloadFileDetector.sanitizeFileName(guessedName)
                        val tempFile = File(container.storageManager.inboxTempDir, safeName)
                        resp.body?.byteStream()?.use { ins ->
                            tempFile.outputStream().use { out -> ins.copyTo(out, 16 * 1024) }
                        } ?: throw IllegalStateException("File tải về bị rỗng.")

                        val detected = WebDownloadFileDetector.detectFormatByMagicBytes(tempFile)
                        if (detected == "html_page") {
                            tempFile.delete()
                            return@runCatching null // Intermediate HTML redirect page -> open in WebView
                        }
                        val finalFile = if (detected != null && detected != "zip_bundle" &&
                            tempFile.extension.lowercase() !in setOf("epub", "pdf", "cbz", "cbr", "mp3", "m4a", "m4b", "opus")
                        ) {
                            WebDownloadFileDetector.renameWithDetectedExtension(tempFile, pageTitle, detected)
                        } else {
                            tempFile
                        }
                        finalFile to (detected ?: finalFile.extension.lowercase())
                    }
                }
            }

            isDownloading = false
            downloadedResult.onSuccess { pair ->
                if (pair == null) {
                    webViewRef?.loadUrl(downloadUrl)
                    snackbarHostState.showSnackbar("Đang mở trang chuyển tiếp để tải file...")
                } else {
                    val (file, ext) = pair
                    if (ext == "pdf") {
                        pendingPdfFile = file
                    } else {
                        importDownloadedFile(file, MediaType.NOVEL)
                    }
                }
            }.onFailure { err ->
                snackbarHostState.showSnackbar(err.message ?: "Lỗi tải file")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(pageTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        CookieManager.getInstance().flush()
                        onBack()
                    }) { Icon(Icons.Default.Close, "Đóng") }
                },
                actions = {
                    IconButton(onClick = {
                        val wv = webViewRef
                        if (wv != null && wv.canGoBack()) wv.goBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Trang trước") }
                    IconButton(onClick = {
                        val wv = webViewRef
                        if (wv != null && wv.canGoForward()) wv.goForward()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Trang sau") }
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, "Tải lại")
                    }
                    IconButton(onClick = {
                        isDownloading = true
                        scope.launch {
                            CookieManager.getInstance().flush()
                            val res = container.directUrlImporter.importFromUrl(currentUrl, followNextMax = 1)
                            isDownloading = false
                            res.onSuccess {
                                snackbarHostState.showSnackbar("Đã nhập trang: ${it.title}")
                                onImportedBook(it.id)
                            }.onFailure { err ->
                                snackbarHostState.showSnackbar("Lỗi trích trang: ${err.message}")
                            }
                        }
                    }) { Icon(Icons.Default.Download, "Nhập trang này") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isDownloading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!url.isNullOrBlank()) currentUrl = url
                                pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: currentUrl
                                CookieManager.getInstance().flush()
                            }
                        }
                        setDownloadListener { url, ua, cd, mime, _ ->
                            handleWebDownload(url, ua, cd, mime)
                        }
                        webViewRef = this
                        loadUrl(currentUrl)
                    }
                }
            )
        }
    }

    pendingPdfFile?.let { pdfFile ->
        AlertDialog(
            onDismissRequest = {
                pdfFile.delete()
                pendingPdfFile = null
            },
            title = { Text("Chọn chế độ nhập PDF") },
            text = { Text("Tệp ${pdfFile.name} là sách chữ hay truyện tranh scan?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingPdfFile = null
                    importDownloadedFile(pdfFile, MediaType.NOVEL)
                }) { Text("Truyện chữ") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingPdfFile = null
                    importDownloadedFile(pdfFile, MediaType.COMIC)
                }) { Text("Truyện tranh") }
            }
        )
    }
}
