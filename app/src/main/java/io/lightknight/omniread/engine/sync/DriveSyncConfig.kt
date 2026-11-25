package io.lightknight.omniread.engine.sync

object DriveSyncConfig {
    const val ROOT_FOLDER_NAME = "OmniRead"
    const val INBOX_FOLDER_NAME = "Inbox"
    const val MEDIA_FOLDER_NAME = "media"
    const val SCRIPTS_FOLDER_NAME = "scripts"
    const val MANIFEST_FILE_NAME = "sync_manifest.json"
    const val CHUNK_SIZE_BYTES = 5 * 1024 * 1024 // 5 MB Google Drive Resumable chunk multiple (256 KB * 20)

    /**
     * Defaults to full Drive scope restricted in app logic to /OmniRead/ so files dropped from PC
     * into E:\Drive\OmniRead\Inbox are automatically visible to Android.
     * Flip to true to restrict strictly to app-created files (drive.file).
     */
    const val USE_DRIVE_FILE_ONLY_SCOPE = false

    val scopeUrl: String
        get() = if (USE_DRIVE_FILE_ONLY_SCOPE) {
            "https://www.googleapis.com/auth/drive.file"
        } else {
            "https://www.googleapis.com/auth/drive"
        }
}
