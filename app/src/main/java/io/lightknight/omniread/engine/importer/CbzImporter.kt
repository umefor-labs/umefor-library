package io.lightknight.omniread.engine.importer

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
import java.util.zip.ZipFile

object CbzImporter {
    fun import(input: InputStream, fileName: String, storage: FileStorageManager): ImportOutcome {
        val itemId = UUID.randomUUID().toString()
        val archiveFile = File(storage.itemDir(itemId), "source.cbz")
        ImporterHelpers.copyInputToFile(input, archiveFile)
        val archiveHash = HashUtils.sha256(archiveFile)

        ZipFile(archiveFile).use { zip ->
            val imageEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && ImporterHelpers.isImageFileName(it.name) }
                .sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }
                .toList()

            if (imageEntries.isEmpty()) {
                throw IllegalArgumentException("Tệp CBZ/ZIP không chứa ảnh hợp lệ.")
            }

            // Group by parent folder if multiple chapters exist inside the archive
            val grouped = imageEntries.groupBy { entry ->
                val dir = entry.name.substringBeforeLast('/', "")
                if (dir == entry.name) "" else dir
            }

            val sortedGroups = grouped.entries.sortedWith { a, b -> NaturalSort.compare(a.key, b.key) }
            val chapters = mutableListOf<ChapterEntity>()
            var coverPath: String? = null

            sortedGroups.forEachIndexed { chIdx, (folderName, entries) ->
                val chDir = File(storage.itemChaptersDir(itemId), "ch_%04d".format(chIdx)).apply { mkdirs() }
                entries.forEachIndexed { pageIdx, entry ->
                    val ext = entry.name.substringAfterLast('.', "jpg")
                    val pageFile = File(chDir, "p_%04d.%s".format(pageIdx, ext))
                    zip.getInputStream(entry).use { eis -> ImporterHelpers.copyInputToFile(eis, pageFile) }
                    if (coverPath == null) {
                        val coverDest = storage.coverFile(itemId, ext)
                        pageFile.copyTo(coverDest, overwrite = true)
                        coverPath = coverDest.absolutePath
                    }
                }
                val chTitle = folderName.substringAfterLast('/').ifBlank {
                    if (sortedGroups.size == 1) "Toàn tập" else "Chương ${chIdx + 1}"
                }
                chapters += ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    itemId = itemId,
                    chapterIndex = chIdx,
                    title = chTitle,
                    localFilePath = chDir.absolutePath,
                    contentSha256 = archiveHash,
                    pageCount = entries.size,
                    downloadState = DownloadState.DOWNLOADED
                )
            }

            val item = LibraryItemEntity(
                id = itemId,
                title = ImporterHelpers.cleanTitleFromFileName(fileName),
                coverLocalPath = coverPath,
                mediaType = MediaType.COMIC,
                originType = OriginType.LOCAL_IMPORT,
                localDirPath = storage.itemDir(itemId).absolutePath,
                contentSha256 = archiveHash
            )
            return ImportOutcome(item, chapters)
        }
    }
}
