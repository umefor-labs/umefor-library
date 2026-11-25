package io.lightknight.omniread

import io.lightknight.omniread.engine.scraper.CloudflareDohDns
import io.lightknight.omniread.engine.scraper.QuickJsPluginRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class CloudflareDohDnsTest {
    @Test
    fun parsesDohJsonAnswerRecordsAndFormatsFriendlyErrors() {
        val json = """
            {"Status":0,"Answer":[{"name":"docln.sbs","type":5,"data":"proxy.cdn.net."},{"name":"docln.sbs","type":1,"data":"104.21.10.5"},{"name":"docln.sbs","type":1,"data":"172.67.130.8"}]}
        """.trimIndent()
        val ips = CloudflareDohDns.parseDohAddresses(json)
        assertEquals(listOf("104.21.10.5", "172.67.130.8"), ips)

        val friendly = QuickJsPluginRunner.formatFriendlyPluginError(
            UnknownHostException("Unable to resolve host \"example.invalid\""),
            "https://example.invalid"
        )
        assertTrue(friendly.contains("Không tìm thấy tên miền"))
    }
}
