package io.lightknight.omniread.engine.ai

import io.lightknight.omniread.core.AppSettingsRepository
import io.lightknight.omniread.data.local.CharacterVoiceEntity
import io.lightknight.omniread.data.local.HighlightEntity
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

class BookAiAssistant(
    private val httpClient: OkHttpClient,
    private val settingsRepo: AppSettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generateSpoilerFreeRecap(
        bookTitle: String,
        recentChaptersUpToCurrent: List<Pair<String, String>>,
        characters: List<CharacterVoiceEntity>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = settingsRepo.getGeminiApiKey()
            if (apiKey.isBlank()) {
                throw IllegalStateException("Chưa nhập Gemini API Key trong Cài đặt.")
            }
            if (recentChaptersUpToCurrent.isEmpty()) {
                throw IllegalStateException("Chưa có nội dung chương nào đã tải để tóm tắt.")
            }
            val model = settingsRepo.settings.first().geminiModel.ifBlank { "gemini-2.5-flash" }
            val charNames = characters.filter { !it.isNarrator }.joinToString(", ") { it.characterName }
            val excerpt = recentChaptersUpToCurrent.takeLast(4).joinToString("\n\n") { (title, body) ->
                "=== $title ===\n${body.take(3500)}"
            }

            val prompt = """
                Bạn là trợ lý đọc sách của Umefor Library.
                Người đọc vừa quay lại bộ truyện "$bookTitle" và cần tóm tắt nhanh diễn biến CHỈ DỰA TRÊN các chương đã đọc dưới đây.
                TUYỆT ĐỐI KHÔNG tiết lộ (spoil) bất kỳ tình tiết nào vượt ngoài đoạn văn bản được cung cấp.
                Nhân vật đã biết: ${charNames.ifBlank { "Chưa rõ" }}.

                Hãy trình bày ngắn gọn bằng tiếng Việt theo 3 mục:
                1. Diễn biến gần đây (3-4 gạch đầu dòng)
                2. Nhân vật & mối quan hệ đang nổi bật
                3. Nút thắt ngay tại điểm dừng

                Nội dung các chương đến hiện tại:
                $excerpt
            """.trimIndent()

            callGeminiText(model, apiKey, prompt)
        }
    }

    suspend fun translateAndExplain(passage: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = settingsRepo.getGeminiApiKey()
            if (apiKey.isBlank()) {
                throw IllegalStateException("Chưa nhập Gemini API Key trong Cài đặt.")
            }
            val model = settingsRepo.settings.first().geminiModel.ifBlank { "gemini-2.5-flash" }
            val prompt = """
                Hãy dịch sát nghĩa và tự nhiên đoạn văn sau sang tiếng Việt, sau đó giải thích ngắn gọn (2-3 dòng) các thuật ngữ, điển tích hoặc sắc thái ngữ cảnh quan trọng (nếu có):

                "${passage.take(2500)}"
            """.trimIndent()
            callGeminiText(model, apiKey, prompt)
        }
    }

    private fun callGeminiText(model: String, apiKey: String, prompt: String): String {
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
                put("temperature", 0.35)
            })
        }.toString()

        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Gemini HTTP ${resp.code}: ${raw.take(180)}")
            }
            val root = json.parseToJsonElement(raw).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: throw IllegalStateException("Gemini không phản hồi.")
            val first = candidates.firstOrNull()?.jsonObject ?: throw IllegalStateException("Thiếu candidate.")
            val parts = first["content"]?.jsonObject?.get("parts")?.jsonArray
            val text = parts?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
            if (text.isBlank()) throw IllegalStateException("Phản hồi AI trống.")
            return text.trim()
        }
    }

    companion object {
        fun buildNotesMarkdown(
            bookTitle: String,
            author: String,
            highlights: List<HighlightEntity>
        ): String = buildString {
            appendLine("# Ghi chú & Highlights: $bookTitle")
            if (author.isNotBlank()) {
                appendLine("- **Tác giả:** $author")
            }
            appendLine("- **Tổng số ghi chú:** ${highlights.size}")
            appendLine()
            if (highlights.isEmpty()) {
                appendLine("_Chưa có highlight hoặc ghi chú nào._")
            } else {
                highlights.forEachIndexed { idx, h ->
                    appendLine("## ${idx + 1}. ${h.chapterTitle} (Đoạn ${h.paragraphIndex + 1})")
                    appendLine("> ${h.quoteText.replace("\n", "\n> ")}")
                    if (h.note.isNotBlank()) {
                        appendLine()
                        appendLine("**Ghi chú:** ${h.note}")
                    }
                    appendLine()
                }
            }
        }
    }
}
