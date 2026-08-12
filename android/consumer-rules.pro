# Keep the plugin class and its @UsedByGodot methods. Godot invokes them by name
# via reflection at runtime, so R8/ProGuard in the consuming app must not rename
# or strip them. This travels inside the AAR (consumerProguardFiles), so any app
# using the plugin gets the rule automatically — no edits to the app's proguard.
-keep class com.aintdevs.inappupdate.** { *; }
