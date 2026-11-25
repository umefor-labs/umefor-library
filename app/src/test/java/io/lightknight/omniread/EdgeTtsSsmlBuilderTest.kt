package io.lightknight.omniread

import io.lightknight.omniread.engine.tts.EdgeTtsSsmlBuilder
import io.lightknight.omniread.engine.tts.VoiceSynthesisParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTtsSsmlBuilderTest {
    @Test
    fun escapesXmlSpecialCharactersAndBuildsValidSsml() {
        val ssml = EdgeTtsSsmlBuilder.buildSsml(
            text = """Anh ấy nói: "A & B < C > D" và 'E'.""",
            params = VoiceSynthesisParams(
                voiceId = "vi-VN-HoaiMyNeural",
                rate = 1.25f,
                pitchHz = -8,
                volumePercent = 90
            )
        )
        assertTrue(ssml.contains("&amp;"))
        assertTrue(ssml.contains("&lt;"))
        assertTrue(ssml.contains("&gt;"))
        assertTrue(ssml.contains("&quot;"))
        assertTrue(ssml.contains("&apos;"))
        assertTrue(ssml.contains("rate='+25%'"))
        assertTrue(ssml.contains("pitch='-8Hz'"))
        assertTrue(ssml.contains("xml:lang='vi-VN'"))
    }

    @Test
    fun generatesConsistent64HexSecMsGecToken() {
        val token = EdgeTtsSsmlBuilder.generateSecMsGec(1732464000L)
        assertEquals(64, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'A'..'F' })
    }
}
