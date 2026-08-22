# Android parity matrix

Every iOS capability, what it does, where it lives on both platforms, and — stated
plainly — how far the Android port has actually got.

**Status vocabulary.** These mean exactly what they say, and nothing is marked complete
because a screen exists.

| Status | Meaning |
| --- | --- |
| ✅ **Ported** | Behaviour carried across, compiles, and is covered by tests that pass. |
| 🟡 **Ported, untested** | Behaviour carried across and compiles, but has no test of its own yet. |
| 🟠 **Partial** | Some of the area is ported; the rest is named in the row. |
| 🔌 **Ported, not wired** | The behaviour is carried across and its `:core` tests pass — but `:app` never constructs it, so the feature does nothing in the running app. Distinguished from ✅ because "the code exists and is tested" was being read as "it works", and for these rows that is false. |
| ⬜ **Not started** | No Android code yet. |
| 🔵 **Android-native substitution** | Deliberately different because the platform is, with the difference stated. |

> **Verification status of the two modules.** `:core` is compiled and its tests are run —
> **1043 tests across 89 classes, 0 failures, 0 skipped** — and the per-area counts below
> are real.
>
> `:app` is now compiled too, by CI (`.github/workflows/android.yml`): `assembleDebug`
> packages a debug APK, Android Lint gates with `abortOnError = true` and reports zero
> errors, and `bundleRelease` runs R8 with minification and resource shrinking. The
> environment this port was written in has no Android SDK, so those three are the only
> place `:app` is verified; every `:app` change is written blind and checked by CI.
>
> What is still **not** verified: nothing has run on a device or emulator, and nothing has
> been heard through a speaker. R8 producing a bundle proves the keep rules build — not
> that a serializer survives at runtime. An earlier version of this note said `:app` had
> never been compiled; that was true when written.
>
> Anything below marked ✅ has passing tests behind it. 🟡 compiles or type-checks with no
> test of its own. 🔌 means the `:core` tests pass and the app never calls the code — read
> that one carefully, because it is the failure mode this matrix previously hid.

> **What is left, and how big it is:**
> [`ANDROID_REMAINING_WORK.md`](ANDROID_REMAINING_WORK.md). This matrix records
> status per capability; that one orders the remaining work and sizes it.

---

## 1. Infinite Flight Connect

| iOS capability | iOS files | Behaviour | Android | Status |
| --- | --- | --- | --- | --- |
| UDP discovery | `IFDiscoveryService.swift` | Listens for IF's broadcast on port 15000; also sweeps the subnet by direct TCP because Apple's `NWListener` cannot receive broadcast | `core/connect/IFDiscoveryService.kt` — `DatagramSocket` with broadcast enabled; subnet TCP sweep ported with its concurrency limit | ✅ |
| TCP session | `IFConnectClient.swift` | Connect API v2 on port 10112, `Int32 id + write flag`, framed responses | `core/connect/IFConnectClient.kt` over `IFConnectTransport` | ✅ |
| Fragmented frame handling | `IFConnectFrameBuffer.swift` | A frame is surfaced only once its full declared length has arrived; a partial response is never read as empty | `core/connect/IFConnectFrameBuffer.kt` | ✅ 16 tests |
| Length-prefixed string decode | same | Strips the nested `Int32` prefix — not doing so caused intermittent "Manifest Unavailable" | `IFConnectStringDecoder` — uses a **rejecting** UTF-8 decoder, because Kotlin's default substitutes U+FFFD and would hide the failure | ✅ |
| Manifest request + retry | `IFManifestReader.swift` | One same-connection retry; distinct diagnostics per failure mode | `core/connect/IFManifestReader.kt` | ✅ |
| Response-id checking | `IFConnectClient.swift` | A late reply to a timed-out read is discarded rather than believed | `IFConnectClient.payloadAnswering` | ✅ |
| Dynamic state mapping | `IFStateMappingStore.swift` | Name signatures + type filtering; per-family radians-vs-degrees proof | `core/connect/IFStateMappingStore.kt` | ✅ |
| State reading and unit conversion | `IFConnectStateReader.swift` | m/s → knots, radians/degrees resolution | `core/connect/IFConnectStateReader.kt` | ✅ |
| Flight-plan parsing | `IFFlightPlanParser.swift` (746) | Full-info JSON, route string, coordinates, SID/STAR/approach groups | `core/connect/IFFlightPlanParser.kt` | ✅ |
| Connection lifecycle, reconnect, rediscovery | `IFConnectManager.swift` | 1 Hz poll, backoff, address self-healing | `core/connect/IFConnectManager.kt` — `StateFlow`, injected scope/clock/diagnostics | ✅ |
| Staffed-ATC detection | `LiveATCStatus.swift` | Per-frequency, location-aware standby | `core/connect/LiveATCStatus.kt` | ✅ |
| Manual IP entry | Settings | Direct connect when discovery fails | Carried in the settings model | 🟡 |
| Local-network permission | `NSLocalNetworkUsageDescription` | iOS prompts | 🔵 Android 16 `LOCAL_NETWORK_ACCESS` declared; **no location permission**, unlike older Android Wi-Fi guidance | 🔵 |

## 2. ATC engine

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| ATC state machine | `ATCStateMachine.swift` | `core/atc/ATCStateMachine.kt` + `ATCContext.kt` | ✅ |
| Phase detection | `PhaseDetector.swift` | `core/atc/PhaseDetector.kt` — including the refusal to guess when the on-ground flag is missing | ✅ |
| Line-up / roll / final detection | `RunwayLineupDetector.swift` | `core/atc/RunwayLineupDetector.kt` | ✅ |
| Approach intercept geometry | `ApproachIntercept.swift` | `core/atc/ApproachIntercept.kt` | ✅ |
| Pilot responses and requests | `PilotResponseEngine.swift` | `core/atc/PilotResponseEngine.kt` | ✅ |
| Push-to-talk intent parsing | `PilotIntentParser.swift` | `core/atc/PilotIntentParser.kt` | ✅ |
| Fallback taxi planner | `TaxiRoutePlanner.swift` | `core/atc/TaxiRoutePlanner.kt` | ✅ |
| Procedures (SID/STAR/approach) | `ProcedureLibrary.swift` | `core/airports/ProcedureLibrary.kt` | ✅ |
| Runway inventory | `RunwayDatabase.swift` | `core/airports/RunwayDatabase.kt` | ✅ 8 tests |
| Ramp profiles | `RampProfile.swift` | `core/airports/RampProfile.kt` | ✅ |
| Read-back gate + idle re-prompt | `AppModel.swift` | `core/session/ReadbackGate.kt` — extracted so it is testable | ✅ 7 tests |
| Flow ordering, facility mapping | `AppModel.swift` | `core/session/AtcFlowOrder.kt` | ✅ |
| Response-button visibility | `AppModel.swift` | `core/session/PilotActionAvailability.kt` | ✅ 11 tests |
| Automatic flow: takeoff, monitor-Tower, hand-offs, gate arrival | `AppModel.swift` 2107–2400, 4386–4780 | `core/session/FlightSessionCoordinator.kt` — including `adjustedAirborneTarget`, the ladder that turns a physical phase into the controller step it actually is | ✅ 30 tests, incl. a full gate-to-gate scenario |
| Departure field elevation → initial climb | `AppModel.swift` 1930–1941, 4118–4133 | Captured from on-ground telemetry only (MSL − AGL); a half-read snapshot with no ground reference is refused | ✅ 2 tests |
| Spoken pilot transmissions route to the button's action | `AppModel.swift` | `PilotIntent.pilotAction` + `FlightSessionCoordinator.handleSpokenPilotText` — same gate, same standby guard, same transcript as a tap | ✅ |
| Go-around / missed approach | `AppModel.swift` 4600–4650 | Action and phraseology ported; the automatic re-establish loop after a go-around is **not** wired into `adjustedAirborneTarget` | 🟠 |

## 3. Phraseology

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| FAA + ICAO packs, every template | `PhraseologyEngine.swift` (626) | `core/phraseology/PhraseologyEngine.kt` | ✅ |
| Phonetics, digits, altitudes, frequencies | `Phonetic.swift` | `core/phraseology/Phonetic.kt` | ✅ |
| Airline radio callsigns | `AirlineDatabase.swift` | `core/phraseology/AirlineDatabase.kt` — 197 ICAO + 136 IATA, diffed entry by entry | ✅ |
| Safe-wording validator | `PhraseologyValidator.swift` | `core/phraseology/PhraseologyValidator.kt` | ✅ |
| Custom profiles, import/export | `PhraseologyProfile*.swift` | `core/phraseology/PhraseologyProfile*.kt` over `FileStore` | ✅ |
| Ramp phraseology | `RampPhraseologyEngine.swift` | `core/phraseology/RampPhraseologyEngine.kt` | ✅ |
| Gate-to-gate call catalog | `Resources/GateToGateCallCatalog.json` | Copied byte-identical into `core/src/main/resources/`, loaded from the classpath | ✅ |

## 4. Weather

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| METAR / TAF / PIREP / SIGMET | the four parsers | `core/weather/` | ✅ |
| aviationweather.gov client | `AviationWeatherService.swift` | `core/weather/AviationWeatherService.kt` through `HttpFetching` | ✅ |
| Shared HTTP conventions | `AppHTTP.swift` | `core/net/AppHttp.kt` | ✅ |
| Route analysis, SIGMET corridor | `WeatherRouteAnalyzer.swift` | `core/weather/WeatherRouteAnalyzer.kt` | ✅ |
| Provider diagnostics | `WeatherProviderDiagnostics.swift` | `core/weather/WeatherProviderDiagnostics.kt` | ✅ |
| Turbulence model, ride reports | `TurbulenceModel.swift`, `RideReportEngine.swift` | `core/weather/deviation/` | ✅ 15 tests |
| Route weather conflict detection | `RouteWeatherConflictDetector.swift` (2236) | `core/weather/deviation/RouteWeatherConflictDetector.kt` — every tuning constant carried at its exact value | ✅ |
| Deviation engine + phraseology | `WeatherDeviationEngine.swift`, `WeatherDeviationPhraseology.swift` | `core/weather/deviation/` | ✅ 88 tests |
| Radar / satellite precipitation | `RadarImageSampler`, `PrecipitationProviders`, `PrecipitationOverlayService`, `RadarPrecipitationProvider`, `RadarOverlayModel` | `core/weather/radar/` — NOAA → OPERA → NASA GIBS selection, with a failure cooldown so a dead provider falls through rather than blanking the map | ✅ |
| Raster → precipitation cells | `RadarImageSampler.swift` | `core/weather/radar/RadarImageSampler.kt`; the PNG/GeoTIFF decode is an injected `RasterImageDecoder`, so colour classification, clustering and the Mercator pixel→coordinate inversion are all testable without an image codec | ✅ 25 tests |
| EUMETNET OPERA (**disabled on iOS**) | `EUMETNETORDClient`, `OPERACompositeRenderer` | Both ported; the shipping provider list constructs the provider with `useORD = false`, exactly as iOS does. Europe falls through to the clearly labelled NASA satellite estimate. See `Docs/ANDROID_DATA_SOURCES.md` | ✅ |
| Weather session (fetch, ride recompute, ATIS cadence, overlay descriptor) | `AppModel.swift` `refreshWeather`/`recomputeRideItems` | `core/weather/WeatherSessionController.kt` — a separate object from the flight coordinator, because the ATC state machine and the weather feed share almost nothing but the flight plan | ✅ 18 tests |
| Never infer turbulence from radar | design rule | The deviation engine keeps radar precipitation, satellite estimate, advisories and pilot reports as separate inputs; a satellite estimate is never labelled radar | ✅ tested |
| Precipitation raster drawn on the route map | `RadarOverlayRenderer.swift` | `app/map/RadarRasterLoader.kt` fetches and decodes; `ui/map/RouteMapLayers.kt` draws it **over** the route, markers and aircraft, which is where iOS puts it too — a SwiftUI `.overlay` on the whole map rather than a layer inside it. Requested for the region on screen once the map settles, matching iOS's `onMapCameraChange(frequency: .onEnd)`. Placement is exact rather than approximate: the service returns the bbox it rendered, where iOS aligns to the visible region and calls that "intentionally approximate" | 🟡 |

## 5. Airport surface and taxi routing

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| OSM constants, licensing, attribution | `OSMSurfaceConstants.swift` | `core/surface/OSMSurfaceConstants.kt` + `core/ui/LegalStrings.OpenStreetMap` | ✅ tested |
| Overpass query, error-page detection | `OverpassQuery.swift`, `OverpassErrorPage.swift` | `core/surface/` | ✅ |
| OSM parsing and normalisation | `OSMElement.swift`, `OSMSurfaceNormalizer.swift` | `core/surface/OSMSurfaceNormalizer.kt` | ✅ |
| Surface cache (75-day refresh) | `AirportSurfaceCache.swift` | `core/surface/AirportSurfaceCache.kt` over `FileStore` | ✅ |
| Provider, failover, backoff, stale-serve | `AirportSurfaceProvider.swift` | `core/surface/AirportSurfaceProvider.kt` — endpoint failover order, 90 s query budget, per-airport backoff, request coalescing | ✅ |
| Aircraft size classes, stand identifiers | `AircraftSizeClass.swift`, `StandIdentifier.swift` | `core/surface/` | ✅ |
| Surface graph + routing | `SurfaceGraph*.swift`, `TaxiRouteEngine.swift` (1030) | `core/surface/routing/TaxiRouteEngine.kt` — A* that never chooses on distance alone: penalties push routes off unnecessary runway crossings, closed and unnamed segments, and opening 180° pivots. `AirportSurfaceCoordinator` is constructed in `AppGraph` and driven from the flight's own states, so routes are computed. Its async work runs on `Dispatchers.Default` — A* on the main thread was a previous ANR | 🟡 |
| Route tracking, off-route, recalculation | `RouteTracker.swift` | `core/surface/routing/RouteTracker.kt` | ✅ |
| Runway-crossing state machine | `RunwayCrossingState.swift` | `core/surface/routing/RunwayCrossingState.kt` — the read-back is what authorises the crossing | ✅ |
| Route confidence | `SurfaceConfidenceEvaluator.swift` | `core/surface/routing/SurfaceConfidenceEvaluator.kt`; a low-confidence route draws dashed on the map rather than being presented as fact | ✅ |
| Automatic gate assignment | `GateAssignment.swift` (622) | `core/surface/routing/GateAssignment.kt` | ✅ |
| Taxi phraseology | `TaxiPhraseology.swift` | `core/surface/routing/TaxiPhraseology.kt` — reached through `FlightSessionCoordinator`'s `taxiContextProvider`, which fills the `taxiway` / `crossingRunway` / `parkingTaxiway` fields `buildContext` used to hardcode empty. With no route it degrades to the generic form | ✅ 2 tests |
| Surface coordinator (map lifecycle) | `AirportSurfaceCoordinator.swift` (1565) | `core/surface/routing/AirportSurfaceCoordinator.kt` | ✅ |
| Surface session (which fields load, and what is said when they can't) | part of the same file | `core/surface/SurfaceSessionController.kt` — an Overpass outage is recorded and reported, never thrown: the taxi map simply doesn't draw and taxi phrasing stays generic | ✅ 8 tests |

## 6. ATIS

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| D-ATIS model and feed parser | `AirportATIS.swift`, `ATISParser.swift` | `core/atis/` — an unrecognised payload yields nothing, and the whole feature disappears for that field. Nothing is ever fabricated | ✅ |
| Active-runway extraction | `ATISRunwayParser.swift` | `core/atis/ATISRunwayParser.kt` — the coded grammar, including the outage/closure suppression | ✅ |
| Keyless public D-ATIS client | `ATISService.swift` | `core/atis/ATISService.kt` — 2-minute TTL, request coalescing, exponential backoff, a cached 404 miss so a field with no D-ATIS is never re-asked | ✅ |
| Spoken ATIS phraseology | `ATISPhraseology.swift` (607) | `core/atis/ATISPhraseology.kt` — every coded observation group decoded as an ATIS voice reads it, plus the ~180-entry abbreviation table | ✅ |
| Information-letter memory (only after tuning) | `AppModel.swift` | `WeatherSessionController.noteAtisTuned` — the app never claims the pilot has information it only fetched in the background | ✅ tested |
| **Total** | 1134 lines of Swift | `core/atis/` (6 files) | ✅ **90 tests** |

## 7. En-route sectors, Mock Mode, persistence

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| Center sector database + tracker | `Enroute/*.swift` + `CenterSectors.json` | `core/enroute/`; the JSON is byte-identical and loaded from the classpath. `FlightSessionCoordinator` feeds every airborne fix to the tracker and publishes the sector's radio name, loading the ~550 KB database off-thread on the first fix that needs it | ✅ 19 tests |
| Center-to-Center hand-offs | `AppModel.updateCenterSector` / `announceCenterSectorHandoff` | A crossing issues a frequency hand-off naming the next sector, held (never dropped) until the radio is clear — no read-back outstanding, no hand-off pending, no go-around being flown. The check-in that follows is answered with radar contact rather than the next clearance, and the working sector survives a reconnect so a hand-off already made is not re-announced | ✅ 6 tests |
| Mock Mode scripted flight | `MockSimulatorFeed.swift` | `core/mock/MockSimulatorFeed.kt` — the same demo flight, KIAH → KMSP at FL370, to the digit | ✅ 12 tests |
| Session resume | `SessionStateStore.swift` | `core/persistence/SessionStateStore.kt`, wired in `AppGraph.warmUp()`. `FlightSessionCoordinator.captureSnapshot()` writes on every new transmission and `restore()` reads on launch; a mock snapshot is never restored into a live flight, or the reverse, and a deliberate Stop clears it | ✅ 25 tests |
| Saved flights | `SavedFlightStore.swift` | `core/persistence/SavedFlightStore.kt` — **never constructed in `:app`**; nothing saves or reads a flight | 🔌 23 tests total in the package |
| Review prompt | `ReviewRequestManager.swift` | 🔵 Play In-App Review replaces `SKStoreReviewController` | ⬜ **not implemented** — the engagement counting is ported, the Play API call is not |

## 8. Audio

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| Transmitter saturation (tanh soft clip) | `RadioSaturation.swift` | `core/audio/RadioAudio.applySaturation` — drive 8, mix 0.7, gain 0.75, unchanged | 🟡 |
| Comms band-pass 320 Hz – 3.3 kHz | `RadioVoiceProcessor.swift` | `core/audio/RadioAudio.applyCommsBand` | 🟡 |
| PTT key-down thump (~32 ms) | `RadioAudioEngine.swift` | `RadioAudio.keyClickSamples` — every envelope constant carried | 🟡 |
| PTT release squelch tail (~140 ms, 3 crackles) | same | `RadioAudio.squelchTailSamples` | 🟡 |
| Static bed + squelch-open levels | same | `RadioAudio.fillStaticBed`, `chatterLevels` | 🟡 |
| Audio graph | `AVAudioEngine` | 🔵 `app/audio/RadioAudioEngine.kt` — one render loop owns the main `AudioTrack`; a second track carries bursts so they can fire over a call and are never ducked | 🔵 |
| Text-to-speech | `AVSpeechSynthesizer` | 🔵 `app/audio/AndroidSpeechService.kt` — `TextToSpeech`; the radio path renders via `synthesizeToFile` (a WAV) because Android has no `write(_:)`, then decodes and processes identically | 🔵 |
| Per-facility voices, pilot/ATIS voices | `VoiceCatalog.swift`, `SpeechService.swift` | Voice selection ported; the curated iOS voice list (Karen/Daniel/Moira/Rishi/Samantha) does not exist on Android — the picker offers the device's installed voices, English first | 🔵 |
| Speech rate mapping | iOS absolute rate | 🔵 Android's rate is a multiplier; the iOS default maps to 1.0 so an untouched slider sounds the same | 🔵 |
| Push-to-talk recognition | `SFSpeechRecognizer` | 🔵 `app/audio/PushToTalkRecognizer.kt` — `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE`, so a pilot's transmissions are never sent to a speech service. With no on-device model it fails with a reason rather than falling back to the network | 🟡 |
| Ambient chatter | `Chatter/*.swift` (1175) | `core/chatter/` (pacing, script generation, ducking, PTT interaction) + `app/audio/AndroidChatterRadio.kt` | ✅ 26 tests |
| Chatter as the background keep-alive | iOS: the chatter audio session is what keeps the process alive | 🔵 **Not so here.** The foreground service keeps the flight running and needs no audio; chatter is only ever a feature. Playing near-silent audio to stay alive is what Play policy treats as abuse. This is why the Live Flight Update and chatter are **independent** settings on Android where iOS interlocks them | 🔵 |

## 9. Subscriptions

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| Products (monthly / annual / lifetime) | `SubscriptionProducts.swift` | `core/billing/LiveAccess.kt`, ids in `AppConfig.Billing` | ✅ |
| Entitlement rule (any one of three) | `EntitlementManager.swift` | `core/billing/LiveAccessRules.kt` | ✅ |
| Purchase / restore / pending / acknowledge | `StoreKitService.swift` | 🔵 `app/billing/PlayBillingRepository.kt` — Play Billing; restore is a re-query, not `AppStore.sync()` | 🟡 **unverified** — the one file that could not be compile-checked |
| Offline cached entitlement | — | 🔵 New on Android: the last confirmed entitlement bridges an offline launch, excluded from backup and device transfer | 🟡 |
| Terms link | Apple standard EULA | 🔵 Google Play Terms — Apple's link must not appear on Android | ✅ |
| Manage subscription | Apple's sheet | 🔵 Play's subscription-management deep link | 🟡 |
| Renewal disclosure | Apple Account wording | 🔵 Rewritten for Google Play | ✅ |

## 10. Background execution and the live notification

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| Background operation | `audio` background mode, gated on chatter | 🔵 `ActiveFlightService` — a foreground service, **not** gated on chatter, and never silent audio. See `Docs/ANDROID_BACKGROUND_EXECUTION.md` | 🟡 |
| Live Activity | ActivityKit + Dynamic Island | 🔵 **Live Flight Update** — the same content on an ongoing notification, promoted on Android 16+. Never called a "Live Activity" in Android UI | 🟡 |
| Live Activity actions | Read Back, Check In, **Refresh** | 🔵 Read Back and Check In. **Refresh is deliberately absent**: it exists on iOS because ActivityKit throttles a backgrounded app's pushes; Android has no such throttle, so the numbers stay live on their own | 🔵 |

## 11. UI

| iOS screen | iOS file | Android | Status |
| --- | --- | --- | --- |
| Tab shell (ATC, Flight, Weather, Settings, Diagnostics) | `ContentView.swift` | `ui/screens/AppShell.kt` — same tabs, same order, Material 3 navigation bar | ✅ type-checked |
| Shared components (card, pill, data row, action button, frequency button) | `Components.swift` | `ui/components/Components.kt` | ✅ |
| ATC screen | `ATCView.swift` (681) | `ui/screens/AtcScreen.kt` — same cards in the same order, copy verbatim | 🟡 type-checked |
| Flight screen | `FlightView.swift` | `ui/screens/FlightScreen.kt` | 🟡 type-checked |
| Subscription screen | `SubscriptionView.swift` | `ui/screens/SubscriptionScreen.kt` | 🟡 type-checked |
| Weather screen | `WeatherView.swift` | `ui/screens/WeatherScreen.kt` — same cards, same legends, disclaimer copy verbatim | 🟡 type-checked |
| Settings screen | `SettingsView.swift` (542) | `ui/screens/SettingsScreen.kt` — same sections, labels and footers | 🟡 type-checked |
| Diagnostics screen | `DiagnosticsView.swift` | `ui/screens/DiagnosticsScreen.kt` | 🟡 type-checked |
| Phraseology profiles | `PhraseologyProfilesView.swift` | `ui/screens/PhraseologyProfilesScreen.kt` — swipe-to-delete behind an EditButton becomes an explicit per-row delete, and `ShareLink` becomes the system share sheet | 🟡 type-checked |
| Flights list | `FlightsListView.swift` | **Not built.** `SavedFlightStore` is ported and tested; the screen that lists them is not | ⬜ |
| Taxi map | `TaxiMapView.swift` (468) | `ui/map/TaxiMapLayers.kt` — draws the route, hold-short bars and crossings, and carries the coordinator's `crossingActions` / `offRouteActions` plus a read-back button — without which `AWAITING_PILOT_READBACK` would wedge a taxi that reached a runway. `relevantRunways` is filtered to the runways the route touches, the restriction iOS adopted after overlay volume crashed MapKit | 🟡 |
| Route map | `RouteMapView.swift` | `ui/map/RouteMapLayers.kt` — the iOS drawing order preserved exactly, because the order is what says which things the pilot must act on | 🟡 type-checked |
| Route map base layer | MapKit's own base map | `ui/map/BaseMapLayers.kt` over `core/map/MapGraticule.kt`, `core/map/CoastlineData.kt` and `core/map/BaseImageryService.kt` — a graticule, a scale bar and bundled Natural Earth coastlines that need no network, plus a keyless NASA GIBS satellite underlay that degrades to them when there is no signal. The three `:core` halves — graticule, coastline dataset, imagery service and window — are tested; `BaseMapLayers.kt` is drawing code in `:app` and is type-checked only. Not a street map; see `Docs/ANDROID_MAPPING.md` | 🟡 |
| Radar raster overlay on the map | `RadarOverlayRenderer.swift` | Not composited onto the canvas yet — see the Weather section | ⬜ |
| Pull-to-refresh on Weather | `.refreshable` | 🔵 A refresh action in the top bar. Compose's pull-to-refresh is still experimental in the pinned BOM, and a screen whose only way to load weather is an unstable API is the wrong trade | 🔵 |
| SimBrief | `SimBriefBrowserView.swift` (SFSafariViewController) | 🔵 `simbrief/SimBriefLauncher.kt` — Custom Tabs, the direct counterpart. A WebView would reintroduce the exact focus problem iOS moved away from | 🟡 |
| SF Symbols | throughout | 🔵 `ui/components/Icons.kt` maps semantic keys to Material Symbols; each substitution is commented with the SF Symbol it replaces | ✅ |
| Dynamic colour | n/a | 🔵 **Deliberately off.** Material You would let the wallpaper repaint the app and break the visual-identity parity this port is for | ✅ |

## 12. Legal, attribution, data sources

| Obligation | Android | Status |
| --- | --- | --- |
| Infinite Flight non-affiliation | `LegalStrings.INFINITE_FLIGHT_DISCLAIMER` | ✅ tested |
| Simulation-only disclaimers | `LegalStrings` | ✅ tested |
| OSM attribution on every surface map, tappable, ODbL 1.0 (**not** CC BY 4.0) | `LegalStrings.OpenStreetMap` | ✅ tested, including a test that CC BY 4.0 never appears |
| VATSpy CC BY-SA 4.0 + ShareAlike + simulated-frequency notice | `LegalStrings.CenterSectors` | ✅ tested |
| Weather source labelling (radar vs satellite estimate) | `LegalStrings` | ✅ |
| SimBrief non-affiliation | `SimBriefStrings.NOT_AFFILIATED` | ✅ |
| Privacy policy link | `AppConfig.Links.PRIVACY_POLICY` | ✅ |

---

## Android-native substitutions, in one place

| Apple | Android | Why |
| --- | --- | --- |
| SwiftUI | Jetpack Compose | — |
| `ObservableObject` + 40 `@Published` | one `StateFlow<FlightSessionState>` | One coherent snapshot per recomposition |
| `async/await`, `Task`, `actor` | coroutines, `CoroutineScope`, `Mutex` | — |
| `URLSession` + `URLCache` | OkHttp + `Cache` behind `HttpFetching` | Same revalidation semantics, testable |
| `AVSpeechSynthesizer` | `TextToSpeech` (+ `synthesizeToFile` for the radio path) | No `write(_:)` equivalent |
| `AVAudioEngine` | `AudioTrack` + the DSP in `:core` | No node graph; the maths moves rather than being re-tuned |
| MapKit | Compose canvas + `MapProjection` | No SDK meets the no-key/no-bill bar — see `Docs/ANDROID_MAPPING.md` |
| StoreKit 2 | Google Play Billing | — |
| ActivityKit Live Activity | Live Flight Update (promoted ongoing notification) | — |
| `SFSafariViewController` | Custom Tabs | Direct counterpart |
| `UserDefaults` | DataStore behind `KeyValueStore` | — |
| `Codable` | `kotlinx.serialization` | — |
| `CLLocationCoordinate2D` | `core/geo/Coordinate` | Keeps `:core` framework-free |
| SF Symbols | Material Symbols | Not licensable off-Apple |
| `SKStoreReviewController` | Play In-App Review | — |
| XCTest | JUnit / `kotlin.test` | — |

## Known parity gaps

Stated plainly, and none of them hidden behind a ✅ elsewhere in this file.

1. **`:app` has never been compiled.** No Android SDK, no Google Maven. The Compose
   screens are type-checked; everything touching an Android API is not. This is the
   single biggest risk in the port and the first item on the release checklist.

   To partly offset it, an adversarial static review was run over the 19 `:app` files
   with no compiler coverage — six dimensions (calls into `:core`, Android framework
   APIs, Play Billing against its pinned version, the Compose wiring layer,
   manifest/resources/Gradle, concurrency and lifecycle), each finding checked against
   the declaration it contradicted. It found **16 real defects, six of them
   build-breaking**, including an app that would never have spoken a word (the TTS
   engine was never initialised), a paywall whose buttons could never enable (the
   BillingClient was never started), and a billing path that revoked Live access from
   paying customers.

   A **second** review then went over those fixes — a fix to uncompiled code is just
   more uncompiled code — and found seven more, one of them caused by the first round:
   the ATIS rewrite had deleted `speakChatter()` while its caller remained, which alone
   would have failed the build. It also found push-to-talk keying its gesture on the
   state its own press handler flips (so the mic latched open), background chatter that
   was never started, a render loop that could wedge the speech pump permanently, and
   the flight session split across two dispatchers.

   All 23 are fixed. A static review is not a compiler, so this lowers the risk rather
   than removing it — and the second round is the evidence for exactly that.
2. **No physical-device or emulator testing.** No device, no emulator, and no Infinite
   Flight installation to connect to. Nothing in the port has been *heard* — the radio
   effect chain, the TTS voices, the chatter mix and the squelch bursts are all ported
   maths that has never been played.
3. **100 parity gaps found by audit**, listed in `ANDROID_PARITY_GAPS.md` — 37 of them
   high, and 47 of them subsystems that existed in `:core` with passing tests and were
   constructed nowhere in `:app`. **85 are now closed, including all 37 rated high**; 5
   medium and 7 low remain, each still carrying its own entry there. The rows below
   describe what is *ported*; a ✅ or 🟡 on a row does not by itself mean the running app
   reaches it.
4. **EUMETNET OPERA rendering stays off**, exactly as it is on iOS. Not a gap so much as
   a carried-across decision, recorded here so it is not mistaken for one.

## What was verified, and how

| | |
| --- | --- |
| `:core` compile | ✅ `./gradlew -c settings-core.gradle.kts :core:compileKotlin` |
| `:core` tests | ✅ **1043 tests, 89 classes, 0 failures, 0 skipped** |
| Compose screens type-check | ✅ `./gradlew -c settings-uicheck.gradle.kts :uicheck:compileKotlin` |
| `:app` compile | ❌ not possible here |
| Instrumented tests | ❌ not possible here |
| Device / emulator | ❌ none available |
| Release AAB | ❌ requires the Android SDK; the Gradle configuration for it is in place |

### `:core` tests by area

| Area | Tests |
| --- | --- |
| Weather (parsers, service, deviation, radar, session) | 232 |
| Airport surface and taxi routing | 133 |
| Phraseology (both packs, airlines, profiles, validator) | 96 |
| ATIS | 90 |
| Infinite Flight Connect | 81 |
| Flight session (incl. the gate-to-gate scenario) | 30 |
| Geo and heading solving | 27 |
| Ambient chatter | 26 |
| Persistence | 23 |
| Center sectors | 19 |
| Map projection | 14 |
| Mock Mode (incl. the golden gate-to-gate transcript) | 13 |
| Settings | 12 |
| Live Flight Update | 10 |
| Airports | 8 |
| Legal strings and attribution | 7 |
