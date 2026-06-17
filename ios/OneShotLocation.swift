import CoreLocation
import Foundation

/// One-shot `requestLocation()` helper backing `getCurrentPosition()`.
///
/// Keeps itself alive in a static registry for the duration of the request (a
/// `CLLocationManager` holds only a weak delegate), and guarantees the
/// completion fires exactly once — on a fix, a failure, or a timeout.
final class OneShotLocation: NSObject, CLLocationManagerDelegate {
  private static var inFlight: [OneShotLocation] = []
  private static let registryLock = NSLock()

  private let manager = CLLocationManager()
  private var completion: ((CLLocation?) -> Void)?
  private var timeoutWork: DispatchWorkItem?
  private var settled = false

  static func request(
    accuracy: CLLocationAccuracy,
    timeout: TimeInterval,
    maximumAge: TimeInterval,
    completion: @escaping (CLLocation?) -> Void
  ) {
    DispatchQueue.main.async {
      let instance = OneShotLocation()
      registryLock.lock()
      inFlight.append(instance)
      registryLock.unlock()

      instance.begin(accuracy: accuracy, timeout: timeout, maximumAge: maximumAge) { location in
        completion(location)
        registryLock.lock()
        inFlight.removeAll { $0 === instance }
        registryLock.unlock()
      }
    }
  }

  private func begin(
    accuracy: CLLocationAccuracy,
    timeout: TimeInterval,
    maximumAge: TimeInterval,
    completion: @escaping (CLLocation?) -> Void
  ) {
    // A recent enough cached fix satisfies a non-zero maxAge immediately.
    if maximumAge > 0, let cached = manager.location,
       Date().timeIntervalSince(cached.timestamp) <= maximumAge {
      completion(cached)
      return
    }

    self.completion = completion
    manager.delegate = self
    manager.desiredAccuracy = accuracy
    manager.requestLocation()

    let work = DispatchWorkItem { [weak self] in self?.resolve(nil) }
    timeoutWork = work
    DispatchQueue.main.asyncAfter(deadline: .now() + max(1.0, timeout), execute: work)
  }

  private func resolve(_ location: CLLocation?) {
    if settled { return }
    settled = true
    timeoutWork?.cancel()
    timeoutWork = nil
    manager.delegate = nil
    let callback = completion
    completion = nil
    callback?(location)
  }

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    resolve(locations.last)
  }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    resolve(nil)
  }
}
