package io.lightknight.omniread.engine.scraper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.lightknight.omniread.OmniReadApp
import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.data.repository.LibraryRepository
import io.lightknight.omniread.data.storage.FileStorageManager
import io.lightknight.omniread.engine.importer.LocalImportManager
import io.lightknight.omniread.engine.importer.MegaPublicDownloader
import io.lightknight.omniread.engine.importer.WebDownloadFileDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

class WebChapterDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val pluginRunner: QuickJsPluginRunner,
    private val repository: LibraryRepository,
    private val storage: FileStorageManager,
    private val localImportManager: LocalImportManager? = null,
    private val megaDownloader: MegaPublicDownloader? = null,
    private val userAgent: String = OkHttpPluginBridge.DEFAULT_MOBILE_UA
) {
    suspend fun addRemoteBookToLibrary(
        pluginId: String,
        detail: RemoteBookDetail,
        chapters: List<RemoteChapter>,
        fallbackCoverUrl: String = ""
    ): LibraryItemEntity {
        val existing = repository.findBySource(pluginId, detail.url)
        val itemId = existing?.id ?: UUID.randomUUID().toString()
        val mediaType = if (detail.mediaType.equals("COMIC", ignoreCase = true)) MediaType.COMIC else MediaType.NOVEL
        val plugin = (context.applicationContext as OmniReadApp).container.database
            .sourcePluginDao().getById(pluginId)
        val referer = plugin?.baseUrl?.trimEnd('/')?.plus("/")
            ?: deriveOriginReferer(detail.url)

        val effectiveCoverUrl = detail.coverUrl.ifBlank { fallbackCoverUrl }
        val existingCoverOk = existing?.coverLocalPath?.let(::File)?.exists() == true
        val coverPath = if (existingCoverOk) {
            existing?.coverLocalPath
        } else {
            downloadCover(itemId, effectiveCoverUrl, referer)
        }

        val item = (existing ?: LibraryItemEntity(
            id = itemId,
            title = detail.title,
            author = detail.author,
            synopsis = detail.synopsis,
            coverLocalPath = coverPath,
            mediaType = mediaType,
            originType = OriginType.WEB_PLUGIN,
            sourcePluginId = pluginId,
            sourceUrl = detail.url,
            localDirPath = storage.itemDir(itemId).absolutePath
        )).copy(
            title = detail.title,
            author = detail.author,
            synopsis = detail.synopsis,
            coverLocalPath = coverPath,
            updatedAt = System.currentTimeMillis()
        )

        val existingByUrl = repository.getChapters(itemId).associateBy { it.remoteUrl }
        val chapterEntities = chapters.mapIndexed { idx, rc ->
            existingByUrl[rc.url]?.copy(chapterIndex = idx, title = rc.title) ?: ChapterEntity(
                id = UUID.randomUUID().toString(),
                itemId = itemId,
                chapterIndex = idx,
                title = rc.title,
                remoteUrl = rc.url,
                downloadState = DownloadState.REMOTE
            )
        }
        repository.saveImportedBook(item, chapterEntities)
        return item
    }

    suspend fun ensureChapterDownloaded(chapter: ChapterEntity, forceReload: Boolean = false): File? =
        withContext(Dispatchers.IO) {
            if (!forceReload) {
                val cached = chapter.localFilePath?.let(::File)?.takeIf { it.exists() }
                if (cached != null) {
                    // Re-fetch novel chapters that were previously cached with empty/title-only text
                    if (cached.isDirectory || cached.length() >= 80L) {
                        return@withContext cached
                    }
                }
            }
            val item = repository.getItem(chapter.itemId) ?: return@withContext null
            val pluginId = item.sourcePluginId ?: return@withContext null
            val remoteUrl = chapter.remoteUrl ?: return@withContext null
            val plugin = (context.applicationContext as OmniReadApp).container.database
                .sourcePluginDao().getById(pluginId) ?: return@withContext null

            repository.markChapterState(chapter.id, DownloadState.DOWNLOADING)
            val payload = pluginRunner.getChapterContent(plugin, remoteUrl).getOrElse {
                repository.markChapterState(chapter.id, DownloadState.FAILED)
                return@withContext null
            }

            return@withContext if (payload.kind == "NOVEL") {
                val text = payload.paragraphs.joinToString("\n\n").trim()
                if (text.isBlank()) {
                    repository.markChapterState(chapter.id, DownloadState.FAILED)
                    return@withContext null
                }
                val file = File(storage.itemChaptersDir(item.id), "ch_%04d.txt".format(chapter.chapterIndex))
                file.writeText(text, Charsets.UTF_8)
                repository.markChapterDownloaded(chapter.id, file.absolutePath, HashUtils.sha256(file))
                file
            } else {
            val chDir = File(storage.itemChaptersDir(item.id), "ch_%04d".format(chapter.chapterIndex)).apply { mkdirs() }
            payload.images.forEachIndexed { idx, img ->
                val ext = img.url.substringAfterLast('.', "jpg").substringBefore('?').take(4).ifBlank { "jpg" }
                val dest = File(chDir, "p_%04d.%s".format(idx, ext))
                if (!dest.exists() || dest.length() == 0L) {
                    downloadBinary(img.url, img.referer, dest)
                }
            }
            val updated = chapter.copy(
                localFilePath = chDir.absolutePath,
                pageCount = payload.images.size,
                contentSha256 = HashUtils.sha256("${chapter.id}:${payload.images.size}"),
                downloadState = DownloadState.DOWNLOADED,
                updatedAt = System.currentTimeMillis()
            )
            repository.upsertChapters(listOf(updated))
            repository.enqueueSyncJob("CHAPTER_FILE", chapter.id, "UPLOAD_FILE")
            chDir
        }
    }

    fun enqueueBatchDownload(itemId: String) {
        val data = Data.Builder().putString(WebChapterDownloadWorker.KEY_ITEM_ID, itemId).build()
        val req = OneTimeWorkRequestBuilder<WebChapterDownloadWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "web_dl_$itemId",
            ExistingWorkPolicy.KEEP,
            req
        )
    }

    suspend fun importEbookFromDownloadUrl(
        downloadUrl: String,
        fallbackTitle: String = "",
        fallbackAuthor: String = "",
        fallbackCoverUrl: String = "",
        referer: String = "",
        replaceExistingItemId: String? = null
    ): Result<LibraryItemEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val importer = localImportManager
                ?: throw IllegalStateException("Bộ nhập tệp cục bộ chưa sẵn sàng.")
            val downloadedFile = downloadEbookFileToTemp(downloadUrl, fallbackTitle, referer)
            try {
                val outcome = importer.importLocalFile(downloadedFile, MediaType.NOVEL).getOrThrow()
                var savedItem = outcome.item
                val cleanedTitle = fallbackTitle.trim()
                val looksLikePortalFilename = savedItem.title.contains("[downloadsachmienphi", ignoreCase = true) ||
                    savedItem.title.startsWith("mega_", ignoreCase = true)
                val targetTitle = if (cleanedTitle.isNotBlank() && (looksLikePortalFilename || savedItem.title.isBlank())) {
                    cleanedTitle
                } else {
                    savedItem.title
                }
                val coverPath = if (savedItem.coverLocalPath?.let(::File)?.exists() == true) {
                    savedItem.coverLocalPath
                } else if (fallbackCoverUrl.isNotBlank()) {
                    downloadCover(savedItem.id, fallbackCoverUrl, referer)
                } else {
                    null
                }
                val finalAuthor = savedItem.author.ifBlank { fallbackAuthor }
                if (targetTitle != savedItem.title || coverPath != savedItem.coverLocalPath || finalAuthor != savedItem.author) {
                    savedItem = savedItem.copy(
                        title = targetTitle,
                        author = finalAuthor,
                        coverLocalPath = coverPath,
                        updatedAt = System.currentTimeMillis()
                    )
                    (context.applicationContext as OmniReadApp).container.database.libraryDao().upsert(savedItem)
                }
                if (!replaceExistingItemId.isNullOrBlank() && replaceExistingItemId != savedItem.id) {
                    repository.deleteBook(replaceExistingItemId)
                }
                savedItem
            } finally {
                downloadedFile.delete()
            }
        }
    }

    private suspend fun downloadEbookFileToTemp(url: String, fallbackTitle: String, referer: String): File {
        if (MegaPublicDownloader.parseMegaLink(url) != null) {
            val mega = megaDownloader
                ?: throw IllegalStateException("Trình tải MEGA chưa được khởi tạo.")
            return mega.downloadEbook(url, storage.inboxTempDir).getOrThrow()
        }

        val resolvedUrl = resolveDirectCloudUrl(url)
        val req = Request.Builder()
            .url(resolvedUrl)
            .header("User-Agent", userAgent)
            .apply { if (referer.isNotBlank()) header("Referer", referer) }
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Tải Ebook thất bại (HTTP ${resp.code}).")
            }
            val cdHeader = resp.header("Content-Disposition")
            val mimeType = resp.header("Content-Type")?.substringBefore(';')?.trim()
            val guessed = WebDownloadFileDetector.extractFileNameFromHeaders(resolvedUrl, cdHeader, mimeType)
                ?: "${fallbackTitle.ifBlank { "ebook" }}.bin"
            val tempFile = File(
                storage.inboxTempDir,
                "dl_${UUID.randomUUID().toString().take(6)}_${WebDownloadFileDetector.sanitizeFileName(guessed)}"
            )
            tempFile.parentFile?.mkdirs()
            resp.body?.byteStream()?.use { ins ->
                tempFile.outputStream().use { out -> ins.copyTo(out, 16 * 1024) }
            } ?: throw IllegalStateException("Tệp Ebook tải về bị rỗng.")
            val detected = WebDownloadFileDetector.detectFormatByMagicBytes(tempFile)
            if (detected == "html_page") {
                tempFile.delete()
                throw IllegalStateException("Liên kết trả về trang web trung gian, không phải tệp EPUB/PDF trực tiếp.")
            }
            return if (detected != null && detected != "zip_bundle" &&
                tempFile.extension.lowercase() !in setOf("epub", "pdf", "cbz", "cbr", "txt", "mp3", "m4a", "m4b")
            ) {
                WebDownloadFileDetector.renameWithDetectedExtension(tempFile, fallbackTitle, detected)
            } else {
                tempFile
            }
        }
    }

    private fun resolveDirectCloudUrl(url: String): String {
        val driveMatch = Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]+)""").find(url)
        return if (driveMatch != null) {
            "https://drive.google.com/uc?export=download&id=${driveMatch.groupValues[1]}"
        } else {
            url
        }
    }

    suspend fun repairMissingCover(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext as OmniReadApp
        val item = repository.getItem(itemId) ?: return@withContext false
        if (item.coverLocalPath?.let(::File)?.exists() == true) return@withContext false
        val pluginId = item.sourcePluginId ?: return@withContext false
        val sourceUrl = item.sourceUrl ?: return@withContext false
        val plugin = app.container.database.sourcePluginDao().getById(pluginId) ?: return@withContext false

        val detail = pluginRunner.getDetail(plugin, sourceUrl).getOrNull() ?: return@withContext false
        if (detail.coverUrl.isBlank()) return@withContext false
        val referer = plugin.baseUrl.trimEnd('/').plus("/")
        val downloaded = downloadCover(item.id, detail.coverUrl, referer) ?: return@withContext false
        app.container.database.libraryDao().upsert(
            item.copy(coverLocalPath = downloaded, updatedAt = System.currentTimeMillis())
        )
        true
    }

    suspend fun refreshWebChapters(itemId: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val item = repository.getItem(itemId)
                ?: throw IllegalStateException("Không tìm thấy sách trong thư viện.")
            val pluginId = item.sourcePluginId
                ?: throw IllegalStateException("Sách này không thuộc nguồn web plugin.")
            val sourceUrl = item.sourceUrl
                ?: throw IllegalStateException("Thiếu đường dẫn nguồn gốc của sách.")
            val plugin = (context.applicationContext as OmniReadApp).container.database
                .sourcePluginDao().getById(pluginId)
                ?: throw IllegalStateException("Không tìm thấy plugin nguồn ($pluginId).")

            val detail = pluginRunner.getDetail(plugin, sourceUrl).getOrNull()
            val remoteChapters = pluginRunner.getChapters(plugin, sourceUrl).getOrThrow()
            if (remoteChapters.isEmpty()) {
                throw IllegalStateException("Nguồn web hiện trả về 0 chương. Hãy thử 'Mở Trình duyệt nguồn' để kiểm tra trang web.")
            }

            val existingByUrl = repository.getChapters(itemId).associateBy { it.remoteUrl }
            val mergedChapters = remoteChapters.mapIndexed { idx, rc ->
                existingByUrl[rc.url]?.copy(chapterIndex = idx, title = rc.title) ?: ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    itemId = itemId,
                    chapterIndex = idx,
                    title = rc.title,
                    remoteUrl = rc.url,
                    downloadState = DownloadState.REMOTE
                )
            }

            val existingAuthorBad = item.author.isBlank() ||
                item.author.equals("Đã hoàn thành", ignoreCase = true) ||
                item.author.equals("Đang tiến hành", ignoreCase = true)
            val newAuthor = if (existingAuthorBad && detail != null && detail.author.isNotBlank()) {
                detail.author
            } else {
                item.author
            }
            val newSynopsis = if (item.synopsis.isBlank() && detail != null) detail.synopsis else item.synopsis
            val newCover = if (item.coverLocalPath?.let(::File)?.exists() == true) {
                item.coverLocalPath
            } else {
                val urlToFetch = detail?.coverUrl.orEmpty()
                if (urlToFetch.isNotBlank()) downloadCover(item.id, urlToFetch, plugin.baseUrl.trimEnd('/') + "/") else item.coverLocalPath
            }

            val updatedItem = item.copy(
                author = newAuthor,
                synopsis = newSynopsis,
                coverLocalPath = newCover,
                updatedAt = System.currentTimeMillis()
            )
            repository.saveImportedBook(updatedItem, mergedChapters)
            mergedChapters.size
        }
    }

    private fun deriveOriginReferer(url: String): String {
        return runCatching {
            val uri = java.net.URI(url)
            if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                "${uri.scheme}://${uri.host}/"
            } else ""
        }.getOrDefault("")
    }

    private fun downloadCover(itemId: String, coverUrl: String, referer: String): String? {
        if (!coverUrl.startsWith("http")) return null
        val dest = storage.coverFile(itemId, "jpg")
        return if (downloadBinary(coverUrl, referer, dest)) dest.absolutePath else null
    }

    private fun downloadBinary(url: String, referer: String, dest: File): Boolean {
        return runCatching {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
            if (referer.isNotBlank()) builder.header("Referer", referer)
            httpClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val bytes = resp.body?.bytes() ?: return false
                if (bytes.isEmpty()) return false
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
                true
            }
        }.getOrDefault(false)
    }
}

class WebChapterDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val container = (applicationContext as OmniReadApp).container
        val item = container.libraryRepository.getItem(itemId) ?: return Result.failure()
        val pending = container.libraryRepository.getChapters(itemId)
            .filter { it.downloadState != DownloadState.DOWNLOADED }

        setForeground(createForegroundInfo(item.title, 0, pending.size.coerceAtLeast(1)))

        pending.forEachIndexed { idx, ch ->
            if (isStopped) return Result.success()
            container.webChapterDownloader.ensureChapterDownloaded(ch)
            setForeground(createForegroundInfo(item.title, idx + 1, pending.size))
            delay(700L) // Polite delay between chapter requests
        }
        return Result.success()
    }

    private fun createForegroundInfo(title: String, done: Int, total: Int): ForegroundInfo {
        val channelId = "omniread_downloads"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Tải truyện & Sách nói", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Đang tải: $title")
            .setContentText("$done / $total chương")
            .setProgress(total, done, false)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(itemIdHash(title), notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(itemIdHash(title), notif)
        }
    }

    private fun itemIdHash(s: String) = (s.hashCode() and 0x7FFF) + 2000

    companion object {
        const val KEY_ITEM_ID = "item_id"
    }
}
