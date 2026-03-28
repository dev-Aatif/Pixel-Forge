# ProGuard rules for PixelForge

# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep native bridge
-keep class com.pixelforge.app.native.** { *; }

# Hilt
-dontwarn dagger.hilt.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose
-dontwarn androidx.compose.**
