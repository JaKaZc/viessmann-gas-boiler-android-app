# Google Play: first release (1.0.0)

Everything needed for the first upload of **ViGasBoiler** (`com.vigasboiler.app`, versionCode 1,
versionName 1.0.0, targetSdk 37, minSdk 28).

| File | Where it goes in Play Console |
|---|---|
| `app-live-release.aab` (git-ignored) | Test and release → a track → Create release → App bundles |
| `listing/en-US/title.txt` (27/30) | Grow users → Store presence → Main store listing → App name |
| `listing/en-US/short-description.txt` (77/80) | Main store listing → Short description |
| `listing/en-US/full-description.txt` (~3300/4000) | Main store listing → Full description |
| `listing/en-US/release-notes-1.0.0.txt` | Create release → Release notes (paste as is, with the `<en-US>` tags) |
| `graphics/icon-512.png` | Main store listing → App icon (512×512) |
| `graphics/feature-graphic-1024x500.png` | Main store listing → Feature graphic |
| `graphics/screenshots/phone/*.png` | Main store listing → Phone screenshots (1080×1920, captioned) |
| `graphics/screenshots/phone-plain/*.png` | Alternative: the same screens without captions (1080×2160) |
| `privacy-policy.html` | Host it publicly, then App content → Privacy policy (URL) |

Upload the screenshots in file-name order (01…08). Play needs 2 to 8 phone screenshots; all 8
are fine. Use either the captioned or the plain set, not both.

## Before the first upload

1. **Put your contact e-mail in `privacy-policy.html`** (replace both `CONTACT_EMAIL`), then host
   it at a public HTTPS address, for example GitHub Pages, Google Sites or your own domain. Play
   needs the URL, not the file.
2. **Back up the upload key.** `upload-keystore.jks` and `keystore.properties` in the project
   root (both git-ignored) hold the key and its password. Copy both to a password manager or
   another safe place. If they are lost, Play support can reset the upload key, but that takes
   days.
   - Upload certificate SHA-256:
     `65:7D:D9:4C:16:92:AA:9A:CD:7F:95:9E:39:F6:BA:16:46:F9:7C:0B:96:E1:C0:05:FD:01:F5:49:6A:A7:65:F9`
3. Create the app: Play Console → Create app → name **ViGasBoiler**, default language English
   (United States), **App**, **Free**. Free cannot be changed to paid later.
4. **Play App Signing:** accept "Use Google-generated key" on the first release. Google signs the
   app for users; the AAB here is signed with your upload key.

## App content (Policy → App content)

**Privacy policy:** the hosted URL from step 1.

**App access → "All or some functionality is restricted".** The real app needs a Viessmann sign-in
with the user's own developer client, which a reviewer does not have, so the app has a demo mode.
Add one instruction set:

- **Name:** Demo mode (no login)
- **Username / password:** leave empty, tick "No other information is required" if offered, or
  enter "not required".
- **Any other information:**

  > The app controls the user's own Viessmann gas boiler and needs a personal Viessmann
  > developer account, so no shared test login exists. To review every screen, tap "Try with
  > sample data" on the first screen, then "Start demo". The demo uses a bundled sample boiler with
  > a week of history; no account is needed and nothing is sent to Viessmann. Settings changes in
  > the demo are applied to the sample boiler only. Leave the demo with "Exit demo" in the banner
  > or under More.

**Ads:** No, the app contains no ads.

**Content rating:** start the questionnaire, category **Utility, Productivity, Communication, or
Other**. Answer No to violence, sexuality, language, controlled substances, gambling. "Does the app
allow users to interact or exchange content?": No. "Does it share the user's location?": No.
Expected result: Everyone / PEGI 3.

**Target audience and content:** **18 and over** only. The app is not designed for children, and
choosing older ages only keeps the Families policy out of scope.

**News app:** No. **COVID-19 contact tracing:** No. **Government app:** No.
**Financial features:** None. **Health:** None.

**Data safety:**

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes |
| Do you provide a way for users to request that their data is deleted? | Yes (turning analytics off in Settings → Privacy deletes unsent data; contact e-mail for the rest) |

Data types to declare (all from Google Analytics for Firebase, only after the user opts in):

| Data type | Collected | Shared | Optional | Purpose |
|---|---|---|---|---|
| App activity → **App interactions** | Yes | No | Yes, users can choose | Analytics |
| App info and performance → **Diagnostics** | Yes | No | Yes, users can choose | Analytics |
| Device or other IDs → **Device or other IDs** (Firebase app instance ID) | Yes | No | Yes, users can choose | Analytics |

"Processed ephemerally": No. Google acting as the developer's analytics provider is not
"sharing" under Play's definition.

Not declared, because the developer never receives them: boiler data and history, OAuth tokens,
the client ID, and the ViCare account (the user signs in on Viessmann's page; the app talks to
Viessmann directly on the user's behalf). No location, no contacts, no personal info, no advertising
ID (the AD_ID permission is removed from the manifest).

**Please check one point yourself:** the app now uses Android Auto Backup, which sends history, the
client ID and settings to the user's own Google account, encrypted. Google's [Data safety
page](https://support.google.com/googleplay/android-developer/answer/10787469) does not mention Auto
Backup. It defines "collect" as data transmitted off the device, and the developer never gets this
data, so I read it as not declared, but that is my interpretation, not Google's wording. The privacy
policy describes the backup either way.

**Advertising ID declaration:** No, the app does not use an advertising ID.

## Store settings

- **Category:** House & Home (alternative: Tools).
- **Tags:** Smart home, Home automation.
- **Contact details:** e-mail required (shown publicly), website optional.
- **Countries:** wherever Viessmann boilers with the API are sold; at minimum the EU countries
  and the UK.

## Testing track first

Personal developer accounts created after 13 November 2023 must run a **closed test with at
least 12 testers who stay opted in for 14 days** before they can apply for production access.
Upload this AAB to **Closed testing** first, add testers by e-mail list, and share the opt-in link.
Organisation accounts can go straight to production (or start with Internal testing to try the
Play-signed build).

## Notes

- Installs from Play are signed with Google's key, so an app sideloaded earlier with a different
  key (your own phone) must be uninstalled first. This deletes its local history, and the backup
  will most likely not bridge the gap: Android restores only to an app with a matching signature,
  so expect history from a sideloaded build not to return in the Play version.
- The listing is English only. Translations can be added later under Store presence → Store
  listings → Manage translations.
- The app name and descriptions use "Viessmann", "Vitodens" and "ViCare" descriptively, with the
  disclaimer at the end of the full description; the icon and graphics use no Viessmann logo.
- To build the next version: raise `versionCode` (and `versionName`) in `app/build.gradle.kts`,
  then run `./gradlew :app:bundleLiveRelease` and upload
  `app/build/outputs/bundle/liveRelease/app-live-release.aab`.
- The AAB contains the R8 mapping file and native debug symbols, so Play shows readable stack
  traces without a separate upload.
