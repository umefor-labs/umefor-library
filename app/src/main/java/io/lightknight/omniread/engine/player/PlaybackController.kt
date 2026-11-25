package io.lightknight.omniread.engine.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackUiState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val currentItemId: String = "",
    val currentBookTitle: String = "",
    val currentChapterId: String = "",
    val currentChapterIndex: Int = 0,
    val currentChapterTitle: String = "",
    val coverPath: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val sleepTimerRemainingMs: Long = 0L,
    val sleepStopAfterChapter: Boolean = false,
    val playlistSize: Int = 0
)

class PlaybackController(
    private val context: Context,
    private val repository: LibraryRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var currentChapters: List<ChapterEntity> = emptyList()
    private var currentBook: LibraryItemEntity? = null
    private var tickerJob: Job? = null
    private var sleepJob: Job? = null
    private var lastPersistedMs: Long = 0L

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    suspend fun ensureConnected(): MediaController {
        controller?.let { if (it.isConnected) return it }
        val token = SessionToken(context, ComponentName(context, OmniMediaService::class.java))
        val mc = MediaController.Builder(context, token).buildAsync().await()
        mc.addListener(playerListener)
        controller = mc
        _uiState.update { it.copy(isConnected = true) }
        startTicker()
        return mc
    }

    suspend fun playAudiobook(
        book: LibraryItemEntity,
        chapters: List<ChapterEntity>,
        startChapterIndex: Int = 0,
        startPositionMs: Long = 0L
    ) {
        val playable = chapters.filter { !it.localFilePath.isNullOrBlank() && File(it.localFilePath).exists() }
        if (playable.isEmpty()) return

        val mc = ensureConnected()
        currentBook = book
        currentChapters = playable

        val mediaItems = playable.map { ch ->
            val file = File(ch.localFilePath!!)
            val metaBuilder = MediaMetadata.Builder()
                .setTitle(ch.title)
                .setArtist(book.author.ifBlank { book.title })
                .setAlbumTitle(book.title)
            book.coverLocalPath?.let { cp ->
                val cf = File(cp)
                if (cf.exists()) metaBuilder.setArtworkUri(Uri.fromFile(cf))
            }
            MediaItem.Builder()
                .setMediaId(ch.id)
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(metaBuilder.build())
                .build()
        }

        val safeIndex = startChapterIndex.coerceIn(0, playable.lastIndex)
        mc.setMediaItems(mediaItems, safeIndex, startPositionMs.coerceAtLeast(0L))
        mc.prepare()
        mc.setPlaybackSpeed(_uiState.value.speed)
        mc.play()

        val activeCh = playable[safeIndex]
        _uiState.update {
            it.copy(
                isPlaying = true,
                currentItemId = book.id,
                currentBookTitle = book.title,
                currentChapterId = activeCh.id,
                currentChapterIndex = activeCh.chapterIndex,
                currentChapterTitle = activeCh.title,
                coverPath = book.coverLocalPath,
                positionMs = startPositionMs,
                durationMs = activeCh.durationMs,
                playlistSize = playable.size
            )
        }
        repository.markOpened(book.id)
    }

    fun togglePlayPause() {
        val mc = controller ?: return
        if (mc.isPlaying) {
            mc.pause()
            persistNow()
        } else {
            mc.play()
        }
    }

    fun seekTo(ms: Long) {
        val mc = controller ?: return
        mc.seekTo(ms.coerceAtLeast(0L))
        _uiState.update { it.copy(positionMs = mc.currentPosition) }
    }

    fun seekBy(deltaMs: Long) {
        val mc = controller ?: return
        val maxDur = mc.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (mc.currentPosition + deltaMs).coerceIn(0L, maxDur)
        mc.seekTo(target)
        _uiState.update { it.copy(positionMs = target) }
    }

    fun skipToChapter(chapterIndexInList: Int) {
        val mc = controller ?: return
        if (chapterIndexInList in currentChapters.indices) {
            mc.seekToDefaultPosition(chapterIndexInList)
            mc.play()
        }
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        controller?.setPlaybackSpeed(clamped)
        _uiState.update { it.copy(speed = clamped) }
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _uiState.update { it.copy(sleepTimerRemainingMs = 0L, sleepStopAfterChapter = false) }
            return
        }
        var remaining = minutes * 60_000L
        _uiState.update { it.copy(sleepTimerRemainingMs = remaining, sleepStopAfterChapter = false) }
        sleepJob = scope.launch {
            while (remaining > 0L && isActive) {
                delay(1000L)
                remaining -= 1000L
                _uiState.update { it.copy(sleepTimerRemainingMs = remaining.coerceAtLeast(0L)) }
            }
            if (isActive) {
                controller?.pause()
                persistNow()
            }
        }
    }

    fun setSleepAtEndOfChapter(enabled: Boolean) {
        sleepJob?.cancel()
        _uiState.update { it.copy(sleepTimerRemainingMs = 0L, sleepStopAfterChapter = enabled) }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val mc = controller
                if (mc != null && mc.isConnected) {
                    val pos = mc.currentPosition.coerceAtLeast(0L)
                    val dur = mc.duration.takeIf { it > 0 } ?: _uiState.value.durationMs
                    val idx = mc.currentMediaItemIndex
                    val ch = currentChapters.getOrNull(idx)
                    _uiState.update {
                        it.copy(
                            isPlaying = mc.isPlaying,
                            positionMs = pos,
                            durationMs = dur,
                            currentChapterId = ch?.id ?: it.currentChapterId,
                            currentChapterIndex = ch?.chapterIndex ?: it.currentChapterIndex,
                            currentChapterTitle = ch?.title ?: it.currentChapterTitle
                        )
                    }
                    if (mc.isPlaying && kotlin.math.abs(pos - lastPersistedMs) >= 5000L) {
                        lastPersistedMs = pos
                        persistNow()
                    }
                    if (mc.isPlaying && (System.currentTimeMillis() / 500L) % 60L == 0L) {
                        scope.launch(Dispatchers.IO) { repository.recordReadingTime(listenSeconds = 30L) }
                    }
                }
                delay(500L)
            }
        }
    }

    private fun persistNow() {
        val mc = controller ?: return
        val book = currentBook ?: return
        val ch = currentChapters.getOrNull(mc.currentMediaItemIndex) ?: return
        val pos = mc.currentPosition.coerceAtLeast(0L)
        val dur = mc.duration.takeIf { it > 0 } ?: ch.durationMs.takeIf { it > 0 } ?: 1L
        val total = currentChapters.size.coerceAtLeast(1)
        val percent = ((mc.currentMediaItemIndex + (pos.toFloat() / dur)).coerceIn(0f, total.toFloat())) / total
        scope.launch(Dispatchers.IO) {
            repository.saveProgress(
                itemId = book.id,
                chapterId = ch.id,
                chapterIndex = ch.chapterIndex,
                audioPositionMs = pos,
                progressPercent = percent
            )
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) persistNow()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (_uiState.value.sleepStopAfterChapter && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                _uiState.update { it.copy(sleepStopAfterChapter = false) }
                persistNow()
                return
            }
            val idx = controller?.currentMediaItemIndex ?: return
            val ch = currentChapters.getOrNull(idx) ?: return
            _uiState.update {
                it.copy(
                    currentChapterId = ch.id,
                    currentChapterIndex = ch.chapterIndex,
                    currentChapterTitle = ch.title,
                    durationMs = ch.durationMs
                )
            }
            scope.launch(Dispatchers.IO) { repository.recordReadingTime(chaptersDone = 1) }
            persistNow()
        }
    }
}
