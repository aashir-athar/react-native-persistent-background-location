# Changelog

All notable changes to `react-native-persistent-background-location` are
documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.1] - 2026-06-17

Android build fix — the module now **compiles cleanly** under **Kotlin 2.1.20
(K2)** / Gradle 9, verified locally with `compileReleaseKotlin` against
play-services-location 21.3.0. No public API or behavior changes.

### Fixed

- **`ActivityRecognitionHelper`: corrected the activity-transition builder method
  name.** The chain called `setActivityTransitionType(...)`, which does **not
  exist** in `play-services-location` 21.x — the real method is
  `setActivityTransition(Int)` (confirmed via `javap` + a green compile). The
  accompanying "cannot infer type" error was only a cascade from this unresolved
  call.
- **`BackgroundLocationService.start` is now `internal`** — it is a `public`
  function taking the `internal` `LocationConfig`, which K2 rejects. All callers
  are in-module.
- **`getCurrentPosition`: `suspendCancellableCoroutine` pinned to `<Location?>`**
  (K2 inferred `Nothing?`, rejecting `cont.resume(location)`).

## [0.1.0] - 2026-06-17

Initial public release — the Expo-free, **Nitro-powered** continuous background
location module for bare React Native. The open escape from the TransistorSoft
Android-release paywall.

### Added

- **Android killed-app survival.** A `location`-typed **foreground service** that
  posts its notification immediately, returns `START_STICKY`, reloads its config
  **from disk** on a system-initiated restart, and does a best-effort restart on
  `onTaskRemoved` (swipe-to-kill). A `BOOT_COMPLETED` **boot receiver** re-arms
  tracking after a reboot when `restartOnBoot` is enabled.
- **iOS resume after termination.** Significant-location-change (SLC) + region
  monitoring relaunch the app in the background after force-quit. The launch
  observer is installed from a **`+load`** hook, so background resume works
  **without any `AppDelegate` edits** by the consumer. (Continuous high-rate GPS
  does not resume after force-quit — that is an OS limitation, not a bug.)
- **Native SQLite buffer + offline sync.** Fixes are persisted to an app-private
  SQLite database and batch-synced over HTTP to a developer-configured `syncUrl`,
  with auto-sync, manual `flush()`, `getBufferedLocations()`, `clearBuffer()`,
  and a `maxRecordsToPersist` cap that drops the oldest rows.
- **New Architecture / Nitro.** Built entirely on
  [Nitro Modules](https://nitro.margelo.com) — a single typed `HybridObject`, no
  old-bridge support. Requires the New Architecture (React Native 0.86+).
- **Config plugin.** `@expo/config-plugins`-based plugin that writes the iOS
  `NSLocation*` / `NSMotion` usage strings and `UIBackgroundModes` (location by
  default; `fetch` / `processing` opt-in via `enableBackgroundFetch`), and the
  Android location / foreground-service / boot permissions — with opt-outs for
  `ACCESS_BACKGROUND_LOCATION` and `ACTIVITY_RECOGNITION` via `tools:node="remove"`.
- **Fused + platform location engines.** `FusedLocationProviderClient` on Android
  where Play Services is available, with a platform `LocationManager` fallback;
  CoreLocation on iOS.
- **Motion gate.** A speed heuristic plus Android `ActivityRecognition` classifies
  moving ⇄ stationary transitions and can throttle updates while stationary,
  surfaced through `onMotionChange` and each `LocationFix.activity`.
- **Typed public API.** `start` / `stop` / `isRunning` / `getStatus` /
  `getCurrentPosition`, buffer controls, permission helpers
  (`getPermissionStatus` / `requestPermissions` / `openSettings`), and
  ref-counted event subscriptions (`onLocation`, `onMotionChange`,
  `onProviderChange`, `onSync`, `onError`).
- **Privacy-safe sync defaults.** `start()` rejects a non-HTTPS `syncUrl` unless
  `buffer.allowInsecureSync` is set (development only); no telemetry — location
  data goes only to your configured `syncUrl`.

[Unreleased]: https://github.com/aashir-athar/react-native-persistent-background-location/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/aashir-athar/react-native-persistent-background-location/releases/tag/v0.1.0
