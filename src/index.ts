/**
 * `react-native-persistent-background-location` — public API.
 *
 * A thin, fully-typed wrapper over the single Nitro HybridObject. It normalizes
 * friendly {@link StartOptions} into the flat native config, ref-counts event
 * subscribers behind a single native callback per event, and converts native
 * optionals into the `null`-shaped public types.
 *
 * @packageDocumentation
 */

import { Platform } from 'react-native';
import { NitroModules } from 'react-native-nitro-modules';

import type { PersistentBackgroundLocation as NativeModule } from './specs/PersistentBackgroundLocation.nitro';
import type {
  NativeLocationFix,
  NativePermissionResult,
  NativeStartConfig,
  NativeSyncResult,
  NativeTrackingStatus,
} from './specs/LocationTypes.nitro';
import {
  LocationPermissionError,
  type CurrentPositionOptions,
  type LocationErrorEvent,
  type LocationFix,
  type MotionActivityType,
  type MotionChangeEvent,
  type PermissionResult,
  type ProviderChangeEvent,
  type RequestPermissionsOptions,
  type StartOptions,
  type SyncResult,
  type TrackingStatus,
} from './types';

export * from './types';

/** Returned by every `on*` subscription. Call `.remove()` to unsubscribe. */
export interface Subscription {
  remove(): void;
}

const isWeb = Platform.OS === 'web';

let nativeModule: NativeModule | null = null;

function native(): NativeModule {
  if (nativeModule == null) {
    nativeModule = NitroModules.createHybridObject<NativeModule>('PersistentBackgroundLocation');
  }
  return nativeModule;
}

// ---------------------------------------------------------------------------
// Conversions (native optionals -> public `null`)
// ---------------------------------------------------------------------------

function toFix(n: NativeLocationFix): LocationFix {
  return {
    id: n.id ?? null,
    latitude: n.latitude,
    longitude: n.longitude,
    accuracy: n.accuracy ?? null,
    altitude: n.altitude ?? null,
    altitudeAccuracy: n.altitudeAccuracy ?? null,
    speed: n.speed ?? null,
    speedAccuracy: n.speedAccuracy ?? null,
    heading: n.heading ?? null,
    headingAccuracy: n.headingAccuracy ?? null,
    timestamp: n.timestamp,
    isMoving: n.isMoving,
    activity: n.activity as MotionActivityType,
    batteryLevel: n.batteryLevel ?? null,
    isCharging: n.isCharging ?? null,
    mocked: n.mocked,
    provider: n.provider ?? null,
  };
}

function toStatus(n: NativeTrackingStatus): TrackingStatus {
  return {
    running: n.running,
    lastFix: n.lastFix ? toFix(n.lastFix) : null,
    bufferedCount: n.bufferedCount,
    authorization: n.authorization as TrackingStatus['authorization'],
    locationServicesEnabled: n.locationServicesEnabled,
    isMoving: n.isMoving,
    trackingSince: n.trackingSince ?? null,
  };
}

function toPermission(n: NativePermissionResult): PermissionResult {
  return {
    status: n.status as PermissionResult['status'],
    foreground: n.foreground as PermissionResult['foreground'],
    background: n.background as PermissionResult['background'],
    canAskAgain: n.canAskAgain,
  };
}

function toSync(n: NativeSyncResult): SyncResult {
  return { success: n.success, count: n.count, status: n.status ?? null, error: n.error ?? null };
}

const DENIED: PermissionResult = {
  status: 'denied',
  foreground: 'denied',
  background: 'denied',
  canAskAgain: false,
};

// ---------------------------------------------------------------------------
// Normalization (single source of every default)
// ---------------------------------------------------------------------------

function clamp(value: number, min: number, max: number): number {
  if (Number.isNaN(value)) return min;
  return Math.min(Math.max(value, min), max);
}

function sanitizeHeaders(headers: Record<string, string> | undefined): Record<string, string> {
  if (!headers) return {};
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(headers)) {
    if (typeof value === 'string') out[key] = value;
  }
  return out;
}

/** Normalize {@link StartOptions} into the flat {@link NativeStartConfig}. */
export function normalizeStartOptions(options: StartOptions = {}): NativeStartConfig {
  const fs = options.foregroundService ?? {};
  const buffer = options.buffer ?? {};
  const motion = options.motion ?? {};

  const syncUrl = typeof buffer.syncUrl === 'string' ? buffer.syncUrl.trim() : '';
  const hasSyncUrl = syncUrl.length > 0;

  if (hasSyncUrl && !buffer.allowInsecureSync && !/^https:\/\//i.test(syncUrl)) {
    throw new Error(
      `react-native-persistent-background-location: buffer.syncUrl must be HTTPS (got "${syncUrl}"). ` +
        `Set buffer.allowInsecureSync: true to override for local development.`
    );
  }

  const interval = clamp(options.interval ?? 5000, 0, 86_400_000);

  return {
    accuracy: options.accuracy ?? 'high',
    distanceFilter: clamp(options.distanceFilter ?? 10, 0, 1_000_000),
    interval,
    fastestInterval: clamp(options.fastestInterval ?? Math.floor(interval / 2), 0, 86_400_000),
    activityType: options.activityType ?? 'other',
    showsBackgroundLocationIndicator: options.showsBackgroundLocationIndicator ?? true,
    pausesUpdatesAutomatically: options.pausesUpdatesAutomatically ?? false,
    stopOnTerminate: options.stopOnTerminate ?? false,
    restartOnBoot: options.restartOnBoot ?? true,
    useSignificantChanges: options.useSignificantChanges ?? true,
    debug: options.debug ?? false,
    notificationTitle: fs.notificationTitle ?? 'Location tracking active',
    notificationBody: fs.notificationBody ?? 'Your location is being tracked in the background.',
    notificationChannelId: fs.notificationChannelId ?? 'persistent_background_location',
    notificationChannelName: fs.notificationChannelName ?? 'Background location',
    notificationColor: fs.notificationColor,
    notificationIcon: fs.notificationIcon,
    tapToOpenApp: fs.tapToOpenApp ?? true,
    persist: buffer.persist ?? hasSyncUrl,
    syncUrl: hasSyncUrl ? syncUrl : undefined,
    httpMethod: buffer.httpMethod ?? 'POST',
    headers: sanitizeHeaders(buffer.headers),
    batchSize: clamp(buffer.batchSize ?? 50, 1, 1000),
    autoSync: buffer.autoSync ?? hasSyncUrl,
    maxRecordsToPersist: clamp(buffer.maxRecordsToPersist ?? 10_000, 100, 1_000_000),
    motionEnabled: motion.enabled ?? false,
    stationaryTimeoutMs: clamp(motion.stationaryTimeoutMs ?? 60_000, 0, 86_400_000),
  };
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

/**
 * Start continuous background tracking. Idempotent — calling again reconfigures.
 * @throws {@link LocationPermissionError} when authorization is missing.
 */
export async function start(options: StartOptions = {}): Promise<void> {
  if (isWeb) throw new Error('react-native-persistent-background-location is not supported on web.');
  const config = normalizeStartOptions(options);
  try {
    await native().start(config);
  } catch (error) {
    const message = (error as { message?: string })?.message ?? '';
    if (message.includes('ERR_PERMISSION_DENIED')) {
      const { status } = await getPermissionStatus();
      throw new LocationPermissionError(status);
    }
    throw error;
  }
}

/** Stop tracking, tear down the foreground service, and disarm boot restart. */
export async function stop(): Promise<void> {
  if (isWeb) return;
  await native().stop();
}

/** Whether the native tracker is currently running. */
export function isRunning(): boolean {
  if (isWeb) return false;
  return native().isRunning();
}

/** Snapshot of the current tracker state, authorization, and buffer size. */
export async function getStatus(): Promise<TrackingStatus> {
  if (isWeb) {
    return {
      running: false,
      lastFix: null,
      bufferedCount: 0,
      authorization: 'denied',
      locationServicesEnabled: false,
      isMoving: false,
      trackingSince: null,
    };
  }
  return toStatus(await native().getStatus());
}

/** Resolve a single fresh fix without starting continuous tracking. */
export async function getCurrentPosition(
  options: CurrentPositionOptions = {}
): Promise<LocationFix> {
  if (isWeb) throw new Error('react-native-persistent-background-location is not supported on web.');
  const fix = await native().getCurrentPosition({
    accuracy: options.accuracy ?? 'high',
    timeoutMs: clamp(options.timeoutMs ?? 15_000, 1_000, 300_000),
    maximumAgeMs: clamp(options.maximumAgeMs ?? 0, 0, 86_400_000),
  });
  return toFix(fix);
}

// ---------------------------------------------------------------------------
// Buffer & sync
// ---------------------------------------------------------------------------

/** Read buffered fixes (newest first). Pass `0` for all rows. */
export async function getBufferedLocations(limit = 0): Promise<LocationFix[]> {
  if (isWeb) return [];
  const fixes = await native().getBufferedLocations(clamp(limit, 0, 1_000_000));
  return fixes.map(toFix);
}

/** Delete every buffered fix. Resolves with the number removed. */
export async function clearBuffer(): Promise<number> {
  if (isWeb) return 0;
  return native().clearBuffer();
}

/** Force an immediate sync of the buffer to the configured `syncUrl`. */
export async function flush(): Promise<SyncResult> {
  if (isWeb) return { success: false, count: 0, status: null, error: 'unsupported-platform' };
  return toSync(await native().flush());
}

// ---------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------

/** Resolve the current foreground + background location authorization. */
export async function getPermissionStatus(): Promise<PermissionResult> {
  if (isWeb) return DENIED;
  return toPermission(await native().getPermissionStatus());
}

/** Prompt for location authorization (background "Always" by default). */
export async function requestPermissions(
  options: RequestPermissionsOptions = {}
): Promise<PermissionResult> {
  if (isWeb) return DENIED;
  return toPermission(await native().requestPermissions(options.background ?? true));
}

/** Open the host app's system settings page — use when permission is `blocked`. */
export function openSettings(): void {
  if (isWeb) return;
  native().openSettings();
}

// ---------------------------------------------------------------------------
// Events (ref-counted: one native callback per event, fanned out to subscribers)
// ---------------------------------------------------------------------------

function makeEvent<T>(
  setNative: (module: NativeModule, callback: ((value: T) => void) | undefined) => void
) {
  const listeners = new Set<(value: T) => void>();
  return (listener: (value: T) => void): Subscription => {
    if (isWeb) return { remove: () => undefined };
    listeners.add(listener);
    if (listeners.size === 1) {
      setNative(native(), (value) => {
        for (const l of listeners) {
          try {
            l(value);
          } catch {
            // a throwing subscriber must not break delivery to the others
          }
        }
      });
    }
    return {
      remove: () => {
        listeners.delete(listener);
        if (listeners.size === 0) setNative(native(), undefined);
      },
    };
  };
}

const locationEvent = makeEvent<NativeLocationFix>((m, cb) =>
  m.setOnLocation(cb ? (fix) => cb(fix) : undefined)
);
const motionEvent = makeEvent<MotionChangeEvent>((m, cb) =>
  m.setOnMotionChange(
    cb
      ? (e) => cb({ isMoving: e.isMoving, activity: e.activity as MotionActivityType, fix: e.fix ? toFix(e.fix) : null })
      : undefined
  )
);
const providerEvent = makeEvent<ProviderChangeEvent>((m, cb) =>
  m.setOnProviderChange(
    cb
      ? (e) => cb({ enabled: e.enabled, gpsEnabled: e.gpsEnabled, networkEnabled: e.networkEnabled, authorization: e.authorization as ProviderChangeEvent['authorization'] })
      : undefined
  )
);
const syncEvent = makeEvent<SyncResult>((m, cb) => m.setOnSync(cb ? (e) => cb(toSync(e)) : undefined));
const errorEvent = makeEvent<LocationErrorEvent>((m, cb) =>
  m.setOnError(cb ? (e) => cb({ code: e.code, message: e.message, fatal: e.fatal }) : undefined)
);

/** Subscribe to location fixes (fires while backgrounded and after Android swipe-kill resume). */
export function onLocation(listener: (fix: LocationFix) => void): Subscription {
  return locationEvent((fix) => listener(toFix(fix)));
}
/** Subscribe to moving ⇄ stationary transitions. */
export function onMotionChange(listener: (event: MotionChangeEvent) => void): Subscription {
  return motionEvent(listener);
}
/** Subscribe to location-provider / authorization changes. */
export function onProviderChange(listener: (event: ProviderChangeEvent) => void): Subscription {
  return providerEvent(listener);
}
/** Subscribe to buffer-sync results. */
export function onSync(listener: (event: SyncResult) => void): Subscription {
  return syncEvent(listener);
}
/** Subscribe to recoverable and fatal tracker errors. */
export function onError(listener: (event: LocationErrorEvent) => void): Subscription {
  return errorEvent(listener);
}
