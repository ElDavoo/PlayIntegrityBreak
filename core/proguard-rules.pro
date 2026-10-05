# Hook logic runs inside the Play Store process and is reached through framework
# callbacks/reflection, which R8 cannot see.
-keep class icu.nullptr.playintegritybreak.core.** { *; }
