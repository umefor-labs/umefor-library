package io.lightknight.omniread.engine.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile
import java.net.URLEncoder

@Serializable
data class DriveFileEntry(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val size: String = "0",
    val modifiedTime: String = ""
)

@Serializable
private data class DriveFileListResponse(
    val files: List<DriveFileEntry> = emptyList()
)

object DriveResumableProtocol {
    fun buildContentRangeHeader(start: Long, endInclusive: Long, totalBytes: Long): String {
        return "bytes $start-$endInclusive/$totalBytes"
    }

    /**
     * Parses Drive's 308 "Range: bytes=0-5242879" header and returns the next byte offset (5242880).
     */
    fun parseNextOffsetFrom308(rangeHeader: String?): Long {
        if (rangeHeader.isNullOrBlank()) return 0L
        val match = Regex("""bytes=\d+-(\d+)""").find(rangeHeader) ?: return 0L
        val lastUploadedByte = match.groupValues[1].toLongOrNull() ?: return 0L
        return lastUploadedByte + 1L
    }
}

class DriveRestClient(
    private val httpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun findOrCreateFolder(token: String, name: String, parentId: String? = null): String =
        withContext(Dispatchers.IO) {
            val existing = findFileByName(token, name, parentId, folderOnly = true)
            if (existing != null) return@withContext existing.id

            val meta = buildJsonObject {
                put("name", name)
                put("mimeType", "application/vnd.google-apps.folder")
                if (!parentId.isNullOrBlank()) {
                    put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
                }
            }.toString()

            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?fields=id")
                .header("Authorization", "Bearer $token")
                .post(meta.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("Drive createFolder HTTP ${resp.code}: $raw")
                json.parseToJsonElement(raw).jsonObject["id"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("Không nhận được folder id")
            }
        }

    suspend fun findFileByName(
        token: String,
        name: String,
        parentId: String?,
        folderOnly: Boolean = false
    ): DriveFileEntry? = withContext(Dispatchers.IO) {
        val escapedName = name.replace("'", "\\'")
        val qParts = mutableListOf("name = '$escapedName'", "trashed = false")
        if (!parentId.isNullOrBlank()) qParts += "'$parentId' in parents"
        if (folderOnly) qParts += "mimeType = 'application/vnd.google-apps.folder'"
        val q = URLEncoder.encode(qParts.joinToString(" and "), "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,name,mimeType,size,modifiedTime)"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Drive list HTTP ${resp.code}: $raw")
            json.decodeFromString(DriveFileListResponse.serializer(), raw).files.firstOrNull()
        }
    }

    suspend fun listFilesInFolder(token: String, folderId: String): List<DriveFileEntry> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode("'$folderId' in parents and trashed = false", "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$q&pageSize=200&fields=files(id,name,mimeType,size,modifiedTime)"
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("Drive listFolder HTTP ${resp.code}: $raw")
                json.decodeFromString(DriveFileListResponse.serializer(), raw).files
            }
        }

    suspend fun downloadText(token: String, fileId: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Drive downloadText HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    suspend fun downloadFile(token: String, fileId: String, destFile: File): File =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .header("Authorization", "Bearer $token")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Drive downloadFile HTTP ${resp.code}")
                destFile.parentFile?.mkdirs()
                resp.body?.byteStream()?.use { ins ->
                    destFile.outputStream().use { out -> ins.copyTo(out, 16 * 1024) }
                } ?: throw IllegalStateException("Drive body rỗng")
                destFile
            }
        }

    suspend fun uploadSmallJson(
        token: String,
        name: String,
        parentId: String,
        existingFileId: String?,
        jsonContent: String
    ): String = withContext(Dispatchers.IO) {
        val boundary = "omniread_boundary_${System.currentTimeMillis()}"
        val metadata = buildJsonObject {
            put("name", name)
            if (existingFileId.isNullOrBlank()) {
                put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
            }
        }.toString()

        val bodyStr = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(jsonContent)
            append("\r\n--$boundary--")
        }

        val url = if (existingFileId.isNullOrBlank()) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=multipart&fields=id"
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
        val mediaType = "multipart/related; boundary=$boundary".toMediaType()
        val reqBody = bodyStr.toRequestBody(mediaType)
        if (existingFileId.isNullOrBlank()) reqBuilder.post(reqBody) else reqBuilder.patch(reqBody)

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Drive uploadSmallJson HTTP ${resp.code}: $raw")
            json.parseToJsonElement(raw).jsonObject["id"]?.jsonPrimitive?.content.orEmpty()
        }
    }

    suspend fun uploadFileResumable(
        token: String,
        name: String,
        parentId: String,
        existingFileId: String?,
        file: File,
        mimeType: String = "application/octet-stream",
        savedSessionUri: String? = null,
        onSessionUri: suspend (String) -> Unit = {},
        onProgress: suspend (Int) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val totalBytes = file.length()
        if (totalBytes == 0L) {
            return@withContext uploadSmallJson(token, name, parentId, existingFileId, "")
        }

        val sessionUri = savedSessionUri?.takeIf { it.isNotBlank() } ?: initiateResumableSession(
            token, name, parentId, existingFileId, totalBytes, mimeType
        ).also { onSessionUri(it) }

        var offset = queryUploadedOffset(sessionUri, totalBytes)
        val chunkSize = DriveSyncConfig.CHUNK_SIZE_BYTES

        RandomAccessFile(file, "r").use { raf ->
            while (offset < totalBytes) {
                val len = minOf(chunkSize.toLong(), totalBytes - offset).toInt()
                val buf = ByteArray(len)
                raf.seek(offset)
                raf.readFully(buf)

                val endInclusive = offset + len - 1
                val rangeHeader = DriveResumableProtocol.buildContentRangeHeader(offset, endInclusive, totalBytes)

                val req = Request.Builder()
                    .url(sessionUri)
                    .put(buf.toRequestBody(mimeType.toMediaType()))
                    .header("Content-Range", rangeHeader)
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        200, 201 -> {
                            onProgress(100)
                            val raw = resp.body?.string().orEmpty()
                            return@withContext json.parseToJsonElement(raw).jsonObject["id"]?.jsonPrimitive?.content.orEmpty()
                        }
                        308 -> {
                            val nextOffset = DriveResumableProtocol.parseNextOffsetFrom308(resp.header("Range"))
                            offset = if (nextOffset > offset) nextOffset else offset + len
                            onProgress(((offset * 100) / totalBytes).toInt().coerceIn(0, 99))
                        }
                        else -> {
                            val raw = resp.body?.string().orEmpty()
                            throw IllegalStateException("Resumable chunk HTTP ${resp.code}: $raw")
                        }
                    }
                }
            }
        }
        throw IllegalStateException("Upload resumable kết thúc không nhận được fileId.")
    }

    private fun initiateResumableSession(
        token: String,
        name: String,
        parentId: String,
        existingFileId: String?,
        totalBytes: Long,
        mimeType: String
    ): String {
        val meta = buildJsonObject {
            put("name", name)
            if (existingFileId.isNullOrBlank()) {
                put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
            }
        }.toString()

        val url = if (existingFileId.isNullOrBlank()) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=resumable&fields=id"
        }

        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", totalBytes.toString())
        val reqBody = meta.toRequestBody("application/json; charset=UTF-8".toMediaType())
        if (existingFileId.isNullOrBlank()) builder.post(reqBody) else builder.patch(reqBody)

        httpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Init resumable HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
            }
            return resp.header("Location")
                ?: throw IllegalStateException("Google Drive không trả Location resumable URI")
        }
    }

    private fun queryUploadedOffset(sessionUri: String, totalBytes: Long): Long {
        val req = Request.Builder()
            .url(sessionUri)
            .put(ByteArray(0).toRequestBody(null))
            .header("Content-Range", "bytes */$totalBytes")
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 308) {
                    DriveResumableProtocol.parseNextOffsetFrom308(resp.header("Range"))
                } else {
                    0L
                }
            }
        }.getOrDefault(0L)
    }

    suspend fun deleteOrMoveOutOfInbox(token: String, fileId: String, oldParentId: String, newParentId: String) {
        withContext(Dispatchers.IO) {
            val url = "https://www.googleapis.com/drive/v3/files/$fileId?addParents=$newParentId&removeParents=$oldParentId"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .patch("{}".toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().close()
        }
    }
}
