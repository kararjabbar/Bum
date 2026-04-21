# Būm — تطبيق الانتحار الرقمي

```
اكتب. أغلق. يموت.
```

**الإصدار الحالي: 1.2.0** · `versionCode 2`

---

## 📱 الفكرة

**Būm** هو تطبيق تواصل اجتماعي مع النفس، ومساحة آمنة للأفكار العابرة.
كتابة سريعة — نص، صورة، أو مرفق — تُشفَّر فوراً بـ AES-256-GCM وتعيش
بعمر محدّد سلفاً (ساعة / يوم / أسبوع / دائم / تموت عند الإغلاق).
لا سحابة. لا إنترنت. لا أرشيف خارج جهازك.

---

## 🧱 البنية العامة

```
Bum/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/bum/app/
│       │   ├── BumApplication.kt
│       │   ├── models/
│       │   │   ├── Note.kt              ← نموذج الملاحظة + Attachment
│       │   │   └── VaultKey.kt
│       │   ├── security/
│       │   │   ├── SecureDataManager.kt ← قلب الأمان (AES-256-GCM)
│       │   │   ├── LifecycleObserver.kt ← المحو/القفل عند الخروج
│       │   │   ├── BootReceiver.kt      ← المحو عند إعادة التشغيل
│       │   │   ├── RootDetector.kt
│       │   │   └── ScreenshotBlocker.kt
│       │   ├── ui/
│       │   │   ├── SplashActivity.kt
│       │   │   ├── BiometricActivity.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── WriteActivity.kt
│       │   │   ├── EncryptedNotesActivity.kt
│       │   │   ├── VaultActivity.kt
│       │   │   ├── SettingsActivity.kt
│       │   │   └── AttachmentViewerActivity.kt  ← عارض مدمج (v1.2)
│       │   └── utils/
│       │       ├── AppSettings.kt
│       │       ├── DateUtils.kt
│       │       ├── ImmersiveHelper.kt
│       │       ├── PasswordGenerator.kt
│       │       ├── ThemeGlow.kt
│       │       └── ThemeManager.kt
│       └── res/ (layouts, drawables, anims, values, xml)
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md   ← هذا الملف
```

---

## 🧩 الأقسام الوظيفية داخل التطبيق

| القسم | ماذا يحوي | كيف يُدخل إليه |
|---|---|---|
| **القائمة الرئيسية** (MainActivity) | لحظات عابرة (نص/صورة/مرفقات) | فتح التطبيق مباشرةً |
| **👻 القسم الشبحي** (داخل القائمة الرئيسية) | ملاحظات لا تظهر أبداً في القائمة | كتابة "الكلمة السرية" في شريط البحث |
| **🔐 القسم المشفّر المنفصل** (EncryptedNotesActivity) | ملاحظات بعناوين واضحة قبل الفتح | زر مستقل + مصادقة بصمة/كلمة مرور |
| **🗝 مخزن المفاتيح** (VaultActivity) | كلمات مرور وAPI Keys دائمة | زر مستقل + مصادقة |

---

## 🔐 ما الذي يشفَّر؟

- **كل** محتوى ملاحظة (نص + عنوان + صورة) → AES-256-GCM.
- **كل** مرفق يُلحَق بملاحظة → يُستنسَخ إلى ByteArray ثم يُشفَّر ويحفظ في `enc_attachments/` داخل Sandbox التطبيق.
- **كل** إعداد حساس (كلمة مرور احتياطية، الكلمة السرية للشبحيات) → SHA-256 (hash فقط، لا plaintext).
- المفتاح الجذر (Master Key) يدار بواسطة `EncryptedSharedPreferences` فوق Android Keystore.

---

## 🧷 الميزات الأمنية الأساسية

| الميزة | التفصيل |
|---|---|
| **FLAG_SECURE** | يمنع لقطة الشاشة وتسجيل الفيديو في كل الشاشات (قبل `setContentView`). |
| **قفل عند الخروج** | فترة سماح قابلة للتخصيص (15–600 ثانية) قبل إعادة طلب البصمة. |
| **المحو المجدول** | يمسح الملاحظات المنتهية + "تموت عند الإغلاق" تلقائياً. |
| **المحو عند الإقلاع** | `BootReceiver` يمسح الملفات المؤقتة عند إعادة تشغيل الجهاز. |
| **كشف Root** | يمنع تشغيل التطبيق على جهاز مكشوف. |
| **بدون INTERNET** | لا إذن شبكة، لا سحابة، `usesCleartextTraffic="false"`. |
| **Data Extraction Rules** | لا نسخ احتياطي، لا backup سحابي. |

---

## 📎 بروتوكول المرفقات — "الاستنساخ والتشفير" (Clone & Encrypt)

عندما يختار المستخدم ملفاً من منتقي الملفات (`OpenMultipleDocuments`)، يجري التطبيق الخطوات التالية **فوراً**:

1. **فتح تيار البيانات** — `contentResolver.openInputStream(uri).readBytes()`
   → قراءة الملف الأصلي كـ `ByteArray` كاملة في الذاكرة.
2. **التشفير اللحظي** — `SecureDataManager.encryptAttachmentBytes()`
   يشفّر الـ `ByteArray` بـ AES-256-GCM + IV عشوائي لكل ملف.
3. **التخزين في الـ Sandbox** — حفظ النسخة المشفّرة في
   `filesDir/enc_attachments/<attId>.enc` (مجلد خاص بالتطبيق لا تصله تطبيقات أخرى).
4. **الاستقلال التام** — النسخة داخل Būm تصبح **كياناً مستقلاً**.
   حذف الملف الأصلي من معرض الصور لا يؤثر عليها.
5. **الحد الأمني** — أي ملف > 25 MB يُتجاوز لتفادي OOM.

> **ملاحظة:** حجم المرفق الظاهر للمستخدم هو الحجم الأصلي (قبل التشفير)، أما الملف على القرص فأكبر قليلاً بسبب `IV (12 bytes)` و`GCM tag (16 bytes)` لكل ملف.

---

## 🖼 العارض المدمج (Native In-App Viewer)

بدل تصدير المرفق عبر `FileProvider` (ما يُعرّضه لتطبيقات أخرى)، نعرضه داخل `AttachmentViewerActivity`:

- **فك التشفير إلى RAM فقط** — `decryptAttachmentToBytes()` يُرجع `ByteArray` ولا يكتب أي ملف على القرص.
- **عرض الصور** — `BitmapFactory.decodeByteArray` بدقة كاملة (ARGB_8888، بلا `inSampleSize`).
- **عرض النصوص** — `text/*`, `application/json`, `application/xml` → كـ `String`.
- **FLAG_SECURE** مُفعّل (عبر `BaseSecureActivity`) → لا لقطات شاشة.
- **بعد الخروج**: نُفرغ المراجع (`decryptedBytes = null`) ونستدعي `System.gc()` لتسريع إزالة البيانات من الذاكرة.

### الأنواع غير المدعومة للعرض المدمج
`application/pdf`, `video/*`, `audio/*`, `application/zip`, … → يُعرض زر **التصدير العكسي** فقط.

---

## 📤 التصدير العكسي (Secure Export)

لأن الملف الأصلي قد يكون حُذف، يوفّر التطبيق زر **تصدير (⬇)** داخل العارض المدمج:

- يستخدم **Storage Access Framework** (`ACTION_CREATE_DOCUMENT`).
- المستخدم يختار بنفسه مكان الحفظ (Downloads عادة).
- نفكّ التشفير إلى `ByteArray` ونكتبه إلى الـ `Uri` المختار مباشرة.
- **لا FileProvider، لا cache، لا أذونات خارجية.** الملف يخرج من Sandbox فقط بطلب صريح من المستخدم.

---

## 👻 الملاحظة الشبحية (The Ghost Note) — ميزة v1.2

**المشكلة:** أحياناً تحتاج لكتابة ملاحظة سريعة (رقم هاتف، عنوان) وأنت في عجلة، ولا تريدها أن تظهر في القائمة الرئيسية أمام أي متطفل.

**الحل:**

1. في شاشة الكتابة، اضغط زر **👻** في الشريط العلوي لتفعيل "وضع الشبح".
2. احفظ الملاحظة كالمعتاد — تتخزَّن مشفّرة، لكنها **لا تظهر في القائمة الرئيسية أبداً**.
3. لاستعادتها: افتح القائمة الرئيسية واكتب **الكلمة السرية** في شريط البحث
   (افتراضياً `0000`).
4. لتغيير الكلمة السرية: **الإعدادات → 👻 كلمة الملاحظة الشبحية → تغيير**.

**الأمان:**
- الكلمة السرية تُخزَّن كـ **SHA-256 hash فقط** (ليست plaintext).
- المقارنة تتم hash مقابل hash → لا كشف للسر حتى أمام مطوّري التطبيق.
- الملاحظات الشبحية **مشفّرة مثل غيرها تماماً** (AES-256-GCM). العلم `isGhost` يؤثر فقط على ظهورها في الواجهة.
- `getRegularNotes()` و `getSecretCompartmentNotes()` **يستبعدان** الشبحيات.
- الكشف يحدث حصراً عبر `revealGhostNotesIfCodeMatches(typed, expectedHash)`.

---

## ✏️ سياسات انتهاء الصلاحية

```kotlin
enum class ExpiryPolicy {
    ONE_HOUR       // ساعة واحدة
    ONE_DAY        // يوم واحد (الافتراضي)
    ONE_WEEK       // أسبوع
    PERMANENT      // دائم — حتى الحذف اليدوي
    ON_APP_CLOSE   // يموت عند الإغلاق
}
```

- **تدمير بعد القراءة** (`destroyOnRead`): خيار إضافي يحذف الملاحظة بعد أول فتح.
- المحو الانتقائي يعمل كل ثانية (Ticker) في الواجهة الرئيسية.
- عند الخروج من التطبيق، `LifecycleObserver` ينفّذ المسح المجدول عند انتهاء فترة السماح.

---

## 🎨 الثيمات

| الثيم | الوصف |
|---|---|
| Default | خلفية `#0A0A0F`، لهب `#FF4D2D` |
| AMOLED | أسود مطلق `#000000` لتوفير الطاقة |
| Light | وضع نهاري كامل |

**تبديل الثيم:** الإعدادات → الثيم (قائمة منسدلة) — يُعاد بناء الواجهة فوراً.

---

## 🎞 نمط الحركة (Motion — v1.2)

حُدِّدت كل الأنيميشن لتكون **ناعمة جداً وغير مزعجة**:

| الحركة | الإعداد (v1.2) |
|---|---|
| `scale_bounce` (ضغطة زر) | 1.0 → 1.015، مدة 60+80 ms |
| `fade_in_scale` (ظهور) | Alpha 0→1 + scale 0.98→1.0، 220 ms |
| `slide_up_fade` (دخول شاشة) | Y: 10dp → 0، 180 ms |
| `slide_down_fade` (خروج) | Y: 0 → 16dp، 200 ms |
| `shake` (خطأ في الحفظ) | ±5dp، دورتان فقط، 320 ms |
| `pulse` (مؤشر قفل) | Alpha 1.0 ↔ 0.7، 1100 ms لكل دورة |
| `animateEntrance` (MainActivity) | إزاحة 8dp، stagger 30ms، مدة 220ms |

> الهدف: شعور هادئ ومتقن بدون إزعاج بصري.

---

## ⚙️ الإعدادات المتاحة (SettingsActivity)

1. **الثيم** — Default / AMOLED / Light.
2. **فترة السماح قبل القفل** — 15 إلى 600 ثانية (افتراضي 90).
3. **الصلاحية الافتراضية** — السياسة المُحددة سلفاً عند كتابة ملاحظة جديدة.
4. **"تدمير بعد القراءة" افتراضياً** — مفتاح تبديل.
5. **مسح كل شيء عند الإغلاق** — مفتاح تبديل.
6. **كلمة المرور الاحتياطية** — لإعادة المصادقة إذا فشلت البصمة.
7. **👻 كلمة الملاحظة الشبحية** — لتغيير الكلمة الافتراضية `0000`.
8. **منطقة الخطر** — مسح كامل لكل البيانات.

---

## 📦 التبعيات الرئيسية

```gradle
androidx.security:security-crypto   // EncryptedSharedPreferences + MasterKey
androidx.biometric:biometric         // بصمة + قفل الجهاز
androidx.camera:camera-*             // كاميرا داخل التطبيق
androidx.work:work-runtime           // محو مجدول
androidx.lifecycle:lifecycle-*       // مراقبة دورة حياة التطبيق
```

- `minSdk 26` (Android 8.0) · `targetSdk 34` · `compileSdk 34`
- Kotlin + ViewBinding + BuildConfig + ProGuard (release).

---

## 🧪 كيف يعمل فلو المصادقة

```
SplashActivity
    ↓
BiometricActivity  ← بصمة أو كلمة مرور احتياطية
    ↓
MainActivity  ← الواجهة الرئيسية
    ├── btn_write       → WriteActivity
    ├── btn_vault       → (مصادقة) → VaultActivity
    ├── btn_secret      → (مصادقة) → EncryptedNotesActivity
    ├── btn_settings    → SettingsActivity
    └── et_search       → كشف الملاحظات الشبحية (عبر الكلمة السرية)
```

عند الخروج، `LifecycleObserver.sessionLocked` يصبح `true` بعد `gracePeriodSeconds`. أي `Activity.onResume` يُعيد توجيه المستخدم إلى `BiometricActivity`.

---

## 📝 ملخص التغييرات في الإصدار الحالي 1.2

- ✅ **ميزة "الملاحظة الشبحية"** — زر 👻 في شاشة الكتابة + شريط بحث + كلمة سرية في الإعدادات.
- ✅ **العارض المدمج للمرفقات** — AttachmentViewerActivity داخل Sandbox.
- ✅ **التصدير العكسي** — SAF بدون FileProvider.
- ✅ **توثيق بروتوكول الاستنساخ والتشفير** — موجود تقنياً مسبقاً، موثَّق الآن.
- ✅ **تلطيف الأنيميشن بالكامل** — قيم مخفَّضة في كل ملفات `anim/` والكود.
- ✅ **توحيد التوثيق** — ملف README واحد بدل ملفات CHANGES المتعددة.

### ✨ تحديثات ميزات العارض (ضمن 1.2):

- 🛡️ **إصلاح الكراش بعد فتح الملفات** — حماية دفاعية شاملة (try/catch على كل نقطة حرجة) + حساب `inSampleSize` ذكي للصور الكبيرة لمنع OOM.
- 👆 **Auto-Hide Controls أنعم** — نقرة واحدة على الشاشة = toggle سلس (ظهور مع fade in، نقرة ثانية تختفيها بوضوح). مدة الحركة 280ms مع Decelerate interpolator.
- ⏪⏩ **Video Overlay مطوّر** — أزرار جديدة:
  - ⏪ **إرجاع 5 ثواني**
  - ⏩ **تقديم 5 ثواني**
  - ⏩⏩ **ضغط مطوّل على الشاشة (500ms+)** = تسريع ×2 (مع اهتزاز خفيف)، يرجع للعادي عند الإفلات.
- 📄 **عارض PDF مطوّر بالكامل** — تمرير عمودي سلس (نصف صفحة + نصف التالية كما في Adobe/Drive) بدلاً من `ViewPager2` المحدّد بصفحة كاملة، مع **Pinch-to-Zoom (1.0x - 4.0x)** و **double-tap zoom** و pan أفقي أثناء التكبير.

---

## 🚀 البناء

```bash
./gradlew clean assembleRelease
```

- APK الناتج: `app/build/outputs/apk/release/app-release.apk`
- موقَّع بـ keystore خاص بك (غير مضمَّن في المستودع).

---

## 📜 الترخيص

مشروع شخصي للاستخدام الخاص. لا ينسخ أي بيانات خارج الجهاز.
