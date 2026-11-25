package io.lightknight.omniread.engine.tts

import kotlinx.serialization.Serializable
import java.io.File

data class VoiceOption(
    val engineId: String,
    val voiceId: String,
    val displayName: String,
    val language: String,
    val gender: String // MALE | FEMALE | NEUTRAL
)

data class VoiceSynthesisParams(
    val voiceId: String,
    val rate: Float = 1.0f,
    val pitchHz: Int = 0,
    val volumePercent: Int = 100
)

data class SynthesizedAudio(
    val file: File,
    val durationMs: Long = 0L
)

@Serializable
data class DialogueSegment(
    val index: Int,
    val speaker: String,
    val gender: String = "NEUTRAL",
    val ageGroup: String = "ADULT",
    val emotion: String = "neutral",
    val text: String,
    val paragraphIndex: Int
)

data class TtsPlaybackState(
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val itemId: String = "",
    val bookTitle: String = "",
    val chapterId: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val activeSegmentIndex: Int = -1,
    val activeParagraphIndex: Int = -1,
    val activeSpeaker: String = "",
    val totalSegments: Int = 0,
    val sleepTimerRemainingMs: Long = 0L,
    val stopAfterChapter: Boolean = false,
    val statusMessage: String = ""
)
