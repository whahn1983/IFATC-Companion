# Google Play release checklist — IFATC Companion

Everything below is either **done in the repository** or **must be done by a person**
with access to the Play Console and the signing key. Nothing here can be completed by
committing code alone, which is why the two are separated.

---

## Part 1 — Done in the repository

| Item | Where |
| --- | --- |
| Android App Bundle configured | `app/build.gradle.kts` — `bundleRelease`; language splits disabled (single locale) |
| Application ID | `com.h3consultingpartners.ifatccompanion` |
| `versionCode` / `versionName` | `app/build.gradle.kts` — currently `1` / `1.0.0` |
| `compileSdk` / `targetSdk` / `minSdk` | 36 / 36 / 29 |
| Release signing wired, **no key committed** | `signingConfigs { release }` reads `keystore.properties`, a Gradle property, or an environment variable; `.gitignore` excludes `keystore.properties`, `*.jks`, `*.keystore` |
| R8 / ProGuard | `app/proguard-rules.pro` — kotlinx.serialization, `:core` models, OkHttp, Play Billing; `isMinifyEnabled` and `isShrinkResources` on for release, full mode on |
| Adaptive launcher icon | `res/mipmap-anydpi-v26/ic_launcher.xml` + background/foreground/monochrome vectors, redrawn from the iOS icon; PNG fallbacks at five densities |
| Themed icon (Android 13+) | `res/drawable/ic_launcher_monochrome.xml` |
| Notification icon | `res/drawable/ic_notification.xml` — flat white silhouette |
| Splash screen | `Theme.IFATCCompanion.Starting` via `androidx.core:core-splashscreen` |
| App name | `res/values/strings.xml` → `IFATC Companion` |
| Network security config | `res/xml/network_security_config.xml` — HTTPS only, system trust anchors, no user CAs, cleartext refused |
| Backup / device-transfer rules | `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` — settings and saved flights carry; the cached entitlement never does |
| Foreground-service declaration | `mediaPlayback` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; justification and the `specialUse` fallback in `Docs/ANDROID_BACKGROUND_EXECUTION.md` |
| Notification permission | Requested at runtime; a denial degrades gracefully rather than blocking a flight |
| Permissions minimised | No location permission of any kind; microphone only for push-to-talk, requested on first use |
| Data-safety answers prepared | `Docs/GOOGLE_PLAY_DATA_SAFETY.md` |
| Attribution and legal text | `core/ui/LegalStrings.kt`, asserted by `LegalStringsTest` |
| Play store icon (512×512) | `Android/play-assets/play-store-icon-512.png` |
| CI | `.github/workflows/android.yml` — engine tests, Compose type-check, and the `:app` build. Path-filtered to `Android/**`, so iOS work never triggers it; advisory, not a required check |

---

## Part 2 — Must be done by a person

### Signing

- [ ] Create the upload keystore (`keytool -genkeypair -v -keystore upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload`).
- [ ] Store it **outside the repository**, back it up, and record the passwords in a password manager. A lost upload key is recoverable through Play support only if Play App Signing is enrolled; a lost *app signing* key without enrolment is not recoverable at all.
- [ ] Enrol in **Play App Signing** (strongly recommended, and required for new apps).
- [ ] Supply the four values as Gradle properties or environment variables:
      `IFATC_RELEASE_STORE_FILE`, `IFATC_RELEASE_STORE_PASSWORD`, `IFATC_RELEASE_KEY_ALIAS`, `IFATC_RELEASE_KEY_PASSWORD`.
- [ ] Confirm `./gradlew :app:bundleRelease` produces a **signed** bundle (the build leaves it unsigned when no key is supplied, by design).

### Play Console — app setup

- [ ] Create the app; set the default language to English (United States).
- [ ] Store listing: title, short description, full description, feature graphic, phone screenshots (min 2), 7-inch and 10-inch tablet screenshots.
      The listing must repeat the non-affiliation disclaimer: *IFATC Companion is an independent companion app developed by H3 Consulting Partners and is not affiliated with, endorsed by, sponsored by, or approved by Infinite Flight LLC. Infinite Flight is sold separately and is required for Live Connected Mode.*
      It must also state that all ATC, weather, taxi and navigation information is **for flight simulation and entertainment only and must not be used for real-world aviation**.
- [ ] Upload the 512×512 icon from `Android/play-assets/`.
- [ ] Privacy policy URL: `https://whahn1983.github.io/IFATC-Companion/privacy-policy.html`.
- [ ] Complete **Data safety** from `Docs/GOOGLE_PLAY_DATA_SAFETY.md`.
- [ ] Complete the **content rating** questionnaire (expected Everyone / PEGI 3).
- [ ] **Ads** declaration: no ads.
- [ ] **Target audience**: not directed at children.
- [ ] **Government apps / financial features**: not applicable.
- [ ] Declare **News app**: no.

### Play Console — monetisation

- [ ] Create the subscription `com.h3consultingpartners.ifatccompanion.live.monthly` with base plan id **`monthly`**, P1M, auto-renewing.
- [ ] Create the subscription `com.h3consultingpartners.ifatccompanion.live.annual` with base plan id **`annual`**, P1Y, auto-renewing.
      *(The base plan ids must match `AppConfig.Billing` exactly — the app launches the purchase flow with them.)*
- [ ] Create the in-app product `com.h3consultingpartners.ifatccompanion.live.lifetime` as a **one-time** (non-consumable) purchase.
- [ ] Set prices. iOS reference: $2.99 / month, $24.99 / year, $79.99 lifetime.
- [ ] Write each product's title and description; the app falls back to its own text only when Play's cannot be loaded.
- [ ] Add licence testers so purchases can be exercised without charge.
- [ ] Confirm the subscription disclosure in the app matches Play's requirements: price, period, that it renews until cancelled, and how to cancel in Google Play.

### Testing that cannot be automated here

- [ ] **Internal testing track**: install the signed bundle and run a full gate-to-gate flight in Mock Mode.
- [ ] Run a full flight in **Live Connected Mode** against Infinite Flight on a second device, on the same Wi-Fi.
- [ ] Purchase each of the three products with a licence tester; confirm Live Connected Mode unlocks, and that the purchase is **acknowledged** (an unacknowledged purchase is auto-refunded after three days).
- [ ] Cancel a subscription and confirm access persists until the period ends, then lapses.
- [ ] Reinstall and confirm **Restore** re-establishes entitlement.
- [ ] Exercise a **pending** purchase if a pending payment method is available in the test region.
- [ ] Confirm the app launches offline and the cached entitlement keeps a paying customer in Live Connected Mode.
- [ ] Confirm the foreground service survives a long flight with the screen off, and that the Live Flight Update stays current.
- [ ] Confirm the Live Update is **promoted** on an Android 16 device and renders as an ordinary ongoing notification on Android 10–15.
- [ ] Deny the notification permission and confirm a flight still runs.
- [ ] Deny the microphone permission and confirm push-to-talk degrades to the on-screen buttons.
- [ ] Test on a **phone** and a **tablet**, in portrait and landscape.
- [ ] Test at 200% font scale and with **TalkBack** enabled; confirm no clipped layout and no unlabelled control.
- [ ] Test in dark mode.
- [ ] Confirm the OpenStreetMap attribution is visible on every taxi-map view and that tapping it opens the OSM copyright page.
- [ ] Run the release build — R8 changes behaviour, so serialization and Play Billing must be exercised **on the minified build**, not only on debug.

### Known gaps to resolve before submitting

> Engineering work left to finish the port — sized and ordered — is in
> [`ANDROID_REMAINING_WORK.md`](ANDROID_REMAINING_WORK.md). This list is the
> submission gate; that one is the build plan.


**Closed since this list was written** — kept here because knowing what was checked is
as useful as knowing what is left:

- [x] `:app` compiles. CI (`.github/workflows/android.yml`) runs `assembleDebug` on every
      Android change. It built clean on the first attempt it was ever given.
- [x] Android Lint gates at `abortOnError = true` with **zero errors**. Its first run
      reported *"14 errors, 63 warnings"* while exiting 0, because `abortOnError` was
      false — a green check that proved nothing. All 14 are fixed.
- [x] R8 runs. `bundleRelease` is in CI, so minification and resource shrinking are
      exercised on every change. **This proves the build succeeds, not that the app
      behaves** — see the runtime item below, which is still open.
- [x] Play Billing compiles and lints against the pinned library. Three defects were found
      and fixed in it, including a failed purchase query being treated as authoritative
      "owns nothing" — which revoked a paying subscriber and poisoned the offline cache.

**Still open:**

- [ ] **Nothing in this port has been heard, and nothing has run on a device.** The radio
      effect chain, the TTS voices, the chatter mix and the squelch bursts are ported maths
      that has never been played through a speaker. Listen to a full flight before shipping.
- [ ] **Run the minified build on a device.** R8 building a bundle is not the same as the
      app working under it: a serializer R8 stripped fails on first deserialization, not at
      build time. Exercise settings, saved flights, phraseology profiles, the surface cache
      and Play Billing **on the minified build**.
- [ ] The **radar raster is not drawn on the route map** — provider selection, fetching and
      sampling all work, and the vector cells and advisory shading draw, but the fetched
      image is not composited onto the canvas.
- [ ] **The taxi route engine is not wired into `:app`.** `AirportSurfaceCoordinator` is
      never constructed, so no route is computed: the taxi map draws the field but no
      route, hold-short bars or crossings, and `TaxiPhraseology` never fires, leaving taxi
      clearances permanently in their generic form. This is the largest remaining gap —
      the integration layer for a 2,106-line stateful subsystem that has to be threaded
      into the telemetry tick and the ATC state machine's taxi, read-back and crossing
      transitions, and it needs live-telemetry device time to trust.
- [ ] The **Flights list screen** is not built, and `SavedFlightStore` is not constructed.
      (Crash/relaunch **session** resume *is* wired and tested — a different thing.)
- [ ] **Center sector hand-offs**: the sector is now named, but the spoken crossing
      hand-off, tuning Center to the tracked sector's frequency, persisting the sector
      across a reconnect, and the `awaitingCenterSectorCheckIn` call-up semantics are not
      done.
- [x] **English only, by decision.** The Compose screens carry hardcoded strings because
      `:uicheck` type-checks them without Android resources, so they cannot call
      `stringResource`. With a single shipping language that arrangement costs nothing, so
      this is closed rather than open. Adding a second language later means giving
      `:uicheck` a resource shim or passing resolved strings into the screens — decide
      which before starting, not after.

      Accessibility is a separate matter and is not affected: icon-only controls carry
      real content descriptions, decorative icons inside labelled buttons pass `null` so
      TalkBack does not read them twice, and the transcript and settings rows use
      `semantics(mergeDescendants = true)`. The TalkBack pass below still needs doing.
- [ ] **Play In-App Review** is not called; the engagement counting that decides when to
      ask is ported.
- [ ] Review `Docs/ANDROID_PARITY_MATRIX.md` and close or accept every open row — in
      particular every row marked 🔌, which means the `:core` tests pass and the app never
      calls the code.

---

## Release commands

```bash
cd Android
./gradlew clean
./gradlew -c settings-core.gradle.kts :core:test          # engine
./gradlew -c settings-uicheck.gradle.kts :uicheck:compileKotlin  # screens
./gradlew :app:testReleaseUnitTest                        # app unit tests, release variant
./gradlew :app:lintRelease
./gradlew :app:bundleRelease                              # → app/build/outputs/bundle/release/
```

Upload `app-release.aab`. Keep the matching `mapping.txt`
(`app/build/outputs/mapping/release/`) — Play needs it to deobfuscate crash reports.
