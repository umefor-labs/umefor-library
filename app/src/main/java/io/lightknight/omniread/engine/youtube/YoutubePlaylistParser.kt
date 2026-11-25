package io.lightknight.omniread.engine.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class YoutubeTrackEntry(
    val id: String,
    val title: String,
    val url: String,
    val durationSec: Long,
    val thumbnailUrl: String = ""
)

data class YoutubeEmbeddedChapter(
    val title: String,
    val startTimeSec: Double,
    val endTimeSec: Double
)

data class YoutubeImportPreview(
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val sourceUrl: String,
    val isPlaylist: Boolean,
    val entries: List<YoutubeTrackEntry>,
    val embeddedChapters: List<YoutubeEmbeddedChapter> = emptyList()
)

object YoutubePlaylistParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseDumpJson(rawJson: String, fallbackUrl: String): YoutubeImportPreview {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val type = root["_type"]?.jsonPrimitive?.content.orEmpty()
        val entriesArr = root["entries"]?.jsonArray

        val rootTitle = root["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Sách nói YouTube" }
        val uploader = root["uploader"]?.jsonPrimitive?.content
            ?: root["channel"]?.jsonPrimitive?.content.orEmpty()
        val rootThumb = extractBestThumbnail(root)

        if (type == "playlist" || (entriesArr != null && entriesArr.isNotEmpty())) {
            val tracks = entriesArr.orEmpty().mapIndexedNotNull { idx, elem ->
                val obj = elem.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
                val title = obj["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Tập ${idx + 1}" }
                if (title == "[Private video]" || title == "[Deleted video]") return@mapIndexedNotNull null
                val rawUrl = obj["url"]?.jsonPrimitive?.content
                    ?: obj["webpage_url"]?.jsonPrimitive?.content
                    ?: if (id.isNotBlank()) "https://www.youtube.com/watch?v=$id" else ""
                val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "https://www.youtube.com/watch?v=$id"
                val dur = obj["duration"]?.jsonPrimitive?.longOrNull
                    ?: obj["duration"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
                val thumb = extractBestThumbnail(obj).ifBlank {
                    if (id.isNotBlank()) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else ""
                }
                YoutubeTrackEntry(
                    id = id.ifBlank { "track_$idx" },
                    title = title,
                    url = fullUrl,
                    durationSec = dur,
                    thumbnailUrl = thumb
                )
            }
            return YoutubeImportPreview(
                title = rootTitle,
                uploader = uploader,
                thumbnailUrl = rootThumb.ifBlank { tracks.firstOrNull()?.thumbnailUrl.orEmpty() },
                sourceUrl = fallbackUrl,
                isPlaylist = true,
                entries = tracks
            )
        }

        // Single video (optionally with embedded chapters)
        val vidId = root["id"]?.jsonPrimitive?.content.orEmpty()
        val dur = root["duration"]?.jsonPrimitive?.longOrNull
            ?: root["duration"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
        val webpageUrl = root["webpage_url"]?.jsonPrimitive?.content ?: fallbackUrl
        val chapters = root["chapters"]?.jsonArray.orEmpty().mapIndexed { idx, chEl ->
            val co = chEl.jsonObject
            YoutubeEmbeddedChapter(
                title = co["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Chương ${idx + 1}" },
                startTimeSec = co["start_time"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                endTimeSec = co["end_time"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            )
        }

        return YoutubeImportPreview(
            title = rootTitle,
            uploader = uploader,
            thumbnailUrl = rootThumb.ifBlank {
                if (vidId.isNotBlank()) "https://i.ytimg.com/vi/$vidId/hqdefault.jpg" else ""
            },
            sourceUrl = webpageUrl,
            isPlaylist = false,
            entries = listOf(
                YoutubeTrackEntry(
                    id = vidId.ifBlank { "single" },
                    title = rootTitle,
                    url = webpageUrl,
                    durationSec = dur,
                    thumbnailUrl = rootThumb
                )
            ),
            embeddedChapters = chapters
        )
    }

    private fun extractBestThumbnail(obj: kotlinx.serialization.json.JsonObject): String {
        val direct = obj["thumbnail"]?.jsonPrimitive?.content.orEmpty()
        if (direct.startsWith("http")) return direct
        val arr = obj["thumbnails"]?.jsonArray ?: return ""
        return arr.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content.orEmpty()
    }
}
