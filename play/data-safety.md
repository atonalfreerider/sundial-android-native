# Play Console answers: Data safety, content rating and app content

Draft answers based on what the release build actually contains (verified from the merged
release manifest: ML Kit GenAI Prompt API with Google datatransport logging, WorkManager,
calendar read, wallpaper, and the in-app reading report). Review before submitting.

## Data safety

**Does your app collect or share any of the required user data types?** Yes.

**Is all of the user data collected by your app encrypted in transit?** Yes (HTTPS).

**Do you provide a way for users to request that their data be deleted?** Yes — by email to
themetavirtuoso@gmail.com (reports). On-device data is deleted by clearing storage or uninstalling.

| Data type | Collected | Shared | Processed ephemerally | Required / optional | Purposes |
|---|---|---|---|---|---|
| App info and performance → Diagnostics | Yes (Google ML Kit) | No | No | Required | Analytics |
| App info and performance → Other app performance data | Yes (Google ML Kit) | No | No | Required | Analytics |
| Device or other IDs | Yes (Google ML Kit diagnostics identifier) | No | No | Required | Analytics |
| App activity → Other user-generated content (a reported AI reading) | Yes | No | No | Optional | App functionality |

Not collected (processed only on the device, never transmitted): calendar events, birth date and
time, horoscope text (unless the person reports it), location, contacts, personal info.

## Content rating (IARC questionnaire)

- Category: Utility, Productivity, Communication or Other (not a game).
- Violence, sexuality, language, controlled substances, gambling: none.
- Users interact or exchange content with each other: no.
- Shares the user's location with others: no.
- Allows purchases of digital goods: no.
- Contains AI-generated content: yes — on-device horoscopes, entertainment only, reportable in app.

## App content declarations

- **Privacy policy URL:** https://primitive.io/legal/sundial-privacy/
- **Ads:** the app contains no ads.
- **App access:** all functionality is available without special access or login.
- **Target audience:** 13 and over (not designed for children; astrology uses generative AI).
- **News app:** no. **Health app:** no. **Financial features:** none. **Government app:** no.
- **Data safety:** as above.
- **Permissions:** READ_CALENDAR is used for the core calendar-on-the-dials feature (not a
  restricted permission; no declaration form needed).

## Store settings

- App or game: App. Free.
- Suggested category: Lifestyle (alternatives: Education, Personalization).
- Tags: astronomy, clock, calendar, wallpaper, astrology.
