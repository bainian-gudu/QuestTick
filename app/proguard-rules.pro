# Keep Jakarta Mail / activation classes used reflectively.
# Upgraded 2026-06-27: javax.mail (android-mail 1.6.7) -> jakarta.mail (Angus 2.0.3)
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
-keep class org.eclipse.angus.mail.** { *; }
-keep class com.sun.mail.** { *; }
# Legacy javax.mail keep for migration period (can remove after 1 version)
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn javax.**
-dontwarn jakarta.**
-dontwarn com.sun.**
-dontwarn org.eclipse.angus.**
-dontwarn myjava.**

# OkHttp / Okio（收紧规则，仅保留必要反射调用）
-dontwarn okhttp3.**
-dontwarn okio.**

# 保留核心类，其余允许混淆
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Response { *; }
-keep class okhttp3.Call { *; }
-keep class okhttp3.Callback { *; }
-keep class okhttp3.Interceptor { *; }
-keep class okhttp3.RequestBody { *; }
-keep class okhttp3.ResponseBody { *; }

# ZXing QR code generation
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# AndroidX Security 已移除（2026-06-27 重构）
# 旧版 EncryptedSharedPreferences 迁移代码已删除，无需保留

# Data 模型类（仅保留必要序列化方法，其余允许混淆）
-keep class com.questtick.data.** {
    public <init>(...);
    public *** get*();
    public void set*(...);
    public static *** fromJson(...);
    public *** toJson(...);
}

# 显式保留核心模型
-keep class com.questtick.data.Account { *; }
-keep class com.questtick.data.TaskResult { *; }
-keep class com.questtick.data.RunRecord { *; }

# ---- R8 性能优化规则 ----

# 允许更激进的优化（5 轮 optimization pass）
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification

# 保留 Compose 运行时（避免激进优化破坏 Compose）
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# 移除 OkHttp 内部日志调用（release 构建）
-assumenosideeffects class okhttp3.internal.Util {
    public static *** log*(...);
}

# ---- Kotlin 2.4 / Hilt 2.59 兼容 ----
-dontwarn kotlin.reflect.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class jakarta.inject.** { *; }

# ---- Room 2.8.4 ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---- Coil 3.5 ----
-keep class coil3.** { *; }
-dontwarn coil3.**
