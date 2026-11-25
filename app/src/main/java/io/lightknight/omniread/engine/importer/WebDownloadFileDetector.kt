package io.lightknight.omniread.engine.importer

import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

object WebDownloadFileDetector {
    private val supportedDirectExts = setOf(
        "epub", "pdf", "txt", "html", "htm", "md", "markdown",
        "cbz", "cbr", "rar", "mp3", "m4a", "m4b", "opus", "ogg", "wav", "flac"
    )
    private val genericNames = setOf("download", "uc", "file", "get-link", "index", "attachment", "unknown")

    fun extractFileNameFromHeaders(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String? {
        if (!contentDisposition.isNullOrBlank()) {
            // 1. RFC 5987 filename*=UTF-8''...
            val starMatch = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)?'[^']*'([^;\r\n"]+)""")
                .find(contentDisposition)
            if (starMatch != null) {
                val decoded = runCatching { URLDecoder.decode(starMatch.groupValues[1].trim(), "UTF-8") }
                    .getOrDefault(starMatch.groupValues[1].trim())
                val sanitized = sanitizeFileName(decoded)
                if (sanitized.contains('.')) return sanitized
            }

            // 2. Standard filename="..." or filename=...
            val stdMatch = Regex("""filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;\r\n]+)""", RegexOption.IGNORE_CASE)
                .find(contentDisposition)
            if (stdMatch != null) {
                val raw = stdMatch.groupValues[1].ifBlank { stdMatch.groupValues[2] }.trim()
                val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                val sanitized = sanitizeFileName(decoded)
                if (sanitized.contains('.')) return sanitized
            }
        }

        // 3. URL path fallback when extension is explicitly recognized
        val urlPath = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val urlDecoded = runCatching { URLDecoder.decode(urlPath, "UTF-8") }.getOrDefault(urlPath)
        val ext = urlDecoded.substringAfterLast('.', "").lowercase()
        if (ext in supportedDirectExts || ext == "zip") {
            return sanitizeFileName(urlDecoded)
        }

        // 4. Known MIME type hint
        val cleanMime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val extFromMime = when (cleanMime) {
            "application/epub+zip" -> "epub"
            "application/pdf" -> "pdf"
            "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
            "application/vnd.comicbook-rar", "application/x-cbr" -> "cbr"
            "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/ogg", "audio/opus" -> "opus"
            "text/plain" -> "txt"
            else -> null
        }
        return extFromMime?.let { "download.$it" }
    }

    fun detectFormatByMagicBytes(file: File): String? {
        if (!file.exists() || !file.isFile || file.length() == 0L) return null
        val header = ByteArray(512)
        val read = file.inputStream().use { it.read(header) }
        if (read <= 0) return null

        val prefixStr = String(header, 0, read, Charsets.ISO_8859_1)
        if (prefixStr.startsWith("%PDF-")) return "pdf"

        // HTML redirect / countdown / challenge page saved accidentally as .bin
        val trimmedText = String(header, 0, read, Charsets.UTF_8).trimStart().lowercase()
        if (trimmedText.startsWith("<!doctype html") ||
            trimmedText.startsWith("<html") ||
            trimmedText.startsWith("<?xml") && trimmedText.contains("<html")
        ) {
            return "html_page"
        }

        // ZIP family: EPUB vs zip_bundle (nested epub/pdf/audio) vs CBZ (comic images)
        if (read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte())
        ) {
            return classifyZipArchive(file)
        }

        // RAR family (CBR)
        if (prefixStr.startsWith("Rar!")) return "cbr"

        // MP3 (ID3 tag or MPEG frame sync)
        if (prefixStr.startsWith("ID3") ||
            (read >= 2 && (header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0)
        ) {
            return "mp3"
        }

        // MP4 / M4A / M4B container
        if (read >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
        ) {
            return "m4a"
        }

        if (prefixStr.startsWith("OggS")) return "ogg"

        // Plain UTF-8 text without binary NUL bytes
        val slice = header.copyOf(read)
        if (!slice.contains(0.toByte())) {
            val nonPrintable = slice.count { b ->
                val v = b.toInt() and 0xFF
                v < 0x09 || (v in 0x0E..0x1F)
            }
            if (nonPrintable <= 2) return "txt"
        }
        return null
    }

    private fun classifyZipArchive(file: File): String {
        return runCatching {
            ZipFile(file).use { zf ->
                val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
                val namesLower = entries.map { it.name.lowercase() }
                if (namesLower.any { it == "meta-inf/container.xml" || it.endsWith(".opf") }) {
                    return@use "epub"
                }
                val mimeEntry = entries.firstOrNull { it.name.equals("mimetype", ignoreCase = true) }
                if (mimeEntry != null) {
                    val mime = zf.getInputStream(mimeEntry).bufferedReader().use { it.readText() }
                    if (mime.contains("epub", ignoreCase = true)) return@use "epub"
                }
                val bundleExts = setOf("epub", "pdf", "txt", "mp3", "m4a", "m4b", "opus", "ogg")
                if (namesLower.any { name -> name.substringAfterLast('.', "") in bundleExts }) {
                    return@use "zip_bundle"
                }
                if (namesLower.any { ImporterHelpers.isImageFileName(it) }) {
                    return@use "cbz"
                }
                "cbz"
            }
        }.getOrDefault("cbz")
    }

    fun renameWithDetectedExtension(file: File, fallbackTitle: String, detectedExt: String): File {
        val currentBase = file.nameWithoutExtension.lowercase()
        val chosenBase = if (currentBase in genericNames || currentBase.length <= 2) {
            sanitizeFileName(fallbackTitle).ifBlank { "omniread_book" }
        } else {
            sanitizeFileName(file.nameWithoutExtension)
        }
        val target = File(file.parentFile, "$chosenBase.$detectedExt")
        if (target.absolutePath == file.absolutePath) return file
        if (target.exists()) target.delete()
        return if (file.renameTo(target)) target else file
    }

    fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().take(120)
    }
}
