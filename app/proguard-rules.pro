# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Keep ML Kit Translate
-keep class com.google.mlkit.nl.translate.** { *; }
-keep class com.google.mlkit.vision.text.** { *; }

# Keep MediaProjection
-keep class android.media.projection.** { *; }

# General rules
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
