# Looked up by name from native code (main.cpp).
-keep class icu.nullptr.playintegritybreak.zygisk.ZygiskEntry {
    public static void main();
}
-keep class icu.nullptr.playintegritybreak.zygisk.LSPlantBridge {
    native <methods>;
}
# Called by LSPlant's generated stubs.
-keep class icu.nullptr.playintegritybreak.zygisk.LSPlantHookBackend$Hooker {
    public java.lang.Object callback(java.lang.Object[]);
}
-dontwarn java.lang.invoke.StringConcatFactory
# Hidden framework classes used by :common; they exist at runtime in the Play Store process.
-dontwarn android.os.SystemProperties
