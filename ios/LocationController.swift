import CoreLocation
import Foundation
import UIKit

/// The heart of the package's iOS story.
///
/// A process-wide singleton wrapping one `CLLocationManager`. It streams fixes,
/// enriches them with motion + battery context, persists them to the
/// ``LocationBufferStore``, and lets the ``LocationSyncer`` drain them — all
/// without any JS running. Critically, when `useSignificantChanges` is on it
/// registers significant-location-change monitoring so iOS will **relaunch the
/// app in the background after a force-quit** (the one exception Apple allows),
/// at which point ``resumeIfNeeded(launchedForLocation:)`` re-arms the exact
/// session.
///
/// Honest limitation: iOS cannot deliver continuous high-rate GPS after a
/// force-quit — only the coarse SLC wake. That is a platform constraint, not a
/// TODO.
final class LocationController: NSObject, CLLocationManagerDelegate {
  static let shared = LocationController()

  // Created (and its delegate assigned) on the main thread — CLLocationManager
  // only delivers callbacks on the run loop of the thread it was created on.
  private var manager: CLLocationManager!

  // Cross-thread state: written on the main thread (delegate callbacks / start)
  // and read from a background function queue (statusStruct / isRunning / flush)
  // and the sync-timer queue. All access goes through `stateLock` — `lastFix` in
  // particular is an ARC reference whose unsynchronized swap-while-read is
  // undefined behaviour, not just a stale read.
  private let stateLock = NSLock()
  private var _config: LocationConfig?
  private var _running = false
  private var _lastFix: LocationFixModel?
  private var _isMoving = false
  private var _trackingSince: Int64 = 0

  // Setters are used only inside this class; reads are thread-safe via the lock.
  var config: LocationConfig? {
    get { lockState { _config } }
    set { lockState { _config = newValue } }
  }
  var running: Bool {
    get { lockState { _running } }
    set { lockState { _running = newValue } }
  }
  var lastFix: LocationFixModel? {
    get { lockState { _lastFix } }
    set { lockState { _lastFix = newValue } }
  }
  var isMoving: Bool {
    get { lockState { _isMoving } }
    set { lockState { _isMoving = newValue } }
  }
  var trackingSince: Int64 {
    get { lockState { _trackingSince } }
    set { lockState { _trackingSince = newValue } }
  }

  // Typed event callbacks, set by the HybridObject while a JS runtime is
  // attached and nil when the app is running headless (post-relaunch, before JS
  // boots). Each carries an already-built Nitro struct.
  var onLocation: ((NativeLocationFix) -> Void)?
  var onMotionChange: ((NativeMotionChangeEvent) -> Void)?
  var onProviderChange: ((NativeProviderChangeEvent) -> Void)?
  var onSync: ((NativeSyncResult) -> Void)?
  var onError: ((NativeLocationErrorEvent) -> Void)?

  // Heuristic motion-gate state (touched on the main thread only).
  private var lastMovingAtMs: Int64 = 0
  private var lastLat: Double?
  private var lastLon: Double?
  private var lastFixAtMs: Int64 = 0
  private var activity = "unknown"

  private var autoSyncTimer: DispatchSourceTimer?

  private override init() {
    super.init()
    let setUp = {
      self.manager = CLLocationManager()
      self.manager.delegate = self
    }
    if Thread.isMainThread {
      setUp()
    } else {
      DispatchQueue.main.sync(execute: setUp)
    }
  }

  private func lockState<T>(_ body: () -> T) -> T {
    stateLock.lock()
    defer { stateLock.unlock() }
    return body()
  }

  // MARK: - Lifecycle

  func start(_ newConfig: LocationConfig, restarted: Bool) {
    runOnMain {
      self.config = newConfig
      self.trackingSince = restarted && ConfigStore.trackingSince() > 0
        ? ConfigStore.trackingSince()
        : Int64(Date().timeIntervalSince1970 * 1000)
      ConfigStore.save(newConfig, trackingSince: self.trackingSince)

      let m: CLLocationManager = self.manager
      m.desiredAccuracy = self.accuracy(for: newConfig.accuracy)
      m.distanceFilter = newConfig.distanceFilter > 0 ? newConfig.distanceFilter : kCLDistanceFilterNone
      m.activityType = self.activityType(for: newConfig.activityType)
      m.pausesLocationUpdatesAutomatically = newConfig.pausesUpdatesAutomatically
      if #available(iOS 11.0, *) {
        m.showsBackgroundLocationIndicator = newConfig.showsBackgroundLocationIndicator
      }
      // Only legal when the host app declares the `location` background mode —
      // the config plugin adds it; guard anyway so a misconfig can't crash.
      if Self.hasLocationBackgroundMode() {
        m.allowsBackgroundLocationUpdates = !newConfig.stopOnTerminate
      }

      m.startUpdatingLocation()
      if newConfig.useSignificantChanges {
        m.startMonitoringSignificantLocationChanges()
      }

      UIDevice.current.isBatteryMonitoringEnabled = true
      self.running = true
      self.startAutoSyncTimer(newConfig)

      if newConfig.debug {
        NSLog("\(PBLConstants.logTag) Tracking started (restarted=\(restarted), slc=\(newConfig.useSignificantChanges)).")
      }
    }
  }

  func stop() {
    runOnMain {
      self.manager.stopUpdatingLocation()
      self.manager.stopMonitoringSignificantLocationChanges()
      self.stopAutoSyncTimer()
      self.running = false
      self.isMoving = false
      // Drop the in-memory config too — it may carry auth headers/token. Pairs
      // with ConfigStore.markStopped() which wipes the persisted blob, so a
      // flush() after stop() can no longer reuse the old credentials.
      self.config = nil
      self.resetMotion()
      ConfigStore.markStopped()
    }
  }

  /// Re-arm tracking after a background relaunch (SLC after force-quit). Called
  /// by the launch hook (`RNPBLResumeIfNeeded`) from `didFinishLaunching`.
  func resumeIfNeeded(launchedForLocation: Bool) {
    guard launchedForLocation || ConfigStore.wasTracking() else { return }
    guard let config = ConfigStore.load(), !config.stopOnTerminate else { return }
    let status = LocationPermissions.shared.currentStatus()
    guard status == .authorizedAlways || status == .authorizedWhenInUse else { return }
    start(config, restarted: true)
  }

  // MARK: - One-shot + status + flush

  func currentPosition(
    accuracy: String,
    timeoutMs: Double,
    maximumAgeMs: Double,
    completion: @escaping (LocationFixModel?) -> Void
  ) {
    OneShotLocation.request(
      accuracy: self.accuracy(for: accuracy),
      timeout: timeoutMs / 1000.0,
      maximumAge: maximumAgeMs / 1000.0
    ) { location in
      guard let location = location else {
        completion(nil)
        return
      }
      let battery = Self.batterySnapshot()
      completion(LocationFixModel.from(
        location: location,
        isMoving: false,
        activity: "unknown",
        battery: battery.0,
        charging: battery.1,
        provider: "ios"
      ))
    }
  }

  /// Snapshot of tracker state, authorization, and buffer size as a Nitro struct.
  func statusStruct() -> NativeTrackingStatus {
    // Read all cross-thread state under a single lock so the snapshot is
    // internally consistent (no mixing of pre- and post-update epochs).
    let snapshot = lockState { (_running, _lastFix, _isMoving, _trackingSince) }
    let authorization = LocationPermissions.string(for: LocationPermissions.shared.currentStatus())
    return NativeTrackingStatus(
      running: snapshot.0,
      lastFix: snapshot.1?.toNitro(),
      bufferedCount: Double(LocationBufferStore.shared.count()),
      authorization: authorization,
      locationServicesEnabled: CLLocationManager.locationServicesEnabled(),
      isMoving: snapshot.2,
      trackingSince: snapshot.3 > 0 ? Double(snapshot.3) : nil
    )
  }

  func flush(completion: @escaping (SyncResultModel) -> Void) {
    guard let config = config ?? ConfigStore.load(), let url = config.syncUrl, !url.isEmpty else {
      completion(SyncResultModel(success: false, count: 0, status: nil, error: "no-sync-url"))
      return
    }
    LocationSyncer.shared.flush(config) { result in
      self.onSync?(result.toNitro())
      completion(result)
    }
  }

  // MARK: - CLLocationManagerDelegate

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    guard let config = config, let location = locations.last else { return }
    let battery = Self.batterySnapshot()
    let providerName = location.speed >= 0 ? "gps" : "slc"

    // Motion gating is opt-in. When disabled, the contract is `activity: 'unknown'`
    // and a stable `isMoving: false`, with no spurious onMotionChange events.
    var flipped = false
    if config.motionEnabled {
      let provisional = LocationFixModel.from(
        location: location, isMoving: isMoving, activity: activity,
        battery: battery.0, charging: battery.1, provider: providerName
      )
      flipped = considerMotion(provisional)
    } else {
      isMoving = false
      activity = "unknown"
    }

    let fix = LocationFixModel.from(
      location: location,
      isMoving: isMoving,
      activity: activity,
      battery: battery.0,
      charging: battery.1,
      provider: providerName
    )
    lastFix = fix

    if config.persist {
      let snapshot = fix
      DispatchQueue.global(qos: .utility).async {
        LocationBufferStore.shared.insert(snapshot, maxRecords: config.maxRecordsToPersist)
        self.maybeAutoSync(config)
      }
    }

    let nitroFix = fix.toNitro()
    onLocation?(nitroFix)
    if flipped {
      onMotionChange?(NativeMotionChangeEvent(
        isMoving: isMoving,
        activity: activity,
        fix: nitroFix
      ))
    }
  }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    let clError = error as? CLError
    let fatal = clError?.code == .denied
    if clError?.code == .locationUnknown { return } // transient; iOS will retry
    onError?(NativeLocationErrorEvent(
      code: fatal ? PBLConstants.errPermissionDenied : PBLConstants.errLocationUnavailable,
      message: error.localizedDescription,
      fatal: fatal
    ))
  }

  @available(iOS 14.0, *)
  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    emitProviderChange()
  }

  func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
    if #available(iOS 14.0, *) { return }
    emitProviderChange()
  }

  // MARK: - Motion heuristic

  private func considerMotion(_ fix: LocationFixModel) -> Bool {
    let now = fix.timestamp
    let speed = estimateSpeed(fix, now: now)
    activity = classify(speed)
    let movingBySpeed = speed >= Self.movingSpeedMps

    let wasMoving = isMoving
    if movingBySpeed {
      lastMovingAtMs = now
      isMoving = true
    } else if let cfg = config, now - lastMovingAtMs >= Int64(cfg.stationaryTimeoutMs) {
      isMoving = false
    }

    lastLat = fix.latitude
    lastLon = fix.longitude
    lastFixAtMs = now
    return wasMoving != isMoving
  }

  private func estimateSpeed(_ fix: LocationFixModel, now: Int64) -> Double {
    if let s = fix.speed, s >= 0 { return s }
    guard let pLat = lastLat, let pLon = lastLon, lastFixAtMs > 0, now > lastFixAtMs else { return 0 }
    let meters = Self.haversineMeters(pLat, pLon, fix.latitude, fix.longitude)
    return meters / (Double(now - lastFixAtMs) / 1000.0)
  }

  private func classify(_ speed: Double) -> String {
    switch speed {
    case ..<Self.movingSpeedMps: return "still"
    case ..<2.2: return "walking"
    case ..<4.5: return "running"
    case ..<50.0: return "in_vehicle"
    default: return "unknown"
    }
  }

  private func resetMotion() {
    lastMovingAtMs = 0
    lastLat = nil
    lastLon = nil
    lastFixAtMs = 0
    activity = "unknown"
  }

  // MARK: - Sync scheduling

  private func startAutoSyncTimer(_ config: LocationConfig) {
    stopAutoSyncTimer()
    guard config.autoSync, let url = config.syncUrl, !url.isEmpty else { return }
    let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
    timer.schedule(deadline: .now() + 30, repeating: 30)
    timer.setEventHandler { [weak self] in
      guard let self = self, let cfg = self.config else { return }
      self.runSync(cfg)
    }
    timer.resume()
    autoSyncTimer = timer
  }

  private func stopAutoSyncTimer() {
    autoSyncTimer?.cancel()
    autoSyncTimer = nil
  }

  private func maybeAutoSync(_ config: LocationConfig) {
    guard config.autoSync, let url = config.syncUrl, !url.isEmpty else { return }
    if LocationBufferStore.shared.count() >= config.batchSize {
      runSync(config)
    }
  }

  private func runSync(_ config: LocationConfig) {
    LocationSyncer.shared.flush(config) { result in
      // "busy" just means a flush was already in-flight (single-flight) — not a
      // real sync outcome, so don't surface it to JS.
      if result.error == "busy" { return }
      if result.count > 0 || !result.success {
        self.onSync?(result.toNitro())
      }
    }
  }

  // MARK: - Helpers

  private func emitProviderChange() {
    let enabled = CLLocationManager.locationServicesEnabled()
    onProviderChange?(NativeProviderChangeEvent(
      enabled: enabled,
      gpsEnabled: enabled,
      networkEnabled: enabled,
      authorization: LocationPermissions.string(for: LocationPermissions.shared.currentStatus())
    ))
  }

  private func accuracy(for value: String) -> CLLocationAccuracy {
    switch value {
    case "lowest": return kCLLocationAccuracyThreeKilometers
    case "low": return kCLLocationAccuracyKilometer
    case "balanced": return kCLLocationAccuracyHundredMeters
    case "high": return kCLLocationAccuracyNearestTenMeters
    case "highest": return kCLLocationAccuracyBest
    default: return kCLLocationAccuracyNearestTenMeters
    }
  }

  private func activityType(for value: String) -> CLActivityType {
    switch value {
    case "automotiveNavigation": return .automotiveNavigation
    case "fitness": return .fitness
    case "otherNavigation": return .otherNavigation
    case "airborne":
      if #available(iOS 12.0, *) { return .airborne }
      return .other
    default: return .other
    }
  }

  private func runOnMain(_ block: @escaping () -> Void) {
    if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
  }

  // MARK: - Statics

  static let movingSpeedMps = 0.5
  private static let earthRadiusM = 6_371_000.0

  static func haversineMeters(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
    let dLat = (lat2 - lat1) * .pi / 180
    let dLon = (lon2 - lon1) * .pi / 180
    let a = sin(dLat / 2) * sin(dLat / 2)
      + cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
    return earthRadiusM * 2 * atan2(sqrt(a), sqrt(1 - a))
  }

  static func batterySnapshot() -> (Double?, Bool?) {
    let device = UIDevice.current
    if !device.isBatteryMonitoringEnabled { device.isBatteryMonitoringEnabled = true }
    let level = device.batteryLevel
    let batteryLevel: Double? = level >= 0 ? Double(level) : nil
    let charging: Bool?
    switch device.batteryState {
    case .charging, .full: charging = true
    case .unplugged: charging = false
    default: charging = nil
    }
    return (batteryLevel, charging)
  }

  static func hasLocationBackgroundMode() -> Bool {
    guard let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] else {
      return false
    }
    return modes.contains("location")
  }
}
