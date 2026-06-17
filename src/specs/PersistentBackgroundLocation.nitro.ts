import type { HybridObject } from 'react-native-nitro-modules';

import type {
  NativeCurrentPositionOptions,
  NativeLocationErrorEvent,
  NativeLocationFix,
  NativeMotionChangeEvent,
  NativePermissionResult,
  NativeProviderChangeEvent,
  NativeStartConfig,
  NativeSyncResult,
  NativeTrackingStatus,
} from './LocationTypes.nitro';

/**
 * The single native HybridObject backing the package. Created once from JS via
 * `NitroModules.createHybridObject('PersistentBackgroundLocation')` and wrapped
 * by the ergonomic API in `src/index.ts`.
 *
 * Events use the single-callback pattern: native holds one callback per event;
 * the JS wrapper registers one native callback and fans it out to all
 * subscribers. A callback delivered while no JS is attached (app killed) is a
 * no-op — fixes are still persisted to the native SQLite buffer the whole time.
 */
export interface PersistentBackgroundLocation
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  /** Start (or reconfigure) the background tracker. */
  start(config: NativeStartConfig): Promise<void>;
  /** Stop the tracker, tear down the foreground service, and disarm boot restart. */
  stop(): Promise<void>;
  /** Whether the native tracker is currently running. */
  isRunning(): boolean;
  /** Snapshot of tracker state, authorization, and buffer size. */
  getStatus(): Promise<NativeTrackingStatus>;
  /** Resolve a single fresh fix without starting continuous tracking. */
  getCurrentPosition(options: NativeCurrentPositionOptions): Promise<NativeLocationFix>;
  /** Read up to `limit` buffered fixes (newest first). `0` = all. */
  getBufferedLocations(limit: number): Promise<NativeLocationFix[]>;
  /** Delete every buffered fix. Resolves with the number removed. */
  clearBuffer(): Promise<number>;
  /** Force an immediate sync of the buffer to `syncUrl`. */
  flush(): Promise<NativeSyncResult>;
  /** Prompt for location authorization. `background` also requests "Always". */
  requestPermissions(background: boolean): Promise<NativePermissionResult>;
  /** Current foreground + background location authorization. */
  getPermissionStatus(): Promise<NativePermissionResult>;
  /** Open the host app's system settings page. */
  openSettings(): void;

  /** Set (or clear, with `undefined`) the single native location callback. */
  setOnLocation(callback?: (fix: NativeLocationFix) => void): void;
  setOnMotionChange(callback?: (event: NativeMotionChangeEvent) => void): void;
  setOnProviderChange(callback?: (event: NativeProviderChangeEvent) => void): void;
  setOnSync(callback?: (event: NativeSyncResult) => void): void;
  setOnError(callback?: (event: NativeLocationErrorEvent) => void): void;
}
