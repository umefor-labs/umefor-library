package io.lightknight.omniread.engine.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.lightknight.omniread.core.NaturalSort
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.repository.LibraryRepository
import io.lightknight.omniread.data.storage.FileStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class LocalImportManager(
    private val context: Context,
    private val storage: FileStorageManager,
    private val repository: LibraryRepository
) {
    private val directKnownExts = setOf(
        "epub", "txt", "html", "htm", "md", "markdown",
        "pdf", "cbz", "cbr", "rar",
        "mp3", "m4a", "m4b", "opus", "ogg", "wav", "flac"
    )

    suspend fun importFileUri(uri: Uri, pdfMode: MediaType = MediaType.NOVEL): Result<ImportOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                val fileName = resolveFileName(uri)
                val temp = File(storage.inboxTempDir, "uri_${UUID.randomUUID()}_${WebDownloadFileDetector.sanitizeFileName(fileName)}")
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ImporterHelpers.copyInputToFile(stream, temp)
                } ?: throw IllegalStateException("Không thể mở tệp đã chọn.")
                try {
                    importLocalFile(temp, pdfMode).getOrThrow()
                } finally {
                    temp.delete()
                }
            }
        }

    suspend fun importLocalFile(file: File, pdfMode: MediaType = MediaType.NOVEL): Result<ImportOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rawExt = file.extension.lowercase()
                val effectiveExt = if (rawExt in directKnownExts) {
                    rawExt
                } else {
                    WebDownloadFileDetector.detectFormatByMagicBytes(file) ?: rawExt
                }

                if (effectiveExt == "html_page") {
                    throw IllegalArgumentException(
                        "Liên kết trả về trang web chuyển tiếp chứ không phải tệp sách. Hãy bấm trực tiếp vào nút tải tệp trên trang."
                    )
                }

                if (effectiveExt == "zip_bundle") {
                    return@runCatching importZipBundle(file, pdfMode)
                }

                val effectiveName = if (rawExt == effectiveExt || effectiveExt.isBlank()) {
                    file.name
                } else {
                    "${file.nameWithoutExtension}.$effectiveExt"
                }

                val outcome = file.inputStream().use { stream ->
                    when (effectiveExt) {
                        "epub" -> EpubImporter.import(stream, effectiveName, storage)
                        "txt" -> TxtImporter.import(stream, effectiveName, storage)
                        "html", "htm", "md", "markdown" -> HtmlMarkdownImporter.import(stream, effectiveName, storage)
                        "pdf" -> if (pdfMode == MediaType.COMIC) {
                            PdfComicImporter.import(stream, effectiveName, storage)
                        } else {
                            PdfNovelImporter.import(context, stream, effectiveName, storage)
                        }
                        "cbz", "zip" -> CbzImporter.import(stream, effectiveName, storage)
                        "cbr", "rar" -> CbrImporter.import(stream, effectiveName, storage)
                        "mp3", "m4a", "m4b", "opus", "ogg", "wav", "flac" ->
                            AudioImporter.importSingleFile(stream, effectiveName, storage)
                        else -> throw IllegalArgumentException("Định dạng .$effectiveExt chưa được hỗ trợ.")
                    }
                }
                repository.saveImportedBook(outcome.item, outcome.chapters)
                outcome
            }
        }

    private suspend fun importZipBundle(zipFile: File, pdfMode: MediaType): ImportOutcome {
        val unpackDir = File(storage.inboxTempDir, "zip_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val extracted = mutableListOf<File>()
            ZipFile(zipFile).use { zf ->
                zf.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                    val name = entry.name.substringAfterLast('/')
                    if (name.isNotBlank() && !entry.name.startsWith("__MACOSX")) {
                        val dest = File(unpackDir, WebDownloadFileDetector.sanitizeFileName(name))
                        zf.getInputStream(entry).use { ins -> ImporterHelpers.copyInputToFile(ins, dest) }
                        extracted += dest
                    }
                }
            }

            val audioFiles = extracted.filter { ImporterHelpers.isAudioFileName(it.name) }
                .sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }
            if (audioFiles.isNotEmpty()) {
                val outcome = AudioImporter.importLocalFiles(
                    files = audioFiles,
                    bookTitle = ImporterHelpers.cleanTitleFromFileName(zipFile.name),
                    storage = storage
                )
                repository.saveImportedBook(outcome.item, outcome.chapters)
                return outcome
            }

            val priorityOrder = listOf("epub", "pdf", "txt", "mobi", "html", "htm", "md", "cbz", "cbr")
            val primaryBook = extracted.sortedBy { f ->
                val idx = priorityOrder.indexOf(f.extension.lowercase())
                if (idx >= 0) idx else 999
            }.firstOrNull { f -> f.extension.lowercase() in directKnownExts }
                ?: throw IllegalArgumentException("Gói ZIP không chứa tệp EPUB/PDF/Audio hợp lệ.")

            return importLocalFile(primaryBook, pdfMode).getOrThrow()
        } finally {
            unpackDir.deleteRecursively()
        }
    }

    suspend fun importFolderUri(treeUri: Uri): Result<ImportOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val rootDir = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalArgumentException("Không truy cập được thư mục đã chọn.")
            val children = rootDir.listFiles()
            val hasAudio = children.any { it.isFile && ImporterHelpers.isAudioFileName(it.name.orEmpty()) }
            val outcome = if (hasAudio) {
                AudioImporter.importAudioFolder(context, rootDir, storage)
            } else {
                ImageFolderImporter.importTree(context, rootDir, storage)
            }
            repository.saveImportedBook(outcome.item, outcome.chapters)
            outcome
        }
    }

    fun isPdfFile(uri: Uri): Boolean = resolveFileName(uri).lowercase().endsWith(".pdf")

    private fun resolveFileName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx) ?: "unknown"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "unknown"
    }
}
