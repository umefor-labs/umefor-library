# Umefor Library

Ứng dụng đọc **Truyện chữ**, **Truyện tranh**, nghe **TTS phân vai AI**, tải **Sách nói từ YouTube (audio-only)**, quản lý **Highlights/Ghi chú** và **Đồng bộ đám mây (WebDAV / Google Drive)** cho Android (Kotlin + Jetpack Compose + Material 3).

> Lưu ý tương thích nâng cấp: `applicationId` giữ `io.lightknight.omniread` để cài đè trực tiếp lên bản cũ mà không mất dữ liệu thư viện cục bộ.

## Hệ Extension Rời (Tachiyomi-style)

Umefor Library tách toàn bộ nguồn web ra khỏi lõi ứng dụng:
- **Kho Extension chính thức (Public):** [umefor-labs/umefor-library-extensions](https://github.com/umefor-labs/umefor-library-extensions)
- Người dùng mới mở app sẽ không bị cài sẵn nguồn rác; chỉ cần vào **Khám phá → Tiện ích** để chọn cài 1 chạm các nguồn mong muốn, cập nhật trực tiếp (`live update`) từ GitHub/jsDelivr, hoặc thêm URL kho repo bên thứ 3.
- Cộng đồng có thể đề xuất nguồn web mới hoặc báo lỗi nguồn/đổi tên miền ngay bằng mẫu Issue tại kho `umefor-library-extensions`.

## Tính năng chính

- **3 Trình đọc hợp nhất:** Truyện chữ (cuộn / lật trang 3D CURL, Cover Flip, Slide, Fade, tùy chỉnh màu nền & chữ, phím âm lượng chuyển trang, khóa xoay), Truyện tranh (Webtoon / Đọc ngang / Tự cuộn / 2 trang màn hình ngang), Sách nói (Media3 ExoPlayer + Mini-player + Sleep Timer + tua ±15s).
- **TTS & Phân vai AI (Gemini BYOK):** Edge TTS, Android TTS, Custom HTTP TTS + phân biệt giọng đọc từng nhân vật bằng Gemini + Từ điển phát âm / lọc quảng cáo + xuất chương thành file MP3 offline.
- **Tiện ích thông minh:** Highlight 4 màu + ghi chú lề (xuất Markdown), AI tóm tắt không spoil & giải thích ngữ cảnh, theo dõi chương mới chạy nền, kệ phân loại & thống kê đọc/nghe 7 ngày + chuỗi ngày (streak).
- **Đồng bộ đám mây:** Hỗ trợ **WebDAV** (Koofr 10GB, InfiniCLOUD 20GB, Nextcloud — không cần Client ID) và **Google Drive** (Browser OAuth PKCE).

## Build APK & Chạy Unit Test (Windows PowerShell)

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\lightknight\AppData\Local\Android\Sdk'
Set-Location 'E:\Drive\Project\OmniRead'
.\gradlew.bat testDebugUnitTest assembleDebug --console=plain
```

- Thư mục build chuyển hướng ra ngoài Google Drive tại `E:\GradleBuilds\OmniRead` (configured in `gradle.properties` / `build.gradle.kts`).
- Các APK đầu ra (`arm64-v8a`, `armeabi-v7a`, `universal`) nằm tại `E:\GradleBuilds\OmniRead\app\outputs\apk\debug\`.
