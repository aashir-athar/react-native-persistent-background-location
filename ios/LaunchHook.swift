import Foundation

/// C-callable entry point invoked by ``PBLLaunchObserver`` (Obj-C++) when iOS
/// posts `UIApplicationDidFinishLaunchingNotification`.
///
/// When iOS relaunches the app **in the background** after a force-quit because
/// a significant location change fired, the launch notification carries the
/// `.location` key. This is the single hook that lets the package resume
/// tracking after termination — there is no JS lifecycle event early enough to
/// catch it, and (unlike the Expo build) we do not depend on an Expo
/// AppDelegate subscriber or any consumer AppDelegate edits.
///
/// `@_cdecl` exports this with an unmangled C symbol so the `.mm` translation
/// unit can call it via `extern "C"`.
@_cdecl("RNPBLResumeIfNeeded")
public func RNPBLResumeIfNeeded(_ forLocation: Bool) {
  LocationController.shared.resumeIfNeeded(launchedForLocation: forLocation)
}
