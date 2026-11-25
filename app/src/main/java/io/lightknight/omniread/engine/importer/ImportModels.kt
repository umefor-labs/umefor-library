package io.lightknight.omniread.engine.importer

import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.storage.FileStorageManager
import java.io.File
import java.io.InputStream
import java.util.UUID

data class ImportOutcome(
    val item: LibraryItemEntity,
    val chapters: List<ChapterEntity>
)

data class ParsedTextChapter(
    val title: String,
    val content: String
)

class UnsupportedRar5Exception(message: String) : Exception(message)

internal object ImporterHelpers {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val audioExtensions = setOf("mp3", "m4a", "m4b", "opus", "ogg", "wav", "flac")

    fun isImageFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions && !name.startsWith("__MACOSX") && !name.substringAfterLast('/').startsWith(".")
    }

    fun isAudioFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in audioExtensions
    }

    fun writeTextChapters(
        storage: FileStorageManager,
        itemId: String,
        parsed: List<ParsedTextChapter>
    ): List<ChapterEntity> {
        val chaptersDir = storage.itemChaptersDir(itemId)
        return parsed.mapIndexed { idx, ch ->
            val file = File(chaptersDir, "ch_%04d.txt".format(idx))
            file.writeText(ch.content, Charsets.UTF_8)
            ChapterEntity(
                id = UUID.randomUUID().toString(),
                itemId = itemId,
                chapterIndex = idx,
                title = ch.title.ifBlank { "Chương ${idx + 1}" },
                localFilePath = file.absolutePath,
                contentSha256 = HashUtils.sha256(file),
                downloadState = DownloadState.DOWNLOADED
            )
        }
    }

    fun copyInputToFile(input: InputStream, dest: File) {
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out ->
            input.copyTo(out, bufferSize = 16 * 1024)
        }
    }

    fun cleanTitleFromFileName(fileName: String): String {
        return fileName.substringBeforeLast('.').replace('_', ' ').trim().ifBlank { "Chưa rõ tên" }
    }
}
