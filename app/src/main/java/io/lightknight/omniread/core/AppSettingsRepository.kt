package io.lightknight.omniread.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.lightknight.omniread.data.model.AppThemeMode
import io.lightknight.omniread.data.model.ComicReadMode
import io.lightknight.omniread.data.model.NovelReadMode
import io.lightknight.omniread.data.model.PageTurnEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "omniread_settings")

data class AppSettings(
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val languageCode: String = "vi",
    val novelFontSizeSp: Float = 18f,
    val novelLineHeightMultiplier: Float = 1.55f,
    val novelHorizontalMarginDp: Int = 20,
    val novelFontFamily: String = "serif",
    val novelReadMode: NovelReadMode = NovelReadMode.SCROLL,
    val comicReadMode: ComicReadMode = ComicReadMode.WEBTOON,
    val comicAutoCropBorders: Boolean = false,
    val pageTurnEffect: PageTurnEffect = PageTurnEffect.CURL_3D,
    val novelCustomBgColorHex: Long = 0L,
    val novelCustomTextColorHex: Long = 0L,
    val defaultTtsEngineId: String = "EDGE_TTS",
    val defaultTtsVoiceId: String = "vi-VN-HoaiMyNeural",
    val defaultTtsRate: Float = 1.0f,
    val defaultTtsPitchHz: Int = 0,
    val geminiModel: String = "gemini-2.5-flash",
    val customTtsBaseUrl: String = "",
    val customTtsModel: String = "tts-1",
    val customTtsVoice: String = "alloy",
    val customTtsIsGemini: Boolean = false,
    val driveSyncEnabled: Boolean = true,
    val driveWifiOnlyForMedia: Boolean = true,
    val driveEmail: String = "",
    val driveLastSyncAt: Long = 0L,
    val syncProvider: String = "WEBDAV", // WEBDAV | GOOGLE_DRIVE
    val webDavPreset: String = "KOOFR", // KOOFR | INFINICLOUD | CUSTOM
    val webDavUrl: String = "https://app.koofr.net/dav/Koofr",
    val webDavUsername: String = "",
    val webDavConnected: Boolean = false,
    val volumeKeyPageTurn: Boolean = true,
    val comicAutoScrollSpeed: Float = 1.0f,
    val comicDoublePageLandscape: Boolean = true,
    val readerOrientationLock: String = "AUTO", // AUTO | PORTRAIT | LANDSCAPE
    val chapterCheckIntervalHours: Int = 12, // 0, 6, 12, 24
    val autoDownloadNewChapters: Boolean = false,
    val ytDlpVersion: String = "0.18.1",
    val deviceId: String = ""
)

class AppSettingsRepository(
    private val context: Context,
    private val secureKeyStore: SecureKeyStore
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language_code")
        val NOVEL_FONT_SIZE = floatPreferencesKey("novel_font_size")
        val NOVEL_LINE_HEIGHT = floatPreferencesKey("novel_line_height")
        val NOVEL_MARGIN = intPreferencesKey("novel_margin")
        val NOVEL_FONT_FAMILY = stringPreferencesKey("novel_font_family")
        val NOVEL_READ_MODE = stringPreferencesKey("novel_read_mode")
        val COMIC_READ_MODE = stringPreferencesKey("comic_read_mode")
        val COMIC_AUTO_CROP = booleanPreferencesKey("comic_auto_crop")
        val PAGE_TURN_EFFECT = stringPreferencesKey("page_turn_effect")
        val NOVEL_BG_COLOR = longPreferencesKey("novel_bg_color")
        val NOVEL_TEXT_COLOR = longPreferencesKey("novel_text_color")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val TTS_PITCH = intPreferencesKey("tts_pitch")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val CUSTOM_TTS_URL = stringPreferencesKey("custom_tts_url")
        val CUSTOM_TTS_MODEL = stringPreferencesKey("custom_tts_model")
        val CUSTOM_TTS_VOICE = stringPreferencesKey("custom_tts_voice")
        val CUSTOM_TTS_IS_GEMINI = booleanPreferencesKey("custom_tts_is_gemini")
        val DRIVE_SYNC_ENABLED = booleanPreferencesKey("drive_sync_enabled")
        val DRIVE_WIFI_ONLY = booleanPreferencesKey("drive_wifi_only")
        val DRIVE_EMAIL = stringPreferencesKey("drive_email")
        val DRIVE_LAST_SYNC = longPreferencesKey("drive_last_sync")
        val SYNC_PROVIDER = stringPreferencesKey("sync_provider")
        val WEBDAV_PRESET = stringPreferencesKey("webdav_preset")
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USERNAME = stringPreferencesKey("webdav_username")
        val WEBDAV_CONNECTED = booleanPreferencesKey("webdav_connected")
        val VOLUME_KEY_PAGE_TURN = booleanPreferencesKey("volume_key_page_turn")
        val COMIC_AUTO_SCROLL_SPEED = floatPreferencesKey("comic_auto_scroll_speed")
        val COMIC_DOUBLE_PAGE = booleanPreferencesKey("comic_double_page_landscape")
        val READER_ORIENTATION = stringPreferencesKey("reader_orientation_lock")
        val CHAPTER_CHECK_HOURS = intPreferencesKey("chapter_check_interval_hours")
        val AUTO_DL_NEW_CHAPTERS = booleanPreferencesKey("auto_download_new_chapters")
        val YTDLP_VERSION = stringPreferencesKey("ytdlp_version")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() }
                ?: AppThemeMode.DARK,
            languageCode = prefs[Keys.LANGUAGE] ?: "vi",
            novelFontSizeSp = prefs[Keys.NOVEL_FONT_SIZE] ?: 18f,
            novelLineHeightMultiplier = prefs[Keys.NOVEL_LINE_HEIGHT] ?: 1.55f,
            novelHorizontalMarginDp = prefs[Keys.NOVEL_MARGIN] ?: 20,
            novelFontFamily = prefs[Keys.NOVEL_FONT_FAMILY] ?: "serif",
            novelReadMode = prefs[Keys.NOVEL_READ_MODE]?.let { runCatching { NovelReadMode.valueOf(it) }.getOrNull() }
                ?: NovelReadMode.SCROLL,
            comicReadMode = prefs[Keys.COMIC_READ_MODE]?.let { runCatching { ComicReadMode.valueOf(it) }.getOrNull() }
                ?: ComicReadMode.WEBTOON,
            comicAutoCropBorders = prefs[Keys.COMIC_AUTO_CROP] ?: false,
            pageTurnEffect = prefs[Keys.PAGE_TURN_EFFECT]?.let { runCatching { PageTurnEffect.valueOf(it) }.getOrNull() }
                ?: PageTurnEffect.CURL_3D,
            novelCustomBgColorHex = prefs[Keys.NOVEL_BG_COLOR] ?: 0L,
            novelCustomTextColorHex = prefs[Keys.NOVEL_TEXT_COLOR] ?: 0L,
            defaultTtsEngineId = prefs[Keys.TTS_ENGINE] ?: "EDGE_TTS",
            defaultTtsVoiceId = prefs[Keys.TTS_VOICE] ?: "vi-VN-HoaiMyNeural",
            defaultTtsRate = prefs[Keys.TTS_RATE] ?: 1.0f,
            defaultTtsPitchHz = prefs[Keys.TTS_PITCH] ?: 0,
            geminiModel = prefs[Keys.GEMINI_MODEL] ?: "gemini-2.5-flash",
            customTtsBaseUrl = prefs[Keys.CUSTOM_TTS_URL] ?: "",
            customTtsModel = prefs[Keys.CUSTOM_TTS_MODEL] ?: "tts-1",
            customTtsVoice = prefs[Keys.CUSTOM_TTS_VOICE] ?: "alloy",
            customTtsIsGemini = prefs[Keys.CUSTOM_TTS_IS_GEMINI] ?: false,
            driveSyncEnabled = prefs[Keys.DRIVE_SYNC_ENABLED] ?: true,
            driveWifiOnlyForMedia = prefs[Keys.DRIVE_WIFI_ONLY] ?: true,
            driveEmail = prefs[Keys.DRIVE_EMAIL] ?: "",
            driveLastSyncAt = prefs[Keys.DRIVE_LAST_SYNC] ?: 0L,
            syncProvider = prefs[Keys.SYNC_PROVIDER] ?: "WEBDAV",
            webDavPreset = prefs[Keys.WEBDAV_PRESET] ?: "KOOFR",
            webDavUrl = prefs[Keys.WEBDAV_URL] ?: "https://app.koofr.net/dav/Koofr",
            webDavUsername = prefs[Keys.WEBDAV_USERNAME] ?: "",
            webDavConnected = prefs[Keys.WEBDAV_CONNECTED] ?: false,
            volumeKeyPageTurn = prefs[Keys.VOLUME_KEY_PAGE_TURN] ?: true,
            comicAutoScrollSpeed = prefs[Keys.COMIC_AUTO_SCROLL_SPEED] ?: 1.0f,
            comicDoublePageLandscape = prefs[Keys.COMIC_DOUBLE_PAGE] ?: true,
            readerOrientationLock = prefs[Keys.READER_ORIENTATION] ?: "AUTO",
            chapterCheckIntervalHours = prefs[Keys.CHAPTER_CHECK_HOURS] ?: 12,
            autoDownloadNewChapters = prefs[Keys.AUTO_DL_NEW_CHAPTERS] ?: false,
            ytDlpVersion = prefs[Keys.YTDLP_VERSION] ?: "0.18.1",
            deviceId = prefs[Keys.DEVICE_ID] ?: ""
        )
    }

    suspend fun ensureDeviceId(): String {
        val current = context.dataStore.data.first()[Keys.DEVICE_ID]
        if (!current.isNullOrBlank()) return current
        val created = "android-" + UUID.randomUUID().toString().take(8)
        context.dataStore.edit { it[Keys.DEVICE_ID] = created }
        return created
    }

    suspend fun updateThemeMode(mode: AppThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun updateLanguage(code: String) = edit { it[Keys.LANGUAGE] = code }
    suspend fun updateNovelFontSize(sp: Float) = edit { it[Keys.NOVEL_FONT_SIZE] = sp.coerceIn(12f, 34f) }
    suspend fun updateNovelLineHeight(mult: Float) = edit { it[Keys.NOVEL_LINE_HEIGHT] = mult.coerceIn(1.1f, 2.2f) }
    suspend fun updateNovelMargin(dp: Int) = edit { it[Keys.NOVEL_MARGIN] = dp.coerceIn(8, 48) }
    suspend fun updateNovelFontFamily(family: String) = edit { it[Keys.NOVEL_FONT_FAMILY] = family }
    suspend fun updateNovelReadMode(mode: NovelReadMode) = edit { it[Keys.NOVEL_READ_MODE] = mode.name }
    suspend fun updateComicReadMode(mode: ComicReadMode) = edit { it[Keys.COMIC_READ_MODE] = mode.name }
    suspend fun updateComicAutoCrop(enabled: Boolean) = edit { it[Keys.COMIC_AUTO_CROP] = enabled }
    suspend fun updatePageTurnEffect(effect: PageTurnEffect) = edit { it[Keys.PAGE_TURN_EFFECT] = effect.name }
    suspend fun updateNovelColors(bgHex: Long, textHex: Long) = edit {
        it[Keys.NOVEL_BG_COLOR] = bgHex
        it[Keys.NOVEL_TEXT_COLOR] = textHex
    }
    suspend fun updateDefaultTts(engineId: String, voiceId: String, rate: Float, pitchHz: Int) = edit {
        it[Keys.TTS_ENGINE] = engineId
        it[Keys.TTS_VOICE] = voiceId
        it[Keys.TTS_RATE] = rate.coerceIn(0.5f, 2.5f)
        it[Keys.TTS_PITCH] = pitchHz.coerceIn(-25, 25)
    }
    suspend fun updateGeminiConfig(model: String, apiKey: String) {
        edit { it[Keys.GEMINI_MODEL] = model.ifBlank { "gemini-2.5-flash" } }
        secureKeyStore.putString(SecureKeyStore.KEY_GEMINI_API, apiKey.trim())
    }
    fun getGeminiApiKey(): String = secureKeyStore.getString(SecureKeyStore.KEY_GEMINI_API)

    suspend fun updateCustomTtsConfig(
        baseUrl: String,
        model: String,
        voice: String,
        isGemini: Boolean,
        apiKey: String
    ) {
        edit {
            it[Keys.CUSTOM_TTS_URL] = baseUrl.trim()
            it[Keys.CUSTOM_TTS_MODEL] = model.trim()
            it[Keys.CUSTOM_TTS_VOICE] = voice.trim()
            it[Keys.CUSTOM_TTS_IS_GEMINI] = isGemini
        }
        secureKeyStore.putString(SecureKeyStore.KEY_CUSTOM_TTS_API, apiKey.trim())
    }
    fun getCustomTtsApiKey(): String = secureKeyStore.getString(SecureKeyStore.KEY_CUSTOM_TTS_API)

    suspend fun updateDriveSettings(enabled: Boolean, wifiOnly: Boolean) = edit {
        it[Keys.DRIVE_SYNC_ENABLED] = enabled
        it[Keys.DRIVE_WIFI_ONLY] = wifiOnly
    }
    suspend fun updateDriveAccount(email: String) = edit { it[Keys.DRIVE_EMAIL] = email }
    suspend fun updateLastSyncTime(ts: Long) = edit { it[Keys.DRIVE_LAST_SYNC] = ts }
    suspend fun updateSyncProvider(provider: String) = edit { it[Keys.SYNC_PROVIDER] = provider }

    suspend fun updateWebDavConfig(
        preset: String,
        url: String,
        username: String,
        password: String,
        connected: Boolean
    ) {
        edit {
            it[Keys.SYNC_PROVIDER] = "WEBDAV"
            it[Keys.WEBDAV_PRESET] = preset
            it[Keys.WEBDAV_URL] = url.trim().trimEnd('/')
            it[Keys.WEBDAV_USERNAME] = username.trim()
            it[Keys.WEBDAV_CONNECTED] = connected
        }
        if (password.isNotBlank()) {
            secureKeyStore.putString(SecureKeyStore.KEY_WEBDAV_PASSWORD, password)
        }
    }

    suspend fun disconnectWebDav() {
        secureKeyStore.remove(SecureKeyStore.KEY_WEBDAV_PASSWORD)
        edit { it[Keys.WEBDAV_CONNECTED] = false }
    }

    fun getWebDavPassword(): String = secureKeyStore.getString(SecureKeyStore.KEY_WEBDAV_PASSWORD)

    suspend fun updateVolumeKeyPageTurn(enabled: Boolean) = edit { it[Keys.VOLUME_KEY_PAGE_TURN] = enabled }
    suspend fun updateComicAutoScrollSpeed(speed: Float) = edit { it[Keys.COMIC_AUTO_SCROLL_SPEED] = speed.coerceIn(0.4f, 3.5f) }
    suspend fun updateComicDoublePageLandscape(enabled: Boolean) = edit { it[Keys.COMIC_DOUBLE_PAGE] = enabled }
    suspend fun updateReaderOrientationLock(lock: String) = edit { it[Keys.READER_ORIENTATION] = lock }
    suspend fun updateChapterTrackerConfig(intervalHours: Int, autoDownload: Boolean) = edit {
        it[Keys.CHAPTER_CHECK_HOURS] = intervalHours
        it[Keys.AUTO_DL_NEW_CHAPTERS] = autoDownload
    }

    suspend fun updateYtDlpVersion(version: String) = edit { it[Keys.YTDLP_VERSION] = version }

    private suspend inline fun edit(crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
