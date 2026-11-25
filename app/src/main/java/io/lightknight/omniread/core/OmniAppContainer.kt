package io.lightknight.omniread.core

import android.content.Context
import android.webkit.WebSettings
import io.lightknight.omniread.data.local.OmniReadDatabase
import io.lightknight.omniread.data.repository.LibraryRepository
import io.lightknight.omniread.data.storage.FileStorageManager
import io.lightknight.omniread.engine.importer.LocalImportManager
import io.lightknight.omniread.engine.importer.MegaPublicDownloader
import io.lightknight.omniread.engine.player.PlaybackController
import io.lightknight.omniread.engine.scraper.CloudflareDohDns
import io.lightknight.omniread.engine.scraper.CloudflareWebViewFetcher
import io.lightknight.omniread.engine.scraper.DirectUrlImporter
import io.lightknight.omniread.engine.scraper.ExtensionCatalogManager
import io.lightknight.omniread.engine.scraper.OkHttpPluginBridge
import io.lightknight.omniread.engine.scraper.PluginSeedManager
import io.lightknight.omniread.engine.scraper.QuickJsPluginRunner
import io.lightknight.omniread.engine.scraper.WebChapterDownloader
import io.lightknight.omniread.engine.scraper.WebCookieJar
import io.lightknight.omniread.engine.sync.DriveAuthManager
import io.lightknight.omniread.engine.sync.DriveRestClient
import io.lightknight.omniread.engine.sync.SyncOrchestrator
import io.lightknight.omniread.engine.sync.WebDavSyncClient
import io.lightknight.omniread.engine.tts.AndroidSystemTtsEngine
import io.lightknight.omniread.engine.tts.CustomHttpTtsEngine
import io.lightknight.omniread.engine.tts.EdgeTtsEngine
import io.lightknight.omniread.engine.ai.BookAiAssistant
import io.lightknight.omniread.engine.tts.GeminiCastClient
import io.lightknight.omniread.engine.tts.TtsChapterPlayer
import io.lightknight.omniread.engine.tts.TtsEngineRegistry
import io.lightknight.omniread.engine.youtube.YoutubeAudioDownloader
import io.lightknight.omniread.engine.youtube.YoutubeDlInitializer
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container avoiding Hilt bytecode transforms on AGP 9.
 * Extended incrementally across Phases 1–5.
 */
class OmniAppContainer(val appContext: Context) {
    val secureKeyStore: SecureKeyStore by lazy { SecureKeyStore(appContext) }
    val settingsRepository: AppSettingsRepository by lazy {
        AppSettingsRepository(appContext, secureKeyStore)
    }
    val storageManager: FileStorageManager by lazy { FileStorageManager(appContext) }
    val database: OmniReadDatabase by lazy { OmniReadDatabase.build(appContext) }
    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(database, storageManager, settingsRepository)
    }
    val localImportManager: LocalImportManager by lazy {
        LocalImportManager(appContext, storageManager, libraryRepository)
    }
    val playbackController: PlaybackController by lazy {
        PlaybackController(appContext, libraryRepository)
    }
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val androidTtsEngine: AndroidSystemTtsEngine by lazy { AndroidSystemTtsEngine(appContext) }
    val edgeTtsEngine: EdgeTtsEngine by lazy { EdgeTtsEngine(httpClient) }
    val customHttpTtsEngine: CustomHttpTtsEngine by lazy {
        CustomHttpTtsEngine(httpClient, settingsRepository)
    }
    val ttsEngineRegistry: TtsEngineRegistry by lazy {
        TtsEngineRegistry(
            engines = listOf(edgeTtsEngine, androidTtsEngine, customHttpTtsEngine),
            fallbackEngine = androidTtsEngine
        )
    }
    val geminiCastClient: GeminiCastClient by lazy {
        GeminiCastClient(httpClient, settingsRepository)
    }
    val bookAiAssistant: BookAiAssistant by lazy {
        BookAiAssistant(httpClient, settingsRepository)
    }
    val ttsChapterPlayer: TtsChapterPlayer by lazy {
        TtsChapterPlayer(
            context = appContext,
            repository = libraryRepository,
            storage = storageManager,
            settingsRepo = settingsRepository,
            ttsRegistry = ttsEngineRegistry,
            geminiCastClient = geminiCastClient
        )
    }

    val webUserAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(appContext) }
            .getOrDefault(OkHttpPluginBridge.DEFAULT_MOBILE_UA)
    }
    val webCookieJar: WebCookieJar by lazy { WebCookieJar() }
    val webHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .dns(CloudflareDohDns())
            .cookieJar(webCookieJar)
            .build()
    }
    val cloudflareWebViewFetcher: CloudflareWebViewFetcher by lazy {
        CloudflareWebViewFetcher(appContext)
    }
    val pluginHttpBridge: OkHttpPluginBridge by lazy {
        OkHttpPluginBridge(webHttpClient, cloudflareWebViewFetcher, webUserAgent)
    }
    val pluginRunner: QuickJsPluginRunner by lazy { QuickJsPluginRunner(pluginHttpBridge) }
    val pluginSeedManager: PluginSeedManager by lazy {
        PluginSeedManager(appContext, database.sourcePluginDao())
    }
    val extensionCatalogManager: ExtensionCatalogManager by lazy {
        ExtensionCatalogManager(
            context = appContext,
            pluginDao = database.sourcePluginDao(),
            repoDao = database.extensionRepoDao(),
            libraryDao = database.libraryDao(),
            httpClient = httpClient
        )
    }
    val directUrlImporter: DirectUrlImporter by lazy {
        DirectUrlImporter(pluginHttpBridge, storageManager, libraryRepository)
    }
    val megaPublicDownloader: MegaPublicDownloader by lazy {
        MegaPublicDownloader(httpClient)
    }
    val webChapterDownloader: WebChapterDownloader by lazy {
        WebChapterDownloader(
            context = appContext,
            httpClient = webHttpClient,
            pluginRunner = pluginRunner,
            repository = libraryRepository,
            storage = storageManager,
            localImportManager = localImportManager,
            megaDownloader = megaPublicDownloader,
            userAgent = webUserAgent
        )
    }

    val youtubeDlInitializer: YoutubeDlInitializer by lazy {
        YoutubeDlInitializer(appContext, settingsRepository)
    }
    val youtubeAudioDownloader: YoutubeAudioDownloader by lazy {
        YoutubeAudioDownloader(appContext, youtubeDlInitializer, httpClient, storageManager, libraryRepository)
    }

    val driveAuthManager: DriveAuthManager by lazy {
        DriveAuthManager(appContext, secureKeyStore, settingsRepository, httpClient)
    }
    val driveRestClient: DriveRestClient by lazy { DriveRestClient(httpClient) }
    val webDavSyncClient: WebDavSyncClient by lazy { WebDavSyncClient(httpClient) }
    val syncOrchestrator: SyncOrchestrator by lazy {
        SyncOrchestrator(
            context = appContext,
            db = database,
            storage = storageManager,
            settingsRepo = settingsRepository,
            authManager = driveAuthManager,
            driveClient = driveRestClient,
            webDavClient = webDavSyncClient,
            importManager = localImportManager
        )
    }
}
