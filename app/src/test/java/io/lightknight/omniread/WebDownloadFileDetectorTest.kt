package io.lightknight.omniread

import io.lightknight.omniread.engine.importer.WebDownloadFileDetector
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebDownloadFileDetectorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parsesRfc5987AndStandardContentDispositionHeaders() {
        val name1 = WebDownloadFileDetector.extractFileNameFromHeaders(
            url = "https://dtv-ebook.com.vn/download/123.bin",
            contentDisposition = "attachment; filename*=UTF-8''Ki%E1%BA%BFm%20Lai.epub",
            mimeType = "application/octet-stream"
        )
        assertEquals("Kiếm Lai.epub", name1)

        val name2 = WebDownloadFileDetector.extractFileNameFromHeaders(
            url = "https://example.com/get?id=9",
            contentDisposition = """inline; filename="Dac_Nhan_Tam.pdf"""",
            mimeType = "application/octet-stream"
        )
        assertEquals("Dac_Nhan_Tam.pdf", name2)
    }

    @Test
    fun detectsEpubPdfZipBundleAndHtmlFromBinFiles() {
        val dir = tempFolder.newFolder("detect")

        // 1. EPUB disguised as .bin
        val epubBin = File(dir, "download.bin")
        ZipOutputStream(epubBin.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write("<container/>".toByteArray())
            zos.closeEntry()
        }
        assertEquals("epub", WebDownloadFileDetector.detectFormatByMagicBytes(epubBin))

        // 2. PDF disguised as .bin
        val pdfBin = File(dir, "file.bin")
        pdfBin.writeBytes("%PDF-1.7\n%sample".toByteArray())
        assertEquals("pdf", WebDownloadFileDetector.detectFormatByMagicBytes(pdfBin))

        // 3. ZIP bundle containing book.epub
        val zipBundle = File(dir, "bundle.bin")
        val innerBaos = ByteArrayOutputStream()
        ZipOutputStream(innerBaos).use { zos ->
            zos.putNextEntry(ZipEntry("book.epub"))
            zos.write("dummy".toByteArray())
            zos.closeEntry()
        }
        zipBundle.writeBytes(innerBaos.toByteArray())
        assertEquals("zip_bundle", WebDownloadFileDetector.detectFormatByMagicBytes(zipBundle))

        // 4. HTML countdown/redirect disguised as .bin
        val htmlBin = File(dir, "redirect.bin")
        htmlBin.writeText("  <!DOCTYPE html><html><body>Wait 5s...</body></html>")
        assertEquals("html_page", WebDownloadFileDetector.detectFormatByMagicBytes(htmlBin))
    }
}
