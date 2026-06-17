# ───────────────────────────────────────────────────────────────────────────
# react-native-persistent-background-location — consumer R8/ProGuard rules.
#
# Nitro's own generated classes (the spec, the structs, the Func_* callbacks)
# are annotated @Keep / @DoNotStrip, so they survive R8 without rules here. The
# rules below cover the runtime entry points the OS / Play Services resolve by
# name (the service, receivers, and the bootstrap ContentProvider) plus the
# play-services-location classes reached reflectively.
# ───────────────────────────────────────────────────────────────────────────

# Components the OS instantiates by class name from the manifest.
-keep class com.margelo.nitro.persistentbackgroundlocation.BackgroundLocationService { *; }
-keep class com.margelo.nitro.persistentbackgroundlocation.BootBroadcastReceiver { *; }
-keep class com.margelo.nitro.persistentbackgroundlocation.ActivityRecognitionReceiver { *; }
-keep class com.margelo.nitro.persistentbackgroundlocation.LocationContextProvider { *; }

# The Nitro HybridObject implementation is created from C++ by exact class name.
-keep class com.margelo.nitro.persistentbackgroundlocation.HybridPersistentBackgroundLocation { *; }

# Google Play Services location / activity-recognition.
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

-keepattributes *Annotation*
