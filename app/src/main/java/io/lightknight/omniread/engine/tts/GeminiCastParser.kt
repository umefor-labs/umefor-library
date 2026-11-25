package io.lightknight.omniread.engine.tts

import io.lightknight.omniread.data.local.CharacterVoiceEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class GeminiScriptResponse(
    val segments: List<GeminiSegmentDto> = emptyList()
)

@Serializable
data class GeminiSegmentDto(
    val speaker: String = LocalDialogueSplitter.NARRATOR_NAME,
    val gender: String = "NEUTRAL",
    val ageGroup: String = "ADULT",
    val emotion: String = "neutral",
    val paragraphIndex: Int = 0,
    val text: String = ""
)

object GeminiCastParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseSegments(rawResponseText: String): List<DialogueSegment> {
        val cleaned = stripMarkdownFences(rawResponseText)
        val parsed = json.decodeFromString(GeminiScriptResponse.serializer(), cleaned)
        return parsed.segments
            .filter { it.text.isNotBlank() }
            .mapIndexed { idx, dto ->
                DialogueSegment(
                    index = idx,
                    speaker = dto.speaker.ifBlank { LocalDialogueSplitter.NARRATOR_NAME },
                    gender = dto.gender.uppercase().takeIf { it in setOf("MALE", "FEMALE", "NEUTRAL") } ?: "NEUTRAL",
                    ageGroup = dto.ageGroup.uppercase().takeIf { it in setOf("CHILD", "YOUNG", "ADULT", "ELDER") } ?: "ADULT",
                    emotion = dto.emotion.ifBlank { "neutral" },
                    text = dto.text.trim(),
                    paragraphIndex = dto.paragraphIndex.coerceAtLeast(0)
                )
            }
    }

    fun stripMarkdownFences(raw: String): String {
        val trimmed = raw.trim()
        val fenceRegex = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$", RegexOption.MULTILINE)
        val match = fenceRegex.find(trimmed)
        return match?.groupValues?.getOrNull(1)?.trim() ?: trimmed
    }

    /**
     * Proposes distinct Edge-TTS voice/pitch/rate profiles for newly discovered characters.
     */
    fun buildNewVoiceProfiles(
        itemId: String,
        segments: List<DialogueSegment>,
        existingRoster: List<CharacterVoiceEntity>
    ): List<CharacterVoiceEntity> {
        val knownNames = existingRoster.map { it.characterName.lowercase() }.toMutableSet()
        val newVoices = mutableListOf<CharacterVoiceEntity>()
        var maleVariantIdx = 0
        var femaleVariantIdx = 0

        for (seg in segments) {
            val key = seg.speaker.lowercase()
            if (key in knownNames) continue
            knownNames += key

            val isNarrator = seg.speaker.equals(LocalDialogueSplitter.NARRATOR_NAME, ignoreCase = true)
            val isMale = seg.gender == "MALE"
            val voiceId = if (isMale) "vi-VN-NamMinhNeural" else "vi-VN-HoaiMyNeural"
            val pitchDelta = when {
                isNarrator -> 0
                seg.ageGroup == "CHILD" -> if (isMale) 14 else 16
                seg.ageGroup == "ELDER" -> if (isMale) -14 else -10
                isMale -> listOf(0, -6, 6, -10)[maleVariantIdx++ % 4]
                else -> listOf(4, -4, 10, 0)[femaleVariantIdx++ % 4]
            }
            val rate = when {
                seg.ageGroup == "ELDER" -> 0.93f
                seg.ageGroup == "CHILD" -> 1.06f
                else -> 1.0f
            }

            newVoices += CharacterVoiceEntity(
                id = UUID.randomUUID().toString(),
                itemId = itemId,
                characterName = seg.speaker,
                gender = seg.gender,
                ageGroup = seg.ageGroup,
                engineId = EdgeTtsEngine.ENGINE_ID,
                voiceId = voiceId,
                speechRate = rate,
                pitchHz = pitchDelta,
                pauseAfterMs = if (isNarrator) 280 else 220,
                isNarrator = isNarrator
            )
        }
        return newVoices
    }
}
