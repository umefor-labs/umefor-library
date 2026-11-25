package io.lightknight.omniread.engine.tts

import java.security.MessageDigest
import java.util.Locale

object EdgeTtsSsmlBuilder {
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH_OFFSET_SECONDS = 11644473600L

    fun escapeXml(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun formatRate(rate: Float): String {
        val pct = ((rate.coerceIn(0.4f, 2.5f) - 1.0f) * 100f).toInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    fun formatPitch(pitchHz: Int): String {
        val clamped = pitchHz.coerceIn(-40, 40)
        return if (clamped >= 0) "+${clamped}Hz" else "${clamped}Hz"
    }

    fun buildSsml(text: String, params: VoiceSynthesisParams): String {
        val lang = params.voiceId.split('-').take(2).joinToString("-").ifBlank { "vi-VN" }
        val escaped = escapeXml(text.trim())
        val rateStr = formatRate(params.rate)
        val pitchStr = formatPitch(params.pitchHz)
        val volStr = "+${(params.volumePercent.coerceIn(10, 100) - 100)}%"
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
            "<voice name='${escapeXml(params.voiceId)}'>" +
            "<prosody pitch='$pitchStr' rate='$rateStr' volume='$volStr'>$escaped</prosody>" +
            "</voice></speak>"
    }

    /**
     * Generates the Sec-MS-GEC token required by Edge ReadAloud WebSocket endpoint.
     */
    fun generateSecMsGec(currentUnixSeconds: Long = System.currentTimeMillis() / 1000L): String {
        var ticks = currentUnixSeconds + WIN_EPOCH_OFFSET_SECONDS
        ticks -= ticks % 300L
        val winFileTime = ticks * 10_000_000L
        val input = "$winFileTime$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(Locale.US, it) }
    }
}
