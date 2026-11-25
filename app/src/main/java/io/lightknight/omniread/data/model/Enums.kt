package io.lightknight.omniread.data.model

enum class MediaType {
    NOVEL,
    COMIC,
    AUDIOBOOK
}

enum class OriginType {
    LOCAL_IMPORT,
    WEB_PLUGIN,
    DIRECT_URL,
    YOUTUBE_PLAYLIST
}

enum class DownloadState {
    REMOTE,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    CLOUD_ONLY,
    FAILED
}

enum class ComicReadMode {
    WEBTOON,
    LTR,
    RTL
}

enum class NovelReadMode {
    SCROLL,
    PAGED
}

enum class PageTurnEffect(val displayNameVi: String) {
    CURL_3D("Lật 3D"),
    COVER_FLIP("Chồng trang"),
    SLIDE("Trượt ngang"),
    FADE("Mờ dần")
}

enum class AppThemeMode {
    LIGHT,
    SEPIA,
    DARK,
    AMOLED
}

enum class TtsMode {
    QUICK_LOCAL,
    AI_CAST
}
