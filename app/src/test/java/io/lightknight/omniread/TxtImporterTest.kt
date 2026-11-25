package io.lightknight.omniread

import io.lightknight.omniread.data.storage.FileStorageManager
import io.lightknight.omniread.engine.importer.TxtImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TxtImporterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun splitsVietnameseChaptersCorrectly() {
        val sample = """
            Lời tựa tác giả.
            
            Chương 1: Khởi đầu mới
            Trời Hà Nội hôm nay se lạnh.
            
            Chương 2: Cuộc gặp gỡ
            Cô ấy mỉm cười chào anh.
        """.trimIndent()

        val chapters = TxtImporter.splitIntoChapters(sample)
        assertEquals(3, chapters.size)
        assertEquals("Mở đầu", chapters[0].title)
        assertEquals("Chương 1: Khởi đầu mới", chapters[1].title)
        assertTrue(chapters[1].content.contains("se lạnh"))
        assertEquals("Chương 2: Cuộc gặp gỡ", chapters[2].title)
    }

    @Test
    fun decodesUtf8BomAndImportsFile() {
        val storage = FileStorageManager(context = null, rootOverride = tempFolder.newFolder("omni"))
        val text = "Chương 1: Tiếng Việt có dấu\nXin chào Ngài."
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)

        val outcome = TxtImporter.import(bomBytes.inputStream(), "truyen_test.txt", storage)
        assertEquals("truyen test", outcome.item.title)
        assertEquals(1, outcome.chapters.size)
        assertEquals("Chương 1: Tiếng Việt có dấu", outcome.chapters[0].title)
    }
}
