# البناء | Build

> **ملف APK المعتمد** يُبنى تلقائيًا على GitHub Actions بأدوات أندرويد الرسمية (Android SDK + AGP + R8)، عبر `.github/workflows/nitaaq-android.yml`. يمر البناء بالاختبارات البرمجية، ثم يُتحقق من التوقيع بـ`apksigner` ومن المحاذاة بـ`zipalign`، ثم يُحفظ الناتج في `dist/`. كل تعديل على الكود يُنتج APK جديدًا.
> التوقيع بمفتاح اختبار ثابت موجود في `app/signing/`، حتى تُثبَّت كل نسخة جديدة فوق السابقة. لنسخة توزيع خاصة استخدم مفتاحك الخاص عبر `NITAAQ_KEYSTORE`.

يوجد طريقان يبنيان **الكود نفسه** (`app/src/main`):

## 1) Android Studio / Gradle العادي (موصى به على جهازك)

المتطلبات: Android Studio حديث (أو JDK 17+ مع Android SDK فيه `platforms;android-35`).

```bash
cd NitaaqMirror
./gradlew assembleDebug            # APK اختبار: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # الاختبارات البرمجية (JVM)
```

إصدار موقّع للتوزيع (لا توجد مفاتيح في المستودع):
```bash
keytool -genkeypair -keystore nitaaq-release.p12 -storetype PKCS12 -alias nitaaq -keyalg EC -groupname secp256r1 -validity 10000
export NITAAQ_KEYSTORE=$PWD/nitaaq-release.p12 NITAAQ_KEYSTORE_PASS='...' NITAAQ_KEY_ALIAS=nitaaq
./gradlew assembleRelease
```

| المكوّن | الإصدار |
|---|---|
| Gradle (wrapper) | 8.14.3 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| com.google.zxing:core | 3.5.3 |
| junit (اختبارات) | 4.13.2 |
| AndroidX | غير مستخدم (واجهات أندرويد الأصلية فقط) |

> ملاحظة: مسار AGP هذا لم يُشغَّل في بيئة التطوير، لكنه يُشغَّل وينجح على GitHub Actions لأن مضيفي Google (`dl.google.com`, `maven.google.com`) كانا محجوبين فيها. الكود نفسه بُني واختُبر عبر المسار الثاني. ملفات Gradle قياسية، لكن إن ظهر خطأ إعداد في AGP فهو في ملفات البناء فقط، لا في الكود.

## 2) Lite build (بديل احتياطي؛ ملفات APK الناتجة عنه، 0.1.0 والنسخة الأولى من 0.1.1، رفض الجهازان تثبيتها)

يبني APK دون Android Gradle Plugin. استُخدم لأن بيئة التطوير لم تستطع الوصول إلى Google SDK/Maven.

```bash
cd NitaaqMirror/litebuild
./setup-sdk-lite.sh                       # مرة واحدة: يجهّز /opt/android-sdk-lite (مع تحقق SHA-256)
gradle assembleApk test verifyApk         # Gradle 8.14.x + JDK 17/21
# الناتج: litebuild/build/outputs/nitaaq-mirror.apk
```

خطوات المسار: `aapt2 compile/link` ← Kotlin (مقابل android.jar، مع `-no-jdk`) ← ProGuard 7.6.1 (تقليص فقط) ← `dx` ← zip مع محاذاة 4 بايت لـ`resources.arsc` ← توقيع v2 عبر `apksig` ← تحقق: `ApkVerifier` و`baksmali`، والتأكد من خلو كود التطبيق من `invokedynamic`.

| أداة | الإصدار | المصدر |
|---|---|---|
| JDK | 21.0.12 | النظام |
| Gradle | 8.14.3 | النظام |
| Kotlin Gradle plugin | 2.0.21 | Gradle Plugin Portal |
| android.jar (API 35) | AOSP android-35 | github.com/Reginer/aosp-android-jar |
| framework resources (API 35) | robolectric android-all 15-robolectric-12714715 | Maven Central |
| aapt2 | 2.20-15009934 | npm `aaptjs3@2.0.2` (Apache-2.0) |
| dx | 16.0.1 | Maven Central `com.jakewharton.android.repackaged:dalvik-dx` |
| apksig | 2.3.0 | Maven Central |
| ProGuard | 7.6.1 | Maven Central `com.guardsquare:proguard-base` |
| baksmali (تحقق فقط) | 2.5.2 | Maven Central |

قيود المسار الخفيف: لا يوجد R8. يُستخدم ProGuard للتقليص فقط، دون تعمية الأسماء ودون تحسين للكود، فيحذف ما لا يُستخدم من Kotlin stdlib (حجم APK نحو 600KB). مهمة `verifyApk` تُفشل البناء إن بقي أي `invokedynamic` في الـdex. كل عناصر الـzip مخزنة دون ضغط، ومحاذاة كل عنصر مسجلة في حقل extra قياسي (0xD935).

مفتاح التوقيع في هذا المسار مفتاح اختبار يُولَّد محليًا في `~/.nitaaq/debug.p12`، أو مفتاحك عبر `NITAAQ_KEYSTORE`. الإصدار المرفق موقّع بمفتاح اختبار من بيئة التطوير. لذلك إن بنيت نسخة بمفتاح مختلف، احذف النسخة القديمة من الجهاز قبل التثبيت.
