package io.lightknight.omniread.engine.scraper

import android.content.Context
import io.lightknight.omniread.data.local.ExtensionRepoDao
import io.lightknight.omniread.data.local.ExtensionRepoEntity
import io.lightknight.omniread.data.local.LibraryDao
import io.lightknight.omniread.data.local.SourcePluginDao
import io.lightknight.omniread.data.local.SourcePluginEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

class ExtensionCatalogManager(
    private val context: Context,
    private val pluginDao: SourcePluginDao,
    private val repoDao: ExtensionRepoDao,
    private val libraryDao: LibraryDao,
    private val httpClient: OkHttpClient
) {
    private val bundledIndexCache: ExtensionRepoIndex by lazy { loadBundledCatalogIndex() }

    suspend fun ensureOfficialRepoAndMigrateLegacy() {
        val existingOfficial = repoDao.getById(OFFICIAL_REPO_ID)
        if (existingOfficial == null) {
            repoDao.upsert(
                ExtensionRepoEntity(
                    id = OFFICIAL_REPO_ID,
                    name = OFFICIAL_REPO_NAME,
                    indexUrl = OFFICIAL_INDEX_URL,
                    mirrorIndexUrl = OFFICIAL_MIRROR_INDEX_URL,
                    websiteUrl = OFFICIAL_WEBSITE_URL,
                    issueUrl = OFFICIAL_REQUEST_ISSUE_URL,
                    isOfficial = true,
                    enabled = true
                )
            )
        } else if (
            existingOfficial.indexUrl != OFFICIAL_INDEX_URL ||
            existingOfficial.mirrorIndexUrl != OFFICIAL_MIRROR_INDEX_URL ||
            existingOfficial.issueUrl != OFFICIAL_REQUEST_ISSUE_URL
        ) {
            repoDao.upsert(
                existingOfficial.copy(
                    name = OFFICIAL_REPO_NAME,
                    indexUrl = OFFICIAL_INDEX_URL,
                    mirrorIndexUrl = OFFICIAL_MIRROR_INDEX_URL,
                    websiteUrl = OFFICIAL_WEBSITE_URL,
                    issueUrl = OFFICIAL_REQUEST_ISSUE_URL,
                    isOfficial = true
                )
            )
        }

        // Remove retired broken starter IDs if present from legacy installs.
        listOf("vn_comic_madara", "tve4u").forEach { retiredId ->
            if (pluginDao.getById(retiredId) != null) {
                pluginDao.deleteById(retiredId)
            }
        }

        // Link existing installed plugins on upgrade to official repo so they receive live updates.
        // Do NOT auto-install anything on fresh installs (source_plugins stays empty).
        val officialIds = bundledIndexCache.extensions.associateBy { it.id }
        val existingPlugins = pluginDao.getAll()
        val toLink = existingPlugins.mapNotNull { plugin ->
            val match = officialIds[plugin.id]
            if (match != null && plugin.repoId.isBlank()) {
                plugin.copy(
                    repoId = OFFICIAL_REPO_ID,
                    description = plugin.description.ifBlank { match.description },
                    iconUrl = plugin.iconUrl.ifBlank { match.iconUrl },
                    scriptSha256 = plugin.scriptSha256.ifBlank {
                        ExtensionCatalogHelpers.computeNormalizedSha256(plugin.scriptContent)
                    }
                )
            } else {
                null
            }
        }
        if (toLink.isNotEmpty()) {
            pluginDao.upsertAll(toLink)
        }
    }

    fun loadBundledCatalogIndex(): ExtensionRepoIndex {
        return runCatching {
            val raw = context.assets.open(BUNDLED_INDEX_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            ExtensionCatalogHelpers.parseIndexJson(raw)
        }.getOrDefault(ExtensionRepoIndex(repoName = OFFICIAL_REPO_NAME))
    }

    fun observeCatalog(): Flow<List<CatalogExtensionItem>> {
        return combine(repoDao.observeAll(), pluginDao.observeAll()) { repos, installedList ->
            val installedMap = installedList.associateBy { it.id }
            val seenIds = mutableSetOf<String>()
            val items = mutableListOf<CatalogExtensionItem>()

            val effectiveRepos = repos.filter { it.enabled }.ifEmpty {
                listOf(
                    ExtensionRepoEntity(
                        id = OFFICIAL_REPO_ID,
                        name = OFFICIAL_REPO_NAME,
                        indexUrl = OFFICIAL_INDEX_URL,
                        mirrorIndexUrl = OFFICIAL_MIRROR_INDEX_URL,
                        websiteUrl = OFFICIAL_WEBSITE_URL,
                        issueUrl = OFFICIAL_REQUEST_ISSUE_URL,
                        isOfficial = true,
                        enabled = true
                    )
                )
            }

            for (repo in effectiveRepos) {
                val parsed = when {
                    repo.cachedIndexJson.isNotBlank() ->
                        runCatching { ExtensionCatalogHelpers.parseIndexJson(repo.cachedIndexJson) }.getOrNull()
                    repo.isOfficial -> bundledIndexCache
                    else -> null
                } ?: continue

                for (entry in parsed.extensions) {
                    if (!seenIds.add(entry.id)) continue
                    val installed = installedMap[entry.id]
                    val state = ExtensionCatalogHelpers.computeInstallState(entry, installed)
                    items += CatalogExtensionItem(
                        entry = entry,
                        repoId = repo.id,
                        repoName = parsed.repoName.ifBlank { repo.name },
                        repoIndexUrl = repo.indexUrl,
                        repoMirrorUrl = repo.mirrorIndexUrl,
                        installedPlugin = installed,
                        state = state
                    )
                }
            }

            items.sortedWith(
                compareBy<CatalogExtensionItem> {
                    when (it.state) {
                        ExtensionInstallState.UPDATE_AVAILABLE -> 0
                        ExtensionInstallState.INSTALLED -> 1
                        ExtensionInstallState.AVAILABLE -> 2
                        ExtensionInstallState.INCOMPATIBLE -> 3
                    }
                }.thenBy { it.entry.name.lowercase() }
            )
        }
    }

    suspend fun refreshAllRepos(): RepoRefreshSummary = withContext(Dispatchers.IO) {
        val repos = repoDao.getEnabled()
        var refreshed = 0
        var failed = 0
        val errors = mutableListOf<String>()

        for (repo in repos) {
            val urls = listOf(repo.indexUrl, repo.mirrorIndexUrl).filter { it.isNotBlank() }
            var rawJson: String? = null
            var lastErr = ""

            for (url in urls) {
                val res = fetchText(url)
                if (res.isSuccess) {
                    val body = res.getOrThrow()
                    val parseCheck = runCatching { ExtensionCatalogHelpers.parseIndexJson(body) }
                    if (parseCheck.isSuccess) {
                        rawJson = body
                        break
                    } else {
                        lastErr = "File index.json không hợp lệ: ${parseCheck.exceptionOrNull()?.message}"
                    }
                } else {
                    lastErr = res.exceptionOrNull()?.message ?: "Lỗi kết nối kho"
                }
            }

            if (rawJson != null) {
                val parsed = ExtensionCatalogHelpers.parseIndexJson(rawJson)
                repoDao.upsert(
                    repo.copy(
                        name = parsed.repoName.ifBlank { repo.name },
                        issueUrl = parsed.issueTemplateUrl.ifBlank { repo.issueUrl },
                        lastSyncedAt = System.currentTimeMillis(),
                        cachedIndexJson = rawJson,
                        lastError = ""
                    )
                )
                refreshed++
            } else {
                repoDao.upsert(repo.copy(lastError = lastErr.take(220)))
                failed++
                errors += "${repo.name}: $lastErr"
            }
        }

        RepoRefreshSummary(refreshedRepos = refreshed, failedRepos = failed, errors = errors)
    }

    suspend fun addCustomRepo(name: String, indexUrl: String): Result<ExtensionRepoEntity> =
        withContext(Dispatchers.IO) {
            val cleanUrl = indexUrl.trim()
            if (!cleanUrl.startsWith("https://", ignoreCase = true) &&
                !cleanUrl.startsWith("http://", ignoreCase = true)
            ) {
                return@withContext Result.failure(IllegalArgumentException("URL kho repo phải bắt đầu bằng https://"))
            }

            val fetchRes = fetchText(cleanUrl)
            if (fetchRes.isFailure) {
                return@withContext Result.failure(
                    fetchRes.exceptionOrNull() ?: IllegalStateException("Không tải được $cleanUrl")
                )
            }
            val raw = fetchRes.getOrThrow()
            val parsed = runCatching { ExtensionCatalogHelpers.parseIndexJson(raw) }.getOrElse { err ->
                return@withContext Result.failure(
                    IllegalStateException("Định dạng index.json không hợp lệ: ${err.message}")
                )
            }

            val repo = ExtensionRepoEntity(
                id = "repo_" + UUID.randomUUID().toString().take(8),
                name = name.trim().ifBlank { parsed.repoName.ifBlank { cleanUrl.substringAfter("://").substringBefore('/') } },
                indexUrl = cleanUrl,
                mirrorIndexUrl = "",
                websiteUrl = cleanUrl.substringBeforeLast('/'),
                issueUrl = parsed.issueTemplateUrl,
                isOfficial = false,
                enabled = true,
                lastSyncedAt = System.currentTimeMillis(),
                cachedIndexJson = raw,
                lastError = ""
            )
            repoDao.upsert(repo)
            Result.success(repo)
        }

    suspend fun setRepoEnabled(repoId: String, enabled: Boolean) {
        val repo = repoDao.getById(repoId) ?: return
        repoDao.upsert(repo.copy(enabled = enabled))
    }

    suspend fun removeRepo(repoId: String): Boolean {
        val repo = repoDao.getById(repoId) ?: return false
        if (repo.isOfficial) return false
        repoDao.deleteById(repoId)
        return true
    }

    suspend fun installOrUpdate(item: CatalogExtensionItem): Result<SourcePluginEntity> =
        withContext(Dispatchers.IO) {
            val entry = item.entry
            if (entry.minAppApi > ExtensionCatalogHelpers.CURRENT_PLUGIN_API) {
                return@withContext Result.failure(
                    IllegalStateException("Extension này yêu cầu Umefor Library API v${entry.minAppApi}. Hãy cập nhật app trước.")
                )
            }

            val primaryUrl = ExtensionCatalogHelpers.resolveScriptUrl(
                indexUrl = item.repoIndexUrl,
                scriptPath = entry.scriptPath,
                explicitScriptUrl = entry.scriptUrl
            )
            val mirrorUrl = if (item.repoMirrorUrl.isNotBlank() && entry.scriptPath.isNotBlank()) {
                ExtensionCatalogHelpers.resolveScriptUrl(item.repoMirrorUrl, entry.scriptPath)
            } else ""

            var scriptContent: String? = null
            var lastErr: Throwable? = null

            for (url in listOf(primaryUrl, mirrorUrl).filter { it.isNotBlank() }) {
                val res = fetchText(url)
                if (res.isSuccess && res.getOrThrow().isNotBlank()) {
                    scriptContent = res.getOrThrow()
                    break
                } else {
                    lastErr = res.exceptionOrNull()
                }
            }

            // Offline fallback from bundled snapshot if official repo & network unavailable
            if (scriptContent == null && item.repoId == OFFICIAL_REPO_ID) {
                scriptContent = runCatching {
                    context.assets.open("plugins/${entry.id}.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrNull()
            }

            val rawScript = scriptContent ?: return@withContext Result.failure(
                lastErr ?: IllegalStateException("Không tải được script của ${entry.name}")
            )

            if (!ExtensionCatalogHelpers.verifySha256(rawScript, entry.sha256)) {
                return@withContext Result.failure(
                    IllegalStateException("Mã kiểm tra SHA-256 của ${entry.name} không khớp. Đã hủy cài đặt để đảm bảo an toàn.")
                )
            }

            val computedSha = ExtensionCatalogHelpers.computeNormalizedSha256(rawScript)
            val existing = pluginDao.getById(entry.id)
            val entity = SourcePluginEntity(
                id = entry.id,
                name = entry.name,
                mediaType = entry.mediaTypeEnum(),
                lang = entry.lang,
                version = entry.version,
                baseUrl = entry.baseUrl.trimEnd('/'),
                scriptContent = rawScript,
                enabled = existing?.enabled ?: true,
                isStarter = (item.repoId == OFFICIAL_REPO_ID),
                repoId = item.repoId,
                description = entry.description,
                iconUrl = entry.iconUrl,
                scriptSha256 = computedSha,
                updatedAt = System.currentTimeMillis()
            )
            pluginDao.upsert(entity)
            Result.success(entity)
        }

    suspend fun updateAll(items: List<CatalogExtensionItem>): Pair<Int, Int> {
        var updated = 0
        var failed = 0
        for (item in items) {
            if (item.state == ExtensionInstallState.UPDATE_AVAILABLE) {
                val res = installOrUpdate(item)
                if (res.isSuccess) updated++ else failed++
            }
        }
        return updated to failed
    }

    suspend fun uninstall(pluginId: String) {
        pluginDao.deleteById(pluginId)
    }

    suspend fun countLinkedBooks(pluginId: String): Int {
        return libraryDao.countBySourcePlugin(pluginId)
    }

    private fun fetchText(url: String): Result<String> {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Cache-Control", "no-cache")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code} khi tải $url")
                }
                resp.body?.string().orEmpty()
            }
        }
    }

    companion object {
        const val OFFICIAL_REPO_ID = "umefor_official"
        const val OFFICIAL_REPO_NAME = "Umefor Library Official Extensions"
        const val OFFICIAL_INDEX_URL =
            "https://raw.githubusercontent.com/umefor-labs/umefor-library-extensions/main/index.json"
        const val OFFICIAL_MIRROR_INDEX_URL =
            "https://cdn.jsdelivr.net/gh/umefor-labs/umefor-library-extensions@main/index.json"
        const val OFFICIAL_WEBSITE_URL =
            "https://github.com/umefor-labs/umefor-library-extensions"
        const val OFFICIAL_REQUEST_ISSUE_URL =
            "https://github.com/umefor-labs/umefor-library-extensions/issues/new?template=request-source.yml"
        const val OFFICIAL_BROKEN_ISSUE_URL =
            "https://github.com/umefor-labs/umefor-library-extensions/issues/new?template=broken-source.yml"
        const val BUNDLED_INDEX_ASSET = "extension_catalog/index.json"
    }
}
