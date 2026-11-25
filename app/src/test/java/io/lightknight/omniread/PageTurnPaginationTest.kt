package io.lightknight.omniread

import io.lightknight.omniread.ui.reader.paginateParagraphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnPaginationTest {
    @Test
    fun paginatesParagraphsWhilePreservingOriginalIndices() {
        val paras = (0 until 15).map { idx ->
            "Đoạn $idx: " + "Nội dung truyện dài để kiểm tra phân trang hợp lý. ".repeat(4)
        }
        val pages = paginateParagraphs(paras, fontSizeSp = 18f, lineHeightMultiplier = 1.55f)
        assertTrue(pages.size >= 2)
        val flattened = pages.flatten()
        assertEquals(15, flattened.size)
        flattened.forEachIndexed { expectedIdx, pair ->
            assertEquals(expectedIdx, pair.first)
            assertEquals(paras[expectedIdx], pair.second)
        }
    }
}
