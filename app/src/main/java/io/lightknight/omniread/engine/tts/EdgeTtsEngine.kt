package io.lightknight.omniread.engine.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume

class EdgeTtsEngine(
    private val httpClient: OkHttpClient
) : TtsEngine {
    override val id: String = ENGINE_ID
    override val displayName: String = "Edge Neural TTS (Online)"
    override val isOffline: Boolean = false

    override suspend fun listVoices(): List<VoiceOption> = presetVoices

    override suspend fun synthesizeToFile(
        text: String,
        params: VoiceSynthesisParams,
        outputFile: File
    ): Result<SynthesizedAudio> = withContext(Dispatchers.IO) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Văn bản trống"))
        }

        val mp3Bytes = runEdgeWebSocket(cleaned, params)
        if (mp3Bytes.isSuccess) {
            val bytes = mp3Bytes.getOrThrow()
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(bytes)
            Result.success(SynthesizedAudio(outputFile))
        } else {
            Result.failure(mp3Bytes.exceptionOrNull() ?: IllegalStateException("Edge TTS lỗi"))
        }
    }

    private suspend fun runEdgeWebSocket(
        text: String,
        params: VoiceSynthesisParams
    ): Result<ByteArray> = suspendCancellableCoroutine { cont ->
        val connId = UUID.randomUUID().toString().replace("-", "")
        val secMsGec = EdgeTtsSsmlBuilder.generateSecMsGec()
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4" +
            "&Sec-MS-GEC=$secMsGec&Sec-MS-GEC-Version=1-130.0.2849.68&ConnectionId=$connId"

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
            )
            .build()

        val audioBuffer = ByteArrayOutputStream()
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val timestamp = edgeTimestamp()

        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val configMsg = "X-Timestamp:$timestamp\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                webSocket.send(configMsg)

                val ssml = EdgeTtsSsmlBuilder.buildSsml(text, params)
                val ssmlMsg = "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${timestamp}Z\r\n" +
                    "Path:ssml\r\n\r\n$ssml"
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, "done")
                    if (cont.isActive) {
                        val bytes = audioBuffer.toByteArray()
                        if (bytes.isNotEmpty()) {
                            cont.resume(Result.success(bytes))
                        } else {
                            cont.resume(Result.failure(IllegalStateException("Edge TTS không trả dữ liệu âm thanh")))
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                if (raw.size > 2) {
                    val headerLen = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
                    val audioStart = 2 + headerLen
                    if (audioStart < raw.size) {
                        audioBuffer.write(raw, audioStart, raw.size - audioStart)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (cont.isActive) {
                    cont.resume(Result.failure(t))
                }
            }
        })

        cont.invokeOnCancellation { ws.cancel() }
    }

    private fun edgeTimestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    companion object {
        const val ENGINE_ID = "EDGE_TTS"

        val presetVoices = listOf(
            VoiceOption(ENGINE_ID, "vi-VN-HoaiMyNeural", "Hoài My (Nữ · Tiếng Việt)", "vi-VN", "FEMALE"),
            VoiceOption(ENGINE_ID, "vi-VN-NamMinhNeural", "Nam Minh (Nam · Tiếng Việt)", "vi-VN", "MALE"),
            VoiceOption(ENGINE_ID, "en-US-JennyNeural", "Jenny (Female · English)", "en-US", "FEMALE"),
            VoiceOption(ENGINE_ID, "en-US-GuyNeural", "Guy (Male · English)", "en-US", "MALE"),
            VoiceOption(ENGINE_ID, "ja-JP-NanamiNeural", "Nanami (Female · Japanese)", "ja-JP", "FEMALE"),
            VoiceOption(ENGINE_ID, "ja-JP-KeitaNeural", "Keita (Male · Japanese)", "ja-JP", "MALE")
        )
    }
}
