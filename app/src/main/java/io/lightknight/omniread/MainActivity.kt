package io.lightknight.omniread

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import io.lightknight.omniread.core.VolumeKeyBus
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import io.lightknight.omniread.core.AppSettings
import io.lightknight.omniread.engine.sync.DriveAuthOutcome
import io.lightknight.omniread.ui.navigation.OmniNavHost
import io.lightknight.omniread.ui.theme.OmniReadTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val driveConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val container = (application as OmniReadApp).container
        if (res.resultCode == Activity.RESULT_OK) {
            lifecycleScope.launch {
                when (val outcome = container.driveAuthManager.handleActivityResult(res.data)) {
                    is DriveAuthOutcome.Authorized -> {
                        Toast.makeText(this@MainActivity, "Đã kết nối Google Drive", Toast.LENGTH_SHORT).show()
                        container.syncOrchestrator.triggerImmediateSync(includeMedia = true)
                    }
                    is DriveAuthOutcome.Failed -> {
                        Toast.makeText(this@MainActivity, outcome.message, Toast.LENGTH_LONG).show()
                    }
                    else -> Unit
                }
            }
        } else {
            Toast.makeText(
                this@MainActivity,
                "Đã hủy hoặc không cấp quyền Google Drive (mã ${res.resultCode}). Nếu dùng Play Services bị lỗi, hãy thử 'Đăng nhập qua Trình duyệt (OAuth PKCE)' trong Cài đặt.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OmniReadApp).container
        setContent {
            val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
            OmniReadTheme(themeMode = settings.themeMode) {
                OmniNavHost(
                    container = container,
                    onSignInDrive = {
                        lifecycleScope.launch {
                            when (val auth = container.driveAuthManager.requestAuthorization()) {
                                is DriveAuthOutcome.Authorized -> {
                                    Toast.makeText(this@MainActivity, "Google Drive đã sẵn sàng", Toast.LENGTH_SHORT).show()
                                    container.syncOrchestrator.triggerImmediateSync(includeMedia = true)
                                }
                                is DriveAuthOutcome.NeedsUserConsent -> {
                                    val req = IntentSenderRequest.Builder(auth.pendingIntent.intentSender).build()
                                    driveConsentLauncher.launch(req)
                                }
                                is DriveAuthOutcome.Failed -> {
                                    Toast.makeText(this@MainActivity, auth.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    onSignInDriveBrowser = { clientId, clientSecret ->
                        lifecycleScope.launch {
                            when (val auth = container.driveAuthManager.signInViaBrowserOAuth(this@MainActivity, clientId, clientSecret)) {
                                is DriveAuthOutcome.Authorized -> {
                                    Toast.makeText(this@MainActivity, "Đã kết nối Google Drive qua Trình duyệt", Toast.LENGTH_SHORT).show()
                                    container.syncOrchestrator.triggerImmediateSync(includeMedia = true)
                                }
                                is DriveAuthOutcome.Failed -> {
                                    Toast.makeText(this@MainActivity, auth.message, Toast.LENGTH_LONG).show()
                                }
                                else -> Unit
                            }
                        }
                    }
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VolumeKeyBus.interceptVolumeKeys) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        VolumeKeyBus.emitPageTurn(+1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        VolumeKeyBus.emitPageTurn(-1)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
