package io.lightknight.omniread.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.model.MediaType
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_items WHERE isDeleted = 0 ORDER BY lastOpenedAt DESC, updatedAt DESC")
    fun observeAllActive(): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE isDeleted = 0 AND mediaType = :mediaType ORDER BY lastOpenedAt DESC, updatedAt DESC")
    fun observeByMediaType(mediaType: MediaType): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items")
    suspend fun getAllIncludingDeleted(): List<LibraryItemEntity>

    @Query("SELECT * FROM library_items WHERE id = :id")
    suspend fun getById(id: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE id = :id")
    fun observeById(id: String): Flow<LibraryItemEntity?>

    @Query("SELECT * FROM library_items WHERE sourcePluginId = :pluginId AND sourceUrl = :sourceUrl AND isDeleted = 0 LIMIT 1")
    suspend fun findBySource(pluginId: String, sourceUrl: String): LibraryItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LibraryItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LibraryItemEntity>)

    @Query("UPDATE library_items SET lastOpenedAt = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun markOpened(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE library_items SET isCachedLocally = :cached, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateCachedLocally(id: String, cached: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE library_items SET driveFolderId = :folderId, coverDriveId = :coverDriveId WHERE id = :id")
    suspend fun updateDriveIds(id: String, folderId: String?, coverDriveId: String?)

    @Query("UPDATE library_items SET isDeleted = 1, updatedAt = :timestamp WHERE id = :id")
    suspend fun softDelete(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE library_items SET shelfTag = :shelfTag, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateShelfTag(id: String, shelfTag: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE library_items SET trackUpdates = :enabled WHERE id = :id")
    suspend fun updateTrackUpdates(id: String, enabled: Boolean)

    @Query("UPDATE library_items SET unreadNewChapters = unreadNewChapters + :delta, updatedAt = :timestamp WHERE id = :id")
    suspend fun addUnreadNewChapters(id: String, delta: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE library_items SET unreadNewChapters = 0 WHERE id = :id")
    suspend fun clearUnreadNewChapters(id: String)

    @Query("SELECT COUNT(*) FROM library_items WHERE sourcePluginId = :pluginId AND isDeleted = 0")
    suspend fun countBySourcePlugin(pluginId: String): Int
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE itemId = :itemId ORDER BY chapterIndex ASC")
    fun observeForItem(itemId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE itemId = :itemId ORDER BY chapterIndex ASC")
    suspend fun getForItem(itemId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters")
    suspend fun getAll(): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun getById(chapterId: String): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chapter: ChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("UPDATE chapters SET downloadState = :state, localFilePath = :localPath, contentSha256 = :sha256, updatedAt = :ts WHERE id = :chapterId")
    suspend fun updateLocalFile(
        chapterId: String,
        state: DownloadState,
        localPath: String?,
        sha256: String,
        ts: Long = System.currentTimeMillis()
    )

    @Query("UPDATE chapters SET driveFileId = :driveFileId WHERE id = :chapterId")
    suspend fun updateDriveFileId(chapterId: String, driveFileId: String)

    @Query("UPDATE chapters SET downloadState = :state, localFilePath = NULL, updatedAt = :ts WHERE itemId = :itemId AND driveFileId IS NOT NULL")
    suspend fun markCloudOnlyForItem(itemId: String, state: DownloadState = DownloadState.CLOUD_ONLY, ts: Long = System.currentTimeMillis())
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM reading_progress")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress")
    suspend fun getAll(): List<ReadingProgressEntity>

    @Query("SELECT * FROM reading_progress WHERE itemId = :itemId")
    fun observeForItem(itemId: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE itemId = :itemId")
    suspend fun getForItem(itemId: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ReadingProgressEntity>)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE itemId = :itemId AND isDeleted = 0 ORDER BY createdAt DESC")
    fun observeForItem(itemId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks")
    suspend fun getAllIncludingDeleted(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bookmarks: List<BookmarkEntity>)

    @Query("UPDATE bookmarks SET isDeleted = 1, updatedAt = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long = System.currentTimeMillis())
}

@Dao
interface VoiceCastDao {
    @Query("SELECT * FROM character_voices WHERE itemId = :itemId ORDER BY isNarrator DESC, characterName ASC")
    fun observeForItem(itemId: String): Flow<List<CharacterVoiceEntity>>

    @Query("SELECT * FROM character_voices WHERE itemId = :itemId ORDER BY isNarrator DESC, characterName ASC")
    suspend fun getForItem(itemId: String): List<CharacterVoiceEntity>

    @Query("SELECT * FROM character_voices")
    suspend fun getAll(): List<CharacterVoiceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(voice: CharacterVoiceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(voices: List<CharacterVoiceEntity>)

    @Query("DELETE FROM character_voices WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChapterScriptDao {
    @Query("SELECT * FROM chapter_scripts WHERE chapterId = :chapterId")
    suspend fun getByChapterId(chapterId: String): ChapterScriptEntity?

    @Query("SELECT * FROM chapter_scripts")
    suspend fun getAll(): List<ChapterScriptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(script: ChapterScriptEntity)
}

@Dao
interface SourcePluginDao {
    @Query("SELECT * FROM source_plugins ORDER BY enabled DESC, name ASC")
    fun observeAll(): Flow<List<SourcePluginEntity>>

    @Query("SELECT * FROM source_plugins WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabled(): List<SourcePluginEntity>

    @Query("SELECT * FROM source_plugins")
    suspend fun getAll(): List<SourcePluginEntity>

    @Query("SELECT * FROM source_plugins WHERE id = :id")
    suspend fun getById(id: String): SourcePluginEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plugin: SourcePluginEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(plugins: List<SourcePluginEntity>)

    @Query("DELETE FROM source_plugins WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ExtensionRepoDao {
    @Query("SELECT * FROM extension_repos ORDER BY isOfficial DESC, name ASC")
    fun observeAll(): Flow<List<ExtensionRepoEntity>>

    @Query("SELECT * FROM extension_repos ORDER BY isOfficial DESC, name ASC")
    suspend fun getAll(): List<ExtensionRepoEntity>

    @Query("SELECT * FROM extension_repos WHERE enabled = 1 ORDER BY isOfficial DESC, name ASC")
    suspend fun getEnabled(): List<ExtensionRepoEntity>

    @Query("SELECT * FROM extension_repos WHERE id = :id")
    suspend fun getById(id: String): ExtensionRepoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(repo: ExtensionRepoEntity)

    @Query("DELETE FROM extension_repos WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SyncJobDao {
    @Query("SELECT * FROM sync_jobs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SyncJobEntity>>

    @Query("SELECT * FROM sync_jobs WHERE status IN ('PENDING', 'FAILED') ORDER BY updatedAt ASC")
    suspend fun getPending(): List<SyncJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: SyncJobEntity)

    @Query("DELETE FROM sync_jobs WHERE status = 'DONE'")
    suspend fun clearCompleted()

    @Transaction
    suspend fun clearAndReplaceAll(jobs: List<SyncJobEntity>) {
        clearCompleted()
        jobs.forEach { upsert(it) }
    }
}

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE itemId = :itemId ORDER BY createdAt DESC")
    fun observeForItem(itemId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE itemId = :itemId ORDER BY createdAt ASC")
    suspend fun getForItem(itemId: String): List<HighlightEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ReadingStatsDao {
    @Query("SELECT * FROM reading_sessions ORDER BY dateKey DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions ORDER BY dateKey DESC")
    suspend fun getAll(): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_sessions WHERE dateKey = :dateKey")
    suspend fun getByDate(dateKey: String): ReadingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ReadingSessionEntity)
}

@Dao
interface PronunciationRuleDao {
    @Query("SELECT * FROM pronunciation_rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PronunciationRuleEntity>>

    @Query("SELECT * FROM pronunciation_rules WHERE enabled = 1 ORDER BY createdAt ASC")
    suspend fun getEnabled(): List<PronunciationRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: PronunciationRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<PronunciationRuleEntity>)

    @Query("DELETE FROM pronunciation_rules WHERE id = :id")
    suspend fun deleteById(id: String)
}
