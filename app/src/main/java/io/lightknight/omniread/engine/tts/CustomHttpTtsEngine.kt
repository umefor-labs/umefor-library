package io.lightknight.omniread.engine.tts

import android.util.Base64
import io.lightknight.omniread.core.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CustomHttpTtsEngine(
    private val httpClient: OkHttpClient,
    private val settingsRepo: AppSettingsRepository
) : TtsEngine {
    override val id: String = ENGINE_ID
    override val displayName: String = "Custom TTS / Gemini TTS API"
    override val isOffline: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listVoices(): List<VoiceOption> = listOf(
        VoiceOption(ENGINE_ID, "custom-default", "Custom Voice (Theo Cài đặt)", "vi-VN", "NEUTRAL"),
        VoiceOption(ENGINE_ID, "Kore", "Gemini Voice: Kore", "vi-VN", "FEMALE"),
        VoiceOption(ENGINE_ID, "Puck", "Gemini Voice: Puck", "vi-VN", "MALE"),
        VoiceOption(ENGINE_ID, "Aoede", "Gemini Voice: Aoede", "vi-VN", "FEMALE"),
        VoiceOption(ENGINE_ID, "Charon", "Gemini Voice: Charon", "vi-VN", "MALE")
    )

    override suspend fun synthesizeToFile(
        text: String,
        params: VoiceSynthesisParams,
        outputFile: File
    ): Result<SynthesizedAudio> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settingsRepo.settings.first()
            val apiKey = settingsRepo.getCustomTtsApiKey().ifBlank { settingsRepo.getGeminiApiKey() }
            if (s.customTtsIsGemini) {
                synthesizeGeminiTts(text, params, s.customTtsModel.ifBlank { "gemini-2.5-flash-preview-tts" }, apiKey, outputFile)
            } else {
                val baseUrl = s.customTtsBaseUrl.trimEnd('/')
                if (baseUrl.isBlank()) throw IllegalStateException("Chưa cấu hình URL Custom TTS trong Cài đặt.")
                synthesizeOpenAiCompatible(baseUrl, s.customTtsModel, params.voiceId.ifBlank { s.customTtsVoice }, params.rate, text, apiKey, outputFile)
            }
        }
    }

    private fun synthesizeOpenAiCompatible(
        baseUrl: String,
        model: String,
        voice: String,
        rate: Float,
        text: String,
        apiKey: String,
        outputFile: File
    ): SynthesizedAudio {
        val url = if (baseUrl.endsWith("/audio/speech")) baseUrl else "$baseUrl/v1/audio/speech"
        val bodyJson = buildJsonObject {
            put("model", model.ifBlank { "tts-1" })
            put("input", text)
            put("voice", voice.ifBlank { "alloy" })
            put("speed", rate.coerceIn(0.25f, 4.0f))
            put("response_format", "mp3")
        }.toString()

        val reqBuilder = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Custom TTS HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(200)}")
            }
            val bytes = resp.body?.bytes() ?: throw IllegalStateException("Custom TTS trả body rỗng")
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(bytes)
            return SynthesizedAudio(outputFile)
        }
    }

    private fun synthesizeGeminiTts(
        text: String,
        params: VoiceSynthesisParams,
        model: String,
        apiKey: String,
        outputFile: File
    ): SynthesizedAudio {
        if (apiKey.isBlank()) throw IllegalStateException("Chưa nhập Gemini API Key trong Cài đặt.")
        val voiceName = params.voiceId.takeIf { it in setOf("Kore", "Puck", "Aoede", "Charon", "Fenrir", "Zephyr") } ?: "Kore"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", text) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) })
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", voiceName)
                        })
                    })
                })
            })
        }.toString()

        val req = Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).build()
        httpClient.newCall(req).execute().use { resp ->
            val rawBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Gemini TTS HTTP ${resp.code}: ${rawBody.take(240)}")
            val root = json.parseToJsonElement(rawBody).jsonObject
            val inlineData = root["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("inlineData")?.jsonObject
                ?: throw IllegalStateException("Gemini TTS không trả inlineData audio.")
            val b64 = inlineData["data"]?.jsonPrimitive?.content.orEmpty()
            val pcm = Base64.decode(b64, Base64.DEFAULT)
            val wavFile = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.wav")
            wavFile.parentFile?.mkdirs()
            wavFile.writeBytes(wrapPcmAsWav(pcm, sampleRate = 24000, channels = 1, bitsPerSample = 16))
            return SynthesizedAudio(wavFile)
        }
    }

    private fun wrapPcmAsWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }

    companion object {
        const val ENGINE_ID = "CUSTOM_HTTP"
    }
}
