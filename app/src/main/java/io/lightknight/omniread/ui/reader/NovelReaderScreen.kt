package io.lightknight.omniread.ui.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lightknight.omniread.core.AppSettings
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.core.VolumeKeyBus
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.model.AppThemeMode
import io.lightknight.omniread.data.model.NovelReadMode
import io.lightknight.omniread.data.model.PageTurnEffect
import io.lightknight.omniread.data.model.TtsMode
import io.lightknight.omniread.engine.importer.MegaPublicDownloader
import io.lightknight.omniread.ui.theme.UmeforLime
import io.lightknight.omniread.ui.theme.UmeforTealDeep
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NovelReaderScreen(
    itemId: String,
    initialChapterIndex: Int,
    container: OmniAppContainer,
    onBack: () -> Unit,
    onOpenVoiceCast: (String) -> Unit,
    onOpenPronunciation: () -> Unit = {},
    onEnsureChapterReady: suspend (ChapterEntity) -> File? = { ch -> ch.localFilePath?.let(::File)?.takeIf { it.exists() } },
    onForceReloadChapter: suspend (ChapterEntity) -> File? = onEnsureChapterReady,
    ttsActiveParagraph: Int = -1,
    ttsIsPlaying: Boolean = false,
    ttsIsActive: Boolean = ttsIsPlaying,
    ttsStatusText: String = "",
    onToggleTts: (ChapterEntity, List<String>, TtsMode) -> Unit = { _, _, _ -> },
    onToggleTtsPause: () -> Unit = {},
    onStopTts: () -> Unit = {},
    onOpenImportedBook: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
    val item by container.libraryRepository.observeItem(itemId).collectAsState(initial = null)
    val chapters by container.libraryRepository.observeChapters(itemId).collectAsState(initial = emptyList())
    val highlights by container.libraryRepository.observeHighlights(itemId).collectAsState(initial = emptyList())
    val voiceCast by container.libraryRepository.observeVoiceCast(itemId).collectAsState(initial = emptyList())
    val ttsFullState by container.ttsChapterPlayer.state.collectAsState()

    var chapterIndex by remember { mutableIntStateOf(initialChapterIndex) }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingChapter by remember { mutableStateOf(true) }
    var showChrome by remember { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showTocSheet by remember { mutableStateOf(false) }
    var showTtsSheet by remember { mutableStateOf(false) }
    var pagedParagraphOffset by remember { mutableIntStateOf(0) }
    var reloadNonce by remember { mutableIntStateOf(0) }

    var selectedParagraph by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var highlightNoteInput by remember { mutableStateOf("") }
    var aiTranslationText by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }
    var isExportingTtsAudio by remember { mutableStateOf(false) }
    var ttsExportProgressText by remember { mutableStateOf("") }
    var recapDialogText by remember { mutableStateOf<String?>(null) }
    var isGeneratingRecap by remember { mutableStateOf(false) }
    var isDownloadingFullEbook by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val currentChapter = chapters.getOrNull(chapterIndex)
    val detectedEbookUrl = remember(paragraphs, chapters.size, currentChapter?.remoteUrl) {
        if (chapters.size <= 3) {
            MegaPublicDownloader.extractDownloadableEbookUrl(
                paragraphs + listOfNotNull(currentChapter?.remoteUrl)
            )
        } else {
            null
        }
    }
    val currentHighlightsMap = remember(highlights, currentChapter?.id) {
        highlights.filter { it.chapterId == currentChapter?.id }.associateBy { it.paragraphIndex }
    }

    // Hardware volume key interception + screen orientation lock
    DisposableEffect(settings.volumeKeyPageTurn, settings.readerOrientationLock) {
        VolumeKeyBus.interceptVolumeKeys = settings.volumeKeyPageTurn
        activity?.requestedOrientation = when (settings.readerOrientationLock) {
            "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "LANDSCAPE" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            VolumeKeyBus.interceptVolumeKeys = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Track active reading duration every 30s
    LaunchedEffect(itemId, isLoadingChapter, ttsIsPlaying) {
        while (isActive) {
            delay(30_000L)
            if (!isLoadingChapter && !ttsIsPlaying) {
                container.libraryRepository.recordReadingTime(readSeconds = 30L)
            }
        }
    }

    LaunchedEffect(itemId) {
        container.libraryRepository.markOpened(itemId)
    }

    LaunchedEffect(currentChapter?.id, reloadNonce) {
        val ch = currentChapter ?: return@LaunchedEffect
        isLoadingChapter = true
        val file = withContext(Dispatchers.IO) {
            if (reloadNonce > 0) onForceReloadChapter(ch) else onEnsureChapterReady(ch)
        }
        val loaded = withContext(Dispatchers.IO) {
            if (file != null && file.exists()) {
                file.readText(Charsets.UTF_8)
                    .split(Regex("\n+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            } else {
                listOf("Chương chưa được tải về máy hoặc đang ở trạng thái ngoại tuyến.")
            }
        }
        paragraphs = loaded
        isLoadingChapter = false

        val saved = container.libraryRepository.getProgress(itemId)
        if (saved != null && saved.currentChapterId == ch.id && saved.textParagraphIndex in loaded.indices) {
            listState.scrollToItem(saved.textParagraphIndex)
            pagedParagraphOffset = saved.textParagraphIndex
        } else {
            listState.scrollToItem(0)
            pagedParagraphOffset = 0
        }
    }

    LaunchedEffect(ttsActiveParagraph, ttsIsPlaying) {
        if (ttsIsPlaying && ttsActiveParagraph in paragraphs.indices) {
            listState.animateScrollToItem(ttsActiveParagraph)
            pagedParagraphOffset = ttsActiveParagraph
        }
    }

    LaunchedEffect(currentChapter?.id, paragraphs.size) {
        val ch = currentChapter ?: return@LaunchedEffect
        if (paragraphs.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstIdx ->
                val totalCh = chapters.size.coerceAtLeast(1)
                val inChPct = (firstIdx.toFloat() / paragraphs.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                val overall = ((chapterIndex + inChPct) / totalCh).coerceIn(0f, 1f)
                container.libraryRepository.saveProgress(
                    itemId = itemId,
                    chapterId = ch.id,
                    chapterIndex = ch.chapterIndex,
                    textParagraphIndex = firstIdx,
                    progressPercent = overall
                )
            }
    }

    val fontFamily = when (settings.novelFontFamily) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    val readerBgColor = if (settings.novelCustomBgColorHex != 0L) Color(settings.novelCustomBgColorHex) else MaterialTheme.colorScheme.background
    val readerTextColor = if (settings.novelCustomTextColorHex != 0L) Color(settings.novelCustomTextColorHex) else MaterialTheme.colorScheme.onBackground

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showChrome) {
                TopAppBar(
                    title = {
                        Column {
                            Text(item?.title ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                currentChapter?.title ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = !isGeneratingRecap,
                            onClick = {
                                isGeneratingRecap = true
                                scope.launch {
                                    val loadedUpToCurrent = withContext(Dispatchers.IO) {
                                        chapters.filter { it.chapterIndex <= chapterIndex }
                                            .takeLast(4)
                                            .mapNotNull { ch ->
                                                val f = ch.localFilePath?.let(::File)?.takeIf { it.exists() }
                                                if (f != null) ch.title to f.readText(Charsets.UTF_8) else null
                                            }
                                    }
                                    val res = container.bookAiAssistant.generateSpoilerFreeRecap(
                                        bookTitle = item?.title.orEmpty(),
                                        recentChaptersUpToCurrent = loadedUpToCurrent,
                                        characters = voiceCast
                                    )
                                    isGeneratingRecap = false
                                    res.onSuccess { recapDialogText = it }
                                        .onFailure { snackbarHostState.showSnackbar("Lỗi tóm tắt AI: ${it.message}") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.AutoAwesome, "Tóm tắt nhớ lại (AI)")
                        }
                        IconButton(onClick = { reloadNonce++ }) {
                            Icon(Icons.Default.Refresh, "Tải lại chương")
                        }
                        IconButton(onClick = { showTtsSheet = true }) {
                            Icon(Icons.Default.Headphones, "Nghe TTS & Phân vai")
                        }
                        IconButton(onClick = {
                            val ch = currentChapter ?: return@IconButton
                            val idx = listState.firstVisibleItemIndex
                            scope.launch {
                                container.libraryRepository.addBookmark(
                                    itemId = itemId,
                                    chapterId = ch.id,
                                    positionLabel = "${ch.title} · Đoạn ${idx + 1}",
                                    positionPayloadJson = """{"chapterIndex":$chapterIndex,"paragraph":$idx}"""
                                )
                                snackbarHostState.showSnackbar("Đã lưu dấu trang")
                            }
                        }) {
                            Icon(Icons.Default.BookmarkAdd, "Đánh dấu")
                        }
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(Icons.Default.Settings, "Cài đặt đọc")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showChrome || ttsIsActive) {
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (chapterIndex > 0) chapterIndex-- },
                            enabled = chapterIndex > 0
                        ) { Icon(Icons.Default.ChevronLeft, "Chương trước") }

                        IconButton(onClick = { showTocSheet = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, "Mục lục")
                        }

                        TextButton(onClick = {
                            val nextMode = if (settings.novelReadMode == NovelReadMode.SCROLL) {
                                NovelReadMode.PAGED
                            } else {
                                NovelReadMode.SCROLL
                            }
                            scope.launch { container.settingsRepository.updateNovelReadMode(nextMode) }
                        }) {
                            Text(if (settings.novelReadMode == NovelReadMode.PAGED) "Lật trang" else "Cuộn dọc")
                        }

                        if (ttsIsActive) {
                            IconButton(onClick = onToggleTtsPause) {
                                Icon(
                                    if (ttsIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    "Phát / Tạm dừng TTS"
                                )
                            }
                            IconButton(onClick = onStopTts) {
                                Icon(Icons.Default.Stop, "Dừng TTS")
                            }
                        } else {
                            TextButton(onClick = { showTtsSheet = true }) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Nghe")
                            }
                        }

                        IconButton(
                            onClick = { if (chapterIndex < chapters.lastIndex) chapterIndex++ },
                            enabled = chapterIndex < chapters.lastIndex
                        ) { Icon(Icons.Default.ChevronRight, "Chương sau") }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(readerBgColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showChrome = !showChrome }
        ) {
            if (isLoadingChapter) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (settings.novelReadMode == NovelReadMode.SCROLL) {
                LaunchedEffect(Unit) {
                    VolumeKeyBus.events.collect { delta ->
                        if (settings.novelReadMode == NovelReadMode.SCROLL) {
                            listState.scrollBy(delta * 560f)
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(readerBgColor)
                        .padding(horizontal = settings.novelHorizontalMarginDp.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = currentChapter?.title.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            fontFamily = fontFamily,
                            color = readerTextColor
                        )
                        if (detectedEbookUrl != null) {
                            Spacer(Modifier.height(10.dp))
                            DirectEbookUnpackCard(
                                isDownloading = isDownloadingFullEbook,
                                onDownloadClick = {
                                    isDownloadingFullEbook = true
                                    scope.launch {
                                        val res = container.webChapterDownloader.importEbookFromDownloadUrl(
                                            downloadUrl = detectedEbookUrl,
                                            fallbackTitle = item?.title.orEmpty(),
                                            fallbackAuthor = item?.author.orEmpty(),
                                            replaceExistingItemId = itemId
                                        )
                                        isDownloadingFullEbook = false
                                        res.onSuccess { saved ->
                                            snackbarHostState.showSnackbar("Đã giải nén toàn bộ Ebook: ${saved.title}")
                                            onOpenImportedBook(saved.id)
                                        }.onFailure { err ->
                                            snackbarHostState.showSnackbar("Lỗi tải Ebook: ${err.message}")
                                        }
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    itemsIndexed(paragraphs) { idx, para ->
                        val isTtsActive = idx == ttsActiveParagraph
                        val userHighlight = currentHighlightsMap[idx]
                        val bgTint = when {
                            isTtsActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            userHighlight != null -> Color(userHighlight.colorHex).copy(alpha = 0.22f)
                            else -> Color.Transparent
                        }
                        Text(
                            text = para,
                            fontSize = settings.novelFontSizeSp.sp,
                            lineHeight = (settings.novelFontSizeSp * settings.novelLineHeightMultiplier).sp,
                            fontFamily = fontFamily,
                            color = readerTextColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(bgTint)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { showChrome = !showChrome },
                                    onLongClick = {
                                        highlightNoteInput = userHighlight?.note.orEmpty()
                                        aiTranslationText = ""
                                        selectedParagraph = idx to para
                                    }
                                )
                                .padding(if (isTtsActive || userHighlight != null) 6.dp else 0.dp)
                        )
                    }
                    item {
                        Spacer(Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(
                                onClick = {
                                    scope.launch { container.libraryRepository.recordReadingTime(chaptersDone = 1) }
                                    if (chapterIndex > 0) chapterIndex--
                                },
                                enabled = chapterIndex > 0
                            ) { Text("← Chương trước") }
                            TextButton(
                                onClick = {
                                    scope.launch { container.libraryRepository.recordReadingTime(chaptersDone = 1) }
                                    if (chapterIndex < chapters.lastIndex) chapterIndex++
                                },
                                enabled = chapterIndex < chapters.lastIndex
                            ) { Text("Chương sau →") }
                        }
                        Spacer(Modifier.height(48.dp))
                    }
                }
            } else {
                val pages = remember(paragraphs, settings.novelFontSizeSp, settings.novelLineHeightMultiplier) {
                    paginateParagraphs(paragraphs, settings.novelFontSizeSp, settings.novelLineHeightMultiplier)
                }
                val initialPage = remember(currentChapter?.id, pages.size) {
                    pages.indexOfFirst { pageList -> pageList.any { it.first >= pagedParagraphOffset } }
                        .coerceAtLeast(0)
                }
                val pagerState = rememberPagerState(
                    initialPage = initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
                    pageCount = { pages.size.coerceAtLeast(1) }
                )

                LaunchedEffect(pages.size, chapterIndex) {
                    VolumeKeyBus.events.collect { delta ->
                        if (settings.novelReadMode == NovelReadMode.PAGED) {
                            if (delta > 0) {
                                if (pagerState.currentPage < pages.lastIndex) {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                } else if (chapterIndex < chapters.lastIndex) {
                                    chapterIndex++
                                }
                            } else {
                                if (pagerState.currentPage > 0) {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                } else if (chapterIndex > 0) {
                                    chapterIndex--
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(ttsActiveParagraph, ttsIsPlaying, pages.size) {
                    if (ttsIsPlaying && ttsActiveParagraph >= 0) {
                        val target = pages.indexOfFirst { list -> list.any { it.first == ttsActiveParagraph } }
                        if (target in pages.indices && target != pagerState.currentPage) {
                            pagerState.animateScrollToPage(target)
                        }
                    }
                }

                LaunchedEffect(currentChapter?.id, pages.size) {
                    val ch = currentChapter ?: return@LaunchedEffect
                    if (pages.isEmpty()) return@LaunchedEffect
                    snapshotFlow { pagerState.currentPage }
                        .distinctUntilChanged()
                        .collect { pageIdx ->
                            val firstPara = pages.getOrNull(pageIdx)?.firstOrNull()?.first ?: 0
                            val totalCh = chapters.size.coerceAtLeast(1)
                            val inChPct = (pageIdx.toFloat() / pages.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val overall = ((chapterIndex + inChPct) / totalCh).coerceIn(0f, 1f)
                            container.libraryRepository.saveProgress(
                                itemId = itemId,
                                chapterId = ch.id,
                                chapterIndex = ch.chapterIndex,
                                textParagraphIndex = firstPara,
                                progressPercent = overall
                            )
                        }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(pages.size, chapterIndex) {
                            detectTapGestures(
                                onTap = { pos ->
                                    val w = size.width
                                    when {
                                        pos.x < w * 0.28f -> {
                                            if (pagerState.currentPage > 0) {
                                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                            } else if (chapterIndex > 0) {
                                                chapterIndex--
                                            }
                                        }
                                        pos.x > w * 0.72f -> {
                                            if (pagerState.currentPage < pages.lastIndex) {
                                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                            } else if (chapterIndex < chapters.lastIndex) {
                                                chapterIndex++
                                            }
                                        }
                                        else -> showChrome = !showChrome
                                    }
                                },
                                onLongPress = {
                                    val firstOnPage = pages.getOrNull(pagerState.currentPage)?.firstOrNull()
                                    if (firstOnPage != null) {
                                        highlightNoteInput = currentHighlightsMap[firstOnPage.first]?.note.orEmpty()
                                        aiTranslationText = ""
                                        selectedParagraph = firstOnPage
                                    }
                                }
                            )
                        }
                ) { pageIdx ->
                    val pageItems = pages.getOrNull(pageIdx).orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .pageTurnTransition(pagerState, pageIdx, settings.pageTurnEffect)
                            .background(readerBgColor)
                            .padding(horizontal = settings.novelHorizontalMarginDp.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (pageIdx == 0) {
                                Text(
                                    currentChapter?.title.orEmpty(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = fontFamily,
                                    color = readerTextColor
                                )
                                if (detectedEbookUrl != null) {
                                    DirectEbookUnpackCard(
                                        isDownloading = isDownloadingFullEbook,
                                        onDownloadClick = {
                                            isDownloadingFullEbook = true
                                            scope.launch {
                                                val res = container.webChapterDownloader.importEbookFromDownloadUrl(
                                                    downloadUrl = detectedEbookUrl,
                                                    fallbackTitle = item?.title.orEmpty(),
                                                    fallbackAuthor = item?.author.orEmpty(),
                                                    replaceExistingItemId = itemId
                                                )
                                                isDownloadingFullEbook = false
                                                res.onSuccess { saved ->
                                                    snackbarHostState.showSnackbar("Đã giải nén toàn bộ Ebook: ${saved.title}")
                                                    onOpenImportedBook(saved.id)
                                                }.onFailure { err ->
                                                    snackbarHostState.showSnackbar("Lỗi tải Ebook: ${err.message}")
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                            pageItems.forEach { (globalIdx, para) ->
                                val isTtsActive = globalIdx == ttsActiveParagraph
                                val userHighlight = currentHighlightsMap[globalIdx]
                                val bgTint = when {
                                    isTtsActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    userHighlight != null -> Color(userHighlight.colorHex).copy(alpha = 0.22f)
                                    else -> Color.Transparent
                                }
                                Text(
                                    text = para,
                                    fontSize = settings.novelFontSizeSp.sp,
                                    lineHeight = (settings.novelFontSizeSp * settings.novelLineHeightMultiplier).sp,
                                    fontFamily = fontFamily,
                                    color = readerTextColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(bgTint)
                                        .padding(if (isTtsActive || userHighlight != null) 4.dp else 0.dp)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                currentChapter?.title.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = readerTextColor.copy(alpha = 0.65f),
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "Trang ${pageIdx + 1}/${pages.size.coerceAtLeast(1)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = readerTextColor.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }

            if (ttsStatusText.isNotBlank()) {
                Surface(
                    tonalElevation = 4.dp,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                ) {
                    Text(
                        ttsStatusText,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }

    // Long-press paragraph sheet: Highlight (4 colors) + Note + AI Translate & Explain
    selectedParagraph?.let { (paraIdx, paraText) ->
        ModalBottomSheet(onDismissRequest = { selectedParagraph = null }) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Đoạn ${paraIdx + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(paraText.take(220) + if (paraText.length > 220) "..." else "", style = MaterialTheme.typography.bodySmall)

                OutlinedTextField(
                    value = highlightNoteInput,
                    onValueChange = { highlightNoteInput = it },
                    label = { Text("Ghi chú (tùy chọn)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Chọn màu Highlight để lưu:", style = MaterialTheme.typography.labelLarge)
                val highlightColors = listOf(
                    "Vàng" to 0xFFFFEB3BL,
                    "Xanh lá" to 0xFF81C784L,
                    "Xanh dương" to 0xFF64B5F6L,
                    "Hồng" to 0xFFF48FB1L
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    highlightColors.forEach { (label, hex) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(hex).copy(alpha = 0.85f),
                            modifier = Modifier.clickable {
                                val ch = currentChapter ?: return@clickable
                                selectedParagraph = null
                                scope.launch {
                                    container.libraryRepository.addHighlight(
                                        itemId = itemId,
                                        chapterId = ch.id,
                                        chapterTitle = ch.title,
                                        paragraphIndex = paraIdx,
                                        quoteText = paraText,
                                        colorHex = hex,
                                        note = highlightNoteInput
                                    )
                                    snackbarHostState.showSnackbar("Đã lưu Highlight ($label)")
                                }
                            }
                        ) {
                            Text(
                                label,
                                color = Color.Black,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                OutlinedButton(
                    enabled = !isTranslating,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isTranslating = true
                        scope.launch {
                            val res = container.bookAiAssistant.translateAndExplain(paraText)
                            isTranslating = false
                            res.onSuccess { aiTranslationText = it }
                                .onFailure { snackbarHostState.showSnackbar("Lỗi AI: ${it.message}") }
                        }
                    }
                ) {
                    if (isTranslating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Translate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (isTranslating) "Đang dịch & giải thích..." else "Dịch & Giải thích đoạn bằng Gemini AI")
                }

                if (aiTranslationText.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(aiTranslationText, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { highlightNoteInput = aiTranslationText }) {
                                Text("Chép vào ô Ghi chú")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
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

    if (showSettingsSheet) {
        ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text("Giao diện & Chữ đọc", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(readerBgColor)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Xem trước: Ánh trăng dịu nhẹ xuyên qua khung cửa sổ...",
                        color = readerTextColor,
                        fontSize = settings.novelFontSizeSp.sp,
                        lineHeight = (settings.novelFontSizeSp * settings.novelLineHeightMultiplier).sp,
                        fontFamily = fontFamily
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text("Mẫu giấy đọc nhanh", style = MaterialTheme.typography.labelLarge)
                val paperPresets = listOf(
                    Triple("Theo Theme", 0L, 0L),
                    Triple("Giấy trắng", 0xFFFCFCFDL, 0xFF1A1C1EL),
                    Triple("Kem Sepia", 0xFFF4ECD8L, 0xFF3B2F24L),
                    Triple("Xanh dịu mắt", 0xFFC7EDCCL, 0xFF1F3323L),
                    Triple("Đêm xám", 0xFF1E2128L, 0xFFDADEE8L),
                    Triple("AMOLED Vàng", 0xFF000000L, 0xFFE6D5B8L)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(paperPresets) { (label, bgHex, txtHex) ->
                        FilterChip(
                            selected = settings.novelCustomBgColorHex == bgHex && settings.novelCustomTextColorHex == txtHex,
                            onClick = {
                                scope.launch { container.settingsRepository.updateNovelColors(bgHex, txtHex) }
                            },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Màu trang (Tùy chọn)", style = MaterialTheme.typography.labelLarge)
                val bgPalette = listOf(
                    0xFFFCFCFDL, 0xFFF4ECD8L, 0xFFFDF0D5L, 0xFFC7EDCCL, 0xFFDCE8F2L,
                    0xFF2B2D30L, 0xFF1C222EL, 0xFF121316L, 0xFF000000L, 0xFF2B1F1AL
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(bgPalette) { hex ->
                        val selected = settings.novelCustomBgColorHex == hex
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable {
                                    scope.launch {
                                        val txt = if (settings.novelCustomTextColorHex != 0L) settings.novelCustomTextColorHex else 0L
                                        container.settingsRepository.updateNovelColors(hex, txt)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Màu chữ (Tùy chọn)", style = MaterialTheme.typography.labelLarge)
                val textPalette = listOf(
                    0xFF1A1C1EL, 0xFF3B2F24L, 0xFF1F3323L, 0xFF102A43L, 0xFF4A4A4AL,
                    0xFFE3E2E6L, 0xFFFFFFFFL, 0xFFE6D5B8L, 0xFFA7D7B8L, 0xFF9AC5F4L
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(textPalette) { hex ->
                        val selected = settings.novelCustomTextColorHex == hex
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable {
                                    scope.launch {
                                        val bg = if (settings.novelCustomBgColorHex != 0L) settings.novelCustomBgColorHex else 0L
                                        container.settingsRepository.updateNovelColors(bg, hex)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Bảng màu hệ thống", style = MaterialTheme.typography.labelLarge)
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

                Spacer(Modifier.height(8.dp))
                Text("Chế độ đọc", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.novelReadMode == NovelReadMode.SCROLL,
                        onClick = { scope.launch { container.settingsRepository.updateNovelReadMode(NovelReadMode.SCROLL) } },
                        label = { Text("Cuộn dọc") }
                    )
                    FilterChip(
                        selected = settings.novelReadMode == NovelReadMode.PAGED,
                        onClick = { scope.launch { container.settingsRepository.updateNovelReadMode(NovelReadMode.PAGED) } },
                        label = { Text("Lật trang") }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text("Hiệu ứng lật trang", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PageTurnEffect.entries) { effect ->
                        FilterChip(
                            selected = settings.pageTurnEffect == effect,
                            onClick = { scope.launch { container.settingsRepository.updatePageTurnEffect(effect) } },
                            label = { Text(effect.displayNameVi) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Cỡ chữ: ${settings.novelFontSizeSp.toInt()}sp")
                Slider(
                    value = settings.novelFontSizeSp,
                    onValueChange = { v -> scope.launch { container.settingsRepository.updateNovelFontSize(v) } },
                    valueRange = 13f..30f
                )

                Text("Giãn dòng: ${"%.2f".format(settings.novelLineHeightMultiplier)}x")
                Slider(
                    value = settings.novelLineHeightMultiplier,
                    onValueChange = { v -> scope.launch { container.settingsRepository.updateNovelLineHeight(v) } },
                    valueRange = 1.15f..2.1f
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("serif" to "Có chân", "sans" to "Không chân", "mono" to "Mono").forEach { (key, label) ->
                        FilterChip(
                            selected = settings.novelFontFamily == key,
                            onClick = { scope.launch { container.settingsRepository.updateNovelFontFamily(key) } },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lật trang / cuộn bằng phím Âm lượng")
                    Switch(
                        checked = settings.volumeKeyPageTurn,
                        onCheckedChange = { scope.launch { container.settingsRepository.updateVolumeKeyPageTurn(it) } }
                    )
                }

                Text("Khóa xoay màn hình:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("AUTO" to "Tự động", "PORTRAIT" to "Dọc", "LANDSCAPE" to "Ngang").forEach { (lock, label) ->
                        FilterChip(
                            selected = settings.readerOrientationLock == lock,
                            onClick = { scope.launch { container.settingsRepository.updateReaderOrientationLock(lock) } },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showTocSheet) {
        ModalBottomSheet(onDismissRequest = { showTocSheet = false }) {
            Text(
                "Mục lục (${chapters.size} chương)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyColumn(modifier = Modifier.height(420.dp)) {
                itemsIndexed(chapters) { idx, ch ->
                    Text(
                        text = ch.title,
                        fontWeight = if (idx == chapterIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (idx == chapterIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                chapterIndex = idx
                                showTocSheet = false
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }

    if (showTtsSheet) {
        ModalBottomSheet(onDismissRequest = { showTtsSheet = false }) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Nghe truyện chữ (TTS)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Button(
                    onClick = {
                        showTtsSheet = false
                        currentChapter?.let { onToggleTts(it, paragraphs, TtsMode.QUICK_LOCAL) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Nghe nhanh (Tách thoại cục bộ · Miễn phí)")
                }
                Button(
                    onClick = {
                        showTtsSheet = false
                        currentChapter?.let { onToggleTts(it, paragraphs, TtsMode.AI_CAST) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.RecordVoiceOver, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Nghe Phân vai nhân vật AI (Gemini Cast)")
                }

                Text("Hẹn giờ tắt TTS:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Tắt", 15 to "15p", 30 to "30p", 60 to "60p").forEach { (m, label) ->
                        FilterChip(
                            selected = m == 0 && ttsFullState.sleepTimerRemainingMs == 0L && !ttsFullState.stopAfterChapter,
                            onClick = { container.ttsChapterPlayer.setSleepTimer(m, false) },
                            label = { Text(label) }
                        )
                    }
                    FilterChip(
                        selected = ttsFullState.stopAfterChapter,
                        onClick = { container.ttsChapterPlayer.setSleepTimer(0, !ttsFullState.stopAfterChapter) },
                        label = { Text("Hết chương") }
                    )
                }

                OutlinedButton(
                    enabled = !isExportingTtsAudio && currentChapter != null && paragraphs.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val ch = currentChapter ?: return@OutlinedButton
                        isExportingTtsAudio = true
                        ttsExportProgressText = "Đang tổng hợp..."
                        scope.launch {
                            val res = container.ttsChapterPlayer.exportChapterToOfflineAudio(
                                itemId = itemId,
                                chapter = ch,
                                paragraphs = paragraphs,
                                mode = TtsMode.QUICK_LOCAL
                            ) { done, total ->
                                ttsExportProgressText = "Đang xuất $done/$total đoạn..."
                            }
                            isExportingTtsAudio = false
                            showTtsSheet = false
                            res.onSuccess { f ->
                                snackbarHostState.showSnackbar("Đã xuất MP3 offline: ${f.name}")
                            }.onFailure { err ->
                                snackbarHostState.showSnackbar("Lỗi xuất audio: ${err.message}")
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isExportingTtsAudio) ttsExportProgressText else "Xuất chương này ra tệp MP3 offline")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showTtsSheet = false
                            onOpenVoiceCast(itemId)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Voice Cast Studio") }
                    TextButton(
                        onClick = {
                            showTtsSheet = false
                            onOpenPronunciation()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Từ điển & Lọc rác") }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun DirectEbookUnpackCard(
    isDownloading: Boolean,
    onDownloadClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = UmeforTealDeep,
        border = BorderStroke(1.dp, UmeforLime.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Phát hiện liên kết tải Ebook (EPUB / PDF)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = UmeforLime
            )
            Text(
                "Trang này chỉ chứa phần giới thiệu. Bấm nút bên dưới để tự động tải và giải nén toàn bộ nội dung sách ngay trong app.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFDCEEE5)
            )
            Button(
                enabled = !isDownloading,
                onClick = onDownloadClick,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Đang tải & giải nén Ebook...")
                } else {
                    Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Tải & đọc toàn bộ Ebook ngay")
                }
            }
        }
    }
}
