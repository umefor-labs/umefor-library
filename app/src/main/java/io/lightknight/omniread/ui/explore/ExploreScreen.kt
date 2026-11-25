package io.lightknight.omniread.ui.explore

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.data.local.ExtensionRepoEntity
import io.lightknight.omniread.data.local.SourcePluginEntity
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.engine.scraper.CatalogExtensionItem
import io.lightknight.omniread.engine.scraper.ExtensionCatalogManager
import io.lightknight.omniread.engine.scraper.ExtensionInstallState
import io.lightknight.omniread.engine.scraper.RemoteBookDetail
import io.lightknight.omniread.engine.scraper.RemoteBookSummary
import io.lightknight.omniread.engine.scraper.RemoteChapter
import io.lightknight.omniread.ui.theme.UmeforLime
import io.lightknight.omniread.ui.theme.UmeforTealDeep
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ExplorePreviewState(
    val plugin: SourcePluginEntity,
    val detail: RemoteBookDetail,
    val chapters: List<RemoteChapter>,
    val fallbackCover: String,
    val chapterWarning: String = ""
)

private data class GlobalSourceHit(
    val plugin: SourcePluginEntity,
    val books: List<RemoteBookSummary>,
    val error: String = ""
)

private data class UninstallPromptState(
    val plugin: SourcePluginEntity,
    val linkedBooksCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    container: OmniAppContainer,
    onOpenPluginManager: () -> Unit,
    onOpenYoutubeImport: () -> Unit,
    onOpenBookDetail: (String) -> Unit,
    onOpenSourceBrowser: (String) -> Unit
) {
    val context = LocalContext.current
    val allPlugins by container.database.sourcePluginDao().observeAll().collectAsState(initial = emptyList())
    val catalogItems by container.extensionCatalogManager.observeCatalog().collectAsState(initial = emptyList())
    val repos by container.database.extensionRepoDao().observeAll().collectAsState(initial = emptyList())

    var activeSubTab by remember { mutableIntStateOf(0) } // 0: Nguồn | 1: Tiện ích | 2: Kho & Công cụ

    // Tab 0 (Nguồn) state
    var sourceGroupFilter by remember { mutableStateOf<MediaType?>(null) }
    var globalSearchMode by remember { mutableStateOf(false) }
    val enabledPlugins = remember(allPlugins, sourceGroupFilter) {
        allPlugins.filter { it.enabled && (sourceGroupFilter == null || it.mediaType == sourceGroupFilter) }
    }
    var selectedPluginId by remember { mutableStateOf("") }
    val activePlugin = remember(enabledPlugins, selectedPluginId) {
        enabledPlugins.firstOrNull { it.id == selectedPluginId } ?: enabledPlugins.firstOrNull()
    }
    var searchQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RemoteBookSummary>>(emptyList()) }
    var globalResults by remember { mutableStateOf<List<GlobalSourceHit>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    // Tab 1 (Tiện ích) state
    var extSearchQuery by remember { mutableStateOf("") }
    var extMediaFilter by remember { mutableStateOf<MediaType?>(null) }
    var extLangFilter by remember { mutableStateOf<String?>(null) }
    var busyExtensionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isRefreshingCatalog by remember { mutableStateOf(false) }
    var isUpdatingAll by remember { mutableStateOf(false) }
    var editingPlugin by remember { mutableStateOf<SourcePluginEntity?>(null) }
    var uninstallPrompt by remember { mutableStateOf<UninstallPromptState?>(null) }

    // Tab 2 (Kho & Công cụ) dialogs
    var showDirectUrlDialog by remember { mutableStateOf(false) }
    var showAddRepoDialog by remember { mutableStateOf(false) }

    var previewState by remember { mutableStateOf<ExplorePreviewState?>(null) }
    var isRetryingChapters by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val updateAvailableCount = remember(catalogItems) {
        catalogItems.count { it.state == ExtensionInstallState.UPDATE_AVAILABLE }
    }

    fun openExternalUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("Không mở được trình duyệt: ${it.message}") }
        }
    }

    suspend fun fetchChaptersWithRetry(plugin: SourcePluginEntity, bookUrl: String): Pair<List<RemoteChapter>, String> {
        var chRes = container.pluginRunner.getChapters(plugin, bookUrl)
        var chapters = chRes.getOrDefault(emptyList())
        if (chRes.isFailure || chapters.isEmpty()) {
            delay(1500L)
            chRes = container.pluginRunner.getChapters(plugin, bookUrl)
            chapters = chRes.getOrDefault(emptyList())
        }
        val warning = when {
            chRes.isFailure -> chRes.exceptionOrNull()?.message ?: "Không tải được danh sách chương."
            chapters.isEmpty() -> "Nguồn trả về 0 chương (có thể đang bị giới hạn tốc độ hoặc cấu trúc trang mới)."
            else -> ""
        }
        return chapters to warning
    }

    fun openBookPreview(plugin: SourcePluginEntity, book: RemoteBookSummary) {
        isLoading = true
        scope.launch {
            val det = container.pluginRunner.getDetail(plugin, book.url)
            if (det.isSuccess) {
                val detailObj = det.getOrThrow()
                val (chapters, warning) = fetchChaptersWithRetry(plugin, detailObj.url)
                isLoading = false
                previewState = ExplorePreviewState(
                    plugin = plugin,
                    detail = detailObj,
                    chapters = chapters,
                    fallbackCover = book.coverUrl,
                    chapterWarning = warning
                )
            } else {
                isLoading = false
                snackbarHostState.showSnackbar("Lỗi đọc chi tiết: ${det.exceptionOrNull()?.message}")
            }
        }
    }

    fun loadSingleSourceCatalog(query: String) {
        val plugin = activePlugin ?: return
        activeJob?.cancel()
        isLoading = true
        errorMsg = ""
        activeJob = scope.launch {
            val res = if (query.isBlank()) {
                container.pluginRunner.popular(plugin, 1)
            } else {
                container.pluginRunner.search(plugin, query, 1)
            }
            isLoading = false
            res.onSuccess { list ->
                results = list
                if (list.isEmpty()) {
                    errorMsg = "Không tìm thấy kết quả nào trên ${plugin.name}."
                }
            }.onFailure { err ->
                results = emptyList()
                errorMsg = err.message ?: "Lỗi tải danh sách từ ${plugin.name}"
            }
        }
    }

    fun runGlobalSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            globalResults = emptyList()
            errorMsg = ""
            return
        }
        val targets = enabledPlugins
        if (targets.isEmpty()) return
        activeJob?.cancel()
        isLoading = true
        errorMsg = ""
        activeJob = scope.launch {
            val hits = coroutineScope {
                targets.map { plugin ->
                    async {
                        val res = container.pluginRunner.search(plugin, trimmed, 1)
                        res.fold(
                            onSuccess = { list -> GlobalSourceHit(plugin = plugin, books = list) },
                            onFailure = { err ->
                                GlobalSourceHit(
                                    plugin = plugin,
                                    books = emptyList(),
                                    error = err.message ?: "Lỗi tìm kiếm"
                                )
                            }
                        )
                    }
                }.awaitAll()
            }
            isLoading = false
            globalResults = hits.filter { it.books.isNotEmpty() || it.error.isNotBlank() }
            if (globalResults.none { it.books.isNotEmpty() }) {
                errorMsg = "Không tìm thấy kết quả cho \"$trimmed\" trên các nguồn đang bật."
            }
        }
    }

    LaunchedEffect(activePlugin?.id, globalSearchMode) {
        if (!globalSearchMode && activePlugin != null && activeSubTab == 0) {
            results = emptyList()
            errorMsg = ""
            loadSingleSourceCatalog(searchQuery)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Khám phá", fontWeight = FontWeight.Bold) },
                actions = {
                    if (activeSubTab == 1) {
                        IconButton(
                            enabled = !isRefreshingCatalog,
                            onClick = {
                                isRefreshingCatalog = true
                                scope.launch {
                                    val summary = container.extensionCatalogManager.refreshAllRepos()
                                    isRefreshingCatalog = false
                                    val msg = if (summary.failedRepos == 0) {
                                        "Đã đồng bộ ${summary.refreshedRepos} kho tiện ích mới nhất"
                                    } else {
                                        "Đồng bộ ${summary.refreshedRepos} kho, lỗi ${summary.failedRepos}"
                                    }
                                    snackbarHostState.showSnackbar(msg)
                                }
                            }
                        ) {
                            if (isRefreshingCatalog) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, "Làm mới kho tiện ích")
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Tachiyomi-style 3 segmented sub-tabs
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val tabs = listOf(
                        Triple(0, "Nguồn (${allPlugins.count { it.enabled }})", 0),
                        Triple(1, "Tiện ích", updateAvailableCount),
                        Triple(2, "Kho & Công cụ", 0)
                    )
                    tabs.forEach { (idx, label, badgeCount) ->
                        val selected = activeSubTab == idx
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) UmeforTealDeep else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { activeSubTab = idx }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) UmeforLime else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (badgeCount > 0) {
                                    Spacer(Modifier.width(4.dp))
                                    Badge(
                                        containerColor = UmeforLime,
                                        contentColor = UmeforTealDeep
                                    ) {
                                        Text(badgeCount.toString(), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            when (activeSubTab) {
                0 -> SourcesBrowserTab(
                    context = context,
                    allPluginsCount = allPlugins.size,
                    enabledPlugins = enabledPlugins,
                    activePlugin = activePlugin,
                    sourceGroupFilter = sourceGroupFilter,
                    onGroupFilterChange = { sourceGroupFilter = it },
                    globalSearchMode = globalSearchMode,
                    onToggleGlobalSearch = {
                        globalSearchMode = it
                        errorMsg = ""
                        if (it && searchQuery.isNotBlank()) runGlobalSearch(searchQuery)
                    },
                    onSelectPlugin = { plugin ->
                        if (selectedPluginId != plugin.id) {
                            activeJob?.cancel()
                            results = emptyList()
                            errorMsg = ""
                            selectedPluginId = plugin.id
                        }
                    },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onSubmitSearch = { q ->
                        if (globalSearchMode) runGlobalSearch(q) else loadSingleSourceCatalog(q)
                    },
                    onClearSearch = {
                        searchQuery = ""
                        if (globalSearchMode) globalResults = emptyList() else loadSingleSourceCatalog("")
                    },
                    isLoading = isLoading,
                    errorMsg = errorMsg,
                    results = results,
                    globalResults = globalResults,
                    onRetry = {
                        if (globalSearchMode) runGlobalSearch(searchQuery) else loadSingleSourceCatalog(searchQuery)
                    },
                    onOpenSourceBrowser = {
                        val url = buildBrowserSearchUrl(activePlugin?.id, activePlugin?.baseUrl, searchQuery)
                        onOpenSourceBrowser(url)
                    },
                    onOpenExtensionStore = { activeSubTab = 1 },
                    onBookClick = { plugin, book -> openBookPreview(plugin, book) }
                )

                1 -> ExtensionsStoreTab(
                    catalogItems = catalogItems,
                    allInstalledPlugins = allPlugins,
                    searchQuery = extSearchQuery,
                    onSearchQueryChange = { extSearchQuery = it },
                    mediaFilter = extMediaFilter,
                    onMediaFilterChange = { extMediaFilter = it },
                    langFilter = extLangFilter,
                    onLangFilterChange = { extLangFilter = it },
                    busyIds = busyExtensionIds,
                    isUpdatingAll = isUpdatingAll,
                    onRefreshCatalog = {
                        isRefreshingCatalog = true
                        scope.launch {
                            val summary = container.extensionCatalogManager.refreshAllRepos()
                            isRefreshingCatalog = false
                            val msg = if (summary.failedRepos == 0) {
                                "Đã cập nhật danh mục từ ${summary.refreshedRepos} kho"
                            } else {
                                "Cập nhật ${summary.refreshedRepos} kho, lỗi ${summary.failedRepos}"
                            }
                            snackbarHostState.showSnackbar(msg)
                        }
                    },
                    onUpdateAll = { updateList ->
                        isUpdatingAll = true
                        scope.launch {
                            val (ok, fail) = container.extensionCatalogManager.updateAll(updateList)
                            isUpdatingAll = false
                            snackbarHostState.showSnackbar("Đã cập nhật $ok tiện ích" + if (fail > 0) " ($fail lỗi)" else "")
                        }
                    },
                    onInstallOrUpdate = { item ->
                        busyExtensionIds = busyExtensionIds + item.entry.id
                        scope.launch {
                            val res = container.extensionCatalogManager.installOrUpdate(item)
                            busyExtensionIds = busyExtensionIds - item.entry.id
                            res.onSuccess {
                                snackbarHostState.showSnackbar("Đã cài ${it.name} (v${it.version})")
                            }.onFailure { err ->
                                snackbarHostState.showSnackbar("Lỗi cài ${item.entry.name}: ${err.message}")
                            }
                        }
                    },
                    onToggleEnabled = { plugin, enabled ->
                        scope.launch {
                            container.database.sourcePluginDao().upsert(
                                plugin.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
                            )
                        }
                    },
                    onEditPlugin = { editingPlugin = it },
                    onRequestUninstall = { plugin ->
                        scope.launch {
                            val count = container.extensionCatalogManager.countLinkedBooks(plugin.id)
                            uninstallPrompt = UninstallPromptState(plugin, count)
                        }
                    },
                    onReportBroken = { nameOrId ->
                        val url = "${ExtensionCatalogManager.OFFICIAL_BROKEN_ISSUE_URL}&extension_id=${
                            URLEncoder.encode(nameOrId, "UTF-8")
                        }"
                        openExternalUrl(url)
                    },
                    onRequestNewSource = {
                        openExternalUrl(ExtensionCatalogManager.OFFICIAL_REQUEST_ISSUE_URL)
                    }
                )

                else -> ReposAndToolsTab(
                    repos = repos,
                    onOpenYoutubeImport = onOpenYoutubeImport,
                    onOpenSourceBrowser = {
                        onOpenSourceBrowser(activePlugin?.baseUrl ?: "https://docln.sbs")
                    },
                    onOpenDirectUrlDialog = { showDirectUrlDialog = true },
                    onOpenPluginManager = onOpenPluginManager,
                    onOpenAddRepo = { showAddRepoDialog = true },
                    onToggleRepo = { repo, enabled ->
                        scope.launch { container.extensionCatalogManager.setRepoEnabled(repo.id, enabled) }
                    },
                    onDeleteRepo = { repo ->
                        scope.launch {
                            container.extensionCatalogManager.removeRepo(repo.id)
                            snackbarHostState.showSnackbar("Đã xóa kho ${repo.name}")
                        }
                    },
                    onRefreshRepos = {
                        isRefreshingCatalog = true
                        scope.launch {
                            val summary = container.extensionCatalogManager.refreshAllRepos()
                            isRefreshingCatalog = false
                            snackbarHostState.showSnackbar(
                                if (summary.failedRepos == 0) "Đã đồng bộ ${summary.refreshedRepos} kho"
                                else "Đồng bộ ${summary.refreshedRepos} kho, lỗi ${summary.failedRepos}"
                            )
                        }
                    },
                    onOpenRequestIssue = {
                        openExternalUrl(ExtensionCatalogManager.OFFICIAL_REQUEST_ISSUE_URL)
                    },
                    onOpenBrokenIssue = {
                        openExternalUrl(ExtensionCatalogManager.OFFICIAL_BROKEN_ISSUE_URL)
                    }
                )
            }
        }
    }

    previewState?.let { state ->
        val (plugin, detail, chapters, fallbackCover, chapterWarning) = state
        AlertDialog(
            onDismissRequest = { previewState = null },
            title = { Text(detail.title, maxLines = 2) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (detail.author.isNotBlank()) {
                        Text("Tác giả: ${detail.author}", fontWeight = FontWeight.SemiBold)
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = UmeforTealDeep.copy(alpha = 0.85f)
                    ) {
                        Text(
                            "${detail.mediaType} · ${chapters.size} chương · ${plugin.name}",
                            color = UmeforLime,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (chapters.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        chapterWarning.ifBlank { "Chưa lấy được danh sách chương." },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                OutlinedButton(
                                    enabled = !isRetryingChapters,
                                    onClick = {
                                        isRetryingChapters = true
                                        scope.launch {
                                            val (retried, warn) = fetchChaptersWithRetry(plugin, detail.url)
                                            isRetryingChapters = false
                                            previewState = state.copy(chapters = retried, chapterWarning = warn)
                                        }
                                    }
                                ) {
                                    if (isRetryingChapters) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (isRetryingChapters) "Đang thử lại..." else "Thử tải lại chương")
                                }
                            }
                        }
                    }
                    if (detail.synopsis.isNotBlank()) {
                        Text(detail.synopsis.take(260), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        previewState = null
                        onOpenSourceBrowser(detail.url)
                    }) {
                        Text("Mở trang gốc tải EPUB/PDF hoặc đăng nhập")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    previewState = null
                    scope.launch {
                        val saved = container.webChapterDownloader.addRemoteBookToLibrary(plugin.id, detail, chapters, fallbackCover)
                        if (chapters.isNotEmpty()) {
                            container.webChapterDownloader.enqueueBatchDownload(saved.id)
                            snackbarHostState.showSnackbar("Đã thêm & bắt đầu tải ${chapters.size} chương")
                        } else {
                            snackbarHostState.showSnackbar("Đã lưu vào thư viện (bấm Cập nhật chương để thử tải lại)")
                        }
                        onOpenBookDetail(saved.id)
                    }
                }) { Text(if (chapters.isNotEmpty()) "Lưu & Tải" else "Lưu") }
            },
            dismissButton = {
                TextButton(onClick = {
                    previewState = null
                    scope.launch {
                        val saved = container.webChapterDownloader.addRemoteBookToLibrary(plugin.id, detail, chapters, fallbackCover)
                        onOpenBookDetail(saved.id)
                    }
                }) { Text("Chỉ thêm thư viện") }
            }
        )
    }

    editingPlugin?.let { target ->
        var baseUrl by remember(target.id) { mutableStateOf(target.baseUrl) }
        var script by remember(target.id) { mutableStateOf(target.scriptContent) }
        AlertDialog(
            onDismissRequest = { editingPlugin = null },
            title = { Text("Cấu hình: ${target.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL (đổi khi web đổi tên miền)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = script,
                        onValueChange = { script = it },
                        label = { Text("Mã Script JS") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        container.database.sourcePluginDao().upsert(
                            target.copy(
                                baseUrl = baseUrl.trim().trimEnd('/'),
                                scriptContent = script,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        editingPlugin = null
                        snackbarHostState.showSnackbar("Đã cập nhật cấu hình ${target.name}")
                    }
                }) { Text("Lưu") }
            },
            dismissButton = {
                TextButton(onClick = { editingPlugin = null }) { Text("Đóng") }
            }
        )
    }

    uninstallPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { uninstallPrompt = null },
            title = { Text("Gỡ tiện ích ${prompt.plugin.name}?") },
            text = {
                val msg = if (prompt.linkedBooksCount > 0) {
                    "Hiện có ${prompt.linkedBooksCount} truyện trong thư viện thuộc nguồn này. Các chương đã tải offline vẫn đọc bình thường, nhưng sẽ không tải chương mới từ web cho đến khi Ngài cài lại tiện ích này."
                } else {
                    "Ngài có chắc muốn gỡ ${prompt.plugin.name}? Có thể cài lại bất kỳ lúc nào từ Kho Tiện ích."
                }
                Text(msg, style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Button(onClick = {
                    val target = prompt.plugin
                    uninstallPrompt = null
                    scope.launch {
                        container.extensionCatalogManager.uninstall(target.id)
                        snackbarHostState.showSnackbar("Đã gỡ ${target.name}")
                    }
                }) { Text("Gỡ bỏ") }
            },
            dismissButton = {
                TextButton(onClick = { uninstallPrompt = null }) { Text("Hủy") }
            }
        )
    }

    if (showDirectUrlDialog) {
        var urlInput by remember { mutableStateOf("") }
        var maxFollow by remember { mutableStateOf("5") }
        AlertDialog(
            onDismissRequest = { showDirectUrlDialog = false },
            title = { Text("Dán link truyện web bất kỳ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("URL trang chương truyện") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = maxFollow,
                        onValueChange = { maxFollow = it },
                        label = { Text("Số chương tự theo link 'Chương sau' tối đa") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (urlInput.isNotBlank()) {
                        val target = urlInput.trim()
                        val count = maxFollow.toIntOrNull()?.coerceIn(1, 50) ?: 1
                        showDirectUrlDialog = false
                        isLoading = true
                        scope.launch {
                            val res = container.directUrlImporter.importFromUrl(target, count)
                            isLoading = false
                            res.onSuccess {
                                snackbarHostState.showSnackbar("Đã nhập từ web: ${it.title}")
                                onOpenBookDetail(it.id)
                            }.onFailure {
                                snackbarHostState.showSnackbar("Lỗi nhập URL: ${it.message}")
                            }
                        }
                    }
                }) { Text("Tải về thư viện") }
            },
            dismissButton = { TextButton(onClick = { showDirectUrlDialog = false }) { Text("Hủy") } }
        )
    }

    if (showAddRepoDialog) {
        var repoName by remember { mutableStateOf("") }
        var repoUrl by remember { mutableStateOf("https://") }
        var isAdding by remember { mutableStateOf(false) }
        var addErr by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!isAdding) showAddRepoDialog = false },
            title = { Text("Thêm Kho Extension (Repo)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Dán đường dẫn file index.json chuẩn Umefor Extension Repository.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = repoName,
                        onValueChange = { repoName = it },
                        label = { Text("Tên kho (tùy chọn)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = repoUrl,
                        onValueChange = { repoUrl = it; addErr = "" },
                        label = { Text("URL index.json") },
                        placeholder = { Text("https://raw.githubusercontent.com/.../index.json") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (addErr.isNotBlank()) {
                        Text(addErr, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isAdding && repoUrl.isNotBlank(),
                    onClick = {
                        isAdding = true
                        addErr = ""
                        scope.launch {
                            val res = container.extensionCatalogManager.addCustomRepo(repoName, repoUrl)
                            isAdding = false
                            res.onSuccess { repo ->
                                showAddRepoDialog = false
                                snackbarHostState.showSnackbar("Đã thêm kho ${repo.name}")
                            }.onFailure { err ->
                                addErr = err.message ?: "Không thêm được kho repo"
                            }
                        }
                    }
                ) {
                    if (isAdding) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (isAdding) "Đang kiểm tra..." else "Thêm kho")
                }
            },
            dismissButton = {
                TextButton(enabled = !isAdding, onClick = { showAddRepoDialog = false }) { Text("Hủy") }
            }
        )
    }
}

@Composable
private fun SourcesBrowserTab(
    context: android.content.Context,
    allPluginsCount: Int,
    enabledPlugins: List<SourcePluginEntity>,
    activePlugin: SourcePluginEntity?,
    sourceGroupFilter: MediaType?,
    onGroupFilterChange: (MediaType?) -> Unit,
    globalSearchMode: Boolean,
    onToggleGlobalSearch: (Boolean) -> Unit,
    onSelectPlugin: (SourcePluginEntity) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    isLoading: Boolean,
    errorMsg: String,
    results: List<RemoteBookSummary>,
    globalResults: List<GlobalSourceHit>,
    onRetry: () -> Unit,
    onOpenSourceBrowser: () -> Unit,
    onOpenExtensionStore: () -> Unit,
    onBookClick: (SourcePluginEntity, RemoteBookSummary) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (enabledPlugins.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        if (allPluginsCount == 0) "Chưa cài đặt tiện ích nguồn nào"
                        else "Không có nguồn nào đang bật",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Umefor Library để trống mặc định giống Tachiyomi. Hãy mở Kho Tiện ích để chọn cài nguồn truyện chữ, truyện tranh hoặc ebook Ngài muốn dùng.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onOpenExtensionStore,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Mở Kho Tiện ích")
                    }
                }
            }
            return
        }

        // Filter chips + Global Search toggle
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = globalSearchMode,
                    onClick = { onToggleGlobalSearch(!globalSearchMode) },
                    label = { Text("Tìm toàn cục") },
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) }
                )
            }
            item {
                FilterChip(
                    selected = sourceGroupFilter == null,
                    onClick = { onGroupFilterChange(null) },
                    label = { Text("Tất cả") }
                )
            }
            item {
                FilterChip(
                    selected = sourceGroupFilter == MediaType.NOVEL,
                    onClick = { onGroupFilterChange(MediaType.NOVEL) },
                    label = { Text("Truyện chữ") }
                )
            }
            item {
                FilterChip(
                    selected = sourceGroupFilter == MediaType.COMIC,
                    onClick = { onGroupFilterChange(MediaType.COMIC) },
                    label = { Text("Truyện tranh") }
                )
            }
        }

        if (!globalSearchMode) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(enabledPlugins, key = { it.id }) { plugin ->
                    FilterChip(
                        selected = activePlugin?.id == plugin.id,
                        onClick = { onSelectPlugin(plugin) },
                        label = { Text(plugin.name) }
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    unfocusedBorderColor = Color.Transparent
                ),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = onClearSearch) {
                            Icon(Icons.Default.Close, contentDescription = "Xóa")
                        }
                    }
                },
                placeholder = {
                    Text(
                        if (globalSearchMode) "Tìm trên ${enabledPlugins.size} nguồn đang bật..."
                        else "Tìm truyện trên ${activePlugin?.name ?: "web"}..."
                    )
                }
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSubmitSearch(searchQuery) },
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.Search, null)
            }
        }

        Spacer(Modifier.height(10.dp))
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (errorMsg.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(errorMsg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRetry) { Text("Thử lại") }
                        if (!globalSearchMode) {
                            Button(onClick = onOpenSourceBrowser) { Text("Mở Trình duyệt nguồn") }
                        }
                    }
                }
            }
        } else if (globalSearchMode) {
            if (globalResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nhập từ khóa rồi bấm Kính lúp để tìm đồng thời trên mọi nguồn đã cài.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(globalResults, key = { it.plugin.id }) { hit ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    hit.plugin.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (hit.books.isNotEmpty()) "${hit.books.size} kết quả" else "Lỗi",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (hit.books.isEmpty()) {
                                Text(
                                    hit.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(hit.books, key = { "${hit.plugin.id}:${it.url}" }) { book ->
                                        Box(modifier = Modifier.width(120.dp)) {
                                            RemoteBookCard(
                                                context = context,
                                                book = book,
                                                referer = hit.plugin.baseUrl.trimEnd('/') + "/",
                                                onClick = { onBookClick(hit.plugin, book) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val plugin = activePlugin
            if (plugin != null) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(results) { book ->
                        RemoteBookCard(
                            context = context,
                            book = book,
                            referer = plugin.baseUrl.trimEnd('/') + "/",
                            onClick = { onBookClick(plugin, book) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteBookCard(
    context: android.content.Context,
    book: RemoteBookSummary,
    referer: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (book.coverUrl.isNotBlank()) {
                val imgReq = remember(book.coverUrl, referer) {
                    val headers = NetworkHeaders.Builder().apply {
                        if (referer.isNotBlank()) add("Referer", referer)
                    }.build()
                    ImageRequest.Builder(context)
                        .data(book.coverUrl)
                        .httpHeaders(headers)
                        .build()
                }
                AsyncImage(
                    model = imgReq,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.Book,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.58f))
                        )
                    )
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ExtensionsStoreTab(
    catalogItems: List<CatalogExtensionItem>,
    allInstalledPlugins: List<SourcePluginEntity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    mediaFilter: MediaType?,
    onMediaFilterChange: (MediaType?) -> Unit,
    langFilter: String?,
    onLangFilterChange: (String?) -> Unit,
    busyIds: Set<String>,
    isUpdatingAll: Boolean,
    onRefreshCatalog: () -> Unit,
    onUpdateAll: (List<CatalogExtensionItem>) -> Unit,
    onInstallOrUpdate: (CatalogExtensionItem) -> Unit,
    onToggleEnabled: (SourcePluginEntity, Boolean) -> Unit,
    onEditPlugin: (SourcePluginEntity) -> Unit,
    onRequestUninstall: (SourcePluginEntity) -> Unit,
    onReportBroken: (String) -> Unit,
    onRequestNewSource: () -> Unit
) {
    val catalogIds = remember(catalogItems) { catalogItems.map { it.entry.id }.toSet() }
    val customInstalled = remember(allInstalledPlugins, catalogIds, searchQuery, mediaFilter, langFilter) {
        allInstalledPlugins.filter { plugin ->
            plugin.id !in catalogIds &&
                (mediaFilter == null || plugin.mediaType == mediaFilter) &&
                (langFilter == null || plugin.lang.equals(langFilter, ignoreCase = true)) &&
                (searchQuery.isBlank() ||
                    plugin.name.contains(searchQuery, ignoreCase = true) ||
                    plugin.baseUrl.contains(searchQuery, ignoreCase = true))
        }
    }

    val filteredCatalog = remember(catalogItems, searchQuery, mediaFilter, langFilter) {
        catalogItems.filter { item ->
            val e = item.entry
            (mediaFilter == null || e.mediaTypeEnum() == mediaFilter) &&
                (langFilter == null || e.lang.equals(langFilter, ignoreCase = true)) &&
                (searchQuery.isBlank() ||
                    e.name.contains(searchQuery, ignoreCase = true) ||
                    e.baseUrl.contains(searchQuery, ignoreCase = true) ||
                    e.description.contains(searchQuery, ignoreCase = true))
        }
    }

    val updates = remember(filteredCatalog) {
        filteredCatalog.filter { it.state == ExtensionInstallState.UPDATE_AVAILABLE }
    }
    val installedFromCatalog = remember(filteredCatalog) {
        filteredCatalog.filter { it.state == ExtensionInstallState.INSTALLED }
    }
    val available = remember(filteredCatalog) {
        filteredCatalog.filter {
            it.state == ExtensionInstallState.AVAILABLE || it.state == ExtensionInstallState.INCOMPATIBLE
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Close, "Xóa")
                    }
                }
            },
            placeholder = { Text("Tìm tiện ích trong kho (tên, tên miền)...") }
        )

        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(selected = mediaFilter == null, onClick = { onMediaFilterChange(null) }, label = { Text("Tất cả") })
            }
            item {
                FilterChip(selected = mediaFilter == MediaType.NOVEL, onClick = { onMediaFilterChange(MediaType.NOVEL) }, label = { Text("Chữ") })
            }
            item {
                FilterChip(selected = mediaFilter == MediaType.COMIC, onClick = { onMediaFilterChange(MediaType.COMIC) }, label = { Text("Tranh") })
            }
            item {
                FilterChip(
                    selected = langFilter == "vi",
                    onClick = { onLangFilterChange(if (langFilter == "vi") null else "vi") },
                    label = { Text("VI") }
                )
            }
            item {
                FilterChip(
                    selected = langFilter == "en",
                    onClick = { onLangFilterChange(if (langFilter == "en") null else "en") },
                    label = { Text("EN") }
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (updates.isNotEmpty()) {
                Button(
                    enabled = !isUpdatingAll,
                    onClick = { onUpdateAll(updates) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.SystemUpdate, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isUpdatingAll) "Đang cập nhật..." else "Cập nhật tất cả (${updates.size})")
                }
            } else {
                OutlinedButton(
                    onClick = onRefreshCatalog,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Kiểm tra cập nhật")
                }
            }
            OutlinedButton(
                onClick = onRequestNewSource,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Feedback, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Đề xuất nguồn")
            }
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(
            contentPadding = PaddingValues(bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (updates.isNotEmpty()) {
                item {
                    ExtensionSectionHeader("Có bản cập nhật (${updates.size})")
                }
                items(updates, key = { "upd_${it.entry.id}" }) { item ->
                    CatalogExtensionRow(
                        item = item,
                        isBusy = item.entry.id in busyIds || isUpdatingAll,
                        onInstallOrUpdate = { onInstallOrUpdate(item) },
                        onToggleEnabled = {},
                        onEdit = { item.installedPlugin?.let(onEditPlugin) },
                        onUninstall = { item.installedPlugin?.let(onRequestUninstall) },
                        onReportBroken = { onReportBroken(item.entry.id) }
                    )
                }
            }

            if (installedFromCatalog.isNotEmpty() || customInstalled.isNotEmpty()) {
                item {
                    ExtensionSectionHeader("Đã cài đặt (${installedFromCatalog.size + customInstalled.size})")
                }
                items(installedFromCatalog, key = { "inst_${it.entry.id}" }) { item ->
                    CatalogExtensionRow(
                        item = item,
                        isBusy = item.entry.id in busyIds,
                        onInstallOrUpdate = { onInstallOrUpdate(item) },
                        onToggleEnabled = { enabled ->
                            item.installedPlugin?.let { onToggleEnabled(it, enabled) }
                        },
                        onEdit = { item.installedPlugin?.let(onEditPlugin) },
                        onUninstall = { item.installedPlugin?.let(onRequestUninstall) },
                        onReportBroken = { onReportBroken(item.entry.id) }
                    )
                }
                items(customInstalled, key = { "custom_${it.id}" }) { plugin ->
                    CustomPluginRow(
                        plugin = plugin,
                        onToggleEnabled = { onToggleEnabled(plugin, it) },
                        onEdit = { onEditPlugin(plugin) },
                        onUninstall = { onRequestUninstall(plugin) }
                    )
                }
            }

            if (available.isNotEmpty()) {
                item {
                    ExtensionSectionHeader("Khả dụng trong kho (${available.size})")
                }
                items(available, key = { "avail_${it.entry.id}" }) { item ->
                    CatalogExtensionRow(
                        item = item,
                        isBusy = item.entry.id in busyIds,
                        onInstallOrUpdate = { onInstallOrUpdate(item) },
                        onToggleEnabled = {},
                        onEdit = {},
                        onUninstall = {},
                        onReportBroken = { onReportBroken(item.entry.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun CatalogExtensionRow(
    item: CatalogExtensionItem,
    isBusy: Boolean,
    onInstallOrUpdate: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onUninstall: () -> Unit,
    onReportBroken: () -> Unit
) {
    val entry = item.entry
    val installed = item.installedPlugin
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val versionLabel = when (item.state) {
                        ExtensionInstallState.UPDATE_AVAILABLE -> "v${installed?.version ?: 0} → v${entry.version}"
                        else -> "v${installed?.version ?: entry.version}"
                    }
                    Text(
                        "$versionLabel · ${entry.mediaType} · ${entry.lang.uppercase()} · ${item.repoName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        installed?.baseUrl ?: entry.baseUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                when (item.state) {
                    ExtensionInstallState.AVAILABLE -> {
                        Button(
                            enabled = !isBusy,
                            onClick = onInstallOrUpdate,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Cài đặt")
                            }
                        }
                    }
                    ExtensionInstallState.UPDATE_AVAILABLE -> {
                        Button(
                            enabled = !isBusy,
                            onClick = onInstallOrUpdate,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Cập nhật")
                            }
                        }
                    }
                    ExtensionInstallState.INSTALLED -> {
                        Switch(
                            checked = installed?.enabled == true,
                            onCheckedChange = onToggleEnabled
                        )
                    }
                    ExtensionInstallState.INCOMPATIBLE -> {
                        Text(
                            "Cần app v${entry.minAppApi}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            val note = if (item.state == ExtensionInstallState.UPDATE_AVAILABLE && entry.changelog.isNotBlank()) {
                "Cập nhật: ${entry.changelog}"
            } else {
                entry.description
            }
            if (note.isNotBlank()) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (installed != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tên miền / JS")
                    }
                    TextButton(onClick = onReportBroken) {
                        Icon(Icons.Default.BugReport, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Báo lỗi")
                    }
                    TextButton(onClick = onUninstall) {
                        Icon(Icons.Default.DeleteOutline, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("Gỡ", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomPluginRow(
    plugin: SourcePluginEntity,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onUninstall: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${plugin.name} (Tùy chỉnh)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("v${plugin.version} · ${plugin.mediaType.name} · ${plugin.baseUrl}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = plugin.enabled, onCheckedChange = onToggleEnabled)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sửa JS")
                }
                TextButton(onClick = onUninstall) {
                    Icon(Icons.Default.DeleteOutline, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Gỡ", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ReposAndToolsTab(
    repos: List<ExtensionRepoEntity>,
    onOpenYoutubeImport: () -> Unit,
    onOpenSourceBrowser: () -> Unit,
    onOpenDirectUrlDialog: () -> Unit,
    onOpenPluginManager: () -> Unit,
    onOpenAddRepo: () -> Unit,
    onToggleRepo: (ExtensionRepoEntity, Boolean) -> Unit,
    onDeleteRepo: (ExtensionRepoEntity) -> Unit,
    onRefreshRepos: () -> Unit,
    onOpenRequestIssue: () -> Unit,
    onOpenBrokenIssue: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Công cụ nhập nội dung", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BentoToolTile(
                        title = "YouTube Sách nói",
                        subtitle = "Tải audio-only",
                        icon = Icons.Default.VideoLibrary,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenYoutubeImport
                    )
                    BentoToolTile(
                        title = "Trình duyệt nguồn",
                        subtitle = "Tải EPUB / PDF / CBZ",
                        icon = Icons.Default.Public,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenSourceBrowser
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BentoToolTile(
                        title = "Dán URL truyện",
                        subtitle = "Trích text / ảnh",
                        icon = Icons.Default.Link,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenDirectUrlDialog
                    )
                    BentoToolTile(
                        title = "Sửa JS thủ công",
                        subtitle = "Tạo / sửa script cục bộ",
                        icon = Icons.Default.Extension,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenPluginManager
                    )
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = UmeforTealDeep,
                border = BorderStroke(1.dp, UmeforLime.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Cộng đồng Umefor Library",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = UmeforLime
                    )
                    Text(
                        "Thiếu trang web Ngài hay đọc hoặc nguồn cũ bị đổi tên miền? Gửi đề xuất trực tiếp lên GitHub để đội ngũ bổ sung và phát hành qua live update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFDCEEE5)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenRequestIssue, shape = RoundedCornerShape(12.dp)) {
                            Text("Đề xuất nguồn mới")
                        }
                        OutlinedButton(onClick = onOpenBrokenIssue, shape = RoundedCornerShape(12.dp)) {
                            Text("Báo nguồn lỗi", color = Color.White)
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
                Text("Kho Extension (Repositories)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row {
                    IconButton(onClick = onRefreshRepos) {
                        Icon(Icons.Default.Refresh, "Đồng bộ lại các kho")
                    }
                    TextButton(onClick = onOpenAddRepo) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Thêm Repo")
                    }
                }
            }
        }

        items(repos, key = { it.id }) { repo ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(repo.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                if (repo.isOfficial) {
                                    Surface(shape = RoundedCornerShape(6.dp), color = UmeforTealDeep) {
                                        Text(
                                            "Chính thức",
                                            color = UmeforLime,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                repo.indexUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val syncText = if (repo.lastSyncedAt > 0L) {
                                "Đồng bộ: ${dateFormatter.format(Date(repo.lastSyncedAt))}"
                            } else {
                                "Đang dùng danh mục offline đính kèm"
                            }
                            Text(syncText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            if (repo.lastError.isNotBlank()) {
                                Text(repo.lastError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Switch(
                            checked = repo.enabled,
                            onCheckedChange = { onToggleRepo(repo, it) }
                        )
                        if (!repo.isOfficial) {
                            IconButton(onClick = { onDeleteRepo(repo) }) {
                                Icon(Icons.Default.DeleteOutline, "Xóa kho", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = Color.Transparent)
        }
    }
}

@Composable
private fun BentoToolTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun buildBrowserSearchUrl(pluginId: String?, baseUrl: String?, query: String): String {
    val base = baseUrl?.trimEnd('/') ?: "https://docln.sbs"
    if (query.isBlank()) return base
    val q = URLEncoder.encode(query.trim(), "UTF-8")
    return when (pluginId) {
        "dtv_ebook" -> "$base/?s=$q"
        "downloadsachmienphi" -> "$base/?s=$q"
        "sachvuii" -> "$base/?s=$q"
        "docln_hako" -> "$base/tim-kiem?keywords=$q"
        else -> base
    }
}
