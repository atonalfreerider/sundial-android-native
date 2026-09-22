# Sundial — native Android rebuild

This is a clean-room native Android/Kotlin rebuild of the sibling Unity project. The Unity source is read-only reference material and is not part of this Gradle project.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Pixel/device verification

The device-side integration test reads the actual Android calendar provider. With the Pixel attached and Google Calendar synced:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.PrimeSoftwareSystems.Sundial android.permission.READ_CALENDAR
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew connectedDebugAndroidTest
```

The test prefers calendars whose account type is `com.google`, expands recurring instances for the current year, and validates IDs, exclusive end times, all-day date semantics, and event ordering on the real device.

## Calendar behavior

The app requests read-only calendar permission. Google calendars already synchronized by Android are read through `CalendarContract`; no Google password, OAuth token, or event write permission is used. Recurring events are expanded through `CalendarContract.Instances`, all-day boundaries remain calendar dates, and timed events are converted with `ZoneId` rules (including DST and non-hour offsets).

## Time model

- Civil dates use `java.time` and proleptic Gregorian leap-year rules.
- Rendering uses `Instant`/UTC for astronomical state and `ZonedDateTime` only for local calendar labels.
- Inner-planet positions use JPL's published J2000 Keplerian elements and rates.
- The annual ring uses the actual number of days in the displayed year.
- Earth rotation uses the IAU-compatible Greenwich mean sidereal time expression rather than a device-current-year offset.

## Controls

- Tap the Sun in the heliocentric dial to open the geocentric dial.
- Tap the Earth in the geocentric dial to return.
- Drag the Earth hand/year dial or Moon in the geocentric view to scrub time.
- Tap the top-left menu for clock, hemisphere, galactic view, and calendar selection.
- **DAILY HELIOCENTRIC WALLPAPER** renders the current heliocentric dial without app controls, applies it to the home screen, and refreshes it shortly after local midnight each day. Turn the switch off to cancel future updates.
- Tap **RESET CURRENT TIME** after scrubbing.

## Daily wallpaper

Wallpaper updates use Android's system wallpaper API and persistent WorkManager scheduling. The frame is rendered locally at the device's full display resolution; it does not require storage or network access. Android may defer the midnight refresh slightly to honor background battery policy.
