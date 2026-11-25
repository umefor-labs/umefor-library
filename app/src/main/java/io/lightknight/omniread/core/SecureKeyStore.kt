package io.lightknight.omniread.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores sensitive API keys and tokens using Android Keystore backed EncryptedSharedPreferences.
 */
class SecureKeyStore(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "omniread_secure_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        // Fallback only if Keystore is unavailable on an unusual ROM
        context.getSharedPreferences("omniread_secure_keys_fallback", Context.MODE_PRIVATE)
    }

    fun getString(key: String): String = prefs.getString(key, "").orEmpty()

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val KEY_GEMINI_API = "gemini_api_key"
        const val KEY_CUSTOM_TTS_API = "custom_tts_api_key"
        const val KEY_DRIVE_ACCESS_TOKEN = "drive_access_token"
        const val KEY_DRIVE_REFRESH_TOKEN = "drive_refresh_token"
        const val KEY_DRIVE_TOKEN_EXPIRY_MS = "drive_token_expiry_ms"
        const val KEY_DRIVE_OAUTH_CLIENT_ID = "drive_oauth_client_id"
        const val KEY_DRIVE_OAUTH_CLIENT_SECRET = "drive_oauth_client_secret"
        const val KEY_WEBDAV_PASSWORD = "webdav_password"
    }
}
