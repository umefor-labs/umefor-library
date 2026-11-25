package io.lightknight.omniread.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.local.ReadingProgressEntity
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.ui.theme.UmeforLime
import io.lightknight.omniread.ui.theme.UmeforTealDeep
import io.lightknight.omniread.ui.theme.UmeforTealRich
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: OmniAppContainer,
    onOpenBook: (LibraryItemEntity) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenDirectUrl: () -> Unit,
    onOpenYoutubeImport: () -> Unit,
    onOpenStats: () -> Unit = {}
) {
    val allItems by container.libraryRepository.observeAllItems().collectAsState(initial = emptyList())
    val allProgress by container.libraryRepository.observeAllProgress().collectAsState(initial = emptyList())
    val progressMap = remember(allProgress) { allProgress.associateBy { it.itemId } }

    var selectedFilter by remember { mutableStateOf<MediaType?>(null) }
    var selectedShelfTag by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var isGridView by remember { mutableStateOf(true) }
    var sortAlphabetical by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }
    var pendingPdfUri by remember { mutableStateOf<Uri?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val repairedCovers = remember { mutableSetOf<String>() }

    LaunchedEffect(allItems) {
        allItems.filter { item ->
            item.originType == OriginType.WEB_PLUGIN &&
                item.id !in repairedCovers &&
                (item.coverLocalPath.isNullOrBlank() || !File(item.coverLocalPath).exists())
        }.forEach { item ->
            repairedCovers += item.id
            launch { container.webChapterDownloader.repairMissingCover(item.id) }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (container.localImportManager.isPdfFile(uri)) {
            pendingPdfUri = uri
        } else {
            isImporting = true
            scope.launch {
                val res = container.localImportManager.importFileUri(uri)
                isImporting = false
                res.onSuccess {
                    snackbarHostState.showSnackbar("Đã nhập: ${it.item.title} (${it.chapters.size} chương)")
                }.onFailure { err ->
                    snackbarHostState.showSnackbar("Lỗi nhập: ${err.message}")
                }
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImporting = true
        scope.launch {
            val res = container.localImportManager.importFolderUri(uri)
            isImporting = false
            res.onSuccess {
                snackbarHostState.showSnackbar("Đã nhập thư mục: ${it.item.title} (${it.chapters.size} phần)")
            }.onFailure { err ->
                snackbarHostState.showSnackbar("Lỗi nhập thư mục: ${err.message}")
            }
        }
    }

    val shelfTags = remember(allItems) {
        allItems.map { it.shelfTag.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    }

    val filteredItems = remember(allItems, selectedFilter, selectedShelfTag, searchQuery, sortAlphabetical) {
        val list = allItems.filter { item ->
            val matchType = selectedFilter == null || item.mediaType == selectedFilter
            val matchShelf = selectedShelfTag == null || item.shelfTag.equals(selectedShelfTag, ignoreCase = true)
            val matchQuery = searchQuery.isBlank() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                item.author.contains(searchQuery, ignoreCase = true)
            matchType && matchShelf && matchQuery
        }
        if (sortAlphabetical) list.sortedBy { it.title.lowercase() } else list
    }

    val recentItems = remember(allItems, progressMap) {
        allItems
            .filter { it.lastOpenedAt > 0L || progressMap.containsKey(it.id) }
            .sortedByDescending { maxOf(it.lastOpenedAt, progressMap[it.id]?.updatedAt ?: 0L) }
            .take(5)
    }

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val isScrolledDown by remember(isGridView) {
        derivedStateOf {
            if (isGridView) {
                gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 64
            } else {
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 64
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showImportSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Thêm", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = padding.calculateBottomPadding())
                .padding(horizontal = 16.dp)
        ) {
            AnimatedVisibility(visible = !isScrolledDown) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = UmeforTealDeep,
                        border = BorderStroke(1.5.dp, UmeforLime),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = UmeforLime,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Umefor Library",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${allItems.size} đầu sách · Chữ · Tranh · Audio",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onOpenStats, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.BarChart, "Thống kê đọc")
                    }
                    IconButton(
                        onClick = {
                            showSearchBar = !showSearchBar
                            if (!showSearchBar) searchQuery = ""
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(if (showSearchBar) Icons.Default.Close else Icons.Default.Search, "Tìm kiếm")
                    }
                    IconButton(onClick = { isGridView = !isGridView }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            "Đổi bố cục"
                        )
                    }
                }
            }

            AnimatedVisibility(visible = showSearchBar || searchQuery.isNotEmpty() || isScrolledDown) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            unfocusedBorderColor = Color.Transparent
                        ),
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, "Xóa")
                                }
                            }
                        },
                        placeholder = {
                            Text(
                                "Tìm tên sách, tác giả...",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    if (isScrolledDown) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { isGridView = !isGridView }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                "Đổi bố cục"
                            )
                        }
                    }
                }
            }

            if (isImporting) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedFilter == null,
                        onClick = { selectedFilter = null },
                        label = { Text("Tất cả (${allItems.size})") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == MediaType.NOVEL,
                        onClick = { selectedFilter = MediaType.NOVEL },
                        label = { Text("Truyện chữ") },
                        leadingIcon = { Icon(Icons.Default.Book, null, Modifier.size(16.dp)) }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == MediaType.COMIC,
                        onClick = { selectedFilter = MediaType.COMIC },
                        label = { Text("Truyện tranh") },
                        leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(16.dp)) }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == MediaType.AUDIOBOOK,
                        onClick = { selectedFilter = MediaType.AUDIOBOOK },
                        label = { Text("Sách nói") },
                        leadingIcon = { Icon(Icons.Default.Headphones, null, Modifier.size(16.dp)) }
                    )
                }
                items(shelfTags) { tag ->
                    FilterChip(
                        selected = selectedShelfTag == tag,
                        onClick = { selectedShelfTag = if (selectedShelfTag == tag) null else tag },
                        label = { Text(tag) },
                        leadingIcon = { Icon(Icons.Default.Label, null, Modifier.size(15.dp)) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            if (filteredItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Chưa có nội dung nào", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Bấm nút + Thêm để nhập EPUB/TXT/PDF/CBZ/CBR/Audio,\nhoặc tải từ Web & YouTube Playlist.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (isGridView) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 84.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (recentItems.isNotEmpty() && searchQuery.isBlank() && selectedFilter == null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Đọc / Nghe gần đây",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(recentItems, key = { "recent_${it.id}" }) { item ->
                                        SpotlightContinueCard(
                                            item = item,
                                            progress = progressMap[item.id],
                                            onContinue = { onOpenBook(item) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Kệ sách (${filteredItems.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = { sortAlphabetical = !sortAlphabetical },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.SwapVert, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (sortAlphabetical) "Tên A-Z" else "Mới nhất", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    items(filteredItems, key = { it.id }) { item ->
                        BookGridCard(
                            item = item,
                            progress = progressMap[item.id],
                            onClick = { onOpenDetail(item.id) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 84.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (recentItems.isNotEmpty() && searchQuery.isBlank() && selectedFilter == null) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Đọc / Nghe gần đây",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(recentItems, key = { "recent_list_${it.id}" }) { item ->
                                        SpotlightContinueCard(
                                            item = item,
                                            progress = progressMap[item.id],
                                            onContinue = { onOpenBook(item) }
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
                            Text(
                                "Kệ sách (${filteredItems.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = { sortAlphabetical = !sortAlphabetical },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.SwapVert, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (sortAlphabetical) "Tên A-Z" else "Mới nhất", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    items(filteredItems, key = { it.id }) { item ->
                        BookListCard(
                            item = item,
                            progress = progressMap[item.id],
                            onClick = { onOpenDetail(item.id) }
                        )
                    }
                }
            }
        }
    }

    if (showImportSheet) {
        ModalBottomSheet(onDismissRequest = { showImportSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    "Thêm vào Umefor Library",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                ListItem(
                    headlineContent = { Text("Nhập tệp từ máy") },
                    supportingContent = { Text("EPUB, TXT, PDF, HTML, MD, CBZ, CBR, MP3, M4A, M4B, OPUS") },
                    leadingContent = { Icon(Icons.Default.AudioFile, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showImportSheet = false
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                )
                ListItem(
                    headlineContent = { Text("Nhập thư mục ảnh hoặc thư mục audio") },
                    supportingContent = { Text("Tự nhận diện chương ảnh truyện tranh hoặc nhiều file sách nói") },
                    leadingContent = { Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showImportSheet = false
                        folderPickerLauncher.launch(null)
                    }
                )
                ListItem(
                    headlineContent = { Text("Dán link web truyện trực tiếp") },
                    supportingContent = { Text("Tự trích nội dung truyện chữ hoặc ảnh truyện tranh từ URL") },
                    leadingContent = { Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showImportSheet = false
                        onOpenDirectUrl()
                    }
                )
                ListItem(
                    headlineContent = { Text("Tải sách nói từ YouTube Playlist") },
                    supportingContent = { Text("Tải trực tiếp luồng âm thanh hoặc tách YouTube Chapters") },
                    leadingContent = { Icon(Icons.Default.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showImportSheet = false
                        onOpenYoutubeImport()
                    }
                )
            }
        }
    }

    pendingPdfUri?.let { pdfUri ->
        AlertDialog(
            onDismissRequest = { pendingPdfUri = null },
            title = { Text("Chọn chế độ nhập PDF") },
            text = { Text("Tệp PDF này là sách chữ (để đọc/nghe TTS) hay truyện tranh scan?") },
            confirmButton = {
                TextButton(onClick = {
                    val target = pdfUri
                    pendingPdfUri = null
                    isImporting = true
                    scope.launch {
                        val res = container.localImportManager.importFileUri(target, MediaType.NOVEL)
                        isImporting = false
                        res.onSuccess {
                            snackbarHostState.showSnackbar("Đã nhập PDF truyện chữ: ${it.item.title}")
                        }.onFailure {
                            snackbarHostState.showSnackbar("Lỗi nhập PDF: ${it.message}")
                        }
                    }
                }) { Text("Truyện chữ (Trích text)") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val target = pdfUri
                    pendingPdfUri = null
                    isImporting = true
                    scope.launch {
                        val res = container.localImportManager.importFileUri(target, MediaType.COMIC)
                        isImporting = false
                        res.onSuccess {
                            snackbarHostState.showSnackbar("Đã nhập PDF truyện tranh: ${it.item.title}")
                        }.onFailure {
                            snackbarHostState.showSnackbar("Lỗi nhập PDF: ${it.message}")
                        }
                    }
                }) { Text("Truyện tranh (Render trang)") }
            }
        )
    }
}

@Composable
private fun SpotlightContinueCard(
    item: LibraryItemEntity,
    progress: ReadingProgressEntity?,
    onContinue: () -> Unit
) {
    val pctFloat = (progress?.progressPercent ?: 0f).coerceIn(0f, 1f)
    val pct = (pctFloat * 100).toInt()
    Box(
        modifier = Modifier
            .width(276.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(UmeforTealDeep, UmeforTealRich)))
            .border(1.dp, UmeforLime.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
            .clickable(onClick = onContinue)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookCoverBox(item = item, modifier = Modifier.size(width = 50.dp, height = 72.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Ch.${(progress?.currentChapterIndex ?: 0) + 1} · $pct%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD5E6DD)
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { pctFloat },
                    color = UmeforLime,
                    trackColor = Color.White.copy(alpha = 0.16f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, null, tint = UmeforLime, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (item.mediaType == MediaType.AUDIOBOOK) "Nghe tiếp" else "Đọc tiếp",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = UmeforLime
                    )
                }
            }
        }
    }
}

@Composable
private fun BookGridCard(
    item: LibraryItemEntity,
    progress: ReadingProgressEntity?,
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
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
        ) {
            BookCoverBox(item = item, modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.68f))))
            )
            val badgeLabel = when (item.mediaType) {
                MediaType.NOVEL -> "Chữ"
                MediaType.COMIC -> "Tranh"
                MediaType.AUDIOBOOK -> "Audio"
            }
            Text(
                text = badgeLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = UmeforLime,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(UmeforTealDeep.copy(alpha = 0.90f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
            if (item.unreadNewChapters > 0) {
                Text(
                    text = "+${item.unreadNewChapters} mới",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = UmeforTealDeep,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(UmeforLime)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            } else if (!item.isCachedLocally) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = UmeforTealDeep.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.CloudDone,
                        contentDescription = "Trên Cloud",
                        tint = UmeforLime,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(14.dp)
                    )
                }
            }
            val pct = (progress?.progressPercent ?: 0f).coerceIn(0f, 1f)
            if (pct > 0f) {
                LinearProgressIndicator(
                    progress = { pct },
                    color = UmeforLime,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.author.isNotBlank()) {
            Text(
                item.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BookListCard(
    item: LibraryItemEntity,
    progress: ReadingProgressEntity?,
    onClick: () -> Unit
) {
    val pctFloat = (progress?.progressPercent ?: 0f).coerceIn(0f, 1f)
    val pct = (pctFloat * 100).toInt()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCoverBox(item = item, modifier = Modifier.size(width = 62.dp, height = 88.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.author.isNotBlank()) {
                    Text(
                        item.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val badgeLabel = when (item.mediaType) {
                        MediaType.NOVEL -> "Truyện chữ"
                        MediaType.COMIC -> "Truyện tranh"
                        MediaType.AUDIOBOOK -> "Sách nói"
                    }
                    Text(
                        badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (pct > 0) {
                        Text("· $pct%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (pct > 0) {
                    LinearProgressIndicator(
                        progress = { pctFloat },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.5.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun BookCoverBox(item: LibraryItemEntity, modifier: Modifier = Modifier) {
    val coverFile = item.coverLocalPath?.let { File(it) }?.takeIf { it.exists() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (coverFile != null) {
            AsyncImage(
                model = coverFile,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val icon = when (item.mediaType) {
                MediaType.NOVEL -> Icons.Default.Book
                MediaType.COMIC -> Icons.Default.Image
                MediaType.AUDIOBOOK -> Icons.Default.Headphones
            }
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}
