package io.lightknight.omniread.engine.youtube

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import io.lightknight.omniread.core.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class YoutubeDlInitializer(
    private val context: Context,
    private val settingsRepo: AppSettingsRepository
) {
    private val initMutex = Mutex()
    private val updateMutex = Mutex()
    @Volatile
    private var initialized = false
    @Volatile
    private var checkedAutoUpdate = false

    suspend fun ensureInitialized() = withContext(Dispatchers.IO) {
        if (!initialized) {
            initMutex.withLock {
                if (!initialized) {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)
                    initialized = true
                    val ver = YoutubeDL.getInstance().version(context.applicationContext).orEmpty()
                    if (ver.isNotBlank()) settingsRepo.updateYtDlpVersion(ver)
                }
            }
        }
        if (!checkedAutoUpdate) {
            checkedAutoUpdate = true
            val currentVer = YoutubeDL.getInstance().version(context.applicationContext).orEmpty()
            if (isOutdatedVersion(currentVer)) {
                runCatching { forceUpdateYoutubeDl() }
            }
        }
    }

    suspend fun forceUpdateYoutubeDl(): Result<String> = updateMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!initialized) {
                    initMutex.withLock {
                        if (!initialized) {
                            YoutubeDL.getInstance().init(context.applicationContext)
                            FFmpeg.getInstance().init(context.applicationContext)
                            initialized = true
                        }
                    }
                }
                val appCtx = context.applicationContext
                var status = runCatching {
                    YoutubeDL.getInstance().updateYoutubeDL(appCtx, YoutubeDL.UpdateChannel.STABLE)
                }.getOrNull()

                if (status == null) {
                    status = runCatching {
                        YoutubeDL.getInstance().updateYoutubeDL(appCtx, YoutubeDL.UpdateChannel.NIGHTLY)
                    }.getOrNull()
                }

                val newVer = YoutubeDL.getInstance().version(appCtx).orEmpty()
                if (newVer.isNotBlank()) settingsRepo.updateYtDlpVersion(newVer)
                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> "Đã cập nhật yt-dlp lên bản $newVer"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp đã ở bản mới nhất ($newVer)"
                    null -> throw IllegalStateException("Không thể kết nối GitHub để cập nhật yt-dlp.")
                }
            }
        }
    }

    suspend fun updateYoutubeDl(): Result<String> = forceUpdateYoutubeDl()

    companion object {
        fun isOutdatedVersion(version: String): Boolean {
            if (version.isBlank()) return true
            return version.startsWith("2024.") || version.startsWith("2025.")
        }
    }
}
