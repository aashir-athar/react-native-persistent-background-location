/**
 * Config plugin for `react-native-persistent-background-location`.
 *
 * Writes the platform glue a background-location module needs but that cannot
 * live in the autolinked library manifest alone:
 *
 *   - **iOS**: the `NSLocation*UsageDescription` strings (App Store rejects
 *     builds without them) and the `location` `UIBackgroundMode` (required for
 *     background updates). `fetch` / `processing` are opt-in via
 *     `enableBackgroundFetch` and off by default.
 *   - **Android**: ensures the location / foreground-service / boot permissions
 *     are present in the app manifest, and lets a consumer *opt out* of the
 *     background-location and activity-recognition asks (e.g. to ease Play Store
 *     review) via `tools:node="remove"`.
 *
 * This is the bare-React-Native port of the Expo module's plugin. It imports
 * from `@expo/config-plugins` (the standalone package) so it works in any RN app
 * that runs `expo prebuild` / Continuous Native Generation, not only managed
 * Expo apps.
 *
 * Usage in `app.json` / `app.config.ts`:
 *
 *   ["react-native-persistent-background-location", {
 *     "locationAlwaysAndWhenInUsePermission": "Track your run even when the screen is off.",
 *     "isAndroidBackgroundLocationEnabled": true,
 *     "isAndroidForegroundServiceEnabled": true,
 *     "isActivityRecognitionEnabled": true
 *   }]
 */

import {
  AndroidConfig,
  ConfigPlugin,
  createRunOncePlugin,
  withAndroidManifest,
  withInfoPlist,
} from "@expo/config-plugins";

const pkg = require("../../package.json") as { name: string; version: string };

export interface PluginOptions {
  /** iOS `NSLocationWhenInUseUsageDescription`. */
  locationWhenInUsePermission?: string;
  /** iOS `NSLocationAlwaysAndWhenInUseUsageDescription`. */
  locationAlwaysAndWhenInUsePermission?: string;
  /** iOS `NSLocationAlwaysUsageDescription` (legacy, still read by older OSes). */
  locationAlwaysPermission?: string;
  /**
   * iOS `NSMotionUsageDescription`. Set to `false` to omit it (only needed if
   * you enable `motion.enabled` at runtime). Defaults to a sensible string.
   */
  motionPermission?: string | false;
  /**
   * Add the iOS `fetch` / `processing` background modes. Defaults to `false` —
   * the module drives sync from the location stream + significant-location-change
   * wake-ups, not BGTaskScheduler, so only the `location` mode is required.
   * Enable this only if you wire up your own background tasks.
   */
  enableBackgroundFetch?: boolean;
  /** Inject `ACCESS_BACKGROUND_LOCATION` on Android. Defaults to `true`. */
  isAndroidBackgroundLocationEnabled?: boolean;
  /** Inject the foreground-service permissions on Android. Defaults to `true`. */
  isAndroidForegroundServiceEnabled?: boolean;
  /** Inject `ACTIVITY_RECOGNITION` on Android. Defaults to `true`. */
  isActivityRecognitionEnabled?: boolean;
}

const DEFAULT_WHEN_IN_USE = "Allow $(PRODUCT_NAME) to use your location.";
const DEFAULT_ALWAYS =
  "Allow $(PRODUCT_NAME) to use your location in the background so tracking continues when the app is closed.";
const DEFAULT_MOTION =
  "Allow $(PRODUCT_NAME) to detect motion to optimize battery use while tracking your location.";

// ---------------------------------------------------------------------------
// iOS
// ---------------------------------------------------------------------------

const withIosLocation: ConfigPlugin<PluginOptions> = (config, opts) =>
  withInfoPlist(config, (cfg) => {
    const plist = cfg.modResults;

    plist.NSLocationWhenInUseUsageDescription =
      opts.locationWhenInUsePermission ??
      plist.NSLocationWhenInUseUsageDescription ??
      DEFAULT_WHEN_IN_USE;

    plist.NSLocationAlwaysAndWhenInUseUsageDescription =
      opts.locationAlwaysAndWhenInUsePermission ??
      plist.NSLocationAlwaysAndWhenInUseUsageDescription ??
      DEFAULT_ALWAYS;

    // Deployment target is iOS 15.1, where the modern When-In-Use +
    // AlwaysAndWhenInUse pair suffices. Only write the legacy (iOS < 11) key when
    // the consumer explicitly supplies it, to keep the declared privacy surface
    // minimal.
    if (opts.locationAlwaysPermission) {
      plist.NSLocationAlwaysUsageDescription = opts.locationAlwaysPermission;
    }

    if (opts.motionPermission !== false) {
      plist.NSMotionUsageDescription =
        (typeof opts.motionPermission === "string"
          ? opts.motionPermission
          : undefined) ??
        plist.NSMotionUsageDescription ??
        DEFAULT_MOTION;
    }

    const modes = new Set<string>(
      Array.isArray(plist.UIBackgroundModes)
        ? (plist.UIBackgroundModes as string[])
        : [],
    );
    modes.add("location");
    // `fetch`/`processing` are opt-in: the module never registers a
    // BGTaskScheduler task, so declaring them by default would request
    // capabilities it does not use (and invite App Store review questions).
    if (opts.enableBackgroundFetch === true) {
      modes.add("fetch");
      modes.add("processing");
    }
    plist.UIBackgroundModes = Array.from(modes);

    return cfg;
  });

// ---------------------------------------------------------------------------
// Android
// ---------------------------------------------------------------------------

const TOOLS_NS = "http://schemas.android.com/tools";

/** Append a `<uses-permission android:name tools:node="remove" />` marker so the
 *  manifest merger strips a permission the autolinked library would otherwise add.
 *  Android manifest JSON is a loosely-typed attribute bag, so the attribute maps
 *  are treated permissively here. */
function removePermission(
  androidManifest: AndroidConfig.Manifest.AndroidManifest,
  name: string,
): void {
  const manifest = androidManifest.manifest as unknown as {
    $: Record<string, string>;
    "uses-permission"?: { $: Record<string, string> }[];
  };
  manifest.$ = manifest.$ ?? {};
  if (!manifest.$["xmlns:tools"]) {
    manifest.$["xmlns:tools"] = TOOLS_NS;
  }
  const permissions = manifest["uses-permission"] ?? [];
  manifest["uses-permission"] = permissions;

  const existing = permissions.find(
    (perm) => perm.$?.["android:name"] === name,
  );
  if (existing) {
    existing.$["tools:node"] = "remove";
  } else {
    permissions.push({ $: { "android:name": name, "tools:node": "remove" } });
  }
}

const withAndroidLocation: ConfigPlugin<PluginOptions> = (config, opts) =>
  withAndroidManifest(config, (cfg) => {
    const manifest = cfg.modResults;

    // Always-present core permissions.
    const ensure = [
      "android.permission.ACCESS_COARSE_LOCATION",
      "android.permission.ACCESS_FINE_LOCATION",
      "android.permission.WAKE_LOCK",
      "android.permission.INTERNET",
      "android.permission.ACCESS_NETWORK_STATE",
      "android.permission.RECEIVE_BOOT_COMPLETED",
    ];

    if (opts.isAndroidForegroundServiceEnabled !== false) {
      ensure.push(
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
      );
    }

    for (const permission of ensure) {
      AndroidConfig.Permissions.ensurePermission(manifest, permission);
    }

    if (opts.isAndroidBackgroundLocationEnabled === false) {
      removePermission(
        manifest,
        "android.permission.ACCESS_BACKGROUND_LOCATION",
      );
    } else {
      AndroidConfig.Permissions.ensurePermission(
        manifest,
        "android.permission.ACCESS_BACKGROUND_LOCATION",
      );
    }

    if (opts.isActivityRecognitionEnabled === false) {
      removePermission(manifest, "android.permission.ACTIVITY_RECOGNITION");
      removePermission(
        manifest,
        "com.google.android.gms.permission.ACTIVITY_RECOGNITION",
      );
    } else {
      AndroidConfig.Permissions.ensurePermission(
        manifest,
        "android.permission.ACTIVITY_RECOGNITION",
      );
    }

    return cfg;
  });

// ---------------------------------------------------------------------------

const withPersistentBackgroundLocation: ConfigPlugin<PluginOptions | void> = (
  config,
  options,
) => {
  const opts = options ?? {};
  config = withIosLocation(config, opts);
  config = withAndroidLocation(config, opts);
  return config;
};

export default createRunOncePlugin(
  withPersistentBackgroundLocation,
  pkg.name,
  pkg.version,
);
