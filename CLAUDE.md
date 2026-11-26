# Prayer Clock Android app - context for Claude

Stand-alone Android (phone + tablet) version of the ESP32 prayer clock firmware
(C:\Users\salih\Documents\SquareLine_Project\PrayerTimesEsp32P4_10_V1_3, see its CLAUDE.md).
Kotlin, Jetpack Compose (Material 3), minSdk 26, targetSdk 35, package com.salihys.prayerclock.
Written in a Claude app chat (Nov 2026) without an Android SDK. First build (2026-11-04): `assembleDebug`
compiled cleanly and has not been tested on a device yet.

## Rules

- `core/PrayerCalc.kt` and `core/Hijri.kt` must stay identical in behaviour to the firmware's
  prayer_times.cpp / hiri.cpp / UQCal.cpp (angles rounded to float like the firmware). If the firmware
  changes, port the change. Method list order = firmware order (settings store the index); append only.
- The clock shows Fajr, Sunrise, Dhuhr, Sunset, Maghrib only; azans for Fajr, Dhuhr, Maghrib (Shia timing).
- Maghrib = sun angle below horizon (0.833 = sunset). Custom Fajr + Maghrib angles are set together
  (fajrAngle > 0 means custom); "Method angles" checkbox greys both fields out.

- Two apps (product flavors): standard (com.salihys.prayerclock, minSdk 26, PrayerClock-release.apk) and fire
  (com.salihys.prayerclock.fire, minSdk 22 = Fire OS 5 / Android 5.1, PrayerClockFire-release.apk, "Prayer Clock Fire").
  Same code: newer APIs behind Build.VERSION checks (Compat.kt: ifChannels, connectLe, localIpv4, HAS_HOTSPOT;
  Sys.svc instead of getSystemService(Class)); java.time via core library desugaring. Check with `lintFireDebug` (NewApi).
  Off on the Fire: no-router hotspot (WiFi tab card hidden), battery / overlay permission rows. Build tasks are now
  assembleStandardRelease / assembleFireRelease.
  Fire OS 5: DISABLE_KEYGUARD is signature|amazon, and WindowManager removes FLAG_SHOW_WHEN_LOCKED / DISMISS_KEYGUARD
  without it - nothing shows over the Fire lock screen. Compat.wakeScreen (ACQUIRE_CAUSES_WAKEUP wake lock) turns the
  screen on for the boot clock and azans (the window flag alone does not wake it).
  Kiosk mode (Kiosk.kt, AdminReceiver + res/xml/device_admin.xml, AppSettings.kioskMode "kiosk"): the only non-root
  way past the Fire swipe lock. Once the app is device owner (adb shell dpm set-device-owner
  com.salihys.prayerclock.fire/com.salihys.prayerclock.AdminReceiver, tablet with no accounts), MainActivity.onResume
  pins it with startLockTask (whole package whitelisted). Tablet Settings tab > Kiosk mode: adb steps, switch, Exit
  kiosk now, Remove device owner (do that before uninstalling). No-op without device owner. Not set up on a tablet yet.

## Structure

- core/: PrayerCalc (engine + CALC_METHODS), Hijri + UmmAlQuraData, Planner (day times, alarm rules, next events)
- data/: AppSettings + Store (SharedPreferences JSON, StateFlows), Alarm, Cities (BUILTIN_CITIES = examples),
  CityFile (city list = PrayerClock/config/cities.txt like the firmware's cities.cpp: "name; lat; lon; tz", created with
  the examples, Online picks added / updated, web page list / use / delete; POSIX time zones not supported),
  AzanLibrary (user azan files copied to filesDir/azan; settings store the file name).
  Azan sound = prayer's own file -> azanDefault -> phone alarm sound (differs from firmware's Dhuhr fallback)
- alert/: AlertScheduler (one exact alarm = next event; setAlarmClock), AlertReceiver / BootReceiver,
  AlertService (foreground mediaPlayback service, MediaPlayer on USAGE_ALARM, full-screen notification,
  snooze), AlertActivity (over lock screen), AlertState (what is ringing)
- ui/: Theme (firmware colours), ClockScreen, PrayerScreen, MonthScreen, AlarmsScreen, SettingsScreen, Common
  (RoundSlider: every slider uses it - Material 3 1.3's default thumb is a vertical bar; 36 dp round thumb, no track gap)
- web/WebRemote (Wi-Fi remote, media player only, setting webRemote + webPin): plain-socket HTTP server on
  port 8080 inside the MediaRemoteServer service, serving assets/PTclock.html and the same /api/... calls as the
  firmware's remote.cpp (status, cmd, settings, cities, geo, 40 alarm slots, azan/alarm test, fs/* with "/" =
  Internal storage/PrayerClock, upbegin + put upload). Player calls go through WebPlayer on the main thread.
  Not available (answer an error): wifi changes, restart, maint, video, Bluetooth file transfer, POSIX time zones.
- send/: Send files over Wi-Fi to the tablet or an ESP32 clock (Share > "Send to Prayer Clock", or Media remote >
  Send files). Targets finds the tablet by DNS-SD "_prayerclock._tcp" (WebRemote announces it) and clocks by
  "_http._tcp" + /api/auth probe; remembers devices, PINs, last folder. UploadService (foreground dataSync) uses the
  clock's upload: /api/fs/upbegin (dir, name, pin) then /api/fs/put (raw, fixed length). Stage 1 of the plan
  (2: P4 layout and tabs on the tablet, 3: Bluetooth for small files, 4: no-router mode with Wi-Fi Direct).
- ui/p4/ (stage 2): the tablet (smallestScreenWidthDp >= 600) shows the ESP32-P4 clock's layout instead of the
  phone tabs: P4Tablet with the P4 tab bar (Clock, Prayer, Alarms, Music, WiFi, Settings), sizes from ui.cpp in P4
  pixels scaled by P4Scale (1280 x 800 -> the screen). Clock = ClockScreen (links off) + ClockPicture + mini player;
  Prayer/Alarms/Music/WiFi/Settings = buildPrayerTab / buildAlarmsTab / buildMusicTab / buildWifiTab /
  buildSettingsTab. Music plays PrayerClock folders through MediaService; videos open MediaActivity full screen.
  Settings tab adds brightness (AppSettings.brightness, window), This device, Clock pictures, Folders, permissions.
  Clock name (firmware [NAME], AppSettings.clockName, data/ClockName): sets the tablet's Bluetooth name (device's own
  name remembered and restored when emptied), DNS-SD name, page bname / ble; MediaRemoteServer applies it and also
  opens the Bluetooth remote when Bluetooth turns on later (ACTION_STATE_CHANGED). The BLE scan response includes the
  name (retried without it if it does not fit); a name change restarts advertising after 1.5 s (reAdvertise).
- Stage 3: files over Bluetooth to the tablet (no Wi-Fi): media/BleUploads on the tablet (upbegin / updata
  base64 pieces of 4500 bytes / upend, each acknowledged), send/BleFileSender on the phone (no-response writes,
  high connection priority). Send lists the tablet as "ble:<address>" (BLE scan for MediaProtocol.SERVICE).
  About 28 KB/s. MediaProtocol.pieces() caps pieces at 512 bytes (Android 14+ negotiates MTU 517).
- Stage 4, no-router mode: web/Hotspot (tablet) = Android local-only hotspot (random name / password, no internet);
  WiFi tab card with QR code; BLE command {"c":"hotspot"} starts it and answers ssid/pass/ip/pin/fresh,
  "hotspotoff" stops it if a phone started it. MediaRemoteServer is also a "location" foreground service so the
  hotspot can start with the screen off (Android 12 needs location for it). The P1250 has one Wi-Fi radio: it
  leaves the home Wi-Fi while the hotspot is on (hotspot ip = the new address, on wlan0). Phone: Send > tablet
  over Bluetooth > "Fast" = UploadService.runHotspot: BLE asks, send/TabletWifi joins with WifiNetworkSpecifier
  (Android asks to allow; app-only network), uploads via Network.openConnection, then hotspotoff. Waits 6 s
  for a fresh hotspot (not visible at once) and retries the join once. Measured 3.7 MB/s.
- YouTube: MediaActivity plays it with the official IFrame API in a WebView. MediaEntry ids "yt:<video>" and
  "ytl:<playlist>" (link with list=, not RD mixes; next/prev = player.nextVideo/previousVideo). Search:
  data/YouTubeSearch (YouTube Data API v3, user's own key in AppSettings.youTubeKey, tablet Settings tab >
  YouTube search; search = 100 units of 10,000/day). Tablet Music tab > YouTube; the phone's Media remote sends
  {"c":"ytsearch","q"} -> {"t":"ytresults","list"|"err"}, then {"c":"ytplay","id","title","pl"}. ui/YouTubeUi
  (NetImage thumbnails, YtResultRow).
- Clock remote over Bluetooth to the tablet: MediaRemoteServer also hosts the Nordic UART service (6e400001...,
  added after the media service; advertised in the scan response, both 128-bit UUIDs do not fit one packet) and
  answers the firmware's page protocol {"id","p","m","a"} -> {"id","s","d"} with WebRemote.api() (shared with
  HTTP), /api/hello, a 1 s {"ev":"status"} push after a PIN-passed request, fs/up + fs/read in base64.
- Tablet restart (alert/Receivers.kt BootReceiver): starts MediaRemoteServer (Wi-Fi page + Bluetooth) and opens
  MainActivity (shown over the swipe lock screen, screen on). Android blocks background activity starts (an alarm
  PendingIntent was aborted too) unless "Display over other apps" (SYSTEM_ALERT_WINDOW, Settings > permissions row) is
  allowed; fallback = silent full-screen notification, which does nothing while the app's notifications are off.
- Tablet: activities locked to landscape (lockTabletLandscape, smallest width >= 600 dp); the Clock screen keeps
  the screen on with the window flag FLAG_KEEP_SCREEN_ON (View.keepScreenOn did not work there).
- Folders (like the firmware's SD card): data/AppFolders - Internal storage/PrayerClock/azan, music, videos, alarms,
  images, wallpaper, config. Needs MANAGE_EXTERNAL_STORAGE ("All files access", Settings > Folders); without it
  azan / images / wallpaper use private filesDir (azan, azanpics, wallpaper) and move to the folders once allowed.
  music + videos are listed first in the media player (📁, MediaLibrary.rescan); alarm sound = a path under PrayerClock,
  any folder like firmware 1.4.5/1.4.6 [SNDPICK] ("alarms/<name>", "music/Quran/a.mp3"; AppFolders.soundRef / refFile /
  refPagePath, AzanLibrary.uriFor resolves it; web page sends "/music/..."), chosen in ui/SoundBrowser (folders, parent,
  Refresh, Default, Cancel; tablet alarm editor + phone "Folder"); a test from the editor keeps it open (no Clock tab switch); config/settings.json + alarms.json written on every save, Import in Settings.
- Clock pictures (tablet only, firmware [AZBG] / [CLKBG]): data/PictureLibrary (PrayerClock/images, PrayerClock/wallpaper;
  azan picture tied to a prayer when its name contains fajr / dhuhr / maghrib, like the firmware), ui/ClockPictures (ClockPicture background,
  40% darkened, and the Settings section). Two switches: azanPicture (on, new picture each azan / test) and
  clockPicture (off, wallpaper picked when switched on / app start, returns after the azan). ActiveAlert.azan = prayer
  index. Shown behind the full-screen clock and on AlertActivity when width >= 600 dp.
- MainActivity: the Clock screen is full screen without tabs; its gear (top row) opens the other screens,
  which have tabs (width >= 600 dp -> NavigationRail on the left, else bottom NavigationBar; Back or Clock
  returns to the clock); 1 s clock ticker. System bar icons always white (SystemBarStyle.dark).
- remote/: RemoteActivity (WebView with the clock's PTclock.html, copied from the P4 V1_3 firmware folder
  at build time), BleUart, ClockPicker, ClockPins, assets/ble-shim.js - from PrayerClockRemote
  ClockPicker also lists Wi-Fi devices (send/Targets: the tablet's Wi-Fi remote, ESP32 clocks); choosing one
  loads that device's own page (http://ip[:8080]/, the page's on-board WiFi mode). Back returns to the app page.
  "Open by IP address" (and Send's address field): a bare IP tries port 80 (ESP32 clock), then 8080 (tablet -
  Android apps cannot bind ports < 1024, so the tablet stays on 8080).
  Firmware update card: WebView reports "Uploading 100%" at once (bytes in the phone's network buffer, not the clock's
  progress); RemoteActivity.onPageFinished runs OTA_NOTE_JS, which adds a note after 100% (app only, page unchanged).
  Page System tab > "Media remote (tablet)" card (board "Android tablet", any browser, Wi-Fi or Bluetooth page mode):
  WebRemote /api/media?c=... = the Bluetooth media remote's commands via WebPlayer.webMedia (MediaRemoteServer;
  perform() answers through a reply callback, shared with Bluetooth) + "list" (files, yt, ytkey, state) and "state".
  Inside this app the card also has "Open in the app" (bridge AndroidBle.openMediaRemote -> MediaRemoteActivity).
  YouTube key from the page (firmware page S3 1.3.8 / P4 1.4.7): c=ytkey&key= saves AppSettings.youTubeKey ("" removes);
  the card shows a key box instead of the search row while "list" answers ytkey:false.
  Page changes go to this app, the firmware folders (sync_page.bat), GitHub Pages (update_github_page.bat) AND the
  separate PrayerClockRemote app (..\PrayerClockRemote, ClockPageActivity = RemoteActivity): every clock-remote change
  here is ported there too (user's rule, 13 Nov 2026).
- Releases: release APKs are published as GitHub Releases on both repos, not committed to git. Each release bumps
  versionCode + versionName (minor: 1.1 -> 1.2) in app/build.gradle.kts, tag v<versionName> (gh release create).
  Here 1.1 (code 2), PrayerClockRemote 2.1 (code 3) on 14 Nov 2026 (the first releases; earlier date-tagged ones were deleted).
  For the tablet the page hides the firmware update and maintenance mode cards.
- data/OnlineGeo: "Online" city search - Open-Meteo geocoding (time zone included, "City, cc" narrows by
  country), fallback OpenStreetMap Nominatim (Arabic names, no time zone). Used by both Settings screens and
  the Wi-Fi remote's /api/geo (instead of the firmware's radar.io).
- media/: MediaActivity (media player: files via Media3 MediaController, YouTube via IFrame API in a
  WebView - it must be MATCH_PARENT or the page height is 0), MediaService (ExoPlayer in a
  MediaSessionService, handles audio focus). data/MediaLibrary keeps file URIs (persisted permission) and
  YouTube ids. Settings "This device" (mediaRole: -1 auto = tablet player, 0 clock remote, 1 media player)
  picks the Clock screen link. AlertService takes AUDIOFOCUS_GAIN_TRANSIENT so azan / alarms pause media.
  Media remote over BLE: tablet = MediaRemoteServer (foreground service, GATT server + advertising, own
  UUIDs in MediaProtocol, JSON lines split to the MTU), phone = MediaRemoteClient + MediaRemoteActivity.
  MediaHub shares YouTube state / volume / full screen between the server and MediaActivity. The remote
  can browse the tablet's MediaStore folders; a video started from it opens full screen; stop closes the
  player screen. A YouTube link shared on a "clock remote" phone is sent to the tablet.

## Known limits / ideas

- Two events in the same minute: only the first rings.
- No azan audio bundled (user picks files). No home-screen widget yet. No Arabic UI yet.
