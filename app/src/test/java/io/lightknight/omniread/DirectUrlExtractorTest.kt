package io.lightknight.omniread

import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.engine.scraper.DirectUrlExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectUrlExtractorTest {
    @Test
    fun extractsMainContentStripsAdsAndDetectsNextChapter() {
        val html = """
            <html>
              <head><title>Chương 10: Kiếm Ý</title></head>
              <body>
                <nav><a href="/home">Trang chủ</a> <a href="/hot">Hot</a></nav>
                <div class="ads">Quảng cáo mua sắm</div>
                <article id="chapter-content">
                  <h1>Chương 10: Kiếm Ý</h1>
                  <p>Gió thổi qua rừng trúc tạo nên tiếng xào xạc nhẹ nhàng.</p>
                  <p>Lâm Phong nhắm mắt cảm nhận từng luồng linh khí xung quanh thân thể.</p>
                  <p>Sau ba canh giờ, thanh trường kiếm trong tay bỗng rung lên bần bật.</p>
                </article>
                <div class="nav-chap">
                  <a href="https://example.com/truyen/ch-11">Chương sau »</a>
                </div>
                <footer>Bản quyền 2026</footer>
              </body>
            </html>
        """.trimIndent()

        val extracted = DirectUrlExtractor.extract(html, "https://example.com/truyen/ch-10")
        assertEquals(MediaType.NOVEL, extracted.mediaType)
        assertEquals("https://example.com/truyen/ch-11", extracted.nextChapterUrl)
        assertEquals(3, extracted.paragraphs.size)
        assertTrue(extracted.paragraphs.none { it.contains("Quảng cáo") })
    }
}
