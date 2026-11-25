package io.lightknight.omniread.data.storage

import android.content.Context
import java.io.File

/**
 * Manages internal app storage for imported books, downloaded chapters, audiobooks,
 * covers, TTS cache, and AI dialogue scripts.
 */
class FileStorageManager(
    private val context: Context?,
    private val rootOverride: File? = null
) {
    val rootDir: File
        get() = (rootOverride ?: File(requireNotNull(context).filesDir, "omniread")).apply { mkdirs() }

    val libraryRoot: File
        get() = File(rootDir, "library").apply { mkdirs() }

    val coversDir: File
        get() = File(rootDir, "covers").apply { mkdirs() }

    val ttsCacheDir: File
        get() = File(rootDir, "tts_cache").apply { mkdirs() }

    val scriptsDir: File
        get() = File(rootDir, "scripts").apply { mkdirs() }

    val inboxTempDir: File
        get() = File(rootDir, "inbox_temp").apply { mkdirs() }

    fun itemDir(itemId: String): File = File(libraryRoot, itemId).apply { mkdirs() }

    fun itemChaptersDir(itemId: String): File = File(itemDir(itemId), "chapters").apply { mkdirs() }

    fun itemAudioDir(itemId: String): File = File(itemDir(itemId), "audio").apply { mkdirs() }

    fun coverFile(itemId: String, ext: String = "jpg"): File = File(coversDir, "$itemId.$ext")

    fun scriptFile(chapterId: String): File = File(scriptsDir, "$chapterId.json")

    fun ttsSegmentFile(chapterId: String, segmentIndex: Int, ext: String = "mp3"): File {
        val dir = File(ttsCacheDir, chapterId).apply { mkdirs() }
        return File(dir, "seg_%04d.%s".format(segmentIndex, ext))
    }

    fun chapterAudioExportFile(itemId: String, chapterId: String): File {
        val dir = File(itemDir(itemId), "tts_audio").apply { mkdirs() }
        return File(dir, "$chapterId.mp3")
    }

    fun evictItemMedia(itemId: String) {
        val dir = File(libraryRoot, itemId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    fun deleteItemCompletely(itemId: String) {
        evictItemMedia(itemId)
        coversDir.listFiles()?.filter { it.nameWithoutExtension == itemId }?.forEach { it.delete() }
    }

    fun calculateLibraryBytes(): Long = calculateSize(libraryRoot)
    fun calculateTtsCacheBytes(): Long = calculateSize(ttsCacheDir)

    fun clearTtsCache() {
        ttsCacheDir.deleteRecursively()
        ttsCacheDir.mkdirs()
    }

    private fun calculateSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
