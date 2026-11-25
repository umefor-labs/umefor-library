package io.lightknight.omniread.engine.youtube

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.lightknight.omniread.OmniReadApp
import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.core.NaturalSort
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.LibraryItemEntity
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.model.OriginType
import io.lightknight.omniread.data.repository.LibraryRepository
import io.lightknight.omniread.data.storage.FileStorageManager
import io.lightknight.omniread.engine.importer.AudioImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

@Serializable
data class YoutubeDownloadSpec(
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val sourceUrl: String,
    val audioFormat: String = "m4a", // m4a | opus | mp3
    val audioQuality: String = "128K", // 64K | 128K | 192K
    val splitChapters: Boolean = false,
    val trackUrls: List<String>,
    val trackTitles: List<String>
)

data class YoutubeDownloadProgress(
    val isRunning: Boolean = false,
    val bookTitle: String = "",
    val currentTrack: Int = 0,
    val totalTracks: Int = 0,
    val percent: Float = 0f,
    val statusLine: String = ""
)

object YoutubeDlCommandBuilder {
    fun buildAudioRequest(
        trackUrl: String,
        outputTemplate: String,
        audioFormat: String,
        audioQuality: String,
        splitChapters: Boolean,
        fallbackClient: Boolean = false
    ): YoutubeDLRequest {
        val fmtLower = audioFormat.lowercase()
        val formatSelector = when (fmtLower) {
            "m4a" -> "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio/ba"
            "opus" -> "bestaudio[ext=webm]/bestaudio[acodec*=opus]/bestaudio/ba"
            else -> "bestaudio/ba"
        }

        val req = YoutubeDLRequest(trackUrl)
            .addOption("--no-update")
            .addOption("--no-warnings")
            .addOption("--force-ipv4")
            .addOption("--retries", "3")
            .addOption("--fragment-retries", "3")
            .addOption("-f", formatSelector)
            .addOption("--add-metadata")
            .addOption("--no-playlist")
            .addOption("-o", outputTemplate)

        if (!fallbackClient) {
            req.addOption("--extractor-args", "youtube:player_client=ios,android")
        }

        // Only run ffmpeg extraction (-x) when transcoding to MP3 or splitting chapters;
        // native M4A/Opus audio-only streams are downloaded directly with zero video download!
        val needsExtraction = fmtLower == "mp3" || splitChapters
        if (needsExtraction) {
            req.addOption("-x")
                .addOption("--audio-format", fmtLower)
                .addOption("--audio-quality", audioQuality)
        }
        if (splitChapters) {
            req.addOption("--split-chapters")
        }
        return req
    }

    fun buildPlaylistInfoRequest(url: String): YoutubeDLRequest {
        return YoutubeDLRequest(url.trim())
            .addOption("--no-update")
            .addOption("--no-warnings")
            .addOption("--force-ipv4")
            .addOption("--flat-playlist")
            .addOption("--dump-single-json")
    }

    fun buildOutputTemplate(tempOut: File, trackIndex: Int, splitChapters: Boolean): String {
        return if (splitChapters) {
            File(tempOut, "%(section_number)03d - %(section_title)s.%(ext)s").absolutePath
        } else {
            val padded = "%04d".format(trackIndex)
            File(tempOut, "track_$padded.%(ext)s").absolutePath
        }
    }

    fun isRetryableFormatOrAuthError(message: String?): Boolean {
        val m = message.orEmpty()
        return m.contains("403", ignoreCase = true) ||
            m.contains("SABR", ignoreCase = true) ||
            m.contains("missing a url", ignoreCase = true) ||
            m.contains("Requested format is not available", ignoreCase = true) ||
            m.contains("format is not available", ignoreCase = true) ||
            m.contains("Sign in to confirm", ignoreCase = true)
    }

    fun is403OrSabrError(message: String?): Boolean = isRetryableFormatOrAuthError(message)

    fun extractConciseError(rawMessage: String?): String {
        val raw = rawMessage.orEmpty().trim()
        if (raw.isBlank()) return "Không thể tải từ YouTube."
        val errorLines = raw.lines()
            .map { it.trim() }
            .filter { it.startsWith("ERROR:", ignoreCase = true) }
        val mainErr = errorLines.lastOrNull()
            ?: raw.lines()
                .map { it.trim() }
                .filterNot { it.startsWith("WARNING:", ignoreCase = true) || it.startsWith("Run \"yt-dlp", ignoreCase = true) || it.startsWith("To suppress", ignoreCase = true) || it.startsWith("--no-update", ignoreCase = true) || it.startsWith("It is strongly", ignoreCase = true) || it.startsWith("to update.", ignoreCase = true) }
                .lastOrNull { it.isNotBlank() }
            ?: raw.take(200)

        return when {
            raw.contains("Requested format is not available", ignoreCase = true) ->
                "Không tìm thấy luồng âm thanh phù hợp ($mainErr). Hãy thử định dạng M4A/OPUS hoặc cập nhật yt-dlp trong Cài đặt."
            is403OrSabrError(raw) ->
                "$mainErr — Hãy vào Cài đặt bấm 'Cập nhật lõi yt-dlp trong app' rồi thử lại."
            else -> mainErr.take(260)
        }
    }
}

class YoutubeAudioDownloader(
    private val context: Context,
    private val initializer: YoutubeDlInitializer,
    private val httpClient: OkHttpClient,
    private val storage: FileStorageManager,
    private val repository: LibraryRepository
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val innerTube = InnerTubeAudioExtractor(httpClient)

    private val _progress = MutableStateFlow(YoutubeDownloadProgress())
    val progress: StateFlow<YoutubeDownloadProgress> = _progress.asStateFlow()

    suspend fun fetchPreview(url: String): Result<YoutubeImportPreview> = withContext(Dispatchers.IO) {
        runCatching {
            initializer.ensureInitialized()
            val req = YoutubeDlCommandBuilder.buildPlaylistInfoRequest(url)
            val resp = YoutubeDL.getInstance().execute(req)
            YoutubePlaylistParser.parseDumpJson(resp.out, url.trim())
        }
    }

    fun enqueueDownload(spec: YoutubeDownloadSpec) {
        val specFile = File(storage.inboxTempDir, "yt_${UUID.randomUUID()}.json")
        specFile.writeText(json.encodeToString(spec), Charsets.UTF_8)
        val data = Data.Builder()
            .putString(YoutubeDownloadWorker.KEY_SPEC_FILE, specFile.absolutePath)
            .build()
        val req = OneTimeWorkRequestBuilder<YoutubeDownloadWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "yt_dl_${HashUtils.sha256(spec.sourceUrl).take(12)}",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    internal suspend fun executeSpec(
        spec: YoutubeDownloadSpec,
        onProgress: suspend (Int, Int, Float, String) -> Unit
    ): Result<LibraryItemEntity> = withContext(Dispatchers.IO) {
        val total = spec.trackUrls.size.coerceAtLeast(1)
        var currentIdx = 0
        runCatching {
            initializer.ensureInitialized()
            val itemId = UUID.randomUUID().toString()
            val audioDir = storage.itemAudioDir(itemId)
            val coverPath = downloadCover(itemId, spec.thumbnailUrl)
            val chapters = mutableListOf<ChapterEntity>()

            spec.trackUrls.forEachIndexed { idx, trackUrl ->
                currentIdx = idx + 1
                val trackTitle = spec.trackTitles.getOrNull(idx) ?: "Tập ${idx + 1}"
                val tempOut = File(audioDir, "raw_%04d".format(idx)).apply { mkdirs() }
                val template = YoutubeDlCommandBuilder.buildOutputTemplate(tempOut, idx, spec.splitChapters)

                _progress.value = YoutubeDownloadProgress(
                    isRunning = true,
                    bookTitle = spec.title,
                    currentTrack = currentIdx,
                    totalTracks = total,
                    percent = 0f,
                    statusLine = "Đang tải tập $currentIdx/$total: $trackTitle"
                )

                val fmtLower = spec.audioFormat.lowercase()
                val preferInnerTubeFirst = !spec.splitChapters && fmtLower in setOf("m4a", "opus")
                var downloadedViaInnerTube = false

                if (preferInnerTubeFirst) {
                    val nativeResult = runCatching {
                        innerTube.downloadAudio(
                            trackUrl = trackUrl,
                            tempOut = tempOut,
                            trackIndex = idx,
                            preferredFormat = fmtLower,
                            preferredQuality = spec.audioQuality
                        ) { pct, line ->
                            _progress.value = YoutubeDownloadProgress(
                                isRunning = true,
                                bookTitle = spec.title,
                                currentTrack = currentIdx,
                                totalTracks = total,
                                percent = pct,
                                statusLine = line
                            )
                        }
                    }
                    downloadedViaInnerTube = nativeResult.isSuccess
                }

                if (!downloadedViaInnerTube) {
                    val ytdlpResult = runCatching {
                        val primaryReq = YoutubeDlCommandBuilder.buildAudioRequest(
                            trackUrl, template, spec.audioFormat, spec.audioQuality, spec.splitChapters, fallbackClient = false
                        )
                        val firstAttempt = runCatching {
                            YoutubeDL.getInstance().execute(primaryReq, "${itemId}_$idx") { pct, _, line ->
                                _progress.value = YoutubeDownloadProgress(
                                    isRunning = true,
                                    bookTitle = spec.title,
                                    currentTrack = currentIdx,
                                    totalTracks = total,
                                    percent = pct.coerceIn(0f, 100f),
                                    statusLine = line.take(80)
                                )
                            }
                        }

                        if (firstAttempt.isFailure) {
                            val err = firstAttempt.exceptionOrNull()!!
                            if (YoutubeDlCommandBuilder.isRetryableFormatOrAuthError(err.message)) {
                                _progress.value = _progress.value.copy(statusLine = "Đang tự cập nhật yt-dlp và thử lại...")
                                runCatching { initializer.forceUpdateYoutubeDl() }
                                val fallbackReq = YoutubeDlCommandBuilder.buildAudioRequest(
                                    trackUrl, template, spec.audioFormat, spec.audioQuality, spec.splitChapters, fallbackClient = true
                                )
                                YoutubeDL.getInstance().execute(fallbackReq, "${itemId}_${idx}_fb") { pct, _, line ->
                                    _progress.value = YoutubeDownloadProgress(
                                        isRunning = true,
                                        bookTitle = spec.title,
                                        currentTrack = currentIdx,
                                        totalTracks = total,
                                        percent = pct.coerceIn(0f, 100f),
                                        statusLine = line.take(80)
                                    )
                                }
                            } else {
                                throw err
                            }
                        }
                    }

                    if (ytdlpResult.isFailure) {
                        val ytErr = ytdlpResult.exceptionOrNull()!!
                        if (!spec.splitChapters) {
                            // Rescue fallback for MP3/yt-dlp failures (e.g. missing JS runtime):
                            // download native M4A audio-only stream directly via InnerTube so ExoPlayer plays cleanly.
                            innerTube.downloadAudio(
                                trackUrl = trackUrl,
                                tempOut = tempOut,
                                trackIndex = idx,
                                preferredFormat = "m4a",
                                preferredQuality = spec.audioQuality
                            ) { pct, line ->
                                _progress.value = YoutubeDownloadProgress(
                                    isRunning = true,
                                    bookTitle = spec.title,
                                    currentTrack = currentIdx,
                                    totalTracks = total,
                                    percent = pct,
                                    statusLine = line
                                )
                            }
                        } else {
                            throw ytErr
                        }
                    }
                }

                onProgress(currentIdx, total, 100f, trackTitle)

                val generatedFiles = tempOut.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in setOf("m4a", "mp3", "opus", "ogg", "wav", "webm") }
                    ?.sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }
                    .orEmpty()

                val finalFiles = if (spec.splitChapters && generatedFiles.size > 1) {
                    generatedFiles.filterNot { it.name.startsWith("track_") }
                } else {
                    generatedFiles
                }

                finalFiles.forEach { file ->
                    val chIdx = chapters.size
                    val dest = File(audioDir, "ch_%04d.%s".format(chIdx, file.extension))
                    file.renameTo(dest)
                    val meta = AudioImporter.extractAudioMeta(dest)
                    val chTitle = if (spec.splitChapters) file.nameWithoutExtension else trackTitle
                    chapters += ChapterEntity(
                        id = UUID.randomUUID().toString(),
                        itemId = itemId,
                        chapterIndex = chIdx,
                        title = chTitle,
                        remoteUrl = trackUrl,
                        localFilePath = dest.absolutePath,
                        contentSha256 = HashUtils.sha256(dest),
                        durationMs = meta.durationMs,
                        downloadState = DownloadState.DOWNLOADED
                    )
                }
                tempOut.deleteRecursively()
            }

            if (chapters.isEmpty()) {
                throw IllegalStateException("Không trích xuất được tệp âm thanh nào từ YouTube.")
            }

            val item = LibraryItemEntity(
                id = itemId,
                title = spec.title,
                author = spec.uploader,
                coverLocalPath = coverPath,
                mediaType = MediaType.AUDIOBOOK,
                originType = OriginType.YOUTUBE_PLAYLIST,
                sourceUrl = spec.sourceUrl,
                localDirPath = storage.itemDir(itemId).absolutePath,
                contentSha256 = HashUtils.sha256(chapters.joinToString { it.contentSha256 })
            )
            repository.saveImportedBook(item, chapters)
            _progress.value = YoutubeDownloadProgress(
                isRunning = false,
                bookTitle = spec.title,
                currentTrack = total,
                totalTracks = total,
                percent = 100f,
                statusLine = "Hoàn tất ($total tập)"
            )
            item
        }.onFailure { err ->
            _progress.value = YoutubeDownloadProgress(
                isRunning = false,
                bookTitle = spec.title,
                currentTrack = currentIdx,
                totalTracks = total,
                statusLine = "Lỗi: ${YoutubeDlCommandBuilder.extractConciseError(err.message)}"
            )
        }
    }

    private fun downloadCover(itemId: String, coverUrl: String): String? {
        if (!coverUrl.startsWith("http")) return null
        return runCatching {
            val req = Request.Builder().url(coverUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                val dest = storage.coverFile(itemId, "jpg")
                dest.writeBytes(bytes)
                dest.absolutePath
            }
        }.getOrNull()
    }
}

class YoutubeDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val specPath = inputData.getString(KEY_SPEC_FILE) ?: return Result.failure()
        val specFile = File(specPath)
        if (!specFile.exists()) return Result.failure()

        val spec = json.decodeFromString(YoutubeDownloadSpec.serializer(), specFile.readText(Charsets.UTF_8))
        val container = (applicationContext as OmniReadApp).container

        setForeground(createForegroundInfo(spec.title, 0, spec.trackUrls.size.coerceAtLeast(1)))
        val result = container.youtubeAudioDownloader.executeSpec(spec) { current, total, _, _ ->
            setForeground(createForegroundInfo(spec.title, current, total))
        }
        specFile.delete()
        return if (result.isSuccess) Result.success() else Result.failure()
    }

    private fun createForegroundInfo(title: String, done: Int, total: Int): ForegroundInfo {
        val channelId = "omniread_downloads"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Tải truyện & Sách nói", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Tải sách nói YouTube: $title")
            .setContentText("$done / $total tập")
            .setProgress(total, done, false)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(3001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(3001, notif)
        }
    }

    companion object {
        const val KEY_SPEC_FILE = "spec_file"
    }
}
