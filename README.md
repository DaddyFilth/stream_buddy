# Stream Buddy

An Android pre-play cache and cache-first player for HLS and direct media streams.

Stream Buddy does one thing: it downloads the first part of a stream **before** you press play, so
playback starts from local storage instead of the network — and it keeps caching as you watch.

```
Queue a stream  ──▶  WorkManager job  ──▶  Media3 SimpleCache (2 GB LRU)
                                                    ▲
Player tab      ──▶  ExoPlayer, cache-first  ───────┘
```

## Status

CI (`.github/workflows/android.yml`) compiles the app, runs the unit tests and packages a debug
APK on every pull request; the APK is uploaded as the `stream-buddy-debug-apk` artifact of the run.
It has **not** been exercised on a physical device yet, so playback edge cases are untested — see
the limitations below.

## What works

| Area | Behaviour |
| --- | --- |
| **Queue** | Add an HLS playlist (`.m3u8`), a DASH manifest (`.mpd`) or a media file (`.mp4`, `.mkv`, …). Title is optional and derived from the URL when blank. Live cache state, progress, errors, retry and delete. |
| **Pre-caching** | HLS: resolves the master playlist to a variant, then caches segments covering the configured pre-play window (2/5/10/20 min), including `#EXT-X-MAP` init segments and `#EXT-X-BYTERANGE` sub-ranges. Progressive files: caches the first 25 % of the file (8 MB – 150 MB). Runs in the background with a low-priority progress notification and resumes after retries or cancellation. |
| **DRM** | FairPlay, Widevine, PlayReady and CENC are detected from the playlist/manifest and never cached. Ordinary HLS `AES-128` encryption is *not* DRM and stays cacheable. |
| **Playback** | ExoPlayer reads through the same cache, so pre-cached data is used immediately and everything else is cached as it plays (HLS segments always; progressive files when the server reports a length). HLS/DASH manifests deliberately bypass the cache so live playlists never go stale. |
| **Apps** | Lists launchable apps with streaming services first; tapping one opens it. DRM services only play inside their own app, so Stream Buddy launches them instead of pretending it can cache them. |
| **Settings** | Pre-play window, cache quality (lowest/highest HLS variant), cache usage and clearing, notification permission, version/about. |

## Honest limitations

* **DRM streaming services** (Netflix, Disney+, Prime Video, Max, …) cannot be pre-cached. Their
  apps are launch-only.
* **DASH** (`.mpd`) playback is supported; *pre-caching* MPDs is not implemented yet (the queue
  reports "Playback only" for those URLs).
* **Live HLS** is cached best-effort from the newest segments of the playlist snapshot. There is
  no DVR/timeshift support.
* **No background audio**: playback pauses when the app is backgrounded, because no
  `MediaSessionService`/notification transport controls are published yet.
* Cache eviction is LRU inside a fixed 2 GB budget (`CacheManager.MAX_BYTES`); deleting a queue
  entry does not evict its cached segments directly — "Clear cache" in Settings does.

## Build

Requirements: JDK 17, Android SDK Platform 35 + Build-Tools 35
(`compileSdk = 35`, `targetSdk = 34`, `minSdk = 24`).

```bash
# 1. Point Gradle at your SDK (Android Studio does this for you).
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or export ANDROID_HOME=...

# 2. Build the debug APK.
./gradlew assembleDebug

# 3. Run the unit tests (pure-JVM, no device needed).
./gradlew testDebugUnitTest
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. CI runs the same two commands on
every pull request and uploads the APK as the `stream-buddy-debug-apk` artifact.

## Project layout

```
app/src/main/java/com/streambuddy/app/
├── MainActivity.kt              bottom-nav shell (apps / queue / player / settings)
├── cache/
│   ├── CacheManager.kt          the single Media3 SimpleCache + data source factories
│   ├── PlaybackDataSource.kt    cache-first for media, direct for manifests
│   └── PreCacheWorker.kt        WorkManager job: progress → Room + notification
├── data/
│   ├── AppDb.kt                 Room entity/DAO/database for the queue
│   └── SettingsRepository.kt    SharedPreferences-backed settings as a StateFlow
├── net/Http.kt                  shared OkHttp client
├── stream/
│   ├── HlsPlaylistParser.kt     RFC 8216 subset: variants, segments, byte ranges, maps
│   ├── HlsPlaylist.kt           parsed models
│   ├── HlsAttributes.kt         quote-aware attribute-list parser
│   ├── DrmDetector.kt           FairPlay/Widevine/PlayReady/CENC detection
│   ├── PrefetchPlanner.kt       which segments cover the pre-play window
│   ├── PreCacheEngine.kt        segment download loop into the Media3 cache
│   ├── StreamFetcher.kt         playlist/probe HTTP calls
│   ├── StreamKind.kt            URL/content-type → stream type
│   ├── StreamUrl.kt             URL validation, scheme repair, title suggestion
│   └── PreCacheErrors.kt        retryable vs. permanent failures
├── ui/
│   ├── MainViewModel.kt         queue/settings flows + the single ExoPlayer
│   ├── AppsScreen.kt            launchable apps, streaming services first
│   ├── QueueScreen.kt           add streams, per-entry cache state
│   ├── PlayerScreen.kt          PlayerView + queue shortcuts
│   ├── SettingsScreen.kt        pre-play window, quality, storage, notifications
│   ├── Components.kt            shared Compose pieces
│   └── Theme.kt                 dark Material 3 theme
└── util/Format.kt               byte/duration/percent formatting
```

Everything in `stream/` (except `PreCacheEngine`/`StreamFetcher`) and `util/` is plain JVM Kotlin
with no Android dependencies, which is what `app/src/test` exercises.

## Tests

`./gradlew testDebugUnitTest` runs 37 unit tests covering:

* master/media playlist parsing, `#EXT-X-BYTERANGE`, `#EXT-X-MAP`, BOM/CRLF handling,
  relative/protocol-relative/awkward URL resolution,
* DRM detection (including "AES-128 is not DRM"),
* pre-play segment planning for VOD and live,
* URL validation and stream-kind detection,
* formatting helpers.

## Design notes

* **One cache instance per process.** Media3 requires a single `SimpleCache` per directory, so
  `CacheManager` is a singleton used by both playback and pre-caching. That is what makes
  pre-cached segments show up instantly in the player.
* **Cache keys line up.** Media3's default cache key is the `DataSpec` key, falling back to the
  resolved URI string. Pre-caching builds the same `DataSpec` ExoPlayer's HLS chunk source builds
  (`new DataSpec(uri, byteRangeOffset, byteRangeLength)`), so both sides write and read the same
  key, position and length — playback finds the pre-cached blocks.
* **Manifests bypass the cache.** HLS reloads its media playlist every target duration; caching
  those responses would serve a stale playlist and stall live playback.
* **No foreground service.** WorkManager runs the pre-cache job with a network constraint; it is
  resumable, so a stopped job simply continues where it left off on retry.

## Roadmap

* DASH pre-caching (MPD parsing + segment templates).
* `MediaSessionService` for background audio and lock-screen controls.
* Per-stream cache eviction, configurable cache budget.
* HLS variant switching during pre-cache (currently lowest or highest only).
