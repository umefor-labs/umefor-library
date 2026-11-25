package io.lightknight.omniread.engine.importer

import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.data.storage.FileStorageManager
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.UUID
import java.util.zip.ZipFile

object EpubImporter {
    fun import(input: InputStream, fileName: String, storage: FileStorageManager): ImportOutcome {
        val itemId = UUID.randomUUID().toString()
        val tempEpub = File(storage.itemDir(itemId), "source.epub")
        ImporterHelpers.copyInputToFile(input, tempEpub)
        val fileHash = HashUtils.sha256(tempEpub)

        val zip = ZipFile(tempEpub)
        zip.use { zf ->
            val containerXml = readZipText(zf, "META-INF/container.xml")
                ?: throw IllegalArgumentException("EPUB không hợp lệ: thiếu META-INF/container.xml")
            val containerDoc = Jsoup.parse(containerXml, "", Parser.xmlParser())
            val opfPath = containerDoc.selectFirst("rootfile")?.attr("full-path")
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("EPUB không hợp lệ: không tìm thấy tệp OPF")

            val opfDir = opfPath.substringBeforeLast('/', "").let { if (it == opfPath) "" else it }
            val opfXml = readZipText(zf, opfPath)
                ?: throw IllegalArgumentException("Không đọc được OPF: $opfPath")
            val opfDoc = Jsoup.parse(opfXml, "", Parser.xmlParser())

            val title = opfDoc.selectFirst("metadata > dc|title, metadata > title")?.text()?.trim()
                ?.ifBlank { null } ?: ImporterHelpers.cleanTitleFromFileName(fileName)
            val author = opfDoc.selectFirst("metadata > dc|creator, metadata > creator")?.text()?.trim().orEmpty()
            val synopsis = opfDoc.selectFirst("metadata > dc|description, metadata > description")?.text()?.trim().orEmpty()

            val manifestById = mutableMapOf<String, ManifestEntry>()
            opfDoc.select("manifest > item").forEach { el ->
                val id = el.attr("id")
                val href = decodeHref(el.attr("href"))
                val mediaType = el.attr("media-type")
                val props = el.attr("properties")
                if (id.isNotBlank() && href.isNotBlank()) {
                    manifestById[id] = ManifestEntry(
                        id = id,
                        fullPath = resolveRelative(opfDir, href),
                        mediaType = mediaType,
                        properties = props
                    )
                }
            }

            // Extract cover image if declared in metadata or manifest
            val coverId = opfDoc.selectFirst("metadata > meta[name=cover]")?.attr("content")
            val coverEntry = manifestById[coverId]
                ?: manifestById.values.firstOrNull { it.properties.contains("cover-image") }
                ?: manifestById.values.firstOrNull { it.mediaType.startsWith("image/") && it.id.contains("cover", ignoreCase = true) }

            var coverLocalPath: String? = null
            if (coverEntry != null) {
                val entry = findZipEntry(zf, coverEntry.fullPath)
                if (entry != null) {
                    val ext = coverEntry.fullPath.substringAfterLast('.', "jpg")
                    val coverDest = storage.coverFile(itemId, ext)
                    zf.getInputStream(entry).use { cis -> ImporterHelpers.copyInputToFile(cis, coverDest) }
                    coverLocalPath = coverDest.absolutePath
                }
            }

            // Parse NCX / NAV TOC for chapter titles keyed by normalized zip path
            val tocTitles = extractTocTitles(zf, opfDoc, manifestById, opfDir)

            // Follow reading spine order
            val spineIds = opfDoc.select("spine > itemref").map { it.attr("idref") }
            val parsedChapters = mutableListOf<ParsedTextChapter>()
            for (idref in spineIds) {
                val item = manifestById[idref] ?: continue
                if (!item.mediaType.contains("html") && !item.mediaType.contains("xml")) continue
                val htmlText = readZipText(zf, item.fullPath) ?: continue
                val doc = Jsoup.parse(htmlText)
                doc.select("script, style").remove()

                val paragraphs = extractCleanParagraphs(doc)
                if (paragraphs.isBlank()) continue

                val headingTitle = tocTitles[item.fullPath]
                    ?: doc.selectFirst("h1, h2, h3, title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Chương ${parsedChapters.size + 1}"

                parsedChapters += ParsedTextChapter(title = headingTitle, content = paragraphs)
            }

            val finalChapters = if (parsedChapters.isEmpty()) {
                listOf(ParsedTextChapter("Nội dung", "Không tìm thấy văn bản đọc được trong EPUB."))
            } else {
                parsedChapters
            }

            val chapterEntities = ImporterHelpers.writeTextChapters(storage, itemId, finalChapters)
            val libraryItem = LibraryItemEntity(
                id = itemId,
                title = title,
                author = author,
                synopsis = synopsis,
                coverLocalPath = coverLocalPath,
                mediaType = MediaType.NOVEL,
                originType = OriginType.LOCAL_IMPORT,
                localDirPath = storage.itemDir(itemId).absolutePath,
                contentSha256 = fileHash
            )
            return ImportOutcome(libraryItem, chapterEntities)
        }
    }

    private data class ManifestEntry(
        val id: String,
        val fullPath: String,
        val mediaType: String,
        val properties: String
    )

    private fun extractTocTitles(
        zf: ZipFile,
        opfDoc: org.jsoup.nodes.Document,
        manifestById: Map<String, ManifestEntry>,
        opfDir: String
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // EPUB 3 nav
        val navEntry = manifestById.values.firstOrNull { it.properties.contains("nav") }
        if (navEntry != null) {
            val navDir = navEntry.fullPath.substringBeforeLast('/', "").let { if (it == navEntry.fullPath) "" else it }
            readZipText(zf, navEntry.fullPath)?.let { navHtml ->
                val navDoc = Jsoup.parse(navHtml)
                navDoc.select("nav[epub:type=toc] a, nav a").forEach { a ->
                    val href = decodeHref(a.attr("href")).substringBefore('#')
                    val label = a.text().trim()
                    if (href.isNotBlank() && label.isNotBlank()) {
                        result.putIfAbsent(resolveRelative(navDir, href), label)
                    }
                }
            }
        }
        // EPUB 2 NCX fallback
        val tocId = opfDoc.selectFirst("spine")?.attr("toc")
        val ncxEntry = tocId?.let { manifestById[it] }
            ?: manifestById.values.firstOrNull { it.mediaType.contains("ncx") }
        if (ncxEntry != null) {
            val ncxDir = ncxEntry.fullPath.substringBeforeLast('/', "").let { if (it == ncxEntry.fullPath) "" else it }
            readZipText(zf, ncxEntry.fullPath)?.let { ncxXml ->
                val ncxDoc = Jsoup.parse(ncxXml, "", Parser.xmlParser())
                ncxDoc.select("navPoint").forEach { np ->
                    val label = np.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
                    val src = decodeHref(np.selectFirst("content")?.attr("src").orEmpty()).substringBefore('#')
                    if (label.isNotBlank() && src.isNotBlank()) {
                        result.putIfAbsent(resolveRelative(ncxDir, src), label)
                    }
                }
            }
        }
        return result
    }

    private fun extractCleanParagraphs(doc: org.jsoup.nodes.Document): String {
        val blocks = doc.body()?.select("p, h1, h2, h3, h4, blockquote, li")
            ?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        return if (blocks.isNotEmpty()) {
            blocks.joinToString("\n\n")
        } else {
            doc.body()?.text()?.trim().orEmpty()
        }
    }

    private fun readZipText(zf: ZipFile, path: String): String? {
        val entry = findZipEntry(zf, path) ?: return null
        return zf.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun findZipEntry(zf: ZipFile, path: String) =
        zf.getEntry(path) ?: zf.entries().asSequence().firstOrNull { it.name.equals(path, ignoreCase = true) }

    private fun decodeHref(href: String): String =
        runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)

    private fun resolveRelative(baseDir: String, relPath: String): String {
        val combined = if (baseDir.isBlank()) relPath else "$baseDir/$relPath"
        val parts = mutableListOf<String>()
        for (seg in combined.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }
}
