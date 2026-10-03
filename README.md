# M36 Media Server

A native Kotlin Android UPnP/DLNA MediaServer prototype for VLC on an Amazon Fire TV Stick. It shares one folder chosen with Android's Storage Access Framework, advertises a UPnP MediaServer over SSDP, returns ContentDirectory DIDL-Lite, and streams the selected files over HTTP without transcoding.

## Build

Requirements:

- Android Studio with JDK 17
- Android SDK Platform 35 and Android Build Tools 35.0.0
- Android SDK Platform 35 and Android Build Tools 35.0.0
- Internet access on the first Gradle run (the checked-in wrapper downloads Gradle 8.9)

Open this repository in Android Studio and let Gradle sync, or from a shell with `ANDROID_HOME` configured:

```sh
# Install once if needed:
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0"
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The local debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The branch CI build also publishes a directly installable copy at:

```text
artifacts/M36-Media-Server-debug.apk
```

Install it with Android Studio, or:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Gradle build uses AGP 8.7.3, Kotlin 2.0.21 and `kotlinx-coroutines-android` 1.9.0. The app targets API 34 and requires Android 8.0 (API 26) or newer.

## Use on the Galaxy M36

1. On the phone, open **M36 Media Server** and grant notification permission when prompted. No broad storage permission is requested.
2. Tap **Select Folder** and select `Internal storage/Jellyfin` (or the directory you actually want VLC to see). Confirm the picker selection. Android's SAF read grant is persisted across restarts.
3. Tap **Start Server**. Keep its ongoing notification present; use the notification's **Stop Server** action or the app's **Stop Server** button to shut it down.
4. Enable the M36 Mobile Hotspot and set the hotspot band to **5 GHz**. The server polls Android's network APIs/interfaces and should rebind/re-advertise when the hotspot interface appears; it is also fine to enable the hotspot before starting the server. Mobile data is not required.
5. Connect the Fire TV Stick to the M36 hotspot. In VLC, open **Local Network → UPnP**, wait a few seconds, then open **M36 Media Server → Jellyfin → Sakamoto Days → Season 1** and select an MKV. VLC should make normal HTTP byte-range requests when seeking.
6. To test the other cases, repeat with hotspot **2.4 GHz**, then with both devices on a regular Wi-Fi network at **5 GHz** and **2.4 GHz**. No address or URL is entered in VLC.

The app's **Show Details** panel is intended for diagnosing the first tethering test. Useful interpretations:

- **Multicast socket created = false**: no SSDP socket remained open; inspect socket details and active interfaces.
- **Socket created = true, multicast group joined = false**: socket creation worked but Android rejected the 239.255.255.250 membership.
- **Group joined = true, M-SEARCH requests stays at 0**: VLC's search is not reaching this process on that interface (or VLC has not issued a search yet). This is the key hotspot multicast/bridge diagnostic.
- **M-SEARCH increases but responses stay at 0**: inspect the last request's `ST`; this implementation answers `ssdp:all`, rootdevice, its UUID, MediaServer:1, ContentDirectory:1 and ConnectionManager:1.
- **Responses increase but HTTP stays at 0**: check the advertised interface/IP and whether VLC can fetch `rootDesc.xml` from the hotspot. HTTP is bound to detected local IPv4 addresses (not cellular/public interfaces).
- **HTTP requests increase**: the UPnP description/control path was reached. Subsequent `POST` requests are ContentDirectory SOAP; `/media/…` requests stream the actual file. `Range` GETs are answered with `206 Partial Content` and `Content-Range`.

## What is implemented

- SSDP multicast listener on `239.255.255.250:1900`, per-interface memberships, `M-SEARCH` responses, startup/periodic `ssdp:alive` and shutdown/network-change `ssdp:byebye` notifications.
- Stable persisted device UUID, UPnP root device description, ContentDirectory and ConnectionManager service descriptions/control URLs.
- ContentDirectory `Browse`, `GetSearchCapabilities`, `GetSortCapabilities`, `GetSystemUpdateID`; browse paging and `dc:title` sorting; XML-escaped DIDL-Lite containers/items.
- SAF-only read-only directory traversal. Files become HTTP-addressable only after appearing in a browsed directory; HTTP paths do not accept filesystem paths.
- HTTP GET/HEAD, correct MIME types for common media (including Matroska), streaming buffers, known-length responses and single byte-range/suffix-range handling for seeking. No full-file buffering and no transcoding.
- Android `ConnectivityManager` callbacks plus interface enumeration and a periodic fallback poll. SSDP is recreated and HTTP listeners rebound as addresses change. HTTP binds only to selected local IPv4 addresses.
- Foreground service, persistent stop notification, multicast lock and partial CPU wake lock while serving. The CPU wake lock helps keep transfers alive with the phone screen off, at the cost of battery while the server is left running.
- Live diagnostics for interface/address selection, socket/group status, SSDP counts, HTTP counts and last requests.

## Limitations / test status

- This prototype has not been exercised on a Galaxy M36 or Fire TV Stick from the build sandbox. Android/OEM hotspot multicast forwarding is intentionally attempted, not assumed; the diagnostic counters above are meant to establish whether M-SEARCH reaches the app. A successful socket join alone does not prove the hotspot forwards multicast.
- The server uses IPv4 SSDP and HTTP. It does not advertise IPv6 endpoints.
- Random-access seeking depends on the selected SAF provider returning a seekable descriptor and a known file size. The Galaxy's local shared-storage provider normally does; cloud/virtual providers may be pipe-backed and may not support byte ranges. Files with unknown length can be streamed chunked, but cannot satisfy a byte-range seek.
- VLC performs decoding. The app does not transcode; whether a particular Fire TV Stick decodes HEVC Main10 smoothly is a client/device capability question.
- The UI keeps folder selection disabled while serving so the advertised tree cannot silently differ from the selected tree. Stop, select another folder, then start again.

No accounts, cloud services, remote-access features, or internet media requests are used. The static manufacturer URL in the UPnP description is metadata only and is never opened by the app.
