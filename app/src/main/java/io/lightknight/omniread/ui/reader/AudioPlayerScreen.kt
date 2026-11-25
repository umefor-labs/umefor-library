package io.lightknight.omniread.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.ui.detail.formatDuration
import io.lightknight.omniread.ui.library.BookCoverBox
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    itemId: String,
    initialChapterIndex: Int,
    container: OmniAppContainer,
    onBack: () -> Unit
) {
    val item by container.libraryRepository.observeItem(itemId).collectAsState(initial = null)
    val chapters by container.libraryRepository.observeChapters(itemId).collectAsState(initial = emptyList())
    val playback by container.playbackController.uiState.collectAsState()

    var showChapters by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(item?.id, chapters.size) {
        val book = item ?: return@LaunchedEffect
        if (chapters.isEmpty()) return@LaunchedEffect
        if (playback.currentItemId != book.id) {
            val saved = container.libraryRepository.getProgress(book.id)
            val startIdx = if (initialChapterIndex >= 0) initialChapterIndex else (saved?.currentChapterIndex ?: 0)
            val startPos = if (saved != null && saved.currentChapterIndex == startIdx) saved.audioPositionMs else 0L
            container.playbackController.playAudiobook(book, chapters, startIdx, startPos)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(item?.title ?: "Đang phát", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
                },
                actions = {
                    IconButton(onClick = {
                        val chId = playback.currentChapterId
                        if (chId.isNotBlank()) {
                            scope.launch {
                                container.libraryRepository.addBookmark(
                                    itemId = itemId,
                                    chapterId = chId,
                                    positionLabel = "${playback.currentChapterTitle} · ${formatDuration(playback.positionMs)}",
                                    positionPayloadJson = """{"chapterIndex":${playback.currentChapterIndex},"posMs":${playback.positionMs}}"""
                                )
                                snackbarHostState.showSnackbar("Đã lưu mốc thời gian")
                            }
                        }
                    }) {
                        Icon(Icons.Default.BookmarkAdd, "Đánh dấu mốc")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            item?.let { book ->
                BookCoverBox(book, modifier = Modifier.size(220.dp))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    playback.currentChapterTitle.ifBlank { "Chưa sẵn sàng" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item?.author?.ifBlank { item?.title }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                val dur = playback.durationMs.coerceAtLeast(1L)
                val pos = playback.positionMs.coerceIn(0L, dur)
                Slider(
                    value = pos.toFloat(),
                    onValueChange = { container.playbackController.seekTo(it.toLong()) },
                    valueRange = 0f..dur.toFloat()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatDuration(pos), style = MaterialTheme.typography.labelMedium)
                    Text(formatDuration(dur), style = MaterialTheme.typography.labelMedium)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val prev = (playback.currentChapterIndex - 1).coerceAtLeast(0)
                    container.playbackController.skipToChapter(prev)
                }) { Icon(Icons.Default.SkipPrevious, "Tập trước", Modifier.size(32.dp)) }

                IconButton(onClick = { container.playbackController.seekBy(-15_000L) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Replay10, "Tua lùi 15s", Modifier.size(28.dp))
                        Text("-15s", style = MaterialTheme.typography.labelSmall)
                    }
                }

                FilledIconButton(
                    onClick = { container.playbackController.togglePlayPause() },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Phát / Tạm dừng",
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(onClick = { container.playbackController.seekBy(15_000L) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Forward30, "Tua tới 15s", Modifier.size(28.dp))
                        Text("+15s", style = MaterialTheme.typography.labelSmall)
                    }
                }

                IconButton(onClick = {
                    val next = (playback.currentChapterIndex + 1).coerceAtMost((chapters.size - 1).coerceAtLeast(0))
                    container.playbackController.skipToChapter(next)
                }) { Icon(Icons.Default.SkipNext, "Tập sau", Modifier.size(32.dp)) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilterChip(
                    selected = playback.speed != 1.0f,
                    onClick = { showSpeed = true },
                    label = { Text("${"%.2f".format(playback.speed)}x") },
                    leadingIcon = { Icon(Icons.Default.Speed, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = playback.sleepTimerRemainingMs > 0L || playback.sleepStopAfterChapter,
                    onClick = { showTimer = true },
                    label = {
                        Text(
                            when {
                                playback.sleepStopAfterChapter -> "Hết tập"
                                playback.sleepTimerRemainingMs > 0L -> formatDuration(playback.sleepTimerRemainingMs)
                                else -> "Hẹn giờ"
                            }
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Timer, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = false,
                    onClick = { showChapters = true },
                    label = { Text("Tập (${chapters.size})") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(16.dp)) }
                )
            }
        }
    }

    if (showSpeed) {
        ModalBottomSheet(onDismissRequest = { showSpeed = false }) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Tốc độ phát: ${"%.2f".format(playback.speed)}x", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = playback.speed,
                    onValueChange = { container.playbackController.setSpeed(it) },
                    valueRange = 0.5f..3.0f
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.8f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                        FilterChip(
                            selected = playback.speed == spd,
                            onClick = { container.playbackController.setSpeed(spd) },
                            label = { Text("${spd}x") }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showTimer) {
        ModalBottomSheet(onDismissRequest = { showTimer = false }) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Hẹn giờ tắt", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Tắt", 15 to "15 phút", 30 to "30 phút", 60 to "60 phút").forEach { (m, label) ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                container.playbackController.setSleepTimer(m)
                                showTimer = false
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                FilterChip(
                    selected = playback.sleepStopAfterChapter,
                    onClick = {
                        container.playbackController.setSleepAtEndOfChapter(!playback.sleepStopAfterChapter)
                        showTimer = false
                    },
                    label = { Text("Dừng sau khi hết tập này") }
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            LazyColumn(modifier = Modifier.height(420.dp)) {
                itemsIndexed(chapters) { idx, ch ->
                    ListItem(
                        headlineContent = {
                            Text(
                                ch.title,
                                fontWeight = if (idx == playback.currentChapterIndex) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        trailingContent = {
                            if (ch.durationMs > 0) Text(formatDuration(ch.durationMs))
                        },
                        modifier = Modifier.clickable {
                            container.playbackController.skipToChapter(idx)
                            showChapters = false
                        }
                    )
                }
            }
        }
    }
}
