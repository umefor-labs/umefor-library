package io.lightknight.omniread.engine.scraper

import android.content.Context
import io.lightknight.omniread.data.local.SourcePluginDao

/**
 * Legacy helper retained only for explicit maintenance cleanup.
 * Sources are now distributed via [ExtensionCatalogManager] instead of being auto-seeded on fresh installs.
 */
class PluginSeedManager(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    private val pluginDao: SourcePluginDao
) {
    suspend fun purgeRetiredStarters() {
        listOf("vn_comic_madara", "tve4u").forEach { retiredId ->
            if (pluginDao.getById(retiredId) != null) {
                pluginDao.deleteById(retiredId)
            }
        }
    }
}
