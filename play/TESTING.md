# Closed test for production access

MetaVirtuoso is a personal developer account created after 13 November 2023, so Google requires a
closed test with **at least 12 testers opted in for the 14 days** before production access can be
requested (https://support.google.com/googleplay/android-developer/answer/14151465). Twelve is
the minimum to apply, not a guarantee: Google also looks at whether testers actually use the app.

## Set up (Play Console)

1. Test and release → Testing → **Closed testing** → create a track (e.g. "Sundial testers").
2. Testers: add a **Google Group** (easiest to manage, e.g. `sundial-testers@googlegroups.com`) or
   an email list. Aim for 15–20 people so the count stays above 12 if anyone drops out.
3. Create a release on the track with the same bundle as internal testing and send it for review.
   The first review of a new app can take several days; the 14 days start once testers can opt in.
4. Share the **opt-in link** from the track's Testers tab. Each tester opens it on the Google
   account used on their Android phone, taps *Become a tester*, then installs from Play.
5. Leave everyone opted in for the full 14 days. Counting restarts if the total drops below 12.

## Invitation (ready to send)

> Subject: Help test Sundial, a celestial clock for Android
>
> I'm releasing Sundial on Google Play: a clock that shows the Sun, Earth, Moon and planets as
> one dial, with a fly-in Earth view, your calendar on the dials, and an optional astrology mode.
> Google asks new apps to be tested by at least 12 people for two weeks before launch — would you
> be one of them?
>
> 1. On your Android phone, open this link and tap "Become a tester": OPT_IN_LINK
> 2. Install Sundial from the Play Store link on that page.
> 3. Please stay opted in for 14 days and open the app a few times — tap the Sun, try the menus
>    in the corners, and if you like, turn on astrology mode.
>
> Tell me about anything odd by replying to this email (or themetavirtuoso@gmail.com).
> Thank you!

## What to ask testers to try

- Tap the Sun to fly to the Earth view and back; drag the Earth and the Moon to move time.
- Open each corner menu; try the Brass Watch aesthetic and the galactic axis.
- Calendar menu → allow access → turn on a calendar; hold an event on a dial.
- Astrology menu: turn on astrology mode, enter a birth date and time (a reading is written on
  supported devices such as recent Pixels); try reporting a reading.
- Optional: turn on the 15-minute celestial wallpaper.

## Applying for production access (draft answers)

- **How did you recruit testers?** Friends, family and colleagues invited by email and a Google
  Group, all using their own Android phones.
- **How easy was it to recruit?** Fill in after the test.
- **Describe the engagement you received.** Fill in: how many testers opened the app over the two
  weeks and what they tried.
- **What feedback did you receive and how did you act on it?** Fill in with the changes made
  (each fix goes out as a new closed-testing release with a higher versionCode).
- **Who is the intended audience?** People interested in astronomy, clocks and calendars; an
  optional, clearly labelled astrology mode for entertainment. Not directed at children.
- **How does the app provide value?** A live, accurate orrery clock with the year, day, Moon and
  planets on one instrument, private calendar overlays and a celestial wallpaper.
- **Expected installs in the first year:** your estimate.
