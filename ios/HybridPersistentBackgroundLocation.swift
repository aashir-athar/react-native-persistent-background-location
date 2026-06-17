import CoreLocation
import Foundation
import NitroModules
import UIKit

/// JS ↔ Swift bridge (Nitro HybridObject). Thin by design: every method
/// configures the long-lived ``LocationController`` singleton or delegates to
/// ``LocationPermissions`` / ``LocationBufferStore``. The controller outlives
/// this object (and the JS runtime), which is what lets tracking resume
/// headlessly after a force-quit.
///
/// Surface and event names are the contract in
/// `src/specs/PersistentBackgroundLocation.nitro.ts`; keep them in sync.
final class HybridPersistentBackgroundLocation: HybridPersistentBackgroundLocationSpec {
  // MARK: - Lifecycle

  func start(config: NativeStartConfig) throws -> Promise<Void> {
    let promise = Promise<Void>()
    guard Self.isAuthorized() else {
      promise.reject(withError: RuntimeError.error(withMessage:
        "\(PBLConstants.errPermissionDenied): Location permission not granted. Call requestPermissions() before start()."))
      return promise
    }
    LocationController.shared.start(Self.toConfig(config), restarted: false)
    promise.resolve()
    return promise
  }

  func stop() throws -> Promise<Void> {
    LocationController.shared.stop()
    return Promise.resolved()
  }

  func isRunning() throws -> Bool {
    return LocationController.shared.running
  }

  func getStatus() throws -> Promise<NativeTrackingStatus> {
    return Promise.parallel {
      LocationController.shared.statusStruct()
    }
  }

  func getCurrentPosition(options: NativeCurrentPositionOptions) throws -> Promise<NativeLocationFix> {
    let promise = Promise<NativeLocationFix>()
    guard Self.isAuthorized() else {
      promise.reject(withError: RuntimeError.error(withMessage:
        "\(PBLConstants.errPermissionDenied): Location permission not granted."))
      return promise
    }
    LocationController.shared.currentPosition(
      accuracy: options.accuracy,
      timeoutMs: options.timeoutMs,
      maximumAgeMs: options.maximumAgeMs
    ) { fix in
      if let fix = fix {
        promise.resolve(withResult: fix.toNitro())
      } else {
        promise.reject(withError: RuntimeError.error(withMessage:
          "\(PBLConstants.errTimeout): Timed out acquiring a location fix."))
      }
    }
    return promise
  }

  // MARK: - Buffer & sync

  func getBufferedLocations(limit: Double) throws -> Promise<[NativeLocationFix]> {
    let count = Int(limit)
    return Promise.parallel {
      LocationBufferStore.shared.recent(count).map { $0.toNitro() }
    }
  }

  func clearBuffer() throws -> Promise<Double> {
    return Promise.parallel {
      Double(LocationBufferStore.shared.clear())
    }
  }

  func flush() throws -> Promise<NativeSyncResult> {
    let promise = Promise<NativeSyncResult>()
    LocationController.shared.flush { result in
      promise.resolve(withResult: result.toNitro())
    }
    return promise
  }

  // MARK: - Permissions

  func requestPermissions(background: Bool) throws -> Promise<NativePermissionResult> {
    let promise = Promise<NativePermissionResult>()
    LocationPermissions.shared.request(background: background) { dict in
      promise.resolve(withResult: Self.permissionResult(from: dict))
    }
    return promise
  }

  func getPermissionStatus() throws -> Promise<NativePermissionResult> {
    let dict = LocationPermissions.shared.currentStatusDictionary()
    return Promise.resolved(withResult: Self.permissionResult(from: dict))
  }

  func openSettings() throws -> Void {
    guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
    DispatchQueue.main.async {
      UIApplication.shared.open(url, options: [:], completionHandler: nil)
    }
  }

  // MARK: - Event callbacks

  func setOnLocation(callback: ((_ fix: NativeLocationFix) -> Void)?) throws -> Void {
    LocationController.shared.onLocation = callback
  }

  func setOnMotionChange(callback: ((_ event: NativeMotionChangeEvent) -> Void)?) throws -> Void {
    LocationController.shared.onMotionChange = callback
  }

  func setOnProviderChange(callback: ((_ event: NativeProviderChangeEvent) -> Void)?) throws -> Void {
    LocationController.shared.onProviderChange = callback
  }

  func setOnSync(callback: ((_ event: NativeSyncResult) -> Void)?) throws -> Void {
    LocationController.shared.onSync = callback
  }

  func setOnError(callback: ((_ event: NativeLocationErrorEvent) -> Void)?) throws -> Void {
    LocationController.shared.onError = callback
  }

  // MARK: - Conversions

  private static func isAuthorized() -> Bool {
    let status = LocationPermissions.shared.currentStatus()
    return status == .authorizedAlways || status == .authorizedWhenInUse
  }

  /// `LocationPermissions` still returns the legacy `[String: Any?]` dictionary;
  /// fold it into the generated `NativePermissionResult` struct.
  private static func permissionResult(from dict: [String: Any?]) -> NativePermissionResult {
    let status = (dict["status"] as? String) ?? "undetermined"
    let foreground = (dict["foreground"] as? String) ?? "undetermined"
    let background = (dict["background"] as? String) ?? "undetermined"
    let canAskAgain = (dict["canAskAgain"] as? Bool) ?? false
    return NativePermissionResult(
      status: status,
      foreground: foreground,
      background: background,
      canAskAgain: canAskAgain
    )
  }

  /// Convert the JS-facing `NativeStartConfig` (numbers are `Double`) into the
  /// internal `LocationConfig` (some fields are `Int`).
  private static func toConfig(_ c: NativeStartConfig) -> LocationConfig {
    return LocationConfig(
      accuracy: c.accuracy,
      distanceFilter: c.distanceFilter,
      interval: c.interval,
      fastestInterval: c.fastestInterval,
      activityType: c.activityType,
      showsBackgroundLocationIndicator: c.showsBackgroundLocationIndicator,
      pausesUpdatesAutomatically: c.pausesUpdatesAutomatically,
      stopOnTerminate: c.stopOnTerminate,
      restartOnBoot: c.restartOnBoot,
      useSignificantChanges: c.useSignificantChanges,
      debug: c.debug,
      notificationTitle: c.notificationTitle,
      notificationBody: c.notificationBody,
      notificationChannelId: c.notificationChannelId,
      notificationChannelName: c.notificationChannelName,
      notificationColor: c.notificationColor,
      notificationIcon: c.notificationIcon,
      tapToOpenApp: c.tapToOpenApp,
      persist: c.persist,
      syncUrl: c.syncUrl,
      httpMethod: c.httpMethod,
      headers: c.headers,
      batchSize: Int(c.batchSize),
      autoSync: c.autoSync,
      maxRecordsToPersist: Int(c.maxRecordsToPersist),
      motionEnabled: c.motionEnabled,
      stationaryTimeoutMs: c.stationaryTimeoutMs
    )
  }
}
