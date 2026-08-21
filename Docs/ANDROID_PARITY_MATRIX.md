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
| ⬜ **Not started** | No Android code yet. |
| 🔵 **Android-native substitution** | Deliberately different because the platform is, with the difference stated. |

> **Verification status of the two modules.** `:core` is compiled and its tests are run
> in the environment this port was written in — the numbers below are real. **`:app` has
> never been compiled**: that environment had no Android SDK and no access to Google's
> Maven repository. The *pure* Compose screens are type-checked against JetBrains Compose
> (`settings-uicheck.gradle.kts`), so their Kotlin is verified; the Android-API code
> around them — Activity, ViewModels, service, audio, billing, resources — is written but
> unverified. Expect the first Android Studio build to surface ordinary integration
> fixes. This is recorded here rather than discovered later.

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
| Automatic flow: takeoff, monitor-Tower, hand-offs, go-around, gate arrival | `AppModel.swift` 2107–2400, 4386–4780 | `core/session/FlightSessionCoordinator.kt` | 🟠 **Partial** — the gate, ordering and button rules are ported and tested; the telemetry-driven trigger loop that drives them is the largest remaining gap |

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
| Turbulence model, ride reports | `TurbulenceModel.swift`, `RideReportEngine.swift` | `core/weather/deviation/` | ⬜ |
| Route weather conflict detection | `RouteWeatherConflictDetector.swift` (2236) | `core/weather/deviation/` | ⬜ |
| Deviation engine + phraseology | `WeatherDeviationEngine.swift`, `WeatherDeviationPhraseology.swift` | `core/weather/deviation/` | ⬜ |
| Radar / satellite precipitation | `RadarImageSampler`, `PrecipitationProviders`, `PrecipitationOverlayService`, `RadarPrecipitationProvider`, `RadarOverlayModel` | `core/weather/radar/` | ⬜ |
| EUMETNET OPERA (**disabled on iOS**) | `EUMETNETORDClient`, `OPERACompositeRenderer` | Stays disabled — see `Docs/ANDROID_DATA_SOURCES.md` | ⬜ |
| Never infer turbulence from radar | design rule | Preserved in the port plan; the deviation engine keeps radar precipitation, satellite estimate, advisories and pilot reports as separate inputs | 🟠 |

## 5. Airport surface and taxi routing

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| OSM constants, licensing, attribution | `OSMSurfaceConstants.swift` | `core/surface/OSMSurfaceConstants.kt` + `core/ui/LegalStrings.OpenStreetMap` | ✅ tested |
| Overpass query, error-page detection | `OverpassQuery.swift`, `OverpassErrorPage.swift` | `core/surface/` | 🟠 |
| OSM parsing and normalisation | `OSMElement.swift`, `OSMSurfaceNormalizer.swift` | `core/surface/OSMSurfaceNormalizer.kt` | 🟠 |
| Surface cache (75-day refresh) | `AirportSurfaceCache.swift` | `core/surface/AirportSurfaceCache.kt` over `FileStore` | 🟠 |
| Provider, failover, backoff, stale-serve | `AirportSurfaceProvider.swift` | `core/surface/` | 🟠 |
| Aircraft size classes, stand identifiers | `AircraftSizeClass.swift`, `StandIdentifier.swift` | `core/surface/` | 🟡 |
| Surface graph + routing | `SurfaceGraph*.swift`, `TaxiRouteEngine.swift` (1030) | `core/surface/routing/` | ⬜ |
| Route tracking, off-route, recalculation | `RouteTracker.swift` | `core/surface/routing/` | ⬜ |
| Runway-crossing state machine | `RunwayCrossingState.swift` | `core/surface/routing/` | ⬜ |
| Route confidence | `SurfaceConfidenceEvaluator.swift` | `core/surface/routing/` | ⬜ |
| Automatic gate assignment | `GateAssignment.swift` (622) | `core/surface/routing/` | ⬜ |
| Taxi phraseology | `TaxiPhraseology.swift` | `core/surface/routing/` | ⬜ |
| Surface coordinator (map lifecycle) | `AirportSurfaceCoordinator.swift` (1565) | `core/surface/routing/` | ⬜ |

## 6. ATIS

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| D-ATIS fetch, parse, runway extraction, spoken phraseology, information-letter memory | `ATIS/*.swift` (1134) | `core/atis/` | ⬜ |

## 7. En-route sectors, Mock Mode, persistence

| iOS capability | iOS files | Android | Status |
| --- | --- | --- | --- |
| Center sector database + tracker | `Enroute/*.swift` + `CenterSectors.json` | `core/enroute/`, JSON bundled as a classpath resource | 🟠 in progress |
| Mock Mode scripted flight | `MockSimulatorFeed.swift` | `core/mock/` | ⬜ |
| Session resume | `SessionStateStore.swift` | `core/persistence/` | ⬜ |
| Saved flights | `SavedFlightStore.swift` | `core/persistence/` | ⬜ |
| Review prompt | `ReviewRequestManager.swift` | 🔵 Play In-App Review replaces `SKStoreReviewController` | ⬜ |

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
| Push-to-talk recognition | `SFSpeechRecognizer` | 🔵 `SpeechRecognizer`, on-device where available | ⬜ |
| Ambient chatter | `Chatter/*.swift` (1175) | `core/chatter/` | ⬜ |

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
| ATC screen | `ATCView.swift` (681) | `ui/screens/AtcScreen.kt` — same cards in the same order, copy verbatim | 🟡 |
| Flight screen | `FlightView.swift` | `ui/screens/FlightScreen.kt` | 🟡 |
| Subscription screen | `SubscriptionView.swift` | `ui/screens/SubscriptionScreen.kt` | 🟡 |
| Weather screen | `WeatherView.swift` | — | ⬜ |
| Settings screen | `SettingsView.swift` (542) | — | ⬜ |
| Diagnostics screen | `DiagnosticsView.swift` | — | ⬜ |
| Flights list | `FlightsListView.swift` | — | ⬜ |
| Phraseology profiles | `PhraseologyProfilesView.swift` | — | ⬜ |
| Taxi map | `TaxiMapView.swift` (468) | `ui/map/` — canvas and projection ready; layers pending | 🟠 |
| Route map + radar overlay | `RouteMapView.swift`, `RadarOverlayRenderer.swift` | `ui/map/` — same | 🟠 |
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

1. **The telemetry-driven automatic ATC loop** is the largest single gap. Its component
   rules — the read-back gate, flow ordering, button availability, the state machine,
   phase detection — are ported and tested; the loop that calls them on each snapshot is
   not yet complete.
2. **Weather deviation, radar overlays, ATIS, taxi routing, runway crossings, Mock Mode,
   session persistence and ambient chatter** are not yet ported. These are the rows
   marked ⬜, and they are substantial: roughly 12,000 lines of Swift between them.
3. **Five screens** (Weather, Settings, Diagnostics, Flights, Phraseology profiles) and
   the two map layer sets are not yet built.
4. **`:app` has never been compiled.** See the note at the top.
5. **No physical-device testing** has happened: there was no Android device, emulator or
   SDK available, and no Infinite Flight installation to connect to.
