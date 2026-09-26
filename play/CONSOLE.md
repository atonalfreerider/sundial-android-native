# Play Console walkthrough for Sundial 3.0.0 (14)

Every value to enter, in order. Files referenced are in this `play/` folder; the bundle is
`~/Desktop/SUNDIAL/release/sundial-3.0.0-14.aab`.

## 1. Create the app

Home → **Create app**

| Field | Value |
|---|---|
| App name | Sundial: Celestial Clock |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | Free |
| Declarations | Developer Program Policies ✓, US export laws ✓ |

## 2. Set up your app (Dashboard tasks)

- **Privacy policy:** https://primitive.io/legal/sundial-privacy/
- **App access:** All functionality is available without any access restrictions.
- **Ads:** No, my app does not contain ads.
- **Content rating:** email themetavirtuoso@gmail.com; category *Utility, Productivity,
  Communication, or Other*; answer **No** to violence, sexuality, language, controlled
  substances, crude humour, gambling, user interaction/sharing, location sharing, digital
  purchases, web browser. If asked about AI-generated content: **Yes** (on-device horoscopes,
  reportable in app).
- **Target audience:** 13–15, 16–17, 18 and over. Appeals to children: No.
- **News app:** No.
- **Data safety:** see `data-safety.md` (collected: diagnostics, other performance data and a
  device ID by Google ML Kit; optional reported reading text; encrypted in transit; nothing
  shared; deletion by email).
- **Advertising ID:** No (the app does not use it; the release manifest has no AD_ID permission).
- **Government apps:** No. **Financial features:** None. **Health:** None.

## 3. Store settings and listing

Grow → **Store presence** → Store settings

- Category: Lifestyle · Tags: astronomy, clock, calendar, wallpaper, astrology
- Contact email: themetavirtuoso@gmail.com · Website: https://primitive.io

Grow → Store presence → **Main store listing**

| Field | Source |
|---|---|
| App name | `listing/en-US/title.txt` |
| Short description | `listing/en-US/short-description.txt` |
| Full description | `listing/en-US/full-description.txt` |
| App icon | `graphics/icon-512.png` |
| Feature graphic | `graphics/feature-graphic-brass.png` (or `-crimson`) |
| Phone screenshots | `graphics/1-…` to `graphics/8-…` (in order) |
| 10-inch tablet screenshots | `graphics/tablet-1-…` to `graphics/tablet-4-…` (optional; helps the app show on tablets) |

## 4. Internal testing release

Test and release → Testing → **Internal testing**

1. Testers: create an email list with the Google accounts that will install it.
2. **Create new release.**
3. App integrity: **Use Google-generated app signing key** (Play App Signing). The upload key
   is `~/keys/sundial-upload.jks` (SHA-256
   `51:93:FA:0D:FB:06:C2:AF:13:1E:0B:76:A2:CF:77:7F:B9:3C:CF:A9:E8:F6:57:59:53:F2:CE:DE:BC:05:29:16`).
4. Upload `sundial-3.0.0-14.aab`. Release name: `3.0.0 (14)`.
5. Release notes (en-US): `listing/en-US/release-notes.txt`.
6. Save → Review release → **Start rollout to Internal testing**.
7. Open the tester opt-in link on the phone, uninstall any locally installed
   `com.metavirtuoso.sundial` build first (Play re-signs the app), then install from Play.

## 4b. Wear OS

1. Test and release → **Advanced settings → Form factors** → add **Wear OS** and accept the Wear OS
   requirements.
2. Store presence → Main store listing → **Wear OS screenshots**: `graphics/wear-1-…` to
   `graphics/wear-5-…` (1:1, 454 × 454).
3. Testing → **Internal testing** (Wear OS track) → create a release with
   `sundial-wear-3.0.0-1000014.aab`. Wear OS releases go through an extra Wear quality review.

## 4c. Watch face

The watch face is a separate app with its own listing: see `watchface/CONSOLE.md`.

## 5. Production

**Personal** developer accounts created after 13 November 2023 must first run a closed test
with at least 12 testers opted in for the 14 days before applying for production access
(https://support.google.com/googleplay/android-developer/answer/14151465); organisation
accounts can go straight to production. Promote the tested release, then send for review. The plan, tester invitation and draft
questionnaire answers are in `TESTING.md`.
