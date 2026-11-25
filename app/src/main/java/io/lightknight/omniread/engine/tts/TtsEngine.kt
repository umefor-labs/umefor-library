package io.lightknight.omniread.engine.tts

import java.io.File

interface TtsEngine {
    val id: String
    val displayName: String
    val isOffline: Boolean
    suspend fun listVoices(): List<VoiceOption>
    suspend fun synthesizeToFile(
        text: String,
        params: VoiceSynthesisParams,
        outputFile: File
    ): Result<SynthesizedAudio>
}

class TtsEngineRegistry(
    private val engines: List<TtsEngine>,
    private val fallbackEngine: TtsEngine
) {
    fun getAllEngines(): List<TtsEngine> = engines

    fun getEngine(engineId: String): TtsEngine =
        engines.firstOrNull { it.id == engineId } ?: fallbackEngine

    suspend fun allVoices(): List<VoiceOption> = engines.flatMap { it.listVoices() }

    /**
     * Synthesizes with the requested engine, automatically falling back to offline Android TTS
     * if a network TTS engine fails.
     */
    suspend fun synthesizeWithFallback(
        engineId: String,
        text: String,
        params: VoiceSynthesisParams,
        targetBaseFile: File
    ): Result<SynthesizedAudio> {
        val primary = getEngine(engineId)
        val primaryResult = primary.synthesizeToFile(text, params, targetBaseFile)
        if (primaryResult.isSuccess || primary.id == fallbackEngine.id) {
            return primaryResult
        }
        val wavFallback = File(targetBaseFile.parentFile, "${targetBaseFile.nameWithoutExtension}_fb.wav")
        return fallbackEngine.synthesizeToFile(
            text = text,
            params = params.copy(voiceId = "vi-VN-default"),
            outputFile = wavFallback
        )
    }
}
