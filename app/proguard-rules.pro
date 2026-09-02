# Views built by name from XML/reflection are none of our concern here (everything is created in
# Kotlin), so the defaults are enough. Keep the entry points explicit anyway.
-keep class com.shashank.ghostly.MainActivity { *; }
-keep class com.shashank.ghostly.GhostOverlayService { *; }
-keep class com.shashank.ghostly.BootReceiver { *; }
