package io.lightknight.omniread

import io.lightknight.omniread.data.local.SourcePluginEntity
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.engine.scraper.PluginHttpBridge
import io.lightknight.omniread.engine.scraper.QuickJsPluginRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EbookPluginsTest {
    private class MockBridge(
        private val gets: Map<String, String>,
        private val posts: Map<Pair<String, String>, String> = emptyMap(),
        private val webViews: Map<String, String> = emptyMap()
    ) : PluginHttpBridge {
        override suspend fun httpGet(url: String, headers: Map<String, String>): String =
            gets[url] ?: error("Unexpected GET $url")

        override suspend fun httpPost(url: String, body: String, headers: Map<String, String>): String =
            posts[url to body] ?: error("Unexpected POST $url body=$body")

        override suspend fun webViewGet(url: String, waitMs: Long): String =
            webViews[url] ?: httpGet(url)
    }

    @Test
    fun testsSachVuiiWpSearchAndCoverEnrichment() = runTest {
        val script = File("src/main/assets/plugins/sachvuii.js").readText(Charsets.UTF_8)
        val plugin = SourcePluginEntity("sachvuii", "Sách Vui", MediaType.NOVEL, baseUrl = "https://sachvuii.com", scriptContent = script)
        val bridge = MockBridge(
            gets = mapOf(
                "https://sachvuii.com/wp-json/wp/v2/search?search=t%C3%A2m%20l%C3%BD%20h%E1%BB%8Dc&per_page=20&page=1&subtype=post" to """
                    [
                      {"id":227114,"title":"Tâm Lý Học Xuyên Văn Hóa","url":"https://sachvuii.com/tam-ly-hoc-xuyen-van-hoa/"},
                      {"id":227104,"title":"Tâm Lý Học Đám Đông &#8211; Sigmund Freud","url":"https://sachvuii.com/tam-ly-hoc-dam-dong/"}
                    ]
                """.trimIndent(),
                "https://sachvuii.com/wp-json/wp/v2/posts?include=227114,227104&per_page=20&_embed=1" to """
                    [
                      {"id":227114,"_embedded":{"wp:featuredmedia":[{"source_url":"https://sachvuii.com/cov1.jpg"}]}},
                      {"id":227104,"_embedded":{"wp:featuredmedia":[{"source_url":"https://sachvuii.com/cov2.jpg"}]}}
                    ]
                """.trimIndent(),
                "https://sachvuii.com/tam-ly-hoc-xuyen-van-hoa/" to """
                    <html><body>
                      <h1>Tâm Lý Học Xuyên Văn Hóa</h1>
                      <a href="/get-link/?type=epub&truyen=1">Tải EPUB</a>
                    </body></html>
                """.trimIndent(),
                "https://sachvuii.com/wp-json/wp/v2/posts?slug=tam-ly-hoc-xuyen-van-hoa" to """
                    [{"content":{"rendered":"<p>Đoạn 1: Tâm lý học nghiên cứu hành vi.</p>"}}]
                """.trimIndent()
            )
        )
        val runner = QuickJsPluginRunner(bridge)
        val found = runner.search(plugin, "tâm lý học", 1).getOrThrow()
        assertEquals(2, found.size)
        assertEquals("Tâm Lý Học Xuyên Văn Hóa", found[0].title)
        assertEquals("https://sachvuii.com/cov1.jpg", found[0].coverUrl)
        val content = runner.getChapterContent(plugin, found[0].url).getOrThrow()
        assertTrue(content.paragraphs.any { it.contains("Tâm lý học nghiên cứu hành vi") })
        assertTrue(content.paragraphs.any { it.contains("/get-link/?type=epub") })
    }

    @Test
    fun testsDoclnWebViewDecryptionStripsHiddenTitleTrap() = runTest {
        val doclnScript = File("src/main/assets/plugins/docln_hako.js").readText(Charsets.UTF_8)
        val bridge = MockBridge(
            gets = mapOf(
                "https://docln.sbs/tim-kiem?keywords=heavy%20object&page=1" to """
                    <div class="thumb-item-flow">
                      <div class="img-in-ratio" data-bg="https://i2.hako.vip/cov.jpg"></div>
                      <div class="series-title"><a href="/truyen/24652-heacy-object">Heavy Object</a></div>
                    </div>
                """.trimIndent()
            ),
            webViews = mapOf(
                "https://docln.sbs/truyen/24652-heacy-object/c291306-mo-dau" to """
                    <div id="chapter-content">
                      <p style="display: none">Mở đầu</p>
                      <p>Đoạn 1: Chiến trường Alaska tuyết phủ trắng xóa.</p>
                      <p>Đoạn 2: Quenser kiểm tra lại bộ đàm liên lạc.</p>
                    </div>
                """.trimIndent()
            )
        )
        val runner = QuickJsPluginRunner(bridge)
        val docln = SourcePluginEntity("docln_hako", "DocLN", MediaType.NOVEL, baseUrl = "https://docln.sbs", scriptContent = doclnScript)
        val lnList = runner.search(docln, "heavy object", 1).getOrThrow()
        assertEquals("Heavy Object", lnList[0].title)

        val ch = runner.getChapterContent(docln, "https://docln.sbs/truyen/24652-heacy-object/c291306-mo-dau").getOrThrow()
        assertFalse(ch.paragraphs.any { it == "Mở đầu" })
        assertEquals(2, ch.paragraphs.size)
        assertTrue(ch.paragraphs[0].contains("Chiến trường Alaska"))
    }

    @Test
    fun testsDownloadSachMienPhiSearchCoverAndFiltersAffiliateLinks() = runTest {
        val script = File("src/main/assets/plugins/downloadsachmienphi.js").readText(Charsets.UTF_8)
        val plugin = SourcePluginEntity(
            id = "downloadsachmienphi",
            name = "DownloadSáchMiễnPhí",
            MediaType.NOVEL,
            baseUrl = "https://downloadsachmienphi.com",
            scriptContent = script
        )
        val bridge = MockBridge(
            gets = mapOf(
                "https://downloadsachmienphi.com/wp-json/wp/v2/search?search=%C4%91%E1%BA%AFc%20nh%C3%A2n%20t%C3%A2m&per_page=20&page=1&subtype=post" to """
                    [{"id":75779,"title":"Đắc Nhân Tâm","url":"https://downloadsachmienphi.com/dac-nhan-tam/"}]
                """.trimIndent(),
                "https://downloadsachmienphi.com/wp-json/wp/v2/posts?include=75779&per_page=20&_embed=1" to """
                    [{"id":75779,"content":{"rendered":"<img src=\"https://bp.blogspot.com/dacnhantam.jpg\" />"}}]
                """.trimIndent(),
                "https://downloadsachmienphi.com/wp-json/wp/v2/posts?slug=dac-nhan-tam" to """
                    [{
                      "content": {
                        "rendered": "<p>Đắc nhân tâm là nghệ thuật thu phục lòng người.</p><a href=\"https://pub.accesstrade.vn/shopee\">Mua sách</a><a href=\"https://mega.nz/folder/123\">PDF</a>"
                      }
                    }]
                """.trimIndent()
            )
        )
        val runner = QuickJsPluginRunner(bridge)
        val res = runner.search(plugin, "đắc nhân tâm", 1).getOrThrow()
        assertEquals(1, res.size)
        assertEquals("Đắc Nhân Tâm", res[0].title)
        assertEquals("https://bp.blogspot.com/dacnhantam.jpg", res[0].coverUrl)

        val chapters = runner.getChapters(plugin, res[0].url).getOrThrow()
        assertEquals(1, chapters.size)
        assertEquals("https://mega.nz/folder/123", chapters[0].url)

        val content = runner.getChapterContent(plugin, res[0].url).getOrThrow()
        assertTrue(content.paragraphs.any { it.contains("nghệ thuật thu phục lòng người") })
        assertTrue(content.paragraphs.any { it.contains("https://mega.nz/folder/123") })
        assertFalse(content.paragraphs.any { it.contains("accesstrade") })
    }

    @Test
    fun testsDtvEbookUsesWpSearchParamAndFiltersByQuery() = runTest {
        val dtvScript = File("src/main/assets/plugins/dtv_ebook.js").readText(Charsets.UTF_8)
        val bridge = MockBridge(
            gets = mapOf(
                "https://dtv-ebook.com.vn/?s=tuyet%20trung" to """
                    <main>
                      <div class="item"><a href="/tuyet-trung-han-dao-hanh.html">Tuyết Trung Hãn Đao Hành</a></div>
                      <div class="item"><a href="/giao-dich-chung-khoan.html">Giao Dịch Kiếm Sống</a></div>
                    </main>
                """.trimIndent()
            )
        )
        val dtv = SourcePluginEntity("dtv_ebook", "DTV", MediaType.NOVEL, baseUrl = "https://dtv-ebook.com.vn", scriptContent = dtvScript)
        val res = QuickJsPluginRunner(bridge).search(dtv, "tuyet trung", 1).getOrThrow()
        assertEquals(1, res.size)
        assertEquals("Tuyết Trung Hãn Đao Hành", res[0].title)
    }
}
