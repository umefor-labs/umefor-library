package io.lightknight.omniread.engine.importer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.data.storage.FileStorageManager
import java.io.File
import java.io.InputStream
import java.util.UUID

object PdfComicImporter {
    fun import(input: InputStream, fileName: String, storage: FileStorageManager): ImportOutcome {
        val itemId = UUID.randomUUID().toString()
        val pdfFile = File(storage.itemDir(itemId), "comic.pdf")
        ImporterHelpers.copyInputToFile(input, pdfFile)
        val sha256 = HashUtils.sha256(pdfFile)

        var pageCount = 0
        var coverPath: String? = null

        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                pageCount = renderer.pageCount
                if (pageCount > 0) {
                    val coverDest = storage.coverFile(itemId, "jpg")
                    renderPage(renderer, 0, maxWidth = 800, coverDest)
                    coverPath = coverDest.absolutePath
                }
            }
        }

        val chapter = ChapterEntity(
            id = UUID.randomUUID().toString(),
            itemId = itemId,
            chapterIndex = 0,
            title = "Toàn tập ($pageCount trang)",
            localFilePath = pdfFile.absolutePath,
            contentSha256 = sha256,
            pageCount = pageCount,
            downloadState = DownloadState.DOWNLOADED
        )
        val item = LibraryItemEntity(
            id = itemId,
            title = ImporterHelpers.cleanTitleFromFileName(fileName),
            coverLocalPath = coverPath,
            mediaType = MediaType.COMIC,
            originType = OriginType.LOCAL_IMPORT,
            localDirPath = storage.itemDir(itemId).absolutePath,
            contentSha256 = sha256
        )
        return ImportOutcome(item, listOf(chapter))
    }

    fun getOrRenderPage(pdfFile: File, pageIndex: Int, cacheDir: File, maxWidth: Int = 1440): File {
        cacheDir.mkdirs()
        val cached = File(cacheDir, "pdf_p_%04d.jpg".format(pageIndex))
        if (cached.exists() && cached.length() > 0) return cached

        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (pageIndex in 0 until renderer.pageCount) {
                    renderPage(renderer, pageIndex, maxWidth, cached)
                }
            }
        }
        return cached
    }

    private fun renderPage(renderer: PdfRenderer, index: Int, maxWidth: Int, dest: File) {
        renderer.openPage(index).use { page ->
            val scale = maxWidth.toFloat() / page.width.coerceAtLeast(1)
            val targetW = maxWidth
            val targetH = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            dest.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
        }
    }
}
