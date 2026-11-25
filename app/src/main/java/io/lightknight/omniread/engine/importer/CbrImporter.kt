package io.lightknight.omniread.engine.importer

import com.github.junrar.Archive
import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.core.NaturalSort
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.data.storage.FileStorageManager
import java.io.File
import java.io.InputStream
import java.util.UUID

object CbrImporter {
    private val rar5Magic = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    fun import(input: InputStream, fileName: String, storage: FileStorageManager): ImportOutcome {
        val itemId = UUID.randomUUID().toString()
        val rarFile = File(storage.itemDir(itemId), "source.cbr")
        ImporterHelpers.copyInputToFile(input, rarFile)

        // Check RAR5 magic header explicitly to give a clear actionable error
        val header = ByteArray(8)
        rarFile.inputStream().use { it.read(header) }
        if (header.contentEquals(rar5Magic)) {
            storage.deleteItemCompletely(itemId)
            throw UnsupportedRar5Exception(
                "Tệp CBR này dùng chuẩn nén RAR5 chưa được hỗ trợ giải nén trực tiếp. " +
                    "Ngài vui lòng giải nén thành thư mục ảnh hoặc nén lại sang ZIP/CBZ."
            )
        }

        val sha256 = HashUtils.sha256(rarFile)
        val extractTemp = File(storage.itemDir(itemId), "rar_extract").apply { mkdirs() }

        Archive(rarFile).use { archive ->
            var fh = archive.nextFileHeader()
            while (fh != null) {
                if (!fh.isDirectory && ImporterHelpers.isImageFileName(fh.fileName)) {
                    val safeName = fh.fileName.replace('\\', '/')
                    val out = File(extractTemp, safeName)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os -> archive.extractFile(fh, os) }
                }
                fh = archive.nextFileHeader()
            }
        }

        val extractedImages = extractTemp.walkTopDown()
            .filter { it.isFile && ImporterHelpers.isImageFileName(it.name) }
            .sortedWith { a, b -> NaturalSort.compare(a.relativeTo(extractTemp).path, b.relativeTo(extractTemp).path) }
            .toList()

        if (extractedImages.isEmpty()) {
            storage.deleteItemCompletely(itemId)
            throw IllegalArgumentException("Tệp CBR không chứa ảnh hợp lệ.")
        }

        val chDir = File(storage.itemChaptersDir(itemId), "ch_0000").apply { mkdirs() }
        var coverPath: String? = null
        extractedImages.forEachIndexed { idx, img ->
            val ext = img.extension.ifBlank { "jpg" }
            val dest = File(chDir, "p_%04d.%s".format(idx, ext))
            img.copyTo(dest, overwrite = true)
            if (coverPath == null) {
                val cover = storage.coverFile(itemId, ext)
                dest.copyTo(cover, overwrite = true)
                coverPath = cover.absolutePath
            }
        }
        extractTemp.deleteRecursively()

        val chapter = ChapterEntity(
            id = UUID.randomUUID().toString(),
            itemId = itemId,
            chapterIndex = 0,
            title = "Toàn tập",
            localFilePath = chDir.absolutePath,
            contentSha256 = sha256,
            pageCount = extractedImages.size,
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
}
