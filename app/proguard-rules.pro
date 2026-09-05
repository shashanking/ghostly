# Views built by name from XML/reflection are none of our concern here (everything is created in
# Kotlin), so the defaults are enough. Keep the entry points explicit anyway.
-keep class com.shashank.ghostly.MainActivity { *; }
-keep class com.shashank.ghostly.GhostOverlayService { *; }
-keep class com.shashank.ghostly.BootReceiver { *; }

# Google Sign-In (Credential Manager) finds its backing provider via reflection and parses the
# returned credential out of a Bundle by field — R8 can rename or strip pieces of that in a
# minified release build without ever failing the build, so sign-in would silently break only for
# Play Store users while an unminified debug build kept working fine.
-keep class androidx.credentials.** { *; }
-keep class com.google.android.gms.auth.api.identity.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn androidx.credentials.**
