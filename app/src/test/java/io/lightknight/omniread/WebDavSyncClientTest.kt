package io.lightknight.omniread

import io.lightknight.omniread.engine.sync.WebDavSyncClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavSyncClientTest {
    @Test
    fun buildsEncodedUrlsCorrectly() {
        assertEquals(
            "https://app.koofr.net/dav/Koofr/UmeforLibrary/Inbox/S%C3%A1ch%20m%E1%BB%9Bi.epub",
            WebDavSyncClient.buildPathUrl("https://app.koofr.net/dav/Koofr/", "/UmeforLibrary/Inbox/Sách mới.epub")
        )
        assertEquals(
            "https://node1.teracloud.jp/dav",
            WebDavSyncClient.buildPathUrl("https://node1.teracloud.jp/dav/", "")
        )
    }

    @Test
    fun parsesMultiNamespacePropfindXmlAndSkipsCurrentDirectory() {
        val sampleXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/Koofr/UmeforLibrary/Inbox/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/Koofr/UmeforLibrary/Inbox/Imported/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/Koofr/UmeforLibrary/Inbox/Heavy%20Object.epub</D:href>
                <D:propstat>
                  <D:prop>
                    <D:resourcetype/>
                    <D:getcontentlength>4521984</D:getcontentlength>
                  </D:prop>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val entries = WebDavSyncClient.parsePropfindXml(sampleXml, "/dav/Koofr/UmeforLibrary/Inbox")
        assertEquals(2, entries.size)
        assertEquals("Imported", entries[0].name)
        assertTrue(entries[0].isDirectory)
        assertEquals("Heavy Object.epub", entries[1].name)
        assertFalse(entries[1].isDirectory)
        assertEquals(4521984L, entries[1].size)
    }
}
