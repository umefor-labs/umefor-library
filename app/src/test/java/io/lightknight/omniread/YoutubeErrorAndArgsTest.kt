package io.lightknight.omniread

import io.lightknight.omniread.engine.youtube.YoutubeDlCommandBuilder
import io.lightknight.omniread.engine.youtube.YoutubeDlInitializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YoutubeErrorAndArgsTest {
    @Test
    fun enforcesStrictAudioOnlyWithoutVideoFallback() {
        val outTemplate = YoutubeDlCommandBuilder.buildOutputTemplate(File("/tmp"), 2, splitChapters = false)
        assertTrue(outTemplate.endsWith("track_0002.%(ext)s"))
        val splitTemplate = YoutubeDlCommandBuilder.buildOutputTemplate(File("/tmp"), 0, splitChapters = true)
        assertTrue(splitTemplate.contains("%(section_number)03d"))

        // M4A direct audio-only download (no -x video extraction needed, never uses android_vr or video fallback)
        val m4aCmd = YoutubeDlCommandBuilder.buildAudioRequest(
            trackUrl = "https://www.youtube.com/watch?v=k8r82bcZjvM",
            outputTemplate = "/tmp/track.%(ext)s",
            audioFormat = "m4a",
            audioQuality = "128K",
            splitChapters = false,
            fallbackClient = false
        ).buildCommand()
        assertTrue(m4aCmd.contains("--no-update"))
        assertTrue(m4aCmd.contains("--force-ipv4"))
        assertTrue(m4aCmd.any { it.contains("bestaudio") })
        assertTrue(m4aCmd.any { it.contains("player_client=ios,android") })
        assertFalse(m4aCmd.any { it.contains("android_vr") })
        assertFalse(m4aCmd.contains("-x"))
        assertFalse(m4aCmd.any { it == "ba/b" || it.endsWith("/b") || it.endsWith("/best") })

        // Fallback mode lets yt-dlp choose its own default client, still strict audio-only
        val mp3Cmd = YoutubeDlCommandBuilder.buildAudioRequest(
            trackUrl = "https://www.youtube.com/watch?v=k8r82bcZjvM",
            outputTemplate = "/tmp/track.%(ext)s",
            audioFormat = "mp3",
            audioQuality = "128K",
            splitChapters = false,
            fallbackClient = true
        ).buildCommand()
        assertTrue(mp3Cmd.contains("-x"))
        assertTrue(mp3Cmd.any { it == "bestaudio/ba" })
        assertFalse(mp3Cmd.any { it.contains("--extractor-args") })
        assertFalse(mp3Cmd.any { it == "ba/b" || it.endsWith("/b") || it.endsWith("/best") })

        val rawStderr = """
            WARNING: Your yt-dlp version (2025.11.12) is older than 90 days!
            Run "yt-dlp --update" or "yt-dlp -U" to update.
            WARNING: [youtube] k8r82bcZjvM: YouTube is forcing SABR streaming for this client.
            ERROR: unable to download video data: HTTP Error 403: Forbidden
        """.trimIndent()

        val concise = YoutubeDlCommandBuilder.extractConciseError(rawStderr)
        assertFalse(concise.contains("90 days"))
        assertTrue(concise.contains("HTTP Error 403"))
        assertTrue(YoutubeDlCommandBuilder.isRetryableFormatOrAuthError("ERROR: [youtube] k8r82bcZjvM: Requested format is not available. Use --list-formats for a list of available formats"))
        assertTrue(YoutubeDlInitializer.isOutdatedVersion("2025.11.12"))
        assertFalse(YoutubeDlInitializer.isOutdatedVersion("2026.11.20"))
    }
}
