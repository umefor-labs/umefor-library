package io.lightknight.omniread.engine.tts

import io.lightknight.omniread.core.AppSettingsRepository
import io.lightknight.omniread.data.local.CharacterVoiceEntity
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

class GeminiCastClient(
    private val httpClient: OkHttpClient,
    private val settingsRepo: AppSettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyzeChapter(
        paragraphs: List<String>,
        existingRoster: List<CharacterVoiceEntity>
    ): Result<List<DialogueSegment>> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = settingsRepo.getGeminiApiKey()
            if (apiKey.isBlank()) {
                throw IllegalStateException("Chưa nhập Gemini API Key trong Cài đặt.")
            }
            val model = settingsRepo.settings.first().geminiModel.ifBlank { "gemini-2.5-flash" }
            val rosterHint = existingRoster.joinToString(", ") {
                "${it.characterName} (${it.gender}/${it.ageGroup})"
            }

            // Chunk paragraphs so each request stays well within context & fast latency
            val chunks = chunkParagraphs(paragraphs, maxChars = 10_000)
            val allSegments = mutableListOf<DialogueSegment>()

            for ((startIdx, chunk) in chunks) {
                val indexedText = chunk.mapIndexed { offset, p ->
                    "[${startIdx + offset}] $p"
                }.joinToString("\n")

                val prompt = """
                    Bạn là đạo diễn lồng tiếng sách nói tiếng Việt.
                    Hãy tách văn bản chương truyện dưới đây thành danh sách các đoạn đọc theo thứ tự xuất hiện, phân biệt chính xác lời người dẫn chuyện ("${LocalDialogueSplitter.NARRATOR_NAME}") và lời thoại của từng nhân vật (kể cả khi nhân vật không được gọi tên trực tiếp ở ngay câu thoại đó, hãy suy luận từ ngữ cảnh trước/sau).
                    Danh sách nhân vật đã biết trong truyện: [${rosterHint.ifBlank { "Chưa có" }}]. Hãy giữ thống nhất tên nhân vật nếu cùng một người.
                    Trả về JSON đúng schema:
                    {"segments":[{"speaker":"Tên nhân vật hoặc ${LocalDialogueSplitter.NARRATOR_NAME}","gender":"MALE|FEMALE|NEUTRAL","ageGroup":"CHILD|YOUNG|ADULT|ELDER","emotion":"neutral|happy|sad|angry|whisper","paragraphIndex":0,"text":"nội dung câu giữ nguyên văn"}]}
                    Quy tắc: giữ nguyên văn bản gốc từng đoạn, không tóm tắt, không bỏ sót chữ, paragraphIndex phải đúng số trong ngoặc vuông [n].
                    
                    Văn bản:
                    $indexedText
                """.trimIndent()

                val rawText = callGeminiJson(model, apiKey, prompt)
                val parsed = GeminiCastParser.parseSegments(rawText)
                parsed.forEach { seg ->
                    allSegments += seg.copy(index = allSegments.size)
                }
            }

            if (allSegments.isEmpty()) {
                throw IllegalStateException("Gemini không trả về đoạn phân vai hợp lệ.")
            }
            allSegments
        }
    }

    private fun chunkParagraphs(paragraphs: List<String>, maxChars: Int): List<Pair<Int, List<String>>> {
        val result = mutableListOf<Pair<Int, List<String>>>()
        var startIdx = 0
        var accChars = 0
        val current = mutableListOf<String>()

        paragraphs.forEachIndexed { idx, p ->
            if (current.isNotEmpty() && accChars + p.length > maxChars) {
                result += startIdx to current.toList()
                current.clear()
                startIdx = idx
                accChars = 0
            }
            current += p
            accChars += p.length
        }
        if (current.isNotEmpty()) {
            result += startIdx to current.toList()
        }
        return result
    }

    private fun callGeminiJson(model: String, apiKey: String, prompt: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("temperature", 0.2)
            })
        }.toString()

        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Gemini API HTTP ${resp.code}: ${raw.take(240)}")
            }
            val root = json.parseToJsonElement(raw).jsonObject
            return root["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: throw IllegalStateException("Gemini trả về phản hồi rỗng.")
        }
    }
}
