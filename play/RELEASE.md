# Releasing Sundial on Google Play

Package: `com.metavirtuoso.sundial` (permanent once the first bundle is uploaded).
Developer account: https://play.google.com/console/u/0/developers/7858859067911254186

## One-time setup

1. **SDK 36.** Google Play requires new apps and updates to target Android 16 (API 36) from
   31 August 2026. Install the Android 16 platform, then raise `compileSdk` and `targetSdk` to 36
   in `app/build.gradle.kts`.
2. **Upload key.** Create it once and back it up somewhere safe (it is not in git):

   ```bash
   keytool -genkeypair -v -keystore ~/keys/sundial-upload.jks -alias sundial-upload -keyalg RSA -keysize 4096 -validity 10000
   ```

   Then create `keystore.properties` next to `settings.gradle.kts` (git-ignored):

   ```properties
   storeFile=/home/john/keys/sundial-upload.jks
   storePassword=…
   keyAlias=sundial-upload
   keyPassword=…
   ```

   Enrol in Play App Signing when creating the release: Google holds the app signing key and
   this key only uploads.
3. **Reading reports.** Play's AI-generated content policy requires in-app reporting. Reports
   go to the "Sundial App Reading Reports" Google Form (`sundial.reportEndpoint` and
   `sundial.reportFields` in `gradle.properties`). The Play bundle will not build without them.
4. **Privacy policy.** Published at https://primitive.io/legal/sundial-privacy/ from the
   Primitive landing site (`PRIMITIVE/landing/src/legal/sundial-privacy.md`); keep it in step
   with `play/privacy-policy.md`. Contact: themetavirtuoso@gmail.com.

## Store assets

Rendered from the app itself, with sample data only:

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e storeAssets true -e class com.metavirtuoso.sundial.store.StoreAssetsCapture com.metavirtuoso.sundial.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.metavirtuoso.sundial/files/store play/graphics
```

This produces the 512 × 512 icon, 1024 × 500 feature graphics and 1080 × 2160 phone
screenshots. Listing text is in `play/listing/en-US/`.

## Each release

1. Raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.
2. Build and test:

   ```bash
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test bundleRelease
   ```

   The bundle is `app/build/outputs/bundle/release/app-release.aab`.
3. Upload it to the internal testing track first, install from Play on a device, then promote
   to production.

Console answers for Data safety, content rating and app content are drafted in
`play/data-safety.md`; `play/CONSOLE.md` walks through the Console field by field, and `play/TESTING.md` covers the
12-tester closed test this personal account needs before production.
