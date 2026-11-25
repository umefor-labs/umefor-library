package io.lightknight.omniread

import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.storage.FileStorageManager
import io.lightknight.omniread.engine.importer.EpubImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubImporterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parsesValidEpubWithVietnameseAndToc() {
        val epubBytes = buildSampleEpub()
        val storage = FileStorageManager(context = null, rootOverride = tempFolder.newFolder("omni"))
        val outcome = EpubImporter.import(epubBytes.inputStream(), "light_novel.epub", storage)

        assertEquals("Kiếm Khách Mùa Đông", outcome.item.title)
        assertEquals("Xuân Thành", outcome.item.author)
        assertEquals(MediaType.NOVEL, outcome.item.mediaType)
        assertNotNull(outcome.item.coverLocalPath)
        assertEquals(2, outcome.chapters.size)
        assertEquals("Chương 1: Gió Bắc", outcome.chapters[0].title)
        assertTrue(outcome.chapters[0].localFilePath != null)
    }

    private fun buildSampleEpub(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fun put(name: String, content: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                   <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                     <rootfiles>
                       <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                     </rootfiles>
                   </container>""".trimIndent().toByteArray()
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
                   <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                     <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                       <dc:title>Kiếm Khách Mùa Đông</dc:title>
                       <dc:creator>Xuân Thành</dc:creator>
                       <meta name="cover" content="cover-img"/>
                     </metadata>
                     <manifest>
                       <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                       <item id="cover-img" href="images/cover.jpg" media-type="image/jpeg"/>
                       <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                       <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                     </manifest>
                     <spine>
                       <itemref idref="ch1"/>
                       <itemref idref="ch2"/>
                     </spine>
                   </package>""".trimIndent().toByteArray()
            )
            put(
                "OEBPS/nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><body>
                   <nav><ol>
                     <li><a href="text/ch1.xhtml">Chương 1: Gió Bắc</a></li>
                     <li><a href="text/ch2.xhtml">Chương 2: Tuyết Rơi</a></li>
                   </ol></nav></body></html>""".toByteArray()
            )
            put("OEBPS/images/cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            put("OEBPS/text/ch1.xhtml", "<html><body><p>Đoạn mở đầu.</p><p>Tiếp theo.</p></body></html>".toByteArray())
            put("OEBPS/text/ch2.xhtml", "<html><body><p>Chương hai.</p></body></html>".toByteArray())
        }
        return baos.toByteArray()
    }
}
