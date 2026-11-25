package io.lightknight.omniread.engine.importer

import android.content.Context
import android.media.MediaMetadataRetriever
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
import java.io.InputStream
import java.util.UUID

object AudioImporter {
    fun importSingleFile(
        input: InputStream,
        fileName: String,
        storage: FileStorageManager
    ): ImportOutcome {
        val itemId = UUID.randomUUID().toString()
        val ext = fileName.substringAfterLast('.', "mp3").lowercase()
        val dest = File(storage.itemAudioDir(itemId), "track_0000.$ext")
        ImporterHelpers.copyInputToFile(input, dest)

        val meta = extractAudioMeta(dest)
        val coverPath = meta.embeddedPicture?.let { pic ->
            val cover = storage.coverFile(itemId, "jpg")
            cover.writeBytes(pic)
            cover.absolutePath
        }

        val chapter = ChapterEntity(
            id = UUID.randomUUID().toString(),
            itemId = itemId,
            chapterIndex = 0,
            title = meta.title.ifBlank { ImporterHelpers.cleanTitleFromFileName(fileName) },
            localFilePath = dest.absolutePath,
            contentSha256 = HashUtils.sha256(dest),
            durationMs = meta.durationMs,
            downloadState = DownloadState.DOWNLOADED
        )
        val item = LibraryItemEntity(
            id = itemId,
            title = meta.album.ifBlank { meta.title }.ifBlank { ImporterHelpers.cleanTitleFromFileName(fileName) },
            author = meta.artist,
            coverLocalPath = coverPath,
            mediaType = MediaType.AUDIOBOOK,
            originType = OriginType.LOCAL_IMPORT,
            localDirPath = storage.itemDir(itemId).absolutePath,
            contentSha256 = chapter.contentSha256
        )
        return ImportOutcome(item, listOf(chapter))
    }

    fun importAudioFolder(
        context: Context,
        rootDir: DocumentFile,
        storage: FileStorageManager
    ): ImportOutcome {
        val audioDocs = rootDir.listFiles()
            .filter { it.isFile && ImporterHelpers.isAudioFileName(it.name.orEmpty()) }
            .sortedWith { a, b -> NaturalSort.compare(a.name.orEmpty(), b.name.orEmpty()) }

        if (audioDocs.isEmpty()) {
            throw IllegalArgumentException("Thư mục không chứa tệp âm thanh hợp lệ.")
        }

        val itemId = UUID.randomUUID().toString()
        val audioDir = storage.itemAudioDir(itemId)
        var coverPath: String? = null
        var bookAuthor = ""
        val chapters = mutableListOf<ChapterEntity>()

        audioDocs.forEachIndexed { idx, doc ->
            val name = doc.name ?: "track_$idx.mp3"
            val ext = name.substringAfterLast('.', "mp3").lowercase()
            val dest = File(audioDir, "track_%04d.%s".format(idx, ext))
            context.contentResolver.openInputStream(doc.uri)?.use { ins ->
                ImporterHelpers.copyInputToFile(ins, dest)
            }
            val meta = extractAudioMeta(dest)
            if (bookAuthor.isBlank() && meta.artist.isNotBlank()) bookAuthor = meta.artist
            if (coverPath == null && meta.embeddedPicture != null) {
                val cover = storage.coverFile(itemId, "jpg")
                cover.writeBytes(meta.embeddedPicture)
                coverPath = cover.absolutePath
            }
            chapters += ChapterEntity(
                id = UUID.randomUUID().toString(),
                itemId = itemId,
                chapterIndex = idx,
                title = meta.title.ifBlank { ImporterHelpers.cleanTitleFromFileName(name) },
                localFilePath = dest.absolutePath,
                contentSha256 = HashUtils.sha256(dest),
                durationMs = meta.durationMs,
                downloadState = DownloadState.DOWNLOADED
            )
        }

        val item = LibraryItemEntity(
            id = itemId,
            title = rootDir.name ?: "Sách nói cục bộ",
            author = bookAuthor,
            coverLocalPath = coverPath,
            mediaType = MediaType.AUDIOBOOK,
            originType = OriginType.LOCAL_IMPORT,
            localDirPath = storage.itemDir(itemId).absolutePath,
            contentSha256 = HashUtils.sha256(chapters.joinToString { it.contentSha256 })
        )
        return ImportOutcome(item, chapters)
    }

    fun importLocalFiles(
        files: List<File>,
        bookTitle: String,
        storage: FileStorageManager
    ): ImportOutcome {
        val sorted = files.filter { it.isFile && ImporterHelpers.isAudioFileName(it.name) }
            .sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }
        if (sorted.isEmpty()) throw IllegalArgumentException("Không có tệp âm thanh hợp lệ.")

        val itemId = UUID.randomUUID().toString()
        val audioDir = storage.itemAudioDir(itemId)
        var coverPath: String? = null
        var bookAuthor = ""
        val chapters = mutableListOf<ChapterEntity>()

        sorted.forEachIndexed { idx, src ->
            val ext = src.extension.lowercase().ifBlank { "mp3" }
            val dest = File(audioDir, "track_%04d.%s".format(idx, ext))
            src.copyTo(dest, overwrite = true)
            val meta = extractAudioMeta(dest)
            if (bookAuthor.isBlank() && meta.artist.isNotBlank()) bookAuthor = meta.artist
            if (coverPath == null && meta.embeddedPicture != null) {
                val cover = storage.coverFile(itemId, "jpg")
                cover.writeBytes(meta.embeddedPicture)
                coverPath = cover.absolutePath
            }
            chapters += ChapterEntity(
                id = UUID.randomUUID().toString(),
                itemId = itemId,
                chapterIndex = idx,
                title = meta.title.ifBlank { ImporterHelpers.cleanTitleFromFileName(src.name) },
                localFilePath = dest.absolutePath,
                contentSha256 = HashUtils.sha256(dest),
                durationMs = meta.durationMs,
                downloadState = DownloadState.DOWNLOADED
            )
        }

        val item = LibraryItemEntity(
            id = itemId,
            title = bookTitle.ifBlank { "Sách nói đã tải" },
            author = bookAuthor,
            coverLocalPath = coverPath,
            mediaType = MediaType.AUDIOBOOK,
            originType = OriginType.LOCAL_IMPORT,
            localDirPath = storage.itemDir(itemId).absolutePath,
            contentSha256 = HashUtils.sha256(chapters.joinToString { it.contentSha256 })
        )
        return ImportOutcome(item, chapters)
    }

    data class AudioMeta(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0L,
        val embeddedPicture: ByteArray? = null
    )

    fun extractAudioMeta(file: File): AudioMeta {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(file.absolutePath)
            AudioMeta(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim(),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim(),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                embeddedPicture = retriever.embeddedPicture
            )
        }.getOrDefault(AudioMeta()).also {
            runCatching { retriever.release() }
        }
    }
}
