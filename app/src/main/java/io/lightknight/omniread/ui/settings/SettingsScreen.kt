package io.lightknight.omniread.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.platform.LocalContext
import io.lightknight.omniread.engine.scraper.ChapterUpdateWorker
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.lightknight.omniread.core.AppSettings
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.data.model.AppThemeMode
import io.lightknight.omniread.engine.sync.WebDavConfig
import io.lightknight.omniread.engine.sync.WebDavPreset
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: OmniAppContainer,
    onSignInDrive: () -> Unit = {},
    onSignInDriveBrowser: (String, String) -> Unit = { _, _ -> },
    onSignOutDrive: () -> Unit = {},
    onUpdateYtDlp: suspend () -> String = { "Chưa kích hoạt" },
    onOpenPronunciation: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var isCheckingUpdates by remember { mutableStateOf(false) }

    var webDavPreset by remember(settings.webDavPreset) { mutableStateOf(settings.webDavPreset) }
    var webDavUrl by remember(settings.webDavUrl) { mutableStateOf(settings.webDavUrl) }
    var webDavUser by remember(settings.webDavUsername) { mutableStateOf(settings.webDavUsername) }
    var webDavPass by remember { mutableStateOf(container.settingsRepository.getWebDavPassword()) }
    var isTestingWebDav by remember { mutableStateOf(false) }

    var geminiModel by remember(settings.geminiModel) { mutableStateOf(settings.geminiModel) }
    var geminiKey by remember { mutableStateOf(container.settingsRepository.getGeminiApiKey()) }
    var customTtsUrl by remember(settings.customTtsBaseUrl) { mutableStateOf(settings.customTtsBaseUrl) }
    var customTtsModel by remember(settings.customTtsModel) { mutableStateOf(settings.customTtsModel) }
    var customTtsVoice by remember(settings.customTtsVoice) { mutableStateOf(settings.customTtsVoice) }
    var customTtsIsGemini by remember(settings.customTtsIsGemini) { mutableStateOf(settings.customTtsIsGemini) }
    var customTtsKey by remember { mutableStateOf(container.settingsRepository.getCustomTtsApiKey()) }

    var oauthClientId by remember { mutableStateOf(container.driveAuthManager.getSavedOAuthClientId()) }
    var oauthClientSecret by remember { mutableStateOf(container.driveAuthManager.getSavedOAuthClientSecret()) }
    val apkSha1 = remember { container.driveAuthManager.getCurrentSha1() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("Cài đặt Umefor Library") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsSectionCard(
                title = "Đồng bộ Đám mây (Cloud Sync)",
                icon = Icons.Default.CloudSync
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.syncProvider.equals("WEBDAV", ignoreCase = true),
                        onClick = { scope.launch { container.settingsRepository.updateSyncProvider("WEBDAV") } },
                        label = { Text("WebDAV (Khuyên dùng)") }
                    )
                    FilterChip(
                        selected = settings.syncProvider.equals("GOOGLE_DRIVE", ignoreCase = true),
                        onClick = { scope.launch { container.settingsRepository.updateSyncProvider("GOOGLE_DRIVE") } },
                        label = { Text("Google Drive") }
                    )
                }

                if (settings.syncProvider.equals("WEBDAV", ignoreCase = true)) {
                    Text(
                        "Không cần Client ID hay SHA-1: chỉ cần tài khoản miễn phí (Koofr 10GB hoặc InfiniCLOUD 20GB).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WebDavPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = webDavPreset.equals(preset.id, ignoreCase = true),
                                onClick = {
                                    webDavPreset = preset.id
                                    if (preset.defaultUrl.isNotBlank()) {
                                        webDavUrl = preset.defaultUrl
                                    }
                                },
                                label = { Text(preset.displayName) }
                            )
                        }
                    }

                    val activePreset = WebDavPreset.fromId(webDavPreset)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            activePreset.hint,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    if (settings.webDavConnected && settings.webDavUsername.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Đã kết nối: ${settings.webDavUsername}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    OutlinedTextField(
                        value = webDavUrl,
                        onValueChange = { webDavUrl = it },
                        label = { Text("URL máy chủ WebDAV") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = webDavUser,
                        onValueChange = { webDavUser = it },
                        label = { Text("Email / Tên đăng nhập") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = webDavPass,
                        onValueChange = { webDavPass = it },
                        label = { Text("Mật khẩu ứng dụng (App Password)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !isTestingWebDav && webDavUrl.isNotBlank() && webDavUser.isNotBlank() && webDavPass.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                isTestingWebDav = true
                                scope.launch {
                                    val cfg = WebDavConfig(webDavUrl, webDavUser, webDavPass)
                                    val testRes = container.webDavSyncClient.testConnection(cfg)
                                    testRes.onSuccess { msg ->
                                        container.settingsRepository.updateWebDavConfig(
                                            preset = webDavPreset,
                                            url = webDavUrl,
                                            username = webDavUser,
                                            password = webDavPass,
                                            connected = true
                                        )
                                        container.syncOrchestrator.triggerImmediateSync(includeMedia = true)
                                        snackbarHostState.showSnackbar("$msg Đang đồng bộ ngay...")
                                    }.onFailure { err ->
                                        snackbarHostState.showSnackbar(err.message ?: "Kết nối WebDAV thất bại")
                                    }
                                    isTestingWebDav = false
                                }
                            }
                        ) {
                            if (isTestingWebDav) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (isTestingWebDav) "Đang kiểm tra..." else "Đăng nhập & Kiểm tra")
                        }
                        if (settings.webDavConnected) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    container.settingsRepository.disconnectWebDav()
                                    webDavPass = ""
                                    snackbarHostState.showSnackbar("Đã ngắt kết nối WebDAV")
                                }
                            }) { Text("Đăng xuất") }
                        }
                    }
                } else {
                    Text(
                        if (settings.driveEmail.isNotBlank()) "Đã kết nối: ${settings.driveEmail}"
                        else "Chưa đăng nhập Google Drive",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (settings.driveEmail.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSignInDrive) {
                            Text(if (settings.driveEmail.isBlank()) "Đăng nhập Google (Play Services)" else "Đổi tài khoản")
                        }
                        if (settings.driveEmail.isNotBlank()) {
                            OutlinedButton(onClick = onSignOutDrive) { Text("Ngắt kết nối") }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Đăng nhập qua Trình duyệt (OAuth PKCE)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedTextField(
                                value = oauthClientId,
                                onValueChange = { oauthClientId = it },
                                label = { Text("OAuth Client ID (Desktop/Web)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = oauthClientSecret,
                                onValueChange = { oauthClientSecret = it },
                                label = { Text("Client Secret (nếu Google yêu cầu)") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedButton(
                                onClick = { onSignInDriveBrowser(oauthClientId, oauthClientSecret) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Public, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Lưu & Đăng nhập qua Trình duyệt")
                            }
                        }
                    }

                    if (apkSha1.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Package: io.lightknight.omniread",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        "SHA-1: $apkSha1",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                IconButton(onClick = {
                                    clipboard.setText(AnnotatedString(apkSha1))
                                    scope.launch { snackbarHostState.showSnackbar("Đã sao chép SHA-1") }
                                }) {
                                    Icon(Icons.Default.ContentCopy, "Sao chép SHA-1", Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tự động đồng bộ nền")
                    Switch(
                        checked = settings.driveSyncEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                container.settingsRepository.updateDriveSettings(enabled, settings.driveWifiOnlyForMedia)
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chỉ đẩy file truyện/audio lớn qua Wi-Fi")
                    Switch(
                        checked = settings.driveWifiOnlyForMedia,
                        onCheckedChange = { wifiOnly ->
                            scope.launch {
                                container.settingsRepository.updateDriveSettings(settings.driveSyncEnabled, wifiOnly)
                            }
                        }
                    )
                }
            }

            SettingsSectionCard(
                title = "Theo dõi chương mới tự động",
                icon = Icons.Default.NotificationsActive
            ) {
                Text("Chu kỳ kiểm tra nền:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Tắt", 6 to "6 giờ", 12 to "12 giờ", 24 to "24 giờ").forEach { (hrs, label) ->
                        FilterChip(
                            selected = settings.chapterCheckIntervalHours == hrs,
                            onClick = {
                                scope.launch {
                                    container.settingsRepository.updateChapterTrackerConfig(hrs, settings.autoDownloadNewChapters)
                                    ChapterUpdateWorker.schedulePeriodicCheck(context, hrs)
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tự tải sẵn chương mới về máy")
                    Switch(
                        checked = settings.autoDownloadNewChapters,
                        onCheckedChange = { autoDl ->
                            scope.launch {
                                container.settingsRepository.updateChapterTrackerConfig(settings.chapterCheckIntervalHours, autoDl)
                            }
                        }
                    )
                }
                OutlinedButton(
                    enabled = !isCheckingUpdates,
                    onClick = {
                        isCheckingUpdates = true
                        scope.launch {
                            val totalNew = ChapterUpdateWorker.checkAllTrackedBooks(context, container)
                            isCheckingUpdates = false
                            snackbarHostState.showSnackbar(
                                if (totalNew > 0) "Phát hiện $totalNew chương mới!" else "Tất cả truyện đều đã mới nhất."
                            )
                        }
                    }
                ) {
                    if (isCheckingUpdates) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isCheckingUpdates) "Đang quét nguồn..." else "Kiểm tra chương mới ngay")
                }
            }

            SettingsSectionCard(
                title = "Điều khiển đọc & Từ điển phát âm TTS",
                icon = Icons.Default.Tune
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lật trang bằng phím âm lượng")
                    Switch(
                        checked = settings.volumeKeyPageTurn,
                        onCheckedChange = { enabled ->
                            scope.launch { container.settingsRepository.updateVolumeKeyPageTurn(enabled) }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Truyện tranh 2 trang khi xoay ngang")
                    Switch(
                        checked = settings.comicDoublePageLandscape,
                        onCheckedChange = { enabled ->
                            scope.launch { container.settingsRepository.updateComicDoublePageLandscape(enabled) }
                        }
                    )
                }
                OutlinedButton(onClick = onOpenPronunciation, modifier = Modifier.fillMaxWidth()) {
                    Text("Mở Từ điển phát âm & Lọc rác TTS")
                }
            }

            SettingsSectionCard(
                title = "Giao diện & Ngôn ngữ",
                icon = Icons.Default.Palette
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppThemeMode.LIGHT to "Sáng",
                        AppThemeMode.SEPIA to "Sepia",
                        AppThemeMode.DARK to "Tối",
                        AppThemeMode.AMOLED to "AMOLED"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { scope.launch { container.settingsRepository.updateThemeMode(mode) } },
                            label = { Text(label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.languageCode == "vi",
                        onClick = { scope.launch { container.settingsRepository.updateLanguage("vi") } },
                        label = { Text("Tiếng Việt") }
                    )
                    FilterChip(
                        selected = settings.languageCode == "en",
                        onClick = { scope.launch { container.settingsRepository.updateLanguage("en") } },
                        label = { Text("English") }
                    )
                }
            }

            SettingsSectionCard(
                title = "AI Phân vai & Giọng đọc TTS",
                icon = Icons.Default.RecordVoiceOver
            ) {
                OutlinedTextField(
                    value = geminiModel,
                    onValueChange = { geminiModel = it },
                    label = { Text("Gemini Model (phân vai)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key (BYOK)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(onClick = {
                    scope.launch {
                        container.settingsRepository.updateGeminiConfig(geminiModel, geminiKey)
                        snackbarHostState.showSnackbar("Đã lưu cấu hình Gemini AI")
                    }
                }) { Text("Lưu cấu hình Gemini") }
            }

            SettingsSectionCard(
                title = "TTS API / Server riêng (Tùy chọn)",
                icon = Icons.Default.GraphicEq
            ) {
                OutlinedTextField(
                    value = customTtsUrl,
                    onValueChange = { customTtsUrl = it },
                    label = { Text("Base URL (OpenAI /v1/audio/speech hoặc Gemini)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customTtsModel,
                        onValueChange = { customTtsModel = it },
                        label = { Text("Model") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = customTtsVoice,
                        onValueChange = { customTtsVoice = it },
                        label = { Text("Voice") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = customTtsKey,
                    onValueChange = { customTtsKey = it },
                    label = { Text("API Key TTS riêng") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dùng giao thức Gemini TTS")
                    Switch(checked = customTtsIsGemini, onCheckedChange = { customTtsIsGemini = it })
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        container.settingsRepository.updateCustomTtsConfig(
                            customTtsUrl, customTtsModel, customTtsVoice, customTtsIsGemini, customTtsKey
                        )
                        snackbarHostState.showSnackbar("Đã lưu Custom TTS")
                    }
                }) { Text("Lưu Custom TTS") }
            }

            SettingsSectionCard(
                title = "YouTube Sách nói (yt-dlp)",
                icon = Icons.Default.VideoLibrary
            ) {
                Text("Phiên bản lõi hiện tại: ${settings.ytDlpVersion}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    scope.launch {
                        val msg = onUpdateYtDlp()
                        snackbarHostState.showSnackbar(msg)
                    }
                }) { Text("Cập nhật lõi yt-dlp trong app") }
            }

            SettingsSectionCard(
                title = "Bộ nhớ cục bộ",
                icon = Icons.Default.Storage
            ) {
                val libMb = container.storageManager.calculateLibraryBytes() / (1024f * 1024f)
                val cacheMb = container.storageManager.calculateTtsCacheBytes() / (1024f * 1024f)
                Text("Thư viện: ${"%.1f".format(libMb)} MB · Cache TTS: ${"%.1f".format(cacheMb)} MB")
                OutlinedButton(onClick = {
                    container.storageManager.clearTtsCache()
                    scope.launch { snackbarHostState.showSnackbar("Đã dọn cache TTS") }
                }) { Text("Xóa cache âm thanh TTS") }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}
