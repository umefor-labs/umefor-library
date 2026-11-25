package io.lightknight.omniread.data.repository

import io.lightknight.omniread.core.AppSettingsRepository
import io.lightknight.omniread.data.local.BookmarkEntity
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.ChapterScriptEntity
import io.lightknight.omniread.data.local.CharacterVoiceEntity
import io.lightknight.omniread.data.local.HighlightEntity
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.local.OmniReadDatabase
import io.lightknight.omniread.data.local.PronunciationRuleEntity
import io.lightknight.omniread.data.local.ReadingProgressEntity
import io.lightknight.omniread.data.local.ReadingSessionEntity
import io.lightknight.omniread.data.local.SyncJobEntity
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.storage.FileStorageManager
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

class LibraryRepository(
    private val db: OmniReadDatabase,
    private val storage: FileStorageManager,
    private val settingsRepo: AppSettingsRepository
) {
    private val libraryDao = db.libraryDao()
    private val chapterDao = db.chapterDao()
    private val progressDao = db.progressDao()
    private val bookmarkDao = db.bookmarkDao()
    private val voiceCastDao = db.voiceCastDao()
    private val scriptDao = db.chapterScriptDao()
    private val syncJobDao = db.syncJobDao()
    private val highlightDao = db.highlightDao()
    private val statsDao = db.readingStatsDao()
    private val pronunciationDao = db.pronunciationRuleDao()

    fun observeAllItems(): Flow<List<LibraryItemEntity>> = libraryDao.observeAllActive()

    fun observeByMediaType(type: MediaType): Flow<List<LibraryItemEntity>> = libraryDao.observeByMediaType(type)

    fun observeItem(itemId: String): Flow<LibraryItemEntity?> = libraryDao.observeById(itemId)

    fun observeChapters(itemId: String): Flow<List<ChapterEntity>> = chapterDao.observeForItem(itemId)

    fun observeProgress(itemId: String): Flow<ReadingProgressEntity?> = progressDao.observeForItem(itemId)

    fun observeAllProgress(): Flow<List<ReadingProgressEntity>> = progressDao.observeAll()

    fun observeBookmarks(itemId: String): Flow<List<BookmarkEntity>> = bookmarkDao.observeForItem(itemId)

    fun observeVoiceCast(itemId: String): Flow<List<CharacterVoiceEntity>> = voiceCastDao.observeForItem(itemId)

    suspend fun getItem(itemId: String): LibraryItemEntity? = libraryDao.getById(itemId)

    suspend fun getChapters(itemId: String): List<ChapterEntity> = chapterDao.getForItem(itemId)

    suspend fun getChapter(chapterId: String): ChapterEntity? = chapterDao.getById(chapterId)

    suspend fun getProgress(itemId: String): ReadingProgressEntity? = progressDao.getForItem(itemId)

    suspend fun findBySource(pluginId: String, sourceUrl: String): LibraryItemEntity? =
        libraryDao.findBySource(pluginId, sourceUrl)

    suspend fun saveImportedBook(item: LibraryItemEntity, chapters: List<ChapterEntity>) {
        libraryDao.upsert(item)
        chapterDao.upsertAll(chapters)
        // Ensure a default narrator voice exists for novel items
        if (item.mediaType == MediaType.NOVEL && voiceCastDao.getForItem(item.id).isEmpty()) {
            voiceCastDao.upsert(
                CharacterVoiceEntity(
                    id = UUID.randomUUID().toString(),
                    itemId = item.id,
                    characterName = "Người dẫn chuyện",
                    gender = "FEMALE",
                    ageGroup = "ADULT",
                    engineId = "EDGE_TTS",
                    voiceId = "vi-VN-HoaiMyNeural",
                    speechRate = 1.0f,
                    pitchHz = 0,
                    pauseAfterMs = 280,
                    isNarrator = true
                )
            )
        }
        enqueueSyncJob("META", item.id, "UPLOAD_META")
        chapters.filter { it.downloadState == DownloadState.DOWNLOADED && !it.localFilePath.isNullOrBlank() }
            .forEach { ch -> enqueueSyncJob("CHAPTER_FILE", ch.id, "UPLOAD_FILE") }
    }

    suspend fun upsertChapters(chapters: List<ChapterEntity>) {
        chapterDao.upsertAll(chapters)
    }

    suspend fun markChapterDownloaded(chapterId: String, localPath: String, sha256: String) {
        chapterDao.updateLocalFile(chapterId, DownloadState.DOWNLOADED, localPath, sha256)
        enqueueSyncJob("CHAPTER_FILE", chapterId, "UPLOAD_FILE")
    }

    suspend fun markChapterState(chapterId: String, state: DownloadState) {
        val current = chapterDao.getById(chapterId) ?: return
        chapterDao.upsert(current.copy(downloadState = state, updatedAt = System.currentTimeMillis()))
    }

    suspend fun markOpened(itemId: String) {
        libraryDao.markOpened(itemId)
    }

    suspend fun saveProgress(
        itemId: String,
        chapterId: String,
        chapterIndex: Int,
        textScrollOffset: Int = 0,
        textParagraphIndex: Int = 0,
        comicPageIndex: Int = 0,
        audioPositionMs: Long = 0L,
        progressPercent: Float = 0f
    ) {
        val deviceId = settingsRepo.ensureDeviceId()
        val now = System.currentTimeMillis()
        progressDao.upsert(
            ReadingProgressEntity(
                itemId = itemId,
                currentChapterId = chapterId,
                currentChapterIndex = chapterIndex,
                textScrollOffset = textScrollOffset,
                textParagraphIndex = textParagraphIndex,
                comicPageIndex = comicPageIndex,
                audioPositionMs = audioPositionMs,
                progressPercent = progressPercent.coerceIn(0f, 1f),
                updatedAt = now,
                deviceId = deviceId
            )
        )
        enqueueSyncJob("META", itemId, "UPLOAD_META")
    }

    suspend fun addBookmark(
        itemId: String,
        chapterId: String,
        positionLabel: String,
        positionPayloadJson: String,
        note: String = ""
    ) {
        bookmarkDao.upsert(
            BookmarkEntity(
                id = UUID.randomUUID().toString(),
                itemId = itemId,
                chapterId = chapterId,
                positionLabel = positionLabel,
                positionPayloadJson = positionPayloadJson,
                note = note
            )
        )
        enqueueSyncJob("META", itemId, "UPLOAD_META")
    }

    suspend fun deleteBookmark(bookmarkId: String) {
        bookmarkDao.softDelete(bookmarkId)
        enqueueSyncJob("META", bookmarkId, "UPLOAD_META")
    }

    suspend fun getVoiceCast(itemId: String): List<CharacterVoiceEntity> = voiceCastDao.getForItem(itemId)

    suspend fun upsertVoiceCast(voice: CharacterVoiceEntity) {
        voiceCastDao.upsert(voice.copy(updatedAt = System.currentTimeMillis()))
        enqueueSyncJob("META", voice.itemId, "UPLOAD_META")
    }

    suspend fun upsertVoiceCasts(voices: List<CharacterVoiceEntity>) {
        voiceCastDao.upsertAll(voices)
        voices.firstOrNull()?.let { enqueueSyncJob("META", it.itemId, "UPLOAD_META") }
    }

    suspend fun deleteVoiceCast(voiceId: String) {
        voiceCastDao.deleteById(voiceId)
    }

    suspend fun getChapterScript(chapterId: String): ChapterScriptEntity? = scriptDao.getByChapterId(chapterId)

    suspend fun saveChapterScript(script: ChapterScriptEntity) {
        scriptDao.upsert(script)
        enqueueSyncJob("SCRIPT", script.chapterId, "UPLOAD_FILE")
    }

    suspend fun evictLocalCache(itemId: String): Boolean {
        val chapters = chapterDao.getForItem(itemId)
        val hasSynced = chapters.any { !it.driveFileId.isNullOrBlank() }
        if (!hasSynced) return false
        storage.evictItemMedia(itemId)
        chapterDao.markCloudOnlyForItem(itemId)
        libraryDao.updateCachedLocally(itemId, false)
        return true
    }

    suspend fun deleteBook(itemId: String) {
        storage.deleteItemCompletely(itemId)
        libraryDao.softDelete(itemId)
        enqueueSyncJob("META", itemId, "DELETE")
    }

    suspend fun enqueueSyncJob(entityType: String, entityId: String, action: String) {
        syncJobDao.upsert(
            SyncJobEntity(
                id = "${entityType}_${entityId}_${action}",
                entityType = entityType,
                entityId = entityId,
                action = action,
                status = "PENDING",
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // --- Phase 6: Shelves, Chapter Tracking, Highlights, Stats & Pronunciation Glossary ---

    suspend fun updateShelfTag(itemId: String, shelfTag: String) {
        libraryDao.updateShelfTag(itemId, shelfTag.trim())
    }

    suspend fun updateTrackUpdates(itemId: String, enabled: Boolean) {
        libraryDao.updateTrackUpdates(itemId, enabled)
    }

    suspend fun addUnreadNewChapters(itemId: String, delta: Int) {
        if (delta > 0) libraryDao.addUnreadNewChapters(itemId, delta)
    }

    suspend fun clearUnreadNewChapters(itemId: String) {
        libraryDao.clearUnreadNewChapters(itemId)
    }

    fun observeHighlights(itemId: String): Flow<List<HighlightEntity>> = highlightDao.observeForItem(itemId)

    suspend fun getHighlights(itemId: String): List<HighlightEntity> = highlightDao.getForItem(itemId)

    suspend fun addHighlight(
        itemId: String,
        chapterId: String,
        chapterTitle: String,
        paragraphIndex: Int,
        quoteText: String,
        colorHex: Long = 0xFFFFEB3BL,
        note: String = ""
    ) {
        highlightDao.upsert(
            HighlightEntity(
                id = UUID.randomUUID().toString(),
                itemId = itemId,
                chapterId = chapterId,
                chapterTitle = chapterTitle,
                paragraphIndex = paragraphIndex,
                quoteText = quoteText.trim(),
                colorHex = colorHex,
                note = note.trim()
            )
        )
    }

    suspend fun deleteHighlight(highlightId: String) {
        highlightDao.deleteById(highlightId)
    }

    fun observeRecentStats(limit: Int = 30): Flow<List<ReadingSessionEntity>> = statsDao.observeRecent(limit)

    suspend fun recordReadingTime(readSeconds: Long = 0L, listenSeconds: Long = 0L, chaptersDone: Int = 0) {
        if (readSeconds <= 0L && listenSeconds <= 0L && chaptersDone <= 0) return
        val today = LocalDate.now().toString()
        val existing = statsDao.getByDate(today) ?: ReadingSessionEntity(dateKey = today)
        statsDao.upsert(
            existing.copy(
                readingSeconds = existing.readingSeconds + readSeconds.coerceAtLeast(0L),
                listeningSeconds = existing.listeningSeconds + listenSeconds.coerceAtLeast(0L),
                chaptersCompleted = existing.chaptersCompleted + chaptersDone.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun observePronunciationRules(): Flow<List<PronunciationRuleEntity>> = pronunciationDao.observeAll()

    suspend fun getEnabledPronunciationRules(): List<PronunciationRuleEntity> = pronunciationDao.getEnabled()

    suspend fun upsertPronunciationRule(rule: PronunciationRuleEntity) {
        pronunciationDao.upsert(rule)
    }

    suspend fun upsertPronunciationRules(rules: List<PronunciationRuleEntity>) {
        pronunciationDao.upsertAll(rules)
    }

    suspend fun deletePronunciationRule(id: String) {
        pronunciationDao.deleteById(id)
    }
}

object ReadingStatsCalculator {
    fun calculateStreak(sessions: List<ReadingSessionEntity>, today: LocalDate = LocalDate.now()): Int {
        val activeDates = sessions
            .filter { it.readingSeconds > 0L || it.listeningSeconds > 0L || it.chaptersCompleted > 0 }
            .mapNotNull { runCatching { LocalDate.parse(it.dateKey) }.getOrNull() }
            .toSet()
        if (activeDates.isEmpty()) return 0

        var cursor = if (today in activeDates) today else if (today.minusDays(1) in activeDates) today.minusDays(1) else return 0
        var streak = 0
        while (cursor in activeDates) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }
}
