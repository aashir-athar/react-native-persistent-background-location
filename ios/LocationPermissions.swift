import CoreLocation
import Foundation

/// Owns the iOS location-authorization state machine, including the two-step
/// **When In Use → Always** escalation that Apple requires (you cannot request
/// "Always" directly from `notDetermined` and have it stick).
///
/// A dedicated `CLLocationManager` is kept here so authorization callbacks never
/// tangle with the tracking manager's location-delivery callbacks.
final class LocationPermissions: NSObject, CLLocationManagerDelegate {
  static let shared = LocationPermissions()

  // Created (and delegate assigned) on the main thread — the singleton is often
  // first touched from a background function queue, and CLLocationManager only
  // delivers authorization callbacks on the run loop of its origin thread.
  private var manager: CLLocationManager!
  private var completion: (([String: Any?]) -> Void)?
  private var wantsBackground = false
  private var stage = 0 // 0 = idle, 1 = foreground asked, 2 = background asked
  private var timeoutWork: DispatchWorkItem?

  override init() {
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

  // MARK: - Public

  func currentStatusDictionary() -> [String: Any?] {
    return buildDictionary(currentStatus())
  }

  func request(background: Bool, completion: @escaping ([String: Any?]) -> Void) {
    DispatchQueue.main.async {
      // Re-entrant call: settle any in-flight request with the current status so
      // its JS promise never hangs, then take over.
      if let pending = self.completion {
        self.timeoutWork?.cancel()
        self.timeoutWork = nil
        pending(self.buildDictionary(self.currentStatus()))
      }
      self.completion = completion
      self.wantsBackground = background
      let status = self.currentStatus()
      switch status {
      case .notDetermined:
        self.stage = 1
        self.manager.requestWhenInUseAuthorization()
        self.armTimeout()
      case .authorizedWhenInUse where background:
        self.stage = 2
        self.manager.requestAlwaysAuthorization()
        self.armTimeout()
      default:
        self.resolve(status)
      }
    }
  }

  static func string(for status: CLAuthorizationStatus) -> String {
    switch status {
    case .authorizedAlways: return "granted"
    case .authorizedWhenInUse: return "whenInUse"
    case .denied: return "denied"
    case .restricted: return "restricted"
    case .notDetermined: return "undetermined"
    @unknown default: return "undetermined"
    }
  }

  func currentStatus() -> CLAuthorizationStatus {
    if #available(iOS 14.0, *) {
      return manager.authorizationStatus
    } else {
      return CLLocationManager.authorizationStatus()
    }
  }

  // MARK: - CLLocationManagerDelegate

  @available(iOS 14.0, *)
  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    handleChange()
  }

  func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
    // Fires on iOS < 14; on iOS 14+ the parameterless variant above is used.
    if #available(iOS 14.0, *) { return }
    handleChange()
  }

  // MARK: - Internals

  private func handleChange() {
    let status = currentStatus()
    if status == .notDetermined { return } // still awaiting the user's choice
    if stage == 1, status == .authorizedWhenInUse, wantsBackground {
      stage = 2
      manager.requestAlwaysAuthorization()
      armTimeout()
      return
    }
    resolve(status)
  }

  private func armTimeout() {
    timeoutWork?.cancel()
    let work = DispatchWorkItem { [weak self] in
      guard let self = self else { return }
      // iOS may not re-fire the delegate when the user keeps the same level on an
      // "Always" upgrade prompt — resolve with whatever the status is by now.
      self.resolve(self.currentStatus())
    }
    timeoutWork = work
    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0, execute: work)
  }

  private func resolve(_ status: CLAuthorizationStatus) {
    timeoutWork?.cancel()
    timeoutWork = nil
    stage = 0
    let callback = completion
    completion = nil
    callback?(buildDictionary(status))
  }

  private func buildDictionary(_ status: CLAuthorizationStatus) -> [String: Any?] {
    let foreground: String
    let background: String
    switch status {
    case .authorizedAlways:
      foreground = "granted"; background = "granted"
    case .authorizedWhenInUse:
      foreground = "granted"; background = "denied"
    case .denied:
      foreground = "denied"; background = "denied"
    case .restricted:
      foreground = "restricted"; background = "restricted"
    case .notDetermined:
      foreground = "undetermined"; background = "undetermined"
    @unknown default:
      foreground = "undetermined"; background = "undetermined"
    }
    return [
      "status": LocationPermissions.string(for: status),
      "foreground": foreground,
      "background": background,
      "canAskAgain": status == .notDetermined
    ]
  }
}
