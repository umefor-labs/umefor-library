package io.lightknight.omniread

import io.lightknight.omniread.engine.tts.GeminiCastParser
import io.lightknight.omniread.engine.tts.LocalDialogueSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCastParserTest {
    @Test
    fun parsesMarkdownFencedJsonAndAssignsNewVoices() {
        val raw = """
            ```json
            {
              "segments": [
                {"speaker": "Người dẫn chuyện", "gender": "NEUTRAL", "ageGroup": "ADULT", "emotion": "neutral", "paragraphIndex": 0, "text": "Ngoài sân tuyết rơi dày."},
                {"speaker": "Tiểu Dao", "gender": "FEMALE", "ageGroup": "YOUNG", "emotion": "happy", "paragraphIndex": 1, "text": "Huynh mau vào sưởi ấm đi!"},
                {"speaker": "Trưởng Lão", "gender": "MALE", "ageGroup": "ELDER", "emotion": "neutral", "paragraphIndex": 2, "text": "Bão tuyết sắp tới rồi."}
              ]
            }
            ```
        """.trimIndent()

        val segments = GeminiCastParser.parseSegments(raw)
        assertEquals(3, segments.size)
        assertEquals(LocalDialogueSplitter.NARRATOR_NAME, segments[0].speaker)
        assertEquals("Tiểu Dao", segments[1].speaker)
        assertEquals("FEMALE", segments[1].gender)

        val profiles = GeminiCastParser.buildNewVoiceProfiles("book-1", segments, emptyList())
        assertEquals(3, profiles.size)
        val elder = profiles.first { it.characterName == "Trưởng Lão" }
        assertEquals("vi-VN-NamMinhNeural", elder.voiceId)
        assertTrue(elder.pitchHz < 0)
        assertTrue(elder.speechRate < 1.0f)
    }
}
