package io.lightknight.omniread.engine.scraper

import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.data.repository.LibraryRepository
import io.lightknight.omniread.data.storage.FileStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.net.URI
import java.util.UUID

data class ExtractedWebPage(
    val title: String,
    val mediaType: MediaType,
    val paragraphs: List<String> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val nextChapterUrl: String? = null
)

object DirectUrlExtractor {
    private val nextRegex = Regex("""(chương sau|chương tiếp|next\s*chapter|tiếp theo|next|»)""", RegexOption.IGNORE_CASE)

    fun extract(html: String, pageUrl: String): ExtractedWebPage {
        val doc = Jsoup.parse(html, pageUrl)
        val title = doc.selectFirst("h1, .chapter-title, title")?.text()?.trim().orEmpty()
            .ifBlank { "Trang web đã nhập" }

        val nextUrl = doc.select("a[href]")
            .firstOrNull { a -> nextRegex.containsMatchIn(a.text()) || a.attr("rel").equals("next", true) }
            ?.absUrl("href")
            ?.takeIf { it.startsWith("http") && it != pageUrl }

        // Remove obvious navigation/chrome before scoring
        doc.select("script, style, nav, footer, header, aside, .comment, .comments, .ads, .sidebar").remove()

        // Find candidate reading container with highest text density
        val candidates = doc.select("article, main, #chapter-c, #chapter-content, .chapter-content, .reading-content, .entry-content, div")
        val bestTextEl: Element? = candidates.maxByOrNull { el ->
            val textLen = el.select("p").sumOf { it.text().length }.takeIf { it > 0 } ?: el.ownText().length
            val linkTextLen = el.select("a").sumOf { it.text().length }
            (textLen - linkTextLen * 2).coerceAtLeast(0)
        }

        val paragraphs = bestTextEl?.let { el ->
            val ps = el.select("p").map { it.text().trim() }.filter { it.length > 2 }
            if (ps.size >= 3) ps else el.text().split(Regex("\n+")).map { it.trim() }.filter { it.length > 2 }
        }.orEmpty()

        val totalChars = paragraphs.sumOf { it.length }
        val comicImages = doc.select(".reading-content img, .page-break img, article img, main img, img")
            .mapNotNull { img ->
                img.absUrl("data-src").ifBlank { img.absUrl("src") }
                    .takeIf { u -> u.startsWith("http") && !u.contains("avatar", true) && !u.contains("logo", true) }
            }
            .distinct()

        return if (totalChars >= 250 || comicImages.size < 4) {
            ExtractedWebPage(
                title = title,
                mediaType = MediaType.NOVEL,
                paragraphs = paragraphs.ifEmpty { listOf(doc.body().text()) },
                nextChapterUrl = nextUrl
            )
        } else {
            ExtractedWebPage(
                title = title,
                mediaType = MediaType.COMIC,
                imageUrls = comicImages,
                nextChapterUrl = nextUrl
            )
        }
    }
}

class DirectUrlImporter(
    private val bridge: PluginHttpBridge,
    private val storage: FileStorageManager,
    private val repository: LibraryRepository
) {
    suspend fun importFromUrl(url: String, followNextMax: Int = 1): Result<LibraryItemEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val itemId = UUID.randomUUID().toString()
                val chapters = mutableListOf<ChapterEntity>()
                var currentUrl: String? = url.trim()
                var bookTitle = ""
                var detectedType = MediaType.NOVEL
                val visited = mutableSetOf<String>()

                while (currentUrl != null && chapters.size < followNextMax && currentUrl !in visited) {
                    visited += currentUrl
                    val html = bridge.httpGet(currentUrl)
                    val extracted = DirectUrlExtractor.extract(html, currentUrl)
                    if (bookTitle.isBlank()) {
                        bookTitle = extracted.title
                        detectedType = extracted.mediaType
                    }

                    val chIdx = chapters.size
                    val chId = UUID.randomUUID().toString()
                    if (extracted.mediaType == MediaType.NOVEL) {
                        val file = File(storage.itemChaptersDir(itemId), "ch_%04d.txt".format(chIdx))
                        file.writeText(extracted.paragraphs.joinToString("\n\n"), Charsets.UTF_8)
                        chapters += ChapterEntity(
                            id = chId,
                            itemId = itemId,
                            chapterIndex = chIdx,
                            title = extracted.title,
                            remoteUrl = currentUrl,
                            localFilePath = file.absolutePath,
                            contentSha256 = HashUtils.sha256(file),
                            downloadState = DownloadState.DOWNLOADED
                        )
                    } else {
                        val chDir = File(storage.itemChaptersDir(itemId), "ch_%04d".format(chIdx)).apply { mkdirs() }
                        // Save image URL list as manifest or download directly via OkHttp in worker; here record remote URLs
                        val metaFile = File(chDir, "pages.txt")
                        metaFile.writeText(extracted.imageUrls.joinToString("\n"), Charsets.UTF_8)
                        chapters += ChapterEntity(
                            id = chId,
                            itemId = itemId,
                            chapterIndex = chIdx,
                            title = extracted.title,
                            remoteUrl = currentUrl,
                            localFilePath = chDir.absolutePath,
                            pageCount = extracted.imageUrls.size,
                            downloadState = DownloadState.REMOTE
                        )
                    }
                    currentUrl = extracted.nextChapterUrl
                }

                val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
                val item = LibraryItemEntity(
                    id = itemId,
                    title = bookTitle.ifBlank { host },
                    author = host,
                    mediaType = detectedType,
                    originType = OriginType.DIRECT_URL,
                    sourceUrl = url,
                    localDirPath = storage.itemDir(itemId).absolutePath,
                    contentSha256 = HashUtils.sha256(url)
                )
                repository.saveImportedBook(item, chapters)
                item
            }
        }
}
