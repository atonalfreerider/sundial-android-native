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

## Wear OS

The watch app is `:wear`, published in the same listing with the same package and upload key.
Its versionCode sits in its own range (1,000,014 for 3.0.0) so it never collides with the
phone's. Build it with:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :wear:bundleRelease
```

The bundle is `wear/build/outputs/bundle/release/wear-release.aab`. Wear OS store screenshots
(1:1, round) come from `WatchStoreCapture` on a watch emulator:

```bash
adb shell am instrument -w -e storeAssets true -e class com.metavirtuoso.sundial.wear.WatchStoreCapture com.metavirtuoso.sundial.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.metavirtuoso.sundial/files/store-wear/. play/graphics/
```

## Watch face

The watch face is `:watchface`, a Watch Face Format face (resources only, format version 1, Wear
OS 4 and later) published as its own app, `com.metavirtuoso.sundial.watchface`, with the same
upload key. Play will not take a watch face in the same bundle as app code. Walkthrough and
listing: `play/watchface/`.

Its images are drawn by the instrument itself (`SundialView.drawWatchFaceLayer`), and
`watchface/tools/generate.py` turns them into `res/raw/watchface.xml`, whose expressions move the
Earth, planets, Moon and globe. After changing the instrument's look, rebuild them with a Wear
OS emulator running (the 454 px round one):

```bash
watchface/tools/build-assets.sh
```

This renders the layers, regenerates the face, checks every expression against the
instrument's astronomy (`WatchFaceReferenceTest`), and runs Google's format validator and
memory-footprint check (`wff-validator.jar` and `memory-footprint.jar` from
https://github.com/google/watchface/releases, in `~/Android/wff-tools`). The generated images
and XML are committed, so a normal build needs none of this. Build the bundle with:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :watchface:bundleRelease
```

It is `watchface/build/outputs/bundle/release/watchface-release.aab`. Try a build on the emulator
with `adb install`, then
`adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --es watchFaceId com.metavirtuoso.sundial.watchface`.

Things the watch's renderer does that the format's documentation does not say, all handled by
the generator: groups need unique names; a setting nested in another setting's option does not
follow changes, so astrology mode is read with `[CONFIGURATION.astrology]`; `?:` binds tighter
than comparisons; `%` fails on negative numbers; and `[UTC_TIMESTAMP]` makes the face redraw ten
times a second, so time is counted from the local date and `[TIMEZONE_OFFSET_DST]` instead.

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
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test :app:bundleRelease :wear:bundleRelease
   ```

   The bundles are `app/build/outputs/bundle/release/app-release.aab` (phone) and
   `wear/build/outputs/bundle/release/wear-release.aab` (watch). Raise the watch versionCode with
   the phone's.
3. Upload it to the internal testing track first, install from Play on a device, then promote
   to production.

Console answers for Data safety, content rating and app content are drafted in
`play/data-safety.md`; `play/CONSOLE.md` walks through the Console field by field, and `play/TESTING.md` covers the
12-tester closed test this personal account needs before production.
