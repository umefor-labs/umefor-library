package io.lightknight.omniread.engine.scraper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.lightknight.omniread.OmniReadApp
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.data.model.OriginType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class ChapterUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as OmniReadApp).container
        checkAllTrackedBooks(applicationContext, container)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "umefor_chapter_tracker"

        fun schedulePeriodicCheck(context: Context, intervalHours: Int) {
            val wm = WorkManager.getInstance(context)
            if (intervalHours <= 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<ChapterUpdateWorker>(intervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        suspend fun checkAllTrackedBooks(context: Context, container: OmniAppContainer): Int {
            val settings = container.settingsRepository.settings.first()
            val tracked = container.libraryRepository.observeAllItems().first()
                .filter { it.originType == OriginType.WEB_PLUGIN && it.trackUpdates }
            var totalNew = 0
            val updatedTitles = mutableListOf<String>()

            for (book in tracked) {
                val beforeCount = container.libraryRepository.getChapters(book.id).size
                val res = container.webChapterDownloader.refreshWebChapters(book.id)
                res.onSuccess { afterCount ->
                    val delta = afterCount - beforeCount
                    if (delta > 0) {
                        totalNew += delta
                        updatedTitles += "${book.title} (+$delta)"
                        container.libraryRepository.addUnreadNewChapters(book.id, delta)
                        if (settings.autoDownloadNewChapters) {
                            container.webChapterDownloader.enqueueBatchDownload(book.id)
                        }
                    }
                }
                delay(1200L)
            }

            if (totalNew > 0) {
                notifyUpdates(context, totalNew, updatedTitles)
            }
            return totalNew
        }

        private fun notifyUpdates(context: Context, totalNew: Int, titles: List<String>) {
            val channelId = "umefor_chapter_updates"
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Cập nhật chương mới", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val notif = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Có $totalNew chương mới trên Umefor Library")
                .setContentText(titles.joinToString(", ").take(120))
                .setAutoCancel(true)
                .build()
            nm.notify(4001, notif)
        }
    }
}
