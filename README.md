# ViGasBoiler

An unofficial Android app to monitor and control a **Viessmann gas condensing boiler** through the
official [Viessmann API](https://developer.viessmann-climatesolutions.com). Built for a
Vitodens 100-W on the E3 platform, but the screens are driven by whatever features your boiler
reports, so other E3 boilers should work too.

> **Not affiliated with Viessmann.** "Viessmann", "Vitodens" and "ViCare" are trademarks of their
> owners. Changing heating settings affects a real appliance: use at your own risk.

## What it does

- **Overview:** room temperature against its target, outside temperature, an animated burner
  indicator with modulation, supply temperatures, system pressure, gateway status and Wi-Fi
  signal, and how much of the API budget is left.
- **Heating:** operating mode, room temperature per program (reduced / normal / comfort), a
  weekly schedule editor with a touch 24-hour dial (drag a dot to change a time, drag an arc to
  move the period), holiday programs, and a detailed heating-curve
  chart you can drag across to read values, with live preview while you change slope and shift.
- **Statistics:** burner hours and starts, gas / electricity / heat counters with month and year
  comparison, efficiency, 30-day outside temperature, and gas-use bars built from the app's own
  history.
- **Messages:** faults (F.) and warnings (A.) with banner and notification; status, service and
  information codes (S., P., I.) listed for reference; code lookup in Viessmann's error database.
- **Charts** (first page of Statistics): room vs target vs outside, supply vs setpoint, modulation,
  pressure, gas per hour/day and burner running time for 24 h / 7 days / 30 days, with figures and
  tap-to-read values. Tapping a tile on the Overview opens its chart. Every refresh is stored on the
  phone; Settings exports it as CSV.
- **Heating check:** after a week of data, rates burner cycling, comfort, efficiency and the system
  against your settings and suggests improvements (More → Heating check).
- **Home-screen widgets:** an overview (room card, burner ring, live countdown to the next refresh)
  and a statistics grid, both 4×3 so they can be stacked.
- **All features:** every value and every command the API exposes for your device, with input
  forms generated from the command constraints.
- **Background refresh and notifications:** every 30 minutes by default; alerts for faults,
  low or high pressure, gateway offline and an almost-used-up API budget.

Every change is validated against the API's constraints and confirmed in a dialog before it is
sent. The new value shows immediately and is confirmed by re-reading the boiler.

## Requirements

**Heating system**

- A Viessmann boiler with internet connection (built-in Wi-Fi / TCU gateway or a Vitoconnect),
  set up in the **ViCare** app with your ViCare account.
- Developed and tested with a Vitodens 100-W, E3 platform (`E3_Vitodens_100_0421`), gateway
  `E3_TCU41_x04`. One installation, one gateway and one heating circuit are what the curated
  screens show; everything else is reachable under **More → All features**.

**Viessmann developer account**

- A free account on the [Viessmann developer portal](https://developer.viessmann-climatesolutions.com)
  (sign in with your ViCare account) and an API client, see below.
- The free **Basic** plan is enough. Some commands only exist in paid plans (for example supply
  temperature limits on the E3 Vitodens); the app shows them read-only.

**Phone**

- Android 9 (API 28) or newer.

**To build**

- [Android Studio](https://developer.android.com/studio) (recent version) with the **Android SDK
  Platform 37**. Its bundled JDK is fine; any JDK 17+ works.
- Internet access for the first build: the Gradle wrapper (9.8.2) and all dependencies download
  automatically.

## 1. Create an API client on the developer portal

Every user registers their own API client; the app's setup wizard walks through these steps on
first start and has buttons to open the portal and copy the redirect URI.

1. Sign in at <https://developer.viessmann-climatesolutions.com> with your ViCare account.
2. Open **Clients** → **Add client** and fill in:
   - **Name:** anything, e.g. `ViGasBoiler`.
   - **Google reCAPTCHA:** **off**. A mobile app cannot solve it.
   - **Redirect URI:** `com.vigasboiler.app://oauth2redirect`
3. Save, then copy the **Client ID**. There is no client secret: the app is a public client and
   signs in with OAuth 2.0 Authorization Code + PKCE.

The redirect URI must match **exactly**, including the two slashes. The single-slash form
(`com.vigasboiler.app:/oauth2redirect`, common in other OAuth apps) does not work: after login
Viessmann's server treats it as a path and shows a 404 page. A newly saved client or redirect URI
can take a few minutes before the login server accepts it.

## 2. Configure your build

Nothing to configure: the client ID is entered in the app, not built in. Android Studio creates
`local.properties` with `sdk.dir` when you open the project (git-ignored; never commit it).

**Optional: analytics.** To receive anonymous usage statistics in your own Firebase project,
register an Android app with package `com.vigasboiler.app` in the
[Firebase console](https://console.firebase.google.com), download its `google-services.json` and
put it at `app/src/live/google-services.json` (git-ignored). Without the file the app builds and
runs without analytics. Debug builds never send analytics.

## 3. Build and install

Open the project in Android Studio and run the `app` configuration with the **liveRelease** or
**liveDebug** build variant, or use the command line (with `JAVA_HOME` pointing to a JDK 17+, e.g.
Android Studio's `jbr` folder):

```bash
./gradlew :app:assembleLiveRelease
```

```bash
adb install -r app/build/outputs/apk/live/release/app-live-release.apk
```

**Use the release build on your phone.** Debug builds of Jetpack Compose apps are much slower
(no code optimisation, extra checks); charts and animations stutter there. The release build is
minified with R8 and is about 5 MB instead of 45 MB.

Release builds are signed with the Play **upload key** when `keystore.properties` (and the keystore
it names) exists in the project root, otherwise with your local **debug key** so they install
without further setup. Both files are git-ignored. For the Play Store bundle run
`./gradlew :app:bundleLiveRelease`; `play-store/README.md` has the listing texts, graphics and the
Play Console checklist.

Run the unit tests with:

```bash
./gradlew :app:testLiveDebugUnitTest :app:testMockDebugUnitTest
```

### Build flavors

| Flavor | Package | What it does |
|---|---|---|
| `live` | `com.vigasboiler.app` | The real app: setup wizard, sign-in and the Viessmann API. |
| `mock` | `com.vigasboiler.app.mock` | No sign-in. Every API call is answered from the masked sample data in `docs/feature-dump/`; commands change an in-memory copy. Installs next to the live app as "ViGasBoiler Mock". For UI work without touching a real boiler or spending API calls. |

## 4. First start

The setup wizard guides through everything:

1. **What you need:** boiler in the ViCare app, ViCare account, free developer account.
2. **Developer account:** opens the portal; sign in with your ViCare account.
3. **Register this app:** the client settings above, with the redirect URI to copy.
4. **Client ID:** paste the client ID from the portal (stored on the phone only).
5. **Sign in** with your ViCare account. If it fails, the wizard lists the usual causes.

**Try with sample data** on the first page starts a demo without any account: the bundled sample
installation from `docs/feature-dump/` (the same as the mock flavor) with a generated week of
history, so every screen, chart and the heating check can be explored. Nothing reaches the network.
Starting and leaving the demo (banner or More → Exit demo) clear the history and cached device
data on the phone, so demo values never mix with a real boiler's. History from an earlier sign-in
is saved first and returns when the demo ends; demo data is never backed up.

Then the app finds your installation, gateway and heating device and loads all features. Allow
notifications if you want fault, pressure and gateway alerts.

After the first sign-in, release builds with a Firebase config ask once whether anonymous usage
statistics may be sent; nothing is sent before the user agrees.

**Settings → Display → Theme** follows the phone by default or can be set to light or dark.

**Settings → Viessmann API client** shows the client ID and redirect URI in use; **Change client
ID** signs out and reopens the wizard. Signing out (More → Sign out) keeps the client ID.

Under **More → Discovery and debug dump** you can export all raw API responses as a zip. With
**Mask IDs, serial numbers and personal data** checked (default), installation IDs, serials and
personal fields are replaced by placeholders, which is how `docs/feature-dump/` was made.

## API limits

The Basic plan allows **120 calls per 10 minutes and 1450 per 24 hours**. Going over blocks your
account for the rest of the window, **including the official ViCare app**. The app protects you:

- Every call is counted on the phone; the overview shows what is left in both windows.
- A refresh costs 2 calls (device + gateway features). A settings change costs 2 (command + one
  re-read of only that feature), plus one delayed re-read if the boiler had not applied it yet.
- Refresh on opening the app (at most once a minute), on pull-down and with the widget's
  refresh button (any time). Background refresh every 30 minutes by default (15–120 in
  Settings): about 96 calls a day. The home-screen widget shows a live countdown to it.
- At 80 % of either window, automatic and background refreshes pause; only manual refreshes and
  setting changes still go through. Nothing is ever sent that would exceed a limit.
- After an HTTP 429 the app reads when the limit resets and sends nothing until then.
- Settings are never changed in the background, only after your explicit confirmation.

## Privacy and security

- Your boiler data, history, tokens and client ID stay on your phone, apart from the backup
  below; the app talks to Viessmann's servers and, only if you agree, to Google Analytics for
  Firebase.
- **Backup:** with the phone's backup on, Android Auto Backup saves the history (as one compact
  file per day in `files/history-backup/`), the settings and the client ID to your own Google
  account, roughly once a night, only when it can be encrypted. After a reinstall or on a new phone
  they come back; you only sign in again. Tokens, their key, the database and the device selection
  are excluded (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`). Several phones are not
  kept in sync: each one has its own backup.
- **Analytics is opt-in.** Asked once after the first sign-in, changeable in Settings → Privacy;
  turning it off also deletes statistics not yet sent. Collected: screens opened, which setting
  was changed (feature and command name, not the value) and whether it worked, sign-in method and
  error categories, theme choice, plus Firebase's app version, phone model, Android version and
  country. Never collected: boiler values, installation or gateway IDs, serial numbers, the
  client ID, account or location data, the advertising ID or the Android ID (the ad-ID
  permissions are removed from the app). Debug builds and the mock flavor send nothing.
- OAuth tokens are encrypted (Tink AES-256-GCM, key in the Android Keystore) and never backed up.
- Debug builds log only the request method, a path with IDs masked, and the status code; release
  builds log nothing.
- Commands are only ever sent to the Viessmann API host.
- Serial numbers are hidden in Device info until you tap **Show**.

## Known limitations

- History starts when you install the app; the Viessmann API offers no past values (except the
  gas, power and heat counters).
- Viessmann's error database had no entry for the status codes (S.) tried so far; they are informational.
- Hot-water (DHW) features are hidden by default (the original installation has a separate hot
  water system); turn them on in Settings.
- Curated screens assume one heating circuit (`heating.circuits.0`).

## Project layout

```
app/src/main/java/com/vigasboiler/app/
  auth/       OAuth login (AppAuth), client ID, token storage and refresh
  feature/    feature model and parser, command validation, schedule rules, repository
  ratelimit/  API call budget and 429 handling
  history/    on-phone history, consumption periods, CSV export
  messages/   error-database lookup
  work/       background refresh and notifications
  analytics/  opt-in usage statistics (Firebase in src/live, no-op in src/mock)
  ui/         Jetpack Compose screens (setup wizard in ui/login) and theme
app/src/live, app/src/mock   flavor-specific setup (real API vs. sample data)
docs/feature-dump/           masked sample API responses (tests and mock flavor)
docs/step2-decisions.md      design decisions taken from the real data
```

The [Viessmann API Postman collection](https://documentation.viessmann.com) documents the
endpoints used.

Built with Kotlin, Jetpack Compose and Material 3, Hilt, Retrofit/OkHttp, kotlinx.serialization,
Room, DataStore, WorkManager, AppAuth, AndroidX Browser (Auth Tab), Tink, Vico and Firebase
Analytics.

## License

[MIT](LICENSE)
