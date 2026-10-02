# Google Play release 3.1.0

Published to the existing internal-testing tracks on 2 October 2026:

| App | Package | Play release | Status |
|---|---|---|---|
| Phone | `com.metavirtuoso.sundial` | `15 (3.1.0)` | Available to internal testers |
| Wear OS | `com.metavirtuoso.sundial.watchface` | `1000015 (3.1.0)` | Available to internal testers |

The uploaded bundles were built from the 3.1.0 source plus the post-release Wear application-ID
correction committed immediately after publishing. The immutable `v3.1.0` tag remains on the
original release commit; this correction is recorded in the next commit on `main`.

Bundle SHA-256 values after the correction:

- Phone: `ee9e47ee6c1cd8fbdddc5c9d881f0db5c4d259e64f76c66cc89b1456bb2a6ea9`
- Wear OS: `a9e633f832e385f8668ced55e0470dce40ff6292a8a19db001999eab12608055`

Open and closed testing were inactive before this release. Production was not submitted because
each app had 0 of the required 12 closed-test opt-ins and had not completed the required 14-day
closed test. Follow `TESTING.md` to start both campaigns.
