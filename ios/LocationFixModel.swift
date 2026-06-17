import CoreLocation
import Foundation

/// Immutable representation of a single location sample.
///
/// Field names and nullability mirror `NativeLocationFix` in
/// `src/specs/LocationTypes.nitro.ts` exactly. The bridge payload is produced by
/// ``toNitro()`` — a generated Nitro struct where optional numeric fields are
/// Swift optionals carrying an honest `nil` to JS instead of a sentinel.
struct LocationFixModel {
  let id: String?
  let latitude: Double
  let longitude: Double
  let accuracy: Double?
  let altitude: Double?
  let altitudeAccuracy: Double?
  let speed: Double?
  let speedAccuracy: Double?
  let heading: Double?
  let headingAccuracy: Double?
  let timestamp: Int64
  let isMoving: Bool
  let activity: String
  let batteryLevel: Double?
  let isCharging: Bool?
  let mocked: Bool
  let provider: String?

  /// Nitro bridge payload (events + function return values). Optional numeric
  /// fields stay as Swift optionals; `nil` where iOS did not measure a value.
  /// `timestamp` is widened to `Double` so the full epoch-ms range survives the
  /// JS number bridge.
  func toNitro() -> NativeLocationFix {
    return NativeLocationFix(
      id: id,
      latitude: latitude,
      longitude: longitude,
      accuracy: accuracy,
      altitude: altitude,
      altitudeAccuracy: altitudeAccuracy,
      speed: speed,
      speedAccuracy: speedAccuracy,
      heading: heading,
      headingAccuracy: headingAccuracy,
      timestamp: Double(timestamp),
      isMoving: isMoving,
      activity: activity,
      batteryLevel: batteryLevel,
      isCharging: isCharging,
      mocked: mocked,
      provider: provider
    )
  }

  /// Compact JSON dictionary for the SQLite body / HTTP sync payload.
  func toJSONObject() -> [String: Any] {
    var obj: [String: Any] = [
      "latitude": latitude,
      "longitude": longitude,
      "timestamp": timestamp,
      "isMoving": isMoving,
      "activity": activity,
      "mocked": mocked
    ]
    obj["id"] = id ?? NSNull()
    obj["accuracy"] = accuracy ?? NSNull()
    obj["altitude"] = altitude ?? NSNull()
    obj["altitudeAccuracy"] = altitudeAccuracy ?? NSNull()
    obj["speed"] = speed ?? NSNull()
    obj["speedAccuracy"] = speedAccuracy ?? NSNull()
    obj["heading"] = heading ?? NSNull()
    obj["headingAccuracy"] = headingAccuracy ?? NSNull()
    obj["batteryLevel"] = batteryLevel ?? NSNull()
    obj["isCharging"] = isCharging ?? NSNull()
    obj["provider"] = provider ?? NSNull()
    return obj
  }

  static func fromJSONObject(_ json: [String: Any], id: String?) -> LocationFixModel? {
    guard let latitude = json["latitude"] as? Double,
          let longitude = json["longitude"] as? Double else {
      return nil
    }
    func optDouble(_ key: String) -> Double? {
      if let n = json[key] as? Double { return n }
      if let n = json[key] as? NSNumber { return n.doubleValue }
      return nil
    }
    let ts: Int64 = (json["timestamp"] as? NSNumber)?.int64Value
      ?? Int64((json["timestamp"] as? Double) ?? 0)
    return LocationFixModel(
      id: id ?? (json["id"] as? String),
      latitude: latitude,
      longitude: longitude,
      accuracy: optDouble("accuracy"),
      altitude: optDouble("altitude"),
      altitudeAccuracy: optDouble("altitudeAccuracy"),
      speed: optDouble("speed"),
      speedAccuracy: optDouble("speedAccuracy"),
      heading: optDouble("heading"),
      headingAccuracy: optDouble("headingAccuracy"),
      timestamp: ts,
      isMoving: (json["isMoving"] as? Bool) ?? false,
      activity: (json["activity"] as? String) ?? "unknown",
      batteryLevel: optDouble("batteryLevel"),
      isCharging: json["isCharging"] as? Bool,
      mocked: (json["mocked"] as? Bool) ?? false,
      provider: json["provider"] as? String
    )
  }

  /// Build a fix from a `CLLocation`, gating each optional on its validity
  /// sentinel so we report `nil` (not a misleading `0`/`-1`) when iOS did not
  /// measure a value.
  static func from(
    location: CLLocation,
    isMoving: Bool,
    activity: String,
    battery: Double?,
    charging: Bool?,
    provider: String
  ) -> LocationFixModel {
    let accuracy = location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil
    let altitudeValid = location.verticalAccuracy > 0
    let altitude = altitudeValid ? location.altitude : nil
    let altitudeAccuracy = altitudeValid ? location.verticalAccuracy : nil
    let speed = location.speed >= 0 ? location.speed : nil

    var speedAccuracy: Double? = nil
    if location.speedAccuracy >= 0 { speedAccuracy = location.speedAccuracy }

    let heading = location.course >= 0 ? location.course : nil
    var headingAccuracy: Double? = nil
    if #available(iOS 13.4, *) {
      if location.courseAccuracy >= 0 { headingAccuracy = location.courseAccuracy }
    }

    var mocked = false
    if #available(iOS 15.0, *) {
      mocked = location.sourceInformation?.isSimulatedBySoftware ?? false
    }

    let timestampMs = Int64(location.timestamp.timeIntervalSince1970 * 1000.0)

    return LocationFixModel(
      id: nil,
      latitude: location.coordinate.latitude,
      longitude: location.coordinate.longitude,
      accuracy: accuracy,
      altitude: altitude,
      altitudeAccuracy: altitudeAccuracy,
      speed: speed,
      speedAccuracy: speedAccuracy,
      heading: heading,
      headingAccuracy: headingAccuracy,
      timestamp: timestampMs,
      isMoving: isMoving,
      activity: activity,
      batteryLevel: battery,
      isCharging: charging,
      mocked: mocked,
      provider: provider
    )
  }
}
