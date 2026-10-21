# Argon2 is looked up directly, keep the generator and its parameter classes.
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
