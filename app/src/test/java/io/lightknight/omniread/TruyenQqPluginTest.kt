package io.lightknight.omniread

import io.lightknight.omniread.data.local.SourcePluginEntity
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.engine.scraper.PluginHttpBridge
import io.lightknight.omniread.engine.scraper.QuickJsPluginRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TruyenQqPluginTest {
    private class MockBridge(
        private val gets: Map<String, String>,
        private val posts: Map<Pair<String, String>, String>
    ) : PluginHttpBridge {
        override suspend fun httpGet(url: String, headers: Map<String, String>): String =
            gets[url] ?: error("Unexpected GET $url")

        override suspend fun httpPost(url: String, body: String, headers: Map<String, String>): String =
            posts[url to body] ?: error("Unexpected POST $url body=$body")

        override suspend fun webViewGet(url: String, waitMs: Long): String = httpGet(url)
    }

    @Test
    fun testsTruyenQqPopularSearchDetailChaptersAndImages() = runTest {
        val script = File("src/main/assets/plugins/truyenqqko.js").readText(Charsets.UTF_8)
        val plugin = SourcePluginEntity(
            id = "truyenqqko",
            name = "TruyenQQ",
            mediaType = MediaType.COMIC,
            baseUrl = "https://truyenqqko.com",
            scriptContent = script
        )

        val bridge = MockBridge(
            gets = mapOf(
                "https://truyenqqko.com/truyen-moi-cap-nhat/trang-1" to """
                    <ul class="list_grid grid"><li>
                      <div class="book_avatar"><a href="/truyen-tranh/dai-quan-gia-7015"><img src="https://img.example/1.jpg" alt="Đại Quản Gia Là Ma Hoàng" /></a></div>
                      <div class="book_name"><a href="/truyen-tranh/dai-quan-gia-7015">Đại Quản Gia Là Ma Hoàng</a></div>
                    </li></ul>
                """.trimIndent(),
                "https://truyenqqko.com/truyen-tranh/dai-quan-gia-7015" to """
                    <h1>Đại Quản Gia Là Ma Hoàng</h1>
                    <div class="book_avatar"><img src="https://img.example/cover.jpg" /></div>
                    <li class="author"><p class="col-xs-9">Dạ Kiêu</p></li>
                    <div class="story-detail-info">Trác Phàm trùng sinh.</div>
                    <div class="works-chapter-item"><a href="/truyen-tranh/dai-quan-gia-7015-chap-2.html">Chương 2</a></div>
                    <div class="works-chapter-item"><a href="/truyen-tranh/dai-quan-gia-7015-chap-1.html">Chương 1</a></div>
                """.trimIndent(),
                "https://truyenqqko.com/truyen-tranh/dai-quan-gia-7015-chap-1.html" to """
                    <div id="page_0" class="page-chapter"><img data-original="https://cdn.truyenvua.com/1/0.jpg" src="https://cdn.truyenvua.com/1/0.jpg" /></div>
                    <div id="page_1" class="page-chapter"><img data-original="https://cdn.truyenvua.com/1/1.jpg" src="https://cdn.truyenvua.com/1/1.jpg" /></div>
                """.trimIndent()
            ),
            posts = mapOf(
                ("https://truyenqqko.com/frontend/search/search" to "search=%C4%91%E1%BA%A1i%20qu%E1%BA%A3n%20gia&type=0") to """
                    <ul><li>
                      <a href="https://truyenqqko.com/truyen-tranh/dai-quan-gia-7015">
                        <img src="https://img.example/thumb.jpg" />
                        <p class="name">Đại Quản Gia Là Ma Hoàng</p>
                      </a>
                    </li></ul>
                """.trimIndent()
            )
        )

        val runner = QuickJsPluginRunner(bridge)
        val pop = runner.popular(plugin, 1).getOrThrow()
        assertEquals(1, pop.size)
        assertEquals("https://truyenqqko.com/truyen-tranh/dai-quan-gia-7015", pop[0].url)
        assertEquals("https://img.example/1.jpg", pop[0].coverUrl)

        val search = runner.search(plugin, "đại quản gia", 1).getOrThrow()
        assertEquals("Đại Quản Gia Là Ma Hoàng", search[0].title)
        assertEquals("https://img.example/thumb.jpg", search[0].coverUrl)

        val detail = runner.getDetail(plugin, pop[0].url).getOrThrow()
        assertEquals("Dạ Kiêu", detail.author)
        assertEquals("COMIC", detail.mediaType)

        val chapters = runner.getChapters(plugin, pop[0].url).getOrThrow()
        assertEquals(2, chapters.size)
        assertEquals("Chương 1", chapters[0].title)

        val content = runner.getChapterContent(plugin, chapters[0].url).getOrThrow()
        assertEquals(2, content.images.size)
        assertEquals("https://truyenqqko.com/", content.images[0].referer)
    }
}
