package io.lightknight.omniread.engine.importer

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.data.storage.FileStorageManager
import java.io.File
import java.io.InputStream
import java.util.UUID

object PdfNovelImporter {
    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    initialized = true
                }
            }
        }
    }

    fun import(
        context: Context,
        input: InputStream,
        fileName: String,
        storage: FileStorageManager
    ): ImportOutcome {
        ensureInitialized(context)
        val itemId = UUID.randomUUID().toString()
        val localPdf = File(storage.itemDir(itemId), "source.pdf")
        ImporterHelpers.copyInputToFile(input, localPdf)
        val sha256 = HashUtils.sha256(localPdf)

        val fullText = StringBuilder()
        var titleFromPdf = ""
        var authorFromPdf = ""

        PDDocument.load(localPdf).use { doc ->
            titleFromPdf = doc.documentInformation?.title?.trim().orEmpty()
            authorFromPdf = doc.documentInformation?.author?.trim().orEmpty()
            val stripper = PDFTextStripper()
            val totalPages = doc.numberOfPages
            val batchSize = 15
            var start = 1
            while (start <= totalPages) {
                val end = minOf(start + batchSize - 1, totalPages)
                stripper.startPage = start
                stripper.endPage = end
                fullText.append(stripper.getText(doc)).append("\n\n")
                start = end + 1
            }
        }

        val chapters = TxtImporter.splitIntoChapters(fullText.toString())
        val chapterEntities = ImporterHelpers.writeTextChapters(storage, itemId, chapters)
        val item = LibraryItemEntity(
            id = itemId,
            title = titleFromPdf.ifBlank { ImporterHelpers.cleanTitleFromFileName(fileName) },
            author = authorFromPdf,
            mediaType = MediaType.NOVEL,
            originType = OriginType.LOCAL_IMPORT,
            localDirPath = storage.itemDir(itemId).absolutePath,
            contentSha256 = sha256
        )
        return ImportOutcome(item, chapterEntities)
    }
}
