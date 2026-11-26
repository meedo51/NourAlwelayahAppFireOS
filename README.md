# Nour Alwelayah Appliance (`com.nouralwelayah.clock`)

[![Version](https://img.shields.io/badge/version-2.0--appliance%20(code%203)-cc9c56)](app/build.gradle.kts)
[![Min SDK](https://img.shields.io/badge/minSdk-22%20(Android%205.1%20%2F%20Fire%20OS%205)-0c1823)](app/build.gradle.kts)
[![Target SDK](https://img.shields.io/badge/targetSdk-35%20(Android%2015)-0c1823)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7f52ff)](gradle/libs.versions.toml)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.12.01-4285f4)](gradle/libs.versions.toml)
[![Voice Engine](https://img.shields.io/badge/sherpa--onnx-2.0.2%20(Offline)-22c55e)](app/build.gradle.kts)

**Nour Alwelayah** is a dedicated, landscape-locked (`1280 × 800`) Android smart Islamic prayer clock, Azan & alarm appliance, Media3 audio/video/YouTube player, and **100% offline bilingual (Arabic & English) voice assistant**. Engineered for white-label Android tablets and Fire HD 10 devices running in Kiosk / Device Owner mode, it operates with zero cloud LLM dependency, deterministic SQLite FTS5 command retrieval, strict Azan/alarm audio preemption, and local Wi-Fi / BLE companion remote control.

---

## Table of Contents

1. [Architecture & Core Highlights](#architecture--core-highlights)
2. [Tech Stack & Project Structure](#tech-stack--project-structure)
3. [App Contents, Tabs & Functionalities](#app-contents-tabs--functionalities)
   - [Main Kiosk / Clock Home Screen](#main-kiosk--clock-home-screen)
   - [Tab 1: Clock (`Clock` / `الساعة`)](#tab-1-clock-clock--الساعة)
   - [Tab 2: Prayer (`Prayer` / `الصلاة`)](#tab-2-prayer-prayer--الصلاة)
   - [Tab 3: Alarms (`Alarms` / `المنبهات`)](#tab-3-alarms-alarms--المنبهات)
   - [Tab 4: Player (`Player` / `القارئ`) & Full-Screen Media Player](#tab-4-player-player--القارئ--full-screen-media-player)
   - [Tab 5: Wi-Fi (`WiFi` / `الشبكة`)](#tab-5-wi-fi-wifi--الشبكة)
   - [Tab 6: Settings (`Settings` / `الإعدادات`)](#tab-6-settings-settings--الإعدادات)
   - [Hidden Technician Admin Dashboard (`AdminDashboard`)](#hidden-technician-admin-dashboard-admindashboard)
   - [Shared Voice Assistant & Production Diagnostics Panel](#shared-voice-assistant--production-diagnostics-panel)
   - [Companion & Remote Integration (HTTP, BLE & Hotspot)](#companion--remote-integration-http-ble--hotspot)
   - [Design System, Bilingual RTL/LTR & Kiosk Behavior](#design-system-bilingual-rtlltr--kiosk-behavior)
4. [Offline Voice Assistant Architecture (`sherpa-onnx` + FTS5)](#offline-voice-assistant-architecture-sherpa-onnx--fts5)
   - [Official `sherpa-onnx-2.0.2.aar` Integration](#official-sherpa-onnx-202aar-integration)
   - [Verified `voice_models/` Directory Structure](#verified-voice_models-directory-structure)
   - [Whisper INT8 Multi-Threading, Silero VAD & Recognition Modes](#whisper-int8-multi-threading-silero-vad--recognition-modes)
   - [Offline Knowledge Base (`nour_kb.db`) & Retrieval Pipeline](#offline-knowledge-base-nour_kbdb--retrieval-pipeline)
   - [Privacy-First Local Phrase Memory (`VoiceLearningStore`)](#privacy-first-local-phrase-memory-voicelearningstore)
   - [One-Tap Correction Capture UX & Strict Azan Preemption](#one-tap-correction-capture-ux--strict-azan-preemption)
5. [Storage Layout & Configuration Reference](#storage-layout--configuration-reference)
6. [Testing & Evaluation Suite](#testing--evaluation-suite)
7. [Building & Tablet Deployment](#building--tablet-deployment)
8. [Kiosk / Device Owner Provisioning](#kiosk--device-owner-provisioning)
9. [Troubleshooting & Security Notes](#troubleshooting--security-notes)

---

## Architecture & Core Highlights

- **Dedicated Appliance Mode:** Unified single-variant build (`com.nouralwelayah.clock`, `minSdk 22`, `targetSdk 35`) locked to the P4 `1280 × 800` landscape tablet layout (`AppSettings.isMediaPlayer() = true`). Legacy phone-only activities and `standard`/`fire` product flavors have been removed.
- **Deterministic Offline Voice Assistant (Zero LLM):** Runs Silero VAD, multi-threaded Whisper INT8 STT, and Arabic/English Piper VITS TTS via the official `sherpa-onnx-2.0.2.aar`, backed by a pre-compiled read-only SQLite FTS5 knowledge base (`nour_kb.db`, 122 intents across 10 subcategories) and a privacy-first device-local phrase memory (`voice_learning.db`).
- **Astronomical Prayer Times & Umm al-Qura Hijri Calendar:** Offline solar-angle prayer calculation supporting Jafari (default) and standard global methods, custom Fajr/Maghrib angles, per-prayer minute offsets, local moon-sighting Hijri adjustment (`-2` to `+2` days), and exact AlarmManager scheduling with Direct Boot recovery.
- **40 Programmable Alarms:** Supports fixed times, prayer-relative offsets (before/after Fajr, Dhuhr, Maghrib), weekly day masks, Ramadan-only schedules, specific calendar dates, custom audio/video alert files, and per-alarm volumes (`0–21`).
- **Local Wi-Fi Web Remote, BLE GATT & No-Router Hotspot:** Embedded HTTP server on port `8080` with DNS-SD (`_prayerclock._tcp`) discovery, high-contrast ZXing QR codes, optional PIN protection, bulk Wi-Fi media uploads, live BLE GATT companion client tracking (`NourAlwelayah`), and Android `LocalOnlyHotspot` mode.
- **Hardened Kiosk Lockdown & Unattended OTA:** Full `DevicePolicyManager` Lock Task enforcement (`KioskPolicy`), 3-second foreground `WatchdogService`, PIN-gated hidden Technician Portal (`AdminDashboard`), and 24-hour unattended OTA firmware/media/KB sync (`OtaManager`, `SilentInstaller`, `KbOtaSyncManager`).

---

## Tech Stack & Project Structure

### Key Dependencies (`gradle/libs.versions.toml` & `app/build.gradle.kts`)

| Component | Library / Version | Purpose |
| :--- | :--- | :--- |
| Build Toolchain | AGP `8.7.3`, Kotlin `2.0.21`, JDK `17` | Unified Android build with `desugar_jdk_libs` `2.1.4` (`java.time` on API 22+) |
| UI Framework | Jetpack Compose BOM `2024.12.01`, Material 3 | 1280×800 P4-scaled bilingual LTR/RTL UI |
| Splash Screen | `androidx.core:core-splashscreen:1.0.1` | Holds splash until `Store.isHydrated` completes |
| Media Playback | AndroidX Media3 `1.5.1` (`exoplayer`, `ui`, `session`) | Background audio service, full-screen video player, external MediaController sync |
| Offline Voice I/O | `app/libs/sherpa-onnx-2.0.2.aar` | Official native Android AAR for Silero VAD, Whisper INT8 STT, and Piper VITS TTS |
| SQLite FTS5 | `com.github.requery:sqlite-android:3.45.0` | Bundled SQLite with FTS5 guaranteed across all Android/Fire OS ROMs (API 22–35) |
| QR Generation | `com.google.zxing:core:3.5.3` | Async high-contrast QR codes for Web Remote URL and LocalOnlyHotspot Wi-Fi |
| Testing | JUnit `4.13.2` | Unit & 36-utterance bilingual retrieval evaluation suite |

### Repository Layout

```text
.
├── app/
│   ├── build.gradle.kts                  # Application config (com.nouralwelayah.clock, noCompress onnx/db/txt)
│   ├── libs/
│   │   └── sherpa-onnx-2.0.2.aar         # Official native sherpa-onnx Android AAR (no local com.k2fsa stubs)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml       # MainActivity (HOME/LAUNCHER), MediaActivity, AlertActivity, Services, Receivers
│       │   ├── assets/
│       │   │   ├── ble-shim.js           # Web Remote BLE/transport helper
│       │   │   ├── kb/nour_kb.db         # Pre-compiled read-only SQLite FTS5 Knowledge Base (122 intents)
│       │   │   └── voice_models/         # Optional bundled fallback models (or pushed to external storage)
│       │   ├── res/
│       │   │   ├── values/strings.xml    # English UI strings
│       │   │   └── values-ar/strings.xml # Arabic UI strings
│       │   └── java/com/nouralwelayah/clock/
│       │       ├── MainActivity.kt       # Entry point, Splash hold, immersive bars, Kiosk boot, P4Tablet host
│       │       ├── PrayerClockApp.kt     # Application init, OTA polling startup
│       │       ├── AdminReceiver.kt      # DeviceAdminReceiver (directBootAware)
│       │       ├── Kiosk.kt              # Lock Task enter/exit/clear helpers
│       │       ├── TabletLandscape.kt    # Landscape orientation lock helper
│       │       ├── alert/                # AlertService, AlertActivity, AlertScheduler, Boot/Alert Receivers
│       │       ├── core/                 # PrayerCalc, Planner, Hijri, UmmAlQuraData
│       │       ├── data/                 # AppSettings/Store, Alarm, AppFolders, AzanLibrary, CityFile, MediaLibrary, PictureLibrary, OnlineGeo, YouTubeSearch
│       │       ├── kiosk/                # KioskPolicy (DPM), WatchdogService (3s foreground guard)
│       │       ├── media/                # MediaService, MediaActivity, MediaHub, MediaRemoteServer (BLE GATT), MediaProtocol, BleUploads
│       │       ├── ota/                  # OtaManager (24h polling), SilentInstaller (Device Owner PackageInstaller)
│       │       ├── system/               # FileSystemManager (.nomedia & traversal guard), AssetExtractor, WifiController, WifiConfigurator
│       │       ├── ui/
│       │       │   ├── ClockScreen.kt            # Digital clock, Hijri/Gregorian dates, next prayer, 5 prayer cards, hidden 5-tap admin trigger
│       │       │   ├── VoiceOverlay.kt           # Translucent Navy modal (Listening/Processing/Speaking/Error + one-tap correction chips)
│       │       │   ├── VoiceAssistantSection.kt  # Shared Voice controls, Recognition Mode, Local Phrase Memory, 4 Diagnostics, Test Console
│       │       │   ├── AdminDashboard.kt         # Hidden PIN-gated Technician Portal (AdminPinOverlay + AdminDashboard)
│       │       │   ├── WebRemoteSection.kt       # Two-column Wi-Fi Web Remote card with async ZXing QR code & PIN editor
│       │       │   ├── ClockPictures.kt          # ClockPicture background layer, Azan picture list, 16:10 Wallpaper Gallery Dialog
│       │       │   ├── LocaleProvider.kt         # Instant zero-restart English (LTR) <-> Arabic (RTL) provider & tr() helper
│       │       │   ├── Theme.kt                  # BrandNavy / BrandCard / BrandGold Material 3 dark color scheme
│       │       │   ├── SoundBrowser.kt           # Multi-folder alarm sound picker
│       │       │   ├── OnlineCityDialog.kt       # Online geocoding & timezone lookup dialog
│       │       │   └── p4/
│       │       │       ├── P4Tablet.kt           # 1280x800 P4 shell, 6-tab host, AlertMini, MiniPlayer, VoiceOverlay & Azan preemption
│       │       │       ├── P4Ui.kt               # P4Scale, P4Card, P4Button, P4TabBar, P4BatteryMonitor, rememberPlayer
│       │       │       ├── PrayerTab.kt          # Daily times, Azan toggles/tests, sound selectors, custom angles
│       │       │       ├── AlarmsTab.kt          # 40-slot paginated alarms list + 1180x740 AlarmEditorP4 modal
│       │       │       ├── PlayerTab.kt          # /audios, /videos, YouTube browser + Now Playing transport card
│       │       │       ├── WifiTab.kt            # Artistic Wi-Fi Status, WifiManagerDialog, Live BLE Clients, Web Remote, No-Router Hotspot
│       │       │       ├── SettingsTab.kt        # Location, TZ, Method, Hijri, Language, Brightness, Voice, Pictures, Kiosk
│       │       │       └── QrCode.kt             # Async ZXing QR Code Composable
│       │       ├── voice/
│       │       │   ├── VoiceIoEngine.kt          # Single-thread sherpa-onnx VAD/Whisper/Piper pipeline + Azan preemption
│       │       │   ├── VoiceModelManager.kt      # Dual-source external/asset model verifier & extractor
│       │       │   ├── KbDatabaseManager.kt      # Thread-safe Requery SQLite FTS5 manager & atomic hot-swap
│       │       │   ├── KbRetrievalEngine.kt      # FTS5 BM25 + Arabic prefix/fuzzy scorer + runner-up suggestions
│       │       │   ├── KbOtaSyncManager.kt       # SHA-256 verified OTA/local nour_kb.db hot-swap manager
│       │       │   ├── TextNormalizer.kt         # Deterministic Arabic/English text normalizer & language detector
│       │       │   ├── VoiceCommandDispatcher.kt # Executes app_command, play_audio, and tts_only KB intents
│       │       │   ├── VoiceLearningStore.kt     # Device-private SQLite local phrase memory & privacy-first text log export
│       │       │   ├── VoiceAssistantService.kt  # Singleton lifecycle holder for voice components
│       │       │   └── KbModels.kt               # KbMatch, KbMeta, KbRetrievalOutcome, VoiceCorrectionSuggestion
│       │       └── web/
│       │           ├── WebRemote.kt              # Embedded HTTP server (:8080), DNS-SD, Web UI & /api/* endpoints
│       │           └── Hotspot.kt                # Android LocalOnlyHotspot manager (API 26+)
│       └── test/java/com/nouralwelayah/clock/
│           ├── PrayerCalcTest.kt
│           ├── CityFileTest.kt
│           └── voice/
│               ├── TextNormalizerTest.kt         # Arabic/English normalization unit tests
│               └── VoiceRetrievalEvalTest.kt     # 36 held-out bilingual utterance eval + VoiceLearningPolicy tests
└── scripts/
    ├── nour_alwelayah_appliance_commands_kb.csv # Canonical 122-intent bilingual appliance commands CSV
    ├── download_voice_models.py          # Downloads & stages Silero VAD, Whisper INT8, Arabic & English Piper TTS
    └── deploy_tablet.ps1                 # End-to-end Windows PowerShell build, ADB install & voice_models push script
```

---

## App Contents, Tabs & Functionalities

Every screen, tab, card, toggle, and dialog documented below corresponds directly to live Jetpack Compose code under `app/src/main/java/com/nouralwelayah/clock/ui/`.

### Main Kiosk / Clock Home Screen

**Composables:** `MainActivity.kt`, `P4Tablet.kt`, `ClockScreen.kt`, `ClockPictures.kt`, `VoiceOverlay.kt`, `P4Ui.kt`

- **Startup & Splash Flow:**
  - Holds Android's Splash Screen (`installSplashScreen`) until `Store.isHydrated` is true so settings load before the first frame.
  - On first boot, displays `HardwareEngineInitSplash` (`"Initializing Hardware Engine..."` / `"جاري تهيئة محرك النظام..."`) while `AssetExtractor` extracts bundled assets and `MediaLibrary.rescan()` indexes local media.
  - Suppresses status and navigation bars immersively; if swiped into view, `P4Tablet` automatically re-hides the navigation bar after 4 seconds.
  - Configures `FLAG_KEEP_SCREEN_ON` (when `keepScreenOn` is active) and applies window-level brightness from `AppSettings.brightness` (`5–100%`, or `-1` for system auto).
  - Auto-returns to the Clock tab after `backToClockMin` minutes (default `5`) of touch inactivity on any other tab whenever no audio or YouTube playback is active.
- **Top Chrome Row:**
  - **City Name:** Active location label in BrandGold (`C_GOLD`).
  - **Voice Mic Button (`VoiceMicButton`):** Accessible `48dp × 48dp` button (`testTag="clock_voice_mic_button"`) visible whenever `voiceAssistantEnabled == true` and no Azan/alarm is active. Tapping requests `RECORD_AUDIO` if needed and starts `VoiceIoEngine.startListening()`.
  - **Timezone Pill:** Displays active IANA/offset timezone ID (`s.zone().id`).
  - **Live Battery Monitor (`P4BatteryMonitor`):** Subscribes to `ACTION_BATTERY_CHANGED`, `ACTION_POWER_CONNECTED`, and `ACTION_POWER_DISCONNECTED`; renders a custom Canvas battery icon + percentage (locked LTR). Turns **BrandGold (`#CC9C56`)** with a lightning bolt when charging, and red when `≤ 15%`.
  - **First-Run Location Prompt:** Shown below the header when `!s.locationSet`, tapping jumps straight to the Settings tab.
- **Center Clock & Date Stack:**
  - **Digital Clock (`ClockText`):** Tabular numerals (`tnum`) strictly locked to `LayoutDirection.Ltr` so `HH:MM`, BrandGold seconds (`SS`), and localized `AM`/`PM` (`ص`/`م`) never invert in Arabic RTL mode.
  - **Gregorian Date:** Localized full weekday, day, month, and year (`DATE_FMT_EN` / `DATE_FMT_AR`).
  - **Hijri Date:** Localized Umm al-Qura Hijri date (`today.hijri.format(ctx, activeLang)`) with user moon-sighting offset (`-2..+2`).
  - **Next Prayer Countdown:** Live countdown to the next upcoming prayer (`Next: <Prayer> at <Time> (in Xh YYm)`), seamlessly rolling over to tomorrow's Fajr after Maghrib.
- **Background Layer (`ClockPicture`):**
  - Displays either the built-in **Solid BrandNavy (`#0C1823`)**, the selected 16:10 custom wallpaper from `/NourAlwelayah/wallpaper/`, or a prayer-specific Azan picture during an active Azan—all darkened by `40%` (`PICTURE_DIM = 0.4f`) with text drop-shadows for readability.
- **Contextual Mini-Bar (`aboveCards`):**
  - **During Active Azan / Alarm (`AlertMini`):** Gold-bordered `74px` card directly above the prayer strip featuring Play/Pause (`▶`/`❚❚`), Stop (`■`), Snooze (`10 min`, alarms only), Azan/alarm icon & title, `mm:ss / mm:ss` progress bar, and a live `0–21` volume slider.
  - **During Media Playback (`MiniPlayer`):** Gold-bordered `74px` card with Play/Pause, Stop/Clear, current track title, progress bar, and `0–21` Media3 volume slider.
- **Bottom Prayer Cards Strip:**
  - Five equal-width cards for **Fajr, Sunrise, Dhuhr, Sunset, and Maghrib**, highlighting the upcoming prayer card in BrandGold (`C_ACCENT`).
- **Bottom Navigation Bar (`P4TabBar`):**
  - Fixed `72px`-scaled 6-tab bar along the bottom edge with active gold underline indicator:
    `⌂ Clock` · `☰ Prayer` · `🔔 Alarms` · `♪ Player` · `📶 WiFi` · `⚙ Settings`.
- **Hidden Technician Trigger:**
  - Invisible `48dp × 48dp` touch zone at `Alignment.TopStart` (`testTag="admin_secret_corner_trigger"`). Five rapid taps (within 2 seconds between taps) open `AdminPinOverlay`.
- **Translucent Voice Overlay (`VoiceOverlay`):**
  - Renders over all tabs inside `P4Tablet` with `88%` BrandNavy scrim + radial BrandGold ambient halo, immediately dismissed whenever an Azan or alarm fires (`isAlertActive == true`).
  - **Listening State:** Concentric audio-reactive BrandGold pulse rings, animated symmetric waveform visualizer, `"Listening..."` / `"جارٍ الاستماع..."`, and Cancel button.
  - **Processing State:** BrandGold spinner medallion, `"Processing..."` / `"جارٍ المعالجة..."`, and Cancel button.
  - **Speaking State:** Animated speaking bars, detected language badge (`English` / `العربية`), optional `Learned` pill when served from promoted local phrase memory, **locally scoped RTL/LTR response container** (with recognized transcript strip, scrollable deterministic response, and optional `Source:`/`المصدر:` citation pill), optional one-tap `"Did you mean:"` / `"هل تقصد:"` correction chips, auto-dismiss countdown bar (`3s` standard, `6s` with suggestions), and Stop/Dismiss button.
  - **Error State:** Scoped RTL/LTR error message with Dismiss and Retry buttons.

---

### Tab 1: Clock (`Clock` / `الساعة`)

| Attribute | Details |
| :--- | :--- |
| **Tab Label** | `⌂  Clock` (EN) · `⌂  الساعة` (AR) |
| **Composable** | `ClockScreen.kt` + `ClockPictures.kt` hosted inside `P4Tablet.kt` |
| **Behavior** | Serves as the appliance's primary ambient home screen (detailed in [Main Kiosk / Clock Home Screen](#main-kiosk--clock-home-screen)). All non-Clock tabs automatically return here on Android Back press, on media stop, when an Azan/alarm triggers, or after `backToClockMin` minutes of idle time. |

---

### Tab 2: Prayer (`Prayer` / `الصلاة`)

| Attribute | Details |
| :--- | :--- |
| **Tab Label** | `☰  Prayer` (EN) · `☰  الصلاة` (AR) |
| **Composable** | `app/src/main/java/com/nouralwelayah/clock/ui/p4/PrayerTab.kt` |

Two-column landscape workspace:

- **Left Column (`600px` scaled) — Today's Schedule, Per-Prayer Azan Toggles & Live Tests:**
  - Five full-width prayer cards (**Fajr / الفجر**, **Sunrise / الشروق**, **Dhuhr / الظهر**, **Sunset / الغروب**, **Maghrib / المغرب**) displaying computed prayer times in BrandGold.
  - Instant-save **Azan Switches** on Fajr, Dhuhr, and Maghrib (`no azan` indicator for Sunrise and Sunset); toggling immediately updates `Store.settings.azanOn` and invokes `AlertScheduler.reschedule(ctx)`.
  - **Method Summary Footer:** Shows active city, calculation method name, effective Fajr angle, effective Maghrib angle (or `sunset`), and `(custom)` badge if custom angles are active.
  - **Live Test Controls:** `▶ Fajr`, `▶ Dhuhr`, `▶ Maghrib` buttons trigger `AlertService.azanIntent(..., test = true)` without navigating away, paired with a `■ Stop` button.
- **Right Card — Azan Sounds, Timing & Custom Solar Angles:**
  - **Sound Library Header (`/azan`):** `+ Add` button (opens Android SAF multi-audio picker via `AzanLibrary.add`) and `SdRefreshButton` to rescan storage.
  - **Per-Prayer Sound Dropdowns:** Fajr (`Same as Dhuhr` or `/azan` file), Dhuhr (`Default alarm sound` or `/azan` file), Maghrib (`Same as Dhuhr` or `/azan` file).
  - **Azan Volume (`0–21`)** and **Start Azan Early (`0–500 s`)** numeric inputs.
  - **Angle Override Controls:** `Method angles` checkbox (unchecking enables manual `Fajr angle (14.0 – 19.9)` and `Maghrib angle (0.833 = sunset – 9.9)` decimal fields).
  - **Azan Picture Toggle:** Instant switch toggling `AppSettings.azanPicture`.
  - **`💾 Save prayer settings` Button:** Validates ranges, persists to `Store`, and reschedules all upcoming Azans via `AlertScheduler.reschedule(ctx)`.

---

### Tab 3: Alarms (`Alarms` / `المنبهات`)

| Attribute | Details |
| :--- | :--- |
| **Tab Label** | `🔔  Alarms` (EN) · `🔔  المنبهات` (AR) |
| **Composable** | `app/src/main/java/com/nouralwelayah/clock/ui/p4/AlarmsTab.kt` |

- **Paginated 40-Slot Alarm List:**
  - Displays `40` slots (`SLOTS = 40`), 7 rows per page (`◀ Prev`, `Page X / 6`, `Next ▶`), with a live count header (`X of 40 alarms on`).
  - Each row shows today's resolved fire time (`HH:MM` or `--:--`), alarm title, bilingual summary line (e.g., `30 min after Maghrib | Fri | Ramadan only | Adeela.mp3 | Vol 12`), and a quick enable/disable `Switch` that immediately persists and reschedules via `AlertScheduler`.
- **Modal Alarm Editor (`AlarmEditorP4`, `1180 × 740` scaled dialog):**
  - **Title:** Custom label up to 30 characters (e.g., `Suhoor`).
  - **When (Trigger Mode):** `Fixed time` (`00–23` : `00–59`) or prayer-relative (`Before / after Fajr`, `Dhuhr`, or `Maghrib` with `Before`/`After` + `0–180 min` dropdowns) with a live `"Today it rings at HH:MM"` preview.
  - **Repeat Schedule:** 7 day-of-week toggle tiles (`Sun`–`Sat`; none selected = ring once next time), `Ramadan only` switch (combinable with weekdays), and `Specific date` switch (mutually exclusive with weekdays/Ramadan; unlocks Day/Month/Year dropdowns up to +5 years).
  - **Sound Selector & Folder Browser:** Quick dropdown populated from `/NourAlwelayah/alarms/` (plus default `alarm.mp3`), paired with a `📁` button opening `SoundBrowser.kt` to pick audio or video files from any Nour Alwelayah folder.
  - **Live Sound Test & Volume:** `▶ Test` (`AlertService.alarmTestIntent`) and `■ Stop` buttons plus a dedicated `0–21` volume slider for this alarm.
  - **Footer Actions:** `✓ Save` (validates calendar dates), `✕ Cancel`, and `🗑 Delete` (crimson button; stops any active preview before closing).

---

### Tab 4: Player (`Player` / `القارئ`) & Full-Screen Media Player

| Attribute | Details |
| :--- | :--- |
| **Tab Label** | `♪  Player` (EN) · `♪  القارئ` (AR) |
| **Composables** | `app/src/main/java/com/nouralwelayah/clock/ui/p4/PlayerTab.kt`, `app/src/main/java/com/nouralwelayah/clock/media/MediaActivity.kt` |

- **Left Card (`680px` scaled) — Local Folder & YouTube Browser:**
  - **Quick-Jump Mode Buttons:** `♪ Audios` (`/NourAlwelayah/audios`), `🎬 Videos` (`/NourAlwelayah/videos`), and `▶ YouTube`.
  - **Local File Browser:** Displays current relative path under `/NourAlwelayah`, supports recursive subfolder traversal (`📁 ..` parent row), highlights the currently playing track, and queues all media files in the active directory into `MediaService` so Next/Prev step cleanly through the folder. Selecting a video file automatically opens `MediaActivity` full-screen.
  - **YouTube Browser:**
    - `+ YouTube link` button opens `YouTubeLinkDialog` (auto-prefills from clipboard if a valid YouTube URL is detected) and saves via `MediaLibrary.addYouTube()`.
    - When `AppSettings.youTubeKey` (YouTube Data API v3 key) is set, shows an inline `Search YouTube` bar with thumbnails (`YtResultRow`) and a `Clear results` action.
    - Displays all saved YouTube entries; tapping plays full-screen in `MediaActivity`.
- **Right Card — Now Playing & Transport Controls:**
  - Shows active track title (or `Stopped`), `mm:ss / mm:ss` timestamp, interactive seek slider (`c?.seekTo(...)`), large transport buttons (`⏮`, `▶`/`❚❚`, `⏭`, `■`), `0–21` volume slider synchronized with `MediaHub.volume`, and a full-width `▶ Media player` button to launch `MediaActivity`.
- **Full-Screen Media Player (`MediaActivity.kt`):**
  - Hosts Media3 `PlayerView` for local video playback and an embedded WebView player for YouTube streams (also accepts Android `ACTION_SEND` `text/plain` intents to receive shared YouTube links).
  - Respects `AppSettings.mediaTimeoutSec` (default `30s`) inactivity return-to-clock and `AppSettings.mediaStayAwake` override.

---

### Tab 5: Wi-Fi (`WiFi` / `الشبكة`)

| Attribute | Details |
| :--- | :--- |
| **Tab Label** | `📶  WiFi` (EN) · `📶  الشبكة` (AR) |
| **Composables** | `app/src/main/java/com/nouralwelayah/clock/ui/p4/WifiTab.kt`, `app/src/main/java/com/nouralwelayah/clock/ui/WebRemoteSection.kt` |

Two-column smart-home connectivity dashboard continuously synced every 3 seconds with `WifiController` and `MediaRemoteServer.bleState`:

- **Left Column (`600px` scaled):**
  1. **Artistic Wi-Fi Status Card (`ArtisticWifiStatusCard`):**
     - Custom Canvas concentric signal-arc icon and animated glowing **Connection Status Pill** (`Connected` / `متصل` in Emerald + BrandGold border when online; `Offline` / `غير متصل` in Amber + Slate when disconnected).
     - **Active Network (SSID) Hero Banner:** Displays the connected SSID and a custom 4-bar Canvas signal-quality badge (`Excellent`, `Good`, `Fair`, `Weak`, `No Signal`). Raw IP addresses and legacy system-settings text are intentionally omitted here for clean appliance presentation.
     - **`📶 Wi-Fi Network Manager` Button:** Opens the built-in Jetpack Compose `WifiManagerDialog`.
  2. **In-App Wi-Fi Scanner & Connector Modal (`WifiManagerDialog`):**
     - Powered by `WifiController` (`BroadcastReceiver` + active scan with spinning `Scan / Refresh` indicator and runtime `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES` permission handling).
     - Lists deduplicated nearby SSIDs sorted by RSSI with 4-bar signal strength, lock icon, security label, and dBm readout.
     - Tapping any network expands an inline password entry field (with `Show`/`Hide` toggle) and `Connect` button (`Connect Now` for open networks).
     - Includes a bottom **System Quick Wi-Fi Sheet** fallback button (`WifiController.openConnectivityPanel`) for non-Device-Owner Android versions that restrict silent programmatic network switching.
  3. **Live Bluetooth Remote Card (`BluetoothRemoteStatusCard`):**
     - Directly observes `MediaRemoteServer.bleState` (`StateFlow<BleServerState>`).
     - Animated glowing advertising status pill (`Ready — Advertising as NourAlwelayah`, standby, Bluetooth off, or Nearby Devices permission prompt).
     - Functional `⟳ Refresh` button (spinning icon) that requests missing BLE permissions or restarts GATT advertising and re-queries active connections.
     - **Connected Companion Remotes List:** Live count badge and per-device cards showing device display name, masked MAC address, and Emerald `Connected` dot (or clean `"No remotes connected"` state).
- **Right Column:**
  1. **Wi-Fi Web Remote Card (`WebRemoteSection`):**
     - Two-column card with Master Enable `Switch`, instruction text, monospaced local URL box (`http://<ip>:8080`), PIN status pill (`🔒 PIN Protection Active` or `🔓 No PIN`), and an async high-contrast **ZXing QR Code** (`QrCode.kt` on `#FAF8F5` cream container with gold border) or offline/disabled placeholder.
     - Inline `PIN (4 - 8 digits, empty = none)` input with `Save PIN` validation.
  2. **No-Router Mode Card (`NoRouterCard`, API 26+):**
     - Starts an Android `LocalOnlyHotspot` (`Hotspot.kt`) when no home router is present. Displays a Wi-Fi-join QR code, generated SSID, password, local HTTP URL, and `■ Stop the tablet's Wi-Fi` button.

---

### Tab 6: Settings (`Settings` / `الإعدادات`)

| Attribute | Details |
| :--- | :--- |
| **Tab Label** | `⚙  Settings` (EN) · `⚙  الإعدادات` (AR) |
| **Composables** | `app/src/main/java/com/nouralwelayah/clock/ui/p4/SettingsTab.kt`, `app/src/main/java/com/nouralwelayah/clock/ui/ClockPictures.kt` |

Scrollable configuration workspace:

1. **Top-Left Card — Location Setup:**
   - `City name` input (max 31 chars), built-in `☰ List` city selector (`CityFile`), `⌖ GPS` runtime location lookup with reverse geocoding, `⬇ Online` search button (`OnlineCityDialog` fetches coordinates + timezone and caches into `CityFile`), and manual `Latitude` / `Longitude` decimal fields.
2. **Top-Right Card — Time Zone, Method, Hijri & Language:**
   - `Time zone` dropdown (appliance default, `WebRemote.TZ_LIST`, or `Custom UTC offset / zone` supporting `+3`, `-5:30`, or IANA IDs like `Asia/Riyadh`).
   - `Calculation method` dropdown showing each method's Fajr/Maghrib angles (default: Jafari).
   - `Hijri date adjust` dropdown (`-2 days`, `-1 day`, `0 (Umm al-Qura)`, `+1 day`, `+2 days`).
   - `Clock picture` quick switch toggling custom wallpaper vs. Default Solid Navy.
   - Instant `English` / `العربية` language toggle buttons.
   - **Hidden Maintainer Unlock Trigger:** Tapping the subtle `v2.0` version label 7 times (within 3 seconds between taps) reveals the `Maintainer` unlock button for Kiosk escape.
3. **Middle Bar — Display & Save:**
   - `24-hour clock` switch, `Brightness` slider (`5–100%`) + `Auto` reset button (`-1`), `Keep screen on` switch, and `💾 Save` button (validates coordinates/timezone, saves to `Store`, reschedules alerts).
4. **This Device Section:**
   - Duplicate quick-access `Appliance language` row and read-only **Bluetooth name** display (`NourAlwelayah`).
5. **Voice Assistant Section:**
   - Full embedded `VoiceAssistantSection` (see [Shared Voice Assistant & Production Diagnostics Panel](#shared-voice-assistant--production-diagnostics-panel)).
6. **Clock Pictures Section (`ClockPictures.kt`):**
   - **Azan Picture:** Enable switch, thumbnail list of all Azan images with per-image prayer assignment dropdown (`All azans`, `Fajr`, `Dhuhr`, `Maghrib`) and Delete confirmation, plus `+ Add azan pictures` SAF multi-image picker.
   - **Wallpaper Selector Card & 16:10 Modal Gallery (`WallpaperGalleryDialog`):** Compact preview card showing the active 16:10 thumbnail (or `Default Navy Theme`), `Default Navy` reset button, and `Change Background` button launching an adaptive 16:10 grid dialog containing the built-in `Default (Solid Navy)` card plus all images in `/NourAlwelayah/wallpaper/` with `+ Add Wallpaper` and delete controls.
7. **Kiosk Mode (Appliance Lockdown) Section:**
   - Live monospaced `KioskPolicy.describeState()` diagnostic box (Device Owner status, Lock Task packages/state, keyguard, status bar, active restrictions, `ADB_ENABLED`, `STAY_ON_WHILE_PLUGGED_IN`).
   - `Kiosk mode` switch (enabled when Device Owner), `Refresh status` button, and 7-tap-revealed `Maintainer Unlock` dialog (authenticated via `AppSettings.webPin`, or fallback PIN `999999` if `webPin` is unset).

---

### Hidden Technician Admin Dashboard (`AdminDashboard`)

**Composable:** `app/src/main/java/com/nouralwelayah/clock/ui/AdminDashboard.kt`

- **Access Flow:** Tap the invisible `48dp × 48dp` top-left corner of the Clock tab 5 times rapidly → enter Technician PIN on `AdminPinOverlay` (3×4 keypad, default PIN `9200`, configurable).
- **Watchdog Safety:** Automatically sets `MainActivity.isAdminSessionActive = true` while open so `WatchdogService` pauses foreground enforcement while technicians configure system Wi-Fi or inspect diagnostics.
- **Included Sections:**
  1. **Header:** Localized *"Nour Alwelayah Technician Portal"* with `Close / Exit` button.
  2. **Bilingual UI Language & RTL Toggle:** Instant `English` / `العربية` full-width toggle buttons.
  3. **Offline Voice Assistant & Production Diagnostics:** Full `VoiceAssistantSection` embedded directly inside the portal.
  4. **Kiosk Master Toggle:** Displays active/disabled state and Device Owner provisioning status; turning off immediately persists `kioskMode = false` and calls `Kiosk.clear(ctx)` to unlock the tablet for servicing.
  5. **Wi-Fi Network Configurator:** Launches Android's `Settings.ACTION_WIFI_SETTINGS` (explicitly whitelisted alongside the appliance package in `KioskPolicy` Lock Task packages).
  6. **OTA Provisioning Server:** Edits and saves `AppSettings.otaServerUrl` (default `https://api.nouralwelayah.com/v1`), polled every 24 hours by `OtaManager` for silent APK & media updates.
  7. **Hardware Volume Limiter:** `0%–100%` slider + numeric input + `Apply` button that persists `AppSettings.maxVolumeLimit`, caps `azanVolume`, and clamps Android `STREAM_MUSIC` and `STREAM_ALARM` volumes immediately.
  8. **Technician PIN & Live Diagnostics:** Updates the 5-tap Technician PIN (up to 8 digits, default `9200`) and displays the live `KioskPolicy.describeState()` report.

---

### Shared Voice Assistant & Production Diagnostics Panel

**Composable:** `app/src/main/java/com/nouralwelayah/clock/ui/VoiceAssistantSection.kt` (rendered in both **Settings Tab** and **Admin Dashboard**)

| Panel Block | Controls & Readouts |
| :--- | :--- |
| **Master Switch** | Enables/disables `AppSettings.voiceAssistantEnabled` (controls `VoiceMicButton` visibility on the Clock chrome and mic activation). |
| **Recognition Mode (Whisper STT)** | 3 segmented buttons: `Auto (Bilingual)` (`""`), `Arabic Priority` (`"ar"`), `English Priority` (`"en"`). Persists to `AppSettings.voiceRecognitionMode` and hot-reloads Whisper on next listen. |
| **Local Phrase Memory & Privacy Controls** | Displays live counts (`Learned: X/250 • Promoted (≥2 hits): Y • Text Logs: Z`), plus `⬇ Export Text Logs (JSON)`, `⬇ Export Text Logs (CSV)`, and `🗑 Clear Learned Phrases`. |
| **Voice Subsystem Diagnostics** | `⟳ Re-scan Diagnostics` button + 4 live color-coded status cards (Emerald / Gold / Red):<br>1. **Knowledge Base (`nour_kb.db`)**: version, intent count, pattern count.<br>2. **Voice Models (`sherpa-onnx`)**: Ready vs. exact missing filenames.<br>3. **Microphone Permission (`RECORD_AUDIO`)**: Granted vs. Missing, with Kiosk-aware `Request Permission` or Device Owner `Auto-Grant (Owner)`.<br>4. **OTA Knowledge Base Sync**: Idle/Checking/Downloading progress bar/Up to Date/Updated/Error + `⟳ Manual KB Sync` button. |
| **Independent Test Query Console** | Works completely offline even before `.onnx` models are pushed:<br>• Live `VoiceUiState` readout + `🎙️ Listen (VAD + STT)` trigger button.<br>• Text query input + `▶ Run Query` button (runs `TextNormalizer` → `KbRetrievalEngine` → `VoiceCommandDispatcher`, plus TTS if available and no Azan is active).<br>• 6 one-tap bilingual sample chips (`متى الصلاة القادمة؟`, `كم الساعة الآن؟`, `ما هو التاريخ الهجري اليوم؟`, `What is the next prayer time?`, `Raise the volume`, `ارفع السطوع`).<br>• Structured result card showing `intentId`, `score`, `lang`, `actionType`, `commandPayload`, `spokenResponse`, and `sourceRef` (or zero-hallucination `NoMatch` notice). |

---

### Companion & Remote Integration (HTTP, BLE & Hotspot)

- **Embedded Wi-Fi Web Remote (`WebRemote.kt`, port `8080`):**
  - Advertised via Android NSD / DNS-SD as `_prayerclock._tcp`.
  - Serves a responsive Navy & Gold web interface at `http://<tablet-ip>:8080/` and JSON/upload `/api/...` endpoints covering live status, prayer times, Azan toggles, alarm CRUD, media & YouTube transport, and a hardened **Wi-Fi File Manager** supporting bulk uploads to `/audios`, `/videos`, `/alarms`, `/azan`, `/images`, and `/wallpaper`.
  - System directories (`/config` and `/voice_models`) are strictly hidden and rejected by `FileSystemManager.isHiddenSystemPath()` and canonical path traversal checks (`resolveSafePath`).
  - Optional 4–8 digit PIN protection (`AppSettings.webPin`).
- **BLE GATT Server (`MediaRemoteServer.kt`, `MediaProtocol.kt`, `BleUploads.kt`):**
  - Runs as a foreground service (`connectedDevice|location|dataSync`), advertising as `NourAlwelayah` (hardware Bluetooth adapter name enforced by `KioskPolicy.enforceBluetoothName`).
  - Processes companion remote commands (`list`, `state`, `toggle`, `next`, `prev`, `stop`, `file`, `yt`, `folders`, `browse`, `pick`, `full`, `vol`, `ytsearch`, `ytplay`, `link`) and exposes live `bleState` (`StateFlow<BleServerState>`) to the Wi-Fi Tab.
- **No-Router LocalOnlyHotspot (`Hotspot.kt`):**
  - Creates a temporary local Wi-Fi AP on Android 8+ (API 26+) so phones can scan the QR code and access the Web Remote without an external router.

---

### Design System, Bilingual RTL/LTR & Kiosk Behavior

- **Material 3 Brand Palette (`Theme.kt`):**
  - The appliance uses a unified dark Material 3 palette engineered for high-contrast wall-clock legibility rather than an OS Light/Dark mode switch:
    - **BrandNavy (`C_BG`):** `#0C1823`
    - **BrandCard (`C_CARD`):** `#142433`
    - **BrandGold (`C_GOLD` / `C_ACCENT`):** `#CC9C56`
    - **BrandText (`C_TEXT`):** `#F2F5F8` (>14:1 contrast on `#0C1823`)
    - **BrandDim (`C_DIM`):** `#94A7B8` (>5.5:1 WCAG AA contrast)
    - **BrandGrey (`C_GREY`):** `#223648`
  - Visual clock background appearance is customized via the **Clock Background Wallpaper Gallery** (Solid Navy vs. custom 16:10 wallpapers & per-prayer Azan pictures, also controllable via voice commands `DISPLAY_TOGGLE_THEME` and `DISPLAY_NEXT_WALLPAPER`).
- **Instant Bilingual RTL/LTR Engine (`LocaleProvider.kt`):**
  - `NourAlwelayahLocaleProvider` wraps the entire Compose tree and switches `LocalConfiguration`, `LocalContext` (preserving `Activity` base context), and `LocalLayoutDirection` (`Ltr` for `"en"`, `Rtl` for `"ar"`) instantaneously with zero Activity restart.
  - Numeric displays (`ClockText`, `P4BatteryMonitor`) stay locked to `LayoutDirection.Ltr` so time digits and battery percentages never reverse.
  - `VoiceOverlay` scopes `LayoutDirection` strictly to the response card and recognized query box, allowing English spoken queries/responses inside an Arabic UI (or Arabic inside an English UI) without mutating `AppSettings.appLanguage`.
- **Kiosk & Watchdog Enforcement (`KioskPolicy.kt`, `WatchdogService.kt`, `BootReceiver`):**
  - When provisioned as Device Owner, `KioskPolicy.applyFullKioskPolicy()` configures Lock Task packages (`[com.nouralwelayah.clock, com.android.settings]`), sets `LOCK_TASK_FEATURE_NONE` (API 28+), registers `MainActivity` as persistent `HOME`, disables keyguard/status bar (API 23+), applies `UserManager` restrictions (intentionally omitting `DISALLOW_CONFIG_WIFI` and `DISALLOW_CONFIG_BLUETOOTH` so local HTTP, Hotspot, and BLE continue working), sets `STAY_ON_WHILE_PLUGGED_IN=1`, disables ADB on API 26+ while locked, and silently auto-grants `RECORD_AUDIO` on API 23+.
  - `BootReceiver` (`directBootAware="true"`) listens for `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, and `TIMEZONE_CHANGED` to re-apply Kiosk policy and reschedule exact alarms immediately.
  - `WatchdogService` checks every 3 seconds and brings `MainActivity` back to the front whenever Kiosk mode is active and neither `MainActivity`, `MediaActivity`, an active `Alert`, nor `AdminDashboard` is visible.

---

## Offline Voice Assistant Architecture (`sherpa-onnx` + FTS5)

The voice pipeline is 100% on-device, zero-LLM, and deterministic:

```text
16kHz Mono PCM (AudioRecord)
   │
   ▼  Silero VAD (silero_vad.onnx, 512-sample windows, 0.48s silence cutoff)
   ▼  Whisper INT8 OfflineRecognizer (multi-threaded CPU, Auto / AR / EN mode)
   ▼  TextNormalizer (Arabic diacritics/alef/taa-marbuta + English cleanup)
   ▼  KbRetrievalEngine (Canonical Exact > Promoted Local Phrase Memory > SQLite FTS5 BM25 + Fuzzy)
   │      ├── High Confidence (>= 0.80) ──► VoiceCommandDispatcher ──► Piper VITS OfflineTts (AR/EN)
   │      └── Medium/Low or NoMatch     ──► VoiceOverlay "Did you mean:" / "هل تقصد:" Correction Chips
   │                                           └── One-Tap Confirm ──► Dispatch + VoiceLearningStore
   └── Strict Azan/Alarm Preemption: AlertService / P4Tablet calls VoiceIoEngine.abortImmediately()
```

### Official `sherpa-onnx-2.0.2.aar` Integration

- The project links directly against the official native Android archive bundled at `app/libs/sherpa-onnx-2.0.2.aar` via `implementation(files("libs/sherpa-onnx-2.0.2.aar"))` in `app/build.gradle.kts`.
- All local JNI stubs (`com.k2fsa.*` / `SherpaOnnxJni.kt` / `SherpaOnnxRuntime`) have been permanently removed; `VoiceIoEngine.kt` imports directly from the official AAR classes (`Vad`, `VadModelConfig`, `SileroVadModelConfig`, `OfflineRecognizer`, `OfflineRecognizerConfig`, `OfflineModelConfig`, `OfflineWhisperModelConfig`, `OfflineTts`, `OfflineTtsConfig`, `OfflineTtsModelConfig`, `OfflineTtsVitsModelConfig`, `FeatureConfig`).
- `app/build.gradle.kts` sets `androidResources { noCompress += listOf("onnx", "db", "txt") }` so bundled model/DB files can be read without decompression overhead.

### Verified `voice_models/` Directory Structure

`VoiceModelManager.kt` uses a **dual-source resolution strategy**:
1. **Primary (External Storage):** Checks `<externalFilesDir>/NourAlwelayah/voice_models/` (e.g., `/sdcard/Android/data/com.nouralwelayah.clock/files/NourAlwelayah/voice_models/`) and `/sdcard/NourAlwelayah/voice_models/` (when All-Files-Access is granted) so large `.onnx` weights can be pushed via ADB or OTA without bloating the APK.
2. **Fallback (Bundled APK Assets):** Any missing file is checked in `app/src/main/assets/voice_models/` and atomically extracted on `Dispatchers.IO`.

Expected directory tree under `voice_models/`:

```text
voice_models/
├── silero_vad.onnx                        # Silero Voice Activity Detection model
├── whisper/
│   ├── tiny-encoder.int8.onnx             # Whisper tiny INT8 encoder (base / flat encoder.int8.onnx also supported)
│   ├── tiny-decoder.int8.onnx             # Whisper tiny INT8 decoder (base / flat decoder.int8.onnx also supported)
│   └── tiny-tokens.txt                    # Whisper vocabulary tokens (or flat tokens.txt)
├── tts_ar/
│   ├── model.onnx                         # Arabic Piper/VITS TTS model (e.g. ar_JO-kareem-medium)
│   ├── tokens.txt                         # Arabic VITS token map
│   └── espeak-ng-data/                    # eSpeak-NG phoneme data directory (optional lexicon.txt supported)
└── tts_en/
    ├── model.onnx                         # English Piper/VITS TTS model (e.g. en_US-amy-low)
    ├── tokens.txt                         # English VITS token map
    └── espeak-ng-data/                    # eSpeak-NG phoneme data directory (optional lexicon.txt supported)
```

> **Crash Prevention:** `VoiceModelManager` publishes `readinessState: StateFlow<VoiceReadinessState>` (`Ready`, `Initializing`, `MissingModels`, `MissingPermission`, `Error`) and `missingModelFiles`. `VoiceIoEngine` verifies non-empty files on disk before calling any native `sherpa-onnx` constructor, ensuring the app never crashes if models have not yet been deployed.

### Whisper INT8 Multi-Threading, Silero VAD & Recognition Modes

- **Dedicated Inference Thread + Multi-Threaded Whisper Decoding:** All JNI lifecycle orchestration runs on a dedicated single-thread executor (`NourVoiceIo-SingleThread`) to prevent concurrent native OOMs, while `OfflineModelConfig.numThreads` is set to `minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(2))` to accelerate Whisper INT8 CPU decoding on multi-core tablets.
- **Tightened Silero VAD End-of-Speech:** Configured with `threshold = 0.5f`, `minSilenceDuration = 0.48f`s, `minSpeechDuration = 0.20f`s, `windowSize = 512` (32ms @ 16kHz), and `VAD_SILENCE_TIMEOUT_MS = 520ms` so short 1–3 word commands finish capturing and begin transcribing immediately without awkward pauses. Initial no-speech timeout is `5s`; maximum utterance capture cap is `12s`.
- **Configurable Recognition Mode (`AppSettings.voiceRecognitionMode`):**
  - `auto` (**Auto (Bilingual)** — default): passes `language = ""` to Whisper for seamless Arabic/English auto-detection.
  - `ar` (**Arabic Priority**): passes `language = "ar"` to minimize short-command cross-language drift.
  - `en` (**English Priority**): passes `language = "en"` for English-first households.
  - Switching mode automatically releases and re-initializes `OfflineRecognizer` on the next session.

### Offline Knowledge Base (`nour_kb.db`) & Retrieval Pipeline

- **Bundled SQLite FTS5 Database (`v1.1.0`):** Compiled from `scripts/nour_alwelayah_appliance_commands_kb.csv` via `compile_kb.py` and packaged at `app/src/main/assets/kb/nour_kb.db` (`122` published intents across `10` subcategories: `navigation` (12), `clock_queries` (14), `prayer_times` (12), `azan_control` (15), `alarms` (10), `media_player` (14), `volume_audio` (9), `display` (12), `connectivity` (11), and `system_settings` (13)).
- **Tables:** `kb_meta`, `intents`, `intent_patterns`, and `intent_patterns_fts` (FTS5 virtual table).
- **Retrieval Scoring (`KbRetrievalEngine.kt`):**
  1. `TextNormalizer` detects script language (`ar`/`en`) and strips Arabic tashkeel/tatweel, unifies alef forms (`أ إ آ ٱ → ا`), taa marbuta (`ة → ه`), alef maqsura (`ى → ي`), normalizes Arabic-Indic digits (`٠–٩ → 0–9`), and lowercases/strips punctuation.
  2. Queries exact canonical patterns first, then promoted local phrase memory, then FTS5 `bm25()` + token-overlap + Arabic light-prefix stripping + 1-edit Levenshtein fuzzy tolerance for minor Whisper transcription drift.
  3. Zero-hallucination guard rejects candidates below `DEFAULT_MIN_SCORE_THRESHOLD = 0.35f`.
- **OTA Hot-Swap (`KbOtaSyncManager.kt`):** Checks `/NourAlwelayah/config/nour_kb.db` or the configured OTA manifest, streams updates with progress reporting, verifies SHA-256 digests, and atomically swaps the active database inside `KbDatabaseManager`'s mutex lock without restarting the app.

### Privacy-First Local Phrase Memory (`VoiceLearningStore`)

- **Storage:** App-private internal SQLite database at `<filesDir>/voice_learning/voice_learning.db` — strictly inaccessible to external apps or the Wi-Fi File Manager, never auto-synced to any cloud server.
- **Zero Raw Audio:** Stores **text transcripts and intent IDs only** (`learned_phrases` and `interaction_logs` tables). Raw PCM audio buffers are discarded immediately after transcription.
- **Deterministic Safety Guardrails (`VoiceLearningPolicy`):**
  - **Canonical Priority Rule:** An exact canonical pattern match in `nour_kb.db` **always wins**—a learned phrase can never hijack a core appliance command.
  - **Promotion Threshold:** Requires `hit_count >= 2` (`MIN_HITS_FOR_PROMOTION = 2`) confirmed user selections before a learned phrase auto-executes on future utterances.
  - **Bounded Retention:** Enforces a `250`-entry LRU cap (`MAX_LEARNED_PHRASES = 250`), automatic `90`-day inactivity expiration (`EXPIRY_DAYS = 90`), and a `1000`-row cap on text interaction logs.
- **User/Technician Controls:** Opt-in manual text-log export to JSON or CSV (`Export Text Logs (JSON)` / `Export Text Logs (CSV)`) and one-tap `Clear Learned Phrases` directly inside `VoiceAssistantSection`.

### One-Tap Correction Capture UX & Strict Azan Preemption

- **Correction Chips (`VoiceOverlay.kt`):**
  - Whenever retrieval produces a medium/low-confidence match (`< 0.80`) or no match above threshold, `KbRetrievalEngine.retrieveWithCandidates()` surfaces the top 2–3 runner-up intents as bilingual chips under `"Did you mean:"` / `"هل تقصد:"`.
  - Auto-dismiss extends from `3s` to `6s` (`POST_TTS_WITH_SUGGESTIONS_AUTO_DISMISS_MS`) when chips are shown.
  - Tapping a chip calls `VoiceIoEngine.applyCorrectionSuggestion()`, which immediately executes the selected intent via `VoiceCommandDispatcher`, records/increments the normalized transcript in `VoiceLearningStore`, and dismisses cleanly.
- **Strict Azan & Alarm Preemption:**
  - Religious alerts and alarms unconditionally override the Voice Assistant.
  - `AlertService` (synchronously when an alert starts) and `P4Tablet` (`LaunchedEffect(alert)`) invoke `VoiceIoEngine.abortImmediately()`, halting `AudioRecord`, canceling active coroutine jobs, stopping `AudioTrack` / TTS immediately, and hiding `VoiceOverlay`.
  - Starting a new listening session is blocked while `AlertState.current.value != null`.

---

## Storage Layout & Configuration Reference

### Appliance Directory Hierarchy (`FileSystemManager.kt`)

Initialized idempotently on every boot at `<externalFilesDir>/NourAlwelayah/` (typically `/sdcard/Android/data/com.nouralwelayah.clock/files/NourAlwelayah/`) with a root `.nomedia` file to prevent Android MediaScanner pollution:

| Subdirectory | Purpose | Visible in Web Remote / Player? |
| :--- | :--- | :--- |
| `alarms/` | Custom alarm audio/video files (MP3, WAV, M4A, MP4, etc.) | Yes |
| `audios/` | Quran recitations, Duas, Ziyarat, and local audio tracks | Yes |
| `azan/` | Custom Azan recordings for Fajr, Dhuhr, and Maghrib | Yes |
| `images/` | Azan pictures displayed behind the clock during Azan playback | Yes |
| `videos/` | Local video files for full-screen Media3 playback | Yes |
| `wallpaper/` | Custom 16:10 clock background wallpapers | Yes |
| `config/` | Internal configuration / local OTA `nour_kb.db` staging | **Hidden & Blocked** |
| `voice_models/` | `sherpa-onnx` VAD, Whisper INT8, and Arabic/English Piper TTS weights | **Hidden & Blocked** |

### Core `AppSettings` Schema (`data/AppSettings.kt`)

| Field | Default | Description |
| :--- | :--- | :--- |
| `city`, `lat`, `lon`, `zoneId` | `"Makkah"`, `21.4225`, `39.8262`, `""` | Active prayer calculation location & timezone (`""` = device timezone) |
| `method`, `fajrAngle`, `maghribAngle` | `14` (Jafari), `0.0`, `0.0` | Calculation method index and optional custom solar angles |
| `hijriAdjust` | `0` | Moon-sighting Hijri offset (`-2..+2` days) |
| `azanOn`, `azanSound`, `azanDefault` | `[true, true, true]` | Per-prayer (Fajr, Dhuhr, Maghrib) Azan enable flags & audio paths |
| `azanVolume`, `azanLeadSec` | `15` (`0..21`), `0` (`0..500s`) | Azan playback volume and early start lead time |
| `use24h`, `keepScreenOn`, `brightness` | `true`, `true`, `-1` (Auto) | Clock format, wakelock, and window brightness (`5..100` or `-1`) |
| `azanPicture`, `clockPicture`, `selectedWallpaper` | `true`, `false`, `""` | Azan image overlay and active wallpaper filename (`""` = Solid Navy) |
| `appLanguage` | `"en"` | Global UI language (`"en"` LTR or `"ar"` RTL) |
| `webRemote`, `webPin` | `true`, `""` | Embedded HTTP server (`:8080`) enable flag and optional 4–8 digit PIN |
| `backToClockMin`, `mediaTimeoutSec`, `mediaStayAwake` | `5`, `30`, `false` | Idle auto-return timers for tabs and full-screen `MediaActivity` |
| `kioskMode`, `adminPin` | `false`, `"9200"` | Device Owner Lock Task toggle and 5-tap Technician Portal PIN |
| `otaServerUrl`, `maxVolumeLimit` | `"https://api.nouralwelayah.com/v1"`, `100` | Unattended OTA endpoint and hardware volume ceiling (`0..100%`) |
| `voiceAssistantEnabled`, `voiceRecognitionMode` | `true`, `"auto"` | Offline Voice Assistant toggle and Whisper language mode (`"auto"`, `"ar"`, `"en"`) |

---

## Testing & Evaluation Suite

Unit and retrieval evaluation tests live under `app/src/test/java/com/nouralwelayah/clock/` and run locally on the JVM without an emulator:

| Test Suite | Coverage |
| :--- | :--- |
| `voice/TextNormalizerTest.kt` | Arabic diacritic/tatweel removal, alef/taa-marbuta/alef-maqsura unification, Arabic-Indic digit conversion, language detection, English punctuation normalization. |
| `voice/VoiceRetrievalEvalTest.kt` | **48 held-out bilingual utterances** (24 Arabic + 24 English spanning all 10 subcategories in the 122-intent KB: navigation, clock/battery/Qibla/Hijri/occasion queries, prayer times & countdowns, Azan control, alarms, Media3 playback & seek, volume, display/wallpaper, Wi-Fi/BLE/Hotspot, and voice recognition modes), collision disambiguation (`رجع الصوت` → `volume_unmute` vs. `رجع المقطع` → `media_seek_backward`), plus **`VoiceLearningPolicy` guardrail tests** (canonical exact match priority, `hit_count >= 2` promotion gate, 90-day expiry, and 250-entry LRU eviction). |
| `PrayerCalcTest.kt` | Solar astronomical prayer-time math & calculation method validation. |
| `CityFileTest.kt` | Built-in and cached city lookup parsing and persistence. |

### Running Tests

```bash
# Windows (PowerShell / CMD)
.\gradlew.bat :app:testDebugUnitTest

# Run only the Voice Assistant normalization, 36-utterance eval & policy regression suite
.\gradlew.bat :app:testDebugUnitTest --tests "com.nouralwelayah.clock.voice.TextNormalizerTest" --tests "com.nouralwelayah.clock.voice.VoiceRetrievalEvalTest"

# Linux / macOS (or container environments with system Gradle)
gradle :app:testDebugUnitTest
```

---

## Building & Tablet Deployment

### Prerequisites

- **JDK 17** (`sourceCompatibility` / `targetCompatibility` = `17`)
- **Android SDK 35** (`compileSdk = 35`)
- **`app/libs/sherpa-onnx-2.0.2.aar`** present in `app/libs/`
- **ADB** (Android Platform-Tools) on `PATH` for USB or Wireless deployment
- **Python 3.8+** (for `scripts/download_voice_models.py` and KB compilation)

### 1. Download & Stage `sherpa-onnx` Voice Models

Run the provisioning script to download official `k2-fsa/sherpa-onnx` releases (Silero VAD, Whisper `tiny` INT8, Arabic `ar_JO-kareem-medium` Piper TTS, and English `en_US-amy-low` Piper TTS):

```bash
python scripts/download_voice_models.py
```

By default, models are staged under `app/src/main/assets/voice_models/` (or pass `--output <dir>` to stage externally for `adb push` without inflating APK size).

### 2. Build the Debug or Release APK

```powershell
# Windows PowerShell
.\gradlew.bat assembleDebug

# Output APK:
# app\build\outputs\apk\debug\app-debug.apk
```

For signed release builds, provide a local `keystore.properties` (excluded from Git) and run `.\gradlew.bat assembleRelease`.

### 3. Automated Deployment to Tablet (`scripts/deploy_tablet.ps1`)

The PowerShell deployment script builds the debug APK, installs it over ADB, pushes `voice_models/` to `/sdcard/Android/data/com.nouralwelayah.clock/files/NourAlwelayah/voice_models/`, grants `RECORD_AUDIO`, and launches `MainActivity`:

```powershell
# USB-connected tablet
.\scripts\deploy_tablet.ps1

# Wireless ADB tablet (or specify serial)
.\scripts\deploy_tablet.ps1 -DeviceSerial "192.168.1.50:5555"

# Skip Gradle rebuild when only pushing updated models/APK
.\scripts\deploy_tablet.ps1 -SkipBuild
```

### 4. Manual Wireless ADB Deployment

```bash
# 1. Pair (first time on Android 11+ Wireless Debugging) and connect
adb pair <TABLET_IP>:<PAIR_PORT>
adb connect <TABLET_IP>:<CONNECT_PORT>

# 2. Install/update the APK preserving app data
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Grant microphone permission for offline voice capture
adb shell pm grant com.nouralwelayah.clock android.permission.RECORD_AUDIO

# 4. Push external sherpa-onnx voice models (if not bundled inside the APK)
adb shell mkdir -p /sdcard/Android/data/com.nouralwelayah.clock/files/NourAlwelayah/voice_models
adb push app/src/main/assets/voice_models/. /sdcard/Android/data/com.nouralwelayah.clock/files/NourAlwelayah/voice_models/

# 5. Launch the appliance
adb shell am start -n com.nouralwelayah.clock/.MainActivity
```

---

## Kiosk / Device Owner Provisioning

To enable full Kiosk lockdown (Lock Task Mode, persistent Home launcher, disabled status bar/keyguard, silent Device Owner OTA installs, and silent `RECORD_AUDIO` auto-grant), provision the app as Device Owner on a fresh tablet with **no Google/device accounts signed in**:

```bash
# Provision Device Owner
adb shell dpm set-device-owner com.nouralwelayah.clock/.AdminReceiver

# Verify provisioning
adb shell dumpsys device_policy | grep -i "Device Owner"
```

Once provisioned:
- Enable **Kiosk Mode** in **Settings → Kiosk mode** or via the hidden **Technician Portal** (5 rapid taps on the top-left corner of the Clock screen → PIN `9200`).
- To exit Kiosk Mode for servicing without ADB:
  - **Technician Portal:** 5 taps top-left on Clock → enter `adminPin` → toggle **Kiosk Master Toggle** off.
  - **Maintainer Unlock (Settings Tab):** Tap `v2.0` 7 times → tap **Maintainer Unlock** → enter `webPin` (or fallback `999999` if `webPin` is unset).

---

## Troubleshooting & Security Notes

- **Voice Assistant Shows "Missing Models" in Diagnostics:**
  - Open **Settings** or **Admin Dashboard → Voice Subsystem Diagnostics** to see the exact missing relative paths under `/NourAlwelayah/voice_models/`.
  - Push the missing files via `scripts/deploy_tablet.ps1` or `adb push` and tap **`⟳ Re-scan Diagnostics`**.
  - Even without `.onnx` models, the **Test Query Console** remains 100% functional for verifying `TextNormalizer`, `nour_kb.db` FTS5 retrieval, and `VoiceCommandDispatcher`.
- **Microphone Permission Blocked in Kiosk Mode:**
  - If provisioned as Device Owner (API 23+), tap **`Auto-Grant (Owner)`** in Voice Diagnostics or run `adb shell pm grant com.nouralwelayah.clock android.permission.RECORD_AUDIO` before locking Kiosk Mode.
- **Short Voice Commands Mis-Detecting Language:**
  - Switch **Recognition Mode** in **Settings / Admin Dashboard → Voice Assistant** from `Auto (Bilingual)` to `Arabic Priority` or `English Priority`.
  - Tap the `"Did you mean:"` / `"هل تقصد:"` correction chip when a phrase is misclassified; after 2 confirmed taps, `VoiceLearningStore` promotes the phrase locally.
- **Privacy & Storage Hardening:**
  - Zero cloud AI calls and zero audio recording persistence—`VoiceLearningStore` stores text transcripts and intent IDs only inside app-private storage (`filesDir/voice_learning/voice_learning.db`).
  - `FileSystemManager.resolveSafePath()` blocks `..` directory traversal and null-byte injection across the Web Remote and Voice `PLAY_AUDIO` dispatcher, while keeping `/config` and `/voice_models` hidden from consumer file browsers and HTTP clients.
  - `android:allowBackup="false"` prevents unintended cloud backup of appliance PINs or local phrase memory.
