package io.lightknight.omniread.engine.scraper

import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.data.local.SourcePluginEntity
import io.lightknight.omniread.data.model.MediaType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExtensionRepoIndex(
    val schemaVersion: Int = 1,
    val repoName: String = "",
    val repoDescription: String = "",
    val generatedAt: String = "",
    val issueTemplateUrl: String = "",
    val extensions: List<ExtensionCatalogEntry> = emptyList()
)

@Serializable
data class ExtensionCatalogEntry(
    val id: String,
    val name: String,
    val mediaType: String = "NOVEL",
    val lang: String = "vi",
    val version: Int = 1,
    val minAppApi: Int = 1,
    val baseUrl: String,
    val description: String = "",
    val scriptPath: String = "",
    val scriptUrl: String = "",
    val sha256: String = "",
    val iconUrl: String = "",
    val changelog: String = "",
    val nsfw: Boolean = false
) {
    fun mediaTypeEnum(): MediaType =
        runCatching { MediaType.valueOf(mediaType.uppercase()) }.getOrDefault(MediaType.NOVEL)
}

enum class ExtensionInstallState {
    AVAILABLE,
    INSTALLED,
    UPDATE_AVAILABLE,
    INCOMPATIBLE
}

data class CatalogExtensionItem(
    val entry: ExtensionCatalogEntry,
    val repoId: String,
    val repoName: String,
    val repoIndexUrl: String,
    val repoMirrorUrl: String,
    val installedPlugin: SourcePluginEntity?,
    val state: ExtensionInstallState
)

data class RepoRefreshSummary(
    val refreshedRepos: Int,
    val failedRepos: Int,
    val errors: List<String>
)

object ExtensionCatalogHelpers {
    const val CURRENT_PLUGIN_API = 1

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseIndexJson(raw: String): ExtensionRepoIndex {
        return json.decodeFromString<ExtensionRepoIndex>(raw)
    }

    fun resolveScriptUrl(
        indexUrl: String,
        scriptPath: String,
        explicitScriptUrl: String = ""
    ): String {
        if (explicitScriptUrl.isNotBlank()) return explicitScriptUrl.trim()
        val cleanBase = indexUrl.trim().substringBefore('?').substringBeforeLast('/')
        val cleanPath = scriptPath.trim().trimStart('/')
        return if (cleanBase.isBlank()) cleanPath else "$cleanBase/$cleanPath"
    }

    fun computeNormalizedSha256(script: String): String {
        val normalized = script.replace("\r\n", "\n")
        return HashUtils.sha256(normalized)
    }

    fun verifySha256(script: String, expectedSha256: String): Boolean {
        if (expectedSha256.isBlank()) return true
        return computeNormalizedSha256(script).equals(expectedSha256.trim(), ignoreCase = true)
    }

    fun computeInstallState(
        entry: ExtensionCatalogEntry,
        installed: SourcePluginEntity?,
        currentAppApi: Int = CURRENT_PLUGIN_API
    ): ExtensionInstallState {
        if (entry.minAppApi > currentAppApi) {
            return ExtensionInstallState.INCOMPATIBLE
        }
        if (installed == null) {
            return ExtensionInstallState.AVAILABLE
        }
        return if (entry.version > installed.version) {
            ExtensionInstallState.UPDATE_AVAILABLE
        } else {
            ExtensionInstallState.INSTALLED
        }
    }
}
