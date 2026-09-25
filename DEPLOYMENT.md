# Deployment verification

## Pixel 10 — Android 17 / API 37

Device identifier: `57171FDCR00AMH`  
Physical display: 1080 × 2424 at 420 dpi

Verified on 2026-09-22:

- Debug APK installs successfully.
- `READ_CALENDAR` runtime permission grants successfully.
- Native activity launch smoke test passes.
- Real-device `CalendarContract`/Google calendar integration test passes.
- The provider exposed 13 calendar rows; the app correctly listed the nine visible, synchronized Google calendars without printing their names in test output.
- All 17 host-side astronomy and calendar tests pass.
- Android lint reports no findings.
- The initial Android 17 launch exposed an early window-insets access crash. Immersive-mode setup now runs after the decor view is attached, and the regression is covered by `MainActivityLaunchTest`.
- Heliocentric rendering, Sun-to-Earth navigation, geocentric rendering, the textured/lit Earth and shadow, lunar/day rings, reset-time, menu interactions, and a real calendar toggle were exercised successfully.
- Steady-state rendering sampled 211 frames with 0% jank, 6 ms median frame time, and 12 ms p99. Selecting and loading a calendar caused one transient slow frame over 1,201 frames (0.08%).
- No fatal exception or ANR appeared after the Android 17 fix.

Run the complete device suite:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.metavirtuoso.sundial android.permission.READ_CALENDAR
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  com.metavirtuoso.sundial.test/androidx.test.runner.AndroidJUnitRunner
```
