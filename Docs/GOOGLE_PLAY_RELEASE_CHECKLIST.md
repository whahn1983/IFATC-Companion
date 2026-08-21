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

- [ ] `:app` has **not been compiled** in the environment this port was written in — the Android SDK and Google's Maven repository were unreachable there. The engine (`:core`) and the pure Compose screens are both verified, but the first Android Studio build should be expected to surface ordinary integration fixes.
- [ ] Confirm the **Play Billing library version** in `gradle/libs.versions.toml` against the current Play requirement, and that `PlayBillingRepository` matches its API. This is the one file that could not be compile-checked.
- [ ] Review `Docs/ANDROID_PARITY_MATRIX.md` and close or accept every open row.

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
