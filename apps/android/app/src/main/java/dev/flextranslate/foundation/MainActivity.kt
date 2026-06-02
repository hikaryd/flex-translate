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
 * Точка входа Compose. Рисует оболочку WS1 (FlexTheme { AppScaffold }). Запрос разрешения
 * RECORD_AUDIO из старого экрана на голых View сохранён здесь через современный
 * ActivityResultContracts; состояние разрешения на микрофон отдаётся экрану Live как
 * [OfflineFirstState] через [LiveSessionState].
 *
 * Язык: [AppLanguageStore] подтягивает сохранённый выбор (по умолчанию — системная локаль)
 * и кладёт в Compose-state. [CompositionLocalProvider] оборачивает всё дерево нужным каталогом
 * [Strings]; смена языка обновляет state и перерисовывает дерево на новом языке — без рестарта
 * Activity. [LiveSessionState.uiStrings] держим в синхроне, чтобы строки причин перевода,
 * собранные вне композиции, тоже были локализованы.
 */
class MainActivity : ComponentActivity() {

    private lateinit var session: LiveSessionState

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Перечитываем реальное состояние разрешения независимо от ответа — без догадок.
            if (::session.isInitialized) session.refreshPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val languageStore = AppLanguageStore(applicationContext)
        setContent {
            // Язык: один раз читаем из SharedPreferences и держим в Compose-state — переключение
            // мгновенно перерисовывает дерево без рестарта Activity.
            var appLanguage by remember { mutableStateOf(languageStore.load()) }
            val strings = remember(appLanguage) { stringsFor(appLanguage) }

            FlexTheme {
                CompositionLocalProvider(LocalStrings provides strings) {
                    val capture = remember { AudioCaptureController(applicationContext) }
                    val modelStore = remember { AsrModelStore(applicationContext) }
                    val mtModelStore = remember { MtModelStore(applicationContext) }
                    val liveSession = remember { LiveSessionState(capture, modelStore, mtModelStore) }
                    // Держим uiStrings сессии в синхроне с активным языком, чтобы строки причин
                    // перевода из translateFinal() были локализованы.
                    liveSession.uiStrings = strings

                    // Реальный менеджер загрузки моделей внутри приложения. Кладёт файлы в тот же
                    // корень filesDir/models/<id>/, который читают сторы, — так докачанная модель
                    // сразу видна рантайму.
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
     * Бенчмарк только для debug. Запускается так:
     *   adb shell am start -n dev.flextranslate/.foundation.MainActivity -e BENCH 1
     * Гоняет [BenchmarkRunner] в фоновой корутине; результаты — в logcat под тегом FlexBench.
     * При обычном (не-BENCH) запуске не делает ничего.
     *
     * Тест Gemini BYOK:
     *   adb shell am start -n dev.flextranslate/.foundation.MainActivity -e GEMINI_TEST 1
     * Берёт ключ из [AndroidGeminiKeyStore], дёргает Gemini напрямую, логирует реальный вывод и латенси.
     * Сам ключ [BenchmarkRunner.runGeminiTest] не логирует никогда.
     */
    private fun maybeRunBenchmark() {
        val bench = intent?.getStringExtra(BenchmarkRunner.INTENT_EXTRA)
        val geminiTest = intent?.getStringExtra(BenchmarkRunner.INTENT_EXTRA_GEMINI)

        if (bench == "1") {
            Log.i(BenchmarkRunner.TAG, "BENCH intent detected — starting benchmark run")
            lifecycleScope.launch {
                BenchmarkRunner.run(applicationContext)
            }
        }

        if (geminiTest == "1") {
            Log.i(BenchmarkRunner.TAG, "GEMINI_TEST intent detected — starting Gemini direct test")
            lifecycleScope.launch {
                BenchmarkRunner.runGeminiTest(applicationContext)
            }
        }

        val keyToSet = intent?.getStringExtra(BenchmarkRunner.INTENT_EXTRA_SET_GEMINI_KEY)
        if (!keyToSet.isNullOrBlank()) {
            // Ключ кладётся синхронно в главном потоке в EncryptedSharedPreferences.
            // Не логируется — provisionGeminiKey пишет в лог только длину.
            BenchmarkRunner.provisionGeminiKey(applicationContext, keyToSet)
        }
    }

    override fun onResume() {
        super.onResume()
        // Пока были в фоне, разрешение могли поменять в системных настройках.
        if (::session.isInitialized) session.refreshPermission()
    }

    override fun onStop() {
        super.onStop()
        // Уходим с переднего плана — глушим реальную запись с микрофона.
        if (::session.isInitialized) session.stopCapture()
    }
}
