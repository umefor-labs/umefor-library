package io.lightknight.omniread

import io.lightknight.omniread.data.local.SourcePluginEntity
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.engine.scraper.PluginHttpBridge
import io.lightknight.omniread.engine.scraper.QuickJsPluginRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QuickJsPluginRunnerTest {
    private class MockBridge(private val responses: Map<String, String>) : PluginHttpBridge {
        override suspend fun httpGet(url: String, headers: Map<String, String>): String =
            responses[url] ?: error("Unexpected GET: $url")

        override suspend fun httpPost(url: String, body: String, headers: Map<String, String>): String = ""
        override suspend fun webViewGet(url: String, waitMs: Long): String = httpGet(url)
    }

    @Test
    fun runsMangadexPluginAgainstJsonFixture() = runTest {
        val script = File("src/main/assets/plugins/mangadex.js").readText(Charsets.UTF_8)
        val plugin = SourcePluginEntity(
            id = "mangadex",
            name = "MangaDex",
            mediaType = MediaType.COMIC,
            baseUrl = "https://api.mangadex.org",
            scriptContent = script
        )
        val bridge = MockBridge(
            mapOf(
                "https://api.mangadex.org/manga?title=frieren&limit=20&offset=0&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive" to """
                    {
                      "data": [{
                        "id": "m-1",
                        "attributes": {"title": {"vi": "Frieren Pháp Sư"}},
                        "relationships": [{"type": "cover_art", "attributes": {"fileName": "cov.jpg"}}]
                      }]
                    }
                """.trimIndent(),
                "https://api.mangadex.org/manga/m-1?includes[]=cover_art&includes[]=author" to """
                    {
                      "data": {
                        "id": "m-1",
                        "attributes": {"title": {"vi": "Frieren Pháp Sư"}, "description": {"vi": "Hành trình sau khi diệt Quỷ Vương"}},
                        "relationships": [{"type": "author", "attributes": {"name": "Kanehito Yamada"}}]
                      }
                    }
                """.trimIndent(),
                "https://api.mangadex.org/manga/m-1/feed?translatedLanguage[]=vi&translatedLanguage[]=en&order[chapter]=asc&limit=100" to """
                    {
                      "data": [{
                        "id": "c-1",
                        "attributes": {"chapter": "1", "title": "Khởi hành", "translatedLanguage": "vi"}
                      }]
                    }
                """.trimIndent(),
                "https://api.mangadex.org/at-home/server/c-1" to """
                    {
                      "baseUrl": "https://cdn.example.com",
                      "chapter": {"hash": "abc123", "dataSaver": ["p1.jpg", "p2.jpg"]}
                    }
                """.trimIndent()
            )
        )

        val runner = QuickJsPluginRunner(bridge)
        val searchRes = runner.search(plugin, "frieren", 1).getOrThrow()
        assertEquals(1, searchRes.size)
        assertEquals("Frieren Pháp Sư", searchRes[0].title)

        val detail = runner.getDetail(plugin, searchRes[0].url).getOrThrow()
        assertEquals("Kanehito Yamada", detail.author)

        val chapters = runner.getChapters(plugin, searchRes[0].url).getOrThrow()
        assertEquals(1, chapters.size)
        assertTrue(chapters[0].title.contains("Khởi hành"))

        val content = runner.getChapterContent(plugin, chapters[0].url).getOrThrow()
        assertEquals("COMIC", content.kind)
        assertEquals(2, content.images.size)
        assertEquals("https://cdn.example.com/data-saver/abc123/p1.jpg", content.images[0].url)
    }

    @Test
    fun runsHtmlNovelPluginWithJsoupBridge() = runTest {
        val script = File("src/main/assets/plugins/truyenfull_vn.js").readText(Charsets.UTF_8)
        val plugin = SourcePluginEntity(
            id = "truyenfull_vn",
            name = "TruyenFull",
            mediaType = MediaType.NOVEL,
            baseUrl = "https://truyen.local",
            scriptContent = script
        )
        val bridge = MockBridge(
            mapOf(
                "https://truyen.local/ch1" to """
                    <html><body>
                      <div id="chapter-c">
                        <p>Đoạn văn thứ nhất.</p>
                        <script>badAd();</script>
                        <p>Đoạn văn thứ hai.</p>
                      </div>
                    </body></html>
                """.trimIndent()
            )
        )
        val runner = QuickJsPluginRunner(bridge)
        val content = runner.getChapterContent(plugin, "https://truyen.local/ch1").getOrThrow()
        assertEquals("NOVEL", content.kind)
        assertEquals(listOf("Đoạn văn thứ nhất.", "Đoạn văn thứ hai."), content.paragraphs)
    }

    @Test
    fun escapesControlCharsAndExtractsDoclnChapters() = runTest {
        val escaped = QuickJsPluginRunner.escapeJson("a\u0007b\u000Cc\u2028d\u2029\"e\"")
        assertEquals("""a\u0007b\fc\u2028d\u2029\"e\"""", escaped)

        val doclnScript = File("src/main/assets/plugins/docln_hako.js").readText(Charsets.UTF_8)
        val plugin = SourcePluginEntity(
            id = "docln_hako",
            name = "DocLN",
            mediaType = MediaType.NOVEL,
            baseUrl = "https://docln.sbs",
            scriptContent = doclnScript
        )
        val sampleBookHtml = """
            <html><body>
              <div class="series-name"><a href="/truyen/26-heavy-object">Heavy Object</a></div>
              <div class="info-item"><a href="/tac-gia/kamachi-kazuma">Kamachi Kazuma</a></div>
              <div class="info-item"><a href="/tinh-trang">Tạm ngưng</a></div>
              <div class="summary-content">Tóm tắt thử nghiệm</div>
              <ul class="list-chapters">
                <li><div class="chapter-name"><a href="/truyen/26-heavy-object/c40633-mo-dau">Mở đầu</a></div></li>
                <li><div class="chapter-name"><a href="/truyen/26-heavy-object/c40634-chuong-1">Chương 1</a></div></li>
              </ul>
            </body></html>
        """.trimIndent()

        var getCount = 0
        val countingBridge = object : PluginHttpBridge {
            override suspend fun httpGet(url: String, headers: Map<String, String>): String {
                getCount++
                return sampleBookHtml
            }

            override suspend fun httpPost(url: String, body: String, headers: Map<String, String>): String = ""
            override suspend fun webViewGet(url: String, waitMs: Long): String = sampleBookHtml
        }
        val runner = QuickJsPluginRunner(countingBridge)
        val detail = runner.getDetail(plugin, "https://docln.sbs/truyen/26-heavy-object/c40633-mo-dau").getOrThrow()
        assertEquals("https://docln.sbs/truyen/26-heavy-object", detail.url)
        assertEquals("Kamachi Kazuma", detail.author)

        val chapters = runner.getChapters(plugin, detail.url).getOrThrow()
        assertEquals(2, chapters.size)
        assertEquals("Mở đầu", chapters[0].title)
        assertEquals("https://docln.sbs/truyen/26-heavy-object/c40634-chuong-1", chapters[1].url)
        assertTrue(getCount == 2)
    }
}
