package io.lightknight.omniread.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.ui.detail.BookDetailScreen
import io.lightknight.omniread.ui.explore.CloudflareChallengeDialog
import io.lightknight.omniread.ui.explore.ExploreScreen
import io.lightknight.omniread.ui.explore.PluginManagerScreen
import io.lightknight.omniread.ui.explore.SourceWebBrowserScreen
import io.lightknight.omniread.ui.explore.YoutubeImportScreen
import io.lightknight.omniread.ui.library.LibraryScreen
import io.lightknight.omniread.ui.queue.QueueScreen
import io.lightknight.omniread.ui.reader.AudioPlayerScreen
import io.lightknight.omniread.ui.reader.ComicReaderScreen
import io.lightknight.omniread.ui.reader.NovelReaderScreen
import io.lightknight.omniread.ui.settings.PronunciationGlossaryScreen
import io.lightknight.omniread.ui.settings.SettingsScreen
import io.lightknight.omniread.ui.stats.ReadingStatsScreen
import io.lightknight.omniread.ui.theme.UmeforLime
import io.lightknight.omniread.ui.theme.UmeforTealDeep
import io.lightknight.omniread.ui.voicecast.VoiceCastStudioScreen
import kotlinx.coroutines.launch

object OmniRoutes {
    const val LIBRARY = "library"
    const val EXPLORE = "explore"
    const val QUEUE = "queue"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val PRONUNCIATION = "pronunciation"
    const val DETAIL = "detail/{itemId}"
    const val NOVEL_READER = "novel/{itemId}/{chapterIndex}"
    const val COMIC_READER = "comic/{itemId}/{chapterIndex}"
    const val AUDIO_PLAYER = "audio/{itemId}/{chapterIndex}"
    const val VOICE_CAST = "voicecast/{itemId}"
    const val PLUGINS = "plugins"
    const val YOUTUBE_IMPORT = "youtube"
    const val SOURCE_BROWSER = "browser?url={url}"

    fun detail(itemId: String) = "detail/$itemId"
    fun novelReader(itemId: String, chapterIndex: Int) = "novel/$itemId/$chapterIndex"
    fun comicReader(itemId: String, chapterIndex: Int) = "comic/$itemId/$chapterIndex"
    fun audioPlayer(itemId: String, chapterIndex: Int) = "audio/$itemId/$chapterIndex"
    fun voiceCast(itemId: String) = "voicecast/$itemId"
    fun sourceBrowser(url: String) = "browser?url=${android.net.Uri.encode(url)}"
}

@Composable
fun OmniNavHost(
    container: OmniAppContainer,
    onSignInDrive: () -> Unit = {},
    onSignInDriveBrowser: (String, String) -> Unit = { _, _ -> }
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val cloudflareChallenge by container.cloudflareWebViewFetcher.pendingChallenge.collectAsState()
    val playback by container.playbackController.uiState.collectAsState()
    val ttsState by container.ttsChapterPlayer.state.collectAsState()
    val scope = rememberCoroutineScope()

    cloudflareChallenge?.let { req ->
        CloudflareChallengeDialog(request = req)
    }

    val topLevelTabs = listOf(
        Triple(OmniRoutes.LIBRARY, "Thư viện", Icons.AutoMirrored.Filled.LibraryBooks),
        Triple(OmniRoutes.EXPLORE, "Khám phá", Icons.Default.Explore),
        Triple(OmniRoutes.QUEUE, "Đồng bộ", Icons.Default.CloudSync),
        Triple(OmniRoutes.SETTINGS, "Cài đặt", Icons.Default.Settings)
    )
    val showBottomBar = currentRoute in topLevelTabs.map { it.first }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (ttsState.isActive) {
                        val frac = if (ttsState.totalSegments > 0) {
                            (ttsState.activeSegmentIndex + 1).toFloat() / ttsState.totalSegments.toFloat()
                        } else 0f
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = UmeforTealDeep,
                            border = BorderStroke(1.dp, UmeforLime.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (ttsState.itemId.isNotBlank()) {
                                        navController.navigate(OmniRoutes.novelReader(ttsState.itemId, ttsState.chapterIndex))
                                    }
                                }
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.RecordVoiceOver, null, tint = UmeforLime, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            ttsState.bookTitle.ifBlank { "Đang đọc TTS" },
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            ttsState.statusMessage,
                                            color = Color(0xFFD5E6DD),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { container.ttsChapterPlayer.togglePlayPause() }) {
                                        Icon(
                                            if (ttsState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            "Phát / Tạm dừng TTS",
                                            tint = UmeforLime
                                        )
                                    }
                                    IconButton(onClick = { container.ttsChapterPlayer.stop() }) {
                                        Icon(Icons.Default.Stop, "Dừng TTS", tint = Color.White)
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { frac.coerceIn(0f, 1f) },
                                    color = UmeforLime,
                                    trackColor = Color.White.copy(alpha = 0.18f),
                                    modifier = Modifier.fillMaxWidth().height(2.5.dp)
                                )
                            }
                        }
                    } else if (playback.currentItemId.isNotBlank()) {
                        val frac = if (playback.durationMs > 0) {
                            playback.positionMs.toFloat() / playback.durationMs.toFloat()
                        } else 0f
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = UmeforTealDeep,
                            border = BorderStroke(1.dp, UmeforLime.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navController.navigate(OmniRoutes.audioPlayer(playback.currentItemId, playback.currentChapterIndex))
                                }
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Headphones, null, tint = UmeforLime, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            playback.currentBookTitle.ifBlank { "Sách nói" },
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            playback.currentChapterTitle,
                                            color = Color(0xFFD5E6DD),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { container.playbackController.seekBy(-15_000L) }) {
                                        Icon(Icons.Default.Replay10, "Tua lùi 15s", tint = Color.White)
                                    }
                                    IconButton(onClick = { container.playbackController.togglePlayPause() }) {
                                        Icon(
                                            if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            "Phát / Tạm dừng",
                                            tint = UmeforLime
                                        )
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { frac.coerceIn(0f, 1f) },
                                    color = UmeforLime,
                                    trackColor = Color.White.copy(alpha = 0.18f),
                                    modifier = Modifier.fillMaxWidth().height(2.5.dp)
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                        shadowElevation = 10.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            topLevelTabs.forEach { (route, label, icon) ->
                                val selected = currentRoute == route
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(22.dp))
                                        .clickable {
                                            navController.navigate(route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Icon(
                                            icon,
                                            contentDescription = label,
                                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(21.dp)
                                        )
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = OmniRoutes.LIBRARY,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        ) {
            composable(OmniRoutes.LIBRARY) {
                LibraryScreen(
                    container = container,
                    onOpenBook = { book ->
                        val route = when (book.mediaType) {
                            MediaType.NOVEL -> OmniRoutes.novelReader(book.id, 0)
                            MediaType.COMIC -> OmniRoutes.comicReader(book.id, 0)
                            MediaType.AUDIOBOOK -> OmniRoutes.audioPlayer(book.id, -1)
                        }
                        navController.navigate(route)
                    },
                    onOpenDetail = { itemId -> navController.navigate(OmniRoutes.detail(itemId)) },
                    onOpenDirectUrl = { navController.navigate(OmniRoutes.EXPLORE) },
                    onOpenYoutubeImport = { navController.navigate(OmniRoutes.YOUTUBE_IMPORT) },
                    onOpenStats = { navController.navigate(OmniRoutes.STATS) }
                )
            }
            composable(OmniRoutes.EXPLORE) {
                ExploreScreen(
                    container = container,
                    onOpenPluginManager = { navController.navigate(OmniRoutes.PLUGINS) },
                    onOpenYoutubeImport = { navController.navigate(OmniRoutes.YOUTUBE_IMPORT) },
                    onOpenBookDetail = { itemId -> navController.navigate(OmniRoutes.detail(itemId)) },
                    onOpenSourceBrowser = { url -> navController.navigate(OmniRoutes.sourceBrowser(url)) }
                )
            }
            composable(
                route = OmniRoutes.SOURCE_BROWSER,
                arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "https://truyenqqko.com" })
            ) { entry ->
                val url = entry.arguments?.getString("url").orEmpty()
                SourceWebBrowserScreen(
                    initialUrl = url,
                    container = container,
                    onBack = { navController.popBackStack() },
                    onImportedBook = { itemId ->
                        navController.navigate(OmniRoutes.detail(itemId)) {
                            popUpTo(OmniRoutes.EXPLORE)
                        }
                    }
                )
            }
            composable(OmniRoutes.PLUGINS) {
                PluginManagerScreen(
                    container = container,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(OmniRoutes.QUEUE) {
                QueueScreen(
                    container = container,
                    onSyncNow = {
                        scope.launch { container.syncOrchestrator.triggerImmediateSync(includeMedia = true) }
                    },
                    onOpenSettings = {
                        navController.navigate(OmniRoutes.SETTINGS) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(OmniRoutes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onSignInDrive = onSignInDrive,
                    onSignInDriveBrowser = onSignInDriveBrowser,
                    onSignOutDrive = {
                        scope.launch { container.driveAuthManager.signOut() }
                    },
                    onUpdateYtDlp = {
                        container.youtubeDlInitializer.updateYoutubeDl()
                            .fold(onSuccess = { it }, onFailure = { "Lỗi cập nhật yt-dlp: ${it.message}" })
                    },
                    onOpenPronunciation = { navController.navigate(OmniRoutes.PRONUNCIATION) }
                )
            }
            composable(OmniRoutes.STATS) {
                ReadingStatsScreen(
                    container = container,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(OmniRoutes.PRONUNCIATION) {
                PronunciationGlossaryScreen(
                    container = container,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = OmniRoutes.DETAIL,
                arguments = listOf(navArgument("itemId") { type = NavType.StringType })
            ) { entry ->
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                BookDetailScreen(
                    itemId = itemId,
                    container = container,
                    onBack = { navController.popBackStack() },
                    onOpenChapter = { book, chIdx ->
                        val route = when (book.mediaType) {
                            MediaType.NOVEL -> OmniRoutes.novelReader(book.id, chIdx)
                            MediaType.COMIC -> OmniRoutes.comicReader(book.id, chIdx)
                            MediaType.AUDIOBOOK -> OmniRoutes.audioPlayer(book.id, chIdx)
                        }
                        navController.navigate(route)
                    },
                    onOpenVoiceCast = { id -> navController.navigate(OmniRoutes.voiceCast(id)) },
                    onBatchDownloadWeb = { id -> container.webChapterDownloader.enqueueBatchDownload(id) }
                )
            }
            composable(
                route = OmniRoutes.NOVEL_READER,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("chapterIndex") { type = NavType.IntType }
                )
            ) { entry ->
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                NovelReaderScreen(
                    itemId = itemId,
                    initialChapterIndex = entry.arguments?.getInt("chapterIndex") ?: 0,
                    container = container,
                    onBack = { navController.popBackStack() },
                    onOpenVoiceCast = { id -> navController.navigate(OmniRoutes.voiceCast(id)) },
                    onOpenPronunciation = { navController.navigate(OmniRoutes.PRONUNCIATION) },
                    onEnsureChapterReady = { ch ->
                        container.webChapterDownloader.ensureChapterDownloaded(ch)
                            ?: container.syncOrchestrator.downloadCloudChapterOnDemand(ch)
                    },
                    onForceReloadChapter = { ch ->
                        container.webChapterDownloader.ensureChapterDownloaded(ch, forceReload = true)
                            ?: container.syncOrchestrator.downloadCloudChapterOnDemand(ch)
                    },
                    ttsActiveParagraph = if (ttsState.isActive) ttsState.activeParagraphIndex else -1,
                    ttsIsPlaying = ttsState.isPlaying,
                    ttsIsActive = ttsState.isActive,
                    ttsStatusText = ttsState.statusMessage,
                    onToggleTts = { ch, paras, mode ->
                        container.ttsChapterPlayer.togglePlayOrStart(itemId, ch, paras, mode)
                    },
                    onToggleTtsPause = { container.ttsChapterPlayer.togglePlayPause() },
                    onStopTts = { container.ttsChapterPlayer.stop() },
                    onOpenImportedBook = { newItemId ->
                        navController.navigate(OmniRoutes.detail(newItemId)) {
                            popUpTo(OmniRoutes.LIBRARY) { inclusive = false }
                        }
                    }
                )
            }
            composable(
                route = OmniRoutes.COMIC_READER,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("chapterIndex") { type = NavType.IntType }
                )
            ) { entry ->
                ComicReaderScreen(
                    itemId = entry.arguments?.getString("itemId").orEmpty(),
                    initialChapterIndex = entry.arguments?.getInt("chapterIndex") ?: 0,
                    container = container,
                    onBack = { navController.popBackStack() },
                    onEnsureChapterReady = { ch ->
                        container.webChapterDownloader.ensureChapterDownloaded(ch)
                            ?: container.syncOrchestrator.downloadCloudChapterOnDemand(ch)
                    }
                )
            }
            composable(
                route = OmniRoutes.AUDIO_PLAYER,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("chapterIndex") { type = NavType.IntType }
                )
            ) { entry ->
                AudioPlayerScreen(
                    itemId = entry.arguments?.getString("itemId").orEmpty(),
                    initialChapterIndex = entry.arguments?.getInt("chapterIndex") ?: -1,
                    container = container,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = OmniRoutes.VOICE_CAST,
                arguments = listOf(navArgument("itemId") { type = NavType.StringType })
            ) { entry ->
                VoiceCastStudioScreen(
                    itemId = entry.arguments?.getString("itemId").orEmpty(),
                    container = container,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(OmniRoutes.YOUTUBE_IMPORT) {
                YoutubeImportScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onGoToQueue = {
                        navController.navigate(OmniRoutes.QUEUE) {
                            popUpTo(OmniRoutes.LIBRARY)
                        }
                    }
                )
            }
        }
    }
}
