package io.lightknight.omniread

import io.lightknight.omniread.engine.youtube.YoutubePlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubePlaylistJsonParserTest {
    @Test
    fun parsesFlatPlaylistAndFiltersPrivateEntries() {
        val raw = """
            {
              "_type": "playlist",
              "title": "Đắc Nhân Tâm - Full Audio",
              "uploader": "Kênh Sách Nói",
              "entries": [
                {"id": "vid1", "title": "Phần 1: Nghệ thuật ứng xử", "duration": 1250, "url": "https://www.youtube.com/watch?v=vid1"},
                {"id": "vid2", "title": "[Private video]", "duration": 0},
                {"id": "vid3", "title": "Phần 2: Tạo thiện cảm", "duration": 980}
              ]
            }
        """.trimIndent()

        val preview = YoutubePlaylistParser.parseDumpJson(raw, "https://youtube.com/playlist?list=PL123")
        assertTrue(preview.isPlaylist)
        assertEquals("Đắc Nhân Tâm - Full Audio", preview.title)
        assertEquals(2, preview.entries.size)
        assertEquals("https://www.youtube.com/watch?v=vid3", preview.entries[1].url)
        assertEquals(980L, preview.entries[1].durationSec)
    }

    @Test
    fun parsesSingleVideoWithEmbeddedChapters() {
        val raw = """
            {
              "id": "abc999",
              "title": "Tam Quốc Diễn Nghĩa Tập 1",
              "channel": "Audio VN",
              "duration": 3600,
              "webpage_url": "https://www.youtube.com/watch?v=abc999",
              "chapters": [
                {"title": "Hồi 1: Kết nghĩa vườn đào", "start_time": 0.0, "end_time": 1800.0},
                {"title": "Hồi 2: Đánh khăn vàng", "start_time": 1800.0, "end_time": 3600.0}
              ]
            }
        """.trimIndent()

        val preview = YoutubePlaylistParser.parseDumpJson(raw, "https://www.youtube.com/watch?v=abc999")
        assertFalse(preview.isPlaylist)
        assertEquals(1, preview.entries.size)
        assertEquals(2, preview.embeddedChapters.size)
        assertEquals("Hồi 1: Kết nghĩa vườn đào", preview.embeddedChapters[0].title)
    }
}
