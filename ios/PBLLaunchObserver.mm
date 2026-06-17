//
//  PBLLaunchObserver.mm
//  react-native-persistent-background-location
//
//  Zero-config launch hook. Registers — at `+load` time, which runs *before*
//  `application:didFinishLaunchingWithOptions:` — an observer for
//  `UIApplicationDidFinishLaunchingNotification`. When iOS relaunches the app in
//  the background after a force-quit due to a significant location change, the
//  notification's userInfo carries `UIApplicationLaunchOptionsLocationKey`; we
//  forward that fact into Swift via the `@_cdecl` symbol `RNPBLResumeIfNeeded`,
//  which re-arms the tracking session.
//
//  This replaces the Expo AppDelegate subscriber: no ExpoModulesCore dependency,
//  and the consumer never has to edit their AppDelegate.
//

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

// Implemented in LaunchHook.swift, exported with a plain C symbol via @_cdecl.
extern "C" void RNPBLResumeIfNeeded(bool forLocation);

@interface PBLLaunchObserver : NSObject
@end

@implementation PBLLaunchObserver

+ (void)load {
  // `+load` is invoked once, very early in process startup — before the app
  // delegate receives didFinishLaunching — so the observer below is guaranteed
  // to be registered in time to catch the SLC relaunch notification.
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    [[NSNotificationCenter defaultCenter]
        addObserverForName:UIApplicationDidFinishLaunchingNotification
                    object:nil
                     queue:[NSOperationQueue mainQueue]
                usingBlock:^(NSNotification *_Nonnull note) {
                  BOOL forLoc =
                      note.userInfo[UIApplicationLaunchOptionsLocationKey] != nil;
                  RNPBLResumeIfNeeded(forLoc ? true : false);
                }];
  });
}

@end
