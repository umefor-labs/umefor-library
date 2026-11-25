package io.lightknight.omniread.engine.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class AndroidSystemTtsEngine(private val context: Context) : TtsEngine {
    override val id: String = ENGINE_ID
    override val displayName: String = "Android TTS (Offline)"
    override val isOffline: Boolean = true

    private val initMutex = Mutex()
    @Volatile
    private var tts: TextToSpeech? = null

    private suspend fun getTts(): TextToSpeech = initMutex.withLock {
        tts?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val instance = TextToSpeech(context.applicationContext) { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }
        val ok = ready.await()
        if (ok) {
            instance.language = Locale.forLanguageTag("vi-VN")
            tts = instance
            instance
        } else {
            instance.shutdown()
            throw IllegalStateException("Không thể khởi tạo Android TextToSpeech.")
        }
    }

    override suspend fun listVoices(): List<VoiceOption> = withContext(Dispatchers.IO) {
        runCatching {
            val engine = getTts()
            val systemVoices = engine.voices.orEmpty()
                .filter { it.locale.language in setOf("vi", "en", "ja") }
                .take(15)
                .map { v ->
                    VoiceOption(
                        engineId = ENGINE_ID,
                        voiceId = v.name,
                        displayName = "${v.name} (${v.locale.toLanguageTag()})",
                        language = v.locale.toLanguageTag(),
                        gender = "NEUTRAL"
                    )
                }
            systemVoices.ifEmpty {
                listOf(
                    VoiceOption(ENGINE_ID, "vi-VN-default", "Hệ thống mặc định (Offline)", "vi-VN", "NEUTRAL")
                )
            }
        }.getOrElse {
            listOf(VoiceOption(ENGINE_ID, "vi-VN-default", "Hệ thống mặc định (Offline)", "vi-VN", "NEUTRAL"))
        }
    }

    override suspend fun synthesizeToFile(
        text: String,
        params: VoiceSynthesisParams,
        outputFile: File
    ): Result<SynthesizedAudio> = withContext(Dispatchers.IO) {
        runCatching {
            val engine = getTts()
            val wavFile = if (outputFile.extension.equals("wav", ignoreCase = true)) {
                outputFile
            } else {
                File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.wav")
            }
            wavFile.parentFile?.mkdirs()

            engine.voices?.firstOrNull { it.name == params.voiceId }?.let { engine.voice = it }
            engine.setSpeechRate(params.rate.coerceIn(0.4f, 2.5f))
            val pitchFactor = (1.0f + (params.pitchHz / 40f)).coerceIn(0.5f, 1.8f)
            engine.setPitch(pitchFactor)

            val utteranceId = UUID.randomUUID().toString()
            val success = suspendCancellableCoroutine { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(uid: String?) = Unit
                    override fun onDone(uid: String?) {
                        if (uid == utteranceId && cont.isActive) cont.resume(true)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(uid: String?) {
                        if (uid == utteranceId && cont.isActive) cont.resume(false)
                    }
                    override fun onError(uid: String?, errorCode: Int) {
                        if (uid == utteranceId && cont.isActive) cont.resume(false)
                    }
                })
                val res = engine.synthesizeToFile(text, Bundle(), wavFile, utteranceId)
                if (res != TextToSpeech.SUCCESS && cont.isActive) {
                    cont.resume(false)
                }
            }
            if (!success || !wavFile.exists() || wavFile.length() == 0L) {
                throw IllegalStateException("Android TTS synthesizeToFile thất bại.")
            }
            SynthesizedAudio(wavFile)
        }
    }

    companion object {
        const val ENGINE_ID = "ANDROID_TTS"
    }
}
