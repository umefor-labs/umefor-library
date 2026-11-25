package io.lightknight.omniread

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.engine.scraper.ChapterUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OmniReadApp : Application() {
    lateinit var container: OmniAppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = OmniAppContainer(this)
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { container.webHttpClient }))
                }
                .build()
        }
        appScope.launch {
            container.extensionCatalogManager.ensureOfficialRepoAndMigrateLegacy()
            runCatching { container.extensionCatalogManager.refreshAllRepos() }
            container.syncOrchestrator.schedulePeriodicSync()
            val trackerHours = container.settingsRepository.settings.first().chapterCheckIntervalHours
            ChapterUpdateWorker.schedulePeriodicCheck(this@OmniReadApp, trackerHours)
        }
    }
}
