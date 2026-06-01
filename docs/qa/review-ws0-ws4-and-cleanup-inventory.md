# Review WS0–WS4 + Production-Cleanup Inventory (for G008)

Source: independent code-review (2026-06-01), verdict **PASS-WITH-FIXES** (0 CRITICAL, 2 HIGH, 7 MEDIUM, 8 LOW). No-false-claims upheld throughout; zero `!!`; aquacard tokens clean. Native `cpp/` (MiLMMT/llama.cpp) was out of scope.

## Must-fix before ship (HIGH — stabilization pass on apps/android)
1. **Compose state mutated from background threads without main dispatch** — `ui/LiveSessionState.kt` (`translateFinal` ~343-351, `runWavDemo` ~168-191, capture-thread `onStats`/`onUpdate` ~273-278). `_translation`/`_finalTranscript`/`_partialTranscript`/`_stats`/`_vadState`/`_translating`/`_demoRunning` are `mutableStateOf` written from `flex-mt`/`flex-wav-demo`/`flex-mic-capture` threads. Fix: dispatch all state writes to main (`Handler(Looper.getMainLooper()).post {}` or convert to ViewModel + `viewModelScope` + Dispatchers.Main; worker bodies on Dispatchers.Default). The `_finalTranscript == finalText` stale-guard must read+write on one thread.
2. **`runWavDemo` re-entrancy/race** — same root cause; demo worker spawns a competing `flex-mt` thread mutating shared transcript/translation. Fix with the same dispatch + serialize demo→MT.

## Next-priority (MEDIUM)
- Picker install-state mismatch: `LanguagesScreen` shows only the *selected* MT candidate as installable (`LiveSessionState.mtModelInstalled` resolves only `selectedMtCandidate`); `ModelsScreen` is per-model correct → add `isMtModelInstalled(candidate)` resolving per `candidate.modelId`.
- `selectMtCandidate`: clear `_translation`/`_translationReason` unconditionally before conditional re-translate (stale cloud/on-device translation).
- `CaptureStats.levelPercent = rms/Short.MAX_VALUE` → meter looks near-empty for normal speech; base on `peak` or dBFS log mapping.
- `AudioPipeline.accept` calls `asrProvider.accept` outside the lock while doc claims full lock coverage → tighten doc to single-writer invariant or move inside lock.
- `MilmmtMtProvider` loads ~3.74GB GGUF synchronously on first translate with only "перевожу…" feedback → add distinct "загрузка модели…" state + pre-warm on selection.
- No test asserting every non-null `MtCandidate.modelId`/`AsrCandidate.id` resolves to a spec → add it (caught a dead ASR pack, below).

## LOW (selected)
- `AsrCandidate` `en-zipformer-20m-low-tier-2023-02-17` has NO `AsrModelSpec` → dead, un-installable pack row. Add spec or drop until WS6.
- `M2m100OnnxEngine.runWithPast` deep-copies static encoder cross-attn KV every decode step (12 layers × 2) → copy once, reuse; perf only.
- `M2m100OnnxEngine.argmaxLastStep` assumes buffer position 0 → compute `(T-1)*vocab` after `rewind()`.
- `MtDirection.parse` splits on `"-"` too → restrict to `"->"`.
- `CloudScreen` `nowEpochMs = remember{...}` never refreshes → tick via `produceState`.
- `MtModelSpec.kt:15` stale comment references merged decoder (impl uses split pair).
- `AudioCaptureController.stop()` join(750) best-effort — document.

---

## PRODUCTION-CLEANUP INVENTORY (drives G008; remove/replace once benchmarks back the claims)

> Rule (from `ws6-telemetry-benchmark.md`): a `(model_id, language[/pair], tier)` label is dropped ONLY after that row PASSES the §7 gates on SM-S937B. Global `DemoBanner` goes LAST, only at full launch scope + final QA pass. Failing rows keep a product-worded limitation (not dev jargon).

**Global banner**
- `ui/AppScaffold.kt:110-120` `DemoBanner()` ("Demo · launch-support не заявлен") + call site `:96`.

**LiveScreen** (`ui/screens/LiveScreen.kt`)
- `:69-83` whole self-test `OutlinedButton` "Demo: распознать тестовое … аудио"; `:78` "Распознаю тестовое аудио…".
- `:107` "offline-перевод не заявлен"; `:180-181` "ASR support пока не заявлен…"; `:187` "…(demo, качество не проверено)."; `:261` "…(модель …, demo, качество не проверено)."

**LanguagesScreen** (`ui/screens/LanguagesScreen.kt`)
- `:84` "offline-ASR: адаптер готов (demo)"; `:88-90` "offline-перевод: не заявлен (нужны benchmark + модель)"; `:158` "реальный вызов в WS5…"; `:162/:164` install copy.

**ModelsScreen** (`ui/screens/ModelsScreen.kt`)
- `:116` **"Скачать" button is a NO-OP** (`onClick={}`) → wire to ModelDownloadManager (task #37) before ship.
- `:119` "Готов к локальному распознаванию (demo, качество не проверено)."

**DiagnosticsScreen** (`ui/screens/DiagnosticsScreen.kt`)
- `:20` `PENDING="pending"` + uses (`:73,74,85,93`); `:72` "asrSupport: не заявлен"; `:92` "…(WS6)."; `:97-98` hardcoded `deviceTier()` on `"S937"`; `:81` `appBuild="0.1.0"` → use `BuildConfig.VERSION_NAME`.

**LiveSessionState** (`ui/LiveSessionState.kt`)
- `:155-202` entire `runWavDemo()` self-test + `demoClipFile()` + demo state/constants (`:420-424`) — test-only, remove.
- `:323` "облачный перевод … появится в WS5…"; `:354` `tierLabel()` hardcoded `"mid"` → real tier.

**Registries / specs**
- `AsrCandidate.kt`/`MtCandidate.kt`/`TranslationCandidate.kt` `support="not_claimed"` defaults → promote ONLY with WS6 evidence (intended gate).
- `TranslationCandidate.kt:26` placeholder "NLLB/M2M/Marian… TBD"; `MtCandidate.kt:83-93` `opus-mt-fast` modelId=null; `:94-104` gemini notes "real call in WS5".
- `AsrCandidate.kt:21-27` dead 20M pack (no spec).
- `MtModelSpec.kt:15` stale merged-decoder comment.

**Theme / build**
- `ui/theme/ThemePreview.kt:9` stale "WS1" comment; `Color.kt` SemanticPurple/Pink unused.
- `build.gradle.kts:14,25-39` NDK pin + arm64-only + S937 comments — revisit ABI coverage before general release.
