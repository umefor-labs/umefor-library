package io.lightknight.omniread

import io.lightknight.omniread.engine.importer.MegaLinkKind
import io.lightknight.omniread.engine.importer.MegaPublicDownloader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MegaPublicDownloaderTest {

    @Test
    fun parseMegaLinkSupportsModernAndLegacyUrls() {
        val folder = MegaPublicDownloader.parseMegaLink("https://mega.nz/folder/JyggnTbY#HYqrSxNoYdnwTG2KYChElQ")
        assertNotNull(folder)
        assertEquals(MegaLinkKind.FOLDER, folder?.kind)
        assertEquals("JyggnTbY", folder?.id)
        assertEquals("HYqrSxNoYdnwTG2KYChElQ", folder?.keyBase64)

        val file = MegaPublicDownloader.parseMegaLink("https://mega.nz/file/VnwUCIDJ#sXC3psj5eqn4xnHsB1bD6-WAMI-gKenOm6SCXscVkqI")
        assertNotNull(file)
        assertEquals(MegaLinkKind.FILE, file?.kind)
        assertEquals("VnwUCIDJ", file?.id)

        val legacyFolder = MegaPublicDownloader.parseMegaLink("https://mega.nz/#F!abc123!key987")
        assertEquals(MegaLinkKind.FOLDER, legacyFolder?.kind)
        assertEquals("abc123", legacyFolder?.id)

        assertNull(MegaPublicDownloader.parseMegaLink("https://example.com/book.html"))
    }

    @Test
    fun pickBestEbookFileNamePrefersEpubOverPdfAndSkipsAzw3Mobi() {
        val mixed = listOf(
            "80 Ngay.mobi",
            "80 Ngay.azw3",
            "80 Ngay.pdf",
            "80 Ngay.epub"
        )
        assertEquals(3, MegaPublicDownloader.pickBestEbookFileName(mixed))

        val onlyPdfAndAzw3 = listOf("book.azw3", "book.pdf")
        assertEquals(1, MegaPublicDownloader.pickBestEbookFileName(onlyPdfAndAzw3))

        val unsupportedOnly = listOf("book.azw3", "book.mobi")
        assertEquals(-1, MegaPublicDownloader.pickBestEbookFileName(unsupportedOnly))
    }

    @Test
    fun aesKeyDerivationAndAttributeDecryptionRoundTrip() {
        val folderKey16 = ByteArray(16) { (it + 1).toByte() }
        val plainNodeKey32 = ByteArray(32) { (it * 3 + 7).toByte() }

        val ecbCipher = Cipher.getInstance("AES/ECB/NoPadding")
        ecbCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(folderKey16, "AES"))
        val encryptedNodeKey = ecbCipher.doFinal(plainNodeKey32)

        val decryptedNodeKey = MegaPublicDownloader.decryptNodeKey(encryptedNodeKey, folderKey16)
        assertArrayEquals(plainNodeKey32, decryptedNodeKey)

        val (aesKey16, ctrIv16) = MegaPublicDownloader.deriveFileKeyAndIv(decryptedNodeKey)
        assertEquals(16, aesKey16.size)
        assertEquals(16, ctrIv16.size)
        for (i in 0 until 8) assertEquals(plainNodeKey32[16 + i], ctrIv16[i])
        for (i in 8 until 16) assertEquals(0.toByte(), ctrIv16[i])

        val attrPlain = """MEGA{"n":"80 Ngay Vong Quanh The Gioi.epub"}"""
        val paddedLen = ((attrPlain.toByteArray().size + 15) / 16) * 16
        val padded = attrPlain.toByteArray(Charsets.UTF_8).copyOf(paddedLen)
        val cbcCipher = Cipher.getInstance("AES/CBC/NoPadding")
        cbcCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey16, "AES"), IvParameterSpec(ByteArray(16)))
        val encAttrB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(cbcCipher.doFinal(padded))

        val extractedName = MegaPublicDownloader.decryptMegaAttributes(encAttrB64, aesKey16)
        assertEquals("80 Ngay Vong Quanh The Gioi.epub", extractedName)
    }

    @Test
    fun extractDownloadableEbookUrlFindsMegaAndDriveLinks() {
        val lines = listOf(
            "Giới thiệu sách: 80 Ngày Vòng Quanh Thế Giới",
            "[Tải sách (PDF)]",
            "https://mega.nz/folder/JyggnTbY#HYqrSxNoYdnwTG2KYChElQ"
        )
        assertEquals(
            "https://mega.nz/folder/JyggnTbY#HYqrSxNoYdnwTG2KYChElQ",
            MegaPublicDownloader.extractDownloadableEbookUrl(lines)
        )
        assertTrue(MegaPublicDownloader.isDownloadableEbookUrl("https://drive.google.com/file/d/1AbCdEf/view"))
        assertFalse(MegaPublicDownloader.isDownloadableEbookUrl("https://downloadsachmienphi.com/80-ngay/"))
    }
}
