package io.lightknight.omniread.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.engine.youtube.YoutubeDownloadSpec
import io.lightknight.omniread.engine.youtube.YoutubeImportPreview
import io.lightknight.omniread.ui.detail.formatDuration
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YoutubeImportScreen(
    container: OmniAppContainer,
    onBack: () -> Unit,
    onGoToQueue: () -> Unit
) {
    var urlInput by remember { mutableStateOf("") }
    var isFetching by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<YoutubeImportPreview?>(null) }
    val selectedMap = remember { mutableStateMapOf<Int, Boolean>() }
    var audioFormat by remember { mutableStateOf("m4a") }
    var audioQuality by remember { mutableStateOf("128K") }
    var splitChapters by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Tải Sách nói YouTube") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Link YouTube Video hoặc Playlist") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !isFetching && urlInput.isNotBlank(),
                    onClick = {
                        isFetching = true
                        scope.launch {
                            val res = container.youtubeAudioDownloader.fetchPreview(urlInput)
                            isFetching = false
                            res.onSuccess { p ->
                                preview = p
                                selectedMap.clear()
                                p.entries.indices.forEach { selectedMap[it] = true }
                                splitChapters = p.embeddedChapters.isNotEmpty()
                            }.onFailure { err ->
                                snackbarHostState.showSnackbar("Lỗi đọc playlist: ${err.message}")
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.Search, null)
                }
            }

            if (isFetching) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text("Đang phân tích playlist bằng yt-dlp...")
                }
            }

            preview?.let { p ->
                Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${p.uploader} · ${p.entries.size} video" +
                        if (p.embeddedChapters.isNotEmpty()) " · ${p.embeddedChapters.size} chapters nhúng" else "",
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    "Chỉ tải luồng âm thanh thuần (audio-only, không tải video)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("m4a" to "M4A (Gốc · Nhanh)", "opus" to "OPUS (Nhẹ nhất)", "mp3" to "MP3").forEach { (fmt, label) ->
                        FilterChip(
                            selected = audioFormat == fmt,
                            onClick = { audioFormat = fmt },
                            label = { Text(label) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("64K", "128K", "192K").forEach { q ->
                        FilterChip(
                            selected = audioQuality == q,
                            onClick = { audioQuality = q },
                            label = { Text(q) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tách chương theo YouTube Chapters")
                    Switch(checked = splitChapters, onCheckedChange = { splitChapters = it })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        p.entries.indices.forEach { selectedMap[it] = true }
                    }) { Text("Chọn hết") }
                    OutlinedButton(onClick = {
                        p.entries.indices.forEach { selectedMap[it] = false }
                    }) { Text("Bỏ chọn") }
                    Button(
                        onClick = {
                            val chosen = p.entries.filterIndexed { idx, _ -> selectedMap[idx] == true }
                            if (chosen.isNotEmpty()) {
                                container.youtubeAudioDownloader.enqueueDownload(
                                    YoutubeDownloadSpec(
                                        title = p.title,
                                        uploader = p.uploader,
                                        thumbnailUrl = p.thumbnailUrl,
                                        sourceUrl = p.sourceUrl,
                                        audioFormat = audioFormat,
                                        audioQuality = audioQuality,
                                        splitChapters = splitChapters,
                                        trackUrls = chosen.map { it.url },
                                        trackTitles = chosen.map { it.title }
                                    )
                                )
                                onGoToQueue()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Tải (${p.entries.indices.count { selectedMap[it] == true }})")
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(p.entries) { idx, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedMap[idx] == true,
                                onCheckedChange = { checked -> selectedMap[idx] = checked }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.title, maxLines = 2)
                                if (entry.durationSec > 0) {
                                    Text(
                                        formatDuration(entry.durationSec * 1000L),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
