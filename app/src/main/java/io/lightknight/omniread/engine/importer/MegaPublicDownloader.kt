package io.lightknight.omniread.engine.importer

import io.lightknight.omniread.core.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class MegaLinkKind { FILE, FOLDER }

data class MegaLinkSpec(
    val kind: MegaLinkKind,
    val id: String,
    val keyBase64: String
)

private data class MegaResolvedFile(
    val nodeId: String,
    val fileName: String,
    val sizeBytes: Long,
    val aesKey16: ByteArray,
    val ctrIv16: ByteArray
)

class MegaPublicDownloader(
    private val httpClient: OkHttpClient
) {
    suspend fun downloadEbook(megaUrl: String, destDir: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val spec = parseMegaLink(megaUrl)
                ?: throw IllegalArgumentException("Liên kết MEGA không hợp lệ: $megaUrl")

            when (spec.kind) {
                MegaLinkKind.FILE -> downloadSingleFile(spec, destDir)
                MegaLinkKind.FOLDER -> downloadBestFileFromFolder(spec, destDir)
            }
        }
    }

    private fun downloadSingleFile(spec: MegaLinkSpec, destDir: File): File {
        val nodeKey32 = decodeMegaBase64(spec.keyBase64)
        if (nodeKey32.size < 32) {
            throw IllegalArgumentException("Khóa giải mã MEGA file không hợp lệ.")
        }
        val (aesKey16, ctrIv16) = deriveFileKeyAndIv(nodeKey32)

        val respRaw = postMegaApi(
            url = "$MEGA_API_BASE?id=1",
            payload = """[{"a":"g","g":1,"p":"${spec.id}"}]"""
        )
        val obj = json.parseToJsonElement(respRaw).jsonArray.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("MEGA không trả về dữ liệu tệp.")
        val dlUrl = obj["g"]?.jsonPrimitive?.content.orEmpty()
        if (dlUrl.isBlank()) {
            throw IllegalStateException("Không lấy được liên kết tải trực tiếp từ MEGA (có thể tệp đã bị xóa hoặc vượt giới hạn).")
        }
        val encAttr = obj["at"]?.jsonPrimitive?.content.orEmpty()
        val fileName = decryptMegaAttributes(encAttr, aesKey16) ?: "ebook_${spec.id}.epub"
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext in UNSUPPORTED_EBOOK_EXTS) {
            throw IllegalArgumentException("Tệp trên MEGA chỉ có định dạng .$ext chưa được hỗ trợ đọc trực tiếp.")
        }
        return streamDecryptToFile(dlUrl, fileName, aesKey16, ctrIv16, destDir)
    }

    private fun downloadBestFileFromFolder(spec: MegaLinkSpec, destDir: File): File {
        val folderKey16 = decodeMegaBase64(spec.keyBase64).copyOf(16)
        val listRaw = postMegaApi(
            url = "$MEGA_API_BASE?id=1&n=${spec.id}",
            payload = """[{"a":"f","c":1,"r":1,"ca":1}]"""
        )
        val rootObj = json.parseToJsonElement(listRaw).jsonArray.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("MEGA không trả về danh sách tệp thư mục.")
        val nodes = rootObj["f"]?.jsonArray
            ?: throw IllegalStateException("Thư mục MEGA trống hoặc không truy cập được.")

        val candidates = mutableListOf<MegaResolvedFile>()
        for (el in nodes) {
            val obj = el.jsonObject
            val nodeType = obj["t"]?.jsonPrimitive?.intOrNull ?: continue
            if (nodeType != 0) continue // 0 = file, 1 = folder
            val nodeId = obj["h"]?.jsonPrimitive?.content.orEmpty()
            val encAttr = obj["a"]?.jsonPrimitive?.content.orEmpty()
            val keyField = obj["k"]?.jsonPrimitive?.content.orEmpty()
            val encNodeKeyB64 = keyField.substringAfter(':', "")
            if (nodeId.isBlank() || encNodeKeyB64.isBlank()) continue

            val nodeKeyBytes = runCatching {
                decryptNodeKey(decodeMegaBase64(encNodeKeyB64), folderKey16)
            }.getOrNull() ?: continue
            if (nodeKeyBytes.size < 32) continue

            val (aesKey16, ctrIv16) = deriveFileKeyAndIv(nodeKeyBytes)
            val fileName = decryptMegaAttributes(encAttr, aesKey16).orEmpty()
            if (fileName.isBlank()) continue
            val sizeBytes = obj["s"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            candidates += MegaResolvedFile(
                nodeId = nodeId,
                fileName = fileName,
                sizeBytes = sizeBytes,
                aesKey16 = aesKey16,
                ctrIv16 = ctrIv16
            )
        }

        if (candidates.isEmpty()) {
            throw IllegalStateException("Không giải mã được danh sách tệp trong thư mục MEGA.")
        }

        val bestIdx = pickBestEbookFileName(candidates.map { it.fileName })
        if (bestIdx < 0) {
            val availNames = candidates.joinToString(", ") { it.fileName }
            throw IllegalArgumentException(
                "Thư mục MEGA không có EPUB/PDF/TXT hỗ trợ (chỉ có: $availNames)."
            )
        }
        val chosen = candidates[bestIdx]

        val dlRespRaw = postMegaApi(
            url = "$MEGA_API_BASE?id=2&n=${spec.id}",
            payload = """[{"a":"g","g":1,"n":"${chosen.nodeId}"}]"""
        )
        val dlObj = json.parseToJsonElement(dlRespRaw).jsonArray.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("MEGA không trả về đường dẫn tải cho ${chosen.fileName}.")
        val dlUrl = dlObj["g"]?.jsonPrimitive?.content.orEmpty()
        if (dlUrl.isBlank()) {
            throw IllegalStateException("Không lấy được liên kết tải trực tiếp cho ${chosen.fileName}.")
        }

        return streamDecryptToFile(dlUrl, chosen.fileName, chosen.aesKey16, chosen.ctrIv16, destDir)
    }

    private fun streamDecryptToFile(
        downloadUrl: String,
        originalFileName: String,
        aesKey16: ByteArray,
        ctrIv16: ByteArray,
        destDir: File
    ): File {
        destDir.mkdirs()
        val safeName = WebDownloadFileDetector.sanitizeFileName(originalFileName)
        val outFile = File(destDir, "mega_${HashUtils.sha256(originalFileName).take(8)}_$safeName")

        val req = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Tải dữ liệu từ MEGA thất bại (HTTP ${resp.code}).")
            }
            val bodyStream = resp.body?.byteStream()
                ?: throw IllegalStateException("Luồng dữ liệu MEGA bị rỗng.")
            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey16, "AES"), IvParameterSpec(ctrIv16))
            }
            CipherInputStream(bodyStream, cipher).use { decIn ->
                outFile.outputStream().use { out ->
                    decIn.copyTo(out, 16 * 1024)
                }
            }
        }
        if (!outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException("Tệp tải từ MEGA bị rỗng sau giải mã.")
        }
        return outFile
    }

    private fun postMegaApi(url: String, payload: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .post(payload.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("MEGA API trả lỗi HTTP ${resp.code}")
            }
            val raw = resp.body?.string().orEmpty().trim()
            if (raw.startsWith("[-") || raw == "-9" || raw == "-16") {
                throw IllegalStateException("MEGA từ chối truy cập liên kết (mã $raw).")
            }
            return raw
        }
    }

    companion object {
        private const val MEGA_API_BASE = "https://g.api.mega.co.nz/cs"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val SUPPORTED_EBOOK_PRIORITY = mapOf(
            "epub" to 0,
            "pdf" to 1,
            "txt" to 2,
            "cbz" to 3,
            "cbr" to 4,
            "mp3" to 5,
            "m4a" to 6,
            "m4b" to 7
        )
        private val UNSUPPORTED_EBOOK_EXTS = setOf("azw3", "mobi", "prc")

        private val MEGA_V2_REGEX = Regex(
            """https?://(?:mega\.nz|mega\.co\.nz)/(file|folder)/([A-Za-z0-9_-]+)#([A-Za-z0-9_-]+)""",
            RegexOption.IGNORE_CASE
        )
        private val MEGA_LEGACY_REGEX = Regex(
            """https?://(?:mega\.nz|mega\.co\.nz)/#?(F?)!([A-Za-z0-9_-]+)!([A-Za-z0-9_-]+)""",
            RegexOption.IGNORE_CASE
        )
        private val URL_SCAN_REGEX = Regex("""https?://[^\s<>"'\])]+""", RegexOption.IGNORE_CASE)

        fun parseMegaLink(url: String): MegaLinkSpec? {
            val trimmed = url.trim()
            MEGA_V2_REGEX.find(trimmed)?.let { m ->
                val kind = if (m.groupValues[1].equals("folder", ignoreCase = true)) {
                    MegaLinkKind.FOLDER
                } else {
                    MegaLinkKind.FILE
                }
                return MegaLinkSpec(kind, m.groupValues[2], m.groupValues[3])
            }
            MEGA_LEGACY_REGEX.find(trimmed)?.let { m ->
                val kind = if (m.groupValues[1].equals("F", ignoreCase = true)) {
                    MegaLinkKind.FOLDER
                } else {
                    MegaLinkKind.FILE
                }
                return MegaLinkSpec(kind, m.groupValues[2], m.groupValues[3])
            }
            return null
        }

        fun isDownloadableEbookUrl(url: String): Boolean {
            val low = url.trim().lowercase()
            if (parseMegaLink(url) != null) return true
            if (low.contains("drive.google.com/file/d/")) return true
            val pathOnly = low.substringBefore('?').substringBefore('#')
            return pathOnly.endsWith(".epub") || pathOnly.endsWith(".pdf") ||
                pathOnly.endsWith(".cbz") || pathOnly.endsWith(".cbr") || pathOnly.endsWith(".txt")
        }

        fun extractDownloadableEbookUrl(texts: Iterable<String>): String? {
            val urls = texts.flatMap { line ->
                URL_SCAN_REGEX.findAll(line).map { it.value.trimEnd('.', ',', ';', ')') }.toList()
            }
            // Prefer MEGA/Drive/EPUB/PDF links first
            return urls.firstOrNull { parseMegaLink(it) != null }
                ?: urls.firstOrNull { isDownloadableEbookUrl(it) }
        }

        fun decodeMegaBase64(b64: String): ByteArray {
            var clean = b64.trim().replace('-', '+').replace('_', '/')
            while (clean.length % 4 != 0) clean += "="
            return Base64.getDecoder().decode(clean)
        }

        fun decryptNodeKey(encryptedNodeKey: ByteArray, folderKey16: ByteArray): ByteArray {
            val keySpec = SecretKeySpec(folderKey16.copyOf(16), "AES")
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            return cipher.doFinal(encryptedNodeKey)
        }

        fun deriveFileKeyAndIv(nodeKey32: ByteArray): Pair<ByteArray, ByteArray> {
            val aesKey16 = ByteArray(16)
            for (i in 0 until 16) {
                aesKey16[i] = (nodeKey32[i].toInt() xor nodeKey32[i + 16].toInt()).toByte()
            }
            val ctrIv16 = ByteArray(16)
            System.arraycopy(nodeKey32, 16, ctrIv16, 0, 8)
            return aesKey16 to ctrIv16
        }

        fun decryptMegaAttributes(encryptedBase64: String, aesKey16: ByteArray): String? {
            if (encryptedBase64.isBlank()) return null
            return runCatching {
                val encryptedBytes = decodeMegaBase64(encryptedBase64)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(aesKey16.copyOf(16), "AES"),
                    IvParameterSpec(ByteArray(16))
                )
                val plain = String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
                    .trimEnd('\u0000')
                    .trim()
                if (!plain.startsWith("MEGA{")) return null
                val jsonBody = plain.removePrefix("MEGA")
                json.parseToJsonElement(jsonBody).jsonObject["n"]?.jsonPrimitive?.content?.trim()
            }.getOrNull()
        }

        fun pickBestEbookFileName(candidates: List<String>): Int {
            var bestIdx = -1
            var bestRank = Int.MAX_VALUE
            candidates.forEachIndexed { idx, name ->
                val ext = name.substringAfterLast('.', "").lowercase()
                val rank = SUPPORTED_EBOOK_PRIORITY[ext] ?: return@forEachIndexed
                if (rank < bestRank) {
                    bestRank = rank
                    bestIdx = idx
                }
            }
            return bestIdx
        }
    }
}
