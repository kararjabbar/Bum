# ====================================================
# Proguard Rules — Būm v1.2
# تشويش قصوى: كل شيء يُشوَّش إلا ما يحتاجه النظام
# ====================================================

# ─── 1. الحد الأدنى الضروري للنظام ────────────────

# Android يحتاج يعرف اسم Application class بالـ AndroidManifest
-keep class com.bum.app.BumApplication { *; }

# الـ Activities/Services/Receivers — Android يحتاج أسماءها للـ manifest
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ─── 2. البيانات (Models) — يحتاجها JSON serialization ──

-keep class com.bum.app.models.** { *; }

# ─── 3. AndroidX Biometric — يستخدم Reflection داخلياً ──

-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# ─── 4. Security-Crypto / Keystore ──────────────────────

-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ─── 5. WorkManager ──────────────────────────────────────

-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class androidx.work.WorkerParameters

# ─── 6. CameraX ──────────────────────────────────────────

-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ─── 7. Kotlin ────────────────────────────────────────────

# Coroutines — تحتاج volatile fields
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# Kotlin Intrinsics — لا تحذفها (تسبب NullPointerException)
-keepclassmembers class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(...);
    static void checkParameterIsNotNull(...);
}

# ─── 8. Parcelable / Serializable ────────────────────────

-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

# ─── 9. Enums ────────────────────────────────────────────

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── 10. حذف كل Logging في Release ─────────────────────

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# ─── 11. إعدادات التشويش والتحسين ───────────────────────

# 5 passes = تشويش أعمق وأكثر تداخلاً
-optimizationpasses 5

# أسماء الكلاسات بحروف صغيرة فقط (a, b, c...) — أصعب للقراءة
-dontusemixedcaseclassnames

# لا تتجاوز المكتبات الخارجية
-dontskipnonpubliclibraryclasses

# خوارزميات التحسين — نُعطّل فقط ما يسبب مشاكل مع Kotlin
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# ─── 12. تحذيرات شائعة يمكن تجاهلها بأمان ─────────────

-dontwarn com.google.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ─── 13. ملاحظة أمنية ────────────────────────────────────
# تم حذف هذه القواعد الخاطئة من النسخة السابقة:
#   -keep class com.bum.app.security.** { *; }   ← كانت تمنع تشويش كود الأمان!
#   -keep class com.bum.app.ui.** { *; }         ← كانت تكشف كل منطق الواجهة!
# الآن: الأمان والـ UI يُشوَّشان بالكامل كبقية الكود.
