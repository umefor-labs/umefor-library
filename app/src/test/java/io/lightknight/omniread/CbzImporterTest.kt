package io.lightknight.omniread

import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.storage.FileStorageManager
import io.lightknight.omniread.engine.importer.CbzImporter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CbzImporterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun importsMultiChapterCbzWithNaturalPageOrder() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            listOf(
                "ch2/page_10.jpg" to "c2p10",
                "ch2/page_2.jpg" to "c2p2",
                "ch1/page_10.jpg" to "c1p10",
                "ch1/page_1.jpg" to "c1p1",
                "ch1/page_2.jpg" to "c1p2"
            ).forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }

        val storage = FileStorageManager(context = null, rootOverride = tempFolder.newFolder("omni"))
        val outcome = CbzImporter.import(baos.toByteArray().inputStream(), "manga_demo.cbz", storage)

        assertEquals(MediaType.COMIC, outcome.item.mediaType)
        assertEquals(2, outcome.chapters.size)
        assertEquals("ch1", outcome.chapters[0].title)
        assertEquals(3, outcome.chapters[0].pageCount)
        assertEquals("ch2", outcome.chapters[1].title)
        assertEquals(2, outcome.chapters[1].pageCount)

        val ch1Files = File(outcome.chapters[0].localFilePath!!).listFiles()!!.sortedBy { it.name }
        assertEquals("c1p1", ch1Files[0].readText())
        assertEquals("c1p2", ch1Files[1].readText())
        assertEquals("c1p10", ch1Files[2].readText())
    }
}
