package io.lightknight.omniread.ui.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.lightknight.omniread.core.AppSettings
import io.lightknight.omniread.core.NaturalSort
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.core.VolumeKeyBus
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.model.ComicReadMode
import io.lightknight.omniread.data.model.PageTurnEffect
import io.lightknight.omniread.engine.importer.PdfComicImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(
    itemId: String,
    initialChapterIndex: Int,
    container: OmniAppContainer,
    onBack: () -> Unit,
    onEnsureChapterReady: suspend (ChapterEntity) -> File? = { ch -> ch.localFilePath?.let(::File)?.takeIf { it.exists() } }
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
    val item by container.libraryRepository.observeItem(itemId).collectAsState(initial = null)
    val chapters by container.libraryRepository.observeChapters(itemId).collectAsState(initial = emptyList())

    var chapterIndex by remember { mutableIntStateOf(initialChapterIndex) }
    var pageFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showChrome by remember { mutableStateOf(true) }
    var showModeSheet by remember { mutableStateOf(false) }
    var isAutoScrolling by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val currentChapter = chapters.getOrNull(chapterIndex)

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

    LaunchedEffect(itemId, isLoading) {
        while (isActive) {
            delay(30_000L)
            if (!isLoading) {
                container.libraryRepository.recordReadingTime(readSeconds = 30L)
            }
        }
    }

    LaunchedEffect(itemId) {
        container.libraryRepository.markOpened(itemId)
    }

    LaunchedEffect(currentChapter?.id) {
        val ch = currentChapter ?: return@LaunchedEffect
        isLoading = true
        isAutoScrolling = false
        val resolved = withContext(Dispatchers.IO) { onEnsureChapterReady(ch) }
        val pages = withContext(Dispatchers.IO) {
            if (resolved == null) {
                emptyList()
            } else if (resolved.isFile && resolved.extension.equals("pdf", ignoreCase = true)) {
                val cacheDir = File(container.storageManager.itemDir(itemId), "pdf_pages")
                (0 until ch.pageCount).map { p -> PdfComicImporter.getOrRenderPage(resolved, p, cacheDir) }
            } else if (resolved.isDirectory) {
                resolved.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }
                    .orEmpty()
            } else {
                emptyList()
            }
        }
        pageFiles = pages
        isLoading = false
    }

    Scaffold(
        topBar = {
            if (showChrome) {
                TopAppBar(
                    title = {
                        Column {
                            Text(item?.title ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(currentChapter?.title ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
                    },
                    actions = {
                        IconButton(onClick = { showModeSheet = true }) {
                            Icon(Icons.Default.Settings, "Chế độ đọc tranh")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showChrome) {
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (chapterIndex > 0) chapterIndex-- },
                            enabled = chapterIndex > 0
                        ) { Icon(Icons.Default.ChevronLeft, "Chương trước") }
                        Text("Ch.${chapterIndex + 1}/${chapters.size.coerceAtLeast(1)} · ${pageFiles.size}tr")
                        TextButton(onClick = {
                            val next = if (settings.comicReadMode == ComicReadMode.WEBTOON) {
                                ComicReadMode.LTR
                            } else {
                                ComicReadMode.WEBTOON
                            }
                            scope.launch { container.settingsRepository.updateComicReadMode(next) }
                        }) {
                            Text(
                                when (settings.comicReadMode) {
                                    ComicReadMode.WEBTOON -> "Webtoon"
                                    ComicReadMode.LTR -> "Lật LTR"
                                    ComicReadMode.RTL -> "Lật RTL"
                                }
                            )
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
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 3.5f)
            if (scale > 1f) {
                offset += panChange
            } else {
                offset = Offset.Zero
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(androidx.compose.ui.graphics.Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showChrome = !showChrome },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.2f
                            }
                        }
                    )
                }
                .then(
                    if (scale > 1f) Modifier.transformable(transformState) else Modifier
                )
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (pageFiles.isEmpty()) {
                Text(
                    "Không tìm thấy trang ảnh của chương này.",
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (settings.comicReadMode == ComicReadMode.WEBTOON) {
                val listState = rememberLazyListState()

                LaunchedEffect(Unit) {
                    VolumeKeyBus.events.collect { delta ->
                        if (settings.comicReadMode == ComicReadMode.WEBTOON) {
                            listState.scrollBy(delta * 720f)
                        }
                    }
                }

                LaunchedEffect(isAutoScrolling, settings.comicAutoScrollSpeed) {
                    while (isAutoScrolling && isActive) {
                        val consumed = listState.scrollBy(settings.comicAutoScrollSpeed * 2.8f)
                        if (consumed == 0f && !listState.canScrollForward) {
                            isAutoScrolling = false
                        }
                        delay(16L)
                    }
                }

                LaunchedEffect(currentChapter?.id, pageFiles.size) {
                    val ch = currentChapter ?: return@LaunchedEffect
                    snapshotFlow { listState.firstVisibleItemIndex }
                        .distinctUntilChanged()
                        .collect { pageIdx ->
                            val total = chapters.size.coerceAtLeast(1)
                            val pct = ((chapterIndex + (pageIdx.toFloat() / pageFiles.size.coerceAtLeast(1))) / total)
                            container.libraryRepository.saveProgress(
                                itemId = itemId,
                                chapterId = ch.id,
                                chapterIndex = ch.chapterIndex,
                                comicPageIndex = pageIdx,
                                progressPercent = pct
                            )
                        }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                ) {
                    itemsIndexed(pageFiles, key = { _, f -> f.absolutePath }) { _, file ->
                        ComicPageImage(
                            file = file,
                            autoCrop = settings.comicAutoCropBorders,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                SmallFloatingActionButton(
                    onClick = { isAutoScrolling = !isAutoScrolling },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        if (isAutoScrolling) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Auto-scroll Webtoon"
                    )
                }
            } else {
                val isRtl = settings.comicReadMode == ComicReadMode.RTL
                val useDoubleSpread = isLandscape && settings.comicDoublePageLandscape
                val spreads = remember(pageFiles, isRtl, useDoubleSpread) {
                    if (useDoubleSpread) {
                        val pairs = pageFiles.chunked(2)
                        if (isRtl) pairs.reversed().map { it.reversed() } else pairs
                    } else {
                        val singles = pageFiles.map { listOf(it) }
                        if (isRtl) singles.reversed() else singles
                    }
                }
                val pagerState = rememberPagerState(
                    initialPage = if (isRtl) (spreads.size - 1).coerceAtLeast(0) else 0,
                    pageCount = { spreads.size.coerceAtLeast(1) }
                )

                LaunchedEffect(spreads.size, chapterIndex) {
                    VolumeKeyBus.events.collect { delta ->
                        if (settings.comicReadMode != ComicReadMode.WEBTOON) {
                            val step = if (isRtl) -delta else delta
                            if (step > 0) {
                                if (pagerState.currentPage < spreads.lastIndex) {
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

                LaunchedEffect(currentChapter?.id, spreads.size) {
                    val ch = currentChapter ?: return@LaunchedEffect
                    snapshotFlow { pagerState.currentPage }
                        .distinctUntilChanged()
                        .collect { rawPage ->
                            val logicalSpread = if (isRtl) (spreads.lastIndex - rawPage).coerceAtLeast(0) else rawPage
                            val logicalPage = if (useDoubleSpread) logicalSpread * 2 else logicalSpread
                            val total = chapters.size.coerceAtLeast(1)
                            val pct = ((chapterIndex + (logicalPage.toFloat() / pageFiles.size.coerceAtLeast(1))) / total)
                            container.libraryRepository.saveProgress(
                                itemId = itemId,
                                chapterId = ch.id,
                                chapterIndex = ch.chapterIndex,
                                comicPageIndex = logicalPage,
                                progressPercent = pct
                            )
                        }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(spreads.size, scale) {
                            if (scale <= 1.01f) {
                                detectTapGestures(
                                    onTap = { pos ->
                                        val w = size.width
                                        when {
                                            pos.x < w * 0.25f && pagerState.currentPage > 0 ->
                                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                            pos.x > w * 0.75f && pagerState.currentPage < spreads.lastIndex ->
                                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                            else -> showChrome = !showChrome
                                        }
                                    },
                                    onDoubleTap = { scale = 2.2f }
                                )
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                ) { idx ->
                    val pagePair = spreads.getOrNull(idx).orEmpty()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pageTurnTransition(pagerState, idx, settings.pageTurnEffect),
                        contentAlignment = Alignment.Center
                    ) {
                        if (pagePair.size == 1) {
                            ComicPageImage(
                                file = pagePair[0],
                                autoCrop = settings.comicAutoCropBorders,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (pagePair.size >= 2) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                ComicPageImage(
                                    file = pagePair[0],
                                    autoCrop = settings.comicAutoCropBorders,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                                ComicPageImage(
                                    file = pagePair[1],
                                    autoCrop = settings.comicAutoCropBorders,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModeSheet) {
        ModalBottomSheet(onDismissRequest = { showModeSheet = false }) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text("Chế độ đọc truyện tranh", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ComicReadMode.WEBTOON to "Webtoon dọc",
                        ComicReadMode.LTR to "Trái → Phải",
                        ComicReadMode.RTL to "Manga Phải → Trái"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.comicReadMode == mode,
                            onClick = { scope.launch { container.settingsRepository.updateComicReadMode(mode) } },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Hiệu ứng lật trang (LTR / Manga RTL)", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PageTurnEffect.entries) { effect ->
                        FilterChip(
                            selected = settings.pageTurnEffect == effect,
                            onClick = { scope.launch { container.settingsRepository.updatePageTurnEffect(effect) } },
                            label = { Text(effect.displayNameVi) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Tốc độ cuộn tự động Webtoon: ${"%.1f".format(settings.comicAutoScrollSpeed)}x")
                Slider(
                    value = settings.comicAutoScrollSpeed,
                    onValueChange = { spd ->
                        scope.launch { container.settingsRepository.updateComicAutoScrollSpeed(spd) }
                    },
                    valueRange = 0.4f..3.5f
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tự cắt viền trắng (Auto-crop)")
                    Switch(
                        checked = settings.comicAutoCropBorders,
                        onCheckedChange = { enabled ->
                            scope.launch { container.settingsRepository.updateComicAutoCrop(enabled) }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Đọc 2 trang song song khi xoay ngang")
                    Switch(
                        checked = settings.comicDoublePageLandscape,
                        onCheckedChange = { enabled ->
                            scope.launch { container.settingsRepository.updateComicDoublePageLandscape(enabled) }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lật trang / cuộn bằng phím Âm lượng")
                    Switch(
                        checked = settings.volumeKeyPageTurn,
                        onCheckedChange = { enabled ->
                            scope.launch { container.settingsRepository.updateVolumeKeyPageTurn(enabled) }
                        }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ComicPageImage(
    file: File,
    autoCrop: Boolean,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    if (!autoCrop) {
        AsyncImage(
            model = file,
            contentDescription = file.name,
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        var croppedBitmap by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(file.absolutePath) {
            croppedBitmap = withContext(Dispatchers.Default) { cropWhiteMargins(file) }
        }
        val bmp = croppedBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = file.name,
                contentScale = contentScale,
                modifier = modifier
            )
        } else {
            AsyncImage(
                model = file,
                contentDescription = file.name,
                contentScale = contentScale,
                modifier = modifier
            )
        }
    }
}

private fun cropWhiteMargins(file: File): Bitmap? {
    val src = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    if (src.width < 40 || src.height < 40) return src
    var left = 0
    var right = src.width - 1
    var top = 0
    var bottom = src.height - 1

    fun isNearWhite(px: Int): Boolean {
        val r = Color.red(px)
        val g = Color.green(px)
        val b = Color.blue(px)
        return r > 242 && g > 242 && b > 242
    }

    val step = 6
    while (left < right / 3) {
        var hasInk = false
        for (y in 0 until src.height step step) {
            if (!isNearWhite(src.getPixel(left, y))) {
                hasInk = true
                break
            }
        }
        if (hasInk) break
        left += 2
    }
    while (right > src.width * 2 / 3) {
        var hasInk = false
        for (y in 0 until src.height step step) {
            if (!isNearWhite(src.getPixel(right, y))) {
                hasInk = true
                break
            }
        }
        if (hasInk) break
        right -= 2
    }
    while (top < bottom / 4) {
        var hasInk = false
        for (x in left..right step step) {
            if (!isNearWhite(src.getPixel(x, top))) {
                hasInk = true
                break
            }
        }
        if (hasInk) break
        top += 2
    }
    while (bottom > src.height * 3 / 4) {
        var hasInk = false
        for (x in left..right step step) {
            if (!isNearWhite(src.getPixel(x, bottom))) {
                hasInk = true
                break
            }
        }
        if (hasInk) break
        bottom -= 2
    }

    val newW = (right - left + 1).coerceAtLeast(1)
    val newH = (bottom - top + 1).coerceAtLeast(1)
    return if (newW < src.width || newH < src.height) {
        Bitmap.createBitmap(src, left, top, newW, newH)
    } else {
        src
    }
}
