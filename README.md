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
  weekly schedule editor with a 24-hour dial, holiday programs, and a detailed heating-curve
  chart you can drag across to read values, with live preview while you change slope and shift.
- **Statistics:** burner hours and starts, gas / electricity / heat counters with month and year
  comparison, efficiency, 30-day outside temperature, and gas-use bars built from the app's own
  history.
- **Messages:** faults (F.) and warnings (A.) with banner and notification; status, service and
  information codes (S., P., I.) listed for reference; code lookup in Viessmann's error database.
- **History:** every refresh is stored on the phone (temperatures, modulation, burner state …),
  with 24 h / 7 day / 30 day charts and CSV export.
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

- Android 9 (API 28) or newer. The app is sideloaded; it is not on Google Play.

**To build**

- [Android Studio](https://developer.android.com/studio) (recent version) with the **Android SDK
  Platform 37**. Its bundled JDK is fine; any JDK 17+ works.
- Internet access for the first build: the Gradle wrapper (9.8.2) and all dependencies download
  automatically.

## 1. Create an API client on the developer portal

1. Sign in at <https://developer.viessmann-climatesolutions.com> with your ViCare account.
2. Open **Clients** → **Add client** and fill in:
   - **Name:** anything, e.g. `ViGasBoiler`.
   - **Google reCAPTCHA:** **off**. A mobile app cannot solve it.
   - **Redirect URI:** `si.vicare.customapp://oauth2redirect`
3. Save, then copy the **Client ID**. There is no client secret: the app is a public client and
   signs in with OAuth 2.0 Authorization Code + PKCE.

The redirect URI must match **exactly**, including the two slashes. The single-slash form
(`si.vicare.customapp:/oauth2redirect`, common in other OAuth apps) does not work: after login
Viessmann's server treats it as a path and shows a 404 page. A newly saved redirect URI can take
a few minutes before the login server accepts it.

## 2. Configure your build

Create or edit `local.properties` in the project root. This file is git-ignored; never commit it.

```properties
sdk.dir=/path/to/Android/sdk

viessmann.clientId=<your client id>
viessmann.redirectUri=si.vicare.customapp://oauth2redirect
```

Android Studio fills in `sdk.dir` automatically when you open the project. Without
`viessmann.clientId` the app still builds, but the sign-in button stays disabled.

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

The release build is signed with your local **debug key** so it can be installed without further
setup. That is fine for your own phone. If you share APKs with others, create your own signing key
and configure `signingConfigs` in `app/build.gradle.kts`.

Run the unit tests with:

```bash
./gradlew :app:testLiveDebugUnitTest :app:testMockDebugUnitTest
```

### Build flavors

| Flavor | Package | What it does |
|---|---|---|
| `live` | `si.vicare.customapp` | The real app: sign-in and the Viessmann API. |
| `mock` | `si.vicare.customapp.mock` | No sign-in. Every API call is answered from the masked sample data in `docs/feature-dump/`; commands change an in-memory copy. Installs next to the live app as "ViGasBoiler Mock". For UI work without touching a real boiler or spending API calls. |

## 4. First start

1. Open the app and tap **Sign in with Viessmann**; log in with your ViCare account.
2. The app finds your installation, gateway and heating device and loads all features.
3. Allow notifications if you want fault, pressure and gateway alerts.

Under **More → Discovery and debug dump** you can export all raw API responses as a zip. With
**Mask IDs, serial numbers and personal data** checked (default), installation IDs, serials and
personal fields are replaced by placeholders, which is how `docs/feature-dump/` was made.

## API limits

The Basic plan allows **120 calls per 10 minutes and 1450 per 24 hours**. Going over blocks your
account for the rest of the window, **including the official ViCare app**. The app protects you:

- Every call is counted on the phone; the overview shows what is left in both windows.
- A refresh costs 2 calls (device + gateway features). A settings change costs 2 (command + one
  re-read of only that feature), plus one delayed re-read if the boiler had not applied it yet.
- Foreground refresh on opening the app and on pull-down, at most once per minute. Background
  refresh every 30 minutes by default (15–120 in Settings): about 96 calls a day.
- At 80 % of either window, automatic and background refreshes pause; only manual refreshes and
  setting changes still go through. Nothing is ever sent that would exceed a limit.
- After an HTTP 429 the app reads when the limit resets and sends nothing until then.
- Settings are never changed in the background, only after your explicit confirmation.

## Privacy and security

- Everything stays on your phone; the app talks only to Viessmann's servers.
- OAuth tokens are encrypted (Tink AES-256-GCM, key in the Android Keystore). App backup is
  disabled, so tokens and history are never copied off the device.
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
app/src/main/java/si/vicare/customapp/
  auth/       OAuth login (AppAuth), token storage and refresh
  feature/    feature model and parser, command validation, schedule rules, repository
  ratelimit/  API call budget and 429 handling
  history/    on-phone history, consumption periods, CSV export
  messages/   error-database lookup
  work/       background refresh and notifications
  ui/         Jetpack Compose screens and theme
app/src/live, app/src/mock   flavor-specific setup (real API vs. sample data)
docs/feature-dump/           masked sample API responses (tests and mock flavor)
docs/step2-decisions.md      design decisions taken from the real data
```

The [Viessmann API Postman collection](https://documentation.viessmann.com) documents the
endpoints used.

Built with Kotlin, Jetpack Compose and Material 3, Hilt, Retrofit/OkHttp, kotlinx.serialization,
Room, DataStore, WorkManager, AppAuth, Tink and Vico.

## License

[MIT](LICENSE)
