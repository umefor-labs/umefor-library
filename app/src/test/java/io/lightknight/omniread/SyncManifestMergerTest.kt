package io.lightknight.omniread

import io.lightknight.omniread.engine.sync.SyncBookmarkDto
import io.lightknight.omniread.engine.sync.SyncChapterDto
import io.lightknight.omniread.engine.sync.SyncItemDto
import io.lightknight.omniread.engine.sync.SyncManifest
import io.lightknight.omniread.engine.sync.SyncManifestMerger
import io.lightknight.omniread.engine.sync.SyncProgressDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncManifestMergerTest {
    @Test
    fun mergesProgressByLatestTimestampAndPreservesBookmarksAndDriveIds() {
        val local = SyncManifest(
            updatedAt = 1000L,
            items = listOf(
                SyncItemDto(
                    id = "book-1",
                    title = "Truyện A",
                    mediaType = "NOVEL",
                    originType = "LOCAL_IMPORT",
                    driveFolderId = null,
                    updatedAt = 2000L
                )
            ),
            chapters = listOf(
                SyncChapterDto(
                    id = "ch-1",
                    itemId = "book-1",
                    chapterIndex = 0,
                    title = "Chương 1",
                    driveFileId = "drv_ch1",
                    updatedAt = 1500L
                )
            ),
            progress = listOf(
                SyncProgressDto(
                    itemId = "book-1",
                    currentChapterId = "ch-1",
                    currentChapterIndex = 0,
                    textParagraphIndex = 12,
                    progressPercent = 0.25f,
                    updatedAt = 1000L,
                    deviceId = "phone-1"
                )
            ),
            bookmarks = listOf(
                SyncBookmarkDto(
                    id = "bm-local",
                    itemId = "book-1",
                    chapterId = "ch-1",
                    positionLabel = "Đoạn 12",
                    positionPayloadJson = "{}",
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            )
        )

        val remote = SyncManifest(
            updatedAt = 2500L,
            items = listOf(
                SyncItemDto(
                    id = "book-1",
                    title = "Truyện A",
                    mediaType = "NOVEL",
                    originType = "LOCAL_IMPORT",
                    driveFolderId = "drv_folder_1",
                    updatedAt = 1500L
                )
            ),
            chapters = listOf(
                SyncChapterDto(
                    id = "ch-1",
                    itemId = "book-1",
                    chapterIndex = 0,
                    title = "Chương 1 (Đã sửa)",
                    driveFileId = null,
                    updatedAt = 2500L
                )
            ),
            progress = listOf(
                SyncProgressDto(
                    itemId = "book-1",
                    currentChapterId = "ch-2",
                    currentChapterIndex = 1,
                    textParagraphIndex = 45,
                    progressPercent = 0.65f,
                    updatedAt = 2500L,
                    deviceId = "tablet-2"
                )
            ),
            bookmarks = listOf(
                SyncBookmarkDto(
                    id = "bm-remote",
                    itemId = "book-1",
                    chapterId = "ch-2",
                    positionLabel = "Đoạn 45",
                    positionPayloadJson = "{}",
                    createdAt = 2500L,
                    updatedAt = 2500L
                )
            )
        )

        val merged = SyncManifestMerger.merge(local, remote)
        assertEquals("drv_folder_1", merged.items.first().driveFolderId)
        assertEquals("drv_ch1", merged.chapters.first().driveFileId)
        assertEquals("Chương 1 (Đã sửa)", merged.chapters.first().title)
        assertEquals(1, merged.progress.first().currentChapterIndex)
        assertEquals("tablet-2", merged.progress.first().deviceId)
        assertEquals(2, merged.bookmarks.size)
        assertTrue(merged.bookmarks.any { it.id == "bm-local" })
        assertTrue(merged.bookmarks.any { it.id == "bm-remote" })
    }
}
