package io.lightknight.omniread.engine.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlin.math.abs

data class InnerTubeAudioStream(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val contentLength: Long,
    val extension: String,
    val userAgent: String,
    val isDefaultTrack: Boolean = true
)

internal data class InnerTubeClientProfile(
    val name: String,
    val userAgent: String,
    val clientJson: JsonObject
)

class InnerTubeAudioExtractor(
    private val httpClient: OkHttpClient
) {
    suspend fun downloadAudio(
        trackUrl: String,
        tempOut: File,
        trackIndex: Int,
        preferredFormat: String,
        preferredQuality: String,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(trackUrl)
            ?: throw IllegalArgumentException("Không trích xuất được YouTube Video ID từ URL: $trackUrl")

        onProgress(0f, "Đang lấy luồng audio trực tiếp (InnerTube)...")
        val streams = fetchAudioStreams(videoId)
        val chosen = selectBestAudioStream(streams, preferredFormat, preferredQuality)
            ?: throw IllegalStateException("Không tìm thấy luồng âm thanh thuần từ YouTube InnerTube cho $videoId")

        tempOut.mkdirs()
        val destFile = File(tempOut, "track_%04d.%s".format(Locale.US, trackIndex, chosen.extension))
        val partFile = File(tempOut, "track_%04d.%s.part".format(Locale.US, trackIndex, chosen.extension))
        if (partFile.exists()) partFile.delete()
        if (destFile.exists()) destFile.delete()

        downloadAudioStream(chosen, partFile, onProgress)
        verifyDownloadedAudio(partFile)

        if (!partFile.renameTo(destFile)) {
            partFile.copyTo(destFile, overwrite = true)
            partFile.delete()
        }
        destFile
    }

    private fun fetchAudioStreams(videoId: String): List<InnerTubeAudioStream> {
        var lastError: String? = null
        for (profile in CLIENT_PROFILES) {
            val result = runCatching {
                val payload = buildJsonObject {
                    put("videoId", videoId)
                    put("context", buildJsonObject {
                        put("client", profile.clientJson)
                    })
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }.toString()

                val req = Request.Builder()
                    .url(PLAYER_ENDPOINT)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", profile.userAgent)
                    .header("Accept", "application/json")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val bodyText = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IOException("InnerTube ${profile.name} HTTP ${resp.code}")
                    }
                    val streams = parseAdaptiveAudioStreams(bodyText, profile.userAgent)
                    if (streams.isEmpty()) {
                        val reason = extractPlayabilityReason(bodyText)
                        throw IllegalStateException(
                            if (reason.isNotBlank()) "${profile.name}: $reason" else "${profile.name}: không có luồng audio"
                        )
                    }
                    streams
                }
            }
            if (result.isSuccess) {
                return result.getOrThrow()
            }
            lastError = result.exceptionOrNull()?.message
        }
        throw IllegalStateException(lastError ?: "Không thể lấy luồng âm thanh từ YouTube.")
    }

    private fun downloadAudioStream(
        stream: InnerTubeAudioStream,
        partFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        if (stream.contentLength > 0L) {
            downloadChunkedRange(stream, partFile, stream.contentLength, onProgress)
        } else {
            downloadSingleGet(stream, partFile, onProgress)
        }
    }

    private fun downloadChunkedRange(
        stream: InnerTubeAudioStream,
        partFile: File,
        totalBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        val totalMb = totalBytes / (1024.0 * 1024.0)
        FileOutputStream(partFile).use { out ->
            var offset = 0L
            val buffer = ByteArray(16 * 1024)
            while (offset < totalBytes) {
                val end = minOf(offset + CHUNK_BYTES - 1L, totalBytes - 1L)
                val expectedLen = (end - offset + 1L)
                var attempt = 0
                var chunkWritten = 0L
                var lastErr: Exception? = null

                while (attempt < MAX_CHUNK_RETRIES && chunkWritten < expectedLen) {
                    attempt++
                    val chunkStart = offset + chunkWritten
                    val req = Request.Builder()
                        .url(stream.url)
                        .header("User-Agent", stream.userAgent)
                        .header("Range", "bytes=$chunkStart-$end")
                        .build()
                    try {
                        httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful && resp.code != 206) {
                                throw IOException("HTTP ${resp.code} khi tải mảnh audio")
                            }
                            val input = resp.body?.byteStream() ?: throw IOException("Body trống")
                            input.use { streamIn ->
                                while (true) {
                                    val read = streamIn.read(buffer)
                                    if (read == -1) break
                                    out.write(buffer, 0, read)
                                    chunkWritten += read
                                    val doneBytes = offset + chunkWritten
                                    val pct = ((doneBytes.toDouble() / totalBytes.toDouble()) * 100.0)
                                        .toFloat()
                                        .coerceIn(0f, 100f)
                                    val doneMb = doneBytes / (1024.0 * 1024.0)
                                    onProgress(
                                        pct,
                                        String.format(Locale.US, "Đã tải %.1f / %.1f MB (audio-only)", doneMb, totalMb)
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }

                if (chunkWritten == 0L && lastErr != null) {
                    throw lastErr
                }
                if (chunkWritten <= 0L) {
                    throw IOException("Kết nối bị ngắt giữa chừng khi tải mảnh audio tại byte $offset")
                }
                offset += chunkWritten
            }
            out.flush()
        }
    }

    private fun downloadSingleGet(
        stream: InnerTubeAudioStream,
        partFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        val req = Request.Builder()
            .url(stream.url)
            .header("User-Agent", stream.userAgent)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} khi tải audio")
            }
            val body = resp.body ?: throw IOException("Body trống")
            val contentLen = body.contentLength()
            FileOutputStream(partFile).use { out ->
                val buffer = ByteArray(16 * 1024)
                var downloaded = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        val pct = if (contentLen > 0L) {
                            ((downloaded.toDouble() / contentLen.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
                        } else {
                            0f
                        }
                        val doneMb = downloaded / (1024.0 * 1024.0)
                        onProgress(pct, String.format(Locale.US, "Đã tải %.1f MB (audio-only)", doneMb))
                    }
                }
                out.flush()
            }
        }
    }

    private fun verifyDownloadedAudio(file: File) {
        if (!file.exists() || file.length() <= MIN_AUDIO_BYTES) {
            throw IOException("Tệp âm thanh tải về quá nhỏ hoặc rỗng (${file.length()} bytes).")
        }
        val head = ByteArray(64)
        val n = file.inputStream().use { it.read(head) }.coerceAtLeast(0)
        val headText = String(head, 0, n, Charsets.UTF_8).trimStart().lowercase(Locale.US)
        if (headText.startsWith("<!doctype") || headText.startsWith("<html")) {
            throw IOException("Máy chủ trả về trang HTML thay vì dữ liệu âm thanh.")
        }
    }

    companion object {
        private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CHUNK_BYTES = 2L * 1024L * 1024L
        private const val MAX_CHUNK_RETRIES = 3
        private const val MIN_AUDIO_BYTES = 1024L

        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
        private val jsonParser = Json { ignoreUnknownKeys = true }

        internal val CLIENT_PROFILES = listOf(
            InnerTubeClientProfile(
                name = "IOS",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                clientJson = buildJsonObject {
                    put("clientName", "IOS")
                    put("clientVersion", "20.10.4")
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone")
                    put("osVersion", "18.3.2.22D82")
                }
            ),
            InnerTubeClientProfile(
                name = "ANDROID",
                userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
                clientJson = buildJsonObject {
                    put("clientName", "ANDROID")
                    put("clientVersion", "20.10.38")
                    put("osName", "Android")
                    put("osVersion", "14")
                    put("androidSdkVersion", 34)
                }
            )
        )

        fun extractVideoId(url: String): String? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            if (VIDEO_ID_REGEX.matches(trimmed)) return trimmed

            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val host = uri.host?.lowercase(Locale.US).orEmpty()
            val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }

            if (host.contains("youtu.be")) {
                val cand = pathSegments.firstOrNull().orEmpty()
                return cand.takeIf { VIDEO_ID_REGEX.matches(it) }
            }

            if (host.contains("youtube.com") || host.contains("youtube-nocookie.com")) {
                val vParam = parseQueryParam(uri.rawQuery, "v")
                if (!vParam.isNullOrBlank() && VIDEO_ID_REGEX.matches(vParam)) {
                    return vParam
                }
                if (pathSegments.size >= 2 && pathSegments[0] in setOf("shorts", "embed", "live", "v")) {
                    val cand = pathSegments[1]
                    if (VIDEO_ID_REGEX.matches(cand)) return cand
                }
            }
            return null
        }

        private fun parseQueryParam(rawQuery: String?, key: String): String? {
            if (rawQuery.isNullOrBlank()) return null
            for (part in rawQuery.split('&')) {
                val eq = part.indexOf('=')
                if (eq > 0 && part.substring(0, eq) == key) {
                    return part.substring(eq + 1)
                }
            }
            return null
        }

        fun extractPlayabilityReason(jsonText: String): String {
            return runCatching {
                val root = jsonParser.parseToJsonElement(jsonText).jsonObject
                val ps = root["playabilityStatus"]?.jsonObject ?: return@runCatching ""
                val status = ps["status"]?.jsonPrimitive?.content.orEmpty()
                val reason = ps["reason"]?.jsonPrimitive?.content.orEmpty()
                listOf(status, reason).filter { it.isNotBlank() }.joinToString(": ")
            }.getOrDefault("")
        }

        fun parseAdaptiveAudioStreams(jsonText: String, userAgent: String): List<InnerTubeAudioStream> {
            val root = runCatching { jsonParser.parseToJsonElement(jsonText).jsonObject }.getOrNull()
                ?: return emptyList()
            val streamingData = root["streamingData"]?.jsonObject ?: return emptyList()
            val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray ?: return emptyList()

            val result = mutableListOf<InnerTubeAudioStream>()
            for (el in adaptiveFormats) {
                val obj = el as? JsonObject ?: continue
                val mimeType = obj["mimeType"]?.jsonPrimitive?.content ?: continue
                // Strictly accept audio-only streams; never pick any video/* stream.
                if (!mimeType.startsWith("audio/", ignoreCase = true)) continue
                val directUrl = obj["url"]?.jsonPrimitive?.content.orEmpty()
                if (directUrl.isBlank()) continue

                val itag = (obj["itag"] as? JsonPrimitive)?.intOrNull ?: 0
                val bitrate = (obj["bitrate"] as? JsonPrimitive)?.intOrNull ?: 0
                val contentLength = (obj["contentLength"] as? JsonPrimitive)?.longOrNull ?: 0L
                val isDefault = obj["audioTrack"]?.let { trackEl ->
                    val trackObj = trackEl as? JsonObject
                    (trackObj?.get("audioIsDefault") as? JsonPrimitive)?.booleanOrNull ?: true
                } ?: true

                val ext = when {
                    mimeType.contains("webm", ignoreCase = true) || mimeType.contains("opus", ignoreCase = true) -> "webm"
                    mimeType.contains("mp4", ignoreCase = true) -> "m4a"
                    else -> "m4a"
                }

                result += InnerTubeAudioStream(
                    itag = itag,
                    url = directUrl,
                    mimeType = mimeType,
                    bitrate = bitrate,
                    contentLength = contentLength,
                    extension = ext,
                    userAgent = userAgent,
                    isDefaultTrack = isDefault
                )
            }
            return result
        }

        fun selectBestAudioStream(
            streams: List<InnerTubeAudioStream>,
            preferredFormat: String,
            preferredQuality: String
        ): InnerTubeAudioStream? {
            if (streams.isEmpty()) return null

            val defaultTracks = streams.filter { it.isDefaultTrack }
            val trackPool = if (defaultTracks.isNotEmpty()) defaultTracks else streams

            val targetExt = if (preferredFormat.equals("opus", ignoreCase = true)) "webm" else "m4a"
            val extPool = trackPool.filter { it.extension.equals(targetExt, ignoreCase = true) }
            val candidates = if (extPool.isNotEmpty()) extPool else trackPool

            return when (preferredQuality.uppercase(Locale.US)) {
                "64K" -> {
                    val atLeast45k = candidates.filter { it.bitrate >= 45_000 }
                    val pool64 = if (atLeast45k.isNotEmpty()) atLeast45k else candidates
                    pool64.minByOrNull { abs(it.bitrate - 64_000) }
                }
                "128K" -> {
                    val upTo160k = candidates.filter { it.bitrate <= 160_000 }
                    if (upTo160k.isNotEmpty()) {
                        upTo160k.maxByOrNull { it.bitrate }
                    } else {
                        candidates.maxByOrNull { it.bitrate }
                    }
                }
                else -> candidates.maxByOrNull { it.bitrate }
            }
        }
    }
}
