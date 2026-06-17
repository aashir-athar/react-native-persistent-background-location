<div align="center">

# 🚀 ZERO-TO-DEPLOY

### Maintainer runbook for `react-native-persistent-background-location` — empty folder → npm.

</div>

> **Audience:** the maintainer. End users follow [README.md](./README.md).

This package is a **Nitro module** whose native survival behaviour — Android foreground service + `START_STICKY` + boot receiver, iOS significant-location-change relaunch — **cannot be verified in CI.** It needs CocoaPods + a real Xcode build, Gradle + a real device, and *actual physical movement* to be meaningful. So the CI gate is the JS / Nitro-spec / config-plugin layer; **killed-app survival is proven on-device, by hand.** Treat the on-device kill test (§4) as the load-bearing test for every release.

---

## 1. Prerequisites

| Tool | Version |
|---|---|
| Node | ≥ 20 LTS |
| npm | ≥ 10 |
| Xcode | 16+ (iOS 15.1 deployment target) |
| CocoaPods | 1.15+ |
| Android Studio / JDK | **JDK 17**, **Android SDK 36**, **NDK 27** |
| Two real devices | one iPhone (iOS 15+), one Android phone (incl. one aggressive OEM — Xiaomi/Samsung — if you can) |

> The simulator/emulator are useless for the things that matter here. iOS SLC relaunch only fires on real ~500 m movement; Android FGS-survival behaves differently on real OEM ROMs than on a stock emulator. **Always test on hardware, ideally walking/driving.**

---

## 2. How the Nitro scaffold works

- Specs live in `src/specs/*.nitro.ts` — `PersistentBackgroundLocation.nitro.ts` (the HybridObject) and `LocationTypes.nitro.ts` (the flat structs). `nitro.json` configures codegen (module name `PersistentBackgroundLocation`, C++ namespace `persistentbackgroundlocation`, Swift impl `HybridPersistentBackgroundLocation`, Kotlin impl `HybridPersistentBackgroundLocation`).
- `npm run codegen` (`nitrogen`) regenerates `nitrogen/generated/**` — the C++ bridges, the Swift/Kotlin abstract specs, and the autolinking glue. **The generated output is committed** so consumers never run codegen; CI fails if it drifts.
- You implement the generated specs:
  - **iOS** — Swift `HybridPersistentBackgroundLocation` in `ios/` driving `CLLocationManager` (+ the `+load` launch observer for the SLC relaunch path; `LocationConfig.swift` / `PBLConstants.swift` are already scaffolded).
  - **Android** — Kotlin `HybridPersistentBackgroundLocation` in `android/src/main/java/com/margelo/nitro/persistentbackgroundlocation/` driving the fused provider + the `location` foreground service, boot receiver, and the Context-capturing `ContentProvider` (`Constants.kt` is already scaffolded).
- The podspec `load`s the generated `PersistentBackgroundLocation+autolinking.rb`; `android/build.gradle` applies the generated gradle and `CMakeLists.txt` includes the generated cmake.
- The JS wrapper (`src/index.ts`) normalizes friendly `StartOptions` → the flat `NativeStartConfig` (single source of every default = `normalizeStartOptions`), ref-counts event subscribers behind one native callback per event, and converts native optionals → public `null`s. The Expo **config plugin** lives in `plugin/` (compiled to `plugin/build`, re-exported by `app.plugin.js`).

**Whenever you change a `.nitro.ts` spec:** `npm run codegen`, commit `nitrogen/generated`, then update the matching Swift + Kotlin impls in lock-step. A spec drift is a build break for consumers.

---

## 3. Local dev loop

```sh
npm install
npm run codegen        # regenerate native specs (only after spec changes)
npm run build          # bob (JS → lib/) + tsc (config plugin → plugin/build)
npm run typecheck

# Run the example on a real device:
cd example
npm install
cd ios && pod install && cd ..   # iOS
npx react-native run-ios --device   # or: run-android  (use a real phone)
```

For Expo-dev-build consumers of the example, `npx expo prebuild --clean` then `npx expo run:ios` / `run:android` instead — but for the kill tests below, prefer a bare run on hardware.

---

## 4. The only test that matters: on-device killed-app survival

Verify on a **physical device**, ideally while actually moving. This is what the package exists to do; if it fails here, the release is not shippable.

### Android — swipe-kill + reboot

1. `requestPermissions({ background: true })` → grant **"Allow all the time"** (drive the two-step Settings escalation; confirm `PermissionAwareActivity` prompts correctly).
2. `start(...)` with a `foregroundService` notification and a `buffer.syncUrl` (point it at a local HTTPS endpoint or [webhook.site](https://webhook.site)).
3. Confirm the FGS notification appears and `onLocation` streams while **backgrounded with the screen off**.
4. **Swipe the app away from Recents.** Walk/drive. Confirm:
   - the foreground service keeps running (notification persists),
   - fixes keep hitting your `syncUrl` with **no JS attached**,
   - reopening the app **re-delivers** via `onLocation` and `getBufferedLocations()` shows the gap-period fixes.
5. **`adb shell am kill <pkg>`** (or force-stop then move): confirm `START_STICKY` recreates the service and it **reloads config from disk** (no JS) and resumes.
6. **Reboot the device.** With `restartOnBoot: true`, confirm the `BOOT_COMPLETED` receiver re-arms tracking.
7. **OEM check:** repeat 4–6 on an aggressive OEM (Xiaomi/Huawei/Samsung). Document which survive — this feeds the README's honest-limits section. Do **not** "fix" OEM kills with vendor hacks in v1.

### iOS — force-quit + real SLC relaunch

1. Grant **"Always"** (the OS surfaces the upgrade prompt after first background use).
2. `start({ useSignificantChanges: true })`. Confirm continuous fixes while backgrounded.
3. **Force-quit** (swipe up in the app switcher). The process is dead — confirm continuous GPS stops (expected; iOS limit).
4. **Move > 500 m for real** (walk a few blocks / drive). iOS relaunches the app in the background:
   - confirm the **`+load` launch observer fires** on `UIApplicationDidFinishLaunchingNotification` (add a temporary `os_log` / breakpoint to prove it),
   - confirm the tracker re-attaches, the SLC fix is written to the SQLite buffer, and (if configured) synced — **all with zero AppDelegate edits in the example app.**
5. Confirm `useSignificantChanges: false` stops tracking permanently on force-quit (negative test).

### Bare-RN specifics to verify explicitly

- **Android:** the **`ContentProvider` captures the app `Context`** before `Application.onCreate` — verify the service/buffer work when the process is recreated **headless** (no Activity). And verify the **`PermissionAwareActivity`** path drives the foreground→background permission prompt.
- **iOS:** the **`+load` launch observer** fires on a cold SLC relaunch with **no consumer AppDelegate changes** — this is the whole bare-RN value proposition; prove it on every release.

---

## 5. Static checks before publish

```sh
npm run codegen && git diff --exit-status nitrogen   # codegen is committed & clean
npm run typecheck
npm run build
npm pack --dry-run                                   # inspect the published file set
```

The tarball must include `src`, `lib`, `ios`, `android`, `nitrogen`, `plugin/build`, `app.plugin.js`, `react-native-persistent-background-location.podspec`, `react-native.config.js`, `nitro.json`, `README.md`, `LICENSE` — and must **exclude** `example/`, `.github/`, `node_modules`, tests, and dotfiles (the `files` allowlist + `!`-globs in `package.json` enforce this; eyeball the `npm pack --dry-run` output anyway).

---

## 6. Versioning

SemVer. Because the Nitro spec is a **native ↔ JS contract**, **any change to a method name, parameter, struct field, event, or default that changes the wire shape is a breaking change** → major bump. Keep `nitrogen/generated`, the Swift impls, and the Kotlin impls in lock-step, and keep `src/types.ts` ↔ `src/specs/LocationTypes.nitro.ts` consistent (the wrapper converts between them — a mismatch is a silent runtime bug). The `peerDependencies` floor on `react-native-nitro-modules` is also a contract: bump it deliberately and note Nitro ABI requirements in the changelog.

---

## 7. Building for publish

```sh
npm run codegen          # Nitro specs (commit nitrogen/generated)
npm run build            # bob build (JS) + plugin tsc (config plugin → plugin/build)
npm run typecheck
npm pack --dry-run       # final file-set sanity check
```

`npm run build` runs `bob build` (emits `lib/commonjs`, `lib/module`, `lib/typescript`) **and** `build:plugin` (`tsc -p plugin/tsconfig.json` → `plugin/build`). `prepare` runs the same on install, so a git/npm install builds automatically.

---

## 8. Publishing (provenance / trusted publishing)

CI (`.github/workflows/release.yml`) publishes on a pushed `vX.Y.Z` tag with provenance:

```sh
# Update CHANGELOG, bump version in package.json, commit.
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

Prefer **npm Trusted Publishing (OIDC)** — configure the package's trusted publisher to the GitHub Actions workflow and you can drop the long-lived token entirely; the action mints a short-lived credential and publishes with `--provenance` automatically. Fallback: an `NPM_TOKEN` repo secret (npm **Automation** token). To publish manually:

```sh
npm publish --provenance --access public
```

(`--provenance` requires the publish to run from a public CI with OIDC, or an npm token with provenance enabled. Confirm the green "provenance" badge on the npm page after publishing.)

---

## 9. Common pitfalls

- **Old Architecture** — Nitro requires the New Architecture; **there is no fallback**. If a consumer reports "cannot create HybridObject", they're on the old bridge or missing `react-native-nitro-modules`. Document it loudly (it's in the README).
- **Stale codegen** — CI fails if `nitrogen/generated` differs from a fresh `npm run codegen`. Always regenerate **and commit** after spec edits.
- **`src/types.ts` ↔ `*.nitro.ts` drift** — the public types and the flat native structs are two separate files bridged by the wrapper. Change one, change the other; a missing field conversion is a silent `undefined`.
- **iOS `+load` observer must self-register** — the SLC-relaunch value depends on the observer registering at `+load` (or via a static initializer) with **no consumer AppDelegate edits**. If you ever require the consumer to call a setup function, you've broken the headline feature.
- **Android Context without an Activity** — after a headless `START_STICKY` recreate there is no Activity and no JS. The service must use the `ContentProvider`-captured app `Context` and reload config from disk; never assume a React context exists.
- **Cleartext sync URL** — `normalizeStartOptions` throws on a non-HTTPS `syncUrl` unless `allowInsecureSync` is set. Keep that guard; location is PII. (It's a `start()`-time throw, easy to mistake for a permission error — note it when triaging.)
- **Foreground-service type (Android 14/15)** — the service must declare `foregroundServiceType="location"` and the app must hold `FOREGROUND_SERVICE_LOCATION`; location permission must be granted *before* the service starts or `startForeground` throws.
- **Don't add OEM autostart hacks in v1** — they're explicitly out of scope. Record OEM survival results in the README instead of shipping fragile vendor-specific workarounds.
