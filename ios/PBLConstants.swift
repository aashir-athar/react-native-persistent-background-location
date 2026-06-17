import Foundation

/// Event names + error codes shared across the iOS sources. Event names are part
/// of the public JS contract (see `src/specs/PersistentBackgroundLocation.nitro.ts`)
/// — do not rename without updating TypeScript in lock-step.
enum PBLConstants {
  static let moduleName = "PersistentBackgroundLocation"

  static let eventLocation = "onLocation"
  static let eventMotionChange = "onMotionChange"
  static let eventProviderChange = "onProviderChange"
  static let eventSync = "onSync"
  static let eventError = "onError"

  static let errPermissionDenied = "ERR_PERMISSION_DENIED"
  static let errLocationUnavailable = "ERR_LOCATION_UNAVAILABLE"
  static let errTimeout = "ERR_TIMEOUT"
  static let errServicesDisabled = "ERR_LOCATION_SERVICES_DISABLED"

  static let logTag = "[RNBgLocation]"
}
