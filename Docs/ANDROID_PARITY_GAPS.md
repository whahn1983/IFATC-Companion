# Parity gaps found by audit

An adversarial sweep of the whole iOS source against the Android port, run after
`ANDROID_REMAINING_WORK.md` had declared feature parity complete. It was not.

Six lanes read the iOS tree — the ATC brain, weather and ATIS, airport surface / Connect /
Mock Mode, every screen control by control, `AppModel` and the models, and audio / billing /
review — and every claimed gap was then handed to a second agent whose job was to **prove
it was already there**, defaulting to "present". 104 claims were examined; **100 survived**.

That confirmation rate is high enough to be worth distrusting, so three findings were then
verified by hand against both sources before this document was written: the check-in reply,
`sayAgain`, and `unable`. All three were exactly as reported.

## Progress

80 of the 100 are closed and three are partly closed; the rest stand. **All 37 rated high
are closed** — every gap a pilot meets in a normal flight. What remains is 7 medium and 10
low. Each closed entry below carries a ✅ (or 🟡) line naming what closes it and, where a
piece is still open, what that piece is — so this document stays the record of what the
audit found *and* of what has been done about it rather than being quietly rewritten.

The closed set was deliberately taken worst-first rather than easiest-first: the app had
no data source in either mode, no flight plan, no Infinite Flight link, no entitlement
enforcement, six of the pilot's response buttons put a call in the transcript and left the
frequency silent, and the whole simulated weather-deviation flow — 4,800 lines of ported,
tested logic — was constructed nowhere. With that block done, the settings whose switches
rendered and controlled nothing followed: the endpoint field, "Keep screen awake", "Live
flight notification", automatic gate assignment, and the chatter's runway pools.

One thing inside the weather flow is deliberately still out: the recovery paths for a
deviation already under way — the telemetry-discontinuity resync, the off-path re-plan, the
redraw when an entry point falls behind the aircraft, and the re-vector onto a fresh line
while already committed. A pilot who flies well off the drawn line keeps the line they were
given rather than being re-vectored onto a new one. It is called out in
`WeatherDeviationController`'s own KDoc so nobody has to find it by flying it.

## What this means

The five gaps `ANDROID_REMAINING_WORK.md` tracked were real and are now closed. They were
not the whole list — they were the ones somebody had already noticed. This is the same
failure the parity matrix's 🔌 status was introduced for, at a larger scale: **47 of the 100
were subsystems that exist in `:core`, pass their tests, and were constructed nowhere in
`:app`.**

Nothing here is a compile error and nothing here fails a test. Every one of them is a
feature that is present in the codebase and absent from the running app, or present in the
running app and behaving differently from the iOS build the pilot is comparing against.

## Shape


| | Count |
| --- | --- |
| **High** — a pilot would hit this in a normal flight | 37 |
| **Medium** — reachable, but not on every flight | 46 |
| **Low** — cosmetic, or a rarely-taken path | 17 |

| Kind | Count |
| --- | --- |
| **Ported, not wired** — in `:core`, tested, constructed nowhere in `:app` | 47 |
| **Behaves differently** — present, but not what iOS does | 30 |
| **Absent** — no Android counterpart at all | 23 |

---

## High (37)

### Session orchestration and models

**Automatic gate assignment never runs, though the Settings toggle for it ships**  
✅ Closed: `AutoGateController` runs the picker on an endpoint change, on a gate edit, when the aircraft comes to rest, and on the Settings toggle — and drops a gate assigned at another airport before reading anything.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:3094 (autoAssignGatesIfNeeded), :3035 (updateAutoGatesFromTelemetry), :3261 (autoAssignGate), :3203 (mayAutoAssignGate), :3129 (dropForeignAutoGates), :3052 (retryFailedAutoGatesIfDue)`

- **iOS:** With "Assign gates automatically" on, the app picks a stand from the OSM surface for the departure and arrival, stamps it so it is never allowed to overwrite a gate the pilot typed, re-runs when the aircraft parks somewhere new, upgrades a guessed gate once the aircraft's parked position is known, drops stamps belonging to a different airport, and retries reads that failed.
- **Android:** `GateAssigner` (core/surface/routing/GateAssignment.kt:408) and its `assign`/`mayAssign` are called from nowhere in `:app` or in the coordinator, and `AutoGateStamp` is only used inside its own file. Meanwhile `SettingsScreen.kt:429-430` renders the `autoAssignGates` switch and `SettingsRepository` persists it, so the pilot can turn on a feature that has no implementation behind it.

**Request Higher / Request Lower get no controller answer and never change the assigned altitude**  
✅ Closed: `altitudeRequest` posts the climb/descend instruction with a read-back, denies a climb into a moderate-or-worse reported ride, and updates `assignedAltitude`.  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:4478 (requestHigher), :4494 (requestLower)`

- **iOS:** The pilot request is posted, then the controller answers — `climbMaintain` / `descendPilotsDiscretion` with a matching altitude read-back — and `assignedAltitude` moves to the new level. A climb into a band with a moderate-or-worse ride report is denied with "unable higher, traffic and reported turbulence at that level".
- **Android:** `performPilotAction` posts `pilotEngine.requestHigher/requestLower` and then calls `onPilotRequest`, whose `when` has no REQUEST_HIGHER/REQUEST_LOWER branch and falls through to `else -> return` (FlightSessionCoordinator.kt:876-886). `PhraseologyEngine.climbMaintain` (line 441) and `.descendPilotsDiscretion` (line 471) exist and are called from nowhere in main. `assignedAltitude` is only ever written by `updateAssignedAltitude` on a state transition and by the go-around. There is no turbulence-band denial.

**Ride Report and Destination Weather requests are unanswered — RideReportEngine is never constructed**  
✅ Closed: `WeatherAnswering` is the seam; `WeatherSessionController` implements it and the coordinator posts the ride report / destination weather with a courtesy Roger read-back.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4880 (requestRideReport), :4929 (requestDestinationWeather), :4912 (computeSmootherAltitude)`

- **iOS:** On Center, Ride Report posts the pilot's request and Center reads back the PIREP-derived ride reports along the route, optionally offering a smoother altitude; Dest WX has Center read the destination weather.
- **Android:** `RideReportEngine` (core/weather/deviation/RideReportEngine.kt:24), which owns both replies including `destinationWeather` at line 177, is constructed only in RideReportEngineTest. `performPilotAction` posts `pilotEngine.requestRideReports` / `pilotEngine.requestWeather` and `onPilotRequest` has no branch for either, so the pilot transmits and nothing answers. FlightViewModel.onContactAtcAboutWeather (FlightViewModel.kt:535) routes the weather banner's CTA into the same dead end.

**Takeoff and landing clearances speak "wind 000 at 0" — buildContext hardcodes the wind to zero**  
✅ Closed: `buildContext` reads the METAR through `WeatherAnswering.metar`, falling back to 270 at 8 rather than to zero.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4010-4012 (`let metar = arrival ? destinationMETAR : departureMETAR; let windDir = metar?.windDirection ?? 270; let windSpeed = metar?.windSpeed ?? 8`)`

- **iOS:** The context carries the field's METAR wind (falling back to 270 at 8), which the takeoff clearance, the line-up-and-wait call and the cleared-to-land call all speak: "United 598, wind 270 at 8, runway 26L, cleared for takeoff".
- **Android:** `FlightSessionCoordinator.buildContext` (core/session/FlightSessionCoordinator.kt:1508-1509) sets `windDirection = 0, windSpeed = 0` literally, with no METAR lookup. `ATCStateMachine.kt:119-120,129-130,194-195` feed those straight into `PhraseologyEngine.clearedForTakeoff` and `.clearedToLand`, which format `%03d` of the direction — so every clearance in the app says "wind 000 at 0".

**The arrival Ramp / "To Gate" flow does nothing — the To Gate button is offered and is a no-op**  
✅ Closed: `contactRamp` → `arriveAtGate` posts the inbound call and the gate routing, then stages `monitor ramp to the gate` and the block-in from telemetry.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:2687 (arriveAtGate), :1966-1976 (the gateMonitored staging in handle(state:)), :2755 (completeGateArrival), :2893 (announceArrival)`

- **iOS:** Tuning Ramp on arrival and tapping To Gate posts the pilot's inbound call, Ramp answers with a routing to the gate, then as the aircraft slows below 8 kt Ramp says "monitor ramp to the gate", and once stopped at the stand with the parking brake set the block-in and "flight complete" are announced.
- **Android:** `FlightSessionCoordinator.performPilotAction` (core/session/FlightSessionCoordinator.kt:859) handles `PilotAction.TO_GATE` with `return` and a comment saying the subsystem "is wired in separately" and "until those land, the button is not offered" — but it *is* offered: `PilotActionAvailability.kt:92` returns `setOf(PilotAction.TO_GATE)` whenever the working facility is RAMP after departure, and FlightViewModel.onPilotAction routes it straight to performPilotAction. `RampPhraseologyEngine.arrivalInbound`, `.proceedToGate` and `.monitorRampToGate` are called from nowhere in main. There is no `isSlowingAtGate` equivalent and no `gateMonitored` stage at all.

**The departure heading and first-fix name are never computed, so the takeoff clearance always says "fly runway heading"**  
✅ Closed: `departureGuidance` measures the bearing from the runway marker, the on-ground position or the field, converts true→magnetic, and names the next unpassed fix.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4048-4090 (headingOrigin / interceptFix / assignedHeading), :4176 (`firstFixName: directFix?.name ?? ""`)`

- **iOS:** iOS measures the bearing from the departure runway marker (or the on-ground position, or the field) to the SID's first fix, converts it from true to magnetic via HeadingSolver, and hands it to the takeoff clearance — which says "fly heading 085" unless that is within 10° of runway heading. The departure climb call then says "resume own navigation, direct <fix>".
- **Android:** buildContext never sets `departureHeading`, `runwayIsKnown` or `firstFixName`, so they take their ATCContext defaults of 0, true and "". `PhraseologyEngine.kt:386` takes the `departureHeading <= 0` branch on every departure, so the clearance is always "fly runway heading". `HeadingSolver` (core/geo/HeadingSolver.kt:44) is referenced from nowhere in main; nor are `FlightPlan.initialDepartureFix` (FlightPlan.kt:226) or `nextUnpassedWaypoint` (FlightPlan.kt:188). `WeatherProviderDiagnostics.departureHeadingSummary` (line 84), the counterpart of iOS's `lastDepartureHeadingSummary`, is never assigned either.

**The whole simulated weather-deviation ATC conversation is ported but never constructed**  
✅ Closed: `WeatherDeviationController` runs the flow and the ATC screen's deviation slot is filled — the card's buttons dispatch through `WeatherDeviationEngine`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:6703 (askCenterAboutWeather), :6746 (requestWeatherDeviation), :6777 (requestVectorAroundWeather), :6605 (applyDeviationResult)`

- **iOS:** With a route-weather conflict active, the ATC tab shows a deviation card whose buttons run a full exchange with Center: ask about weather ahead, request a left/right deviation, request a vector around it, request higher/lower for weather, report clear of weather, continue on course. WeatherDeviationEngine drives the lifecycle and the deviation line drawn on the route map.
- **Android:** `WeatherDeviationEngine` is constructed only in tests. `FlightSessionState.availableWeatherDeviationActions` (core/session/FlightSessionState.kt:102) is declared with `emptySet()` and is never assigned anywhere in main, and never read by any screen. `AtcScreen.kt:139` declares a `weatherDeviationCard: @Composable () -> Unit = {}` slot which no caller ever supplies, so the card renders nothing. The `WeatherDeviationAction` enum (core/session/PilotAction.kt:42) has no dispatcher. A pilot on Android can never talk to ATC about weather.

### ATC, phraseology, en-route

**Approach request is never granted**  
✅ Closed: `requestApproach` posts the cleared-approach call with the FINAL read-back attached.  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:4577-4591`

- **iOS:** `requestApproach()` posts the pilot request and then Approach answers: `engine.clearedApproach(cs:procedure:runway:)` (or the string form), with `pilotEngine.readback(for: .final, context:)` attached as the read-back so the Read Back button echoes the approach clearance.
- **Android:** `performPilotAction` (FlightSessionCoordinator.kt:844) posts `pilotEngine.requestApproach(context)` only. `engine.clearedApproach` is reached solely from `ATCStateMachine` when telemetry advances the state to FINAL, never from the pilot's request.

**Check In gets no controller reply on any frequency** ✅ FIXED  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:4728-4770 (requestHandoff), with IFATCCompanion/App/AppModel.swift:2619-2626 (nextState(workedBy:after:))`

- **iOS:** After the pilot's check-in, iOS looks up `nextState(workedBy: facility, after: stateMachine.current)` — the next state in the gate-to-gate order worked by the frequency just tuned — and calls `advanceAndPost(to: target, announceHandoff: false)` so the controller answers with its instruction. If nothing is ahead for that controller it posts `engine.radarContact(cs:facility:)` instead, so a check-in is *always* answered.
- **Android:** `FlightSessionCoordinator.checkIn()` (Android/core/.../session/FlightSessionCoordinator.kt:659-706) posts only `pilotEngine.requestHandoff(...)`, clears `pendingCheckInFacility` and calls `recomputeDerivedState()`. There is no controller reply on any path except the two special cases handled first (go-around resume, and the Center-to-Center sector check-in at line 693). Checking in with Departure, Approach, Tower or Ground therefore produces the pilot's call and silence.

**Every takeoff and landing clearance reads "wind 000 at 0"**  
✅ Closed — same fix as the wind gap above.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4010-4012 and 4155-4160`

- **iOS:** `buildContext` reads the relevant METAR (departure or destination) and sets `windDirection = metar?.windDirection ?? 270`, `windSpeed = metar?.windSpeed ?? 8`. So Tower says "wind 270 at 8, runway 27, cleared for takeoff" — and `Phonetic.wind` speaks it.
- **Android:** `FlightSessionCoordinator.buildContext` hard-codes `windDirection = 0, windSpeed = 0` (FlightSessionCoordinator.kt:1506-1507). The coordinator is constructed with no weather/METAR provider at all (AppGraph.kt:321-341 passes scope, clock, diagnostics, connect, settingsProvider, speak, taxiContextProvider, savedFlightBinding). Every `clearedForTakeoff` and `clearedToLand` therefore reads "wind 000 at 0", spoken as zero-zero-zero at zero.

**Ground never hands the pilot to Tower to monitor before departure**  
✅ Closed: `maybeMonitorTowerHandoff` fires from `GroundHandoffSignals`, and a Tower check-in while monitoring answers with `numberOneForTakeoff`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:2275-2287 (maybeMonitorTowerHandoff), 2247-2266 (autoAdvanceMonitoringTower), 4702-4710 ("number one for departure")`

- **iOS:** As the departure taxi comes within `OSMSurface.monitorTowerLeadMeters` (600 m) of the runway, Ground posts `engine.monitorTower(cs:frequency:)` ("monitor Tower on 118.3") and latches `monitoringTower`. That then (a) makes Tower proactively issue "line up and wait" as the aircraft rolls up, (b) suppresses the redundant "contact Tower" on the takeoff clearance, (c) adds Check In to the Tower button set, and (d) makes a Tower check-in answer with `engine.numberOneForTakeoff(cs:runway:)` and nothing else.
- **Android:** `FlightSessionState.monitoringTower` (FlightSessionState.kt:67) is read in three places (FlightSessionCoordinator.kt:530, 541, and PilotActionAvailability.kt:145) and captured/restored in snapshots — but nothing in `:core` main or `:app` ever sets it to true. `engine.monitorTower` is never called outside a test, `numberOneForTakeoff` is never called at all, and the surface coordinator's `approachingRunwayHandoff` flag (surface/routing/AirportSurfaceCoordinator.kt:1411-1414) is computed and then consumed by nobody.

**Request Higher and Request Lower post only the pilot's half**  
✅ Closed — see `altitudeRequest`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4478-4502`

- **iOS:** `requestHigher()` posts the pilot request, then either denies it (`deny(c, reason: "unable higher, traffic and reported turbulence at that level")` when a ride-report item of severity >= .moderate covers the target altitude band) or sets `assignedAltitude` and posts `engine.climbMaintain(cs:altitude:)` with an `altitudeReadback("Climb", ...)` attached. `requestLower()` posts the pilot request then `engine.descendPilotsDiscretion(cs:altitude:)` with a matching read-back, and updates `assignedAltitude`. Both then clear the one-shot smoother-altitude hint.
- **Android:** `performPilotAction` (FlightSessionCoordinator.kt:836-842) builds `pilotEngine.requestHigher/requestLower`, posts it, and calls `onPilotRequest(action)` — which (line 876-886) maps only CLEARANCE/PUSHBACK/ENGINE_START/TAXI/READY/TAKEOFF and `else -> return`. No controller answer, no denial branch, and `assignedAltitude` is never updated by the request.

**Ride Report and Dest Wx get no answer; RideReportEngine is constructed nowhere**  
✅ Closed — see `WeatherAnswering`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4880-4900 (requestRideReport) and 4929-4941 (requestDestinationWeather)`

- **iOS:** `requestRideReport()` posts the pilot request, refreshes weather, recomputes ride items, computes a smoother altitude, and posts `rideEngine.rideReport(assessment:items:referenceAltitudeFt:smoother:callsign:)` with a courtesy `pilotEngine.roger(...)` attached as the read-back. `requestDestinationWeather()` does the same with `rideEngine.destinationWeather(metar:callsign:icao:)`.
- **Android:** `performPilotAction` (FlightSessionCoordinator.kt:845-846) posts `pilotEngine.requestRideReports` / `pilotEngine.requestWeather` and stops. `FlightViewModel.onContactAtcAboutWeather()` (app/.../ui/FlightViewModel.kt:535) routes to the same dead branch. Center never answers a ride-report or destination-weather request.

**Takeoff clearance never issues a departure heading, and the departure climb never names the first fix**  
✅ Closed — see `departureGuidance`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4056-4090 and 4168-4172`

- **iOS:** `buildContext` computes `departureHeading` from the bearing to `flightPlan.initialDepartureFix(sidFixes:origin:)`, converted true→magnetic, and `firstFixName` from `nextUnpassedWaypoint`/the first filed fix. With a heading known, `ATCStateMachine.towerDeparture` takes the rich branch — "wind 270 at 8, runway 27, cleared for takeoff, fly heading 085, climb and maintain 5,000" — and `departureClimb` says "resume own navigation, direct SSCOT".
- **Android:** `buildContext` never sets `departureHeading` or `firstFixName`, so both keep their ATCContext defaults (0 and ""). `ATCStateMachine.kt:115` therefore always takes the `else` branch (the plain "cleared for takeoff"), and `departureClimb` always says the bare "resume own navigation". The four-argument `clearedForTakeoff` overload, its runway-alignment test and its read-back are all correctly ported (PhraseologyEngine.kt:369-408) and unreachable.

**Taxi clearances lose their route whenever the OSM surface has not resolved**  
✅ Closed: `buildContext` falls back to `TaxiRoutePlanner` whenever no live route has resolved.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4026-4028 (taxiPlanner.plan) with IFATCCompanion/ATC/TaxiRoutePlanner.swift`

- **iOS:** `buildContext` always fills `taxiway`, `crossingRunway` and `parkingTaxiway` from `taxiPlanner.plan(airport:runway:arrival:)` — a deterministic fallback layout that works at any field, with or without OpenStreetMap data. A live OSM route later supersedes the clearance, but there is never a routeless clearance.
- **Android:** `buildContext` sources those three fields solely from `taxiContextProvider()` (FlightSessionCoordinator.kt:1488, 1509-1511) which returns null until the Overpass fetch resolves; the fields then fall back to `""`/null. `TaxiRoutePlanner.kt` — a faithful port of the same class, with `plan`, `defaultRoute`, `generatedLayout`, `replacingFallbackRoute` and `runwayNumber` — is referenced nowhere. With an empty `via`, `PhraseologyEngine.taxiToRunway` (line 281) emits "…, taxi to runway 27 via . Contact Tower when ready."

**The hold-for-check-in (semi-automatic) airborne flow has no Android counterpart**  
✅ Closed: `advanceSemiAutomatic` issues the hand-off alone and holds the new controller until the pilot checks in.  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:2350-2397 (advanceSemiAutomatic, issueAutoHandoff), dispatched at 2033`

- **iOS:** Once the pilot has tuned any frequency by hand (`manualTuning` latches true), a facility change posts *only* the hand-off — `issueAutoHandoff(from:to:)` — sets `pendingCheckInFacility` and does not advance the state machine. The new controller says nothing until the pilot tunes and checks in. It also holds the whole flow while `pendingCheckInFacility != nil`, and sets that flag explicitly on the FINAL (→ Tower) and runwayExit (→ Ground) steps.
- **Android:** `advanceAutomaticFlow` (FlightSessionCoordinator.kt:354-403) has one path only — the iOS non-manual one. It gates on standby, the read-back gate, `isManualGroundFlow` and `goAroundInProgress`, but never on `pendingCheckInFacility`, and always calls `advanceAndPost(target, automatic = true)`, which posts the hand-off *and* the new controller's instruction back to back (FlightSessionCoordinator.kt:590-624). `FlightSessionState.manualTuning` is set by `tuneTo` (line 916) and persisted in snapshots, but is read nowhere in `:core` main or `:app` — so at the TRACON ceiling the pilot hears "contact Center on 133.4" immediately followed by Center's clearance, before they have tuned or checked in.

**To Gate is a dead button and the whole arrival Ramp flow is missing**  
✅ Closed — see `arriveAtGate`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:2671-2681 (contactRamp) and 2687-2716 (arriveAtGate); IFATCCompanion/Phraseology/RampPhraseologyEngine.swift (arrivalInbound, proceedToGate, monitorRampToGate)`

- **iOS:** Tapping To Gate calls `contactRamp()` → `arriveAtGate()`, which tunes Ramp, posts `rampEngine.arrivalInbound(cs:gate:)` and `rampEngine.proceedToGate(cs:gate:via:)` ("proceed to gate B44 via the inner alley"), then stages `rampEngine.monitorRampToGate` as the aircraft slows (AppModel.swift:1968-1971) and the block-in once parked.
- **Android:** `performPilotAction` (FlightSessionCoordinator.kt:859-864) handles `PilotAction.TO_GATE` with a bare `return` and a comment claiming the arrival-ramp flow is "wired in separately" and that "the button is not offered". It *is* offered: `PilotActionAvailability.availableActions` returns `setOf(PilotAction.TO_GATE)` for `ATCFacility.RAMP` (PilotActionAvailability.kt:92), the Ramp tune button is live on arrival via `canContactRamp` (app/.../screens/AtcScreen.kt:410-421), and `ResponsesCard` renders every action in `session.availableActions` (AtcScreen.kt:456-495) wired to `onPilotAction` → `performPilotAction`. So the pilot taps To Gate and nothing happens at all.

**Vectors request produces no vector**  
✅ Closed: `requestVectors` computes a 30° intercept with `ApproachIntercept` and reads the heading back.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4535-4575`

- **iOS:** `requestVectors()` posts the pilot request, then computes a real 30° intercept to the final approach course with `ApproachIntercept.heading(finalCourse:aircraft:runwayReference:variationDegreesEast:)` and posts an Approach call — "fly heading 083, vectors for the ILS runway 27 approach" — with a read-back that echoes the heading (the safety-critical element).
- **Android:** `performPilotAction` (FlightSessionCoordinator.kt:843) posts `pilotEngine.requestVectors(context)` and nothing else. No heading is computed and no controller call follows.

### Audio, billing, review

**Ambient chatter is never ducked under a real ATC call**  
✅ Closed: `IFATCCompanionApplication` collects `speech.isSpeaking` into `chatter.setDucked`, and `AndroidChatterRadio` now speaks at `engine.chatterSpeechLevel` so the duck reaches the voice and not only the static bed.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:1365-1369 (speech.$isSpeaking.removeDuplicates().sink { self?.chatter.setDucked(speaking) }); the level it drives is IFATCCompanion/Chatter/RadioAudioEngine.swift:199-207`

- **iOS:** Whenever any real ATC/ATIS/pilot call is speaking, AppModel ducks the ambient chatter — the chatter voice mixer goes to 0 and the static bed drops to chatterLevel * 0.05, a faint hiss — and restores it when the call ends. docs/BackgroundChatter.md states this under "Interaction with the rest of the app → Ducking" as the reason real calls stay clear.
- **Android:** Nothing in :app ever calls AmbientChatterService.setDucked(...), and nothing consumes AndroidSpeechService.isSpeaking at all. The chatter therefore runs at full level straight through a controller clearance. With the radio effect off the two genuinely overlap (the ATC line goes out through TextToSpeech while the chatter bed and voice keep playing on the AudioTrack); with it on they do not overlap but are serialized — both AndroidSpeechService.play() and speakChatter() enqueue into the same RadioAudioEngine `pending` queue and one render loop drains it — so a real controller call is instead held behind whatever chatter line is on the air. Either way the iOS behaviour (chatter silenced, call immediately clear) does not happen. :core implements and tests setDucked; it is the classic ported-but-unreachable case.

### Found by the completeness pass

**Mock Mode's telemetry never reaches the flight session, and the feed is never started at launch — the app has no data source in either mode**  
✅ Closed: `MockSimulatorFeed.onState` is assigned in `AppGraph`, and `FlightSourceController.startAtLaunch` starts a feed at launch.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:1068 (`mock.onState = { [weak self] state in self?.handle(state: state) }`), :1086-1091 (pin to Mock Mode, then `startMock()`), :1431 `func startMock()``

- **iOS:** AppModel subscribes to the mock feed exactly as it subscribes to the live link (`mock.onState` at AppModel.swift:1068), and at launch it pins to Mock Mode when unentitled and calls `startMock()` (AppModel.swift:1086-1091). `startMock()` (AppModel.swift:1431-1456) disconnects Connect, resets the state machine, calls `stateMachine.setConnected()` and sets `atcState = .connectedIdle`, then the feed's 1 Hz synthesized `AircraftState` drives the whole gate-to-gate flow. Mock Mode is the free demo the entire app is sold on.
- **Android:** `MockSimulatorFeed.onState` — whose own KDoc says "the same closure shape `IFConnectManager.onState` has, so the session coordinator subscribes to the mock feed exactly as it does to the live link" (core/mock/MockSimulatorFeed.kt:76-81) — is never assigned anywhere in `:app` or `core/src/main`, and `mockFeed.state` is never collected. The coordinator's only telemetry subscription is `connect?.onState = ::ingestAircraftState` (core/session/FlightSessionCoordinator.kt:197), and `connect(...)` is never called (already-confirmed gap). `ingestAircraftState` has zero call sites outside `core/src/test`. Separately, nothing starts the feed at process start: `mockFeed.start()` is reached only from `AppGraph.setMockMode(true)` (AppGraph.kt:496-501), whose only caller is `FlightViewModel.onToggleMockMode` (FlightViewModel.kt:957-959), the Diagnostics screen switch — even though `AppSettings.mockMode` defaults to `true` (core/settings/AppSettings.kt:365). `setMockMode` also never resets the state machine or moves it to `CONNECTED_IDLE` (`ATCStateMachine.setConnected` has no caller in main; `resetForNewFlight` is called only from AppGraph.kt:170, the Clear Flight path), so `FlightSessionState.atcState` stays at its `NOT_CONNECTED` default (FlightSessionState.kt:37). Net effect: on a fresh launch the Android app is a static shell — no aircraft state, no phase, no ATC flow, no taxi, nothing — and even toggling Mock Mode in Diagnostics only spins a feed whose output goes nowhere. AppGraph.kt:247 asserts the opposite in a comment: "On [sessionScope]: its ticks are pushed straight into the coordinator's state."

**No entitlement enforcement at all — nothing observes the billing state, so Live Connected Mode is never locked, unlocked, or revoked mid-flight**  
✅ Closed: `AppGraph` observes the billing state into `FlightSourceController.applyEntitlement`, which locks to Mock Mode on loss and promotes on gain.  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:1114-1141 (`observeEntitlements()` / `applyEntitlement(hasLiveAccess:)`), :1086, :1618-1621 (`toggleMockMode` guard)`

- **iOS:** `observeEntitlements()` (AppModel.swift:1114-1118) subscribes to `entitlements.$hasLiveAccess` and routes every change through `applyEntitlement` (AppModel.swift:1129-1141): losing access logs "Live subscription not active — locking to Mock Mode", forces `settings.mockMode = true` and calls `startMock()`; gaining it switches to Live and calls `enterLiveMode()`. `toggleMockMode` (AppModel.swift:1618-1621) additionally refuses to leave Mock Mode without entitlement, and SettingsView.swift:88 disables the toggle outright. This is the only thing that makes the subscription mean anything — including a refund or a lapse that lands mid-flight.
- **Android:** Nothing in `:app` collects `graph.entitlements.state`; the only consumers of `PlayBillingRepository` are the paywall screen and `warmUp()`'s `entitlements.start()` (AppGraph.kt:526). There is no `applyEntitlement` equivalent, no launch-time pin to Mock Mode, and no guard on either mode switch: `FlightViewModel.onToggleMockMode` (FlightViewModel.kt:957-959) and `updateSettings` (FlightViewModel.kt:807-816) both write the flag unconditionally, and the Settings toggle is never disabled (SettingsScreen.kt:92-99). A subscription that lapses, is refunded, or is revoked mid-flight changes nothing the app does.

**Reading back a runway-crossing clearance is broken in both directions: the Read Back button never authorizes the crossing, and the taxi-map button authorizes it with no transmission**  
✅ Closed: `readBack()` takes the crossing branch first — it transmits the read-back and then authorizes — and the taxi map's button routes through the same method.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4388-4398 (`readBack()` handles `airportSurface.awaitingCrossingReadback` first: posts the pilot read-back, then calls `airportSurface.crossingReadbackReceived()`)`

- **iOS:** There is one Read Back button. When a simulated crossing clearance is pending, `readBack()` takes the crossing branch first (AppModel.swift:4391-4398): it posts `lastATCTransmission.readback` as a pilot transmission (so "Cross runway 6R-24L, United 598." appears in the transcript and is spoken) and *then* calls `airportSurface.crossingReadbackReceived()`, which is what authorizes the crossing. One tap, both effects.
- **Android:** Android splits the two and drops one half on each path. `FlightSessionCoordinator.readBack()` (core/session/FlightSessionCoordinator.kt:632-653) has no `awaitingCrossingReadback` branch at all, so pressing the ATC screen's Read Back button (or the notification's Read Back action) posts the read-back text but never calls `crossingReadbackReceived()` — the crossing stays unauthorized and `RunwayCrossingState` never advances past `AWAITING_PILOT_READBACK`. Conversely the dedicated "Read back crossing clearance" button on the taxi map (TaxiMapLayers.kt:107-111) is wired to `FlightViewModel.onCrossingReadback()` (FlightViewModel.kt:631), which is `graph.surfaceRouting.crossingReadbackReceived()` and nothing else; `AirportSurfaceCoordinator.crossingReadbackReceived()` (surface/routing/AirportSurfaceCoordinator.kt:1638-1644) only flips `authorizedCrossingIndex` and the state — it posts no pilot transmission. So the crossing is authorized silently, with nothing on the frequency and nothing in the transcript, even though `TaxiPhraseology.crossingClearance` (surface/routing/TaxiPhraseology.kt:155-165) carries the read-back text ready to be posted.

**The active phraseology profile is never applied to the engine — the whole custom-phraseology feature changes nothing a controller says**  
✅ Closed: the coordinator takes a `profileProvider`, and every path that selects or edits a profile already rebuilt the engine.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:741-748 (`engine.profile = profiles.activeProfile`), :785 (same, in the settings-applied rebuild), :1336-1342 (rebuild engines when the active profile or the profile list changes)`

- **iOS:** AppModel builds `PhraseologyEngine` and immediately assigns `engine.profile = profiles.activeProfile` (AppModel.swift:748), repeats it in the settings-applied rebuild (AppModel.swift:785), and re-runs both whenever the active profile or the stored profiles change (AppModel.swift:1336-1342). The profile supplies per-call templates (clearance, taxi-to-runway, takeoff, landing) and a custom airline call set, so a pilot's saved profile changes the wording of the calls they hear.
- **Android:** `FlightSessionCoordinator.buildEngine()` (core/session/FlightSessionCoordinator.kt:227-230) is `PhraseologyEngine(digitStyle = settings.digitStyle, mode = settings.phraseologyMode)` — the `profile` parameter (PhraseologyEngine.kt:29) is never supplied anywhere in `core/src/main` or `app/src/main`, and the coordinator is constructed with no reference to `PhraseologyProfileStore` at all (AppGraph.kt:320-341). The engine reads `profile` in six places — `airlineCallName` (PhraseologyEngine.kt:89, :99) and the CLEARANCE / TAXI_TO_RUNWAY / TAKEOFF / LANDING template lookups (:171, :262, :331, :599) — all of which see `null` forever. Meanwhile the whole Phraseology Profiles screen ships and works: create, edit, add example, import JSON, share JSON, and select active (`FlightViewModel.onSelectActiveProfile`, FlightViewModel.kt:894-897, which sets `activeProfileID` and then calls `coordinator.applyEngineConfig()` — which rebuilds an engine that ignores the profile).

**`FlightSessionState.hasLiveAccess` has no writer, so a paying subscriber sees the Subscribe banner and "Unlock Live Connected Mode" forever**  
✅ Closed: `FlightSessionCoordinator.setLiveAccess`, driven by the same billing observer.  
*Ported, not wired* · iOS: `IFATCCompanion/Views/ATCView.swift:27 (`if !entitlements.hasLiveAccess { subscribeBanner }`), IFATCCompanion/Views/SettingsView.swift:81 (`private var liveLocked: Bool { !entitlements.hasLiveAccess }`), :73-75 (subscription footer)`

- **iOS:** Every entitlement-gated piece of UI reads `entitlements.hasLiveAccess` live from `EntitlementManager` (EntitlementManager.swift:26, kept fresh by `Transaction.updates`). The ATC screen's subscribe banner disappears the moment the subscription is confirmed, and Settings flips to the "Live Connected Mode is active" footer and un-disables the Mock Mode toggle.
- **Android:** `FlightSessionState.hasLiveAccess` (core/session/FlightSessionState.kt:95) is declared `= false` and is assigned nowhere in `core/src/main` or `app/src/main` — the only other occurrences of the identifier are the billing repository's own `EntitlementState` and `LiveAccessRules`. It is permanently false. `AtcScreen.kt:147-149` renders `SubscribeBanner` on `!session.hasLiveAccess`, so the paywall banner sits at the top of the ATC tab for the whole life of the app even for a lifetime purchaser. `ScreenModels.kt:231` sets `hasLiveAccess = session.hasLiveAccess` while line 232 sets `entitlementStatusText = entitlements.statusText` from the *real* `EntitlementState` — so the Settings Subscription section shows "Live Connected Mode Active" directly above a link reading "Unlock Live Connected Mode" (SettingsScreen.kt:82-86), and the Mock Mode row keeps its `LIVE_LOCKED` subtitle "Live Connected Mode requires an active subscription." (SettingsScreen.kt:98).

### Airport surface, Connect, Mock Mode

**Auto-assign gates is a dead toggle — GateAssigner.assign is never called from :app or :core**  
✅ Closed — see `AutoGateController`.  
*Ported, not wired* · iOS: `IFATCCompanion/AirportSurface/GateAssignment.swift:460/474 (GateAssigner.assign); IFATCCompanion/App/AppModel.swift:3094 (autoAssignGatesIfNeeded), :3261-3290 (autoAssignGate), :3243 (applyAutoAssignedGate), :3129-3149 (dropForeignAutoGates), :3163-3189 (applyAutoGateSettingChange / clearAutoAssignedGates); IFATCCompanion/Views/SettingsView.swift:411`

- **iOS:** With the "Auto-assign gates" toggle on, iOS fills a blank departure/arrival gate from the field's OSM stand data: it drops any automatic gate stamped for a different airport, fetches the surface via airportSurface.surfaceModel(icao:reference:) (AppModel.swift:3275), runs GateAssigner.assign against a FlightContext (callsign, airline, aircraft type/size class, and the aircraft's parked position when it is stopped on the ground), writes the chosen stand into settings.departureGate/arrivalGate with an AutoGateStamp so a later pass may replace it and a pilot edit takes it back, logs the rationale, upgrades a chosen departure gate to the stand the aircraft is actually parked on, gives the fields back when the toggle is switched off, and re-tries via onSurfaceAvailable with a read-failure cap.
- **Android:** The picker itself is fully ported and tested (GateAssignment.kt: StandProfile.from, StandOperators, GateAssigner.assign/mayAssign/isAppAssigned/couldUpgrade/mayUpgrade, AutoGateStamp.decode) and the persistence exists (SettingsKeys.AUTO_ASSIGN_GATES / AUTO_ASSIGNED_DEPARTURE_GATE / AUTO_ASSIGNED_ARRIVAL_GATE, SettingsRepository.setAutoAssignedDepartureGate/ArrivalGate) — but no production code ever calls any of it. GateAssigner.assign has zero call sites outside its own file; setAutoAssignedDepartureGate/ArrivalGate have zero callers; AirportSurfaceCoordinator.surfaceModel(...) — the read-only surface accessor iOS uses for exactly this — has zero callers. Toggling "Auto-assign gates" in SettingsScreen.kt:429 writes a boolean nobody reads, so a blank gate stays blank and the taxi route has no stand to route to or from.

**Mock Mode never installs the scripted flight plan, so the demo runs with an empty plan**  
✅ Closed: `FlightPlanComposer` composes the plan from the pilot's fields and the demo route, with the pilot's entries always winning.  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:4247-4280 (syncFlightPlanFromSettings), especially :4258-4261 (United/598), :4262-4263 (KIAH/KMSP), :4265-4266 (cruise 37000), :4274-4277 (gates C24/C6), :4278 (mock.route.waypoints); called from toggleMockMode at :1621-1625`

- **iOS:** When Mock Mode is on, syncFlightPlanFromSettings fills every blank plan field from MockSimulatorFeed.defaultRoute(): airline "United" / flight number "598", departure KIAH, destination KMSP, cruise 37000 ft, departure gate C24, arrival gate C6, and the five synthetic fixes TBONE, KMCI, KOMA, KDSM, FARGO. Anything the pilot typed wins; toggleMockMode(true) re-runs it so the demo always has a plan.
- **Android:** Nothing in Android reads graph.mockFeed.route into the flight plan. AppGraph.setMockMode(true) (AppGraph.kt:496) only calls connect.disconnect() and mockFeed.start(). The only consumer of mockFeed.route in the whole app is FlightViewModel.mockRouteText() at :977, a Diagnostics label string. AppSettings defaults departure/destination/callsign/gates to "" and cruiseAltitude to 0, and FlightPlan is only ever populated by IFConnectManager (which is never connected — see the gap above) or by the pilot typing into the Flight-screen override fields. So the demo starts with a blank plan: no route line on the weather map, no surface load (the surface refresh in FlightViewModel.kt:141-149 returns early when both endpoints are blank), no taxi map (observeTaxi guards on `plan.departure.length >= 3`), no United 598 identity, no gates for the gate-routed taxi, and no waypoints for the weather-deviation demo the mock radar cells exist to drive.

**The Infinite Flight Connect link is never opened — connect() and startAutoDiscover() have no caller in :app**  
✅ Closed: `FlightSourceController.connectToInfiniteFlight` decides between the stored endpoint and auto-discovery, and persists what discovery finds.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:1544 (connectToInfiniteFlight), :1550 (connect.startAutoDiscover), :1556 (connect.connect(host:port:rediscoverOnFailure:onRediscovered:)), :1652 (reconnect); IFATCCompanion/Views/SettingsView.swift:118 (Connect/Reconnect button)`

- **iOS:** On entering Live mode iOS calls connectToInfiniteFlight(): with no stored host and auto-discover on it runs connect.startAutoDiscover and connects to whatever the search finds, persisting the address; with a stored host and auto-discover on it calls connect(host:port:rediscoverOnFailure:true) so a stale address is re-searched and overwritten when the network changed; with auto-discover off it dials the entered address exactly. Settings also has an explicit Connect/Reconnect button (model.reconnect()).
- **Android:** IFConnectManager.connect(...) and startAutoDiscover(...) are never invoked anywhere in Android/app or the rest of :core. AppGraph constructs the manager (AppGraph.kt:234), wires connect.onState/onFlightPlan through FlightSessionCoordinator, observes connect.state, and calls connect.disconnect() on teardown (AppGraph.kt:411, :498) — but nothing ever brings the link up. AppGraph.setMockMode(false) (AppGraph.kt:496-505) only stops the mock feed and ends the session; its own comment says it "lets discovery start", and nothing does. Nothing reads settings.host/settings.port other than the Settings text field, the `autoDiscover` toggle (SettingsScreen.kt:117) feeds nothing, and there is no Connect/Reconnect control. Net effect: turning Mock Mode off leaves the app with no data source at all — the entire ported Connect stack (client, discovery, manifest handshake, state reader, flight-plan parser, reconnect/rediscovery watchdog) is inert.

**The arrival taxi never begins — beginArrival and updateTaxiStart have no caller**  
✅ Closed: `observeTaxi` handles `GROUND_ARRIVAL` and `RUNWAY_EXIT`, calling `beginArrival` and re-anchoring with `updateTaxiStart`.  
*Ported, not wired* · iOS: `IFATCCompanion/AirportSurface/AirportSurfaceCoordinator.swift:426 (beginArrival), :450 (updateTaxiStart); IFATCCompanion/App/AppModel.swift:894-908 (prepareArrivalTaxi), :976-996 (issueArrivalTaxiClearance re-anchoring the start), :938-960 (shouldWaitForArrivalRoute)`

- **iOS:** After landing, iOS starts the destination surface load at the runway exit and calls airportSurface.beginArrival(icao:reference:aircraftName:gate:startCoordinate:mock:arrivalRunway:), re-anchors the route to where the aircraft actually is with updateTaxiStart(coordinate:) when taxi-in is requested, withholds Ground's taxi-in clearance while the surface loads (up to a 40 s backstop) and then issues a gate-routed clearance naming the taxiways and the parking taxiway, drawing the arrival taxi map with its runway crossings.
- **Android:** AirportSurfaceCoordinator.beginArrival(...) and updateTaxiStart(...) are never called in :app or :core. FlightViewModel.observeTaxi() (FlightViewModel.kt:558-608) is the only driver of the coordinator, and it fires only on ATCState.GROUND_TAXI / PUSHBACK_TAXI, always with plan.departure, plan.departureRunway and plan.departureGate, always calling beginDeparture. ATCState.GROUND_ARRIVAL and ATCState.RUNWAY_EXIT are never handled anywhere in :app. Consequently route.isDeparture is always true, so AppGraph.taxiClearanceContext() (AppGraph.kt:389-397) always yields an empty parkingTaxiway and the arrival taxi-in clearance can never be gate-routed; no arrival taxi map is ever drawn (TaxiMapLayers.kt:195-199 and ScreenModels.kt:489 have the arrival rendering, it just never receives an arrival route); and the arrival runway crossings are never issued. AirportSurfaceCoordinator.resumeTaxiAfterRelaunch() (the mid-taxi relaunch restore, iOS AppModel.swift:3846) likewise has zero callers.

### Screens and settings

**"Weather deviation alerts" picker renders but is read by nothing**  
✅ Closed: the flow reads it for both the banner and the auto-issued advisory.  
*Ported, not wired* · iOS: `IFATCCompanion/Views/SettingsView.swift:321-323; AppModel.swift:6376 (`guard settings.weatherDeviationAlerts.alertsEnabled`), and `suggestsDeviation` gating the deviation half of the flow`

- **iOS:** Off / Advisory only / Advisory + suggested deviation. `alertsEnabled` gates whether the weather banner appears at all; `suggestsDeviation` decides whether a suggested deviation accompanies the advisory.
- **Android:** `SettingsScreen.kt:352-358` renders the picker and persists the choice, but `weatherDeviationAlerts` has no reader anywhere in `app/src/main` or `core/src/main` outside `AppSettings.kt`, `SettingsRepository.kt`, `SettingsKeys.kt` and `SettingsScreen.kt`. Setting it to "Off" does not suppress the Android weather banner, which is gated only on `weather.routeSigmets.isEmpty()` (ScreenModels.kt:143-147).

**Route map never draws the mint deviation line, the faint preview reroutes, or the rejoin marker**  
✅ Closed: `deviationLine` and `deviationPreviews` are assigned from the flow's published state.  
*Ported, not wired* · iOS: `IFATCCompanion/Views/RouteMapView.swift:158-179 (`model.weatherDeviationPreviews` polylines, `model.weatherDeviationLine` polyline, `model.weatherRejoinMarker`); AppModel.swift:6410-6440 (`weatherDeviationLine`)`

- **iOS:** Draws dashed mint preview reroutes for weather systems further along the route, the solid/committed mint deviation line for the system being worked, and a mint "rejoin" marker at the fix the deviation rejoins the route at.
- **Android:** `RouteMapModel` declares `deviationLine` (RouteMapLayers.kt:68) and `deviationPreviews` (:70), and `RouteMap` genuinely draws both (RouteMapLayers.kt:200-209) — but `FlightViewModel.routeMapModel()` (ScreenModels.kt:408-443) never sets either field, so both are always empty. There is no counterpart to `weatherRejoinMarker` at all. Meanwhile WeatherScreen.kt:186-189 tells the pilot verbatim that "The mint paths are the simulated recommended reroutes around the precipitation on your route" — describing something that can never appear.

**Settings → Infinite Flight Connection has no Connect/Reconnect button, and Host / Port / Auto-discover are read by nothing**  
✅ Closed: the Connect/Reconnect row and the connection caption ship, and the three fields are locked without a subscription.  
*Absent* · iOS: `IFATCCompanion/Views/SettingsView.swift:96-124 (Host/IP, Port, "Auto-discover on local network", the Connect/Reconnect button at :118-121 and the `connect.connectionState.detailedTitle` line at :122); IFATCCompanion/App/AppModel.swift:1544-1566 (`connectToInfiniteFlight`, which branches on `settings.autoDiscover` and uses `settings.host`/`settings.port`)`

- **iOS:** The Connection section shows Host/IP and Port fields, an Auto-discover toggle, a Connect/Reconnect button (shown whenever Mock Mode is off and the user has Live access) and a live connection-state line. `connectToInfiniteFlight()` reads all three settings: blank host + auto-discover → `connect.startAutoDiscover`; host + auto-discover → connect with rediscovery on failure; host without auto-discover → connect to exactly what was typed.
- **Android:** `SettingsScreen.kt:90-127` renders the Host, Port and Auto-discover controls but there is no Connect/Reconnect button and no connection-state line. More seriously the controls are inert: `settings.host` has exactly one reader in the whole app (`SettingsScreen.kt:102`), `settings.autoDiscover` has none outside `SettingsKeys.kt`, and no code in `:app` ever calls `IFConnectManager.connect(...)` (IFConnectManager.kt:148) or `startAutoDiscover(...)` (:705) — the only call `:app` makes on the manager is `connect.disconnect()`.

**Taxi map is drawn without its card: no title, no header chips, no off-route banner, no Expand/Recalculate/Read Back, and no OpenStreetMap attribution**  
✅ Closed: `TaxiMapCard` adds the title, the destination and confidence chips, the taxiway sequence, the crossing count, the off-route banner, the Expand / Recalculate / Read Back row, the OpenStreetMap attribution and the simulation-only line — and `ExpandedTaxiMap` is the full-screen view, with the attribution repeated.  
*Absent* · iOS: `IFATCCompanion/Views/TaxiMapView.swift:33 (Card titled "Taxi Map (Simulated)"), :35 + :103-134 (TaxiMapHeader — destination pill, confidence pill, "Via …", crossing count), :36 + :54-66 (off-route banner), :79-99 (Expand / Recalculate / Read Back), :43 + :174-190 (TaxiMapFooter — tappable OSM attribution + simulation-only line), :193-255 (ExpandedTaxiMap)`

- **iOS:** The taxi map lives inside a titled Card with a header row (assigned gate/runway, route confidence, taxiway sequence, crossing count), an orange "Off assigned taxi route" banner when `surface.offRoute`, a controls row (Expand → full-screen map, Recalculate, Read Back), and a footer carrying the tappable OpenStreetMap attribution link plus "Simulation only — not for real-world aviation. OSM data may not match Infinite Flight scenery." The attribution is also repeated inside the expanded map.
- **Android:** `AppNavHost.kt:117-129` drops `TaxiMap(...)` straight into `item { taxiMap() }` (AtcScreen.kt:172) with no `Card` wrapper. `TaxiMapLayers.kt:96-121` renders only the canvas, the next-instruction text, a read-back-crossing button and the crossing/off-route action buttons. There is no title, no header chips (`TaxiMapModel.destinationLabel` at TaxiMapLayers.kt:56 is populated by ScreenModels.kt:492 and then never read by any composable), no off-route banner, no Expand control and no expanded map (`TaxiMap(expanded = true)` is never called), and no attribution — despite the file's own KDoc at TaxiMapLayers.kt:72-73 saying "the attribution shown beneath the map is the licence condition, not decoration", and Docs/ANDROID_PARITY_MATRIX.md:225 claiming "OSM attribution on every surface map, tappable, ODbL 1.0 — ✅ tested". `AirportSurfaceState.taxiMapVisible`, `.mapExpanded` and `.offRoute` are never read by any UI.

**The whole simulated weather-deviation card is absent from the Android ATC screen — the slot exists and is never filled**  
✅ Closed: `WeatherDeviationCard` fills the slot, keyed off `availableWeatherDeviationActions`.  
*Ported, not wired* · iOS: `IFATCCompanion/Views/ATCView.swift:34 (`if model.weatherDeviationCardVisible { weatherDeviationCard }`), :315-360 (the card and its nine buttons); IFATCCompanion/App/AppModel.swift:6489 (`weatherActions`), :6703 (`askCenterAboutWeather`), :6746 (`requestWeatherDeviation`)`

- **iOS:** When a route-weather conflict or ride SIGMET is being worked, ATCView shows a "Weather Deviation (Simulated)" card with a status line ("Moderate precipitation, 42 NM ahead. Say intentions.") and up to nine buttons — Contact ATC, Right Dev, Left Dev, Vectors, Higher Wx, Lower Wx, Clear of Wx, Continue, Say Again — each driving `AppModel`'s deviation methods through `WeatherDeviationEngine`.
- **Android:** `AtcScreen` declares the slot `weatherDeviationCard: @Composable () -> Unit = {}` (AtcScreen.kt:139) and renders it at AtcScreen.kt:174, but `AppNavHost.kt:113-130` constructs `AtcScreen(model=…, actions=…, modifier=…, taxiMap=…)` and never passes `weatherDeviationCard`, so it defaults to the empty lambda. Underneath, `WeatherDeviationEngine` / `WeatherDeviationPhraseology` / `WeatherDeviationContext` are referenced only by each other and by two test files, and `FlightSessionState.availableWeatherDeviationActions` (FlightSessionState.kt:102) is never written — `FlightSessionCoordinator.kt` contains the string "deviation" zero times. A pilot on Android can never request a deviation, a vector around weather, or report clear of weather.

### Weather and ATIS

**A pilot's "Destination Weather" request gets no controller answer — RideReportEngine.destinationWeather is never called**  
✅ Closed — see `WeatherAnswering`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4929 requestDestinationWeather → :4936 rideEngine.destinationWeather(metar:callsign:icao:); engine at IFATCCompanion/Weather/RideReportEngine.swift:124`

- **iOS:** Posts the pilot's request, refreshes weather, then posts a Center read-out of the destination METAR — "<city> is reporting wind 270 at 12, visibility 10, ceiling 3000 broken, altimeter 29.92" (QNH in hPa in ICAO mode) — or "<city> weather is not available at this time." when there is no METAR, with a courtesy "Roger" attached as the read-back.
- **Android:** FlightSessionCoordinator.performPilotAction maps PilotAction.DEST_WX to pilotEngine.requestWeather(context, context.plan.destination) — the pilot's half only — and onPilotRequest has no DEST_WX case, so nothing answers. The Kotlin port of the read-out exists (core/weather/deviation/RideReportEngine.kt:221-222, "$city is reporting ...") but is unreachable from the app.

**A pilot's "Ride Report" request gets no controller answer — RideReportEngine.rideReport is never called**  
✅ Closed — see `WeatherAnswering`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4880 requestRideReport → :4892 rideEngine.rideReport(assessment:items:referenceAltitudeFt:smoother:callsign:); engine at IFATCCompanion/Weather/RideReportEngine.swift:59`

- **iOS:** Posts the pilot's request, refreshes weather, recomputes the ride items, then posts a Center transmission relaying the lead PIREP — its severity, reported altitude, distance ahead or "along your route", nearest fix, reporting aircraft type, age in minutes, the contributing factors — and attaches a courtesy "Roger" as the read-back so the Read Back button acknowledges the report. With nothing along the route it says "overall ride is smooth along your route at this time."
- **Android:** FlightSessionCoordinator.performPilotAction handles PilotAction.RIDE_REPORT by posting only the pilot half (pilotEngine.requestRideReports(context)) and onPilotRequest returns for it, so no ATC reply is generated. RideReportEngine.kt exists in :core with the phraseology strings verbatim ("no significant ride reports along your route at this time." at lines 40-41, "smooth ride reported along your route." at 56-57) but is constructed nowhere. The pilot transmits and the frequency stays silent. The same button is reached from the Weather tab (FlightViewModel.kt:535 onContactAtcAboutWeather → PilotAction.RIDE_REPORT).

**Live radar raster is never sampled into precipitation cells — RadarImageSampler has no call site in :app, so sampledCells is always empty**  
✅ Closed: `PrecipitationSampler` fetches the corridor image, decodes it through a `BitmapFactory`-backed `RasterImageDecoder`, and hands the cells to `WeatherSessionController.noteSampledCells`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:6047 sampleLivePrecipitation (:6099 RadarImageSampler.mercatorSampleSize, :6111 RadarImageSampler.cells), :5955 maybeResamplePrecipitation; IFATCCompanion/Weather/RadarImageSampler.swift`

- **iOS:** Fetches a NOAA/OPERA base-reflectivity image for the whole flight-plan corridor (aircraft plus every fix ahead, widened ~60 NM), sized to the corridor bbox's exact Web-Mercator aspect ratio at ~2 NM/pixel, classifies pixels by the reflectivity colour ramp (IMERG rate palette for an opted-in satellite estimate), clusters moderate-and-warmer returns into cells, resamples on a ~60 s staleness check while airborne and in the foreground, and keeps the last good cells on a fetch/decode failure. Those cells are what the whole deviation flow runs on, what the Diagnostics "sampled cells" count reports, and what the opt-in "Show sampled cells on map" toggle draws.
- **Android:** RadarImageSampler.kt exists in :core with 25 unit tests but is called from nowhere in :app. The only precipitation image path in the app is app/map/RadarRasterLoader.kt, which calls PrecipitationOverlayService.overlayImage for the map's *visible* region and decodes it straight to a bitmap for drawing — it never hands the bytes to the sampler and never produces a RadarCell. WeatherSessionState.radarOverlay.sampledCells is written in exactly one place (WeatherSessionController.kt:188) and only to emptyList(). So the Diagnostics "Sampled cells" row (app/ui/ScreenModels.kt:367) always reads 0, the RouteMapLayers sampled-cell layer (RouteMapLayers.kt:191) never draws anything even with the diagnostics toggle on, and there is no cell data for any deviation to be computed from.

**The entire simulated weather-deviation flow is in :core and constructed nowhere in :app — no hazards, no conflict detection, no mint line, no deviation ATC calls**  
✅ Closed: hazards are built from the radar cells, the whole-route walk locks a reroute per system, and the mint line and its faint previews reach `RouteMapModel`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:754 (deviationEngine = WeatherDeviationEngine(phraseology: WeatherDeviationPhraseology(engine: engine))), :5229 recomputeWeatherHazards, :5455 recomputeLockedDeviations, :5504 computeDeviations, :6746 requestWeatherDeviation, :6663/:6713 deviationEngine.issueAdvisory, :7304 beginDeviationTurn, :7965 rejoinTurn, :8044 reportClearOfWeather; IFATCCompanion/Weather/WeatherDeviationEngine.swift, WeatherDeviationPhraseology.swift, RouteWeatherConflictDetector.swift, WeatherHazard.swift`

- **iOS:** On every telemetry tick iOS builds weather hazards from radar cells, runs RouteWeatherConflictDetector over the upcoming route corridor, draws the mint deviation line plus faint previews on the route map, raises the "contact ATC" banner inside the ~60 NM tactical range, issues the ATC weather advisory, puts up the response card (Vectors / left deviation / right deviation / higher / lower / continue), assigns a heading and a downstream rejoin fix, monitors clear-of-weather and clears the aircraft back to the filed route. It also raises the altitude-change-only advisory (higher/lower/continue, never deviate) when a turbulence or icing SIGMET lies along the route with no precipitation conflict.
- **Android:** Nothing in :app ever constructs RouteWeatherConflictDetector, WeatherDeviationEngine, WeatherDeviationPhraseology or calls WeatherHazard.buildWeatherHazards, so no conflict is ever detected and no deviation call is ever made. The consumers are all present but permanently inert: RouteMapModel.deviationLine and .deviationPreviews (app/ui/map/RouteMapLayers.kt:68-70) have no assignment anywhere in :app, so the mint line and previews never draw; AtcScreen.kt:139 declares weatherDeviationCard: @Composable () -> Unit = {} and AppNavHost.kt:113 constructs AtcScreen without passing it, so the response card is always the empty default; FlightSessionState.availableWeatherDeviationActions (core/session/FlightSessionState.kt:102) is never populated and never read; the ATC-tab weather banner (app/ui/ScreenModels.kt:143 weatherBannerText) keys off routeSigmets only, never off a precipitation conflict. The Settings rows that exist for this flow — Weather deviation alerts (SettingsScreen.kt:372) and "Deviations from satellite estimate" (SettingsScreen.kt:379) — therefore control nothing.

---

## Medium (46)

### Session orchestration and models

**ATIS receipt state does not survive a reconnect — six snapshot fields are never written or read**  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:3563-3568 (currentSnapshot writes reportedDepartureInfo/reportedArrivalInfo/departureInfoAppended/arrivalInfoAppended/departureATISDismissed/arrivalATISDismissed), :3692-3699 (apply restores all six), plus :3723-3726 for departureATIS/arrivalATIS/lastArrivalATISAttempt`

- **iOS:** After a reconnect the pilot still "has" information X: the taxi request and the Approach check-in keep appending it, it is not re-reported if it already was, the ATIS tune button stays dismissed, and the ATIS card is populated from the snapshot rather than blank until the next fetch.
- **Android:** Android's `ATISSession` holds exactly these fields (core/atis/ATISSession.kt:62,63,66,67,75,76,79) but nothing bridges them to the snapshot: `captureSnapshot` sets none of the six (nor `departureATIS`/`arrivalATIS`/`lastArrivalATISAttemptMillis`), and `restore` reads none of them. After a relaunch mid-taxi the pilot silently loses the information code they had copied and the ATIS button reappears.

**An in-progress weather diversion does not survive a reconnect**  
✅ Closed: the deviation context is captured into and restored from the session snapshot.  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:3561 (`weatherDeviation: weatherDeviation`), :3690 (`if let deviation = snap.weatherDeviation { weatherDeviation = deviation }`)`

- **iOS:** The deviation lifecycle — including a committed diversion and its "clear of weather" button — is snapshotted and restored, so backgrounding the app mid-diversion does not drop it.
- **Android:** `SessionSnapshot.weatherDeviation` is declared (SessionSnapshot.kt:88) and never written by captureSnapshot nor read by restore. `WeatherDeviationContext` appears only inside core/weather/deviation/, never in the session or persistence path.

**Background chatter never learns the field's runways, and does not react to a frequency change**  
🟡 Half-closed: the runway pools are bound through `ChatterRunwayResolver`, so the chatter names runways the field actually has. Ending the exchange on the old frequency mid-call is still open — `AmbientChatterService.facilityDidChange` has no caller.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:1156-1170 (configureChatter binds both facility *and* runways, and pipes $currentFacility into chatter.facilityDidChange), :1178-1194 (chatterRunwayContext)`

- **iOS:** The chatter references the real active runways for whichever field is in play — parsed out of that field's ATIS by ATISRunwayParser and reconciled against the OSM runway inventory — and when the pilot changes frequency, chatter on the old frequency is ended mid-exchange and chatter for the new one begins.
- **Android:** `IFATCCompanionApplication.kt:55` calls `chatter.bindContext(facility = { ... })` and omits the `runways` argument, so `AmbientChatterService` keeps its default `{ ChatterRunwayContext() }` (AmbientChatterService.kt:137,172) and invents random runways forever. `AmbientChatterService.facilityDidChange` (line 236) has no caller in main, so the chatter does not follow the radio. `ATISRunwayParser` (core/atis/ATISRunwayParser.kt:18) is never called.

**Center is always worked on a fixed frequency, and Ground/Departure differ from the iOS numbers**  
✅ Closed: `buildContext` passes `appliedCenterSector?.frequency`, so the Center tune button and every Center hand-off name the sector under the aircraft. Ground is 121.8, Departure 124.3 and the Center fallback 132.45, matching iOS.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4160-4166 (departureFrequency: 124.300, centerFrequency: currentCenterFrequency, approachFrequency: 119.700, towerFrequency: 118.300, groundFrequency: 121.800), :4186-4188 (`currentCenterFrequency` = centerSector?.frequency ?? 132.450)`

- **iOS:** Once the sector map resolves, Center's frequency in the context — the number the tune button shows and every "contact Center on …" speaks — is the *current sector's*, falling back to 132.450. Ground is 121.800 and Departure 124.300.
- **Android:** buildContext passes constants: `DEFAULT_CENTER_FREQUENCY = 133.4`, `DEFAULT_GROUND_FREQUENCY = 121.9`, `DEFAULT_DEPARTURE_FREQUENCY = 124.35` (FlightSessionCoordinator.kt:1618-1624), and `frequencyFor(CENTER, c)` returns `c.centerFrequency`, so the sector's own frequency never reaches the context. Only `announceCenterSectorHandoff` (line 1419) uses `crossing.to.frequency`; the Center tune button and the Departure→Center hand-off both read 133.4 regardless of sector.

**No telemetry-discontinuity detection and no forced reconnect on returning to the foreground**  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:1699 (handleReturnToForeground), :1717 (detectTelemetryDiscontinuity), :1692 (markBackgrounded)`

- **iOS:** Returning from the background tears the Connect link down and restarts it, and marks the next fix as a resync; separately, any fix that moved farther than groundspeed × elapsed × 3 + 1 NM is treated as a frozen socket snapping forward. The resulting `telemetryJumped` flag makes position-driven weather decisions stand down for that tick instead of replaying turns flown during the gap.
- **Android:** MainActivity's `onStart`/`onResume` do only notification-rationale and billing-Activity work (MainActivity.kt:211-234); nothing calls `connect.disconnect()`+reconnect on foreground return, and no `markBackgrounded` equivalent exists. There is no jump test anywhere: `grep` for discontinuity/telemetryJumped finds only an unrelated comment in CenterSectorTracker. IFConnectManager.kt:507 even carries a comment about "the forced reconnect — the one the app performs on returning from the background", which no code performs.

**Ramp pushback never names a push direction or a spot**  
✅ Closed: `buildContext` fills `pushDirection` from the ramp profile's first default push direction and `rampSpot` from its first spot name when the field uses spots.  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:4146-4147 (`pushDirection = rampProfile.defaultPushDirections.first ?? ""`, `rampSpot = rampProfile.usesSpots ? (rampProfile.defaultSpotNames.first ?? "") : ""`)`

- **iOS:** At a field whose ramp profile supplies them, the push clearance says "push tail west approved" and the Ramp→Ground hand-off names the spot; without a profile it falls back to the generic "push approved, advise ready to taxi".
- **Android:** buildContext sets `rampProfile` but not `pushDirection` or `rampSpot`, so both take their ATCContext defaults of `""` and every airport gets the generic push. `ATCStateMachine.kt:97` and `PilotResponseEngine.kt:45` read them and always see empty.

**Request Approach and Request Vectors are unanswered; the vector heading engine is unwired**  
✅ Closed — see `requestVectors` and `requestApproach`.  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:4535 (requestVectors), :4564 (approachInterceptHeading), :4577 (requestApproach)`

- **iOS:** Vectors posts the request and Approach answers with a real 30° intercept to the final approach course — computed by ApproachIntercept from the runway's magnetic course, the aircraft's side of the centerline and the local variation — with the heading as the read-back. Request Approach posts the request and Approach issues the cleared-approach with a matching read-back.
- **Android:** `performPilotAction` posts `pilotEngine.requestVectors` / `.requestApproach` and `onPilotRequest` has no branch for either, so nothing answers until the automatic flow happens to reach FINAL on its own. `ApproachIntercept` (core/atc/ApproachIntercept.kt:16) is called from nowhere in main — the only mention outside its own file is a CONTRACT comment in HeadingSolver.kt:401 — so no vector heading is ever produced.

**The Accept-smoother-altitude button never appears, and would do nothing if it did**  
✅ Closed: the ride report stores the suggestion, `smootherAltitudeLabel` publishes it, and `acceptSmootherAltitude` flies it.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4508 (acceptSmootherAltitude), :302 (smootherAltitudeActionTitle), :236 (`if suggestedSmootherAltitude != nil { actions.insert(.acceptSmootherAltitude) }`)`

- **iOS:** After a ride report suggests a smoother level, a labelled "Climb FL390" button appears on Center; tapping it posts the request, has the controller clear the aircraft there with a read-back, sets assignedAltitude and clears the suggestion.
- **Android:** Availability is gated on `current.smootherAltitudeLabel != null` (FlightSessionCoordinator.kt:1191), and `FlightSessionState.smootherAltitudeLabel` (FlightSessionState.kt:106) is never assigned anywhere — so `ACCEPT_SMOOTHER_ALTITUDE` is never in `availableActions` and AtcScreen's label branch at line 477 is dead. Even if it were offered, `performPilotAction` returns without acting (FlightSessionCoordinator.kt:859-863). The suggestion itself does exist on the weather side (`WeatherSessionState.suggestedSmootherAltitude`), so the two halves are simply not joined.

**The Live Flight Update never shows the weather advisory**  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:1317 (`weatherAlert: activeWeatherConflict != nil ? "Weather ahead on route" : nil`)`

- **iOS:** When a route-weather conflict is active, the Live Activity carries a "Weather ahead on route" line, so a pilot with the phone locked sees it.
- **Android:** `LiveFlightUpdateProjection.from` (core/liveupdate/LiveFlightUpdateProjection.kt:56) sets `weatherAlert = null` unconditionally — the field is modelled and rendered but never populated. The projection is fed only `FlightSessionState`, which carries no weather conflict, and `FlightSessionActiveFlightController.kt:49` is the only caller.

**The approach fallback altitude is never elevation-aware**  
✅ Closed: `approachDefaultAltitude` is derived from the live field elevation.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4138 (`let approachDefault = Self.roundedUpToThousand((liveFieldElevationMSL() ?? 0) + 3000)`), :3485 (liveFieldElevationMSL)`

- **iOS:** When the flight plan supplies no intercept altitude, Approach assigns 3,000 ft above the *destination field*, estimated live from MSL − AGL near the field and rounded up — 9,000 ft at Denver, not 3,000.
- **Android:** buildContext never sets `approachDefaultAltitude`, so it stays at `DEFAULT_APPROACH_ALTITUDE = 3_000` (ATCContext.kt). `ATCStateMachine.kt:172`, `PilotResponseEngine.kt:127` and `FlightSessionCoordinator.kt:744` (the go-around pattern altitude) and :1267 all read that sea-level constant. At a high-elevation destination Approach assigns and the go-around climbs to an altitude below the ground.

**The squawk code is a fixed 4271 instead of one derived from the flight number**  
✅ Closed: `deterministicSquawk(plan)` derives `(abs(n) * 7 + 1) % 4096` formatted as octal, stepping over the codes that mean something else (7500/7600/7700/7777/1200).  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4191-4194 (`deterministicSquawk()` = `String(format: "%04o", (abs(n) * 7 + 1) % 4096)` over the digits of the flight number)`

- **iOS:** Every flight gets its own beacon code, spoken in the IFR clearance and read back — and, being octal, it is always a legal squawk. UAL598 and UAL2210 get different codes.
- **Android:** buildContext passes `squawk = DEFAULT_SQUAWK` where `const val DEFAULT_SQUAWK = "4271"` (FlightSessionCoordinator.kt:1614). Every clearance in the Android app assigns 4271, which is also a code iOS never produces (its no-digits fallback yields 2312).

### ATC, phraseology, en-route

**Accept Smoother Altitude never appears, and is a no-op if it did**  
✅ Closed — see the smoother-altitude gap above.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4508-4521 (acceptSmootherAltitude), 237 (availability), 4891 (suggestion set), 4967-4971 (nextAltitude preference)`

- **iOS:** A ride report publishes `suggestedSmootherAltitude`; the button then appears labelled "Climb FL390" / "Descend FL330", and tapping it posts the pilot request plus `engine.climbMaintain`/`descendPilotsDiscretion` to that exact level with a read-back, updates `assignedAltitude` and clears the hint. The hint also biases the next plain Request Higher/Lower toward that level.
- **Android:** `PilotActionAvailability` gates the button on `hasSmootherAltitudeSuggestion`, fed from `current.smootherAltitudeLabel` (FlightSessionCoordinator.kt:1191) — a `FlightSessionState` field (FlightSessionState.kt:106) that nothing ever assigns, so it is permanently null and the button never renders. Even if it did, `performPilotAction` returns early for `ACCEPT_SMOOTHER_ALTITUDE` (FlightSessionCoordinator.kt:859-864). `WeatherSessionController.computeSmootherAltitude` (line 295) and `noteSmootherAltitude` (line 320) are never called in main — only `clearSmootherAltitude` from AppGraph.kt:179. `nextAltitudeStep` (line 896) has no smoother-altitude preference.

**Approach's terminal altitude and the go-around pattern altitude are not elevation-aware**  
🟡 Partly closed: the approach terminal altitude is now elevation-aware; the go-around pattern altitude is not.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4136-4138 and 3485 (liveFieldElevationMSL)`

- **iOS:** `buildContext` sets `approachDefaultAltitude = roundedUpToThousand((liveFieldElevationMSL() ?? 0) + 3000)` — 3,000 ft above the *destination field*, in MSL. At Denver that is 9,000 ft, which is also what `goAround()` uses as the pattern altitude (AppModel.swift:4613).
- **Android:** `buildContext` never passes `approachDefaultAltitude`, so it stays at the ATCContext default of 3,000 (ATCContext.kt:`DEFAULT_APPROACH_ALTITUDE = 3_000`) regardless of field elevation. `ATCStateMachine`'s APPROACH branch then tells a pilot arriving at Denver to "descend and maintain 3,000" — below the runway — and `FlightSessionCoordinator.goAround()` (line 741, `val patternAltitude = context.approachDefaultAltitude`) assigns the same 3,000 ft pattern, despite its comment claiming "the terminal fallback the context already computes".

**Center hand-offs name a fixed frequency instead of the sector's**  
✅ Closed — same fix as the Center frequency gap above.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4164 (centerFrequency: currentCenterFrequency) and 4188-4190`

- **iOS:** `currentCenterFrequency` returns `centerSector?.frequency ?? 132.450`, so the Departure→Center hand-off, the Center frequency button and every Center call name the frequency of the sector actually under the aircraft. iOS also uses ground 121.800 and departure 124.300.
- **Android:** `buildContext` passes the constant `DEFAULT_CENTER_FREQUENCY = 133.4` (FlightSessionCoordinator.kt:1513, companion at :1611). `applyCenterSector` (line 1400) updates the sector *name* on the engine but never the context frequency, so only the Center-to-Center crossing call uses `crossing.to.frequency`; the initial Departure→Center hand-off always says 133.4. Android's other defaults also differ from iOS: ground 121.9 vs 121.8, departure 124.35 vs 124.3, Center fallback 133.4 vs 132.45 — all spoken aloud in hand-offs.

**Ramp never hands the pilot to Ground after the push**  
✅ Closed: `handOffDepartureRampToGround` posts `pushComplete` and `contactGround`, moves the radio to Ground and leaves the flow where it was; the pilot then asks Ground for the clearance. `buildContext` fills `rampSpot`, and the duplicate state-machine hand-off is suppressed.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4802-4809 (requestTaxi) and 4836-4853 (onDepartureRampPreTaxi, handOffDepartureRampToGround)`

- **iOS:** At an airport with a ramp layer, tapping Taxi while still on the Ramp frequency does *not* produce a taxi clearance. `handOffDepartureRampToGround()` posts `rampEngine.pushComplete(cs:)` and `rampEngine.contactGround(cs:groundFrequency:spot:)` ("push complete, contact Ground on 121.8 at spot 5"), moves the tuned facility to Ground, and leaves the flow where it was. The pilot then re-requests taxi on Ground for the actual clearance — a two-step sequence.
- **Android:** `performPilotAction(TAXI)` (FlightSessionCoordinator.kt:838) always posts `pilotEngine.requestTaxi` and `onPilotRequest` advances straight to `ATCState.GROUND_TAXI` (line 883), so Ramp itself issues the Ground taxi clearance in one step. There is no `onDepartureRampPreTaxi` equivalent and no `rampEngine.pushComplete`/`contactGround` call anywhere in main. `ATCContext.rampSpot` is likewise never populated by `buildContext`.

**Runway in use is the first one listed, ignores the filed approach, and is empty at unknown fields**  
✅ Closed: `resolveRunway` honours the parsed approach, then the filed runway, then the real active runway for the live wind, then the field's own OSM runway ends.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4207-4243 (resolvedRunway)`

- **iOS:** Resolves in order: on arrival the parsed approach's runway, then the filed arrival runway; on departure the filed departure runway; then the plan's runway; then `runways.activeRunway(for:windDirection:windSpeed:)` — the into-wind pick from the field's real inventory; then the into-wind pick among the runway idents in the loaded airport surface; and finally, as a *name only*, the wind direction rounded to the nearest ten, returned with `isKnown == false` so no caller reads it back as a heading.
- **Android:** `resolveRunway` (FlightSessionCoordinator.kt:1535-1544) checks the filed arrival/departure/plan runway and then returns `RunwayDatabase.runways(icao).firstOrNull().orEmpty()` — the first ident in the table, not the into-wind active runway. It never consults the parsed approach procedure, never consults the loaded surface, and returns an empty string at any field outside the built-in table (so the clearance reads "runway , cleared for takeoff"). `ATCContext.runwayIsKnown` is never set, so it stays `true` even for a derived runway.

**Takeoff clearance fires the instant the aircraft is lined up, with no delay and no stopped check**  
✅ Closed: `maybeIssueTakeoffClearance` clears an aircraft already rolling at once, arms a five-second hold for one lined up *and stopped*, and disarms when it manoeuvres off — re-checked on every telemetry tick rather than by a timer that could fire against a stale picture.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:2293-2334 (autoAdvanceTakeoffClearance, isLinedUpAndStopped, armTakeoffClearance) and :681 (takeoffClearanceDelay = 5)`

- **iOS:** If the aircraft is already rolling, clear immediately; if it is lined up *and stopped* (`onGround && groundSpeed < 5`), arm a 5-second countdown and clear only after re-checking that it is still lined up, still not departed, the gate is still open and no human controller is staffing; if it is neither (still manoeuvring onto the runway) cancel the timer.
- **Android:** `maybeIssueTakeoffClearance` (FlightSessionCoordinator.kt:529-546) tests `isLinedUp || isDepartingRoll || phase == TAKEOFF` and calls `advanceAndPost` immediately. There is no timer, no lined-up-and-stopped distinction and no cancellation path, so the clearance arrives the moment the nose swings onto the centreline rather than a beat after the aircraft settles.

**The ATIS information letter is never added to the taxi request or the Approach check-in**  
✅ Closed: `WeatherAnswering.atisInfoWord` is the one-shot seam — the taxi request and the first Approach check-in of an arrival carry the code, and only a code the pilot actually tuned.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4814-4818 (taxi) and 4732-4757 (Approach check-in), with 8437-8460 (consumeATISInfoWord, appendingATISInfo)`

- **iOS:** The pilot reports the ATIS code on the initial taxi request ("…request taxi, information Alpha") and on the first Approach check-in on arrival ("…with you at seven thousand, information Bravo"). Each is one-shot and only once the corresponding ATIS has actually been received.
- **Android:** `performPilotAction(TAXI)` posts a bare `pilotEngine.requestTaxi(context)` and `checkIn()` posts a bare `pilotEngine.requestHandoff(...)`; neither consults ATIS. `ATISSession.consumeATISInfoWord(arrival:)` (core/atis/ATISSession.kt:256) and `ATISSession.appendingATISInfo(tx:word:)` (line 351) are ported and called nowhere in main. The Android UI nevertheless promises the behaviour: AtcScreen.kt:430-435 renders "the information code is added to your ${taxi request | arrival check-in}".

### Audio, billing, review

**A frequency change never abandons the chatter exchange on the old frequency**  
✅ Closed: `IFATCCompanionApplication` collects `currentFacility` into `chatter.facilityDidChange`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:1160-1167 ($currentFacility.removeDuplicates().sink { self?.chatter.facilityDidChange(to: facility) }); the handler is IFATCCompanion/Chatter/AmbientChatterService.swift:156-182`

- **iOS:** On every distinct facility change AppModel passes the new facility to the chatter service. If a chatter exchange is mid-air for the old facility, abandonCurrentExchange() cuts the playing call, drops the pending read-back tied to it, and the loop settles 0.4–0.8 s and starts a fresh exchange for the newly-tuned frequency — rather than finishing a Tower exchange after the pilot has switched to Ground.
- **Android:** AmbientChatterService.facilityDidChange(facility) has no caller anywhere in :app. Because the loop reads facilityProvider() only once per exchange, a mid-exchange switch leaves the old frequency's controller line and its read-back to play out in full on the new frequency, and the full inter-exchange gap (5–14 s at MODERATE) is waited out before chatter for the new facility begins. shouldAbandonExchange is implemented and unit-tested in :core and reachable from nothing.

**Ambient chatter voice plays at half the iOS level; the correctly-computed level is never used**  
✅ Closed: `AndroidChatterRadio.speak` passes `engine.chatterSpeechLevel` — `chatterLevel * 2.0`, zero while ducked — instead of the raw `chatterVolume`.  
*Behaves differently* · iOS: `IFATCCompanion/Chatter/RadioAudioEngine.swift:199 (let voice: Float = ducked ? 0 : chatterLevel * 2.0, applied to speechMixer.outputVolume at :208)`

- **iOS:** The chatter voice sits at chatterVolume * 2.0 (clamped to 1). At the 0.16 default that is 0.32 — deliberately well above the static bed so the background calls read as half-audible traffic rather than mush. The buffers themselves are rendered at the utterance default of 1.0; the ×2 lives entirely in the mixer.
- **Android:** RadioAudioEngine.kt:264-268 computes exactly the right value — RadioAudio.chatterLevels(chatterLevel, ducked, transmitting).voice — into `chatterSpeechLevel`, and its own KDoc at :291 says "chatter passes [chatterSpeechLevel] instead". Nothing reads it. AndroidChatterRadio.kt:86 passes `configured.chatterVolume` raw into speech.speakChatter(...), which forwards it to radio.playProcessed(rendered, volume) where the samples are multiplied by it (RadioAudioEngine.kt:299-300). The chatter voice therefore plays at chatterVolume, i.e. about 6 dB quieter than iOS, while the static bed around it is at the iOS level — so the mix is wrong in both directions, and the voice is thin under its own static. (Passing chatterSpeechLevel would also carry the duck-to-zero, which is the separate gap above.)

**Chatter names invented runways: the runway pools are never bound**  
✅ Closed — see `ChatterRunwayResolver` above.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:1158-1159 (chatter.bindContext(facility:runways:)) feeding chatterRunwayContext() at AppModel.swift:1176+; consumed at IFATCCompanion/Chatter/AmbientChatterService.swift:248-250`

- **iOS:** Every loop cycle the service refreshes the generator's runwayIdents from the loaded OSM surface of the airport in play (origin pre-departure/climb, destination once descending/arriving) and departureRunwayIdents / arrivalRunwayIdents from that field's ATIS. So background Ground never taxis a jet to a runway the field lacks, Tower clears takeoffs on a departure runway and landings on an arrival runway, and Approach's clearances match the arrival field. docs/BackgroundChatter.md calls this out at length ("Runway references are grounded in the real field").
- **Android:** IFATCCompanionApplication.kt:55 calls chatter.bindContext(facility = { ... }) and omits the `runways` argument, so runwaysProvider keeps its default { ChatterRunwayContext() }. All three pools stay empty for the whole flight and ChatterScriptGenerator falls through to its random-runway fallback (core/.../ChatterScriptGenerator.kt:348, :359, :368) on every call — the app happily says "cleared for takeoff runway one eight" at a field with no runway 18, which is precisely the behaviour the iOS feature exists to prevent. The plumbing (ChatterRunwayContext, the three generator fields, AirportSurfaceCoordinator.cachedRunwayIdents) is ported and tested.

**The "Live flight notification" Settings toggle has no effect**  
✅ Closed: `startTheForegroundServiceWithTheFlight` combines `isSessionActive` with `liveActivityEnabled`, and `ActiveFlightService.dismiss` takes the notification down without ending the flight.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:1284-1291 (updateLiveActivityRunState: if settings.liveActivityEnabled { liveActivity.start(...) } else { liveActivity.end() }), driven from applyChatterSettings at :1233-1239 and the settings observer at :1356-1359`

- **iOS:** Turning the toggle on starts the Live Activity; turning it off ends it immediately, and refreshLiveActivity() (:1295-1298) refuses to push while it is off. The default is false (AppSettings.swift:352), so a user who never touches it never gets the card.
- **Android:** The Android Settings screen shows a toggle with the identical label — SettingsLabels.LIVE_ACTIVITY = "Live flight notification" (core/.../settings/SettingsLabels.kt:95), rendered at SettingsScreen.kt:312-316 — which persists liveActivityEnabled, and no production code ever reads it. ActiveFlightService posts and re-posts the Live Flight Update for the whole session regardless (ActiveFlightService.kt:120-158). Flipping it off changes nothing the pilot can see. (Android must post *a* foreground-service notification, but nothing gates the flight card's content, its Read Back / Check In actions, or the Android-16 promoted-ongoing request on the setting the pilot was given.)

**The Live Flight Update never shows the weather advisory**  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:1317 (weatherAlert: activeWeatherConflict != nil ? "Weather ahead on route" : nil), on the ContentState field declared at IFATCCompanion/LiveActivity/CompanionActivityAttributes.swift:65`

- **iOS:** While a route weather conflict is active, the Lock Screen / Dynamic Island card carries the line "Weather ahead on route" — the one warning that reaches the pilot with the phone in their pocket.
- **Android:** LiveFlightUpdateProjection.kt:55 hardcodes weatherAlert = null. The field exists on LiveFlightUpdate (:39) and FlightNotifications.kt:137 is ready to render it, so the notification is structurally complete and permanently blank in that slot — the advisory never appears. The proximate cause is upstream of my lane: WeatherSessionController.kt's own KDoc (line 68-69) says "The route-conflict/deviation solver is not driven from here yet", and grep finds no construction of RouteWeatherConflictDetector or WeatherDeviationEngine outside core/weather/deviation itself, so no conflict state exists for the projection to read. I report it here because the missing content is on the Live Flight Update, and because Docs/ANDROID_PARITY_MATRIX.md:105 marks route weather conflict detection ✅ rather than recording it as undriven.

**The Voice volume slider does nothing unless the radio voice effect is on**  
✅ Closed: both clean-path `speak()` calls pass `KEY_PARAM_VOLUME`, so the slider governs the clean path, the Settings audition and the chatter's render fallback.  
*Behaves differently* · iOS: `IFATCCompanion/Speech/SpeechService.swift:110 (utterance.volume = Float(min(max(settings.voiceVolume, 0), 1))) and SpeechService.swift:252 for the Settings audition`

- **iOS:** Every utterance — controller, pilot, ATIS, and the Settings voice audition — is stamped with the user's voiceVolume, on both the clean path and the radio-effect path, so the slider always governs how loud the radio is relative to everything else the phone is playing.
- **Android:** On Android the volume is applied only where the samples pass through the radio chain: AndroidSpeechService.play() → radio.playProcessed(rendered, voiceVolume) (AndroidSpeechService.kt:427). The clean path speakAndWait() calls it.speak(text, QUEUE_ADD, null, id) (AndroidSpeechService.kt:501) and previewVoice() calls it.speak(sample, QUEUE_FLUSH, null, ...) (:359-365) — both pass a null params Bundle, so TextToSpeech.Engine.KEY_PARAM_VOLUME is never set and the utterance plays at the engine default (1.0). applyVoice() (:511-527) sets only voice, rate and pitch. Result: with "Transmission static" off — or on any call whose synthesizeToFile render fails and falls back — the slider is inert and the calls come out at full volume. The same omission hits the chatter's own fallback: speakChatter() falls through to speakAndWait() (:316) when the render returns nothing, where iOS's speakFallback (AmbientChatterService.swift:356-362) sets utterance.volume = chatterVolume * 1.4, so a chatter line that fails to render plays at full voice volume instead of a quiet 0.22.

### Found by the completeness pass

**"Say Again" never makes the controller repeat the call** ✅ FIXED  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:4451-4460`

- **iOS:** `sayAgain()` posts the pilot's "say again" and then re-posts the last ATC transmission verbatim — same display text, spoken text and read-back — with `speak: true`, so the controller actually repeats the instruction the pilot missed (AppModel.swift:4454-4459). (The same is done for the weather-frequency variant at AppModel.swift:8080-8090.)
- **Android:** `FlightSessionCoordinator.sayAgain()` is `fun sayAgain() = postPilot { pilotEngine.sayAgain(it, _state.value.workingFacility) }` (core/session/FlightSessionCoordinator.kt:655) — the pilot's half only. Nothing re-posts or re-speaks `latestTransmission`, so the Say Again button (PilotActionPresentation.kt:80 → FlightViewModel.kt:485) produces a pilot call and silence. There is a separate Replay control on the transmission card (`actions.onReplay`, AtcScreen.kt:165), but that is the iOS speaker button, not Say Again, and it puts nothing on the frequency.

**"Unable" gets no controller reply — the pilot transmits and the frequency stays silent** ✅ FIXED  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:4462-4476`

- **iOS:** `unable()` posts the pilot's "unable" and then a deterministic controller answer built inline: "<callsign>, roger, maintain <alt>, advise able to comply." spoken with `Phonetic.altitude`, carrying its own read-back ("Maintain <alt>, <callsign>.") on the current facility, posted with `speak: true`. The altitude is `max(assignedAltitude, c.initialClimbAltitude)`.
- **Android:** `FlightSessionCoordinator.unable()` is a one-liner: `fun unable() = postPilot { pilotEngine.unable(it, _state.value.workingFacility) }` (core/session/FlightSessionCoordinator.kt:657). `postPilot` (kt:927-929) posts exactly one transmission and returns. There is no controller response, no "advise able to comply", and no read-back — so tapping the red Unable button (rendered from `PilotActionPresentation.Acknowledgement.UNABLE`, PilotActionPresentation.kt:81, dispatched at FlightViewModel.kt:486) leaves the controller mute.

**A push-to-talk transmission is spoken back at the pilot — the "input came by voice" suppression is missing**  
✅ Closed: `handleSpokenPilotText` brackets its dispatch with `pilotInputViaVoice`, and every pilot post goes through `shouldSpeakPilot`.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:3538 (`private var shouldSpeakPilot: Bool { settings.speakPilot && !pilotInputViaVoice }`), :4350-4360 (`handleSpokenInput` sets `pilotInputViaVoice = true` around `perform(intent)`)`

- **iOS:** `handleSpokenInput` brackets the dispatched action with `pilotInputViaVoice = true / false` (AppModel.swift:4356-4358) with the comment "The user already spoke this; don't re-speak the pilot transmission." Every pilot transmission is posted with `speak: shouldSpeakPilot` (AppModel.swift:3534), which is `settings.speakPilot && !pilotInputViaVoice` — so a read-back the pilot said into the mic goes into the transcript but is not synthesized back at them.
- **Android:** `FlightSessionCoordinator.handleSpokenPilotText` (core/session/FlightSessionCoordinator.kt:1450-1464) parses the intent and calls `readBack()` / `sayAgain()` / `unable()` / `checkIn()` / `performPilotAction(action)` directly, with no equivalent flag. Every one of those paths posts with `speakIt = settings.speakPilot` (FlightSessionCoordinator.kt:646, 691, 706, 789, 867, 928), and `AppSettings.speakPilot` defaults to `true` (core/settings/AppSettings.kt:135). So on Android, holding the mic and saying "cross runway six right, United five nine eight" is immediately followed by the app's TTS reciting the same line back over the radio. `grep` for `pilotInputViaVoice` or any "via voice" flag in `core/src/main` and `app/src/main` returns nothing.

**The Settings Mock Mode toggle persists the flag but never switches modes — the feed is neither started nor stopped and the session is not ended**  
✅ Closed: `updateSettings` routes a mode change through `FlightSourceController.toggleMockMode`.  
*Ported, not wired* · iOS: `IFATCCompanion/Views/SettingsView.swift:85-89 (toggle bound to `model.toggleMockMode`, `.disabled(liveLocked)`), IFATCCompanion/App/AppModel.swift:1616-1633`

- **iOS:** The Settings Mock Mode toggle is bound directly to `model.toggleMockMode($0)` (SettingsView.swift:86-88), which clears the airport surface, rebuilds the plan from the mock route and calls `startMock()`, or calls `enterLiveMode()` (AppModel.swift:1625-1632) — and refuses the switch without entitlement. The Diagnostics toggle (DiagnosticsView.swift:41-43) calls the identical method, so both switches do the same thing.
- **Android:** The Settings toggle (SettingsScreen.kt:92-99) calls `update { it.copy(mockMode = on) }` → `FlightViewModel.updateSettings` (FlightViewModel.kt:807-816), which does `settingsRepository.replace(settings)`, reconfigures chatter and rebuilds the phraseology engine — and never calls `graph.setMockMode(...)`. Only the Diagnostics toggle (`onToggleMockMode`, FlightViewModel.kt:957-959) reaches it. Nothing else in `:app` observes `settings.mockMode` to drive the transport. So flipping Mock Mode off in Settings leaves the mock feed running and the session "active" (the `endSession()` at AppGraph.kt:502 never fires), and flipping it on in Settings never calls `mockFeed.start()`; only the flag and the UI labels change.

**The Weather Diagnostics card is a different card: `WeatherProviderDiagnostics` is constructed nowhere, so the wind, conflict, deviation and radar-data-usage rows do not exist on Android**  
*Behaves differently* · iOS: `IFATCCompanion/Views/DiagnosticsView.swift:137-176 (`weatherDiagnosticsCard`), fed by IFATCCompanion/App/AppModel.swift:6227-6300 (`updateWeatherDiagnostics`) and published at AppModel.swift:375`

- **iOS:** The Diagnostics screen's Weather Diagnostics card prints eighteen named rows from `model.weatherDiagnostics`: Precip source, Overlay coverage, Last radar update, Radar data (OPERA) — the last-download / session-total byte counters that measure real cellular usage — Last aviation wx update, Hazards detected, Sampled radar cells, Route conflict (with the monitoring / on-flight-path / discarded-excursion wording composed at AppModel.swift:6244-6283), Rejoin fix, Deviation state, Wind in use, Sim-reported wind, Solved wind, Reported vs solved, Magnetic variation, Last weather vector and Departure heading, plus a provider-error line and a coverage message. The wind rows exist specifically so a vector that comes out wrong can be checked against Infinite Flight's own panel (WeatherProviderDiagnostics.swift:23-45).
- **Android:** `WeatherProviderDiagnostics` is ported in full — all the fields and every computed string (`solvedWindText`, `reportedWindText`, `windSourceText`, `reportedWindDeltaText`, `magneticVariationText`, `radarDataUsageText`, core/weather/WeatherProviderDiagnostics.kt:91-158) — and is **constructed nowhere**: the only occurrences of the type name in `core/src/main` and `app/src/main` are its own declaration and `companion val empty`. The Android card is built instead by `weatherDiagnosticRows(weather)` (app/ui/ScreenModels.kt:357-366), which renders eight entirely different rows: METARs, PIREPs, SIGMETs, Ride index, Overlay layer, Overlay source, Sampled cells, Mock cells. No wind rows, no magnetic variation, no route-conflict line, no deviation state, no rejoin fix, no provider error and no radar byte counters — `OPERACompositeStore.dataUsage()` (core/weather/radar/OPERACompositeRenderer.kt:563) also has zero callers outside its own file. `WeatherProviderDiagnostics.departureHeadingSummary` was noted by an earlier lane as never assigned; in fact the entire object is.

### Airport surface, Connect, Mock Mode

**"Refresh airport data" in Settings does not force a refresh, so it is a no-op for up to 75 days**  
✅ Closed: the button passes `forceRefresh = true` and also calls `AirportSurfaceCoordinator.refreshData()`, so the surface an in-progress taxi is routing on is refreshed too.  
*Behaves differently* · iOS: `IFATCCompanion/AirportSurface/AirportSurfaceCoordinator.swift:1290-1293 (refreshData → loadSurface(..., forceRefresh: true)); IFATCCompanion/Views/SettingsView.swift:412-416`

- **iOS:** The Settings button calls model.airportSurface.refreshData(), which re-loads the *currently active* airport's surface with forceRefresh: true — bypassing the cache-freshness check so a pilot who knows the OSM data changed (or whose extract came back partial) actually gets a new Overpass fetch.
- **Android:** FlightViewModel.onRefreshAirportData() (FlightViewModel.kt:838-840) calls graph.surface.refresh(session.value.flightPlan) and omits the forceRefresh argument, which defaults to false (SurfaceSessionController.kt:88). AirportSurfaceProvider.surface (AirportSurfaceProvider.kt:156-170) returns the cached model immediately when `!forceRefresh` and the cache is neither stale (75-day interval, OSMSurfaceConstants.CACHE_REFRESH_INTERVAL_SECONDS) nor schema-outdated, so the button performs no network request at all for a recently-cached airport. It also targets SurfaceSessionController rather than AirportSurfaceCoordinator, so even a successful re-fetch does not refresh the surface an in-progress taxi is routing on; AirportSurfaceCoordinator.refreshData() (which does pass forceRefresh = true, AirportSurfaceCoordinator.kt:1719-1725) has no caller.

**Live ATC staffing status never reaches the session, so the companion never stands by for a human controller in live mode**  
✅ Closed: `AppGraph` mirrors `IFConnectManager.state.liveATC` into `FlightSessionCoordinator.applyLiveATC` in live mode.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:1381-1386 (connect.$liveATC subscription) and :1410-1428 (applyLiveATC); IFATCCompanion/Connect/IFConnectStateReader.swift:194-228 (readATCStatus) feeding IFConnectManager's published liveATC`

- **iOS:** iOS subscribes to connect.$liveATC and, in live mode, applies each status to model.liveATC. That drives companionShouldStandBy — the per-frequency, location-aware rule that makes the companion go quiet while the pilot is tuned to a staffed human IFATC controller — and logs the human-ATC and standby transitions; the Diagnostics and ATC views show liveATC.summary.
- **Android:** IFConnectManager polls and publishes liveATC correctly (IFConnectManager.kt:509-511, reader.readATCStatus), but nothing ever copies it into FlightSessionState.liveATC. FlightSessionCoordinator wires connect.onState and connect.onFlightPlan (FlightSessionCoordinator.kt:197-198) and nothing else; FlightSessionState.liveATC (FlightSessionState.kt:76) has no writer, so it stays LiveATCStatus.none for the whole flight. computeStandby (FlightSessionCoordinator.kt:1241-1246) therefore always reads false on the live branch, and the Diagnostics "Live ATC" row (ScreenModels.kt:315 → DiagnosticsScreen.kt:116) always renders the none-state summary. The mock branch (simulateStaffedATC) is wired and works; only the live half is missing.

**Mock Mode taxis the bundled synthetic field, never the real OSM airport — prepareSimulatedSurfaces has no caller**  
*Ported, not wired* · iOS: `IFATCCompanion/AirportSurface/AirportSurfaceCoordinator.swift:266-286 (prepareSimulatedSurfaces) and :288-295 (storeSimulatedSurface); IFATCCompanion/App/AppModel.swift:853-866 (prefetchAirportSurfaces, mock branch)`

- **iOS:** When Mock Mode is on, iOS pre-caches the real OSM extracts for the demo's own origin and destination (KIAH and KMSP) and records their reference coordinates, so the simulated taxi runs on the actual airport — the demo taxis Houston's real taxiways, not a synthetic stand-in. It falls back to the synthetic field only when a real extract genuinely can't be produced.
- **Android:** AirportSurfaceCoordinator.prepareSimulatedSurfaces(...) is never called from :app or :core, so `simulatedReferences` (AirportSurfaceCoordinator.kt:307) is always empty outside tests. loadSimulatedSurface (AirportSurfaceCoordinator.kt:757-772) reads `if (simulatedReferences[icao] == null) { installSyntheticSurface(generation); return }`, so in Mock Mode it always takes the synthetic branch and fetchRealSimulatedSurface (the path whose KDoc says it exists precisely to stop the demo dropping onto "the tiny synthetic field") is unreachable. The same omission leaves runwayIdentsByICAO unpopulated for the demo airports, since recordRunwayIdents is reached from storeSimulatedSurface. Note this is not covered by SurfaceSessionController.refresh (FlightViewModel.kt:145) — that warms the provider's cache but never touches the coordinator's simulatedSurfaces/simulatedReferences maps.

**The field's OSM runway idents are never consulted — cachedRunwayIdents has no caller, and wind-based active-runway selection is unwired**  
✅ Closed: `SurfaceSessionController.runwayIdents` feeds `resolveRunway`.  
*Ported, not wired* · iOS: `IFATCCompanion/AirportSurface/AirportSurfaceCoordinator.swift:307 (cachedRunwayIdents); IFATCCompanion/App/AppModel.swift:1178-1190 (chatterRunwayContext) and :4207-4242 (resolvedRunway, especially :4230)`

- **iOS:** iOS uses the runway-end idents parsed from the loaded airport surface in two places. (1) resolvedRunway falls back, after the filed/typed runway and the curated RunwayDatabase, to runways.activeRunway(among: airportSurface.cachedRunwayIdents(icao:), windDirection:, windSpeed:) — picking the into-wind runway from the field's *real* runways, and only then to a wind-derived name flagged as not-known. (2) chatterRunwayContext feeds those idents (reconciled against the ATIS active runways) to the background chatter so its runway references are the field's actual runways.
- **Android:** AirportSurfaceCoordinator.cachedRunwayIdents(icao) has zero call sites outside its own file. RunwayDatabase.activeRunway(code, windDirection, windSpeed) and RunwayDatabase.activeRunwayAmong(idents, ...) — both fully ported — have zero call sites in main sources, so wind never influences runway choice at all. FlightSessionCoordinator.resolveRunway (FlightSessionCoordinator.kt:1535-1544) is the whole Android implementation: filed runway, else `RunwayDatabase.runways(icao).firstOrNull().orEmpty()` — the first ident in the static table regardless of wind, and an empty string at any field not in the table (no OSM fallback, no wind-derived name). Separately AmbientChatterService.bindContext is called with only `facility` (IFATCCompanionApplication.kt:55), so runwaysProvider keeps its `{ ChatterRunwayContext() }` default and the chatter never learns the field's runways; ATISRunwayParser has no callers either. Pilot-visible: at KIAH with a westerly wind iOS says runway 26L/26R and Android says 26L only by luck of table order, and at any uncurated field iOS names a real runway from the OSM extract while Android names none.

### Screens and settings

**"Deviations from satellite estimate" toggle renders but is read by nothing**  
*Ported, not wired* · iOS: `IFATCCompanion/Views/SettingsView.swift:324-326 (toggle, whose setter also calls `model.applySatelliteDeviationSettingChange()`); AppSettings.swift:290-296`

- **iOS:** Opts in to driving the deviation flow from the NASA satellite estimate where there is no radar coverage, and immediately re-applies the change to the running session.
- **Android:** `SettingsScreen.kt:359-365` renders the toggle; `satelliteDeviationsEnabled` has no reader outside the settings plumbing and the Settings screen, and there is no equivalent of `applySatelliteDeviationSettingChange()`.

**"Keep screen awake" toggle renders but is read by nothing**  
✅ Closed: `MainActivity` collects `keepScreenAwake` and adds or clears `FLAG_KEEP_SCREEN_ON` on the window.  
*Ported, not wired* · iOS: `IFATCCompanion/Views/SettingsView.swift:115; AppModel.swift:1148 (`UIApplication.shared.isIdleTimerDisabled = settings.keepScreenAwake`) and :1351 (observed)`

- **iOS:** Disables the idle timer so the companion's screen never locks — the setting defaults on precisely because Infinite Flight drops the Connect link when the companion device locks.
- **Android:** `SettingsScreen.kt:119-124` renders the toggle; `keepScreenAwake` has no reader outside the settings plumbing, and the app never sets `FLAG_KEEP_SCREEN_ON` or `Modifier`/`View.keepScreenOn` anywhere.

**"Live flight notification" toggle renders but is read by nothing**  
✅ Closed — same fix as the toggle gap above.  
*Ported, not wired* · iOS: `IFATCCompanion/Views/SettingsView.swift:274; AppSettings.swift:222-234`

- **iOS:** Turns the live-updating Lock Screen / Dynamic Island flight activity on and off.
- **Android:** `SettingsScreen.kt:311-316` renders the toggle, but `liveActivityEnabled` has no reader anywhere in `app/src/main` or `core/src/main` outside the settings plumbing — `ActiveFlightService` / `FlightNotifications` never consult it, so the Live Flight Update appears whenever a flight is running regardless of the switch. (The documented 🔵 divergence at Docs/ANDROID_PARITY_MATRIX.md:170 is only that the notification is *independent of chatter* on Android, not that its own toggle is ignored.)

**Flight screen's "Distance to Dest" shows the distance to the nearest airport, not to the destination**  
✅ Closed: `distanceToDestinationNM` resolves the destination through Infinite Flight's reported position, then `AirportDatabase`, then the plan's last located fix, and measures to that.  
*Behaves differently* · iOS: `IFATCCompanion/Views/FlightView.swift:48 and :199-209 (`distanceToDest` resolves the destination via AirportDatabase → `flightPlan.destinationCoordinate` → last located fix, then `Geo.distanceNM(from: pos, to: dest)`)`

- **iOS:** Reads the great-circle distance from the aircraft to the destination airport, so it counts down through the flight.
- **Android:** `ScreenModels.kt:162-163` sets `distanceToDestination = session.aircraftState.nearestAirportDistanceNM?.let { "${it.toInt()} NM" }`. `nearestAirportDistanceNM` (AircraftState.kt:76) is the distance to whichever airport is nearest — the mock feed sets it to the departure for the first half of the flight and the destination for the second (MockSimulatorFeed.kt:185-189). So the row labelled "Distance to Dest" (FlightScreen.kt:114) counts *up* away from the departure field for the first half of every flight.

**SIGMET/PIREP "Endpoint" field renders but the weather service is never configured with it**  
✅ Closed: `AppGraph` configures `AviationWeatherService` with `settings.weatherBaseURL` at construction, and `FlightViewModel.updateSettings` re-configures it when the field changes.  
*Ported, not wired* · iOS: `IFATCCompanion/Views/SettingsView.swift:294-299; AppModel.swift:745 (`AviationWeatherService(baseURL: settings.weatherBaseURL)`) and :1059 (`weatherService.configure(baseURL: settings.weatherBaseURL, …)`)`

- **iOS:** The typed base URL is what the aviation-weather service actually fetches METARs, TAFs, PIREPs and SIGMETs from, re-applied via `configure` when it changes.
- **Android:** `SettingsScreen.kt:342-347` renders the field and persists it, but `AppGraph.kt:250-251` builds `AviationWeatherService(http, clock, diagnostics)` with the default base URL and `AviationWeatherService.configure(baseUrl = …)` (AviationWeatherService.kt:87-92) is never called. `weatherBaseURL` has no reader outside the settings plumbing, so editing the endpoint changes nothing.

**The ATC status pill never shows the working Center sector name**  
✅ Closed: `facilityLabel` reads `centerSectorName` for Center, falling back to the plain title before the sector map has resolved one.  
*Behaves differently* · iOS: `IFATCCompanion/Views/ATCView.swift:191-192 (`model.facilityLabel(for: model.currentFacility)`); AppModel.swift:2596-2602 (`facilityLabel` returns `sector.radioName` for Center)`

- **iOS:** The facility pill reads "Fort Worth Center" once an enroute sector is known, and the plain title for every other facility.
- **Android:** `ScreenModels.kt:77` sets `facilityLabel = session.currentFacility.title`, so the pill always reads a bare "Center". The sector name is available — `FlightSessionState.centerSectorName` (FlightSessionState.kt:70) is maintained by FlightSessionCoordinator.kt:1403-1408 and is already used for the Settings "Working sector" row (ScreenModels.kt:234) — it is simply not used here, despite the comment at AtcScreen.kt:167-172 claiming the pill does show it.

**The ATC weather banner uses a different trigger and different text, and tapping it sends a ride report instead of a weather advisory**  
🟡 Partly closed: the banner now keys off the precipitation conflict and its distance; what tapping it sends is unchanged.  
*Behaves differently* · iOS: `IFATCCompanion/Views/ATCView.swift:30, :287-311 (banner, `model.askCenterAboutWeather()`); AppModel.swift:6375-6389 (`weatherBannerVisible` / `weatherBannerText`), :6703-6722 (`askCenterAboutWeather`)`

- **iOS:** Shows the banner only when weather alerts are enabled and there is a flyable precipitation conflict or an active ride SIGMET, with the text "Weather ahead — contact ATC", "<Turbulence> advisory — contact ATC", or "Weather near final — advisory only". Tapping it posts a pilot request for a weather advisory and runs the controller's advisory through the deviation engine.
- **Android:** `ScreenModels.kt:143-147` shows the banner whenever `weather.routeSigmets` is non-empty — ignoring `weatherDeviationAlerts`, ignoring radar precipitation conflicts entirely, and ignoring the on-final case — with the text "<hazard> along your route". `ScreenModels.kt:118` wires the tap to `onContactAtcAboutWeather`, which is `FlightViewModel.kt:535: fun onContactAtcAboutWeather() = coordinator.performPilotAction(PilotAction.RIDE_REPORT)` — a ride report, not the weather advisory.

**The ATIS tune button uses different wording, never dismisses, and lights up as the active frequency — and `ATISSession`'s ported helpers are used by nothing**  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:8254-8258 (`atisButtonVisible`), :8262 (`atisButtonActive` is hard-coded false, with the comment explaining why), :8277-8280 (`atisButtonSubtitle` → "Info B" / "Listen"), :8285-8294 (`atisReceiptSummary` → "KLAX arrival information Bravo — added to your check-in."); consumed at IFATCCompanion/Views/ATCView.swift:430-467`

- **iOS:** The ATIS button's subtitle reads "Info B" or "Listen"; it is never drawn as the active/tuned frequency; it leaves the grid once the pilot tunes any controller for that phase; and the receipt underneath reads "KLAX arrival information Bravo — added to your check-in."
- **Android:** `:core` has all four rules ported verbatim on `ATISSession` (`atisButtonVisible` ATISSession.kt:130, `atisButtonSubtitle` :149-152 returning exactly "Info $code"/"Listen", `atisReceiptSummary` :160-167 returning the same sentence, plus `departureATISDismissed`/`arrivalATISDismissed` at :75-76 set at :244) — and `ATISSession` is never instantiated in `:app`; only its static `atisTransmission` is used (AndroidSpeechService.kt:279). `ScreenModels.kt:83-93` re-derives all four differently: `atisButtonVisible = atis != null` (never dismissed), subtitle "Information Bravo" or empty string, `atisButtonActive = atisReceiptSummary(...) != null` (so the button *does* render filled/active, which iOS explicitly refuses), and receipt "Reporting information Bravo".

**The Tune Frequency grid is built from the raw enum instead of the ported `AtcFlowOrder.tunableFacilities`, so Ramp can appear twice and the order differs**  
✅ Closed: the grid is built from `AtcFlowOrder.tunableFacilities`, which excludes Ramp — `AtcScreen` appends its own Ramp button.  
*Behaves differently* · iOS: `IFATCCompanion/Views/ATCView.swift:477-479 (`AppModel.tunableFacilities.filter { model.relevantFacilities.contains($0) }`); AppModel.swift:2579-2580 (`[.clearance, .ground, .tower, .departure, .center, .approach]`, deliberately excluding Ramp, which ATCView adds separately at :445-455)`

- **iOS:** The grid lists only the six ATC positions in the fixed gate-to-gate order, and appends a single Ramp button separately when `model.canContactRamp`.
- **Android:** `:core` already carries the exact list as `AtcFlowOrder.tunableFacilities` (AtcFlowOrder.kt:122-129), whose KDoc says "Ramp is handled separately because it is only live for the push and the gate" — and it is referenced by nothing. `ScreenModels.kt:82` instead does `ATCFacility.entries.filter { it in session.relevantFacilities }`, and `ATCFacility.entries` is ordered CLEARANCE, RAMP, GROUND, TOWER, DEPARTURE, CENTER, APPROACH. `relevantFacilities` always contains `currentFacility` (AtcFlowOrder.kt:110), so while the pilot is tuned to Ramp the grid renders a Ramp button from the loop, and `AtcScreen.kt:411-425` then renders a second Ramp button because `canContactRamp` is still true (PilotActionAvailability.kt:184-187 keeps it true for the push and until PARKED).

**The three airport-surface toggles (auto crossing calls, auto-recalculate, auto-assign gates) render but are applied to nothing; GateAssigner is constructed only in tests**  
✅ Closed: `taxiAutoCrossingCalls` and `taxiAutoRecalculate` are applied to `AirportSurfaceCoordinator` at construction and on every settings change; `autoAssignGates` drives `AutoGateController`.  
*Ported, not wired* · iOS: `IFATCCompanion/Views/SettingsView.swift:409-411 and their bindings at :381-397; AppSettings.swift:246-268 ("Applied to `AirportSurfaceCoordinator.autoCrossingCalls` by `AppModel`")`

- **iOS:** `taxiAutoCrossingCalls` and `taxiAutoRecalculate` are pushed onto the live `AirportSurfaceCoordinator`; `autoAssignGates`' setter also calls `model.applyAutoGateSettingChange()` so it takes effect on the flight already loaded.
- **Android:** `SettingsScreen.kt:417-432` renders all three. `AirportSurfaceCoordinator` exposes settable `autoCrossingCalls` (:260-262) and `autoRecalculate` (:264-266) and consumes them at :1386, :1490 and :1525 — but nothing in `:app` ever assigns them, so they stay at their construction defaults whatever the pilot chooses. `autoAssignGates` has no reader at all, and `GateAssigner` is referenced only from `core/src/test/.../GateAssignmentTest.kt` — it is constructed nowhere in `:app` or `core/src/main`, so gates are never auto-assigned.

### Weather and ATIS

**ATISRunwayParser is ported but never called, so background chatter names random runways instead of the field's active ones**  
✅ Closed: `ChatterRunwayResolver` picks the field in play, parses the ATIS through `ATISRunwayParser` and reconciles it against the OSM runway inventory; `bindContext` is called with both `facility` and `runways`.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:1178 chatterRunwayContext → :1185 ATISRunwayParser.activeRunways(atis), :1206-1210 reconcileRunways, :1216 orderedRunwayUnion; bound at AppModel.swift:1158-1159 chatter.bindContext(facility:runways:)`

- **iOS:** Feeds the ambient chatter the runways of the airport currently in play: the ATIS's active departure/arrival runways parsed out of the D-ATIS text, reconciled against the field's OSM runway set so a parse slip can never make the chatter name a runway that is not there, falling back to the full OSM runway set and only then to a random runway.
- **Android:** IFATCCompanionApplication.kt:55 calls chatter.bindContext(facility = { ... }) and omits the runways lambda, so AmbientChatterService keeps its default `{ ChatterRunwayContext() }` (AmbientChatterService.kt:137) — an all-empty context — at the one read site (AmbientChatterService.kt:352). ATISRunwayParser.kt (216 lines, ported with tests) is called from nowhere at all. A pilot hears simulated traffic cleared onto runways picked at random, which at a given field will routinely be runways that do not exist there and will contradict the ATIS the app just read them.

**No periodic weather/ATIS refresh — PIREPs freeze at the connect-time snapshot and the arrival D-ATIS is never fetched in flight**  
✅ Closed: `FlightSourceController` arms a 300 s weather + ATIS refresh for as long as a feed is running.  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:5010 (weatherRefreshInterval = 300), :5025 armWeatherRefreshTimer → :5032 refreshWeather() → :5075/:5113 refreshATIS()`

- **iOS:** While a feed is active a timer re-fetches METARs/TAF/PIREPs/SIGMETs — and D-ATIS with them — every 300 s, the interval deliberately set to the service's cache TTL so each tick revalidates. docs/Weather.md states the reason outright: "so the PIREP/ride-report pool stays current through a long flight instead of freezing at the connect-time snapshot". Because refreshATIS runs inside refreshWeather, the arrival field's ATIS starts being fetched as soon as the aircraft comes inside the 100 NM arrival range.
- **Android:** There is no timer. graph.weather.refresh() has exactly three call sites in :app: FlightViewModel.kt:149 (fires only when flightPlan.departure/destination changes, i.e. essentially once at connect), FlightViewModel.kt:798 (the manual "Refresh weather" button, WeatherScreen.kt:169), and AppGraph.kt:188 (restoring a saved flight). Consequences a pilot sees: the PIREP list and ride assessment on the Weather tab, and the ride index, stop updating after the first fetch and go stale as reports fall behind the aircraft; and because WeatherSessionController.refreshAtis only fetches the arrival ATIS when already within ARRIVAL_ATIS_RANGE_NM = 100 (WeatherSessionController.kt:376-380) and that check last ran at the gate, arrivalAtis stays null — so `atisButtonVisible = atis != null` (ScreenModels.kt:83) hides the ATIS button for the whole arrival unless the pilot happens to tap it (onTuneAtis, FlightViewModel.kt:502, is the only other refreshAtis caller).

**The smoother-altitude suggestion is never computed, so the green "Climb/Descend FLxxx" accept button can never appear**  
✅ Closed — see the smoother-altitude gap above.  
*Ported, not wired* · iOS: `IFATCCompanion/App/AppModel.swift:4891 (suggestedSmootherAltitude = smoother), :237 (actions.insert(.acceptSmootherAltitude)), :4508 acceptSmootherAltitude, :4969; UI at IFATCCompanion/Views/ATCView.swift:551-554`

- **iOS:** After a ride report, computeSmootherAltitude scans route-corridor PIREPs at *other* levels and, when one supports it, names a specific smoother altitude in the controller's call ("Smooth ride reported at flight level three three zero; advise if you'd like to climb."), stores it as a one-shot hint, adds the .acceptSmootherAltitude response button, and targets the next higher/lower request at that exact level.
- **Android:** WeatherSessionController.computeSmootherAltitude (WeatherSessionController.kt:295) and noteSmootherAltitude (:320) have no caller in :app — only clearSmootherAltitude is called (AppGraph.kt:179, on starting a new flight). WeatherSessionState.suggestedSmootherAltitude is therefore permanently null, so ScreenModels.kt:100 smootherAltitudeTitle always returns null. Independently, FlightSessionState.smootherAltitudeLabel (core/session/FlightSessionState.kt:106) is never assigned, so PilotActionAvailability's `if (inputs.hasSmootherAltitudeSuggestion)` (PilotActionAvailability.kt:79) is never true and ACCEPT_SMOOTHER_ALTITUDE is never offered — FlightSessionCoordinator.kt:859 handles the action with a bare `return` and a comment saying the subsystem "is wired in separately… until those land, the button is not offered".

---

## Low (17)

### Session orchestration and models

**A saved flight carries no Diagnostics log, and there is no way to restore one**  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:3602 (snapshotForSaving attaches `diagnostics.captureSnapshot()`), :3771 (`if let log = snap.diagnostics { diagnostics.restore(log) }`)`

- **iOS:** Saving a flight attaches the Diagnostics log to the snapshot (deliberately only for saved flights, not the auto-resume one), and loading it back restores the log so the flight's history is inspectable.
- **Android:** There is no `snapshotForSaving` equivalent — `SavedFlightsController.saveCurrentFlight` (core/persistence/SavedFlightsController.kt:58) calls the same `captureSnapshot` the resume path uses, which never sets `diagnostics`. `DiagnosticsStore` (core/diagnostics/DiagnosticsStore.kt) exposes `log`, `clear` and `exportText` and has no `restore`, so `DiagnosticsSnapshot` has no consumer.

**The altitude ladder for Request Higher/Lower uses a different base and a different floor**  
✅ Closed: the ladder now measures from `max(assignedAltitude, live altitude)` with a 4,000 ft floor and a 35,000 ft base, and prefers the smoother level.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4966-4977 (nextAltitude), called at :4480 and :4496 with `from: max(assignedAltitude, aircraftAltInt())``

- **iOS:** Prefers a ride-report-backed smoother level when it lies in the requested direction; otherwise starts from `max(assignedAltitude, current aircraft altitude)`, falls back to the cruise altitude or 35,000 ft when that is zero, steps 2,000 ft, and never returns below 4,000 ft.
- **Android:** `nextAltitudeStep` (FlightSessionCoordinator.kt:896-900) starts from `assignedAltitude` alone (ignoring how high the aircraft actually is), falls back to `flightPlan.cruiseAltitude` with no 35,000 ft backstop — so with no cruise altitude filed the request asks for 2,000 ft — floors at 2,000 rather than 4,000, and never consults the smoother-altitude suggestion.

**The takeoff clearance is issued instantly rather than after the realism delay**  
✅ Closed — same fix as the takeoff-clearance timing gap above.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:2313 (armTakeoffClearance), :681 (`var takeoffClearanceDelay: TimeInterval = 5`), :2293 (autoAdvanceTakeoffClearance)`

- **iOS:** When the aircraft settles lined up and stopped, Tower waits 5 seconds, re-checks that the pilot is still lined up, has not departed, is not standing by for a human controller and does not owe a read-back, and only then clears the takeoff. An aircraft already rolling is cleared immediately.
- **Android:** `maybeIssueTakeoffClearance` (FlightSessionCoordinator.kt:539-552) clears the moment `isLinedUp || isDepartingRoll || phase == TAKEOFF`, with no timer, no `isLinedUpAndStopped` (< 5 kt) distinction and no re-check. `grep -rn "takeoffClearanceDelay\|TAKEOFF_CLEARANCE_DELAY" core/src/main app/src/main` returns nothing.

### ATC, phraseology, en-route

**No "flight complete" block-in line at the end of the flight**  
✅ Closed: `announceFlightComplete` posts a `SYSTEM` line — "United 598 parked at B44. Flight complete." — once per flight, unspoken.  
*Absent* · iOS: `IFATCCompanion/App/AppModel.swift:2893-2904 (announceArrival)`

- **iOS:** Once the aircraft is actually parked at the gate with the brake set, iOS posts a `.system` transmission into the transcript — "United 598 parked at B44. Flight complete." — exactly once per flight, and uses that same moment as the review-prompt trigger.
- **Android:** Nothing in the Android coordinator posts a completion transmission. `FlightSessionState.flightHasEnded` is a pure derivation (`atcState == ATCState.PARKED`, FlightSessionState.kt:169) and `arrivalAnnounced` in both the snapshot and the saved-flight policy is fed from it (FlightSessionCoordinator.kt:968, 1200) rather than from an announcement, so the transcript simply stops at the taxi-in clearance.

**Squawk is a fixed 4271 rather than derived from the flight number**  
✅ Closed — see `deterministicSquawk` above.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4191-4194 (deterministicSquawk) and 4158`

- **iOS:** `deterministicSquawk()` derives a per-flight code from the flight number — `String(format: "%04o", (abs(n) * 7 + 1) % 4096)` — so the IFR clearance reads a different, valid octal squawk for each flight and the read-back echoes it.
- **Android:** `buildContext` passes `squawk = DEFAULT_SQUAWK` (FlightSessionCoordinator.kt:1510), a companion constant fixed at "4271" (line 1613). Every clearance on every flight reads "squawk 4271", and `Phonetic.squawk` speaks the same four digits every time.

### Audio, billing, review

**An audio route change never re-forms the radio graph**  
*Ported, not wired* · iOS: `IFATCCompanion/Chatter/AmbientChatterService.swift:86-90 (the .AVAudioEngineConfigurationChange observer) and :393-400 (onConfigChange, which stops, restarts and re-ducks the engine)`

- **iOS:** When the audio route changes — headphones in or out, a Bluetooth headset connecting — the service bounces the engine so the graph re-forms against the new hardware format, then re-applies the duck state. The chatter keeps playing on the new device.
- **Android:** AmbientChatterService.onAudioRouteChanged() (core/.../chatter/AmbientChatterService.kt:330-335) is written and documented as being "for :app to call from the platform's own audio-focus callbacks", and nothing calls it. :app registers no AudioDeviceCallback and no ACTION_AUDIO_BECOMING_NOISY receiver. Its sibling hooks onInterruptionBegan/Ended are wired (AppGraph.kt:212, from RadioAudioEngine's focus listener); the route-change one is not. The only route-change handling that exists is RadioAudioEngine.kt:193-207, which treats the resulting negative AudioTrack.write() as fatal and calls stop() — tearing the bed down for good rather than bouncing and restarting it as iOS does.

**No way to cut a spoken transmission short**  
*Absent* · iOS: `IFATCCompanion/Views/ATCView.swift:51-55 (a toolbar stop.circle.fill button, shown while speech.isSpeaking, calling speech.stop())`

- **iOS:** While the app is speaking, the ATC screen's toolbar shows a stop button that immediately silences the current call and drops the queued ones — useful for a long ATIS or a clearance the pilot has already read.
- **Android:** AndroidSpeechService.stop() exists and drains the queue correctly, but no UI reaches it: FlightViewModel exposes no stop-speech action and AtcScreen renders no such control. The underlying isSpeaking StateFlow is published and collected by nothing, so the app cannot even show the indicator that gates the button on iOS.

**The Live Flight Update falls back to a different default callsign**  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:1324-1328 (notificationCallsign(): settings.callsign, else airline+flightNumber, else "IFATC")`

- **iOS:** With no callsign and no airline/flight number set, the Live Activity is labelled "IFATC".
- **Android:** LiveFlightUpdateProjection.kt:34-35 uses the same two-step fallback but ends at DEFAULT_CALLSIGN = "Flight" (:74). The string is on the notification twice — inside flightTitle ("IFATC Companion · Flight", :40) and as the subText when the route is empty (FlightNotifications.kt:96) — so a pilot who has not filled in a flight plan sees different wording from the iOS card.

**The pilot's own transmissions lose their distinguishing pitch offset**  
*Behaves differently* · iOS: `IFATCCompanion/Speech/SpeechService.swift:105-108 (utterance.pitchMultiplier = isPilot ? min(max(basePitch * 0.92, 0.5), 2.0) : basePitch)`

- **iOS:** Own-ship pilot calls are spoken 8% below the configured pitch, with the stated purpose of keeping the pilot audibly distinct from the controller even when the two share a system voice (the common case, since both fall back to defaultVoiceID).
- **Android:** AndroidSpeechService.applyVoice() (AndroidSpeechService.kt:511-527) applies engine.setPitch(configuration.speechPitch.coerceIn(0.5, 2.0)) with no pilot branch, and the QueuedCall's isPilot flag is used only to pick the voice id (:243) and to bracket the call with the mic-key thump and squelch tail (:424, :434). A pilot who has not set a separate pilot voice hears their own read-backs at exactly the controller's pitch.

### Found by the completeness pass

**Saying "wilco" produces the full read-back instead of a wilco — `PilotResponseEngine.wilco` is never called**  
✅ Closed: `wilco()` posts the acknowledgement, opens the read-back gate and tunes on a hand-off; a runway-crossing clearance still goes to `readBack`, since on Android the words are what authorizes the crossing.  
*Behaves differently* · iOS: `IFATCCompanion/App/AppModel.swift:4446-4449 (`func wilco()` → `postPilot(pilotEngine.wilco(context:facility:))`), dispatched from `perform(_:)` at AppModel.swift:4368 (`case .wilco: wilco()`)`

- **iOS:** The `.wilco` intent has its own action: `wilco()` posts `pilotEngine.wilco(context:facility:)` — the short "Wilco, <callsign>" acknowledgement — which is a different transmission from a read-back.
- **Android:** `FlightSessionCoordinator.handleSpokenPilotText` maps it to the read-back instead: `intent == PilotIntent.WILCO -> { readBack(); intent.title }` (core/session/FlightSessionCoordinator.kt:1460, with the comment "Wilco acknowledges the instruction it answers, which is a read-back"). `PilotResponseEngine.wilco` is ported and has no caller anywhere in `core/src/main` or `app/src/main`, so the phrase never goes on the air; the pilot who says "wilco" hears the app recite the whole clearance back instead.

### Airport surface, Connect, Mock Mode

**No WifiManager.MulticastLock is ever acquired, though the manifest permission exists solely for it**  
*Absent* · iOS: `IFATCCompanion/Connect/IFDiscoveryService.swift:100-155 (openSocket + the repeating sendPermissionPing that keeps inbound local traffic flowing on iOS)`

- **iOS:** iOS goes to some length to keep the UDP-broadcast discovery path alive: it binds a BSD socket on 15000 with SO_BROADCAST and re-sends a broadcast ping every two seconds specifically so the Local Network permission is granted and inbound datagrams are not dropped.
- **Android:** The Android equivalent enabler is a WifiManager.MulticastLock held for the lifetime of a discovery window; IFDiscoveryService.kt:36-41 states this as a requirement on the app layer ("without it several OEM Wi-Fi drivers filter inbound broadcast to 255.255.255.255 before it reaches the socket, which looks exactly like Infinite Flight not broadcasting at all") and AndroidManifest.xml:12-16 declares ACCESS_WIFI_STATE and CHANGE_WIFI_MULTICAST_STATE with a comment saying they exist to hold that lock. No code anywhere acquires one. The IFBonjourBrowsing seam is likewise left at its `unavailable` default (IFConnectManager.kt:54 default-constructs IFDiscoveryService(scope)), so of the three discovery paths only the TCP subnet sweep is live on Android. I am flagging this at low severity because the sweep is the documented workhorse on both platforms and iOS's own broadcast path is also frequently dead without the multicast entitlement — but the permission is shipped and the contract the code states for itself is unmet.

### Screens and settings

**Diagnostics cannot be shared or exported — only the surface subset can**  
*Ported, not wired* · iOS: `IFATCCompanion/Views/DiagnosticsView.swift:29-35 (`ShareLink(item: diagnostics.exportText())` in the toolbar)`

- **iOS:** A share button in the Diagnostics toolbar exports the whole diagnostics log.
- **Android:** `DiagnosticsScreen.kt` has an "Export surface diagnostics" button (:245-248) but no export of the log itself, and the shell's top bar shows no action on the Diagnostics tab (MainActivity.kt:185-194 only adds actions when `onAtc`). `DiagnosticsStore.exportText()` exists at core/.../diagnostics/DiagnosticsStore.kt:55 and has no caller.

**Flight screen's "Airport Proximity" drops the distance, and the ATC header's "Airport" goes blank instead of falling back**  
✅ Closed: `airportProximityText` renders "KIAH (12 NM)", and both it and the ATC header fall back to the filed departure before any telemetry, then to an em-dash.  
*Behaves differently* · iOS: `IFATCCompanion/Views/FlightView.swift:215-219 (`"\(near) (\(Int(d.rounded())) NM)"`); IFATCCompanion/Views/ATCView.swift:250-252 (`nearestAirport ?? (flightPlan.departure.isEmpty ? "—" : flightPlan.departure)`)`

- **iOS:** Flight shows "KIAH (12 NM)"; the ATC header's Airport stat falls back to the filed departure ICAO, then to an em-dash, so it is never empty.
- **Android:** `ScreenModels.kt:166` sets `airportProximity = session.aircraftState.nearestAirport ?: EM_DASH` — the distance in `nearestAirportDistanceNM` is dropped. `ScreenModels.kt:74` sets `nearestAirport = session.aircraftState.nearestAirport.orEmpty()`, so before any telemetry the ATC header's Airport field renders as an empty string rather than the departure ICAO or an em-dash.

**No way to stop a transmission mid-speech from the ATC screen**  
*Absent* · iOS: `IFATCCompanion/Views/ATCView.swift:51-55 (`if speech.isSpeaking { Button { speech.stop() } label: { Image(systemName: "stop.circle.fill") } }`)`

- **iOS:** While the synthesizer is speaking, a stop button appears in the ATC toolbar so a long ATIS or clearance can be cut short.
- **Android:** `MainActivity.kt:185-194` builds the ATC top-bar actions as exactly two buttons — Clear Flight and Flights — with no stop control, and nothing in the UI observes speech state. `AndroidSpeechService` exposes `isSpeaking` (AndroidSpeechService.kt:112) and `stop()` (:369); the only caller of `speech.stop()` is AppGraph.kt:176 (lifecycle teardown).

**Route map draws smooth PIREPs that iOS filters out**  
*Behaves differently* · iOS: `IFATCCompanion/Views/RouteMapView.swift:44-46 (`model.pireps.filter { ($0.coordinate?.isValid ?? false) && ($0.turbulence ?? .smooth) > .smooth }`)`

- **iOS:** Only PIREPs reporting more than smooth air are plotted, so every dot on the map means something.
- **Android:** `ScreenModels.kt:431` sets `pireps = weather.pireps.filter { it.coordinate?.isValid == true }` with no turbulence filter, and `RouteMapLayers.kt:220-223` draws each one (colouring a SMOOTH report green). The map therefore carries green "nothing here" dots that the iOS map never shows — under a legend whose first entry is "Light/chop".

**The ATC connection pill never says "Mock Mode"**  
✅ Closed: the pill reads "Mock Mode" on the demo feed, and `title` rather than `detailedTitle` otherwise — the long form with the failure reason is what Settings shows.  
*Behaves differently* · iOS: `IFATCCompanion/Views/ATCView.swift:183-185 (`connectionText = settings.mockMode ? "Mock Mode" : connect.connectionState.title`)`

- **iOS:** In Mock Mode the ATC header's connection pill reads "Mock Mode" (amber). Otherwise it shows the short connection title.
- **Android:** `ScreenModels.kt:77` sets `connectionText = session.connectionState.detailedTitle` unconditionally. Since Mock Mode is the default and nothing ever connects, the pill reads "Disconnected" with an amber dot for the whole of a mock flight. It also uses `detailedTitle` (the long form iOS reserves for Settings) rather than `title` in the space-constrained pill — `IFConnectTypes.kt:27-46` documents exactly that split.

**The human-ATC standby banner shows a generic hint instead of the staffing summary**  
✅ Closed: the banner shows `liveATC.summary` — which position is staffed and who is working it — falling back to the generic hint only when there is no summary.  
*Behaves differently* · iOS: `IFATCCompanion/Views/ATCView.swift:371-390 (a Card headed "Human ATC Active" with `model.liveATC.summary` beneath it)`

- **iOS:** When the companion steps aside for a live controller, the ATC screen shows a card titled "Human ATC Active" with the detected-staffing summary (which frequency, which controller) underneath.
- **Android:** `ScreenModels.kt:78` sets `standbyText = if (session.companionStandby) PilotActionPresentation.STANDBY_HINT else null`, and `STANDBY_HINT` is the string "Follow the live controller." (PilotActionPresentation.kt:89). `AtcScreen.kt:160-162` renders that single line through `StandbyBanner`. `session.liveATC.summary` exists and is used on the Diagnostics screen (ScreenModels.kt:315) but never here, so the pilot is never told *which* position is staffed.

---

## How to work through this

Sorted by severity, then by area, so a whole lane can be picked up at once — the ATC lane's
items share the same files, as do the screens.

Before starting any item, **verify it against both sources yourself.** These findings were
produced by agents and confirmed by other agents; three were hand-checked. The rest are
evidenced but not personally verified, and this repository has a history of confident
claims in both directions — a feature reported missing that had just shipped, and a feature
reported ported that did not exist.

