# AGENTS.md

Working notes for AI agents on this repository. Read this before touching anything.

This file is about **how to work here without breaking things**. It is not a feature list —
`README.md` covers what the app does, and `Docs/` covers each subsystem in depth.

---

## 1. What this repository is

Two implementations of one app, **IFATC Companion**: an independent companion for the
flight simulator *Infinite Flight* that talks a pilot through a flight gate-to-gate with
simulated, spoken ATC.

| Path | What it is | May you edit it? |
| --- | --- | --- |
| `IFATCCompanion/` | The **shipping iOS app**, in Swift. | **No.** Read-only. It is the specification. |
| `IFATCCompanionTests/`, `IFATCCompanionWidgets/` | iOS tests and widgets. | **No.** |
| `Android/` | The Kotlin/Compose port. | Yes — this is where work happens. |
| `Docs/` | Android port documentation. | Yes, and you must keep it true. |
| `docs/` | iOS subsystem documentation. Referenced by both. | Rarely; prefer `Docs/`. |

**iOS is the authoritative spec.** When Android and iOS disagree about behaviour, iOS is
right and Android is wrong, unless a `Docs/` file records a deliberate, reasoned divergence
(there are several, and each says why — see §7).

There is no shared code between the two. No Kotlin Multiplatform, no bridging, no
transpilation. The port is a re-implementation.

### Identity — never change these

- App name: **IFATC Companion**
- Application ID: `com.h3consultingpartners.ifatccompanion`
- Publisher: **H3 Consulting Partners**

---

## 2. Android module layout, and why it is shaped this way

```
Android/
  core/        Pure Kotlin/JVM. The entire engine. Nine hundred-odd unit tests.
  app/         Android: Compose UI, services, audio, billing, platform ports.
  uicheck/     Not shipped. Type-checks the pure Compose screens without the Android SDK.
```

### `:core` — the engine

**`:core` must never import `android.*` or `androidx.*`.** This is the single most important
structural rule in the repository, and it is what makes the port testable at all: every
decision the app makes — the ATC state machine, phraseology, taxi routing, weather
deviation, the map projection, billing entitlement logic — runs on a plain JVM with no
emulator.

Packages:

```
core/airports    core/atc        core/atis      core/audio     core/billing
core/chatter     core/config     core/connect   core/diagnostics
core/enroute     core/geo        core/liveupdate core/map      core/mock
core/model       core/net        core/persistence core/phraseology
core/platform    core/session    core/settings  core/surface   core/ui
core/weather (+ /deviation, /radar)
```

Platform capabilities `:core` needs are **ports** — interfaces it declares and `:app`
implements. `HttpFetching`, `FileStore`, `KeyValueStore`, `Clock`, `DiagnosticsSink`,
`SpeechService` and friends all follow this shape. If you need a platform thing in `:core`,
declare an interface; do not reach for the Android SDK.

### `:app` — the shell

```
app/audio   app/billing  app/data    app/map     app/notification
app/service app/simbrief app/ui (+ /components /map /screens /state /theme)
```

`AppGraph.kt` is the object graph, hand-wired. There is no DI framework: one long-lived
graph with no build variants does not earn a compiler plugin, and constructor parameters
keep every engine's dependencies visible and testable.

`ui/FlightViewModel.kt` is the bridge. **It holds no logic of its own.** The engine decides;
the ViewModel exposes what it decided and forwards what the pilot did. Anything that looks
like a rule belongs in `:core` where it can be tested. What *does* live in the ViewModel is
*draft* state — text a pilot is part-way through typing, which is not a fact about the
flight until they commit it.

`ui/ScreenModels.kt` maps engine state into per-screen models. `ui/AppNavHost.kt` is the
shell. `ui/screens/*.kt` are pure: state in, callbacks out.

### `:uicheck` — the reason the Compose screens compile

There is **no Android SDK in the agent sandbox**, and `dl.google.com` is typically
unreachable. `:app` therefore cannot be compiled locally — only CI compiles it.

`:uicheck` closes most of that gap. It compiles the *pure* Compose sources against
**JetBrains Compose 1.6.11 desktop** artifacts from Maven Central, which publish the same
`androidx.compose.{runtime,foundation,material3,ui}` packages. Those source directories are:

```
app/src/main/kotlin/com/h3consultingpartners/ifatccompanion/ui/screens
                                                            /ui/components
                                                            /ui/theme
                                                            /ui/map
                                                            /ui/state
```

**Anything in those five directories that touches an Android-only API breaks the `:uicheck`
build.** That is a feature, not an obstacle: it is what keeps the screens portable and
type-checked. When you need an Android API near the UI — `BitmapFactory`, say — put it
outside those directories (see `app/map/BaseMapImageryLoader.kt`, which exists exactly
because `BitmapFactory` cannot live in `ui/map`).

`:uicheck` does **not** cover `ScreenModels.kt`, `AppNavHost.kt`, `FlightViewModel.kt`,
`MainActivity.kt`, `AppGraph.kt`, `IFATCCompanionApplication.kt`, or anything under
`app/audio`, `app/service`, `app/map`, `app/billing`, `app/data`, `app/notification` or
`app/simbrief`. Changes to any of those are compiled only by CI, at four minutes a round
trip — so read the declaration you are calling, including its parameter *types*.

---

## 3. Commands that actually work here

Run from `Android/`. All three work with no Android SDK and no network beyond Maven Central.

```bash
# The engine and its tests. Run this after ANY :core change.
./gradlew -c settings-core.gradle.kts :core:test --no-daemon --offline

# Type-check the pure Compose screens. Run this after ANY ui/ change.
./gradlew -c settings-uicheck.gradle.kts :uicheck:compileKotlin --no-daemon --offline

# Will NOT work in the sandbox — needs the Android SDK. CI runs it.
./gradlew :app:assembleDebug
```

Read the test results without opening HTML:

```bash
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t=f=e=0; c=0
for p in glob.glob("core/build/test-results/test/*.xml"):
    r=ET.parse(p).getroot(); c+=1
    t+=int(r.get("tests")); f+=int(r.get("failures")); e+=int(r.get("errors"))
    for tc in r.iter("testcase"):
        for x in list(tc.iter("failure"))+list(tc.iter("error")):
            print("FAIL:", tc.get("classname").split(".")[-1], "::", tc.get("name"))
            print((x.text or "")[:400])
print(f"{t} tests / {c} classes / {f} failures / {e} errors")
PY
```

### CI — `.github/workflows/android.yml`

Three jobs, scoped to `paths: ['Android/**', '.github/workflows/android.yml']` so an
iOS-only commit does not run it. It is **not** a required check, and
`concurrency: cancel-in-progress: true` is on.

| Job | Steps |
| --- | --- |
| Core engine tests | `:core:test`, then **documented test counts match the run** |
| Compose screens type-check | `:uicheck:compileKotlin` |
| Android app build | `:app:assembleDebug`, **Android Lint**, **`:app:bundleRelease` (R8)**, **bundled map data is in the release bundle** |

The two bolded guard steps exist because both failures had already happened silently:
docs quoted a test count two changes out of date, and `coastlines.json` is a Java resource
that nothing in the build graph declares, so losing it would look like a rendering bug on a
device and nowhere else.

`cancel-in-progress: true` means **pushing a follow-up commit kills the run in flight**.
Batch your pushes. Three CI runs were wasted learning this.

---

## 4. Hard constraints

These are not preferences. Violating one is a defect regardless of whether it compiles.

### Stack
- Kotlin, Jetpack Compose, Material 3, Coroutines, StateFlow.
- compileSdk 36, targetSdk 36, **minSdk 29**.
- **Forbidden:** Flutter, React Native, Xamarin, MAUI, Capacitor, Cordova, Kotlin
  Multiplatform UI, or embedded web tech for native screens.

### Secrets and infrastructure
- Never commit private signing keys, credentials or API secrets.
- If a provider requires a true secret that cannot safely live in an Android app, **do not
  obfuscate around it.** Document the limitation and ask for direction.
- **Do not invent a backend.** Do not add hosted infrastructure. Both need explicit approval.

### Privacy
- No advertising SDKs, behavioural analytics, cross-app tracking, unnecessary identifiers,
  unnecessary accounts, or new third-party telemetry — without explicit approval.
- The app has no accounts and no logins. Keep it that way.

### Maps
- **Do not** use OpenStreetMap's public raster tile servers as a production base map.
- **Do not** describe OSM data as CC BY 4.0. It is **ODbL 1.0**.
- **Do not** adopt a mapping SDK that needs an API key, a billing account, restrictive
  commercial licensing, or recurring cost — not even because MapKit has no Android
  equivalent. Document the issue and stop. `Docs/ANDROID_MAPPING.md` records every provider
  that was evaluated and rejected, and what was built instead.

### SimBrief / Navigraph
- Do not scrape SimBrief, inject behaviour into it, alter what it shows, remove SimBrief or
  Navigraph branding, claim affiliation, or extract proprietary data outside authorised
  APIs. It is opened in a Custom Tab, in the pilot's own browser session, and that is all.

### Background execution
- **No silent-audio keep-alive hacks.** The foreground service is the sanctioned mechanism.

### Store
- **Never** use Apple's Terms of Use or subscription-management links on Android. Use
  Google Play's (`AppConfig.Links.GOOGLE_PLAY_TERMS`).

---

## 5. Git and delivery

- Develop on the branch you were given. **Never push to a different branch without explicit
  permission.**
- `git push -u origin <branch>`. On network failure, retry up to 4 times with exponential
  backoff (2s, 4s, 8s, 16s).
- After pushing, open a PR if no open one exists for the branch.
- If the branch's PR is already **merged**, treat follow-up work as a fresh change: restart
  the branch from the latest default branch and open a new PR. Never stack onto merged
  history.
- Commit messages here are long and explain *why*, in prose. Match that. Do not put a model
  identifier in any commit message, PR body, code comment or other pushed artifact.

---

## 6. How to write code that fits

### Comments
This codebase writes dense, reasoned KDoc that **names the failure a piece of code exists to
prevent**. Not "sets the timeout to 12 seconds" but "short on purpose: imagery is an
enhancement; waiting on it would make the map feel broken on a slow link when it has
coastlines to draw immediately."

Explain **why**, never what. Match the surrounding density — do not add comments to a file
that has none, or strip them from a file built around them.

### Tests
Every `:core` test should assert behaviour that matters and carry a comment saying what
breaking it would cost. There are nine hundred-odd of them, and they are the only thing between a
change and a device.

**A test that does not exercise the production path cannot catch the bug it was written
for.** This has already happened: two tests covering a world-map fit passed only because
they used `paddingFraction = 0.0`, which the app never uses; at the real value one of them
misses its own tolerance by 0.074. Pass the constants the app passes.

### Statuses in `Docs/ANDROID_PARITY_MATRIX.md`

The legend at the top of that file is the complete vocabulary. **Do not invent symbols** —
a `🟢` was once added for a row that had no `:app` tests, in a document whose own preamble
says it exists to stop exactly that. Of particular note:

- **🔌 Ported, not wired** — the code exists in `:core`, has passing tests, and is
  constructed nowhere in `:app`. This status exists because `CenterSectorDatabase` had 17
  passing tests and was never once instantiated by the running app.

---

## 7. Deliberate divergences from iOS

Recorded so nobody "fixes" them back:

| Divergence | Why |
| --- | --- |
| No hosted base map; the route and taxi maps are drawn on a Compose canvas | Every SDK failed the no-key / no-bill / no-backend bar. `Docs/ANDROID_MAPPING.md`. |
| Route map has a graticule, scale bar, bundled Natural Earth coastlines and a NASA GIBS satellite underlay | MapKit gave iOS coastlines for free; Android had to build them. |
| Live Flight Update and background chatter are **independent** settings (iOS interlocks them) | iOS interlocks them because of its background-audio mechanism. Android's foreground service needs no audio, so interlocking them would be cargo-culting a constraint the platform does not have. |
| Google Play terms and subscription management, not Apple's | Required. |
| English only | Product decision, not a gap. No `strings.xml` localisation work is pending. |
| `AirportDatabase` holds 21 US airports | **Not** a divergence — iOS holds the identical 21, and its own header calls itself "not exhaustive". Expanding it would diverge *from* iOS. |

---

## 8. Lessons this repository has paid for

Every one of these is a real failure that happened here. They are listed because they will
happen again otherwise.

**Reading a constructor is not the same as grepping a file.** A field list built with
`grep -oP "^    val \w+"` matched a *second* data class in the same file. The code did not
compile, and CI found it, not the author. Open the declaration you are describing.

The same mistake, twice more: `SavedFlightStore.load()` was called from `AppGraph` when it
is `private` *and* already runs in the store's own `init`; and the store was constructed
with its default in-memory `defaults`, so the flight the session was bound to would have
been forgotten on every launch. A grep hit shows a line, not a modifier and not a default
argument. **`:app` wiring files (`AppGraph`, `FlightViewModel`, `ScreenModels`,
`AppNavHost`, `MainActivity`) are compiled by CI alone** — a four-minute round trip — so
before pushing changes to them, open every `:core` declaration they call and check its
visibility and its defaults.

And a fourth time, differently: `BitmapRasterDecoder` was written against the first 68
lines of `RasterImage.kt`, which is where `RasterImageDecoder.decode` is declared. The
interface has a second member — `decodeScaled` — twenty lines further down. CI found it
after four minutes. **Read a whole interface before implementing it**, not the part of it
your call site happens to need.

And a fifth: two one-line changes in `:app` cost another four-minute round trip. A `Float`
was passed where the callee wanted a `Double` — `speakChatter(text, voiceId, volume: Double)`
against `RadioAudioEngine.chatterSpeechLevel: Float` — and `distinctUntilChanged()` was
applied straight to a `StateFlow`, which is a **deprecation error**, not a warning, because
a `StateFlow` already conflates equal values. So, in these CI-only files: check the
parameter *types* of every call you write, not only that the symbol exists; and never chain
`distinctUntilChanged()` onto a `StateFlow` — it is legal only after a `map`, `filter` or
`combine` has turned it into a plain `Flow`.

**"Tests pass" is not "the feature works".** `CenterSectorDatabase` had 17 passing tests and
was constructed nowhere. `SurfaceSessionController.refresh` had exactly one caller — a
Settings row most pilots never open — so the taxi map read "Taxi route pending" from launch
to landing. The weather engine's `refresh()` was reachable only from a control that was
never built, so METARs, TAF, ride assessment and SIGMETs were *never fetched at all*. Before
claiming a subsystem works, grep for its constructor and its call sites.

**Documentation over-claims unless it is checked.** `ANDROID_MAPPING.md` described a
graticule, a scale bar and GIBS coastlines as though they existed; none did. Six parity
matrix rows claimed more than the code did. Test counts drifted twice. When you write a
claim in `Docs/`, verify it against source in the same edit — and where a claim can be
machine-checked, make CI check it.

**A comment asserting behaviour is a claim, and can be false.** One commit's comment said an
unplanned flight "now shows coastlines and a grid rather than an empty rectangle... over a
map that is genuinely there". The map drew *nothing* in that case: the viewport was null and
the whole draw lambda was skipped. The comment was written in the same commit as the bug.

**Android Lint's `MissingPermission` is intra-procedural.** A `canPostNotifications()`
helper is invisible to it. Inline the permission check at the call site.

**Lint can report errors while exiting 0.** It did, for 14 errors, because
`abortOnError = false`. It is `true` now. Do not turn it off.

**Silent degradation is right for being offline and wrong for a misconfiguration.**
`BaseImageryService` returned `null` for both "no signal" and "the service refused", which
are indistinguishable from outside and need opposite handling — a layer identifier that
stopped existing would fail forever with nothing recorded anywhere. When a call can fail two
ways, say which.

**Density matters and the sandbox cannot see it.** Base-map label placement was in raw
`DrawScope` pixels while the text was sized in `sp`; above mdpi every label printed through
its own line. `DrawScope` works in pixels — write `dp` and call `.toPx()`, and take label
clearance from the height the text actually measured.

**A doc saying a thing is ported is not evidence it is.** `ANDROID_REMAINING_WORK.md`
described the review engagement counting as "ported and tested"; grep found no review code
in `:core` at all. Check before you build on a claim, including one this file makes.

**Read the state machine before writing a test about it.** A test asserting that a sector
crossing goes unannounced "under another controller" used a climbing aircraft — and `CLIMB`
maps to Center, so the behaviour it called a bug was correct. `AtcFlowOrder.controller` is
the authority on who owns the radio in a given state; a plausible-sounding altitude is not.

**Hardcoded colours break one theme.** `Color(0x33FFFFFF)` on a near-white light-theme
surface is nothing at all. Base-map ink now comes from `IFATCSemanticColors`, which has
`light` and `dark` variants. Every new drawing colour must too.

---

## 9. What is left, and where to look

`Docs/ANDROID_REMAINING_WORK.md` is the live list, in the order it is worth doing, with
honest sizes. `Docs/GOOGLE_PLAY_RELEASE_CHECKLIST.md` covers store mechanics.

**Feature parity with iOS is complete.** Every capability the iOS app has is built and
wired. What is left is verification, tests for the rows that have none, and store mechanics.

The gate everything else is behind: **nothing in this port has ever run on hardware, and
nothing has been heard.** The radio effect chain, the TTS voices, the chatter mix and the
squelch bursts are ported maths that has never reached a speaker. There are no instrumented
tests. CI compiles `:app`, gates Lint and runs R8, and that is the limit of what it can
prove.

Treat "compiles, lints clean, every test passes" as the floor, not the finish line.

---

## 10. Sandbox notes

- **No Android SDK**, and `dl.google.com` is normally unreachable. Use the `:core` and
  `:uicheck` builds; let CI compile `:app`.
- **Outbound HTTPS goes through an agent proxy with an allowlist.** Some hosts the app
  itself uses are denied — `gibs.earthdata.nasa.gov` and `naciscdn.org` among them — so
  their responses cannot be verified here. A 403 to CONNECT is a policy denial, not a
  misconfiguration: do not try to route around it, and never disable TLS verification or
  unset `HTTPS_PROXY`. Say plainly what could not be verified.
- Use the session scratchpad directory for temporary files, not `/tmp`.
- `gh` is unavailable. Use the GitHub MCP tools (`mcp__github__*`).
