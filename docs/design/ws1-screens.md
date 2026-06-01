# WS1 — Screen & Navigation Spec (flex-translate, aquacard dark style)

> Implementation-ready spec for G002/WS1. Style = aquacard dark system (see `aquacard-design-tokens.md`). Content = flex-translate live offline-first transcription + tier-gated translation. Both platforms implement the SAME 5 screens.
> **Discipline (A1, no-false-claims):** adapters are built but real ASR/MT output is gated on models (A2). The UI demonstrates the end-to-end flow and NEVER fakes a transcript/translation as if proven, and NEVER claims launch support. A persistent honest "demo / launch-support не заявлен" affordance is shown.

## 0. Design invariants (apply everywhere)
- Dark only. Background `#0E0F11`, Surface `#16181C`, Elevated `#1E2126`. Text primary `#E6E8EB`, secondary `#9BA1A8`. Single accent `#7C9CFF`. Error `#FF6B6B`.
- Depth via surface layers only — **no shadows, no gradients, no elevation**.
- Cards/panels radius 12–20dp (iOS cardRadius 18). Badges = pills radius 4–6dp, tinted `accent.copy(alpha=0.16)` bg + accent text (or semantic color for status).
- **Monospace** (`FontFamily.Monospace` / `.monospacedDigit()`) for all numeric/data: levels, latency ms, sizes (MB), timestamps, percentages.
- System font; type scale from `Type.kt` (titleLarge 22 SemiBold … labelSmall 11 Medium).
- Use existing `FlexTheme` (Android) / `FlexTheme` tokens (iOS). Do not invent colors.
- Semantic status palette (reuse aquacard difficulty palette): supported/ok = green `#22C55E`; warn/degraded = amber `#F59E0B`; unsupported/error = red `#EF4444`; cloud/info = accent `#7C9CFF`.

## 1. Navigation shell
**5 destinations**, identical on both platforms (icon = Material/SF symbol):
| # | Title (RU) | Android icon | iOS SF symbol |
|---|---|---|---|
| 0 | Эфир (Live) | `Icons.Default.GraphicEq` | `waveform` |
| 1 | Языки | `Icons.Default.Translate` | `globe` |
| 2 | Модели | `Icons.Default.Download` | `square.and.arrow.down` |
| 3 | Облако | `Icons.Default.Cloud` | `cloud` |
| 4 | Диагностика | `Icons.Default.Speed` | `gauge` |

- **Android:** `Scaffold` with `TopAppBar` (title "Flex Translate", accent, bold) + bottom `NavigationBar` (5 `NavigationBarItem`); body = `when(currentTab)`. No nav graph (mirror aquacard `MainAppScreen.kt`). Window/system bars `#0E0F11`.
- **iOS:** `TabView` (tint = FlexTheme.primary, `.preferredColorScheme(.dark)`) of 5 tabs, each a `NavigationStack` (mirror aquacard `ContentView.swift`).
- **Global demo banner:** a thin pill row pinned under the top bar on every tab: text "Demo · launch-support не заявлен", amber-tinted, dismissible-no. This is the structural no-false-claims affordance.

## 2. Screen — Эфир / Live (tab 0, primary)
Purpose: the live interpreter surface. Transcript-dominant, but mode/pair/readiness always legible (synthesis of both design lenses).

Layout (top → bottom):
1. **Status strip** (Row of pills): mode badge (`offline` accent / `cloud` — only if cloud active), language-pair badge (`RU → EN` monospace), readiness badge derived from `OfflineFirstState`:
   - `ReadyOfflineAsr` → "микрофон готов" green
   - `CaptureBlocked(reason)` → reason, red
   - `MissingOfflinePack(packId)` → "нет пакета: {packId}" amber
   - `CloudDisabled` → not shown as error (it's the default) 
2. **Mic level meter** (`Surface` radius 12, elevated): horizontal bar driven by `CaptureStats.levelPercent` (0–100), accent fill; monospace `peak`/`rms` small labels; when not capturing show idle state.
3. **Transcript panel** (the dominant area, `Surface` radius 16, fills remaining height, scrollable): list of `TranscriptEvent` — `isFinal=false` rendered muted/italic (`#9BA1A8`), `isFinal=true` primary text. **Empty/A1 state:** since `AsrProvider` returns `[]` until models load, show centered honest placeholder: "ASR support пока не заявлен — транскрипт появится после загрузки локальной модели (см. Модели)." NEVER fabricate text.
4. **Translation field** (`Surface` radius 12, below transcript): shows latest `TranslationResult` — if `unsupportedReason != null` show it in amber ("перевод недоступен offline для {pair}/{tier}"); else translated text; A1 placeholder: "перевод появится после загрузки MT-модели (demo, качество не проверено)".
5. **Capture control** (bottom): primary start/stop button (accent filled when idle → "Слушать"; surface outline when active → "Стоп"). In debug builds, a secondary "push-to-talk" press-and-hold control. Disabled with helper text when `CaptureBlocked` (route to permission request) or `MissingOfflinePack`.

Interactions: Start → `AudioCaptureController.start{onStats}` updates level meter + (when WS2/WS3 land) feeds frames to `AsrProvider`; transcript list appends events. Stop → `stop()`. Permission denied → reuse existing MainActivity RU copy, request `RECORD_AUDIO`.

## 3. Screen — Языки / Languages (tab 1)
Purpose: pick source/target; show honest per-pair support.
Layout:
1. **Source / Target selectors** (two `Surface` radius 10 dropdown rows): RU, EN (Phase-0 scope). Swap button (accent icon) between them.
2. **Pair support row** (`Surface` radius 12): for the active pair {RU→EN | EN→RU}, show support state from support-matrix lookup. Until benchmarks exist → "offline-перевод: не заявлен (нужны benchmark + модель)" amber; ASR for RU/EN → "offline-ASR: адаптер готов (demo)". Map to `UnsupportedOfflineTranslation` when applicable.
3. **Note:** "Поддержка генерируется из benchmark-доказательств, а не из намерений" (mirror plan §1.7) — small secondary text.

## 4. Screen — Модели / Models & offline packs (tab 2)
Purpose: manage offline ASR/MT packs honestly.
Layout: `LazyColumn`/`List` of pack rows (`Surface` radius 12), one per candidate from `configs/asr-candidates.json` + `configs/mt-candidates.json` (RU/EN ASR, RU↔EN MT):
- Row: pack name (mono id), tier chip, **size MB** (monospace), state chip:
  - installed → green "установлен"
  - missing → secondary "не установлен" + "Скачать" button (enabled only when online; disabled offline with helper "доступно только онлайн")
  - downloading → progress + amber
  - corrupt/rollback → red "повреждён — откат к последней валидной"
- State maps to `OfflineFirstState.MissingOfflinePack(packId)`. Checksum/rollback status line per row.
- Header note: model weights are NOT bundled (license/size); download shown with size before fetch (plan §1.9 / phase-0 §7).

## 5. Screen — Облако / Cloud (tab 3, Settings)
Purpose: opt-in cloud, default OFF, honest disclosure.
Layout: 3 provider cards (`Surface` radius 12) from `CloudProviderRegistry.providers`:
- `cloud-stt-recognition-fallback` — "Cloud STT · fallback распознавания"
- `gemini-live-assistant` — "Gemini Live · realtime ассистент"
- `gemini-batch-audio-enrichment` — "Gemini batch · async обогащение"
Each card: title, role description, **toggle (default OFF)**, provider disclosure + data-retention consent copy (collapsible), state line bound to `CloudOptInState` (userConsented / disclosureAccepted / networkState / credential). Toggle can enable only when `canStart(now)` preconditions are explainable; show what's missing (consent / disclosure / online / ephemeral token). Hard copy: "Облако выключено по умолчанию · нет silent fallback · нет встроенных API-ключей (backend ephemeral tokens)" (plan §1.9).

## 6. Screen — Диагностика / Diagnostics (tab 4, debug-oriented)
Purpose: operator trust + debugging.
Layout: `Surface` panels with monospace key/value `StatCell`-style grid:
- Capture: `isCapturing`, `sampleRateHz`, `framesRead`, `chunksRead`, `elapsedMs`, `peak`, `rms`, `lastError`.
- Pipeline: VAD state (WS2), ASR provider id + "support: не заявлен", buffer depth, latency p95 placeholders (WS6 telemetry).
- Build/device: app build, device tier (high on SM-S937B), model/runtime versions.
- Telemetry: last N events (when WS6 emits) with session_id, monotonic_ts, event_type.
- All values real where available; clearly "—" / "pending" where not. No fabricated metrics.

## 7. No-false-claims checklist (must hold in implementation)
- [ ] Global "Demo · launch-support не заявлен" banner present on all tabs.
- [ ] Transcript never shows fabricated text; A1 placeholder explains gating.
- [ ] Translation never shows fabricated output; unsupported/gated states explicit.
- [ ] Models screen never marks a pack "supported"; only installed/missing/size/checksum.
- [ ] Languages screen states offline-translation support is benchmark-gated, not claimed.
- [ ] Cloud OFF by default; UI explains preconditions; no silent fallback wording.
- [ ] Diagnostics shows real or "pending" values only.

## 8. Scope boundary for WS1
WS1 delivers the SHELL + screens + wiring to existing domain types and `CaptureStats` (real mic level works since Android capture is real). Real ASR transcript (WS2/WS3), real VAD (WS2), real MT (WS4), cloud calls (WS5), telemetry emission (WS6) are later stories — WS1 renders their states/placeholders honestly. Rewire Android `MainActivity` from raw Views to a Compose `setContent { FlexTheme { AppScaffold() } }`; rewire iOS `FlexTranslateApp` to the `TabView` root. Preserve the existing permission-request flow (now inside the Live screen).
