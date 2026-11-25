package io.lightknight.omniread.engine.tts

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.lightknight.omniread.core.AppSettingsRepository
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.ChapterScriptEntity
import io.lightknight.omniread.data.local.CharacterVoiceEntity
import io.lightknight.omniread.data.model.TtsMode
import io.lightknight.omniread.data.repository.LibraryRepository
import io.lightknight.omniread.data.storage.FileStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class TtsChapterPlayer(
    private val context: Context,
    private val repository: LibraryRepository,
    private val storage: FileStorageManager,
    private val settingsRepo: AppSettingsRepository,
    private val ttsRegistry: TtsEngineRegistry,
    private val geminiCastClient: GeminiCastClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(TtsPlaybackState())
    val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var playJob: Job? = null
    private var sleepJob: Job? = null

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()
        return ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { player = it }
    }

    fun togglePlayOrStart(
        itemId: String,
        chapter: ChapterEntity,
        paragraphs: List<String>,
        mode: TtsMode,
        bookTitle: String = ""
    ) {
        val current = _state.value
        if (current.isActive && current.chapterId == chapter.id) {
            togglePlayPause()
            return
        }
        startChapter(itemId, chapter, paragraphs, mode, bookTitle)
    }

    fun togglePlayPause() {
        val exo = player ?: return
        val current = _state.value
        if (!current.isActive) return
        if (exo.isPlaying) {
            exo.pause()
            _state.update { it.copy(isPlaying = false, statusMessage = "Tạm dừng TTS") }
        } else {
            exo.play()
            _state.update { it.copy(isPlaying = true, statusMessage = "${it.activeSpeaker} (${it.activeSegmentIndex + 1}/${it.totalSegments})") }
        }
    }

    fun setSleepTimer(minutes: Int, stopAfterChapter: Boolean = false) {
        sleepJob?.cancel()
        if (minutes <= 0 && !stopAfterChapter) {
            _state.update { it.copy(sleepTimerRemainingMs = 0L, stopAfterChapter = false) }
            return
        }
        if (stopAfterChapter) {
            _state.update { it.copy(sleepTimerRemainingMs = 0L, stopAfterChapter = true) }
            return
        }
        var remaining = minutes * 60_000L
        _state.update { it.copy(sleepTimerRemainingMs = remaining, stopAfterChapter = false) }
        sleepJob = scope.launch {
            while (remaining > 0L && isActive) {
                delay(1000L)
                remaining -= 1000L
                _state.update { it.copy(sleepTimerRemainingMs = remaining.coerceAtLeast(0L)) }
            }
            if (isActive) {
                stop()
            }
        }
    }

    fun startChapter(
        itemId: String,
        chapter: ChapterEntity,
        paragraphs: List<String>,
        mode: TtsMode,
        bookTitle: String = ""
    ) {
        playJob?.cancel()
        playJob = scope.launch {
            val resolvedBookTitle = bookTitle.ifBlank {
                withContext(Dispatchers.IO) { repository.getItem(itemId)?.title.orEmpty() }
            }
            _state.value = TtsPlaybackState(
                isActive = true,
                isPreparing = true,
                itemId = itemId,
                bookTitle = resolvedBookTitle,
                chapterId = chapter.id,
                chapterIndex = chapter.chapterIndex,
                chapterTitle = chapter.title,
                stopAfterChapter = _state.value.stopAfterChapter,
                sleepTimerRemainingMs = _state.value.sleepTimerRemainingMs,
                statusMessage = if (mode == TtsMode.AI_CAST) "Đang phân vai bằng Gemini AI..." else "Đang tách lời thoại..."
            )

            val segments = resolveSegments(itemId, chapter, paragraphs, mode)
            if (segments.isEmpty()) {
                _state.value = TtsPlaybackState(statusMessage = "Chương không có nội dung để đọc.")
                return@launch
            }

            val roster = repository.getVoiceCast(itemId).associateBy { it.characterName.lowercase() }
            val rules = withContext(Dispatchers.IO) { repository.getEnabledPronunciationRules() }
            val appSettings = settingsRepo.settings.first()
            val exo = ensurePlayer()

            val prefetched = mutableMapOf<Int, kotlinx.coroutines.Deferred<Result<SynthesizedAudio>>>()
            fun prefetch(idx: Int) {
                if (idx !in segments.indices || prefetched.containsKey(idx)) return
                prefetched[idx] = scope.async(Dispatchers.IO) {
                    val seg = segments[idx]
                    val cleanText = TextSanitizer.applyRules(seg.text, rules)
                    if (cleanText.isBlank()) {
                        return@async Result.failure(IllegalStateException("Đoạn rỗng sau lọc"))
                    }
                    val voice = roster[seg.speaker.lowercase()]
                    val engineId = voice?.engineId ?: appSettings.defaultTtsEngineId
                    val voiceId = voice?.voiceId ?: appSettings.defaultTtsVoiceId
                    val rate = voice?.speechRate ?: appSettings.defaultTtsRate
                    val pitch = voice?.pitchHz ?: appSettings.defaultTtsPitchHz
                    val target = storage.ttsSegmentFile(chapter.id, idx)
                    if (target.exists() && target.length() > 0 && rules.isEmpty()) {
                        Result.success(SynthesizedAudio(target))
                    } else {
                        ttsRegistry.synthesizeWithFallback(
                            engineId = engineId,
                            text = cleanText,
                            params = VoiceSynthesisParams(voiceId = voiceId, rate = rate, pitchHz = pitch),
                            targetBaseFile = target
                        )
                    }
                }
            }

            prefetch(0)
            prefetch(1)
            prefetch(2)

            var accumulatedListenMs = 0L
            for (idx in segments.indices) {
                if (!isActive) break
                val seg = segments[idx]
                prefetch(idx + 1)
                prefetch(idx + 2)

                _state.update {
                    it.copy(
                        isPreparing = true,
                        activeSegmentIndex = idx,
                        activeParagraphIndex = seg.paragraphIndex,
                        activeSpeaker = seg.speaker,
                        totalSegments = segments.size,
                        statusMessage = "${seg.speaker} (${idx + 1}/${segments.size})"
                    )
                }

                val synthResult = prefetched.remove(idx)?.await()
                val audio = synthResult?.getOrNull() ?: continue

                val startAt = System.currentTimeMillis()
                playSingleFileAndAwait(exo, audio.file)
                accumulatedListenMs += (System.currentTimeMillis() - startAt).coerceIn(0L, 120_000L)
                if (accumulatedListenMs >= 20_000L) {
                    val secs = accumulatedListenMs / 1000L
                    accumulatedListenMs = 0L
                    launch(Dispatchers.IO) { repository.recordReadingTime(listenSeconds = secs) }
                }

                val pauseMs = (roster[seg.speaker.lowercase()]?.pauseAfterMs ?: 220).toLong()
                if (pauseMs > 0 && isActive) delay(pauseMs)
            }

            val remSecs = accumulatedListenMs / 1000L
            launch(Dispatchers.IO) {
                repository.recordReadingTime(listenSeconds = remSecs, chaptersDone = 1)
            }
            sleepJob?.cancel()
            _state.value = TtsPlaybackState()
        }
    }

    suspend fun exportChapterToOfflineAudio(
        itemId: String,
        chapter: ChapterEntity,
        paragraphs: List<String>,
        mode: TtsMode,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val segments = resolveSegments(itemId, chapter, paragraphs, mode)
            if (segments.isEmpty()) throw IllegalStateException("Chương không có nội dung để xuất.")
            val roster = repository.getVoiceCast(itemId).associateBy { it.characterName.lowercase() }
            val rules = repository.getEnabledPronunciationRules()
            val appSettings = settingsRepo.settings.first()

            val outDir = storage.itemAudioDir(itemId)
            val outFile = File(outDir, "tts_ch_%04d.mp3".format(chapter.chapterIndex))
            if (outFile.exists()) outFile.delete()

            var wroteFirstChunk = false
            FileOutputStream(outFile).use { fos ->
                segments.forEachIndexed { idx, seg ->
                    onProgress(idx + 1, segments.size)
                    val clean = TextSanitizer.applyRules(seg.text, rules)
                    if (clean.isBlank()) return@forEachIndexed
                    val voice = roster[seg.speaker.lowercase()]
                    val engineId = voice?.engineId ?: appSettings.defaultTtsEngineId
                    val voiceId = voice?.voiceId ?: appSettings.defaultTtsVoiceId
                    val rate = voice?.speechRate ?: appSettings.defaultTtsRate
                    val pitch = voice?.pitchHz ?: appSettings.defaultTtsPitchHz
                    val segFile = storage.ttsSegmentFile(chapter.id, idx)
                    val synth = if (segFile.exists() && segFile.length() > 0L) {
                        SynthesizedAudio(segFile)
                    } else {
                        ttsRegistry.synthesizeWithFallback(
                            engineId = engineId,
                            text = clean,
                            params = VoiceSynthesisParams(voiceId = voiceId, rate = rate, pitchHz = pitch),
                            targetBaseFile = segFile
                        ).getOrThrow()
                    }
                    val bytes = synth.file.readBytes()
                    val payloadOffset = if (wroteFirstChunk) skipId3v2Offset(bytes) else 0
                    if (bytes.size > payloadOffset) {
                        fos.write(bytes, payloadOffset, bytes.size - payloadOffset)
                        wroteFirstChunk = true
                    }
                }
                fos.flush()
            }
            if (!outFile.exists() || outFile.length() == 0L) {
                throw IllegalStateException("Không tổng hợp được tệp audio nào.")
            }
            outFile
        }
    }

    private fun skipId3v2Offset(bytes: ByteArray): Int {
        if (bytes.size >= 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val tagSize = ((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)
            return (10 + tagSize).coerceAtMost(bytes.size)
        }
        return 0
    }

    suspend fun previewVoice(voice: CharacterVoiceEntity, sampleText: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val tempFile = File(storage.ttsCacheDir, "preview_${voice.id}.mp3")
            val res = ttsRegistry.synthesizeWithFallback(
                engineId = voice.engineId,
                text = sampleText,
                params = VoiceSynthesisParams(voice.voiceId, voice.speechRate, voice.pitchHz),
                targetBaseFile = tempFile
            )
            res.map { synth ->
                withContext(Dispatchers.Main) {
                    val exo = ensurePlayer()
                    exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(synth.file)))
                    exo.prepare()
                    exo.play()
                }
            }
        }

    suspend fun reanalyzeChapterWithAi(
        itemId: String,
        chapterId: String,
        paragraphs: List<String>
    ): Result<List<DialogueSegment>> {
        val roster = repository.getVoiceCast(itemId)
        val result = geminiCastClient.analyzeChapter(paragraphs, roster)
        result.onSuccess { segments ->
            val newVoices = GeminiCastParser.buildNewVoiceProfiles(itemId, segments, roster)
            if (newVoices.isNotEmpty()) {
                repository.upsertVoiceCasts(newVoices)
            }
            val encoded = json.encodeToString(segments)
            val scriptFile = storage.scriptFile(chapterId).apply {
                parentFile?.mkdirs()
                writeText(encoded, Charsets.UTF_8)
            }
            repository.saveChapterScript(
                ChapterScriptEntity(
                    chapterId = chapterId,
                    itemId = itemId,
                    mode = "GEMINI_AI",
                    segmentsJson = encoded,
                    cachedAudioPath = scriptFile.absolutePath
                )
            )
        }
        return result
    }

    private suspend fun resolveSegments(
        itemId: String,
        chapter: ChapterEntity,
        paragraphs: List<String>,
        mode: TtsMode
    ): List<DialogueSegment> {
        val cached = repository.getChapterScript(chapter.id)
        if (cached != null && (mode == TtsMode.QUICK_LOCAL || cached.mode == "GEMINI_AI")) {
            val decoded = runCatching { json.decodeFromString<List<DialogueSegment>>(cached.segmentsJson) }.getOrNull()
            if (!decoded.isNullOrEmpty()) return decoded
        }

        if (mode == TtsMode.AI_CAST) {
            val aiResult = reanalyzeChapterWithAi(itemId, chapter.id, paragraphs)
            if (aiResult.isSuccess) return aiResult.getOrThrow()
            _state.update {
                it.copy(statusMessage = "AI phân vai lỗi (${aiResult.exceptionOrNull()?.message}), chuyển sang tách thoại cục bộ")
            }
        }

        val localSegments = LocalDialogueSplitter.split(paragraphs)
        val existingRoster = repository.getVoiceCast(itemId)
        val newProfiles = GeminiCastParser.buildNewVoiceProfiles(itemId, localSegments, existingRoster)
        if (newProfiles.isNotEmpty()) {
            repository.upsertVoiceCasts(newProfiles)
        }
        val encoded = json.encodeToString(localSegments)
        repository.saveChapterScript(
            ChapterScriptEntity(
                chapterId = chapter.id,
                itemId = itemId,
                mode = "LOCAL_RULE",
                segmentsJson = encoded
            )
        )
        return localSegments
    }

    private suspend fun playSingleFileAndAwait(exo: ExoPlayer, file: File) {
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.prepare()
        exo.play()
        _state.update { it.copy(isPreparing = false, isPlaying = true) }

        while (true) {
            val state = exo.playbackState
            if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) break
            delay(150L)
        }
    }

    fun stop() {
        playJob?.cancel()
        sleepJob?.cancel()
        player?.stop()
        _state.value = TtsPlaybackState()
    }
}
