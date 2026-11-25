package io.lightknight.omniread.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import io.lightknight.omniread.data.local.PronunciationRuleEntity
import io.lightknight.omniread.engine.tts.TextSanitizer
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PronunciationGlossaryScreen(
    container: OmniAppContainer,
    onBack: () -> Unit
) {
    val rules by container.libraryRepository.observePronunciationRules().collectAsState(initial = emptyList())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Từ điển phát âm & Lọc rác TTS") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "Thêm quy tắc")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Thay thế tên riêng tiếng Anh / thuật ngữ để TTS đọc chuẩn hơn, hoặc dùng Regex xóa bỏ dòng quảng cáo từ web trước khi đọc.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = {
                            scope.launch {
                                val presets = TextSanitizer.defaultVietnameseWebFilterRules()
                                container.libraryRepository.upsertPronunciationRules(presets)
                                snackbarHostState.showSnackbar("Đã nạp ${presets.size} quy tắc mẫu")
                            }
                        }) {
                            Icon(Icons.Default.AutoFixHigh, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Nạp bộ lọc rác web VN mẫu")
                        }
                    }
                }
            }

            items(rules, key = { it.id }) { rule ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${rule.pattern}  →  ${rule.replacement.ifBlank { "(Xóa bỏ)" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (rule.isRegex) "Regex · Khớp biểu thức" else "Thay thế cụm từ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    container.libraryRepository.upsertPronunciationRule(rule.copy(enabled = enabled))
                                }
                            }
                        )
                        IconButton(onClick = {
                            scope.launch { container.libraryRepository.deletePronunciationRule(rule.id) }
                        }) {
                            Icon(Icons.Default.Delete, "Xóa")
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var pattern by remember { mutableStateOf("") }
        var replacement by remember { mutableStateOf("") }
        var isRegex by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Thêm quy tắc phát âm / lọc rác") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        label = { Text("Cụm từ gốc hoặc Regex") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        label = { Text("Đọc thành (để trống nếu muốn xóa)") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Chế độ Regex")
                        Switch(checked = isRegex, onCheckedChange = { isRegex = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = pattern.isNotBlank(),
                    onClick = {
                        showAddDialog = false
                        scope.launch {
                            container.libraryRepository.upsertPronunciationRule(
                                PronunciationRuleEntity(
                                    id = UUID.randomUUID().toString(),
                                    pattern = pattern.trim(),
                                    replacement = replacement,
                                    isRegex = isRegex,
                                    enabled = true
                                )
                            )
                        }
                    }
                ) { Text("Lưu") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Hủy") }
            }
        )
    }
}
