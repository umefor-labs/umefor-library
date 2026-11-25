package io.lightknight.omniread.ui.voicecast

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.data.local.CharacterVoiceEntity
import io.lightknight.omniread.engine.tts.EdgeTtsEngine
import io.lightknight.omniread.engine.tts.VoiceOption
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCastStudioScreen(
    itemId: String,
    container: OmniAppContainer,
    onBack: () -> Unit
) {
    val item by container.libraryRepository.observeItem(itemId).collectAsState(initial = null)
    val voices by container.libraryRepository.observeVoiceCast(itemId).collectAsState(initial = emptyList())
    var availableVoices by remember { mutableStateOf<List<VoiceOption>>(EdgeTtsEngine.presetVoices) }
    var showAddDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        availableVoices = container.ttsEngineRegistry.allVoices()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Voice Cast Studio", style = MaterialTheme.typography.titleMedium)
                        Text(item?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "Thêm nhân vật")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Gán giọng đọc, tốc độ, cao độ và khoảng nghỉ riêng cho từng nhân vật trong truyện.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(voices, key = { it.id }) { character ->
                CharacterVoiceCard(
                    character = character,
                    availableVoices = availableVoices,
                    onUpdate = { updated ->
                        scope.launch { container.libraryRepository.upsertVoiceCast(updated) }
                    },
                    onPreview = {
                        scope.launch {
                            val sample = "Xin chào, tôi là ${character.characterName} đang thử giọng đọc."
                            val res = container.ttsChapterPlayer.previewVoice(character, sample)
                            res.onFailure {
                                snackbarHostState.showSnackbar("Lỗi nghe thử: ${it.message}")
                            }
                        }
                    },
                    onDelete = {
                        scope.launch { container.libraryRepository.deleteVoiceCast(character.id) }
                    }
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var gender by remember { mutableStateOf("MALE") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Thêm nhân vật mới") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Tên nhân vật") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("MALE" to "Nam", "FEMALE" to "Nữ", "NEUTRAL" to "Trung tính").forEach { (g, label) ->
                            FilterChip(selected = gender == g, onClick = { gender = g }, label = { Text(label) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            val defaultVoice = if (gender == "MALE") "vi-VN-NamMinhNeural" else "vi-VN-HoaiMyNeural"
                            scope.launch {
                                container.libraryRepository.upsertVoiceCast(
                                    CharacterVoiceEntity(
                                        id = UUID.randomUUID().toString(),
                                        itemId = itemId,
                                        characterName = name.trim(),
                                        gender = gender,
                                        engineId = EdgeTtsEngine.ENGINE_ID,
                                        voiceId = defaultVoice
                                    )
                                )
                            }
                            showAddDialog = false
                        }
                    }
                ) { Text("Thêm") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Hủy") } }
        )
    }
}

@Composable
private fun CharacterVoiceCard(
    character: CharacterVoiceEntity,
    availableVoices: List<VoiceOption>,
    onUpdate: (CharacterVoiceEntity) -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(character.characterName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${character.engineId} · ${character.voiceId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    OutlinedButton(onClick = onPreview) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Thử")
                    }
                    if (!character.isNarrator) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, "Xóa vai")
                        }
                    }
                }
            }

            // Quick voice selector chips (filtered to key voices)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                availableVoices.take(4).forEach { opt ->
                    FilterChip(
                        selected = character.voiceId == opt.voiceId && character.engineId == opt.engineId,
                        onClick = {
                            onUpdate(character.copy(engineId = opt.engineId, voiceId = opt.voiceId))
                        },
                        label = { Text(opt.displayName.substringBefore(" (")) }
                    )
                }
            }

            Text("Tốc độ: ${"%.2f".format(character.speechRate)}x")
            Slider(
                value = character.speechRate,
                onValueChange = { onUpdate(character.copy(speechRate = it)) },
                valueRange = 0.6f..1.8f
            )

            Text("Cao độ: ${character.pitchHz} Hz")
            Slider(
                value = character.pitchHz.toFloat(),
                onValueChange = { onUpdate(character.copy(pitchHz = it.toInt())) },
                valueRange = -20f..20f
            )

            Text("Nghỉ sau câu: ${character.pauseAfterMs} ms")
            Slider(
                value = character.pauseAfterMs.toFloat(),
                onValueChange = { onUpdate(character.copy(pauseAfterMs = it.toInt())) },
                valueRange = 0f..1000f
            )
        }
    }
}
