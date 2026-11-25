package io.lightknight.omniread

import io.lightknight.omniread.engine.tts.LocalDialogueSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDialogueSplitterTest {
    @Test
    fun splitsNarrationQuotesAndDashDialogueWithSpeakerHints() {
        val paragraphs = listOf(
            """Lâm Phong cười nói: "Hôm nay trời đẹp thật!" rồi bước đi.""",
            "— Đúng vậy, chúng ta lên đường thôi."
        )

        val segments = LocalDialogueSplitter.split(paragraphs)
        assertEquals(4, segments.size)
        assertEquals(LocalDialogueSplitter.NARRATOR_NAME, segments[0].speaker)
        assertEquals("Lâm Phong cười nói:", segments[0].text)

        assertEquals("Lâm Phong", segments[1].speaker)
        assertEquals("Hôm nay trời đẹp thật!", segments[1].text)
        assertEquals(0, segments[1].paragraphIndex)

        assertEquals(LocalDialogueSplitter.NARRATOR_NAME, segments[2].speaker)
        assertTrue(segments[2].text.contains("rồi bước đi"))

        assertEquals("Lâm Phong", segments[3].speaker)
        assertEquals("Đúng vậy, chúng ta lên đường thôi.", segments[3].text)
        assertEquals(1, segments[3].paragraphIndex)
    }
}
