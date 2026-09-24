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

- Tap the Sun in the heliocentric dial to fly in to the geocentric dial; tap the Earth to fly back out.
- Drag the Earth hand/year dial or Moon in the geocentric view to scrub time.
- In the geocentric view, the local wheel around the globe points its long, red-tipped tooth at the selected zone's local time. Tap the wheel to choose another zone; the translucent strip spans the zones already on the new date.
- In the galactic view, drag anywhere to slide the ribbon of years under the Sun (there is no end to it); tap to return.
- The corner buttons unfold tuck menus: **settings** (top left: clock, hemisphere, galactic view, aesthetic, wallpaper), **calendars** (bottom left) and **astrology** (bottom right). Tap outside, the button again, or Back to tuck a menu away.
- The astrology menu holds every astrology input: astrology mode, birth date and time typed inline, and the sun sign. With astrology on and a valid birth date and time, today's private on-device horoscope is written automatically (again each new day, or when birth details change).
- In the solar view the Earth carries its own subdial, like the silver gear on an astrological watch: a 24-hour sprocket ring with noon toward the Sun and an enlarged Moon on its lunar track. Astrology mode draws the planets as their classical symbols (☿ ♀ ⊕ ♂ and the lunar crescent).
- **15-MIN CELESTIAL WALLPAPER** renders the live Earth-centered dial without app controls and refreshes it every 15 minutes. It targets the lock screen by default; disable **APPLY TO LOCK SCREEN** to use the home screen instead.
- **AESTHETIC** offers Void Black, Crimson Nebula, Deep Space Blue, Cosmic Violet, Solar Bronze and Brass Watch, which engraves the instrument into a polished brass watch face in both astronomy and astrology modes. The chosen aesthetic is shared by the app and generated wallpaper.
- Tap **RESET CURRENT TIME** after scrubbing.

## Celestial wallpaper

Wallpaper updates use Android's system wallpaper API and persistent WorkManager scheduling. The Earth-centered frame is rendered locally at the device's full display resolution; it does not require storage or network access. Android may defer individual 15-minute refreshes slightly to honor background battery policy.
