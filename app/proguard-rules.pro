# Project-specific R8 rules. Library rules arrive through consumer files.

# SQLCipher is reached through JNI; its classes must keep their names.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**
