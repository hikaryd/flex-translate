package dev.flextranslate.foundation

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import dev.flextranslate.ui.AppScaffold
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.theme.FlexTheme

/**
 * Compose entry point. Renders the WS1 shell (FlexTheme { AppScaffold }). The RECORD_AUDIO
 * permission flow from the old raw-Views screen is preserved here via the modern
 * ActivityResultContracts API; mic-permission state is exposed to the Live screen as
 * [OfflineFirstState] through [LiveSessionState].
 */
class MainActivity : ComponentActivity() {

    private lateinit var session: LiveSessionState

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Re-read the real permission state regardless of grant result — no silent assumptions.
            if (::session.isInitialized) session.refreshPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlexTheme {
                val capture = remember { AudioCaptureController(applicationContext) }
                val modelStore = remember { AsrModelStore(applicationContext) }
                val mtModelStore = remember { MtModelStore(applicationContext) }
                val liveSession = remember { LiveSessionState(capture, modelStore, mtModelStore) }
                // Real in-app model download manager. Lands files in the same filesDir/models/<id>/
                // root the stores resolve, so a completed download is visible to the runtime.
                val downloadManager = remember {
                    ModelDownloadManager(
                        context = applicationContext,
                        resolveModelDir = { id -> liveSession.downloadDirFor(id) ?: java.io.File(filesDir, "models/$id") },
                    )
                }
                session = liveSession
                AppScaffold(
                    session = liveSession,
                    downloadManager = downloadManager,
                    onRequestPermission = {
                        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permission may have changed in system settings while backgrounded.
        if (::session.isInitialized) session.refreshPermission()
    }

    override fun onStop() {
        super.onStop()
        // Stop real mic capture when leaving the foreground.
        if (::session.isInitialized) session.stopCapture()
    }
}
