# FileDroid ProGuard rules

# Keep NanoHTTPD — uses reflection-style patterns and inner classes
-keep class com.filedroid.nanohttpd.** { *; }

# Keep data model classes used with Gson serialization
-keep class com.filedroid.model.** { *; }

# Keep server API handler inner response classes
-keep class com.filedroid.server.ApiHandler$* { *; }

# Gson: keep generic type signatures for TypeToken
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep Android entry points
-keep class com.filedroid.MainActivity { *; }
-keep class com.filedroid.TransferActivity { *; }
-keep class com.filedroid.SettingsActivity { *; }
-keep class com.filedroid.server.WebServerService { *; }

# Remove Log.d and Log.v in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
