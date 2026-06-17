import Foundation

/// Immutable, `Codable` snapshot of a tracking session's configuration.
///
/// Field names map 1:1 onto `NativeStartConfig` in the TypeScript layer. The
/// snapshot is what gets persisted to `UserDefaults` so a background relaunch
/// (significant-location-change after termination) can restore the exact session
/// the user started.
struct LocationConfig: Codable {
  var accuracy: String
  var distanceFilter: Double
  var interval: Double
  var fastestInterval: Double
  var activityType: String
  var showsBackgroundLocationIndicator: Bool
  var pausesUpdatesAutomatically: Bool
  var stopOnTerminate: Bool
  var restartOnBoot: Bool
  var useSignificantChanges: Bool
  var debug: Bool
  var notificationTitle: String
  var notificationBody: String
  var notificationChannelId: String
  var notificationChannelName: String
  var notificationColor: String?
  var notificationIcon: String?
  var tapToOpenApp: Bool
  var persist: Bool
  var syncUrl: String?
  var httpMethod: String
  var headers: [String: String]
  var batchSize: Int
  var autoSync: Bool
  var maxRecordsToPersist: Int
  var motionEnabled: Bool
  var stationaryTimeoutMs: Double
}

/// Durable home for the active config + the "should be tracking" flag.
enum ConfigStore {
  private static let configKey = "rn_pbl_config"
  private static let wasTrackingKey = "rn_pbl_was_tracking"
  private static let trackingSinceKey = "rn_pbl_tracking_since"

  private static var defaults: UserDefaults { UserDefaults.standard }

  static func save(_ config: LocationConfig, trackingSince: Int64) {
    if let data = try? JSONEncoder().encode(config) {
      defaults.set(data, forKey: configKey)
    }
    defaults.set(true, forKey: wasTrackingKey)
    defaults.set(NSNumber(value: trackingSince), forKey: trackingSinceKey)
  }

  static func load() -> LocationConfig? {
    guard let data = defaults.data(forKey: configKey) else { return nil }
    return try? JSONDecoder().decode(LocationConfig.self, from: data)
  }

  static func wasTracking() -> Bool {
    return defaults.bool(forKey: wasTrackingKey)
  }

  static func trackingSince() -> Int64 {
    return (defaults.object(forKey: trackingSinceKey) as? NSNumber)?.int64Value ?? 0
  }

  /// Clear the "should be tracking" flag **and** the persisted config blob —
  /// the config can carry `buffer.headers` (e.g. an `Authorization` token), so
  /// we do not leave it in plaintext `UserDefaults` after tracking stops.
  static func markStopped() {
    defaults.set(false, forKey: wasTrackingKey)
    defaults.removeObject(forKey: trackingSinceKey)
    defaults.removeObject(forKey: configKey)
  }
}
