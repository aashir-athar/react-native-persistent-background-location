<div align="center">

# react-native-persistent-background-location

### Continuous **background GPS** for **bare React Native** that survives swipe-to-kill on Android and resumes after termination on iOS — a **Nitro** module, New Architecture, **no Expo**.

Keep a continuous location stream alive while your app is backgrounded, **auto-restart it after the app is killed or swiped away on Android** (a `location` foreground service + boot receiver), and **resume after termination on iOS** (significant-location-change). Every fix is buffered to a native **SQLite** store *before* it reaches JS, and can be synced to your backend over HTTP by the native layer — so nothing is lost while the JS runtime is gone. The free, open-source escape from **TransistorSoft's Android-release paywall**, built for **plain React Native** with zero Expo dependency.

<br />

[![npm version](https://img.shields.io/npm/v/react-native-persistent-background-location.svg?style=for-the-badge&color=cb3837&logo=npm&logoColor=white)](https://www.npmjs.com/package/react-native-persistent-background-location)
[![npm downloads](https://img.shields.io/npm/dm/react-native-persistent-background-location.svg?style=for-the-badge&color=cb3837&logo=npm&logoColor=white)](https://www.npmjs.com/package/react-native-persistent-background-location)
[![Platform](https://img.shields.io/badge/platform-iOS%20%7C%20Android-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white)](#platform-support)
[![New Architecture](https://img.shields.io/badge/New%20Architecture-required-9457EB.svg?style=for-the-badge&logo=react&logoColor=white)](#requirements)

[![Nitro](https://img.shields.io/badge/Nitro-modules-ff6688.svg?style=flat-square)](https://nitro.margelo.com)
[![TypeScript](https://img.shields.io/badge/TypeScript-strict-3178c6.svg?style=flat-square&logo=typescript&logoColor=white)](#)
[![License](https://img.shields.io/npm/l/react-native-persistent-background-location.svg?style=flat-square&color=blue)](./LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat-square)](#contributing)

```
                       ┌──────────────────────────────────────────────────────┐
   OS location  ─────► │  Native tracker  (Kotlin FGS / Swift CoreLocation)   │
   (GPS / fused / SLC) │                                                       │
                       │   ① write fix ──► SQLite buffer  (survives app kill)  │
                       │   ② emit  ──────► onLocation  (when JS is attached)   │
                       │   ③ batch ──────► HTTP sync ──► your backend (offline-│
                       └────────────┬──────────────────────────────tolerant)──┘
                                    │
                  app killed/swiped │ app alive
              ┌─────────────────────┴─────────────────────┐
              ▼                                            ▼
   START_STICKY restart · onTaskRemoved          onLocation / onMotionChange
   restart · BOOT_COMPLETED receiver (Android)    onProviderChange / onSync /
   SLC relaunch via +load launch observer (iOS)   onError  ──►  Your UI / store
```

</div>

---

## Table of contents

- [Why this exists](#why-this-exists)
- [Comparison](#comparison)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick start](#quick-start)
- [API reference](#api-reference)
  - [Lifecycle](#lifecycle)
  - [Buffer & sync](#buffer--sync)
  - [Permissions](#permissions)
  - [Events](#events)
  - [Types](#types)
  - [Errors](#errors)
- [How killed-app survival works](#how-killed-app-survival-works)
- [Platform support](#platform-support)
- [Honest limitations — read before you ship](#honest-limitations--read-before-you-ship)
- [FAQ & troubleshooting](#faq--troubleshooting)
- [Relation to `expo-persistent-background-location`](#relation-to-expo-persistent-background-location)
- [Contributing](#contributing)
- [License](#license)

---

## Why this exists

Continuous background location on React Native has one production-grade native library — **[react-native-background-geolocation](https://github.com/transistorsoft/react-native-background-geolocation)** by TransistorSoft. It is excellent. It is also **paid for Android release builds**: debug builds are free, but shipping to the Play Store requires a per-app purchased license key. iOS is free; Android is not. For an indie dev or an OSS project, that licensing wall is a real barrier.

The framework-blessed alternative, **`expo-location`**, deliberately documents that it **cannot keep tracking after the app is force-quit**. From the Expo docs' platform-limitation note, background location updates *"stop when the app is terminated by the user."* And there is a long-standing, never-fully-closed thread about killed-app behaviour — **[expo/expo#3535](https://github.com/expo/expo/issues/3535)** — where people keep rediscovering that the background task does not survive a swipe-to-kill on Android (the `TaskManager`/background task is gone the moment the process dies; nothing relaunches it).

This is the **exact same gap the Expo sibling [`expo-persistent-background-location`](https://github.com/aashir-athar/expo-persistent-background-location) solves — but here for *bare React Native, without Expo*.** Same feature set, same public API, re-implemented as a **[Nitro](https://nitro.margelo.com) module** so it works in a plain RN app (New Architecture, no `expo-modules-core`, no managed workflow required):

- On **Android**, a `location`-typed **foreground service** keeps the process alive after swipe-to-kill, `START_STICKY` lets the system restart the service (reloading config from disk), `onTaskRemoved` does a best-effort restart, and a `BOOT_COMPLETED` receiver re-arms tracking after reboot.
- On **iOS**, **significant-location-change (SLC)** monitoring lets the OS relaunch the app in the background after the user force-quits it, and a self-registered `+load` launch observer re-attaches the tracker on relaunch — **with zero AppDelegate edits in your app**.
- Every fix is written to a **native SQLite buffer first**, then delivered to JS and/or **HTTP-synced** by the native layer — so a dead JS runtime or an offline network never loses data.

It does **not** try to out-engineer TransistorSoft. It is honest about what each OS actually permits (see [Honest limitations](#honest-limitations--read-before-you-ship)) — particularly the hard iOS force-quit limit and Android OEM battery killers.

---

## Comparison

| Package | Bare RN | Expo | Free Android release | Survives swipe-kill (Android) | Resumes after force-quit (iOS) | Native buffer + offline sync | Arch |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **react-native-persistent-background-location** | ✅ Nitro | via dev build | ✅ MIT | ✅ FGS + boot receiver | ✅ SLC (~500 m) | ✅ SQLite + HTTP | New Arch |
| [`expo-persistent-background-location`](https://github.com/aashir-athar/expo-persistent-background-location) | — | ✅ native module | ✅ MIT | ✅ FGS + boot receiver | ✅ SLC (~500 m) | ✅ SQLite + HTTP | New Arch |
| `react-native-background-geolocation` (TransistorSoft) | ✅ | ✅ | 💲 paid license | ✅ | ✅ SLC | ✅ | Both |
| `expo-location` | — | ✅ | ✅ | ❌ ([#3535](https://github.com/expo/expo/issues/3535)) | ❌ (docs note) | ❌ | Both |
| `@react-native-community/geolocation` | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | Both |

> Same API, two packages: pick **this** for bare React Native / Nitro, pick the **Expo** sibling for a managed Expo app. See [Relation to `expo-persistent-background-location`](#relation-to-expo-persistent-background-location).

---

## Requirements

- **React Native 0.79+ with the New Architecture enabled** (Nitro modules require it; there is **no old-bridge fallback** — Fabric / TurboModules only).
- **`react-native-nitro-modules`** installed in the app (peer dependency).
- **iOS 15+**, **Android minSdk 24+** (Android 7.0).

---

## Installation

```sh
npm install react-native-persistent-background-location react-native-nitro-modules
# or: yarn add / pnpm add
```

**iOS**

```sh
cd ios && pod install
```

> **No AppDelegate changes are required.** Unlike the typical iOS background-location library, this package **self-registers a launch observer** (a `+load`-time subscriber to `UIApplicationDidFinishLaunchingNotification`) to catch the significant-location-change relaunch and re-attach the tracker. You do **not** edit `AppDelegate.swift` / `AppDelegate.mm` at all.

**Android** — autolinks; no manual steps. (Release builds: the R8/ProGuard keep-rules for the service, receiver, and content provider ship automatically via `consumer-rules.pro`.) You still need to declare the platform permissions and the location-typed service in your app — see [Manifest & Info.plist](#manifest--infoplist) below.

**Expo (dev build) users** — this is a native module, so it needs a [development build](https://docs.expo.dev/develop/development-builds/introduction/) (it does **not** run in Expo Go). Add the bundled config plugin in `app.json` to inject the iOS usage strings + `UIBackgroundModes` and the Android location / foreground-service / boot permissions:

```json
{
  "expo": {
    "plugins": [
      [
        "react-native-persistent-background-location",
        {
          "locationWhenInUsePermission": "Allow $(PRODUCT_NAME) to use your location while tracking your route.",
          "locationAlwaysAndWhenInUsePermission": "Allow $(PRODUCT_NAME) to keep tracking your route in the background.",
          "isAndroidBackgroundLocationEnabled": true,
          "isAndroidForegroundServiceEnabled": true
        }
      ]
    ]
  }
}
```

then `npx expo prebuild --clean` and run a dev build. (If you're a pure managed-Expo app, prefer the [`expo-persistent-background-location`](https://github.com/aashir-athar/expo-persistent-background-location) sibling instead — same API, no Nitro peer dep.)

### Manifest & Info.plist

A bare RN app must declare the OS glue itself (the Expo config plugin does this for you when prebuilding). The essentials:

**`android/app/src/main/AndroidManifest.xml`** — permissions:

```xml
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- only if you enable motion.enabled: -->
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

The library's own `location`-typed foreground service, boot receiver, and the Context-capturing `ContentProvider` are merged in automatically by the library manifest — you only add the permissions above.

**`ios/<App>/Info.plist`** — usage strings (the App Store rejects builds without them) and background modes:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Allow location while tracking your route.</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Allow location to keep tracking in the background.</string>
<key>UIBackgroundModes</key>
<array>
  <string>location</string>
  <string>fetch</string>
  <string>processing</string>
</array>
```

---

## Quick start

```ts
import {
  requestPermissions,
  start,
  stop,
  onLocation,
  getStatus,
} from 'react-native-persistent-background-location';

async function beginTracking() {
  // 1. Ask for location authorization (foreground + background "Always").
  const perm = await requestPermissions({ background: true });
  if (perm.foreground !== 'granted') return;

  // 2. Subscribe to fixes (fires foregrounded, backgrounded, and after resume).
  const sub = onLocation((fix) => {
    console.log(`${fix.latitude}, ${fix.longitude} (±${fix.accuracy ?? '?'}m)`);
  });

  // 3. Start continuous background tracking.
  await start({
    accuracy: 'high',
    distanceFilter: 10,
    interval: 5000,
    foregroundService: {
      notificationTitle: 'Tracking your route',
      notificationBody: 'Tap to return to the app.',
    },
    buffer: {
      // HTTPS endpoint that receives batched fixes as a JSON array, with no JS:
      syncUrl: 'https://api.example.com/locations',
      headers: { Authorization: 'Bearer <token>' },
    },
    motion: { enabled: true }, // throttle GPS when stationary to save battery
  });

  // Snapshot whenever you like:
  const status = await getStatus();
  console.log(status.running, status.bufferedCount, status.authorization);

  // …later:
  sub.remove();
  await stop();
}
```

Tracking now continues with the screen off, after you background the app, and — on Android — after the task is swiped away (see [How killed-app survival works](#how-killed-app-survival-works)).

---

## API reference

Every function is imported from the package root. Async functions return a `Promise`; event subscriptions return a `Subscription` with a `.remove()` method. On **web**, every method is a typed no-op (or returns a sane default / throws on `start` / `getCurrentPosition` where a value is impossible) so cross-platform builds don't break.

```ts
import {
  start, stop, isRunning, getStatus, getCurrentPosition,
  getBufferedLocations, clearBuffer, flush,
  requestPermissions, getPermissionStatus, openSettings,
  onLocation, onMotionChange, onProviderChange, onSync, onError,
  LocationPermissionError,
} from 'react-native-persistent-background-location';
```

### Lifecycle

#### `start(options?)`

```ts
start(options?: StartOptions): Promise<void>;
```

Start (or **reconfigure**) continuous background tracking. **Idempotent** — calling `start` again reconfigures the running tracker rather than spawning a second one.

- On **Android**: launches a `location`-typed foreground service with the configured notification and, when `restartOnBoot` is set, arms the boot receiver. The config is persisted to disk so the service can reload it after a process kill.
- On **iOS**: begins standard location updates plus, when `useSignificantChanges` is set, significant-location-change monitoring so the OS can relaunch the app after force-quit.

Throws [`LocationPermissionError`](#locationpermissionerror) when authorization is missing, and throws on web. See [`StartOptions`](#startoptions) for every field.

#### `stop()`

```ts
stop(): Promise<void>;
```

Stop tracking, tear down the foreground service, and disarm boot restart. No-op on web.

#### `isRunning()`

```ts
isRunning(): boolean;
```

Whether the native tracker is currently running (synchronous). Returns `false` on web.

#### `getStatus()`

```ts
getStatus(): Promise<TrackingStatus>;
```

Snapshot of the current tracker state, authorization, and buffer size. See [`TrackingStatus`](#trackingstatus).

#### `getCurrentPosition(options?)`

```ts
getCurrentPosition(options?: CurrentPositionOptions): Promise<LocationFix>;
```

Resolve a single fresh fix **without** starting continuous tracking — a one-shot "where am I now". Throws on web.

| Option         | Type               | Default  | Range          | Effect                                                     |
| -------------- | ------------------ | -------- | -------------- | ---------------------------------------------------------- |
| `accuracy`     | `LocationAccuracy` | `'high'` | —              | Desired accuracy for the one-shot fix.                     |
| `timeoutMs`    | `number`           | `15000`  | `1000–300000`  | Reject after this many ms without a fix.                   |
| `maximumAgeMs` | `number`           | `0`      | `0–86400000`   | Accept a cached fix no older than this (`0` = force fresh).|

### Buffer & sync

#### `getBufferedLocations(limit?)`

```ts
getBufferedLocations(limit?: number): Promise<LocationFix[]>;
```

Read buffered fixes from the native SQLite store, **newest first** — these are the fixes captured while the app was killed or offline. Pass `0` (the default) for all rows. Returns `[]` on web.

#### `clearBuffer()`

```ts
clearBuffer(): Promise<number>;
```

Delete every buffered fix. Resolves with the number of rows removed. Returns `0` on web.

#### `flush()`

```ts
flush(): Promise<SyncResult>;
```

Force an immediate sync of the buffer to the configured `syncUrl`. Resolves with a [`SyncResult`](#syncresult). On web resolves with `{ success: false, count: 0, status: null, error: 'unsupported-platform' }`.

### Permissions

#### `getPermissionStatus()`

```ts
getPermissionStatus(): Promise<PermissionResult>;
```

Resolve the current foreground + background location authorization without prompting. See [`PermissionResult`](#permissionresult).

#### `requestPermissions(options?)`

```ts
requestPermissions(options?: { background?: boolean }): Promise<PermissionResult>;
```

Prompt for location authorization. By default (`background: true`) this also requests background ("Always") access. On **Android 11+** the OS mandates a **two-step** escalation — foreground is granted first, then the user is sent to Settings for background — which the native layer drives automatically via a `PermissionAwareActivity` prompt. See [`PermissionResult`](#permissionresult).

| Option       | Type      | Default | Effect                                            |
| ------------ | --------- | ------- | ------------------------------------------------- |
| `background` | `boolean` | `true`  | Also request background ("Always") authorization. |

#### `openSettings()`

```ts
openSettings(): void;
```

Open the host app's system settings page. Use this when a permission is `blocked` (`canAskAgain === false` — the user can no longer be prompted). No-op on web.

### Events

Each subscription function returns a `Subscription`; call `.remove()` to unsubscribe. Internally one native callback is registered per event and **ref-counted** — fanned out to every JS subscriber, and torn down when the last subscriber removes. On web they return a no-op subscription.

#### `onLocation(listener)`

```ts
onLocation(listener: (fix: LocationFix) => void): Subscription;
```

Subscribe to location fixes. Fires while the app is foregrounded, backgrounded, and — on Android — after the app is swiped away (delivered the moment the JS runtime is re-attached, in addition to being buffered/synced natively the whole time).

#### `onMotionChange(listener)`

```ts
onMotionChange(listener: (event: MotionChangeEvent) => void): Subscription;
```

Subscribe to moving ⇄ stationary transitions from the motion gate. See [`MotionChangeEvent`](#event-payloads).

#### `onProviderChange(listener)`

```ts
onProviderChange(listener: (event: ProviderChangeEvent) => void): Subscription;
```

Subscribe to location-provider / authorization changes (e.g. the user toggles GPS off). See [`ProviderChangeEvent`](#event-payloads).

#### `onSync(listener)`

```ts
onSync(listener: (event: SyncResult) => void): Subscription;
```

Subscribe to buffer-sync results — fires each time the native layer flushes the buffer to `syncUrl`. See [`SyncResult`](#syncresult).

#### `onError(listener)`

```ts
onError(listener: (event: LocationErrorEvent) => void): Subscription;
```

Subscribe to recoverable and fatal tracker errors. See [`LocationErrorEvent`](#event-payloads).

### Types

The canonical definitions live in [`src/types.ts`](./src/types.ts). The key shapes:

#### Enumerations

```ts
type LocationAccuracy = 'lowest' | 'low' | 'balanced' | 'high' | 'highest';

type LocationAuthorizationStatus =
  | 'granted'      // background tracking permitted ("Always" / ACCESS_BACKGROUND_LOCATION)
  | 'whenInUse'    // only-while-using; background limited
  | 'denied'
  | 'undetermined'
  | 'restricted'
  | 'blocked';     // cannot prompt again — needs a Settings trip

type MotionActivityType =
  | 'still' | 'walking' | 'running' | 'on_foot' | 'on_bicycle' | 'in_vehicle' | 'unknown';

type IOSActivityType =
  | 'other' | 'automotiveNavigation' | 'fitness' | 'otherNavigation' | 'airborne';
```

`LocationAccuracy` maps to platform priorities — `high` → Android `PRIORITY_HIGH_ACCURACY` / iOS `kCLLocationAccuracyNearestTenMeters`; `highest` → `PRIORITY_HIGH_ACCURACY` / `kCLLocationAccuracyBest`; `balanced` → `PRIORITY_BALANCED_POWER` / `kCLLocationAccuracyHundredMeters`; `low` → `PRIORITY_LOW_POWER` / `kCLLocationAccuracyKilometer`; `lowest` → `PRIORITY_PASSIVE` / `kCLLocationAccuracyThreeKilometers`.

#### `LocationFix`

A single location sample. Every optional numeric field is `null` when the platform did not report it; `latitude`, `longitude`, and `timestamp` are always present.

| Key                | Type                  | Notes                                                            |
| ------------------ | --------------------- | --------------------------------------------------------------- |
| `id`               | `string \| null`      | Stable SQLite row id; `null` for live, not-yet-persisted fixes. |
| `latitude`         | `number`              | Decimal degrees (WGS-84).                                       |
| `longitude`        | `number`              | Decimal degrees (WGS-84).                                       |
| `accuracy`         | `number \| null`      | Horizontal accuracy radius in metres (68% confidence).         |
| `altitude`         | `number \| null`      | Metres above the WGS-84 ellipsoid.                             |
| `altitudeAccuracy` | `number \| null`      | Vertical accuracy in metres.                                   |
| `speed`            | `number \| null`      | Ground speed in m/s.                                           |
| `speedAccuracy`    | `number \| null`      | Speed accuracy in m/s.                                         |
| `heading`          | `number \| null`      | Course in degrees (0–360, clockwise from true north).          |
| `headingAccuracy`  | `number \| null`      | Heading accuracy in degrees.                                   |
| `timestamp`        | `number`              | Unix **epoch milliseconds** (UTC) when the fix was acquired.   |
| `isMoving`         | `boolean`             | Whether the motion gate considers the device moving.          |
| `activity`         | `MotionActivityType`  | Best-effort motion classification.                            |
| `batteryLevel`     | `number \| null`      | `[0, 1]`, or `null` when unavailable.                         |
| `isCharging`       | `boolean \| null`     | `null` when unavailable.                                      |
| `mocked`           | `boolean`             | `true` when from a mock / test provider.                      |
| `provider`         | `string \| null`      | e.g. `'fused'`, `'gps'`, `'network'`, `'slc'`.                |

#### `StartOptions`

Every field has a sensible default (the single source of truth for defaults is `normalizeStartOptions` in [`src/index.ts`](./src/index.ts)).

| Option                             | Type                       | Default        | Notes                                                                                   |
| ---------------------------------- | -------------------------- | -------------- | --------------------------------------------------------------------------------------- |
| `accuracy`                         | `LocationAccuracy`         | `'high'`       | Accuracy / power trade-off.                                                              |
| `distanceFilter`                   | `number`                   | `10`           | Min movement in metres between delivered fixes. `0` delivers every fix.                  |
| `interval`                         | `number`                   | `5000`         | Desired update interval in ms (Android).                                                 |
| `fastestInterval`                  | `number`                   | `interval / 2` | Fastest interval the app can handle, in ms (Android).                                    |
| `activityType`                     | `IOSActivityType`          | `'other'`      | iOS `CLActivityType` hint.                                                               |
| `showsBackgroundLocationIndicator` | `boolean`                  | `true`         | Show the blue background-location bar (iOS 11+).                                         |
| `pausesUpdatesAutomatically`       | `boolean`                  | `false`        | Let Core Location auto-pause when stationary (iOS).                                      |
| `stopOnTerminate`                  | `boolean`                  | `false`        | If `true`, tracking ends on termination and does **not** restart.                       |
| `restartOnBoot`                    | `boolean`                  | `true`         | Re-arm tracking after reboot via the boot receiver (Android).                           |
| `useSignificantChanges`            | `boolean`                  | `true`         | SLC monitoring so iOS can relaunch after force-quit. Disabling = iOS stops permanently on force-quit. |
| `debug`                            | `boolean`                  | `false`        | Emit verbose native logs (`adb logcat` / Xcode console).                                 |
| `foregroundService`                | `ForegroundServiceOptions` | see below      | Android notification (iOS ignores this block).                                           |
| `buffer`                           | `BufferOptions`            | see below      | Offline persistence + HTTP sync.                                                         |
| `motion`                           | `MotionOptions`            | see below      | Activity-recognition gating.                                                             |

**`foregroundService`** (Android only — the persistent notification is what keeps the process alive after swipe-kill):

| Option                    | Type      | Default                                                |
| ------------------------- | --------- | ------------------------------------------------------ |
| `notificationTitle`       | `string`  | `'Location tracking active'`                           |
| `notificationBody`        | `string`  | `'Your location is being tracked in the background.'`  |
| `notificationChannelId`   | `string`  | `'persistent_background_location'`                     |
| `notificationChannelName` | `string`  | `'Background location'`                                |
| `notificationColor`       | `string`  | `#RRGGBB` or `#AARRGGBB` accent colour. Default: none. |
| `notificationIcon`        | `string`  | Small-icon drawable resource name. Default: app icon.  |
| `tapToOpenApp`            | `boolean` | `true` — tapping the notification re-opens the app.    |

**`buffer`** (when `persist` is on, every fix is written to SQLite *before* JS delivery; when `syncUrl` is set, the native layer batches to your backend with no JS required):

| Option                | Type                    | Default                                | Range         | Notes                                                                              |
| --------------------- | ----------------------- | -------------------------------------- | ------------- | ---------------------------------------------------------------------------------- |
| `persist`             | `boolean`               | `true` if `syncUrl` set, else `false`  | —             | Persist fixes to the SQLite buffer.                                                |
| `syncUrl`             | `string`                | none                                   | —             | **HTTPS** endpoint that receives batched fixes as a JSON array.                    |
| `allowInsecureSync`   | `boolean`               | `false`                                | —             | Permit a cleartext `syncUrl`. **Dev only** — `start()` throws on plain HTTP otherwise (location is sensitive PII). |
| `httpMethod`          | `'POST' \| 'PUT'`       | `'POST'`                               | —             | HTTP method for sync requests.                                                     |
| `headers`             | `Record<string,string>` | `{}`                                   | —             | Extra HTTP headers, e.g. an `Authorization` token.                                 |
| `batchSize`           | `number`                | `50`                                   | `1–1000`      | Max fixes per sync request.                                                        |
| `autoSync`            | `boolean`               | `true` if `syncUrl` set                | —             | Automatically flush the buffer in the background.                                  |
| `maxRecordsToPersist` | `number`                | `10000`                                | `100–1000000` | Hard cap; oldest rows drop once exceeded so disk can't fill.                       |

**`motion`** (requires `ACTIVITY_RECOGNITION` on Android 10+ / a motion usage description on iOS):

| Option                | Type      | Default | Range        | Notes                                            |
| --------------------- | --------- | ------- | ------------ | ------------------------------------------------ |
| `enabled`             | `boolean` | `false` | —            | Throttle location when stationary; resume on movement. |
| `stationaryTimeoutMs` | `number`  | `60000` | `0–86400000` | Continuous stillness before throttling kicks in. |

#### `TrackingStatus`

```ts
interface TrackingStatus {
  running: boolean;
  lastFix: LocationFix | null;
  bufferedCount: number;                    // fixes currently in the SQLite buffer
  authorization: LocationAuthorizationStatus;
  locationServicesEnabled: boolean;         // device GPS / network switched on
  isMoving: boolean;
  trackingSince: number | null;             // epoch ms the session began, or null
}
```

#### `PermissionResult`

```ts
interface PermissionResult {
  status: LocationAuthorizationStatus;       // combined — 'granted' only when background is permitted
  foreground: LocationAuthorizationStatus;
  background: LocationAuthorizationStatus;
  canAskAgain: boolean;                      // false → must use openSettings()
}
```

#### `SyncResult`

```ts
interface SyncResult {
  success: boolean;
  count: number;                             // fixes included in the batch
  status: number | null;                     // HTTP status, or null if skipped
  error: string | null;                      // message when success is false
}
```

#### Event payloads

```ts
interface MotionChangeEvent {
  isMoving: boolean;
  activity: MotionActivityType;
  fix: LocationFix | null;
}

interface ProviderChangeEvent {
  enabled: boolean;        // any provider enabled
  gpsEnabled: boolean;     // Android GPS provider (mirrors `enabled` on iOS)
  networkEnabled: boolean; // Android network provider (mirrors `enabled` on iOS)
  authorization: LocationAuthorizationStatus;
}

interface LocationErrorEvent {
  code: string;            // e.g. 'ERR_LOCATION_UNAVAILABLE'
  message: string;
  fatal: boolean;          // true when the tracker had to stop
}
```

### Errors

#### `LocationPermissionError`

Thrown by `start()` when tracking is started without the required authorization. Carries a `status: LocationAuthorizationStatus` field. `instanceof`-checkable.

```ts
import { start, LocationPermissionError } from 'react-native-persistent-background-location';

try {
  await start();
} catch (e) {
  if (e instanceof LocationPermissionError) {
    console.log('Need permission, status was', e.status);
  }
}
```

---

## How killed-app survival works

This is the heart of the package. The mechanisms are different on each OS, and each has honest limits.

### Android — surviving swipe-to-kill

Four layers, in order of how the process gets back:

1. **Foreground service keeps the process alive.** When you call `start()`, the module starts a `location`-typed foreground service with the persistent notification you configured. A foreground service is the only sanctioned way to run continuous background work; while it's up, the OS keeps your process resident and GPS streaming with the screen off. Swiping the app away from Recents removes the *task* but the service (and thus tracking) keeps running.
2. **`START_STICKY` system restart.** If the system reclaims the process under memory pressure, `START_STICKY` tells Android to recreate the service when resources free up. On recreation there is **no JS** — so the service **reloads its `StartOptions` from disk** (they were persisted on the last `start()`) and resumes natively. Fixes continue to flow into the SQLite buffer and sync; `onLocation` re-delivers once the JS runtime re-attaches.
3. **`onTaskRemoved` best-effort restart.** When the task is explicitly swiped away, the service's `onTaskRemoved` schedules a restart of itself as a defensive measure for OEMs that tear the service down with the task.
4. **`BOOT_COMPLETED` receiver.** When `restartOnBoot` is `true` (default), a boot receiver re-arms tracking after the device reboots, again reloading config from disk.

> **Bare-RN detail:** with no Expo/`Application` lifecycle to hook, the library captures the app `Context` from its own auto-merged `ContentProvider` (which Android instantiates before `Application.onCreate`), so the service, receiver, and SQLite buffer all work even when the process is recreated headless with no Activity. The two-step background permission prompt is driven through React Native's `PermissionAwareActivity`.

**The honest limit:** layers 2–4 are *best-effort*. **OEM battery killers** (MIUI/Xiaomi, EMUI/Huawei, Samsung, OnePlus/OPPO/Vivo) routinely kill foreground services and block autostart in ways the Android framework cannot override. v1 does **not** ship vendor-specific autostart hacks. Tell affected users to disable battery optimization for your app and consult [dontkillmyapp.com](https://dontkillmyapp.com/).

### iOS — resuming after force-quit

iOS gives you exactly **two** background-relaunch mechanisms for location, and only one survives a user force-quit:

- **While the app is merely backgrounded** (not force-quit): the `location` background mode + standard updates keep continuous GPS flowing.
- **After the user force-quits** (swipe-up in the app switcher): the system terminates your process and **will not** restart it for standard updates. The *only* thing that relaunches a force-quit app is **significant-location-change (SLC)** — and only when the device moves ~**500 m**. On that relaunch, iOS spins your app up in the background and posts `UIApplicationDidFinishLaunchingNotification`. This package's **`+load`-time launch observer** — registered automatically, **with no consumer AppDelegate edits** — catches that notification, re-creates the `CLLocationManager`, re-attaches the tracker, records the fix to the SQLite buffer, and (if configured) syncs.

So with `useSignificantChanges: true` (default), an iOS app that's been force-quit will **resume coarse (~500 m) tracking** on the next significant move — but it will **not** resume metre-level continuous GPS until the user reopens the app. **No library can change this; it is an Apple OS constraint.** If you set `useSignificantChanges: false`, iOS tracking stops permanently on force-quit.

---

## Platform support

| Platform     | Background while alive | After **force-quit / swipe-kill**                                                 | After **reboot**                       |
| ------------ | ---------------------- | --------------------------------------------------------------------------------- | -------------------------------------- |
| **Android 7+** (minSdk 24) | Full — foreground service keeps continuous GPS running with the screen off. | Best-effort restart via `START_STICKY` + `onTaskRemoved`. **OEM battery killers can defeat this** (see below). | Yes, via `BOOT_COMPLETED` receiver when `restartOnBoot` is set. |
| **iOS 15+** | Full — standard updates continue backgrounded with the `location` background mode. | **Only significant-location-change (~500 m of movement) can wake the app.** Continuous high-frequency GPS does **not** survive force-quit — this is an Apple OS limit, not a bug. | N/A — iOS relaunches via SLC on next significant movement, not on boot. |
| **Web**      | No-op stub.            | —                                                                                 | —                                      |
| **Old Architecture** | Not supported — Nitro requires the New Architecture. | — | — |

---

## Honest limitations — read before you ship

> **iOS cannot run continuous GPS after force-quit.** When a user force-quits your app (swipe up in the app switcher), iOS **will not** resume continuous GPS. The *only* mechanism Apple provides to relaunch a force-quit app for location is **significant-location-change**, which fires roughly every **500 metres** (cell-tower / Wi-Fi granularity), not on a timer and not at GPS precision. If your product needs metre-level continuous tracking through a force-quit on iOS, **no library can deliver that** — including the paid ones. Keep `useSignificantChanges: true` (the default) for the coarse SLC relaunch, and design your UX around it.

> **Android OEM battery killers are explicitly out of scope for v1.** Aggressive vendor power managers — **Xiaomi/MIUI, Huawei/EMUI, Samsung, OnePlus/OPPO/Vivo (BBK), and others** — will silently kill background processes (including foreground services) to save battery, regardless of what the framework promises. We rely on the documented Android framework behaviour (FGS, `START_STICKY`, boot receiver); we do **not** ship OEM-specific autostart hacks or ML-driven battery-optimization workarounds in v1. Direct affected users to [dontkillmyapp.com](https://dontkillmyapp.com/) and ask them to disable battery optimization for your app. This is a known, documented limitation.

> **No offline-sync database is shipped for you.** The package buffers fixes to its **own** native SQLite store and (optionally) HTTP-syncs them — but it is **not** a general-purpose offline database or a state-management solution. It does not expose the SQLite handle, does not do conflict resolution, and does not store your app's domain data. Read buffered fixes with `getBufferedLocations()` and persist them into *your* store; the buffer is a transient capture-before-delivery queue, not your source of truth.

> **New Architecture is required — the old bridge is unsupported.** This is a Nitro module; it only runs with Fabric / TurboModules enabled (React Native 0.79+). There is no legacy-bridge fallback and there will not be one.

---

## FAQ & troubleshooting

<details>
<summary><strong>Does this keep tracking after the app is force-quit on iOS?</strong></summary>

Not at GPS precision — and no library can. iOS only relaunches a force-quit app via **significant-location-change** (~500 m granularity). With `useSignificantChanges: true` (default) you get coarse resume on the next significant move; you do **not** get continuous metre-level GPS until the user reopens the app. This is an Apple OS limit. See [How killed-app survival works](#how-killed-app-survival-works).
</details>

<details>
<summary><strong>Do I need to edit my AppDelegate on iOS?</strong></summary>

No. The package self-registers a `+load`-time observer of `UIApplicationDidFinishLaunchingNotification`, so the SLC relaunch path re-attaches the tracker with **zero** changes to your `AppDelegate.swift` / `AppDelegate.mm`. Just `pod install` and add the `Info.plist` keys.
</details>

<details>
<summary><strong>My Android tracking dies when the screen is off / after a few minutes on a Xiaomi/Huawei/Samsung phone.</strong></summary>

That's an **OEM battery killer**, not a bug in this package. Aggressive vendor power managers kill foreground services and block autostart regardless of the Android framework. v1 deliberately does not ship vendor hacks. Ask the user to: disable battery optimization for your app, enable "Autostart" (MIUI/EMUI), and lock the app in Recents. See [dontkillmyapp.com](https://dontkillmyapp.com/) for per-vendor steps.
</details>

<details>
<summary><strong>Android 14/15 build fails with a foreground-service-type error.</strong></summary>

Android 14 (API 34) requires a declared `foregroundServiceType` and the `FOREGROUND_SERVICE_LOCATION` permission for location services; Android 15 (API 35) tightens timing rules. The library service already declares the `location` type — you just need `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, and `POST_NOTIFICATIONS` in your manifest (see [Manifest & Info.plist](#manifest--infoplist)), or the bundled config plugin if you prebuild. The user must have granted location permission *before* the location-typed service starts.
</details>

<details>
<summary><strong>I asked for permission but only got <code>whenInUse</code> — background isn't working.</strong></summary>

Background ("Always") location is a **two-step** grant on Android 11+ and iOS. The OS grants foreground first, then requires a **separate trip to Settings** to upgrade to "Allow all the time". `requestPermissions({ background: true })` drives this escalation automatically (through `PermissionAwareActivity` on Android), but the user must actually choose "Allow all the time". Check `result.background` and `result.status` — `status` is only `granted` when background is permitted. If `canAskAgain` is `false`, send the user to `openSettings()`.
</details>

<details>
<summary><strong><code>start()</code> throws "buffer.syncUrl must be HTTPS".</strong></summary>

Location is sensitive PII, so a cleartext `http://` `syncUrl` is rejected by default. For local development against a dev server, set `buffer.allowInsecureSync: true` to override — but never ship that. Use HTTPS in production.
</details>

<details>
<summary><strong>The buffer keeps growing and never syncs.</strong></summary>

Sync only runs when `buffer.syncUrl` is set. Without it, fixes persist to SQLite (if `persist` is on) but are never sent — read them yourself with `getBufferedLocations()`. With a `syncUrl`, check `onSync` / `onError` and the `SyncResult.status`/`error` fields. The buffer is capped at `maxRecordsToPersist` (default 10,000); oldest rows drop past the cap so the disk can't fill.
</details>

<details>
<summary><strong>"Cannot create HybridObject" / native module not found.</strong></summary>

This is a Nitro module: you must have the **New Architecture enabled** and **`react-native-nitro-modules` installed**, then `pod install` (iOS) and a clean rebuild. It does not run on the old bridge, and it does not run in Expo Go — use a dev build.
</details>

<details>
<summary><strong>Nothing happens on web.</strong></summary>

By design — web is a typed no-op. Methods that can't return a value (like `start` / `getCurrentPosition`) throw; the rest return sane defaults so cross-platform builds don't break.
</details>

---

## Relation to `expo-persistent-background-location`

This package and **[`expo-persistent-background-location`](https://github.com/aashir-athar/expo-persistent-background-location)** are **twins**: identical public API, identical feature set, identical native survival strategy — they differ only in the binding layer.

| | **react-native-persistent-background-location** (this) | **expo-persistent-background-location** |
|---|---|---|
| Binding | **Nitro** (`react-native-nitro-modules`) | `expo-modules-core` |
| Target | **Bare React Native** (no Expo dependency) | Expo SDK 56 (managed / dev build) |
| Public API | Identical | Identical |
| iOS relaunch | `+load` launch observer, **no AppDelegate edits** | bundled `AppDelegate` subscriber |
| Install | `npm install … react-native-nitro-modules` + `pod install` | `npx expo install …` + config plugin |

**Pick this one** if you're on bare React Native, want a Nitro module, or don't want an Expo dependency. **Pick the Expo one** if you're in a managed Expo app. The code you write on top is the same — migrating between them is just a package swap and an install step.

---

## Contributing

PRs and issues welcome — especially:

- **OEM-killer survival** — vendor-specific autostart strategies are out of scope for v1; well-tested contributions are very welcome.
- **Real-device test reports** — which phones/OEMs survive swipe-kill and reboot, and which don't.
- **iOS region/visit monitoring** improvements around the SLC relaunch path.
- **Docs** — clearer permission-onboarding recipes.

```sh
git clone https://github.com/aashir-athar/react-native-persistent-background-location
cd react-native-persistent-background-location
npm install
npm run codegen     # regenerate the Nitro native specs (commit the output)
npm run build       # bob (JS) + tsc (config plugin)
npm run typecheck
```

The native ↔ JS contract is generated by Nitro from `src/specs/*.nitro.ts` — keep the Swift/Kotlin impls in sync with the specs. See [ZERO-TO-DEPLOY.md](./ZERO-TO-DEPLOY.md) for the full maintainer runbook (dev loop, on-device kill tests, publishing).

---

## License

MIT © aashir-athar — see [LICENSE](./LICENSE).
