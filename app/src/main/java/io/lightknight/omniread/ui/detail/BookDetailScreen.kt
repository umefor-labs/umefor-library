package io.lightknight.omniread.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.lightknight.omniread.core.AppSettings
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.engine.ai.BookAiAssistant
import io.lightknight.omniread.engine.sync.WebDavConfig
import io.lightknight.omniread.ui.library.BookCoverBox
import io.lightknight.omniread.ui.theme.UmeforLime
import io.lightknight.omniread.ui.theme.UmeforTealDeep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    itemId: String,
    container: OmniAppContainer,
    onBack: () -> Unit,
    onOpenChapter: (LibraryItemEntity, Int) -> Unit,
    onOpenVoiceCast: (String) -> Unit,
    onBatchDownloadWeb: (String) -> Unit = {}
) {
    val item by container.libraryRepository.observeItem(itemId).collectAsState(initial = null)
    val chapters by container.libraryRepository.observeChapters(itemId).collectAsState(initial = emptyList())
    val progress by container.libraryRepository.observeProgress(itemId).collectAsState(initial = null)
    val bookmarks by container.libraryRepository.observeBookmarks(itemId).collectAsState(initial = emptyList())
    val highlights by container.libraryRepository.observeHighlights(itemId).collectAsState(initial = emptyList())
    val voiceCast by container.libraryRepository.observeVoiceCast(itemId).collectAsState(initial = emptyList())
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var confirmDelete by remember { mutableStateOf(false) }
    var isRefreshingChapters by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Chapters, 1 = Info & Notes
    var reverseOrder by remember { mutableStateOf(false) }
    var isGeneratingRecap by remember { mutableStateOf(false) }
    var recapDialogText by remember { mutableStateOf<String?>(null) }
    var customShelfInput by remember(item?.shelfTag) { mutableStateOf(item?.shelfTag.orEmpty()) }
    val autoRefreshedIds = remember { mutableSetOf<String>() }

    fun triggerRefreshChapters(bookId: String, silentOnZero: Boolean = false) {
        if (isRefreshingChapters) return
        isRefreshingChapters = true
        scope.launch {
            val res = container.webChapterDownloader.refreshWebChapters(bookId)
            isRefreshingChapters = false
            res.onSuccess { count ->
                snackbarHostState.showSnackbar("Đã cập nhật $count chương từ nguồn web")
            }.onFailure { err ->
                if (!silentOnZero) {
                    snackbarHostState.showSnackbar("Không cập nhật được chương: ${err.message}")
                }
            }
        }
    }

    LaunchedEffect(itemId) {
        container.libraryRepository.clearUnreadNewChapters(itemId)
    }

    LaunchedEffect(item?.id) {
        val b = item ?: return@LaunchedEffect
        if (b.coverLocalPath.isNullOrBlank() || !File(b.coverLocalPath).exists()) {
            container.webChapterDownloader.repairMissingCover(b.id)
        }
    }

    LaunchedEffect(item?.id, chapters.isEmpty()) {
        val b = item ?: return@LaunchedEffect
        if (b.originType == OriginType.WEB_PLUGIN && chapters.isEmpty() && b.id !in autoRefreshedIds) {
            autoRefreshedIds += b.id
            triggerRefreshChapters(b.id, silentOnZero = true)
        }
    }

    val displayChapters = remember(chapters, reverseOrder) {
        val indexed = chapters.mapIndexed { idx, ch -> idx to ch }
        if (reverseOrder) indexed.reversed() else indexed
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(item?.title ?: "Chi tiết", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                    }
                },
                actions = {
                    if (item?.originType == OriginType.WEB_PLUGIN) {
                        IconButton(
                            onClick = { item?.let { triggerRefreshChapters(it.id) } },
                            enabled = !isRefreshingChapters
                        ) {
                            Icon(Icons.Default.Refresh, "Cập nhật chương")
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, "Xóa khỏi thư viện")
                    }
                }
            )
        }
    ) { padding ->
        val book = item ?: return@Scaffold
        val downloadedCount = chapters.count { it.downloadState == DownloadState.DOWNLOADED }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.verticalGradient(listOf(UmeforTealDeep.copy(alpha = 0.55f), Color.Transparent)))
                        .padding(horizontal = 14.dp, vertical = 18.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BookCoverBox(
                            book,
                            modifier = Modifier
                                .size(width = 136.dp, height = 196.dp)
                                .shadow(12.dp, RoundedCornerShape(14.dp))
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (book.author.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                book.author,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatPill("Chương/Tập", "${chapters.size}")
                                StatPill("Loại", when (book.mediaType) {
                                    MediaType.NOVEL -> "Chữ"
                                    MediaType.COMIC -> "Tranh"
                                    MediaType.AUDIOBOOK -> "Audio"
                                })
                                StatPill("Đã tải", "$downloadedCount/${chapters.size}")
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            onClick = {
                                val targetIdx = progress?.currentChapterIndex
                                    ?.coerceIn(0, (chapters.size - 1).coerceAtLeast(0)) ?: 0
                                onOpenChapter(book, targetIdx)
                            },
                            enabled = chapters.isNotEmpty()
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (progress != null && chapters.isNotEmpty()) {
                                    "Tiếp tục (Ch.${progress!!.currentChapterIndex + 1})"
                                } else {
                                    "Bắt đầu đọc / nghe"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (book.originType == OriginType.WEB_PLUGIN) {
                                ActionTileButton(
                                    label = if (isRefreshingChapters) "Đang lấy" else "Cập nhật",
                                    icon = Icons.Default.Refresh,
                                    loading = isRefreshingChapters,
                                    modifier = Modifier.weight(1f),
                                    onClick = { triggerRefreshChapters(book.id) }
                                )
                            }
                            if (book.mediaType == MediaType.NOVEL) {
                                ActionTileButton(
                                    label = "Phân vai TTS",
                                    icon = Icons.Default.RecordVoiceOver,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onOpenVoiceCast(book.id) }
                                )
                            }
                            if (chapters.any { it.downloadState == DownloadState.REMOTE }) {
                                ActionTileButton(
                                    label = "Tải hết",
                                    icon = Icons.Default.Download,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        onBatchDownloadWeb(book.id)
                                        scope.launch { snackbarHostState.showSnackbar("Đang tải toàn bộ chương về máy...") }
                                    }
                                )
                            }
                            if (chapters.any { !it.driveFileId.isNullOrBlank() && it.downloadState == DownloadState.DOWNLOADED }) {
                                ActionTileButton(
                                    label = "Dọn cache",
                                    icon = Icons.Default.CloudOff,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch {
                                            val evicted = container.libraryRepository.evictLocalCache(book.id)
                                            snackbarHostState.showSnackbar(
                                                if (evicted) "Đã giải phóng bộ nhớ máy (giữ bản trên Cloud)"
                                                else "Chưa có bản sao trên Cloud"
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            label = { Text("Chương (${chapters.size})") }
                        )
                        FilterChip(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            label = { Text("Thông tin & Ghi chú") }
                        )
                    }
                    if (selectedTab == 0 && chapters.size > 1) {
                        TextButton(
                            onClick = { reverseOrder = !reverseOrder },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(Icons.Default.SwapVert, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (reverseOrder) "Mới → Cũ" else "Cũ → Mới", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (selectedTab == 0) {
                if (chapters.isEmpty() && book.originType == OriginType.WEB_PLUGIN) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Chưa tải được danh sách chương (nguồn web có thể vừa giới hạn tốc độ hoặc vừa nâng cấp plugin).",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Button(
                                    onClick = { triggerRefreshChapters(book.id) },
                                    enabled = !isRefreshingChapters
                                ) {
                                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (isRefreshingChapters) "Đang tải..." else "Cập nhật chương từ nguồn")
                                }
                            }
                        }
                    }
                }

                itemsIndexed(displayChapters, key = { _, pair -> pair.second.id }) { _, (origIdx, ch) ->
                    ChapterRow(
                        chapter = ch,
                        isCurrent = progress?.currentChapterId == ch.id,
                        onClick = { onOpenChapter(book, origIdx) }
                    )
                }
            } else {
                // Kệ sách / Tag + Theo dõi chương mới
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Kệ sách / Nhãn phân loại", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(listOf("Đang đọc", "Tiên hiệp", "Light Novel", "Manga", "Tài liệu học", "Hoàn thành")) { tag ->
                                    FilterChip(
                                        selected = book.shelfTag == tag,
                                        onClick = {
                                            val next = if (book.shelfTag == tag) "" else tag
                                            customShelfInput = next
                                            scope.launch { container.libraryRepository.updateShelfTag(book.id, next) }
                                        },
                                        label = { Text(tag) }
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = customShelfInput,
                                    onValueChange = { customShelfInput = it },
                                    label = { Text("Nhãn kệ tùy chỉnh") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        container.libraryRepository.updateShelfTag(book.id, customShelfInput)
                                        snackbarHostState.showSnackbar("Đã lưu kệ sách")
                                    }
                                }) { Text("Lưu") }
                            }
                            if (book.originType == OriginType.WEB_PLUGIN) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Tự động kiểm tra chương mới", style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = book.trackUpdates,
                                        onCheckedChange = { enabled ->
                                            scope.launch { container.libraryRepository.updateTrackUpdates(book.id, enabled) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // AI Recap (Spoiler-free) + Sổ tay nhân vật
                if (book.mediaType == MediaType.NOVEL) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Trợ lý AI & Sổ tay nhân vật", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Button(
                                    enabled = !isGeneratingRecap && chapters.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        isGeneratingRecap = true
                                        scope.launch {
                                            val maxIdx = progress?.currentChapterIndex ?: 0
                                            val loadedUpToCurrent = withContext(Dispatchers.IO) {
                                                chapters.filter { it.chapterIndex <= maxIdx }
                                                    .takeLast(4)
                                                    .mapNotNull { ch ->
                                                        val f = ch.localFilePath?.let(::File)?.takeIf { it.exists() }
                                                            ?: container.webChapterDownloader.ensureChapterDownloaded(ch)
                                                        if (f != null && f.exists()) {
                                                            ch.title to f.readText(Charsets.UTF_8)
                                                        } else null
                                                    }
                                            }
                                            val res = container.bookAiAssistant.generateSpoilerFreeRecap(
                                                bookTitle = book.title,
                                                recentChaptersUpToCurrent = loadedUpToCurrent,
                                                characters = voiceCast
                                            )
                                            isGeneratingRecap = false
                                            res.onSuccess { recapDialogText = it }
                                                .onFailure { snackbarHostState.showSnackbar("Lỗi tóm tắt AI: ${it.message}") }
                                        }
                                    }
                                ) {
                                    if (isGeneratingRecap) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                    } else {
                                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(if (isGeneratingRecap) "Đang tóm tắt..." else "Nhớ lại cốt truyện (AI không spoil)")
                                }

                                val chars = voiceCast.filter { !it.isNarrator }
                                if (chars.isNotEmpty()) {
                                    Text("Nhân vật đã nhận diện (${chars.size}):", style = MaterialTheme.typography.labelLarge)
                                    chars.forEach { cv ->
                                        Text(
                                            "• ${cv.characterName} (${cv.gender} · ${cv.ageGroup}) — ${cv.voiceId}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    Text(
                                        "Chưa có nhân vật (bấm Phân vai TTS hoặc nghe 1 chương để AI lập hồ sơ nhân vật).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Highlights & Export Markdown
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Highlights & Ghi chú (${highlights.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                OutlinedButton(
                                    enabled = highlights.isNotEmpty(),
                                    onClick = {
                                        scope.launch {
                                            val md = BookAiAssistant.buildNotesMarkdown(book.title, book.author, highlights)
                                            val outFile = withContext(Dispatchers.IO) {
                                                File(container.storageManager.itemDir(book.id), "notes.md").apply {
                                                    parentFile?.mkdirs()
                                                    writeText(md, Charsets.UTF_8)
                                                }
                                            }
                                            clipboard.setText(AnnotatedString(md))
                                            var extraMsg = "Đã lưu ${outFile.name} & sao chép Markdown"
                                            if (settings.syncProvider.equals("WEBDAV", true) && settings.webDavConnected) {
                                                val pass = container.settingsRepository.getWebDavPassword()
                                                if (pass.isNotBlank()) {
                                                    val cfg = WebDavConfig(settings.webDavUrl, settings.webDavUsername, pass)
                                                    val safeName = book.title.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(50)
                                                    runCatching {
                                                        container.webDavSyncClient.ensureDirectory(cfg, "UmeforLibrary/Notes")
                                                        container.webDavSyncClient.uploadText(cfg, "UmeforLibrary/Notes/$safeName.md", md, "text/markdown; charset=utf-8")
                                                    }.onSuccess {
                                                        extraMsg = "Đã xuất Markdown lên máy & UmeforLibrary/Notes/$safeName.md"
                                                    }
                                                }
                                            }
                                            snackbarHostState.showSnackbar(extraMsg)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.FileDownload, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Xuất .md")
                                }
                            }
                            if (highlights.isEmpty()) {
                                Text(
                                    "Nhấn giữ bất kỳ đoạn văn nào khi đọc để tô Highlight, viết ghi chú hoặc dịch bằng AI.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                highlights.forEach { hl ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(hl.colorHex).copy(alpha = 0.16f),
                                        border = BorderStroke(1.dp, Color(hl.colorHex).copy(alpha = 0.55f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(hl.chapterTitle, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                Text(hl.quoteText, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                                if (hl.note.isNotBlank()) {
                                                    Text("Ghi chú: ${hl.note}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                            IconButton(onClick = {
                                                scope.launch { container.libraryRepository.deleteHighlight(hl.id) }
                                            }) {
                                                Icon(Icons.Default.Delete, "Xóa highlight", Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (book.synopsis.isNotBlank()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                book.synopsis,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }

                if (bookmarks.isNotEmpty()) {
                    item {
                        Text("Dấu trang (${bookmarks.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    itemsIndexed(bookmarks, key = { _, bm -> bm.id }) { _, bm ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ListItem(
                                headlineContent = { Text(bm.positionLabel) },
                                supportingContent = if (bm.note.isNotBlank()) { { Text(bm.note) } } else null
                            )
                        }
                    }
                }
            }
        }
    }

    recapDialogText?.let { recap ->
        AlertDialog(
            onDismissRequest = { recapDialogText = null },
            title = { Text("Tóm tắt cốt truyện (Không spoil)") },
            text = { Text(recap, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { recapDialogText = null }) { Text("Đóng") }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xóa khỏi thư viện?") },
            text = { Text("Sách và các tệp tải cục bộ sẽ bị xóa khỏi thiết bị.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        container.libraryRepository.deleteBook(itemId)
                        onBack()
                    }
                }) { Text("Xóa") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Hủy") }
            }
        )
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionTileButton(
    label: String,
    icon: ImageVector,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier.clickable(enabled = !loading, onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChapterRow(chapter: ChapterEntity, isCurrent: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
        border = if (isCurrent) BorderStroke(1.dp, UmeforLime.copy(alpha = 0.6f)) else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        ListItem(
            headlineContent = {
                Text(
                    chapter.title,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            },
            supportingContent = {
                val info = buildList {
                    if (chapter.pageCount > 0) add("${chapter.pageCount} trang")
                    if (chapter.durationMs > 0) add(formatDuration(chapter.durationMs))
                }.joinToString(" · ")
                if (info.isNotBlank()) Text(info)
            },
            trailingContent = {
                val icon = when (chapter.downloadState) {
                    DownloadState.DOWNLOADED -> Icons.Default.DownloadDone
                    DownloadState.CLOUD_ONLY -> Icons.Default.CloudDone
                    else -> Icons.Default.Download
                }
                Icon(
                    icon,
                    contentDescription = chapter.downloadState.name,
                    tint = if (chapter.downloadState == DownloadState.DOWNLOADED) UmeforLime else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
