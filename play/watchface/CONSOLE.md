# Play Console walkthrough for the Sundial watch face 1.0.0 (1)

The watch face is its own app on Google Play: package `com.metavirtuoso.sundial.watchface`, a
Watch Face Format bundle with no code. Google Play will not accept a watch face in the same
bundle as an app's code, so it cannot share the Sundial listing. The bundle is
`~/Desktop/SUNDIAL/release/sundial-watchface-1.0.0-1.aab`; files below are in this folder.

## 1. Create the app

Home → **Create app**

| Field | Value |
|---|---|
| App name | Sundial: Celestial Watch Face |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | Free |
| Declarations | Developer Program Policies ✓, US export laws ✓ |

## 2. Set up your app

- **Privacy policy:** https://primitive.io/legal/sundial-privacy/ (after it gains the watch
  face paragraph in `../privacy-policy.md`).
- **App access:** All functionality is available without any access restrictions.
- **Ads:** No.
- **Content rating:** email themetavirtuoso@gmail.com; category *Utility, Productivity,
  Communication, or Other*; No to every content question. It has no AI-generated content.
- **Target audience:** 13–15, 16–17, 18 and over. Appeals to children: No.
- **News app:** No. **Government apps:** No. **Financial features:** None. **Health:** None.
- **Advertising ID:** No.
- **Data safety:** *Does your app collect or share any of the required user data types?* **No.**
  The package has no code and requests no permissions; the watch draws it from resources.

## 3. Form factor

Test and release → **Advanced settings → Form factors** → add **Wear OS**, accept the Wear OS
requirements, and remove Phone if it was added by default (this app has nothing for phones).

## 4. Store settings and listing

Grow → Store presence → Store settings

- Category: Personalization · Tags: watch face, astronomy, clock
- Contact email: themetavirtuoso@gmail.com · Website: https://primitive.io

Grow → Store presence → **Main store listing**

| Field | Source |
|---|---|
| App name | `listing/en-US/title.txt` |
| Short description | `listing/en-US/short-description.txt` |
| Full description | `listing/en-US/full-description.txt` |
| App icon | `graphics/icon-512.png` |
| Feature graphic | `graphics/feature-graphic.png` |
| Wear OS screenshots | `graphics/wear-face-1-…` to `graphics/wear-face-7-…` (1:1, 454 × 454, in order) |

## 5. Testing and release

1. Test and release → Testing → **Internal testing** → Create new release.
2. App integrity: **Use Google-generated app signing key**. Upload key: the same
   `~/keys/sundial-upload.jks` as the app (SHA-256
   `51:93:FA:0D:FB:06:C2:AF:13:1E:0B:76:A2:CF:77:7F:B9:3C:CF:A9:E8:F6:57:59:53:F2:CE:DE:BC:05:29:16`).
3. Upload `sundial-watchface-1.0.0-1.aab`. Release name `1.0.0 (1)`, notes from
   `listing/en-US/release-notes.txt`. Play validates the Watch Face Format on upload.
4. Roll out to internal testing, then open the tester link on the phone paired with the watch
   and install the face on the watch from Play. Choose it from the watch's face picker.
5. Like the app, a new app from a personal account needs a **closed test with 12 or more testers
   opted in for 14 days** before production (see `../TESTING.md`). Run it alongside the app's:
   the same tester group can join both, but each app has its own opt-in link.

Wear OS releases go through an extra Wear quality review.
