package io.lightknight.omniread.engine.sync

import io.lightknight.omniread.data.local.BookmarkEntity
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.CharacterVoiceEntity
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.local.ReadingProgressEntity
import io.lightknight.omniread.data.local.SourcePluginEntity
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import kotlinx.serialization.Serializable

@Serializable
data class SyncManifest(
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val items: List<SyncItemDto> = emptyList(),
    val chapters: List<SyncChapterDto> = emptyList(),
    val progress: List<SyncProgressDto> = emptyList(),
    val bookmarks: List<SyncBookmarkDto> = emptyList(),
    val characterVoices: List<SyncVoiceDto> = emptyList(),
    val plugins: List<SyncPluginDto> = emptyList()
)

@Serializable
data class SyncItemDto(
    val id: String,
    val title: String,
    val author: String = "",
    val synopsis: String = "",
    val coverDriveId: String? = null,
    val mediaType: String,
    val originType: String,
    val sourcePluginId: String? = null,
    val sourceUrl: String? = null,
    val driveFolderId: String? = null,
    val contentSha256: String = "",
    val isDeleted: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class SyncChapterDto(
    val id: String,
    val itemId: String,
    val chapterIndex: Int,
    val title: String,
    val remoteUrl: String? = null,
    val driveFileId: String? = null,
    val contentSha256: String = "",
    val durationMs: Long = 0L,
    val pageCount: Int = 0,
    val updatedAt: Long = 0L
)

@Serializable
data class SyncProgressDto(
    val itemId: String,
    val currentChapterId: String = "",
    val currentChapterIndex: Int = 0,
    val textScrollOffset: Int = 0,
    val textParagraphIndex: Int = 0,
    val comicPageIndex: Int = 0,
    val audioPositionMs: Long = 0L,
    val progressPercent: Float = 0f,
    val updatedAt: Long = 0L,
    val deviceId: String = ""
)

@Serializable
data class SyncBookmarkDto(
    val id: String,
    val itemId: String,
    val chapterId: String,
    val positionLabel: String,
    val positionPayloadJson: String,
    val note: String = "",
    val isDeleted: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class SyncVoiceDto(
    val id: String,
    val itemId: String,
    val characterName: String,
    val gender: String,
    val ageGroup: String,
    val engineId: String,
    val voiceId: String,
    val speechRate: Float,
    val pitchHz: Int,
    val pauseAfterMs: Int,
    val isNarrator: Boolean,
    val updatedAt: Long
)

@Serializable
data class SyncPluginDto(
    val id: String,
    val name: String,
    val mediaType: String,
    val lang: String,
    val version: Int,
    val baseUrl: String,
    val scriptContent: String,
    val enabled: Boolean,
    val isStarter: Boolean,
    val repoId: String = "",
    val description: String = "",
    val iconUrl: String = "",
    val scriptSha256: String = "",
    val updatedAt: Long
)

object SyncManifestMerger {
    fun merge(local: SyncManifest, remote: SyncManifest): SyncManifest {
        val mergedItems = mergeByKey(local.items, remote.items, { it.id }, { it.updatedAt }) { a, b ->
            // Preserve Drive IDs if one side already recorded them
            val winner = if (a.updatedAt >= b.updatedAt) a else b
            val loser = if (winner === a) b else a
            winner.copy(
                driveFolderId = winner.driveFolderId ?: loser.driveFolderId,
                coverDriveId = winner.coverDriveId ?: loser.coverDriveId
            )
        }
        val mergedChapters = mergeByKey(local.chapters, remote.chapters, { it.id }, { it.updatedAt }) { a, b ->
            val winner = if (a.updatedAt >= b.updatedAt) a else b
            val loser = if (winner === a) b else a
            winner.copy(driveFileId = winner.driveFileId ?: loser.driveFileId)
        }
        val mergedProgress = mergeByKey(local.progress, remote.progress, { it.itemId }, { it.updatedAt })
        val mergedBookmarks = mergeByKey(local.bookmarks, remote.bookmarks, { it.id }, { it.updatedAt })
        val mergedVoices = mergeByKey(local.characterVoices, remote.characterVoices, { it.id }, { it.updatedAt })
        val mergedPlugins = mergeByKey(local.plugins, remote.plugins, { it.id }, { it.updatedAt })

        return SyncManifest(
            version = maxOf(local.version, remote.version),
            updatedAt = maxOf(local.updatedAt, remote.updatedAt, System.currentTimeMillis()),
            items = mergedItems,
            chapters = mergedChapters,
            progress = mergedProgress,
            bookmarks = mergedBookmarks,
            characterVoices = mergedVoices,
            plugins = mergedPlugins
        )
    }

    private fun <T> mergeByKey(
        localList: List<T>,
        remoteList: List<T>,
        keyOf: (T) -> String,
        updatedAtOf: (T) -> Long,
        combine: (T, T) -> T = { a, b -> if (updatedAtOf(a) >= updatedAtOf(b)) a else b }
    ): List<T> {
        val map = LinkedHashMap<String, T>()
        localList.forEach { map[keyOf(it)] = it }
        remoteList.forEach { remoteItem ->
            val k = keyOf(remoteItem)
            val existing = map[k]
            map[k] = if (existing == null) remoteItem else combine(existing, remoteItem)
        }
        return map.values.toList()
    }
}

fun LibraryItemEntity.toSyncDto() = SyncItemDto(
    id = id, title = title, author = author, synopsis = synopsis,
    coverDriveId = coverDriveId, mediaType = mediaType.name, originType = originType.name,
    sourcePluginId = sourcePluginId, sourceUrl = sourceUrl, driveFolderId = driveFolderId,
    contentSha256 = contentSha256, isDeleted = isDeleted, createdAt = createdAt, updatedAt = updatedAt
)

fun SyncItemDto.toEntity(localDirPath: String, existing: LibraryItemEntity?) = LibraryItemEntity(
    id = id,
    title = title,
    author = author,
    synopsis = synopsis,
    coverLocalPath = existing?.coverLocalPath,
    coverDriveId = coverDriveId ?: existing?.coverDriveId,
    mediaType = runCatching { MediaType.valueOf(mediaType) }.getOrDefault(MediaType.NOVEL),
    originType = runCatching { OriginType.valueOf(originType) }.getOrDefault(OriginType.LOCAL_IMPORT),
    sourcePluginId = sourcePluginId,
    sourceUrl = sourceUrl,
    localDirPath = existing?.localDirPath ?: localDirPath,
    driveFolderId = driveFolderId ?: existing?.driveFolderId,
    contentSha256 = contentSha256,
    isCachedLocally = existing?.isCachedLocally ?: false,
    isDeleted = isDeleted,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastOpenedAt = existing?.lastOpenedAt ?: 0L
)

fun ChapterEntity.toSyncDto() = SyncChapterDto(
    id = id, itemId = itemId, chapterIndex = chapterIndex, title = title,
    remoteUrl = remoteUrl, driveFileId = driveFileId, contentSha256 = contentSha256,
    durationMs = durationMs, pageCount = pageCount, updatedAt = updatedAt
)

fun SyncChapterDto.toEntity(existing: ChapterEntity?) = ChapterEntity(
    id = id,
    itemId = itemId,
    chapterIndex = chapterIndex,
    title = title,
    remoteUrl = remoteUrl,
    localFilePath = existing?.localFilePath,
    driveFileId = driveFileId ?: existing?.driveFileId,
    contentSha256 = contentSha256,
    durationMs = durationMs,
    pageCount = pageCount,
    downloadState = existing?.downloadState ?: if (!driveFileId.isNullOrBlank()) DownloadState.CLOUD_ONLY else DownloadState.REMOTE,
    updatedAt = updatedAt
)

fun ReadingProgressEntity.toSyncDto() = SyncProgressDto(
    itemId = itemId, currentChapterId = currentChapterId, currentChapterIndex = currentChapterIndex,
    textScrollOffset = textScrollOffset, textParagraphIndex = textParagraphIndex,
    comicPageIndex = comicPageIndex, audioPositionMs = audioPositionMs,
    progressPercent = progressPercent, updatedAt = updatedAt, deviceId = deviceId
)

fun SyncProgressDto.toEntity() = ReadingProgressEntity(
    itemId = itemId, currentChapterId = currentChapterId, currentChapterIndex = currentChapterIndex,
    textScrollOffset = textScrollOffset, textParagraphIndex = textParagraphIndex,
    comicPageIndex = comicPageIndex, audioPositionMs = audioPositionMs,
    progressPercent = progressPercent, updatedAt = updatedAt, deviceId = deviceId
)

fun BookmarkEntity.toSyncDto() = SyncBookmarkDto(
    id = id, itemId = itemId, chapterId = chapterId, positionLabel = positionLabel,
    positionPayloadJson = positionPayloadJson, note = note, isDeleted = isDeleted,
    createdAt = createdAt, updatedAt = updatedAt
)

fun SyncBookmarkDto.toEntity() = BookmarkEntity(
    id = id, itemId = itemId, chapterId = chapterId, positionLabel = positionLabel,
    positionPayloadJson = positionPayloadJson, note = note, isDeleted = isDeleted,
    createdAt = createdAt, updatedAt = updatedAt
)

fun CharacterVoiceEntity.toSyncDto() = SyncVoiceDto(
    id = id, itemId = itemId, characterName = characterName, gender = gender, ageGroup = ageGroup,
    engineId = engineId, voiceId = voiceId, speechRate = speechRate, pitchHz = pitchHz,
    pauseAfterMs = pauseAfterMs, isNarrator = isNarrator, updatedAt = updatedAt
)

fun SyncVoiceDto.toEntity() = CharacterVoiceEntity(
    id = id, itemId = itemId, characterName = characterName, gender = gender, ageGroup = ageGroup,
    engineId = engineId, voiceId = voiceId, speechRate = speechRate, pitchHz = pitchHz,
    pauseAfterMs = pauseAfterMs, isNarrator = isNarrator, updatedAt = updatedAt
)

fun SourcePluginEntity.toSyncDto() = SyncPluginDto(
    id = id, name = name, mediaType = mediaType.name, lang = lang, version = version,
    baseUrl = baseUrl, scriptContent = scriptContent, enabled = enabled, isStarter = isStarter,
    repoId = repoId, description = description, iconUrl = iconUrl, scriptSha256 = scriptSha256,
    updatedAt = updatedAt
)

fun SyncPluginDto.toEntity() = SourcePluginEntity(
    id = id, name = name,
    mediaType = runCatching { MediaType.valueOf(mediaType) }.getOrDefault(MediaType.NOVEL),
    lang = lang, version = version, baseUrl = baseUrl, scriptContent = scriptContent,
    enabled = enabled, isStarter = isStarter, repoId = repoId, description = description,
    iconUrl = iconUrl, scriptSha256 = scriptSha256, updatedAt = updatedAt
)
