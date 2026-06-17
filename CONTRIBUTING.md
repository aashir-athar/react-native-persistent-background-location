# Contributing to react-native-persistent-background-location

Thanks for taking the time to contribute! This is a free, open-source **bare
React Native (Nitro)** module that keeps continuous background GPS alive across
app kills on Android and resumes after termination on iOS. It is the Expo-free,
Nitro-powered escape from the TransistorSoft Android-release paywall — and it
stays good because people like you file issues, send sample logs, and open PRs.

This document covers how to set up the repo, the Nitro codegen → build loop, the
coding standards we hold native and JS code to, how to actually test native
changes on a device (the only test that matters for a background-location
library), and our commit / PR conventions.

Please keep all project spaces — issues, pull requests, and discussions —
respectful and constructive.

---

## Table of contents

- [Ways to contribute](#ways-to-contribute)
- [Repository layout](#repository-layout)
- [Prerequisites](#prerequisites)
- [Setting up the repo](#setting-up-the-repo)
- [The Nitro codegen → build loop](#the-nitro-codegen--build-loop)
- [Running the example app](#running-the-example-app)
- [Coding standards](#coding-standards)
  - [TypeScript](#typescript)
  - [Kotlin (Android)](#kotlin-android)
  - [Swift (iOS)](#swift-ios)
  - [The native ⇄ JS contract](#the-native--js-contract)
  - [Config plugin](#config-plugin)
- [Testing native changes on a device](#testing-native-changes-on-a-device)
  - [Android: surviving swipe-to-kill](#android-surviving-swipe-to-kill)
  - [iOS: resuming after force-quit](#ios-resuming-after-force-quit)
  - [Buffer & offline sync](#buffer--offline-sync)
- [Commit conventions](#commit-conventions)
- [Pull request process](#pull-request-process)
- [PR checklist](#pr-checklist)
- [Scope: what we will and won't take](#scope-what-we-will-and-wont-take)
- [Reporting security issues](#reporting-security-issues)

---

## Ways to contribute

You don't have to write Kotlin or Swift to help. Especially valuable:

- **Device reports** — open an issue with the exact device, OEM skin
  (One UI / MIUI / HyperOS / EMUI / ColorOS / stock), Android/iOS version, and
  whether tracking survived swipe-to-kill, reboot, and Doze. Attach `adb logcat`
  (Android) or a trimmed Xcode console snippet (iOS).
- **OEM battery-killer findings** — these are explicitly out of scope for v1
  (see [Scope](#scope-what-we-will-and-wont-take)), but reproducible notes help
  us document reality honestly.
- **Docs & honesty fixes** — if the README or a doc oversells what's possible
  (e.g. implies iOS does continuous GPS after force-quit — it does not), send a
  PR. Honesty about platform limits is a feature here.
- **Native fixes** — foreground-service lifecycle, boot receiver, SLC/region
  monitoring, SQLite buffer, HTTP sync, motion gate.
- **Config-plugin coverage** — permission and Info.plist edge cases.

## Repository layout

```
.
├── src/                     # TypeScript public API + Nitro specs + types
│   ├── index.ts             # Public JS API (the surface users import)
│   ├── types.ts             # Canonical public type contract
│   └── specs/               # Nitro HybridObject + struct specs (*.nitro.ts)
├── nitrogen/                # Nitro-generated C++/Swift/Kotlin glue (committed)
├── android/                 # Kotlin: foreground service, boot receiver, fused engine, SQLite
├── ios/                     # Swift: CoreLocation engine, SLC/region monitoring, sqlite3 buffer
├── plugin/src/              # Config plugin (@expo/config-plugins) — Info.plist + AndroidManifest glue
├── example/                 # Expo dev-client example app used to test on a real device
├── nitro.json               # Nitro module config
└── package.json
```

## Prerequisites

- **Node** 20+ and your package manager of choice (`npm` / `yarn` / `pnpm`).
- **React Native 0.86** with the **New Architecture enabled** (Nitro requires
  it; there is no old-bridge fallback).
- **Android**: Android Studio, JDK 17, Android SDK with **compileSdk 36**,
  a device or emulator on **minSdk 24+**. A real device is required to test
  swipe-to-kill and boot survival meaningfully.
- **iOS**: macOS with **Xcode 16+**, an Apple developer signing identity, and a
  **real iPhone** — the Simulator cannot deliver real significant-location-change
  (SLC) events or relaunch your app after force-quit. (You can fake routes with
  *Features → Location*, but SLC resume after kill needs hardware.)

> This is a Nitro native module — it does **not** run in Expo Go. You always work
> through a dev client / prebuild.

## Setting up the repo

```bash
git clone https://github.com/aashir-athar/react-native-persistent-background-location
cd react-native-persistent-background-location
npm install

# Regenerate the Nitro glue, type-check, and build the library + config plugin.
npm run codegen
npm run typecheck
npm run build
```

## The Nitro codegen → build loop

This package is built with **Nitro Modules**. The TypeScript specs in
`src/specs/*.nitro.ts` are the source of truth for the native interface; the
C++/Swift/Kotlin bindings in `nitrogen/generated` are **generated** from them and
**committed** to the repo.

Whenever you change a `*.nitro.ts` spec:

```bash
npm run codegen      # nitrogen — regenerates nitrogen/generated from the specs
npm run typecheck    # tsc --noEmit — TS must be clean
npm run build        # bob build + tsc -p plugin/tsconfig.json (the config plugin)
```

Then **commit the regenerated `nitrogen/generated` output** alongside your spec
change — CI runs `npm run codegen` and a stale or missing generated tree will
fail review. After regenerating, rebuild the example's native projects (see
below) so the new interface is wired into the app.

`npm run build` produces the published JS (`lib/`, via `react-native-builder-bob`)
and the compiled config plugin (`plugin/build/`, via `tsc -p plugin/tsconfig.json`).

## Running the example app

The `example/` app is the harness for everything. It's an **Expo dev-client**
app (Nitro needs a dev build, not Expo Go) that resolves the library from the
repo root via `file:..` and a `metro.config.js` that watches the parent folder.
From the repo root:

```bash
cd example
npm install
npx expo prebuild --clean   # regenerate native projects with the local module + config plugin

# Android
npx expo run:android

# iOS (real device strongly recommended)
npx expo run:ios --device
```

Re-run `npx expo prebuild --clean` whenever you change the **config plugin** or
the **Nitro specs** (so the regenerated native glue is picked up). For pure
Kotlin/Swift edits you can usually rebuild from Android Studio / Xcode without a
full prebuild.

## Coding standards

### TypeScript

- **Strict mode, no exceptions.** Code must type-check with the project's strict
  config (`npm run typecheck`). Do not introduce `any`; prefer precise types and
  `unknown` + narrowing at boundaries. The native bridge is untyped at runtime,
  so sanitize there (see `normalizeStartOptions` / `sanitizeHeaders` in
  `src/index.ts` for the pattern — never let `undefined` cross the bridge).
- **Public API is the contract.** Everything users touch lives in `src/index.ts`,
  and every type they see lives in `src/types.ts`. Keep TSDoc on exported
  symbols — the existing files set the tone (explain *why*, document defaults, be
  honest about platform limits).
- **Defaults live in one place.** `normalizeStartOptions` is the single source of
  truth for every `StartOptions` default; native carries matching defaults only
  as a safety net. If you add an option, add its default there *and* mirror it
  natively.
- **Web/unsupported paths** must stay safe no-ops returning sane defaults — never
  throw on `stop()` / `isRunning()` / `getStatus()`; do throw on genuinely
  unavailable methods (`start`, `getCurrentPosition`).

### Kotlin (Android)

- Target **Kotlin** with **coroutines**; keep all I/O (SQLite, HTTP sync) off the
  main thread on a sane dispatcher with structured concurrency — never block the
  location callback thread.
- Follow the [official Kotlin style guide](https://kotlinlang.org/docs/coding-conventions.html);
  4-space indent, explicit visibility on public surface, no wildcard imports.
- The **foreground service** is the heart of kill-survival: it must be
  `location`-typed, post its notification immediately on `startForeground`,
  return `START_STICKY`, and reload its config **from disk** on a system-initiated
  restart (the JS runtime is gone — config must be re-read, not assumed in
  memory). `onTaskRemoved` does a best-effort restart; the `BOOT_COMPLETED`
  receiver re-arms after reboot.
- Use `FusedLocationProviderClient` for the fused engine; keep the platform
  `LocationManager` path as the no-Play-Services fallback.
- Guard every permission-sensitive call; runtime permissions can be revoked while
  the service runs.

### Swift (iOS)

- **Swift 5.9+**, deployment target **15.1**.
- Be honest in code and comments: iOS **cannot** run continuous GPS after the
  user force-quits. Only **significant-location-change** (~500 m granularity) and
  **region monitoring** can relaunch the app in the background. Do not write code
  or docs implying otherwise.
- The launch observer is installed from a **`+load`** hook so background relaunch
  works **without** requiring the consumer to edit their `AppDelegate`. Keep it
  that way — zero-AppDelegate-edit resume is a core promise.
- Set `allowsBackgroundLocationUpdates` only when entitled; respect
  `pausesLocationUpdatesAutomatically` and `showsBackgroundLocationIndicator`
  from config.
- Use system **`sqlite3`** directly for the buffer (no third-party DB). Always
  `finalize` prepared statements; do buffer writes off the main queue.
- Follow standard Swift API design guidelines; mark CoreLocation delegate work
  appropriately and avoid retain cycles in closures (`[weak self]`).

### The native ⇄ JS contract

The Nitro spec method and event names **must match across the spec, the
generated glue, the Swift `HybridObject`, and the Kotlin `HybridObject`**. Do not
rename one side only. When you change a spec, run `npm run codegen` and commit the
regenerated `nitrogen/generated` tree in the same PR.

Any `LocationFix` you emit must carry **every** documented key
(`id, latitude, longitude, accuracy, altitude, altitudeAccuracy, speed,
speedAccuracy, heading, headingAccuracy, timestamp, isMoving, activity,
batteryLevel, isCharging, mocked, provider`). Use `null` for fields the platform
didn't report — never omit a key, and never invent a value. Keep enum strings in
sync with the TS unions in `src/types.ts` (`accuracy`, `authorization`,
`activity`).

If you change any of the above, update **all layers** (spec, Swift, Kotlin),
`src/types.ts`, `src/index.ts`, and the README in the same PR.

### Config plugin

- The plugin (`plugin/src/index.ts`) is plain strict TypeScript that imports from
  `@expo/config-plugins`. It writes only the glue autolinking can't: iOS
  `NSLocation*` / `NSMotion` usage strings + `UIBackgroundModes`, and Android
  location / foreground-service / boot permissions (with opt-out via
  `tools:node="remove"`).
- It must compile under `plugin/tsconfig.json` (`npm run build:plugin`).
- Keep it **idempotent** and wrapped in `createRunOncePlugin`. Re-running prebuild
  must not duplicate entries.
- Any new option needs a documented default and a matching README row.

## Testing native changes on a device

A background-location library is only truly tested **on real hardware under real
lifecycle events**. Before requesting review on a native change, walk the
relevant scenario below and paste the result into the PR.

### Android: surviving swipe-to-kill

1. `cd example && npx expo run:android` on a **physical device**.
2. Grant foreground + background ("Allow all the time") location and (Android 13+)
   notifications.
3. `start()` with a `buffer.syncUrl` pointed at a request bin you control. Confirm
   the foreground-service notification appears and `onLocation` fires.
4. **Swipe the app away** from recents. Walk/drive ~500 m+.
5. Verify with `adb logcat` (run `start({ debug: true })`) that the service stays
   alive and fixes keep landing in the buffer / hitting your `syncUrl`.
6. Reboot the device (tests `restartOnBoot` + the `BOOT_COMPLETED` receiver).
   Confirm tracking re-arms without reopening the app.
7. Note the OEM skin and Android version — survival behaviour varies wildly across
   OEM battery managers.

### iOS: resuming after force-quit

1. `cd example && npx expo run:ios --device` on a **real iPhone**.
2. Grant "Always" location.
3. `start()` with `useSignificantChanges: true`. Confirm `onLocation` fires.
4. **Force-quit** (swipe up in the app switcher). Move the device **~500 m+**.
5. Confirm the OS relaunches the app in the background (via the `+load` launch
   observer, no AppDelegate edits) and `onLocation` resumes (provider will be
   `slc` or `visit`). Continuous high-rate GPS will **not** resume — that's
   expected and correct.

### Buffer & offline sync

1. Put the device in airplane mode, generate fixes, confirm rows accumulate via
   `getBufferedLocations()` and `getStatus().bufferedCount`.
2. Restore connectivity; confirm native auto-sync drains the buffer to `syncUrl`,
   `onSync` fires with `success: true`, and `flush()` reports a sensible count.
3. Confirm `maxRecordsToPersist` drops oldest rows (don't let a permanently
   offline device fill the disk) and `clearBuffer()` returns the deleted count.

## Commit conventions

We use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).
This keeps the [changelog](./CHANGELOG.md) and releases tidy.

```
<type>(<optional scope>): <short summary>
```

Common types: `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `build`,
`ci`, `chore`. Useful scopes: `android`, `ios`, `plugin`, `buffer`, `sync`,
`motion`, `permissions`, `nitro`, `types`, `example`, `docs`.

Examples:

```
feat(android): reload service config from disk on START_STICKY restart
fix(ios): finalize sqlite statement after buffered-fix insert
docs(readme): clarify iOS cannot run continuous GPS after force-quit
feat(plugin): allow opting out of ACTIVITY_RECOGNITION
```

Use `feat!:` / `fix!:` or a `BREAKING CHANGE:` footer for breaking changes
(anything that alters the public JS API, the Nitro spec method/event names, or
the `LocationFix` shape).

## Pull request process

1. **Open an issue first** for anything non-trivial so we can agree on approach —
   especially native lifecycle changes.
2. Branch off `main`: `git checkout -b feat/android-boot-retry`.
3. Make focused commits following the conventions above.
4. Run the full local gate: `npm run codegen && npm run typecheck && npm run build`.
5. Run the relevant **device scenario** above and capture the result.
6. Open the PR against `main` with a clear description, linked issue, and the
   completed checklist below. Keep PRs small and single-purpose.
7. Be responsive to review. Maintainers may ask for logcat/console output for any
   lifecycle-affecting change.

## PR checklist

Copy this into your PR description and tick each box:

- [ ] The change is focused and single-purpose; the PR title is a Conventional Commit.
- [ ] `npm run codegen` run and the regenerated `nitrogen/generated` is committed (if specs changed).
- [ ] `npm run typecheck` passes.
- [ ] `npm run build` succeeds (library **and** `plugin/build`).
- [ ] TypeScript is strict-clean — no new `any`, no `undefined` crossing the bridge.
- [ ] If the public API changed: `src/index.ts`, `src/types.ts`, and the README are all updated together.
- [ ] If a Nitro spec method or event changed: the spec, **Swift, and Kotlin** all match (names + payload shape), and the generated glue is committed.
- [ ] If a `LocationFix` field changed: every documented key is still present on both platforms (`null`, never omitted).
- [ ] If a `StartOptions` default changed: updated in `normalizeStartOptions` **and** mirrored natively.
- [ ] If the config plugin changed: re-ran `npx expo prebuild --clean` and verified Info.plist / AndroidManifest output; it's still idempotent.
- [ ] Tested on a **real device** for the affected scenario (Android swipe-to-kill / reboot, iOS force-quit resume, or buffer sync) and pasted the result.
- [ ] Docs are **honest** about platform limits — nothing oversells iOS background GPS or implies OEM-killer survival is solved.
- [ ] Added/updated tests where it makes sense.
- [ ] `CHANGELOG.md` has an entry under **Unreleased** describing the change.

## Scope: what we will and won't take

**In scope for v1:** Android killed-app survival via foreground service +
`START_STICKY` + `onTaskRemoved` best-effort restart + `BOOT_COMPLETED` receiver;
iOS resume after termination via SLC + region monitoring (installed from a
`+load` launch observer, no AppDelegate edits); the native SQLite buffer and
offline HTTP sync; fused **and** platform location engines; the heuristic +
`ActivityRecognition` motion gate; and the config plugin.

**Explicitly out of scope for v1 (document, don't promise):**

- **OEM battery-killer survival** (Xiaomi/MIUI/HyperOS, Huawei/EMUI, Samsung One
  UI aggressive app sleep, OPPO/Vivo/ColorOS, etc.). These OEMs kill background
  processes in ways no public API can reliably defeat. We document the reality
  and point users at per-OEM "don't optimize this app" settings, but we will not
  claim to beat them.
- **ML / adaptive battery-optimization avoidance.**
- **iOS continuous GPS after force-quit** — impossible by OS design; only SLC
  (~500 m) wakes the app. PRs implying otherwise will be asked to correct the
  framing.

PRs that *honestly document* these limits are very welcome. PRs that *claim to
solve* them will be sent back for evidence on multiple OEM devices.

## Reporting security issues

Please do **not** open a public issue for a vulnerability. Location data is
sensitive PII — follow the private process in [SECURITY.md](./SECURITY.md)
(GitHub Security Advisories or email subscriptions@hybriddot.com).

---

Thank you for helping keep continuous background location free and open. 🛰️
