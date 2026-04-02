# Enum class
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class icu.nullptr.playintegritybreak.data.UpdateData { *; }
-keep class icu.nullptr.playintegritybreak.data.UpdateData$* { *; }

-keep,allowoptimization class * extends androidx.preference.PreferenceFragmentCompat
-keepclassmembers class org.frknkrc44.pib_oss.databinding.**  {
    public <methods>;
}
