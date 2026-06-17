/**
 * Nitro struct types for `react-native-persistent-background-location`.
 *
 * Flat, primitive-friendly shapes that cross the JS <-> native bridge. Nullable
 * fields use optional (`?`) so Nitrogen emits `std::optional` / Swift optionals /
 * Kotlin nullables. The ergonomic public types live in `src/types.ts`; the
 * wrapper in `src/index.ts` converts between the two.
 */

/** Fully-resolved, flat start config (defaults applied in JS by `normalizeStartOptions`). */
export interface NativeStartConfig {
  accuracy: string;
  distanceFilter: number;
  interval: number;
  fastestInterval: number;
  activityType: string;
  showsBackgroundLocationIndicator: boolean;
  pausesUpdatesAutomatically: boolean;
  stopOnTerminate: boolean;
  restartOnBoot: boolean;
  useSignificantChanges: boolean;
  debug: boolean;
  notificationTitle: string;
  notificationBody: string;
  notificationChannelId: string;
  notificationChannelName: string;
  notificationColor?: string;
  notificationIcon?: string;
  tapToOpenApp: boolean;
  persist: boolean;
  syncUrl?: string;
  httpMethod: string;
  headers: Record<string, string>;
  batchSize: number;
  autoSync: boolean;
  maxRecordsToPersist: number;
  motionEnabled: boolean;
  stationaryTimeoutMs: number;
}

/** A single location sample. `timestamp` is epoch ms. */
export interface NativeLocationFix {
  id?: string;
  latitude: number;
  longitude: number;
  accuracy?: number;
  altitude?: number;
  altitudeAccuracy?: number;
  speed?: number;
  speedAccuracy?: number;
  heading?: number;
  headingAccuracy?: number;
  timestamp: number;
  isMoving: boolean;
  activity: string;
  batteryLevel?: number;
  isCharging?: boolean;
  mocked: boolean;
  provider?: string;
}

export interface NativeTrackingStatus {
  running: boolean;
  lastFix?: NativeLocationFix;
  bufferedCount: number;
  authorization: string;
  locationServicesEnabled: boolean;
  isMoving: boolean;
  trackingSince?: number;
}

export interface NativePermissionResult {
  status: string;
  foreground: string;
  background: string;
  canAskAgain: boolean;
}

export interface NativeSyncResult {
  success: boolean;
  count: number;
  status?: number;
  error?: string;
}

export interface NativeMotionChangeEvent {
  isMoving: boolean;
  activity: string;
  fix?: NativeLocationFix;
}

export interface NativeProviderChangeEvent {
  enabled: boolean;
  gpsEnabled: boolean;
  networkEnabled: boolean;
  authorization: string;
}

export interface NativeLocationErrorEvent {
  code: string;
  message: string;
  fatal: boolean;
}

export interface NativeCurrentPositionOptions {
  accuracy: string;
  timeoutMs: number;
  maximumAgeMs: number;
}
