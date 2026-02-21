# Lottery Analyzer ProGuard Rules

# Сохраняем ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Сохраняем модели распознавания
-keepclassmembers class * {
    @com.google.mlkit.** <methods>;
}

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# AndroidX
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# Сохраняем Activity
-keep class com.lottery.analyzer.** { *; }
-keep class com.lottery.analyzer.MainActivity { *; }
-keep class com.lottery.analyzer.CameraActivity { *; }

# ViewBinding
-keepclassmembers class * extends androidx.viewbinding.ViewBinding {
    public static *** bind(...);
    public static *** inflate(...);
}

# R8 оптимизации
-allowaccessmodification
-optimizations !code/simplification/*,!field/*,!class/merging/*
-optimizationpasses 5
