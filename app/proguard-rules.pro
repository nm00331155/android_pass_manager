# Keep Room schema and generated classes
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase

# Keep SQLCipher JNI bridge classes
-keep class net.sqlcipher.** { *; }

# Keep Tink key material and registration classes
-keep class com.google.crypto.tink.** { *; }

# Keep Hilt generated entry points
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponentManager
