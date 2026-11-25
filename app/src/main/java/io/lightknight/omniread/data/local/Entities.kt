package io.lightknight.omniread.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType

@Entity(tableName = "library_items")
data class LibraryItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val synopsis: String = "",
    val coverLocalPath: String? = null,
    val coverDriveId: String? = null,
    val mediaType: MediaType,
    val originType: OriginType,
    val sourcePluginId: String? = null,
    val sourceUrl: String? = null,
    val localDirPath: String,
    val driveFolderId: String? = null,
    val contentSha256: String = "",
    val isCachedLocally: Boolean = true,
    val isDeleted: Boolean = false,
    val shelfTag: String = "",
    val unreadNewChapters: Int = 0,
    val trackUpdates: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0L
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("itemId"), Index(value = ["itemId", "chapterIndex"], unique = true)]
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val chapterIndex: Int,
    val title: String,
    val remoteUrl: String? = null,
    val localFilePath: String? = null,
    val driveFileId: String? = null,
    val contentSha256: String = "",
    val durationMs: Long = 0L,
    val pageCount: Int = 0,
    val downloadState: DownloadState = DownloadState.DOWNLOADED,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReadingProgressEntity(
    @PrimaryKey val itemId: String,
    val currentChapterId: String = "",
    val currentChapterIndex: Int = 0,
    val textScrollOffset: Int = 0,
    val textParagraphIndex: Int = 0,
    val comicPageIndex: Int = 0,
    val audioPositionMs: Long = 0L,
    val progressPercent: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis(),
    val deviceId: String = ""
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("itemId")]
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val chapterId: String,
    val positionLabel: String,
    val positionPayloadJson: String,
    val note: String = "",
    val isDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "character_voices",
    foreignKeys = [
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("itemId"), Index(value = ["itemId", "characterName"], unique = true)]
)
data class CharacterVoiceEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val characterName: String,
    val gender: String = "NEUTRAL", // MALE, FEMALE, NEUTRAL
    val ageGroup: String = "ADULT", // CHILD, YOUNG, ADULT, ELDER
    val engineId: String = "EDGE_TTS",
    val voiceId: String = "vi-VN-HoaiMyNeural",
    val speechRate: Float = 1.0f,
    val pitchHz: Int = 0,
    val pauseAfterMs: Int = 250,
    val isNarrator: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chapter_scripts",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChapterScriptEntity(
    @PrimaryKey val chapterId: String,
    val itemId: String,
    val mode: String, // LOCAL_RULE | GEMINI_AI
    val segmentsJson: String,
    val cachedAudioPath: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "source_plugins")
data class SourcePluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mediaType: MediaType,
    val lang: String = "vi",
    val version: Int = 1,
    val baseUrl: String,
    val scriptContent: String,
    val enabled: Boolean = true,
    val isStarter: Boolean = false,
    val repoId: String = "",
    val description: String = "",
    val iconUrl: String = "",
    val scriptSha256: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "extension_repos")
data class ExtensionRepoEntity(
    @PrimaryKey val id: String,
    val name: String,
    val indexUrl: String,
    val mirrorIndexUrl: String = "",
    val websiteUrl: String = "",
    val issueUrl: String = "",
    val isOfficial: Boolean = false,
    val enabled: Boolean = true,
    val lastSyncedAt: Long = 0L,
    val cachedIndexJson: String = "",
    val lastError: String = ""
)

@Entity(tableName = "sync_jobs")
data class SyncJobEntity(
    @PrimaryKey val id: String,
    val entityType: String, // META | CHAPTER_FILE | COVER | SCRIPT | INBOX
    val entityId: String,
    val action: String, // UPLOAD_META | UPLOAD_FILE | DOWNLOAD_FILE | DELETE
    val status: String = "PENDING", // PENDING | RUNNING | DONE | FAILED
    val progressPercent: Int = 0,
    val resumableSessionUri: String? = null,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("itemId")]
)
data class HighlightEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val chapterId: String,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val quoteText: String,
    val colorHex: Long = 0xFFFFEB3BL,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reading_sessions")
data class ReadingSessionEntity(
    @PrimaryKey val dateKey: String, // yyyy-MM-dd
    val readingSeconds: Long = 0L,
    val listeningSeconds: Long = 0L,
    val chaptersCompleted: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "pronunciation_rules")
data class PronunciationRuleEntity(
    @PrimaryKey val id: String,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
