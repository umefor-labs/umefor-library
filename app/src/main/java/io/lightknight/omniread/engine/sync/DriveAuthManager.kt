package io.lightknight.omniread.engine.sync

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import io.lightknight.omniread.core.AppSettingsRepository
import io.lightknight.omniread.core.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { res -> if (cont.isActive) cont.resume(res) }
    addOnFailureListener { err -> if (cont.isActive) cont.resumeWithException(err) }
    addOnCanceledListener { cont.cancel() }
}

sealed interface DriveAuthOutcome {
    data class Authorized(val accessToken: String) : DriveAuthOutcome
    data class NeedsUserConsent(val pendingIntent: PendingIntent) : DriveAuthOutcome
    data class Failed(val message: String) : DriveAuthOutcome
}

class DriveAuthManager(
    private val context: Context,
    private val secureKeyStore: SecureKeyStore,
    private val settingsRepo: AppSettingsRepository,
    private val httpClient: OkHttpClient
) {
    private val client by lazy { Identity.getAuthorizationClient(context) }

    fun getCachedAccessToken(): String =
        secureKeyStore.getString(SecureKeyStore.KEY_DRIVE_ACCESS_TOKEN)

    fun getSavedOAuthClientId(): String =
        secureKeyStore.getString(SecureKeyStore.KEY_DRIVE_OAUTH_CLIENT_ID)

    fun getSavedOAuthClientSecret(): String =
        secureKeyStore.getString(SecureKeyStore.KEY_DRIVE_OAUTH_CLIENT_SECRET)

    fun saveOAuthClientConfig(clientId: String, clientSecret: String) {
        secureKeyStore.putString(SecureKeyStore.KEY_DRIVE_OAUTH_CLIENT_ID, clientId.trim())
        secureKeyStore.putString(SecureKeyStore.KEY_DRIVE_OAUTH_CLIENT_SECRET, clientSecret.trim())
    }

    fun getCurrentSha1(): String = getAppSigningSha1(context)

    suspend fun requestAuthorization(): DriveAuthOutcome = withContext(Dispatchers.IO) {
        val refreshed = refreshAccessTokenIfNeeded()
        if (refreshed != null) {
            return@withContext DriveAuthOutcome.Authorized(refreshed)
        }

        runCatching {
            val req = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DriveSyncConfig.scopeUrl)))
                .build()
            val result = client.authorize(req).awaitResult()
            if (result.hasResolution()) {
                val pi = result.pendingIntent
                    ?: return@runCatching DriveAuthOutcome.Failed("Thiếu PendingIntent xác thực Drive.")
                DriveAuthOutcome.NeedsUserConsent(pi)
            } else {
                val token = result.accessToken.orEmpty()
                if (token.isBlank()) {
                    DriveAuthOutcome.Failed("Không lấy được access token Google Drive.")
                } else {
                    secureKeyStore.putString(SecureKeyStore.KEY_DRIVE_ACCESS_TOKEN, token)
                    val email = result.toGoogleSignInAccount()?.email.orEmpty()
                    if (email.isNotBlank()) settingsRepo.updateDriveAccount(email)
                    DriveAuthOutcome.Authorized(token)
                }
            }
        }.getOrElse { err ->
            DriveAuthOutcome.Failed(formatPlayServicesAuthError(err, getCurrentSha1()))
        }
    }

    suspend fun signInViaBrowserOAuth(
        activity: Activity,
        clientId: String,
        clientSecret: String = ""
    ): DriveAuthOutcome = withContext(Dispatchers.IO) {
        val cleanId = clientId.trim()
        val cleanSecret = clientSecret.trim()
        if (cleanId.isBlank()) {
            return@withContext DriveAuthOutcome.Failed(
                "Vui lòng dán OAuth Client ID (Desktop/Web) từ Google Cloud Console trước khi đăng nhập bằng trình duyệt."
            )
        }
        saveOAuthClientConfig(cleanId, cleanSecret)

        runCatching {
            val (codeVerifier, codeChallenge) = generatePkcePair()
            val state = generatePkcePair().first.take(24)

            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 180_000
                val redirectUri = "http://127.0.0.1:${server.localPort}/oauth2callback"
                val authUrl = buildString {
                    append("https://accounts.google.com/o/oauth2/v2/auth")
                    append("?client_id=").append(URLEncoder.encode(cleanId, "UTF-8"))
                    append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
                    append("&response_type=code")
                    append("&scope=").append(URLEncoder.encode("${DriveSyncConfig.scopeUrl} https://www.googleapis.com/auth/userinfo.email", "UTF-8"))
                    append("&code_challenge=").append(URLEncoder.encode(codeChallenge, "UTF-8"))
                    append("&code_challenge_method=S256")
                    append("&access_type=offline")
                    append("&prompt=consent")
                    append("&state=").append(URLEncoder.encode(state, "UTF-8"))
                }

                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                }

                val socket = server.accept()
                val queryParams = socket.use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    val requestLine = reader.readLine().orEmpty()
                    val pathWithQuery = requestLine.split(' ').getOrNull(1).orEmpty()
                    val parsed = parseLoopbackQueryParams(pathWithQuery)
                    val isOk = parsed["error"].isNullOrBlank() && !parsed["code"].isNullOrBlank()
                    val html = if (isOk) {
                        """<html><meta charset="utf-8"><body style="font-family:sans-serif;text-align:center;padding:40px;background:#102E2B;color:#D4EF79"><h2>Đăng nhập thành công!</h2><p style="color:#E6F2EB">Bạn có thể đóng tab này và quay lại ứng dụng <b>Umefor Library</b>.</p></body></html>"""
                    } else {
                        """<html><meta charset="utf-8"><body style="font-family:sans-serif;text-align:center;padding:40px;background:#2b1010;color:#ffd2d2"><h2>Đăng nhập chưa hoàn tất</h2><p>${parsed["error"] ?: "Không nhận được mã xác thực"}</p></body></html>"""
                    }
                    val respBytes = html.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n"
                    sock.getOutputStream().apply {
                        write(header.toByteArray(Charsets.UTF_8))
                        write(respBytes)
                        flush()
                    }
                    parsed
                }

                val errParam = queryParams["error"]
                if (!errParam.isNullOrBlank()) {
                    throw IllegalStateException("Google từ chối đăng nhập: $errParam")
                }
                if (queryParams["state"] != state) {
                    throw IllegalStateException("Sai mã state OAuth, vui lòng thử lại.")
                }
                val code = queryParams["code"]
                    ?: throw IllegalStateException("Không nhận được authorization code từ Google.")

                val formBuilder = FormBody.Builder()
                    .add("client_id", cleanId)
                    .add("code", code)
                    .add("code_verifier", codeVerifier)
                    .add("redirect_uri", redirectUri)
                    .add("grant_type", "authorization_code")
                if (cleanSecret.isNotBlank()) {
                    formBuilder.add("client_secret", cleanSecret)
                }

                val tokenReq = Request.Builder()
                    .url(TOKEN_ENDPOINT)
                    .post(formBuilder.build())
                    .build()

                val tokenJsonText = httpClient.newCall(tokenReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Đổi token thất bại (HTTP ${resp.code}): ${extractOAuthError(body)}")
                    }
                    body
                }

                val tokenObj = jsonParser.parseToJsonElement(tokenJsonText).jsonObject
                val accessToken = tokenObj["access_token"]?.jsonPrimitive?.content.orEmpty()
                if (accessToken.isBlank()) {
                    throw IllegalStateException("Google không trả về access_token.")
                }
                val refreshToken = tokenObj["refresh_token"]?.jsonPrimitive?.content.orEmpty()
                val expiresInSec = tokenObj["expires_in"]?.jsonPrimitive?.intOrNull ?: 3500
                val expiryMs = System.currentTimeMillis() + (expiresInSec * 1000L)

                secureKeyStore.putString(SecureKeyStore.KEY_DRIVE_ACCESS_TOKEN, accessToken)
                if (refreshToken.isNotBlank()) {
                    secureKeyStore.putString(SecureKeyStore.KEY_DRIVE_REFRESH_TOKEN, refreshToken)
                }
                secureKeyStore.putString(SecureKeyStore.KEY_DRIVE_TOKEN_EXPIRY_MS, expiryMs.toString())

                val email = fetchGoogleEmail(accessToken).ifBlank { "Google Drive (OAuth)" }
                settingsRepo.updateDriveAccount(email)
                DriveAuthOutcome.Authorized(accessToken)
            }
        }.getOrElse { err ->
            DriveAuthOutcome.Failed(err.message ?: "Đăng nhập qua trình duyệt thất bại.")
        }
    }

    private fun refreshAccessTokenIfNeeded(): String? {
        val refreshToken = secureKeyStore.getString(SecureKeyStore.KEY_DRIVE_REFRESH_TOKEN)
        if (refreshToken.isBlank()) return null

        val cached = getCachedAccessToken()
        val expiryMs = secureKeyStore.getString(SecureKeyStore.KEY_DRIVE_TOKEN_EXPIRY_MS).toLongOrNull() ?: 0L
        if (cached.isNotBlank() && System.currentTimeMillis() + 60_000L < expiryMs) {
            return cached
        }

        val clientId = getSavedOAuthClientId()
        if (clientId.isBlank()) return null
        val clientSecret = getSavedOAuthClientSecret()

        return runCatching {
            val formBuilder = FormBody.Builder()
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
            if (clientSecret.isNotBlank()) {
                formBuilder.add("client_secret", clientSecret)
            }
            val req = Request.Builder().url(TOKEN_ENDPOINT).post(formBuilder.build()).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                val obj = jsonParser.parseToJsonElement(body).jsonObject
                val newAccess = obj["access_token"]?.jsonPrimitive?.content.orEmpty()
                if (newAccess.isBlank()) return@runCatching null
                val expiresIn = obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 3500
                secureKeyStore.putString(SecureKeyStore.KEY_DRIVE_ACCESS_TOKEN, newAccess)
                secureKeyStore.putString(
                    SecureKeyStore.KEY_DRIVE_TOKEN_EXPIRY_MS,
                    (System.currentTimeMillis() + expiresIn * 1000L).toString()
                )
                newAccess
            }
        }.getOrNull()
    }

    private fun fetchGoogleEmail(accessToken: String): String {
        return runCatching {
            val req = Request.Builder()
                .url("https://www.googleapis.com/oauth2/v2/userinfo")
                .header("Authorization", "Bearer $accessToken")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching ""
                val body = resp.body?.string().orEmpty()
                jsonParser.parseToJsonElement(body).jsonObject["email"]?.jsonPrimitive?.content.orEmpty()
            }
        }.getOrDefault("")
    }

    suspend fun handleActivityResult(data: Intent?): DriveAuthOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val result = client.getAuthorizationResultFromIntent(data)
            val token = result.accessToken.orEmpty()
            if (token.isBlank()) {
                DriveAuthOutcome.Failed("Người dùng chưa cấp quyền hoặc token rỗng.")
            } else {
                secureKeyStore.putString(SecureKeyStore.KEY_DRIVE_ACCESS_TOKEN, token)
                val email = result.toGoogleSignInAccount()?.email.orEmpty()
                if (email.isNotBlank()) {
                    settingsRepo.updateDriveAccount(email)
                } else {
                    settingsRepo.updateDriveAccount("Google Drive (Đã kết nối)")
                }
                DriveAuthOutcome.Authorized(token)
            }
        }.getOrElse { err ->
            DriveAuthOutcome.Failed(formatPlayServicesAuthError(err, getCurrentSha1()))
        }
    }

    suspend fun signOut() {
        secureKeyStore.remove(SecureKeyStore.KEY_DRIVE_ACCESS_TOKEN)
        secureKeyStore.remove(SecureKeyStore.KEY_DRIVE_REFRESH_TOKEN)
        secureKeyStore.remove(SecureKeyStore.KEY_DRIVE_TOKEN_EXPIRY_MS)
        settingsRepo.updateDriveAccount("")
    }

    companion object {
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private val jsonParser = Json { ignoreUnknownKeys = true }

        @Suppress("DEPRECATION")
        fun getAppSigningSha1(context: Context): String {
            return runCatching {
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    )
                } else {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_SIGNATURES
                    )
                }
                val sigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                } else {
                    pkgInfo.signatures?.firstOrNull()?.toByteArray()
                } ?: return@runCatching ""
                val digest = MessageDigest.getInstance("SHA-1").digest(sigBytes)
                digest.joinToString(":") { b -> String.format(Locale.US, "%02X", b) }
            }.getOrDefault("")
        }

        fun generatePkcePair(): Pair<String, String> {
            val raw = ByteArray(32)
            SecureRandom().nextBytes(raw)
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val verifier = encoder.encodeToString(raw)
            val challengeBytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            val challenge = encoder.encodeToString(challengeBytes)
            return verifier to challenge
        }

        fun parseLoopbackQueryParams(requestTarget: String): Map<String, String> {
            val qIdx = requestTarget.indexOf('?')
            if (qIdx < 0 || qIdx >= requestTarget.length - 1) return emptyMap()
            val query = requestTarget.substring(qIdx + 1)
            val map = mutableMapOf<String, String>()
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                    val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    map[k] = v
                }
            }
            return map
        }

        fun formatPlayServicesAuthError(throwable: Throwable, sha1: String): String {
            val apiEx = generateSequence(throwable) { it.cause }.filterIsInstance<ApiException>().firstOrNull()
            val rawMsg = throwable.message.orEmpty()
            val statusCode = apiEx?.statusCode ?: Regex("\\b(10|16|7|12500|12501|12502)\\b")
                .find(rawMsg)?.value?.toIntOrNull()

            return when (statusCode) {
                10 -> "Lỗi cấu hình OAuth Android (mã 10): Google Cloud chưa có Android OAuth Client khớp gói io.lightknight.omniread + SHA-1 (${sha1.ifBlank { "xem trong Cài đặt" }}). Bạn cũng có thể dùng 'Đăng nhập qua Trình duyệt (OAuth PKCE)' ở Cài đặt."
                16 -> "Tài khoản chưa được cấp quyền hoặc chưa thêm vào Test users trên Google Cloud (mã 16). Vui lòng kiểm tra OAuth Consent Screen hoặc thử đăng nhập qua Trình duyệt."
                7 -> "Lỗi kết nối mạng tới dịch vụ Google (mã 7). Kiểm tra Internet rồi thử lại."
                12501 -> "Đã hủy đăng nhập Google."
                12500 -> "Cấu hình Google OAuth chưa hoàn chỉnh trên Google Cloud (mã 12500). Hãy dùng mục 'Đăng nhập qua Trình duyệt (OAuth PKCE)' trong Cài đặt."
                else -> if (rawMsg.isNotBlank()) "Lỗi xác thực Google Drive: $rawMsg" else "Lỗi xác thực Google Drive"
            }
        }

        private fun extractOAuthError(jsonBody: String): String {
            return runCatching {
                val obj = jsonParser.parseToJsonElement(jsonBody).jsonObject
                val e = obj["error"]?.jsonPrimitive?.content.orEmpty()
                val desc = obj["error_description"]?.jsonPrimitive?.content.orEmpty()
                listOf(e, desc).filter { it.isNotBlank() }.joinToString(" - ")
            }.getOrDefault(jsonBody.take(160))
        }
    }
}
