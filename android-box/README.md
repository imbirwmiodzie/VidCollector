# Upload Box (Android PoC)

Turns an Android phone into a local "upload box". The app runs an HTTP server on
port **8080** inside a foreground service and shows its own URL as large text and as a
QR code. Other phones on the same Wi-Fi (a router, or this phone's own hotspot) scan
the code and upload videos from the browser. Files are streamed straight to app storage.

This is a validation PoC. It checks that the APK, server, QR code and LAN access work.
Resumable (tus) uploads, the tap game, the log collector, video metadata, microSD and
HTTPS are later phases.

| | |
|---|---|
| Package | `com.vidcollector.uploadbox` |
| Version | `0.1.0` (versionCode 1) |
| Min / target | Android 8.0 (API 26) / API 35 |
| Download | [`dist/upload-box.apk`](dist/upload-box.apk) or the GitHub Release `v0.1.0` |
| Checksum | [`dist/upload-box.apk.sha256`](dist/upload-box.apk.sha256) |

## Install on a phone

**Sideload (no computer):**

1. On the phone, open the release page
   `https://github.com/imbirwmiodzie/VidCollector/releases/tag/v0.1.0`
   and tap `upload-box.apk`. You can also open the file `android-box/dist/upload-box.apk` in the repository and tap **Download raw file**.
2. Open the downloaded file. Android asks you to allow **Install unknown apps** for
   the browser (Chrome or Samsung Internet). Allow it, go back and tap **Install**.
3. If Play Protect shows "App scanned / unsafe app blocked", tap **More details →
   Install anyway**. The app is signed with a self-made PoC key, which Play Protect
   doesn't know.

**With a computer (USB debugging on):**

```bash
adb install -r android-box/dist/upload-box.apk
```

All builds are signed with the same PoC key (`keystore/upload-box-poc.jks`), so a newer
APK installs over an older one without uninstalling.

## One-time toggles on the server phone

1. **Notifications**: allow them when the app asks (Android 13+). Without them the
   service still runs, but the "Upload box running" notification is hidden. If you
   denied it, use the **Allow notifications** button.
2. **Battery**: tap **Battery optimization exemption → Allow**. On Samsung, also tap
   **App settings → Battery → Unrestricted**. Otherwise One UI may kill the server
   when the screen is off.
3. **Play Protect**: "Install anyway" (see above). This only appears the first time.

## Use

1. Turn on the hotspot (Settings → Connections → Mobile Hotspot), or join a Wi-Fi network.
   The app cannot switch the hotspot on for you.
2. Open **Upload Box** and tap **Start**. The URL `http://<ip>:8080/` and its QR code appear.
   The notification "Upload box running" shows the same URL and a **Stop** action.
3. On another phone on the same network, scan the QR code with the camera, then
   **Choose files → Upload**. The page shows per-file and total progress and MB/s.
4. Files land in `Android/data/com.vidcollector.uploadbox/files/uploads/` on the server
   phone and appear in the app's **Received files** list.

The hotspot address changes every time the hotspot starts (Android 11+). The app
re-reads the address every 2 s while it's open, on every network callback and on the
hotspot on/off broadcast, and updates the URL and QR code. The notification refreshes every 5 s.

## Build

Requirements: JDK 17+ (tested with 21) and the Android SDK with platform 35 and
build-tools 35. Android Studio is not needed.

```bash
cd android-box
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or export ANDROID_HOME
./gradlew assembleRelease          # → app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest      # unit + Robolectric tests
```

`assembleDebug` works too. The release build is preferred because debuggable apps
run noticeably slower on ART, and throughput matters here. To refresh the
downloadable copy, run:

```bash
cp app/build/outputs/apk/release/app-release.apk dist/upload-box.apk
```

```bash
(cd dist && sha256sum upload-box.apk > upload-box.apk.sha256)
```

## HTTP API

| Method | Path | Result |
|---|---|---|
| GET | `/` | Upload page (`app/src/main/assets/index.html`), "Upload box works" |
| GET | `/health` | `{"ok":true,"ip":"192.168.x.y","port":8080,"freeBytes":…,"files":N,"uploading":N}` |
| GET | `/files` | `{"files":[{"name":…,"size":…,"modified":<epoch ms>}]}` (newest first) |
| POST or PUT | `/upload?name=<file name>` | Raw body = file bytes. Returns `{"ok":true,"name","size","sha256","ms","mbps"}` |

Upload rules:
- The body is the raw file, not multipart. `Content-Length` is required (browsers always
  send it for a `File` body), and chunked bodies get `411`.
- The data is streamed in 256 KiB chunks to a hidden `.<uuid>.part` file and
  renamed only when every declared byte has arrived. A dropped connection leaves no file.
  Size is limited only by free space, so 4 GB+ works.
- If `Content-Length` > free space − 50 MB, the server replies `507` without reading the body.
- Names are sanitised to the last path segment and safe characters. Duplicates get
  `-1`, `-2`, … suffixes.
- The server computes SHA-256 while streaming, so the client can verify without a second pass.

From a computer, for example:

```bash
curl -H 'Expect:' -T video.mp4 "http://192.168.43.1:8080/upload?name=video.mp4"
```

`-T` streams from disk, while `--data-binary @file` loads the whole file into RAM. `Expect:` avoids a 1 s wait,
because NanoHTTPD doesn't send `100 Continue`.
[`scripts/smoke-test.sh`](scripts/smoke-test.sh) does health → upload → checksum → list
(via `adb forward` or a direct URL).

## Design notes

- **HTTP server: NanoHTTPD 2.3.1 rather than Ktor CIO.** NanoHTTPD is a single ~100 KB
  Java jar with no transitive dependencies. Ktor CIO pulls in kotlinx-coroutines,
  ktor-io/http/utils, kotlinx-io and slf4j (several MB, plus R8 rules), and its benefits
  (coroutines, a routing DSL, many concurrent connections) don't matter for a box with
  4 endpoints and a handful of uploading phones. NanoHTTPD exposes the raw request
  `InputStream`, which is all that streaming uploads (and a tus server later) need. It
  uses one thread per connection, so parallel uploads work. Caveats: it is unmaintained
  since 2016, it has no chunked request bodies and no `100 Continue`. Both are irrelevant for browsers.
  The server class (`UploadServer.kt`) has no Android imports and could be moved to Ktor later.
- **Foreground service type `dataSync`** with a partial wake lock (the CPU keeps running with the screen
  off) and a Wi-Fi lock (`FULL_HIGH_PERF` on Android ≤ 13, `FULL_LOW_LATENCY` on 14+).
  The lock matters when the phone is a Wi-Fi client of a router; it does nothing in hotspot mode.
  **Android 15+ limits `dataSync` services to 6 h per 24 h.** When the limit hits, the
  app stops cleanly and shows a message, and you press Start again. If that becomes a
  problem, switch to `specialUse` (fine for sideloading).
- **IP selection (`IpPicker.kt`):** private IPv4 only (10/8, 172.16/12, 192.168/16), on these interfaces in order:
  hotspot `ap*` / `swlan*` → `wlan*` → other LAN (`eth*`, `rndis*`, `p2p*` …). Cellular (`rmnet*`,
  `ccmni*`, `v4-*`), VPN (`tun*`, `ppp*`), loopback and dummy interfaces are never used. If the phone
  is on a router and running a hotspot at the same time, the hotspot wins. The other candidates are
  shown in small text under the QR code.
- **UI:** plain `Activity` and Views. There is no AppCompat or Compose, so the APK is ~0.9 MB. The QR code is
  generated on the device with ZXing core.
- **Signing:** `keystore/upload-box-poc.jks` (password `uploadbox-poc`, alias `uploadbox`)
  is committed **on purpose** so every build has the same signature. It's a test key, not a
  secret. Replace it before any wider distribution.
- Ready for later phases: `/upload` could be complemented by tus endpoints in the same
  `UploadServer.serve()` router. The `.part`/rename scheme maps to tus offsets, and
  `UploadServer.Events` is the hook for the log collector and dashboard. The storage dir is one
  function (`UploadBoxService.storageDir`), so a microSD path can replace it.

## What is tested automatically

`./gradlew testReleaseUnitTest` runs:

- `IpPickerTest`: private-range detection, Samsung `swlan0` hotspot vs. cellular,
  hotspot vs. router Wi-Fi, Pixel-style `wlan1` hotspot, VPN, cellular and CGNAT exclusion, ordering.
- `UploadServerTest` (plain JVM over real loopback sockets): `/`, `/health`, `/files`, a
  **64 MB streamed upload with a SHA-256 comparison** (server reply and the file on disk), duplicate
  names, path traversal, an empty file, a truncated upload leaving no file, `411`, `507`, `404`/`405`.
- `UploadBoxServiceTest` (Robolectric, Android 15 framework): starts the **real
  foreground service**. It checks the notification title "Upload box running", the wake lock
  being held, `GET /health`, and `GET /` served from assets. It does a **50 MB POST with a SHA-256 comparison**
  of the file in `getExternalFilesDir`, then checks that the Stop action releases the locks.
- `MainActivityTest` (Robolectric, native graphics): the main screen shows the `swlan0` URL. The
  QR bitmap is **decoded with ZXing and equals the URL**. When the hotspot address changes, the URL and QR
  code update within one refresh. With only cellular available, the screen shows the "No Wi-Fi" hint and no QR code.

CI (`.github/workflows/android-box.yml`) builds the APK and runs these tests on every push.

## Manual test checklist (real phones)

Use generated test files or test videos only (no personal videos). For example:

```bash
ffmpeg -f lavfi -i testsrc2=size=1920x1080:rate=30 -t 600 -c:v libx264 test-300mb.mp4
```

Or record a few minutes of something neutral.

**A. Install and start (server = Samsung S20 FE)**
- [ ] APK downloads from the release page and installs (Install unknown apps, Play Protect "Install anyway").
- [ ] The app opens and asks for notifications. Allow it.
- [ ] Battery optimization exemption is allowed, and App settings → Battery is set to **Unrestricted**.
- [ ] Turn the **hotspot** on, open the app and tap **Start**. The notification "Upload box running" appears.
- [ ] The URL shows the **hotspot IP** (Settings → Mobile Hotspot → connected devices / or `swlan0` shown under the QR code).
- [ ] Turn the hotspot off and on again. Within a few seconds the URL and QR code change to the new address.

**B. Upload over the hotspot (client = second phone)**
- [ ] The client joins the hotspot and scans the QR code. The page shows "Upload box works" and the box info line.
- [ ] Upload a ~300 MB video. Record the **MB/s** shown on the page: ______ MB/s.
- [ ] The file appears in the app's "Received files" list with the correct size. `/files` lists it.
- [ ] Optional: compare checksums. The upload reply contains `sha256`, which you can see in the server logcat
      (`adb logcat -s UploadBox`), and you can compare it with `sha256sum` of the original.
- [ ] Upload several files at once (`multiple`). All finish.

**C. Screen off**
- [ ] Start a ~300 MB upload, then press the power button on the **server** phone right away.
      The upload must continue to 100% (keep the client screen on). Speed: ______ MB/s.
- [ ] Leave the server screen off for 10+ minutes, then start another upload. The box is still reachable.

**D. Router Wi-Fi**
- [ ] Turn the hotspot off. Both phones join the same router. The URL changes to the `wlan0` address.
- [ ] Repeat B and C. Speed: ______ MB/s.

**E. Edge cases (optional)**
- [ ] Upload a file > 4 GB (a 4K60 clip). It completes and the size matches.
- [ ] Stop the box mid-upload. The client shows an error and no partial file appears in the list.
- [ ] Swipe the app away from recents. The server keeps running (the notification stays).

Note any Samsung-specific kills (the notification disappears or the server becomes unreachable) with the time
and the screen state.
