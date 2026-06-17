/**
 * Public type definitions for `react-native-persistent-background-location`.
 *
 * These are what consumers import. The wrapper in `src/index.ts` normalizes them
 * into the flat Nitro structs in `src/specs/LocationTypes.nitro.ts`.
 */

// ---------------------------------------------------------------------------
// Enumerations
// ---------------------------------------------------------------------------

/**
 * Desired positioning accuracy.
 *
 * | Value      | Android (Priority)               | iOS (CLLocationAccuracy)              |
 * | ---------- | -------------------------------- | ------------------------------------- |
 * | `lowest`   | `PRIORITY_PASSIVE`               | `kCLLocationAccuracyThreeKilometers`  |
 * | `low`      | `PRIORITY_LOW_POWER`             | `kCLLocationAccuracyKilometer`        |
 * | `balanced` | `PRIORITY_BALANCED_POWER`        | `kCLLocationAccuracyHundredMeters`    |
 * | `high`     | `PRIORITY_HIGH_ACCURACY`         | `kCLLocationAccuracyNearestTenMeters` |
 * | `highest`  | `PRIORITY_HIGH_ACCURACY`         | `kCLLocationAccuracyBest`             |
 */
export type LocationAccuracy = 'lowest' | 'low' | 'balanced' | 'high' | 'highest';

/**
 * Resolved authorization state. `granted` means the app may track in the
 * background ("Always" on iOS / `ACCESS_BACKGROUND_LOCATION` on Android 10+).
 */
export type LocationAuthorizationStatus =
  | 'granted'
  | 'whenInUse'
  | 'denied'
  | 'undetermined'
  | 'restricted'
  | 'blocked';

/**
 * Coarse motion classification. Always `unknown` when `motion.enabled` is off.
 * `on_foot` / `on_bicycle` are Android-only (from `ActivityRecognition`); iOS's
 * speed heuristic coalesces those into `walking` / `running` / `in_vehicle`.
 */
export type MotionActivityType =
  | 'still'
  | 'walking'
  | 'running'
  | 'on_foot'
  | 'on_bicycle'
  | 'in_vehicle'
  | 'unknown';

/** iOS `CLActivityType` hint — lets Core Location tune power/pausing behaviour. */
export type IOSActivityType =
  | 'other'
  | 'automotiveNavigation'
  | 'fitness'
  | 'otherNavigation'
  | 'airborne';

// ---------------------------------------------------------------------------
// Location fix
// ---------------------------------------------------------------------------

/** A single location sample. Optional numeric fields are `null` when unreported. */
export interface LocationFix {
  /** Stable row id assigned when persisted to the buffer; `null` for live fixes. */
  id: string | null;
  latitude: number;
  longitude: number;
  /** Estimated horizontal accuracy radius in metres. `null` if unknown. */
  accuracy: number | null;
  altitude: number | null;
  altitudeAccuracy: number | null;
  /** Ground speed in m/s. `null` if unavailable. */
  speed: number | null;
  speedAccuracy: number | null;
  /** Heading in degrees (0–360). `null` if unavailable. */
  heading: number | null;
  headingAccuracy: number | null;
  /** Unix epoch milliseconds (UTC). */
  timestamp: number;
  isMoving: boolean;
  activity: MotionActivityType;
  /** Battery level in `[0, 1]`, or `null`. */
  batteryLevel: number | null;
  isCharging: boolean | null;
  /** `true` when from a mock provider. */
  mocked: boolean;
  /** `'fused'` / `'gps'` / `'network'` / `'slc'` / …, or `null`. */
  provider: string | null;
}

// ---------------------------------------------------------------------------
// Start options
// ---------------------------------------------------------------------------

/** Android foreground-service notification configuration (iOS ignores it). */
export interface ForegroundServiceOptions {
  /** Defaults to `"Location tracking active"`. */
  notificationTitle?: string;
  /** Defaults to `"Your location is being tracked in the background."`. */
  notificationBody?: string;
  /** Defaults to `"persistent_background_location"`. */
  notificationChannelId?: string;
  /** Defaults to `"Background location"`. */
  notificationChannelName?: string;
  /** `#RRGGBB` / `#AARRGGBB`. */
  notificationColor?: string;
  /** Drawable/mipmap resource name (no extension). Defaults to the app icon. */
  notificationIcon?: string;
  /** Tapping the notification re-opens the app. Defaults to `true`. */
  tapToOpenApp?: boolean;
}

/** Offline persistence + HTTP sync configuration. */
export interface BufferOptions {
  /** Persist fixes to the native SQLite buffer. Defaults to `true` when `syncUrl` is set. */
  persist?: boolean;
  /** HTTPS endpoint that receives batched fixes as a JSON array. Enables native auto-sync. */
  syncUrl?: string;
  /**
   * Allow a non-HTTPS `syncUrl`. Defaults to `false` — `start()` throws on a
   * cleartext URL otherwise (location is sensitive PII). Development only.
   */
  allowInsecureSync?: boolean;
  /** Defaults to `'POST'`. */
  httpMethod?: 'POST' | 'PUT';
  /** Extra HTTP headers, e.g. an `Authorization` token. */
  headers?: Record<string, string>;
  /** Max fixes per sync request. Defaults to `50`. */
  batchSize?: number;
  /** Auto-flush the buffer in the background. Defaults to `true` when `syncUrl` is set. */
  autoSync?: boolean;
  /** Hard cap on persisted rows (oldest dropped). Defaults to `10000`. */
  maxRecordsToPersist?: number;
}

/** Motion-detection (activity-recognition) gating options. */
export interface MotionOptions {
  /** Enable activity-recognition gating. Defaults to `false`. */
  enabled?: boolean;
  /** Ms of continuous stillness before throttling. Defaults to `60000`. */
  stationaryTimeoutMs?: number;
}

/** Options accepted by {@link start}. Every field has a sensible default. */
export interface StartOptions {
  /** Defaults to `'high'`. */
  accuracy?: LocationAccuracy;
  /** Min movement in metres between fixes. Defaults to `10`; `0` delivers every fix. */
  distanceFilter?: number;
  /** Desired update interval in ms (Android). Defaults to `5000`. */
  interval?: number;
  /** Fastest interval in ms (Android). Defaults to `interval / 2`. */
  fastestInterval?: number;
  /** iOS `CLActivityType` hint. Defaults to `'other'`. */
  activityType?: IOSActivityType;
  /** Show the blue background-location indicator (iOS 11+). Defaults to `true`. */
  showsBackgroundLocationIndicator?: boolean;
  /** Let Core Location auto-pause when stationary (iOS). Defaults to `false`. */
  pausesUpdatesAutomatically?: boolean;
  /** End tracking on terminate (don't survive swipe-kill). Defaults to `false`. */
  stopOnTerminate?: boolean;
  /** Re-arm after reboot via a boot receiver (Android). Defaults to `true`. */
  restartOnBoot?: boolean;
  /** Use significant-location-change so iOS can relaunch after force-quit. Defaults to `true`. */
  useSignificantChanges?: boolean;
  foregroundService?: ForegroundServiceOptions;
  buffer?: BufferOptions;
  motion?: MotionOptions;
  /** Verbose native logs. Defaults to `false`. */
  debug?: boolean;
}

/** Options for {@link getCurrentPosition}. */
export interface CurrentPositionOptions {
  accuracy?: LocationAccuracy;
  /** Reject after this many ms. Defaults to `15000`. */
  timeoutMs?: number;
  /** Accept a cached fix no older than this many ms. Defaults to `0`. */
  maximumAgeMs?: number;
}

/** Options for {@link requestPermissions}. */
export interface RequestPermissionsOptions {
  /** Also request background ("Always"). Defaults to `true`. */
  background?: boolean;
}

// ---------------------------------------------------------------------------
// Status & results
// ---------------------------------------------------------------------------

/** Snapshot of the tracker state, returned by {@link getStatus}. */
export interface TrackingStatus {
  running: boolean;
  lastFix: LocationFix | null;
  bufferedCount: number;
  authorization: LocationAuthorizationStatus;
  locationServicesEnabled: boolean;
  isMoving: boolean;
  trackingSince: number | null;
}

/** Detailed permission result. */
export interface PermissionResult {
  status: LocationAuthorizationStatus;
  foreground: LocationAuthorizationStatus;
  background: LocationAuthorizationStatus;
  /**
   * Whether the OS will still show a prompt (vs. a Settings trip). The portable
   * "show an Open Settings CTA?" signal is `canAskAgain === false` (Android
   * surfaces the terminal state as `status: 'blocked'`, iOS as `'denied'`).
   */
  canAskAgain: boolean;
}

/** Result of a sync attempt. */
export interface SyncResult {
  success: boolean;
  count: number;
  status: number | null;
  error: string | null;
}

// ---------------------------------------------------------------------------
// Event payloads
// ---------------------------------------------------------------------------

export interface ProviderChangeEvent {
  enabled: boolean;
  gpsEnabled: boolean;
  networkEnabled: boolean;
  authorization: LocationAuthorizationStatus;
}

export interface MotionChangeEvent {
  isMoving: boolean;
  activity: MotionActivityType;
  fix: LocationFix | null;
}

export interface LocationErrorEvent {
  code: string;
  message: string;
  fatal: boolean;
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/** Thrown when tracking is started without the required location authorization. */
export class LocationPermissionError extends Error {
  readonly status: LocationAuthorizationStatus;
  constructor(status: LocationAuthorizationStatus, message?: string) {
    super(
      message ??
        `Location permission not granted (status: ${status}). ` +
          `Call requestPermissions() before start().`
    );
    this.name = 'LocationPermissionError';
    this.status = status;
  }
}
