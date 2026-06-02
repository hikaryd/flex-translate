package dev.flextranslate.foundation

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.flextranslate.ui.AppScaffold
import dev.flextranslate.ui.LiveSessionState
import dev.flextranslate.ui.i18n.AppLanguage
import dev.flextranslate.ui.i18n.AppLanguageStore
import dev.flextranslate.ui.i18n.LocalStrings
import dev.flextranslate.ui.i18n.stringsFor
import dev.flextranslate.ui.theme.FlexTheme
import kotlinx.coroutines.launch

/**
 * Compose entry point. Renders the WS1 shell (FlexTheme { AppScaffold }). The RECORD_AUDIO
 * permission flow from the old raw-Views screen is preserved here via the modern
 * ActivityResultContracts API; mic-permission state is exposed to the Live screen as
 * [OfflineFirstState] through [LiveSessionState].
 *
 * Language state: [AppLanguageStore] loads the persisted choice (defaulting to the system locale)
 * and stores it in a Compose state variable. [CompositionLocalProvider] wraps the entire content
 * tree with the matching [Strings] catalog; switching language updates the state variable and
 * triggers a full recomposition in the new language — no Activity restart needed. The session's
 * [LiveSessionState.uiStrings] is kept in sync so translation-reason strings produced outside the
 * composition are also localised.
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
        val languageStore = AppLanguageStore(applicationContext)
        setContent {
            // Language state: loaded once from SharedPreferences, then held in Compose state so
            // toggling it recomposes the entire tree instantly without restarting the Activity.
            var appLanguage by remember { mutableStateOf(languageStore.load()) }
            val strings = remember(appLanguage) { stringsFor(appLanguage) }

            FlexTheme {
                CompositionLocalProvider(LocalStrings provides strings) {
                    val capture = remember { AudioCaptureController(applicationContext) }
                    val modelStore = remember { AsrModelStore(applicationContext) }
                    val mtModelStore = remember { MtModelStore(applicationContext) }
                    val liveSession = remember { LiveSessionState(capture, modelStore, mtModelStore) }
                    // Keep session's uiStrings in sync with the currently active language so that
                    // translation-reason strings produced in translateFinal() are localised.
                    liveSession.uiStrings = strings

                    // Real in-app model download manager. Lands files in the same filesDir/models/<id>/
                    // root the stores resolve, so a completed download is visible to the runtime.
                    val downloadManager = remember {
                        ModelDownloadManager(
                            context = applicationContext,
                            resolveModelDir = { id ->
                                liveSession.downloadDirFor(id) ?: java.io.File(filesDir, "models/$id")
                            },
                        )
                    }
                    session = liveSession
                    AppScaffold(
                        session = liveSession,
                        downloadManager = downloadManager,
                        onRequestPermission = {
                            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onLanguageChange = { lang ->
                            appLanguage = lang
                            languageStore.save(lang)
                        },
                        selectedLanguage = appLanguage,
                    )
                }
            }
        }
        maybeRunBenchmark()
    }

    /**
     * Debug-only benchmark path. Activated by launching the app with:
     *   adb shell am start -n dev.flextranslate/.foundation.MainActivity -e BENCH 1
     * Runs [BenchmarkRunner] on a background coroutine; results appear in logcat under FlexBench.
     * Has zero effect in normal (non-BENCH) launches.
     */
    private fun maybeRunBenchmark() {
        val bench = intent?.getStringExtra(BenchmarkRunner.INTENT_EXTRA) ?: return
        if (bench != "1") return
        Log.i(BenchmarkRunner.TAG, "BENCH intent detected — starting benchmark run")
        lifecycleScope.launch {
            BenchmarkRunner.run(applicationContext)
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
