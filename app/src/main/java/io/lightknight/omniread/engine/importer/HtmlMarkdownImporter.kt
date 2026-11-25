package io.lightknight.omniread.engine.importer

import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.data.storage.FileStorageManager
import org.jsoup.Jsoup
import java.io.InputStream
import java.util.UUID

object HtmlMarkdownImporter {
    fun import(input: InputStream, fileName: String, storage: FileStorageManager): ImportOutcome {
        val bytes = input.readBytes()
        val raw = TxtImporter.decodeBytes(bytes)
        val isHtml = fileName.lowercase().let { it.endsWith(".html") || it.endsWith(".htm") }

        val title: String
        val chapters: List<ParsedTextChapter>

        if (isHtml) {
            val doc = Jsoup.parse(raw)
            title = doc.title().trim().ifBlank { ImporterHelpers.cleanTitleFromFileName(fileName) }
            doc.select("script, style, nav, footer, header").remove()
            val paragraphs = doc.select("h1, h2, h3, p, li")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
            val joined = if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else doc.body().text()
            chapters = TxtImporter.splitIntoChapters(joined)
        } else {
            // Markdown: strip basic markdown heading hashes for clean reading/TTS
            title = ImporterHelpers.cleanTitleFromFileName(fileName)
            val cleaned = raw.lines().joinToString("\n") { line ->
                line.replace(Regex("^#{1,6}\\s+"), "")
            }
            chapters = TxtImporter.splitIntoChapters(cleaned)
        }

        val itemId = UUID.randomUUID().toString()
        val chapterEntities = ImporterHelpers.writeTextChapters(storage, itemId, chapters)
        val item = LibraryItemEntity(
            id = itemId,
            title = title,
            mediaType = MediaType.NOVEL,
            originType = OriginType.LOCAL_IMPORT,
            localDirPath = storage.itemDir(itemId).absolutePath,
            contentSha256 = HashUtils.sha256(bytes)
        )
        return ImportOutcome(item, chapterEntities)
    }
}
