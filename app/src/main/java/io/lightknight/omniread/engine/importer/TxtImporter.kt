package io.lightknight.omniread.engine.importer

import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.data.storage.FileStorageManager
import java.io.InputStream
import java.nio.charset.Charset
import java.util.UUID

object TxtImporter {
    private val chapterHeadingRegex = Regex(
        """^(chương|hồi|phần|quyển|tập|chapter|part|book|prologue|epilogue|mở đầu|ngoại truyện)\b.*$""",
        setOf(RegexOption.IGNORE_CASE)
    )

    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // Validate strict UTF-8; fallback to Windows-1258 / ISO-8859-1 if invalid bytes exist
        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching {
            utf8Decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrElse {
            val cp1258 = runCatching { Charset.forName("windows-1258") }.getOrDefault(Charsets.ISO_8859_1)
            String(bytes, cp1258)
        }
    }

    fun splitIntoChapters(rawText: String, fallbackChunkChars: Int = 9000): List<ParsedTextChapter> {
        val normalized = rawText.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")
        val chapters = mutableListOf<ParsedTextChapter>()

        var currentTitle = ""
        val currentBody = StringBuilder()

        fun flushCurrent() {
            val body = currentBody.toString().trim()
            if (body.isNotEmpty() || currentTitle.isNotEmpty()) {
                val title = currentTitle.ifBlank {
                    if (chapters.isEmpty()) "Mở đầu" else "Phần ${chapters.size + 1}"
                }
                chapters += ParsedTextChapter(title = title, content = body.ifEmpty { title })
            }
            currentBody.clear()
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.length in 3..90 && chapterHeadingRegex.matches(trimmed)) {
                if (currentBody.isNotBlank() || currentTitle.isNotBlank()) {
                    flushCurrent()
                }
                currentTitle = trimmed
            } else {
                currentBody.appendLine(line)
            }
        }
        flushCurrent()

        // If no chapter headings found and file is large, split on paragraph boundaries
        if (chapters.size == 1 && chapters[0].content.length > fallbackChunkChars) {
            return splitByLength(chapters[0].content, fallbackChunkChars)
        }
        return chapters.ifEmpty { listOf(ParsedTextChapter("Toàn văn", normalized.trim())) }
    }

    private fun splitByLength(text: String, maxChars: Int): List<ParsedTextChapter> {
        val paragraphs = text.split(Regex("\n{2,}"))
        val result = mutableListOf<ParsedTextChapter>()
        val buf = StringBuilder()
        for (p in paragraphs) {
            if (buf.length + p.length > maxChars && buf.isNotEmpty()) {
                result += ParsedTextChapter("Phần ${result.size + 1}", buf.toString().trim())
                buf.clear()
            }
            buf.append(p.trim()).append("\n\n")
        }
        if (buf.isNotBlank()) {
            result += ParsedTextChapter("Phần ${result.size + 1}", buf.toString().trim())
        }
        return result
    }

    fun import(input: InputStream, fileName: String, storage: FileStorageManager): ImportOutcome {
        val bytes = input.readBytes()
        val text = decodeBytes(bytes)
        val itemId = UUID.randomUUID().toString()
        val parsed = splitIntoChapters(text)
        val chapters = ImporterHelpers.writeTextChapters(storage, itemId, parsed)
        val item = LibraryItemEntity(
            id = itemId,
            title = ImporterHelpers.cleanTitleFromFileName(fileName),
            mediaType = MediaType.NOVEL,
            originType = OriginType.LOCAL_IMPORT,
            localDirPath = storage.itemDir(itemId).absolutePath,
            contentSha256 = HashUtils.sha256(bytes)
        )
        return ImportOutcome(item, chapters)
    }
}
