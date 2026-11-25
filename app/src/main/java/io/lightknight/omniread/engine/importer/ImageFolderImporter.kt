package io.lightknight.omniread.engine.importer

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.core.NaturalSort
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.data.storage.FileStorageManager
import java.io.File
import java.util.UUID

object ImageFolderImporter {
    fun importTree(
        context: Context,
        rootDir: DocumentFile,
        storage: FileStorageManager
    ): ImportOutcome {
        val itemId = UUID.randomUUID().toString()
        val children = rootDir.listFiles()
        val subDirs = children.filter { it.isDirectory }
            .sortedWith { a, b -> NaturalSort.compare(a.name.orEmpty(), b.name.orEmpty()) }
        val rootImages = children.filter { it.isFile && ImporterHelpers.isImageFileName(it.name.orEmpty()) }
            .sortedWith { a, b -> NaturalSort.compare(a.name.orEmpty(), b.name.orEmpty()) }

        val chapters = mutableListOf<ChapterEntity>()
        var coverPath: String? = null

        fun copyChapterImages(chIdx: Int, title: String, files: List<DocumentFile>) {
            if (files.isEmpty()) return
            val chDir = File(storage.itemChaptersDir(itemId), "ch_%04d".format(chIdx)).apply { mkdirs() }
            files.forEachIndexed { idx, doc ->
                val ext = doc.name?.substringAfterLast('.', "jpg") ?: "jpg"
                val dest = File(chDir, "p_%04d.%s".format(idx, ext))
                context.contentResolver.openInputStream(doc.uri)?.use { ins ->
                    ImporterHelpers.copyInputToFile(ins, dest)
                }
                if (coverPath == null && dest.exists()) {
                    val coverDest = storage.coverFile(itemId, ext)
                    dest.copyTo(coverDest, overwrite = true)
                    coverPath = coverDest.absolutePath
                }
            }
            chapters += ChapterEntity(
                id = UUID.randomUUID().toString(),
                itemId = itemId,
                chapterIndex = chIdx,
                title = title,
                localFilePath = chDir.absolutePath,
                pageCount = files.size,
                downloadState = DownloadState.DOWNLOADED
            )
        }

        if (rootImages.isNotEmpty()) {
            copyChapterImages(0, if (subDirs.isEmpty()) "Toàn tập" else "Chương mở đầu", rootImages)
        }
        subDirs.forEach { dir ->
            val imgs = dir.listFiles()
                .filter { it.isFile && ImporterHelpers.isImageFileName(it.name.orEmpty()) }
                .sortedWith { a, b -> NaturalSort.compare(a.name.orEmpty(), b.name.orEmpty()) }
            copyChapterImages(chapters.size, dir.name ?: "Chương ${chapters.size + 1}", imgs)
        }

        if (chapters.isEmpty()) {
            storage.deleteItemCompletely(itemId)
            throw IllegalArgumentException("Thư mục không chứa ảnh truyện tranh nào.")
        }

        val item = LibraryItemEntity(
            id = itemId,
            title = rootDir.name ?: "Truyện tranh cục bộ",
            coverLocalPath = coverPath,
            mediaType = MediaType.COMIC,
            originType = OriginType.LOCAL_IMPORT,
            localDirPath = storage.itemDir(itemId).absolutePath,
            contentSha256 = HashUtils.sha256("${rootDir.name}:${chapters.size}")
        )
        return ImportOutcome(item, chapters)
    }
}
