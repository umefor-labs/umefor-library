package io.lightknight.omniread.engine.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.lightknight.omniread.OmniReadApp
import io.lightknight.omniread.core.AppSettings
import io.lightknight.omniread.core.AppSettingsRepository
import io.lightknight.omniread.core.HashUtils
import io.lightknight.omniread.core.NaturalSort
import io.lightknight.omniread.data.local.ChapterEntity
import io.lightknight.omniread.data.local.OmniReadDatabase
import io.lightknight.omniread.data.local.SyncJobEntity
import io.lightknight.omniread.data.model.DownloadState
import io.lightknight.omniread.data.storage.FileStorageManager
import io.lightknight.omniread.engine.importer.LocalImportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SyncOrchestrator(
    private val context: Context,
    private val db: OmniReadDatabase,
    private val storage: FileStorageManager,
    private val settingsRepo: AppSettingsRepository,
    private val authManager: DriveAuthManager,
    private val driveClient: DriveRestClient,
    private val webDavClient: WebDavSyncClient,
    private val importManager: LocalImportManager
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val syncMutex = Mutex()

    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<DriveSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(Data.Builder().putBoolean(DriveSyncWorker.KEY_INCLUDE_MEDIA, false).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "omniread_periodic_meta_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    suspend fun triggerImmediateSync(includeMedia: Boolean = true) {
        val settings = settingsRepo.settings.first()
        if (!settings.driveSyncEnabled) return
        val netType = if (includeMedia && settings.driveWifiOnlyForMedia) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val req = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(netType).build())
            .setInputData(Data.Builder().putBoolean(DriveSyncWorker.KEY_INCLUDE_MEDIA, includeMedia).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (includeMedia) "omniread_full_sync" else "omniread_meta_sync",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    suspend fun runSync(includeMedia: Boolean): Result<String> = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val settings = settingsRepo.settings.first()
                if (settings.syncProvider.equals("WEBDAV", ignoreCase = true)) {
                    return@runCatching runWebDavSync(includeMedia, settings)
                }
                val token = when (val auth = authManager.requestAuthorization()) {
                    is DriveAuthOutcome.Authorized -> auth.accessToken
                    is DriveAuthOutcome.NeedsUserConsent ->
                        throw IllegalStateException("Cần đăng nhập lại Google Drive trong Cài đặt.")
                    is DriveAuthOutcome.Failed ->
                        authManager.getCachedAccessToken().ifBlank { throw IllegalStateException(auth.message) }
                }

                val rootId = driveClient.findOrCreateFolder(token, DriveSyncConfig.ROOT_FOLDER_NAME)
                val mediaRootId = driveClient.findOrCreateFolder(token, DriveSyncConfig.MEDIA_FOLDER_NAME, rootId)
                val scriptsRootId = driveClient.findOrCreateFolder(token, DriveSyncConfig.SCRIPTS_FOLDER_NAME, rootId)
                val inboxId = driveClient.findOrCreateFolder(token, DriveSyncConfig.INBOX_FOLDER_NAME, rootId)
                val processedInboxId = driveClient.findOrCreateFolder(token, "Imported", inboxId)

                // 1. Check OmniRead/Inbox for files dropped from PC (E:\Drive\OmniRead\Inbox)
                if (includeMedia) {
                    val inboxFiles = driveClient.listFilesInFolder(token, inboxId)
                        .filter { it.mimeType != "application/vnd.google-apps.folder" }
                    for (entry in inboxFiles) {
                        val temp = File(storage.inboxTempDir, entry.name)
                        driveClient.downloadFile(token, entry.id, temp)
                        importManager.importLocalFile(temp)
                        temp.delete()
                        driveClient.deleteOrMoveOutOfInbox(token, entry.id, inboxId, processedInboxId)
                    }
                }

                // 2. Two-way manifest merge (metadata, progress, bookmarks, voice casts, plugins)
                val localManifest = buildLocalManifest()
                val manifestEntry = driveClient.findFileByName(token, DriveSyncConfig.MANIFEST_FILE_NAME, rootId)
                val remoteManifest = manifestEntry?.let { entry ->
                    runCatching {
                        val txt = driveClient.downloadText(token, entry.id)
                        json.decodeFromString(SyncManifest.serializer(), txt)
                    }.getOrNull()
                } ?: SyncManifest()

                val merged = SyncManifestMerger.merge(localManifest, remoteManifest)
                applyMergedManifestToLocalDb(merged)

                // 3. Upload heavy media & AI scripts when includeMedia = true
                if (includeMedia) {
                    syncScripts(token, scriptsRootId)
                    syncMediaFiles(token, mediaRootId)
                }

                // 4. Write updated manifest back to Drive
                val finalManifest = buildLocalManifest()
                driveClient.uploadSmallJson(
                    token = token,
                    name = DriveSyncConfig.MANIFEST_FILE_NAME,
                    parentId = rootId,
                    existingFileId = manifestEntry?.id,
                    jsonContent = json.encodeToString(finalManifest)
                )

                db.syncJobDao().clearCompleted()
                settingsRepo.updateLastSyncTime(System.currentTimeMillis())
                "Đồng bộ Google Drive thành công (${finalManifest.items.size} bộ)"
            }
        }
    }

    private fun buildWebDavConfig(settings: AppSettings): WebDavConfig? {
        val pass = settingsRepo.getWebDavPassword()
        if (settings.webDavUrl.isBlank() || settings.webDavUsername.isBlank() || pass.isBlank()) {
            return null
        }
        return WebDavConfig(
            baseUrl = settings.webDavUrl,
            username = settings.webDavUsername,
            password = pass,
            rootFolder = "UmeforLibrary"
        )
    }

    private suspend fun runWebDavSync(includeMedia: Boolean, settings: AppSettings): String {
        val cfg = buildWebDavConfig(settings)
            ?: throw IllegalStateException("Chưa kết nối WebDAV (Koofr/InfiniCLOUD). Vào Cài đặt để đăng nhập.")
        val root = cfg.rootFolder
        webDavClient.ensureDirectory(cfg, root)
        webDavClient.ensureDirectory(cfg, "$root/Media")
        webDavClient.ensureDirectory(cfg, "$root/Scripts")
        webDavClient.ensureDirectory(cfg, "$root/Inbox/Imported")

        if (includeMedia) {
            val inboxEntries = webDavClient.listDirectory(cfg, "$root/Inbox").filter { !it.isDirectory }
            for (entry in inboxEntries) {
                val temp = File(storage.inboxTempDir, entry.name)
                webDavClient.downloadFile(cfg, "$root/Inbox/${entry.name}", temp)
                importManager.importLocalFile(temp)
                temp.delete()
                webDavClient.moveFile(cfg, "$root/Inbox/${entry.name}", "$root/Inbox/Imported/${entry.name}")
            }
        }

        val localManifest = buildLocalManifest()
        val manifestPath = "$root/${DriveSyncConfig.MANIFEST_FILE_NAME}"
        val remoteManifest = runCatching {
            val txt = webDavClient.downloadText(cfg, manifestPath)
            if (!txt.isNullOrBlank()) json.decodeFromString(SyncManifest.serializer(), txt) else null
        }.getOrNull() ?: SyncManifest()

        val merged = SyncManifestMerger.merge(localManifest, remoteManifest)
        applyMergedManifestToLocalDb(merged)

        if (includeMedia) {
            syncScriptsWebDav(cfg, "$root/Scripts")
            syncMediaFilesWebDav(cfg, "$root/Media")
        }

        val finalManifest = buildLocalManifest()
        webDavClient.uploadText(cfg, manifestPath, json.encodeToString(finalManifest))

        db.syncJobDao().clearCompleted()
        settingsRepo.updateLastSyncTime(System.currentTimeMillis())
        return "Đồng bộ WebDAV thành công (${finalManifest.items.size} bộ)"
    }

    private suspend fun syncScriptsWebDav(cfg: WebDavConfig, scriptsDir: String) {
        val scripts = db.chapterScriptDao().getAll()
        val existingNames = webDavClient.listDirectory(cfg, scriptsDir).map { it.name }.toSet()
        for (sc in scripts) {
            val fileName = "${sc.chapterId}.json"
            if (fileName !in existingNames) {
                webDavClient.uploadText(cfg, "$scriptsDir/$fileName", sc.segmentsJson)
            }
        }
    }

    private suspend fun syncMediaFilesWebDav(cfg: WebDavConfig, mediaRoot: String) {
        val items = db.libraryDao().getAllIncludingDeleted().filter { !it.isDeleted }
        for (item in items) {
            val itemFolder = "$mediaRoot/${item.id}"
            webDavClient.ensureDirectory(cfg, itemFolder)

            val coverFile = item.coverLocalPath?.let(::File)?.takeIf { it.exists() }
            if (item.coverDriveId == null && coverFile != null) {
                val coverRemote = "$itemFolder/cover.${coverFile.extension.ifBlank { "jpg" }}"
                runCatching {
                    webDavClient.uploadFile(cfg, coverRemote, coverFile, "image/jpeg")
                    db.libraryDao().updateDriveIds(item.id, itemFolder, "webdav:$coverRemote")
                }
            }

            val chapters = db.chapterDao().getForItem(item.id)
            for (ch in chapters) {
                if (!ch.driveFileId.isNullOrBlank()) continue
                val localPath = ch.localFilePath ?: continue
                val localObj = File(localPath)
                if (!localObj.exists()) continue

                val uploadTarget: File
                val remoteName: String
                val tempCreated: Boolean
                if (localObj.isDirectory) {
                    val zipFile = File(storage.inboxTempDir, "${ch.id}.zip")
                    zipDirectory(localObj, zipFile)
                    uploadTarget = zipFile
                    remoteName = "ch_%04d.zip".format(ch.chapterIndex)
                    tempCreated = true
                } else {
                    uploadTarget = localObj
                    remoteName = "ch_%04d.%s".format(ch.chapterIndex, localObj.extension.ifBlank { "dat" })
                    tempCreated = false
                }

                val jobId = "CHAPTER_FILE_${ch.id}_UPLOAD_FILE"
                db.syncJobDao().upsert(SyncJobEntity(jobId, "CHAPTER_FILE", ch.id, "UPLOAD_FILE", "RUNNING", 0))
                runCatching {
                    val remotePath = "$itemFolder/$remoteName"
                    webDavClient.uploadFile(cfg, remotePath, uploadTarget)
                    db.chapterDao().updateDriveFileId(ch.id, "webdav:$remotePath")
                    db.syncJobDao().upsert(SyncJobEntity(jobId, "CHAPTER_FILE", ch.id, "UPLOAD_FILE", "DONE", 100))
                }.onFailure { err ->
                    db.syncJobDao().upsert(
                        SyncJobEntity(jobId, "CHAPTER_FILE", ch.id, "UPLOAD_FILE", "FAILED", 0, lastError = err.message)
                    )
                }
                if (tempCreated) uploadTarget.delete()
            }
        }
    }

    /**
     * Downloads a single CLOUD_ONLY chapter on demand when the user opens it.
     */
    suspend fun downloadCloudChapterOnDemand(chapter: ChapterEntity): File? = withContext(Dispatchers.IO) {
        chapter.localFilePath?.let(::File)?.takeIf { it.exists() }?.let { return@withContext it }
        val remoteRef = chapter.driveFileId ?: return@withContext null
        val item = db.libraryDao().getById(chapter.itemId) ?: return@withContext null

        if (remoteRef.startsWith("webdav:")) {
            val settings = settingsRepo.settings.first()
            val cfg = buildWebDavConfig(settings) ?: return@withContext null
            val relPath = remoteRef.removePrefix("webdav:")
            return@withContext runCatching {
                when (item.mediaType) {
                    io.lightknight.omniread.data.model.MediaType.NOVEL -> {
                        val dest = File(storage.itemChaptersDir(item.id), "ch_%04d.txt".format(chapter.chapterIndex))
                        webDavClient.downloadFile(cfg, relPath, dest)
                        db.chapterDao().updateLocalFile(chapter.id, DownloadState.DOWNLOADED, dest.absolutePath, HashUtils.sha256(dest))
                        dest
                    }
                    io.lightknight.omniread.data.model.MediaType.COMIC -> {
                        val chDir = File(storage.itemChaptersDir(item.id), "ch_%04d".format(chapter.chapterIndex)).apply { mkdirs() }
                        val zipTmp = File(storage.inboxTempDir, "${chapter.id}.zip")
                        webDavClient.downloadFile(cfg, relPath, zipTmp)
                        ZipFile(zipTmp).use { zf ->
                            zf.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                                val out = File(chDir, entry.name.substringAfterLast('/'))
                                zf.getInputStream(entry).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                            }
                        }
                        zipTmp.delete()
                        db.chapterDao().updateLocalFile(chapter.id, DownloadState.DOWNLOADED, chDir.absolutePath, chapter.contentSha256)
                        chDir
                    }
                    io.lightknight.omniread.data.model.MediaType.AUDIOBOOK -> {
                        val dest = File(storage.itemAudioDir(item.id), "ch_%04d.m4a".format(chapter.chapterIndex))
                        webDavClient.downloadFile(cfg, relPath, dest)
                        db.chapterDao().updateLocalFile(chapter.id, DownloadState.DOWNLOADED, dest.absolutePath, HashUtils.sha256(dest))
                        dest
                    }
                }
            }.getOrNull()
        }

        val token = when (val auth = authManager.requestAuthorization()) {
            is DriveAuthOutcome.Authorized -> auth.accessToken
            else -> authManager.getCachedAccessToken()
        }
        if (token.isBlank()) return@withContext null

        return@withContext runCatching {
            when (item.mediaType) {
                io.lightknight.omniread.data.model.MediaType.NOVEL -> {
                    val dest = File(storage.itemChaptersDir(item.id), "ch_%04d.txt".format(chapter.chapterIndex))
                    driveClient.downloadFile(token, remoteRef, dest)
                    db.chapterDao().updateLocalFile(chapter.id, DownloadState.DOWNLOADED, dest.absolutePath, HashUtils.sha256(dest))
                    dest
                }
                io.lightknight.omniread.data.model.MediaType.COMIC -> {
                    val chDir = File(storage.itemChaptersDir(item.id), "ch_%04d".format(chapter.chapterIndex)).apply { mkdirs() }
                    val zipTmp = File(storage.inboxTempDir, "${chapter.id}.zip")
                    driveClient.downloadFile(token, remoteRef, zipTmp)
                    ZipFile(zipTmp).use { zf ->
                        zf.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                            val out = File(chDir, entry.name.substringAfterLast('/'))
                            zf.getInputStream(entry).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                        }
                    }
                    zipTmp.delete()
                    db.chapterDao().updateLocalFile(chapter.id, DownloadState.DOWNLOADED, chDir.absolutePath, chapter.contentSha256)
                    chDir
                }
                io.lightknight.omniread.data.model.MediaType.AUDIOBOOK -> {
                    val dest = File(storage.itemAudioDir(item.id), "ch_%04d.m4a".format(chapter.chapterIndex))
                    driveClient.downloadFile(token, remoteRef, dest)
                    db.chapterDao().updateLocalFile(chapter.id, DownloadState.DOWNLOADED, dest.absolutePath, HashUtils.sha256(dest))
                    dest
                }
            }
        }.getOrNull()
    }

    private suspend fun syncScripts(token: String, scriptsRootId: String) {
        val scripts = db.chapterScriptDao().getAll()
        val existingRemote = driveClient.listFilesInFolder(token, scriptsRootId).associateBy { it.name }
        for (sc in scripts) {
            val fileName = "${sc.chapterId}.json"
            if (!existingRemote.containsKey(fileName)) {
                driveClient.uploadSmallJson(token, fileName, scriptsRootId, null, sc.segmentsJson)
            }
        }
    }

    private suspend fun syncMediaFiles(token: String, mediaRootId: String) {
        val items = db.libraryDao().getAllIncludingDeleted().filter { !it.isDeleted }
        for (item in items) {
            val folderId = item.driveFolderId
                ?: driveClient.findOrCreateFolder(token, item.id, mediaRootId).also { fid ->
                    db.libraryDao().updateDriveIds(item.id, fid, item.coverDriveId)
                }

            var coverDriveId = item.coverDriveId
            val coverFile = item.coverLocalPath?.let(::File)?.takeIf { it.exists() }
            if (coverDriveId == null && coverFile != null) {
                coverDriveId = driveClient.uploadFileResumable(
                    token = token,
                    name = "cover.${coverFile.extension.ifBlank { "jpg" }}",
                    parentId = folderId,
                    existingFileId = null,
                    file = coverFile,
                    mimeType = "image/jpeg"
                )
                db.libraryDao().updateDriveIds(item.id, folderId, coverDriveId)
            }

            val chapters = db.chapterDao().getForItem(item.id)
            for (ch in chapters) {
                if (!ch.driveFileId.isNullOrBlank()) continue
                val localPath = ch.localFilePath ?: continue
                val localObj = File(localPath)
                if (!localObj.exists()) continue

                val uploadTarget: File
                val remoteName: String
                val tempCreated: Boolean
                if (localObj.isDirectory) {
                    val zipFile = File(storage.inboxTempDir, "${ch.id}.zip")
                    zipDirectory(localObj, zipFile)
                    uploadTarget = zipFile
                    remoteName = "ch_%04d.zip".format(ch.chapterIndex)
                    tempCreated = true
                } else {
                    uploadTarget = localObj
                    remoteName = "ch_%04d.%s".format(ch.chapterIndex, localObj.extension.ifBlank { "dat" })
                    tempCreated = false
                }

                val jobId = "CHAPTER_FILE_${ch.id}_UPLOAD_FILE"
                db.syncJobDao().upsert(SyncJobEntity(jobId, "CHAPTER_FILE", ch.id, "UPLOAD_FILE", "RUNNING", 0))
                runCatching {
                    val driveId = driveClient.uploadFileResumable(
                        token = token,
                        name = remoteName,
                        parentId = folderId,
                        existingFileId = null,
                        file = uploadTarget,
                        onProgress = { pct ->
                            db.syncJobDao().upsert(SyncJobEntity(jobId, "CHAPTER_FILE", ch.id, "UPLOAD_FILE", "RUNNING", pct))
                        }
                    )
                    db.chapterDao().updateDriveFileId(ch.id, driveId)
                    db.syncJobDao().upsert(SyncJobEntity(jobId, "CHAPTER_FILE", ch.id, "UPLOAD_FILE", "DONE", 100))
                }.onFailure { err ->
                    db.syncJobDao().upsert(
                        SyncJobEntity(jobId, "CHAPTER_FILE", ch.id, "UPLOAD_FILE", "FAILED", 0, lastError = err.message)
                    )
                }
                if (tempCreated) uploadTarget.delete()
            }
        }
    }

    private fun zipDirectory(dir: File, destZip: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedWith { a, b -> NaturalSort.compare(a.name, b.name) }.orEmpty()
        ZipOutputStream(destZip.outputStream()).use { zos ->
            files.forEach { f ->
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private suspend fun buildLocalManifest(): SyncManifest {
        return SyncManifest(
            updatedAt = System.currentTimeMillis(),
            items = db.libraryDao().getAllIncludingDeleted().map { it.toSyncDto() },
            chapters = db.chapterDao().getAll().map { it.toSyncDto() },
            progress = db.progressDao().getAll().map { it.toSyncDto() },
            bookmarks = db.bookmarkDao().getAllIncludingDeleted().map { it.toSyncDto() },
            characterVoices = db.voiceCastDao().getAll().map { it.toSyncDto() },
            plugins = db.sourcePluginDao().getAll().map { it.toSyncDto() }
        )
    }

    private suspend fun applyMergedManifestToLocalDb(merged: SyncManifest) {
        val existingItems = db.libraryDao().getAllIncludingDeleted().associateBy { it.id }
        val existingChapters = db.chapterDao().getAll().associateBy { it.id }

        db.libraryDao().upsertAll(merged.items.map { dto ->
            dto.toEntity(storage.itemDir(dto.id).absolutePath, existingItems[dto.id])
        })
        db.chapterDao().upsertAll(merged.chapters.map { dto ->
            dto.toEntity(existingChapters[dto.id])
        })
        db.progressDao().upsertAll(merged.progress.map { it.toEntity() })
        db.bookmarkDao().upsertAll(merged.bookmarks.map { it.toEntity() })
        db.voiceCastDao().upsertAll(merged.characterVoices.map { it.toEntity() })
        db.sourcePluginDao().upsertAll(merged.plugins.map { it.toEntity() })
    }
}

class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val includeMedia = inputData.getBoolean(KEY_INCLUDE_MEDIA, false)
        val container = (applicationContext as OmniReadApp).container
        val res = container.syncOrchestrator.runSync(includeMedia)
        return if (res.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_INCLUDE_MEDIA = "include_media"
    }
}
