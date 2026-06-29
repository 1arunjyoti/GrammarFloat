# ── OkHttp & Okio ────────────────────────────────────────────────────────────
# Both ship their own consumer rules via AAR — no manual rules needed.
-dontwarn okhttp3.**
-dontwarn okio.**

# ── kotlinx-serialization ────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,allowobfuscation,allowoptimization class kotlinx.serialization.** { *; }

# Keep all @Serializable annotated classes and their generated serializers
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class **$$serializer { *; }

# ── Accessibility Service ─────────────────────────────────────────────────────
# Referenced by name in the manifest and in SettingsActivity class name check.
-keep class app.grammarfloat.pro.GrammarAccessibilityService { *; }

# ── Android Keystore / JCA ────────────────────────────────────────────────────
-keep class android.security.keystore.** { *; }
-keepclassmembers class * extends java.security.Provider { *; }

# ── API Models ────────────────────────────────────────────────────────────────
-keep class app.grammarfloat.pro.api.ModelDefaults { *; }

# ── Logging ───────────────────────────────────────────────────────────────────
# Strip verbose/debug/info logs in release. Keep warn and error for diagnostics.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
