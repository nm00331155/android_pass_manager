# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# SQLCipher
-keep class net.zetetic.** { *; }
-keep class net.sqlcipher.** { *; }

# Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Room
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep class com.securevault.app.data.db.entity.** { *; }
-keep class com.securevault.app.data.db.dao.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.securevault.app.**$$serializer { *; }
-keepclassmembers class com.securevault.app.** {
	*** Companion;
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponentManager

# BackupCredential (Serializable)
-keep class com.securevault.app.data.backup.BackupCredential { *; }
