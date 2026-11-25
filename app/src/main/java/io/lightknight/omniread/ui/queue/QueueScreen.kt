package io.lightknight.omniread.ui.queue

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.lightknight.omniread.core.AppSettings
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.engine.sync.WebDavPreset
import io.lightknight.omniread.ui.theme.UmeforLime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    container: OmniAppContainer,
    onSyncNow: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val ytProgress by container.youtubeAudioDownloader.progress.collectAsState()
    val syncJobs by container.database.syncJobDao().observeAll().collectAsState(initial = emptyList())
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())

    val useWebDav = settings.syncProvider.equals("WEBDAV", ignoreCase = true)
    val isConnected = if (useWebDav) settings.webDavConnected && settings.webDavUsername.isNotBlank() else settings.driveEmail.isNotBlank()
    val providerLabel = if (useWebDav) {
        "WebDAV · ${WebDavPreset.fromId(settings.webDavPreset).displayName}"
    } else {
        "Google Drive"
    }
    val accountLabel = if (useWebDav) settings.webDavUsername else settings.driveEmail
    val pendingCount = syncJobs.count { it.status == "PENDING" || it.status == "FAILED" }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Tải & Đồng bộ") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isConnected) Color(0xFF15382C) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (isConnected) UmeforLime.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                if (isConnected) Icons.Default.CheckCircle else Icons.Default.CloudOff,
                                null,
                                tint = if (isConnected) UmeforLime else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                if (isConnected) "Đã kết nối" else "Chưa kết nối",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isConnected) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                providerLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isConnected) UmeforLime else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                Icons.Default.PendingActions,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "$pendingCount tác vụ",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Đang chờ đồng bộ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudSync, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(providerLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Text(
                            if (isConnected) "Tài khoản: $accountLabel"
                            else "Chưa đăng nhập đám mây — vào Cài đặt để kết nối bằng tài khoản WebDAV (Koofr / InfiniCLOUD).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val syncTime = if (settings.driveLastSyncAt > 0L) {
                            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(settings.driveLastSyncAt))
                        } else "Chưa đồng bộ"
                        Text("Lần đồng bộ cuối: $syncTime", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSyncNow, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Đồng bộ ngay")
                            }
                            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Cấu hình Cloud")
                            }
                        }
                    }
                }
            }

            if (ytProgress.isRunning || ytProgress.statusLine.isNotBlank()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "YouTube Sách nói: ${ytProgress.bookTitle}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                "Tập ${ytProgress.currentTrack}/${ytProgress.totalTracks} · ${ytProgress.statusLine}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (ytProgress.isRunning) {
                                LinearProgressIndicator(
                                    progress = { (ytProgress.percent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Nhật ký tác vụ (${syncJobs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(syncJobs, key = { it.id }) { job ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = { Text("${job.entityType} · ${job.action}", fontWeight = FontWeight.Medium) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append("Trạng thái: ${job.status}")
                                    if (job.progressPercent in 1..99) append(" (${job.progressPercent}%)")
                                    if (!job.lastError.isNullOrBlank()) append(" · ${job.lastError}")
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}
