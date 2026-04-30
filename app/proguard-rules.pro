# Drive Player ProGuard rules

# Keep data model classes used by Gson/Retrofit
-keep class com.driveplayer.data.model.** { *; }
-keep class com.driveplayer.ui.cloud.SavedAccount { *; }

# Keep generic signatures and annotations (required by Retrofit and kotlinx.serialization)
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# Retrofit
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# kotlinx.serialization
-keepclassmembers class **$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }

# OkHttp (platform calls use reflection)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# libVLC — JNI bridge looks up these classes/methods by name from native code.
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**

# Google Sign-In
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**
