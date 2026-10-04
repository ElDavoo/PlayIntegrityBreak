# Looked up by name by ZygoteLoader's EntryPoint (module.prop "entrypoint").
-keep class icu.nullptr.playintegritybreak.zygisk.ZygiskEntry {
    public static void premain();
    public static void main();
}
-dontwarn java.lang.invoke.StringConcatFactory
# Hidden framework classes used by :common; they exist at runtime in the Play Store process.
-dontwarn android.os.SystemProperties
