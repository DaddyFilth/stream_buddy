# ProGuard/R8 rules for Stream Buddy.
#
# R8 is currently disabled for the release build type (see app/build.gradle.kts).
# Enable it there once the release build has been validated on a device.

# --- Kotlin metadata / coroutines ---
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Media3 / ExoPlayer ---
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.** { *; }
-keepclassmembers class * implements androidx.media3.common.util.** { *; }

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- WorkManager ---
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
