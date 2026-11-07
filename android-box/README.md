# Upload Box (Android PoC)

Turns an Android phone into a local "upload box" that other phones upload videos to from their browser.
No internet, router or computer is needed.

- **Own Wi-Fi mode** (default, Android 10+): the box creates **its own Wi-Fi network** (a Wi-Fi Direct
  group with a name and password the app chooses). The screen shows two QR codes:
  **1 · Join Wi-Fi** (the phone camera offers "Join network", no typing) and **2 · Open page**
  (`http://192.168.49.1:8080/`). When a phone joins, the box highlights code 2.
- **Existing Wi-Fi or hotspot mode**: everyone is already on the same router or on the box phone's regular
  hotspot. The screen shows one QR code with the box URL.

The server runs in a foreground service on port **8080** and streams uploads straight to app storage.
This is a validation PoC. Resumable (tus) uploads, the tap game, the log collector, video metadata, microSD and
HTTPS are later phases.

| | |
|---|---|
| Package | `com.vidcollector.uploadbox` |
| Version | `0.2.1` (versionCode 3) |
| Min / target | Android 8.0 (API 26) / API 35. Own Wi-Fi mode needs Android 10 (API 29). |
| Download | [`dist/upload-box.apk`](dist/upload-box.apk) or the latest GitHub Release |
| Checksum | [`dist/upload-box.apk.sha256`](dist/upload-box.apk.sha256) |

## Install on a phone

**Sideload (no computer):**

1. On the phone, open
   `https://github.com/imbirwmiodzie/VidCollector/releases/latest`
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
APK (for example 0.2.0 over 0.1.0) installs over an older one without uninstalling.

## One-time toggles on the box phone

1. **Permissions**: when the app asks, allow **notifications** (Android 13+) and **Nearby devices**
   (Android 13+). Android 10–12 asks for **Location** instead and needs Location switched on. Both are needed only for
   own Wi-Fi mode. If you denied them, use the **Allow notifications / nearby devices** button.
2. **Battery**: tap **Battery optimization exemption → Allow**. On Samsung, also tap
   **App settings → Battery → Unrestricted**. Otherwise One UI may kill the server
   when the screen is off.
3. **Play Protect**: "Install anyway" (see above). This only appears the first time.

## Use: own Wi-Fi (default)

1. On the box phone, switch **Wi-Fi on**. It doesn't have to connect to any network. Turn the **regular hotspot off**,
   because many phones can't run both.
2. Open **Upload Box**, keep **Own Wi-Fi** selected and tap **Start**. Within a few seconds the
   network `DIRECT-UB-Box-XXXX` is up and both QR codes become sharp. The notification shows the network
   name and URL.
3. Each guest scans **1 · Join Wi-Fi** with the camera and taps **Join** or **Connect**. The box shows "Phone
   joined → now scan 2". The guest then scans **2 · Open page** and uploads.
4. **Before scanning, on each guest phone, turn off mobile data.** On Samsung, also go to Settings → Connections →
   Wi-Fi → ⋮ → Intelligent Wi-Fi and turn off **Switch to mobile data**. The box Wi-Fi has no internet, so a phone with mobile
   data may drop it again right away (a connect/disconnect loop) or send the page request over mobile data.
   If the phone asks "No internet, stay connected?", choose to stay.

### Troubleshooting own Wi-Fi: read the on-screen log
The box screen has a **Log** (newest first) that records:
- when the Wi-Fi is created, with its band;
- each guest joining or leaving (`Guest joined (…a1b2), now 1`);
- the first HTTP request from each guest IP (`Guest 192.168.49.x reached the box`);
- uploads;
- whether the box itself ever re-created the Wi-Fi.

| Log pattern | Meaning |
|---|---|
| `Guest joined` → `Guest left` a few seconds later, repeating, **no `reached the box`**, no `Creating Wi-Fi` in between | The **guest** drops the network. Usually it has no internet and mobile data is on (see step 4). It can also mean the guest got no IP address. |
| The same, but with `Wi-Fi disappeared … re-creating` / `Creating Wi-Fi` each time | The **box** lost or re-created its network. Report this with the log. |
| `Guest 192.168.49.x reached the box` | The guest has an IP address and works. Any later problem is in the page or upload. |

`adb logcat -s UploadBox` on the box shows the same lines.

The name and password are stored and stay the same across restarts, so the two codes can be **printed**
(a screenshot while stopped is enough). **New Wi-Fi name and password** (only while stopped) rotates them.
The box asks Android for a 5 GHz network and falls back to any band if the phone refuses. The band in use
is shown next to "Phones connected".

Why not an automatic redirect after joining? That would need a captive portal, meaning the box answers DNS
and controls DHCP. A non-rooted app can't do that (port 53 and the DHCP server belong to the system),
so the second QR code takes its place.

## Use: existing Wi-Fi or hotspot

1. Turn on the regular hotspot (Settings → Connections → Mobile Hotspot), or join a Wi-Fi router.
2. Select **Existing Wi-Fi or hotspot** and tap **Start**. The URL `http://<ip>:8080/` and its QR code appear.
3. Guests on the same network scan it and upload.

The hotspot address changes every time the hotspot starts (Android 11+). The app re-reads it every second
while it's open, on network callbacks and on hotspot on/off, and updates the QR code.

Files from both modes land in `Android/data/com.vidcollector.uploadbox/files/uploads/` and appear in
**Received files**.

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

Pushing a changed `dist/` triggers `.github/workflows/android-box-release.yml`, which publishes Release
`v<versionName>`.

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
curl -H 'Expect:' -T video.mp4 "http://192.168.49.1:8080/upload?name=video.mp4"
```

`-T` streams from disk, while `--data-binary @file` loads the whole file into RAM. `Expect:` avoids a 1 s wait,
because NanoHTTPD doesn't send `100 Continue`.
[`scripts/smoke-test.sh`](scripts/smoke-test.sh) does health → upload → checksum → list
(via `adb forward` or a direct URL).

## Design notes

- **Own Wi-Fi = Wi-Fi Direct group, not a hotspot.** Android has no public API for an app to
  switch on the regular hotspot. `LocalOnlyHotspot` exists, but the system picks a random name and password
  on every start and decides the band. A Wi-Fi Direct group created with
  `WifiP2pConfig.Builder` (Android 10+) gets **our** name and password (so the codes can be printed) and asks for **5 GHz**.
  Its address is always the group owner address `192.168.49.1`. Regular phones and iPhones join it as an
  ordinary WPA2 network. Code:
  - `WifiDirectGroup.kt`: create, the 5 GHz → auto-band fallback, a watchdog every 4 s that re-creates the group if
    the system dropped it, and the client count.
  - `P2pBackend.kt`: the system calls, behind an interface so the logic is unit-tested with a fake.
  - `WifiCredentials.kt`: the name/password rules (`DIRECT-xy…`, ≤ 32 bytes; WPA2 8–63 chars, no
    ambiguous characters) and the `WIFI:T:WPA;S:…;P:…;;` QR payload with escaping.
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
  The service also owns the Wi-Fi Direct group, so the network lives exactly as long as the server.
  **Android 15+ limits `dataSync` services to 6 h per 24 h.** When the limit hits, the
  app stops cleanly and shows a message, and you press Start again. If that becomes a
  problem, switch to `specialUse` (fine for sideloading).
- **IP selection (existing Wi-Fi mode, `IpPicker.kt`):** private IPv4 only (10/8, 172.16/12, 192.168/16), on these interfaces in order:
  hotspot `ap*` / `swlan*` → `wlan*` → other LAN (`eth*`, `rndis*`, `p2p*` …). Cellular (`rmnet*`,
  `ccmni*`, `v4-*`), VPN (`tun*`, `ppp*`), loopback and dummy interfaces are never used. In own Wi-Fi mode
  the address comes from the group's interface, falling back to `192.168.49.1`.
- **UI:** plain `Activity` and Views. There is no AppCompat or Compose, so the APK is ~0.9 MB. The QR codes are
  generated on the device with ZXing core.
- **Signing:** `keystore/upload-box-poc.jks` (password `uploadbox-poc`, alias `uploadbox`)
  is committed **on purpose** so every build has the same signature. It's a test key, not a
  secret. Replace it before any wider distribution.
- Ready for later phases: `/upload` could be complemented by tus endpoints in the same
  `UploadServer.serve()` router. The `.part`/rename scheme maps to tus offsets, and
  `UploadServer.Events` is the hook for the log collector and dashboard. The storage dir is one
  function (`UploadBoxService.storageDir`), so a microSD path can replace it.

## What is tested automatically

`./gradlew testReleaseUnitTest` runs 43 tests:

- `WifiCredentialsTest`: generated names and passwords are valid for Android and WPA2 (500 samples, no ambiguous
  characters). The `WIFI:` QR payload format and escaping of `\ ; , : "` are checked.
- `IpPickerTest`: private-range detection, Samsung `swlan0` hotspot vs. cellular,
  hotspot vs. router Wi-Fi, Pixel-style `wlan1` hotspot, VPN, cellular and CGNAT exclusion, ordering, and
  the Wi-Fi Direct address taken from the group interface or from 192.168.49.0/24.
- `WifiDirectGroupTest` (Robolectric, fake Wi-Fi Direct backend):
  - creates the group with the stored credentials on 5 GHz;
  - falls back to auto band when 5 GHz is refused;
  - the watchdog re-creates a dropped group and updates the client count;
  - a single momentary "no group" answer from the system does **not** re-create the group, which would kick guests.
    The test fails with the 0.2.0 behaviour;
  - guests joining and leaving are written to the on-screen log;
  - repeated failures are reported and retried until they succeed;
  - a missing permission is explained, and the group starts once it's granted, without a restart;
  - a phone with no Wi-Fi Direct is reported;
  - Stop removes the group and leaves no activity behind.
- `UploadServerTest` (plain JVM over real loopback sockets): `/`, `/health`, `/files`, a
  **64 MB streamed upload with a SHA-256 comparison**, duplicate names, path traversal, an empty file, a truncated
  upload leaving no file, `411`, `507`, `404`/`405`.
- `UploadBoxServiceTest` (Robolectric, Android 15 framework), two tests:
  - **Existing Wi-Fi mode** starts the real foreground service. It checks the notification, the wake lock,
    `/health`, the page from assets, a **50 MB POST with a SHA-256 comparison**, and that Stop releases the locks.
  - **Own Wi-Fi mode** checks that the group is created, `/health` reports `192.168.49.1`, the notification shows the
    network name and URL, and Stop removes the group.
- `MainActivityTest` (Robolectric, native graphics), two tests:
  - **Own Wi-Fi mode**: QR 1 **decodes to the exact `WIFI:` string** (with escaping) and QR 2 to the URL. The band and the client
    count are shown. A guest joining shows "Phone joined → now scan 2", which fades after 30 s. Problems
    (Wi-Fi off) show in the status line.
  - **Existing Wi-Fi mode**: the URL and QR code follow hotspot address changes, and the "No Wi-Fi" hint shows when there is no address.

CI (`.github/workflows/android-box.yml`) builds the APK and runs these tests on every push.

**Not testable here:** a real Wi-Fi Direct radio. No emulator can run in the build container, and emulators don't
support Wi-Fi Direct anyway. Everything about the radio is in the manual checklist below.

## Manual test checklist (real phones)

Use generated test files or test videos only (no personal videos). For example:

```bash
ffmpeg -f lavfi -i testsrc2=size=1920x1080:rate=30 -t 600 -c:v libx264 test-300mb.mp4
```

Or record a few minutes of something neutral.

**A. Install (box = Samsung S20 FE)**
- [ ] APK 0.2.1 downloads from the release page and installs over 0.1.0 or 0.2.0 (or fresh, with Play Protect "Install anyway").
- [ ] The app asks for notifications and Nearby devices. Allow both.
- [ ] Battery optimization exemption is allowed, and App settings → Battery is set to **Unrestricted**.

**B. Own Wi-Fi: create and join**
- [ ] Wi-Fi is on and the regular hotspot is off. Mode is **Own Wi-Fi**. Tap Start.
      Within about 5 s both codes are sharp, "Phones connected: 0 · __ GHz" shows, and the notification shows `DIRECT-UB-Box-…`.
- [ ] **Android guest:** scan code 1 with the camera and tap Join. The box shows "Phone joined" and the count is 1.
- [ ] Scan code 2. The page "Upload box works" opens. Note whether you had to turn mobile data off or tap "Stay connected": ______
- [ ] If the guest loops (connect → disconnect): screenshot the box **Log**. Then repeat with the guest's mobile data
      **off** and Intelligent Wi-Fi "Switch to mobile data" **off**. Does the loop stop? ______
- [ ] **iPhone guest** (if available): the same two scans. Note any prompts.
- [ ] Two guests at once: both join and the count is 2.

**C. Own Wi-Fi: upload and speed**
- [ ] Upload a ~300 MB video. Record the **MB/s** shown on the page: ______ MB/s (band: ____ GHz).
- [ ] The file appears in "Received files" with the correct size.
- [ ] Optional checksum: the `sha256` from `adb logcat -s UploadBox` should equal `sha256sum` of the original.
- [ ] Upload several files at once. All finish.

**D. Own Wi-Fi: screen off and stability**
- [ ] Start a ~300 MB upload, then press the power button on the **box** right away. The upload must reach 100%
      (keep the guest screen on). Speed: ______ MB/s.
- [ ] Leave the box screen off for 10+ minutes with no guests. Then a guest re-joins (the saved network should auto-reconnect)
      and uploads. Does the network still exist? ______
- [ ] Toggle the box's Wi-Fi off and on. Within about 10 s the group comes back with the **same name and password**,
      and the guest reconnects.
- [ ] Stop, then Start again: the same name and password.

**E. Existing Wi-Fi or hotspot (regression from 0.1.0)**
- [ ] Mode **Existing Wi-Fi or hotspot** with the regular hotspot on. The URL shows the `swlan0` address. Upload ~300 MB: ______ MB/s.
- [ ] Both phones on a router: the URL changes to `wlan0`. Upload ~300 MB: ______ MB/s.

**F. Edge cases (optional)**
- [ ] With the regular hotspot **on**, start own Wi-Fi. Either it works, or the red status explains the problem (note which).
- [ ] Upload a file > 4 GB (a 4K60 clip). It completes and the size matches.
- [ ] Stop the box mid-upload. The guest shows an error and no partial file appears in the list.
- [ ] Swipe the app away from recents. The server and the Wi-Fi keep running (the notification stays).

Note any Samsung-specific kills (the notification disappears, the network vanishes or the server is
unreachable) with the time and the screen state.
