package io.lightknight.omniread

import io.lightknight.omniread.engine.youtube.InnerTubeAudioExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubeAudioExtractorTest {
    @Test
    fun extractsVideoIdFromAllYoutubeUrlVariants() {
        assertEquals("k8r82bcZjvM", InnerTubeAudioExtractor.extractVideoId("https://www.youtube.com/watch?v=k8r82bcZjvM"))
        assertEquals("k8r82bcZjvM", InnerTubeAudioExtractor.extractVideoId("https://m.youtube.com/watch?v=k8r82bcZjvM&list=PL12345&index=1"))
        assertEquals("k8r82bcZjvM", InnerTubeAudioExtractor.extractVideoId("https://music.youtube.com/watch?v=k8r82bcZjvM"))
        assertEquals("k8r82bcZjvM", InnerTubeAudioExtractor.extractVideoId("https://youtu.be/k8r82bcZjvM?t=42"))
        assertEquals("k8r82bcZjvM", InnerTubeAudioExtractor.extractVideoId("https://www.youtube.com/shorts/k8r82bcZjvM"))
        assertEquals("k8r82bcZjvM", InnerTubeAudioExtractor.extractVideoId("https://www.youtube.com/live/k8r82bcZjvM?feature=share"))
        assertNull(InnerTubeAudioExtractor.extractVideoId("https://example.com/watch?v=invalid"))
    }

    @Test
    fun filtersStrictlyAudioStreamsAndSelectsExpectedItag() {
        val sampleJson = """
            {
              "playabilityStatus": {"status": "OK"},
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "itag": 137,
                    "url": "https://googlevideo.example/video1080",
                    "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                    "bitrate": 2400000,
                    "contentLength": "250000000"
                  },
                  {
                    "itag": 139,
                    "url": "https://googlevideo.example/audio139",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.5\"",
                    "bitrate": 53296,
                    "contentLength": "22956405"
                  },
                  {
                    "itag": 140,
                    "url": "https://googlevideo.example/audio140",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 133819,
                    "contentLength": "60925949"
                  },
                  {
                    "itag": 249,
                    "url": "https://googlevideo.example/audio249",
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "bitrate": 60425,
                    "contentLength": "23158751"
                  },
                  {
                    "itag": 251,
                    "url": "https://googlevideo.example/audio251",
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "bitrate": 142715,
                    "contentLength": "56717854"
                  },
                  {
                    "itag": 140,
                    "url": "https://googlevideo.example/dub140",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 135000,
                    "contentLength": "60925949",
                    "audioTrack": {"audioIsDefault": false}
                  }
                ]
              }
            }
        """.trimIndent()

        val streams = InnerTubeAudioExtractor.parseAdaptiveAudioStreams(sampleJson, "TestUA")
        assertEquals(5, streams.size)
        assertTrue(streams.none { it.mimeType.startsWith("video/") })

        val m4a128 = InnerTubeAudioExtractor.selectBestAudioStream(streams, "m4a", "128K")
        assertEquals(140, m4a128?.itag)
        assertEquals("m4a", m4a128?.extension)
        assertTrue(m4a128?.isDefaultTrack == true)

        val m4a64 = InnerTubeAudioExtractor.selectBestAudioStream(streams, "m4a", "64K")
        assertEquals(139, m4a64?.itag)

        val opus128 = InnerTubeAudioExtractor.selectBestAudioStream(streams, "opus", "128K")
        assertEquals(251, opus128?.itag)
        assertEquals("webm", opus128?.extension)

        val opus64 = InnerTubeAudioExtractor.selectBestAudioStream(streams, "opus", "64K")
        assertEquals(249, opus64?.itag)
    }
}
