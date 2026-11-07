# SQLCipher uses JNI: native code calls these classes by name.
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }

# Strip all android.util.Log calls from release builds (no data in logcat).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
