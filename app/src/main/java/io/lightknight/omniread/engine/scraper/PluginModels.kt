package io.lightknight.omniread.engine.scraper

import io.lightknight.omniread.data.model.MediaType
import kotlinx.serialization.Serializable

@Serializable
data class RemoteBookSummary(
    val title: String,
    val url: String,
    val coverUrl: String = "",
    val author: String = ""
)

@Serializable
data class RemoteBookDetail(
    val title: String,
    val url: String,
    val coverUrl: String = "",
    val author: String = "",
    val synopsis: String = "",
    val mediaType: String = "NOVEL"
)

@Serializable
data class RemoteChapter(
    val title: String,
    val url: String,
    val chapterIndex: Int = 0
)

@Serializable
data class ImagePage(
    val url: String,
    val referer: String = ""
)

@Serializable
data class RemoteChapterPayload(
    val kind: String = "NOVEL", // NOVEL | COMIC
    val paragraphs: List<String> = emptyList(),
    val images: List<ImagePage> = emptyList()
)

data class StarterPluginSpec(
    val id: String,
    val name: String,
    val mediaType: MediaType,
    val lang: String,
    val baseUrl: String,
    val assetFileName: String
)

interface PluginHttpBridge {
    suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String
    suspend fun httpPost(url: String, body: String, headers: Map<String, String> = emptyMap()): String
    suspend fun webViewGet(url: String, waitMs: Long = 2500L): String
}
