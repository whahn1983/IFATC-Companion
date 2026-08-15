import Foundation
import Combine
import CoreLocation
import CoreGraphics
import MapKit
import Network
#if canImport(UIKit)
import UIKit
#endif

/// A pilot response-button action. Used to surface only the buttons that make
/// sense for the controller the pilot is currently tuned to and the phase of
/// flight, instead of showing every button all the time.
enum PilotAction: CaseIterable {
    case clearance, pushback, engineStart, taxi, ready, takeoff
    case requestHigher, requestLower, vectors, approach, rideReport, destWx, checkIn
    case toGate
    /// Break off the approach and fly the missed-approach / go-around pattern (shown
    /// only while airborne, inbound to land on the Tower frequency).
    case goAround
    /// Accept the smoother cruise altitude the last ride report suggested (shown only
    /// while such a suggestion is active).
    case acceptSmootherAltitude
}

/// A pilot response-button action for the simulated weather-deviation flow. Kept
/// separate from `PilotAction` so the existing gate-to-gate button logic is
/// untouched; surfaced only while a route-weather conflict / deviation is active.
enum WeatherDeviationAction: CaseIterable {
    case askCenter
    case requestRightDeviation
    case requestLeftDeviation
    case requestVector
    case requestHigher
    case requestLower
    case clearOfWeather
    case continueOnCourse
    case sayAgain
}

/// Central coordinator. Owns all services, holds the published app state the UI
/// renders, and turns live/mock aircraft state into deterministic ATC dialogue.
@MainActor
final class AppModel: ObservableObject {

    // Services (injected into the SwiftUI environment by the app root).
    let settings: AppSettings
    let diagnostics: DiagnosticsStore
    let speech: SpeechService
    let connect: IFConnectManager
    let mock: MockSimulatorFeed
    let phraseologyProfiles: PhraseologyProfileStore
    let speechRecognizer: SpeechRecognitionService
    /// Ambient background radio chatter + the mic-key static effects. Also the app's
    /// background-audio anchor (keeps the flight updating while backgrounded).
    let chatter = AmbientChatterService()
    /// Drives the live Lock Screen / Dynamic Island flight notification (iOS 16.1+).
    let liveActivity = LiveActivityController()
    /// Owns the StoreKit subscription state. Live Connected Mode is available
    /// only while `entitlements.hasLiveAccess` is true; otherwise the app is
    /// locked to Mock Mode.
    let entitlements: EntitlementManager
    /// Decides when to ask for an App Store rating. Only ever prompts at the two
    /// calm moments the product allows — before the first ATC call of a session, or
    /// after a completed flight — never mid-flight, and self-limits the frequency on
    /// top of StoreKit's own three-per-year cap.
    let review = ReviewRequestManager()

    private let weatherService: AviationWeatherService
    /// Fetches real-world FAA D-ATIS (datis.clowd.io). Independent of the weather
    /// service; the whole ATIS feature degrades to "absent" whenever it returns nothing.
    private let atisService = ATISService()

    // Deterministic engines.
    private var engine: PhraseologyEngine
    private var stateMachine: ATCStateMachine
    private var pilotEngine: PilotResponseEngine
    private var rampEngine: RampPhraseologyEngine
    private var phaseDetector = PhaseDetector()
    private let taxiPlanner = TaxiRoutePlanner()
    private let intentParser = PilotIntentParser()
    private let lineupDetector = RunwayLineupDetector()
    private var routeAnalyzer = WeatherRouteAnalyzer()
    private var turbulenceModel = TurbulenceModel()
    private var rideEngine: RideReportEngine
    /// Precipitation overlay provider selector (NOAA → OPERA → NASA, or mock).
    private let precipService = PrecipitationOverlayService()
    /// Pure route-weather conflict detector.
    private var conflictDetector = RouteWeatherConflictDetector()
    /// Simulated weather-deviation flow coordinator (rebuilt with the engine).
    private var deviationEngine: WeatherDeviationEngine
    private let airports = AirportDatabase.shared
    private let runways = RunwayDatabase.shared

    /// OpenStreetMap airport-surface / taxi-routing / runway-crossing coordinator.
    /// Owns the surface data, the temporary taxi map, and the simulated Ground
    /// runway-crossing workflow. Observed directly by the taxi map view.
    let airportSurface = AirportSurfaceCoordinator()

    // Published UI state.
    @Published var aircraftState = AircraftState.empty
    @Published var flightPlan = FlightPlan.empty
    @Published var phase: FlightPhase = .preflight
    @Published var atcState: ATCState = .notConnected
    // Clearance Delivery is the first controller a flight calls, so the radio starts
    // tuned there (not Ground) at the gate. This is the frequency the radio is actually
    // tuned to — it changes only when the pilot tunes by hand or reads a hand-off back
    // (auto-tune), never the moment a controller issues a hand-off. The controller the
    // pilot is *dealing with* (which may be a not-yet-tuned hand-off target) is
    // `workingFacility`.
    @Published var currentFacility: ATCFacility = .clearance
    @Published var transcript: [ATCTransmission] = []
    @Published var latestTransmission: ATCTransmission?
    @Published var phaseDebug = PhaseDetector.Debug()
    @Published var assignedAltitude: Int = 0

    // Multiplayer / human-ATC staffing. When a human controller is detected the
    // companion stands by (stops generating controller calls).
    @Published var liveATC: LiveATCStatus = .none
    /// Mock-mode demo toggle to exercise the "step aside" behavior in the Simulator.
    @Published var simulateStaffedATC: Bool = false

    /// Whether the companion should defer to a human controller right now. The guard is
    /// per-frequency and location-aware: in live mode it applies only while the pilot's
    /// tuned COM frequency is a staffed human controller (read from Infinite Flight), so
    /// tuning to UNICOM, ATIS, or an unstaffed field lifts it and the companion resumes
    /// covering that sector. In mock mode it follows the demo toggle against the tuned
    /// facility so the standby behavior can be exercised in the Simulator.
    var companionStandby: Bool {
        if settings.mockMode {
            return simulateStaffedATC && currentFacility.isFAAATC
        }
        return liveATC.companionShouldStandBy
    }

    /// Whether the pre-departure ground actions (clearance → pushback → engine
    /// start → taxi → ready) should be offered. True until the first departure.
    var isPreDeparture: Bool { !hasDeparted }

    /// The runway in use for the current phase (departure or arrival end), resolved
    /// from the flight plan / approach / wind. Empty only when nothing is known yet.
    var activeRunwayDisplay: String { buildContext(for: atcState).runway }

    /// Whether the simulated arrival Ramp (taxi-to-gate) flow applies right now: the
    /// aircraft has departed and is back on the ground arriving, not yet parked.
    var isArrivalRamp: Bool {
        hasDeparted && atcState != .parked
            && ([.landing, .taxiIn, .parked].contains(phase)
                || [.runwayExit, .groundArrival].contains(atcState))
    }

    /// Whether the "Ramp" button should be live: pushback before departure, or the
    /// taxi-to-gate hand-off on arrival — but never once parked.
    var canContactRamp: Bool {
        guard atcState != .parked else { return false }
        return isPreDeparture || isArrivalRamp
    }

    // MARK: - Response-button visibility
    //
    // The response buttons are tied to the controller the pilot is currently working
    // (`workingFacility`) and gated by the phase of flight, so only the calls
    // that make sense right now are shown — e.g. Clearance at the gate, push/start
    // on Ramp, taxi on Ground, takeoff on Tower, and the enroute/arrival requests on
    // their respective controllers. This drives the ATC view's button grid.

    /// The controller the pilot is currently dealing with for responses and check-ins:
    /// the facility a hand-off has told them to contact (`pendingCheckInFacility`) if one
    /// is outstanding, otherwise the frequency they're tuned to (`currentFacility`). This
    /// is distinct from `currentFacility` on purpose — the radio does not tune to a new
    /// controller until the pilot reads the hand-off back (or tunes by hand), yet the
    /// check-in / request buttons must already point at the controller taking over.
    var workingFacility: ATCFacility { pendingCheckInFacility ?? currentFacility }

    /// The response-button actions to surface right now, keyed off the working facility
    /// and the flight phase.
    var availableActions: Set<PilotAction> {
        // Defer entirely to a human controller when one is staffing the position.
        if companionStandby { return [] }

        if isPreDeparture {
            switch workingFacility {
            case .clearance:
                // Offer the IFR clearance until it's issued. The push is NOT offered
                // here — the clearance ends by telling the pilot to contact Ramp (or
                // Ground) for the pushback, so the Pushback button appears only after
                // they tune that frequency, never under Clearance.
                let beforeClearance = stateMachine.current == .notConnected
                    || stateMachine.current == .connectedIdle
                return beforeClearance ? [.clearance] : []
            case .ramp:
                // Tuning to Ramp does not transmit, so the push must be requested
                // here. Before the push: offer Pushback. After it: engine start, then
                // a taxi request hands off to Ground (handled in requestTaxi).
                if [.notConnected, .connectedIdle, .clearance].contains(stateMachine.current) {
                    return [.pushback]
                }
                return [.engineStart, .taxi]
            case .ground:
                // On Ground only the taxi is requested. "Ready for departure" is a
                // Tower call (it addresses Tower while holding short), so it is offered
                // only once the pilot has tuned Tower — never on Ground. The exception
                // is a no-ramp airport, where the push happens on Ground: offer it
                // there (and only before it has been done).
                let prePush = [.notConnected, .connectedIdle, .clearance].contains(stateMachine.current)
                if prePush, buildContext(for: stateMachine.current).pushbackFacility == .ground {
                    return [.pushback, .taxi]
                }
                return [.taxi]
            case .tower:
                // After a "monitor Tower" hand-off no check-in is required, but offer it —
                // checking in gets a "number one for departure" acknowledgement — alongside
                // the report-ready ("line up and wait") and takeoff requests. Otherwise the
                // usual report-ready / takeoff pair.
                return monitoringTower ? [.ready, .checkIn, .takeoff] : [.ready, .takeoff]
            default:
                return [.clearance]
            }
        }

        // Flight finished at the gate — nothing left to request.
        if stateMachine.current == .parked { return [] }

        // Airborne / arrival — tie the requests to the controller currently working
        // the flight (the pending hand-off target, if any, else the tuned frequency).
        switch workingFacility {
        case .departure:
            return [.checkIn, .requestHigher, .requestLower]
        case .center:
            var actions: Set<PilotAction> = [.requestHigher, .requestLower, .rideReport, .destWx, .checkIn]
            // Surface the accept button only while a ride report's smoother-altitude
            // suggestion is active.
            if suggestedSmootherAltitude != nil { actions.insert(.acceptSmootherAltitude) }
            return actions
        case .approach:
            return [.checkIn, .vectors, .approach, .requestLower, .destWx]
        case .ramp:
            // Arrival Ramp: tuning in does not transmit, so the taxi-to-gate call is
            // made here with To Gate.
            return [.toGate]
        case .tower, .ground, .clearance:
            // Tower (landing) and Ground (taxi-in) progress with a check-in; no
            // enroute requests apply. Inbound to land on Tower, the pilot can also
            // break off the approach with Go Around.
            var actions: Set<PilotAction> = [.checkIn]
            if canGoAround { actions.insert(.goAround) }
            return actions
        }
    }

    /// Whether the "Go Around" button applies right now: airborne, inbound to land on
    /// the Tower frequency for the ILS/GPS/visual approach — either cleared the
    /// approach / contacting Tower (`.final`) or cleared to land (`.landing`). Hidden
    /// on the ground and throughout the departure.
    var canGoAround: Bool {
        guard workingFacility == .tower, hasDeparted,
              !(aircraftState.onGround ?? false) else { return false }
        return [.final, .landing].contains(stateMachine.current)
    }

    /// The frequency-tune buttons worth showing right now: the controller currently
    /// working the flight plus the next distinct controller ahead, so the pilot can
    /// tune ahead for the upcoming hand-off without every facility cluttering the
    /// page (e.g. Tower doesn't appear until the taxi is underway).
    var relevantFacilities: Set<ATCFacility> {
        // The frequency you're on now, plus where you're headed: a hand-off you've been
        // told to take but haven't tuned yet (so its button is there to tap), or — with
        // none pending — the next distinct controller ahead.
        var set: Set<ATCFacility> = [currentFacility]
        if let pending = pendingCheckInFacility {
            set.insert(pending)
        } else if let next = nextDistinctFacility(after: stateMachine.current) {
            set.insert(next)
        }
        return set
    }

    // Weather state.
    @Published var departureMETAR: METAR?
    @Published var destinationMETAR: METAR?
    @Published var alternateMETAR: METAR?
    @Published var destinationTAF: TAF?
    @Published var pireps: [PIREP] = []
    @Published var sigmets: [SIGMET] = []
    /// SIGMETs whose advisory area actually intersects the active route corridor —
    /// the subset that feeds the ride assessment and the Weather tab's advisory card.
    @Published var routeSigmets: [SIGMET] = []
    @Published var rideReportItems: [RideReportItem] = []
    @Published var rideAssessment: RideAssessment = .smooth
    @Published var weatherStatus: String = "Not loaded"
    /// A specific smoother cruise altitude surfaced by the last ride report, for the
    /// dedicated accept button (and the next higher/lower request) to target directly.
    /// Nil when no PIREP supports one. Published so the button can appear/label itself.
    @Published private(set) var suggestedSmootherAltitude: SmootherAltitude?

    /// The accept button's label for the active suggestion, e.g. "Climb FL390". Nil when
    /// there is no suggestion, so the button is hidden.
    var smootherAltitudeActionTitle: String? {
        guard let s = suggestedSmootherAltitude else { return nil }
        return "\(s.higher ? "Climb" : "Descend") \(engine.formatAltDisplay(s.altitudeFt))"
    }

    // Radar precipitation + simulated weather-deviation state.
    /// Descriptive state for the Weather View radar layer (coverage, opacity,
    /// source, last update, mock cells). The image/tiles themselves are fetched by
    /// `RadarOverlayRenderer` from the live provider.
    @Published var radarOverlay = RadarOverlayModel()
    /// Normalized weather hazards fed to the route-conflict detector.
    @Published var weatherHazards: [WeatherHazard] = []
    /// The most significant route-weather conflict currently detected (nil = none).
    @Published var activeWeatherConflict: RouteWeatherConflict?
    /// Faint "preview" reroute lines for the weather systems ahead along the route —
    /// every locked deviation *except* the one currently drawn solid (`weatherDeviationLine`).
    /// Display only (never drives ATC). Derived from `lockedDeviations`, so it is stable
    /// (locked in place) rather than recomputed every tick.
    @Published var weatherDeviationPreviews: [[CLLocationCoordinate2D]] = []
    /// Every recommended deviation across the **entire** flight plan, found in one pass
    /// from the origin to the destination and then **locked in place**. Each is a clean
    /// turn-out / parallel-hug / turn-back maneuver around one weather system on the route.
    /// Computed once when the flight's radar data first lands (and re-computed on a route
    /// change, on a pull-to-refresh, and automatically every ~5 min) — never every telemetry
    /// tick — so the mint lines stop shifting, flickering, or drawing crazy triangles. Faint
    /// until the aircraft is within draw range of each (`weatherDeviationLine` draws that one
    /// solid); they stay put until the next refresh.
    @Published var lockedDeviations: [RouteWeatherConflict] = []
    /// Diagnostics overlay: when on, the route map draws the **sampled radar cells** (the
    /// raster→cell clusters the live sampler derives from the radar image, and the sole
    /// input to the deviation math) as colored polygons, so you can confirm they land on the
    /// actual radar returns. Off by default and transient (resets each launch), like
    /// `simulateStaffedATC` — it's a verification aid, not a normal display layer.
    @Published var showSampledRadarCells = false
    /// Whether `lockedDeviations` has been computed for the current route+radar. Cleared on a
    /// route change, a pull-to-refresh, or the 5-min auto-refresh, so the set is recomputed
    /// once, then held. In live mode it is only set once the lock actually produces lines —
    /// an empty result (e.g. from a partial first radar frame after connect) stays unlocked and
    /// re-solves as fresher samples land, so the mint lines still appear on their own.
    private var deviationsLocked = false
    /// Identifies the route `lockedDeviations` was computed for; a change re-locks a fresh set.
    private var lockedRouteKey = ""
    /// The earliest point on the filed route the whole-route deviation walk may start from.
    /// Set when a drawn mint line's entry point falls behind the aircraft and the deviation is
    /// redrawn ahead of it (`redrawDeviationsAhead`): without it the next recompute would walk
    /// from the departure again and re-produce the very line drawn behind the aircraft. Only
    /// ever moves forward along the route; cleared on a route change.
    private var deviationWalkFloor: CLLocationCoordinate2D?
    /// The lateral excursion (NM) of the nearest deviation the last walk **discarded** for
    /// solving onto the flight path, or nil when none was. Diagnostics only: the discarded
    /// deviation is gone from `lockedDeviations` — it raises no banner, no advisory, and no
    /// response card — so without this the readout would fall back to a bare "on route —
    /// monitoring" and lose the one number that says a reroute was solved and thrown out.
    private var discardedOnRouteExcursionNM: Double?
    /// The radar sample (`lastPrecipSampleAt`) the current locked set was last solved against.
    /// While a live solve comes up empty the set is left unlocked (see
    /// `ensureLockedDeviationsComputed`) and re-solved only when a *fresher* sample lands —
    /// never every telemetry tick.
    private var lockedSampleStamp: Date?
    /// Quick-resample budget for the window right after a corridor change. The first live radar
    /// frame after connecting can come back partial (a cold fetch), so the deviation set locks
    /// empty and — on the normal 60 s staleness cadence — the mint lines wouldn't appear until a
    /// manual pull-to-refresh. While no lines have locked yet and this budget is unspent,
    /// `maybeResamplePrecipitation` retries on a short interval so a complete frame is picked up
    /// within seconds. Spent down per quick retry; refilled on each route change.
    private var emptyLockResampleRetries = 0
    /// How many quick resamples are allowed after a corridor change (see `emptyLockResampleRetries`).
    private let emptyLockResampleRetryBudget = 6
    /// The short staleness interval used for those quick retries, vs. the normal 60 s cadence.
    private let emptyLockResampleInterval: TimeInterval = 8
    /// The active simulated weather-deviation interaction (state + assignments).
    @Published var weatherDeviation = WeatherDeviationContext()
    /// A read-only snapshot for the Weather Diagnostics panel.
    @Published var weatherDiagnostics = WeatherProviderDiagnostics.empty
    /// True once a mock weather advisory has been auto-issued for the current
    /// conflict, so the demo advisory fires once rather than every telemetry tick.
    private var mockWeatherAdvisoryIssued = false
    /// True once the pilot has acted on the current conflict (asked / deviated /
    /// continued), so the "ask Center" banner doesn't re-appear while the same
    /// weather is still ahead. Reset when the conflict clears.
    private var weatherHandled = false
    /// When a route-weather conflict was last actually detected. Drives the confirm-
    /// clear hysteresis: once weather is shown, the mint line and "contact ATC"
    /// banner are held until the route has tested continuously clear for
    /// `weatherClearConfirmWindow`, so a noisy radar resample that momentarily loses
    /// a storm still ahead doesn't blink them out. Nil once clear is confirmed.
    private var lastConflictSeenAt: Date?
    /// How long the route must test continuously clear before a shown conflict is
    /// removed — long enough to span a radar resample cycle (~45 s) so a single
    /// empty sample never drops a storm that's really still there.
    private var weatherClearConfirmWindow: TimeInterval = 90
    /// How close (NM) to the flight-plan intercept at the end of the mint line the
    /// aircraft must get before the controller auto-resumes own navigation when the
    /// pilot hasn't reported clear of weather.
    private let autoResumeInterceptNM: Double = 15
    /// How far ahead (NM) the mint line's turn-out point must sit for a deviation request
    /// to be *held* — approved now, but the beginning turn deferred until the aircraft
    /// reaches the turn-out ("continue, expect the turn in X miles"). Within this the
    /// aircraft is essentially at the turn-out, so the turn is worked immediately.
    private let deviationTurnHoldNM: Double = 6
    /// How close (NM) the aircraft may come to a locked deviation's turn-out before the
    /// controller **auto-issues** the weather advisory, even if the pilot never tapped the
    /// "contact ATC" banner. Because the mint lines are locked and drawn ahead, a pilot who
    /// ignores the banner could otherwise fly straight past the first turn with no ATC call;
    /// this makes ATC initiate the advisory on its own once the turn is imminent.
    private let deviationAutoCallNM: Double = 15
    /// How far ahead (NM) of the aircraft a deviation is redrawn once its entry point — the
    /// turn-out at the start of the mint line — has fallen behind the aircraft. The locked
    /// lines are solved for the whole route and then held, so a pilot who ignores the banner
    /// (or a position jump) can leave the drawn reroute *behind* the aircraft, where it can no
    /// longer be flown. It is then re-solved from this far in front, giving room to work the
    /// new turn — and the controller advises the revised deviation.
    private let deviationRedrawAheadNM: Double = 20
    /// How far behind the aircraft (NM, along the filed route) a deviation's entry point must
    /// fall before it counts as passed — a small tolerance so a line whose turn-out sits
    /// essentially abeam the aircraft isn't redrawn on projection noise.
    private let deviationEntryPassedNM: Double = 1
    /// How far (NM, either side) the aircraft may drift off the committed mint line while
    /// flying it before the deviation is re-planned from where the aircraft actually is. Wind,
    /// a late roll-in, or a wide turn all push the aircraft off the drawn line; past this the
    /// line no longer describes the reroute being flown, so it is re-anchored to the aircraft
    /// and the controller re-vectors onto it. Comfortably wider than the ~1–2 NM a normal
    /// anticipated turn cuts across a vertex.
    private let deviationOffPathToleranceNM: Double = 5
    /// Minimum interval between off-path re-plans, so a single drift can't re-vector on
    /// consecutive ticks. A re-plan re-anchors the line to the aircraft (off-path distance
    /// back to zero), so this only ever guards against a pathological repeat.
    private let offPathReplanInterval: TimeInterval = 30
    /// How long after an automatically-issued turn the aircraft is left to roll out before its
    /// distance from the line counts as drift. A turn called at (or wide of) a vertex leaves the
    /// aircraft off the line by design while it comes around, so judging it immediately would
    /// re-vector one tick after the turn call.
    private let turnComplyWindow: TimeInterval = 60
    /// When the deviation was last re-planned for drifting off the committed line, and when the
    /// last automatic turn was issued. Both cleared with the deviation lifecycle.
    private var lastOffPathReplanAt: Date?
    private var lastAutoTurnIssuedAt: Date?
    /// The corrections that turn a mint-line leg (a **true** course, straight out of the
    /// great-circle geometry) into the heading assigned to the pilot: the local magnetic
    /// variation, and the wind the aircraft is actually flying in. Both are sampled from
    /// telemetry and held across ticks that don't carry a usable sample — a turn is
    /// exactly when the samples are least trustworthy and the correction most needed, so
    /// falling back to "no correction" mid-turn would defeat the point. See `HeadingSolver`.
    private var variationEstimate = HeadingSolver.VariationEstimate()
    /// The variation the magnetic conversion is using — nil until two readings have agreed.
    private var lastKnownVariationEast: Double? { variationEstimate.degreesEast }
    /// The wind the crab is actually computed for — the sim's own reading wherever it is
    /// exposed and trusted, the triangle's estimate otherwise.
    private var lastKnownWind: HeadingSolver.Wind?
    /// The wind triangle's own running estimate, kept apart from `lastKnownWind` so it stays
    /// an independent second opinion: it is never contaminated by a sim-reported sample, which
    /// is what lets it cross-check one (and lets Weather Diagnostics print the two side by side
    /// and mean it).
    private var lastSolvedWind: HeadingSolver.Wind?
    /// The last weather vector's leg course (**true**, straight off the drawn line) and the
    /// magnetic heading actually spoken for it. Kept only so Weather Diagnostics can show what
    /// the wind/variation correction did to a vector — neither the solved wind nor the
    /// variation was visible anywhere, so a heading that came out pointing the wrong way could
    /// only be argued about. Never read by the flow.
    private var lastAssignedTrueCourse: Double?
    private var lastAssignedVectorHeading: Int?
    /// How the last initial departure heading was arrived at — the runway it will be
    /// measured against, the origin it was taken from, the fix it targeted, and the
    /// true→magnetic step. Shown in Diagnostics. Never read by the flow.
    private var lastDepartureHeadingSummary: String?
    /// Whether `lastKnownWind` currently comes from the sim's own `environment/wind_*` states
    /// rather than the inferred wind triangle. Reported in Weather Diagnostics so it is never
    /// a guess which of the two a given vector was crabbed for.
    private var windSourceIsSimReported = false
    /// The steepest turn back onto the flight plan a drawn deviation may end with. Past this the
    /// rejoin is moved to the next fix down the route (`deviationWithGentleRejoin`), lengthening
    /// the closing leg so the return to course — and the turn the controller calls for it — is
    /// gradual instead of a hard corner onto the line.
    private let maxRejoinTurnDegrees: Double = 60
    /// How much clear air (NM along the route) a softened rejoin leaves before the next
    /// deviation's turn-out, so reaching farther down for a gentler intercept can never run into
    /// the following reroute.
    private let mergeAdjacentRejoinMarginNM: Double = 5
    /// The greatest turn (degrees off the aircraft's current track) an automatically-issued
    /// weather vector may command, and the bound on what counts as *ahead* of the aircraft when
    /// a deviation is re-planned. The detector already clamps every drawn leg to
    /// `maxDeviationTurnDegrees` (100°) off course — ATC vectors around a storm, it never turns
    /// an aircraft the long way around — so a vector past this bound is not a tight turn but a
    /// line pointing **behind** the aircraft, and is suppressed rather than flown.
    private let maxWeatherVectorTurnDegrees: Double = 135
    /// How far (NM) before a weather system's near edge each locked deviation is solved
    /// from. A reroute is a straight-corridor offset aimed at the storm; solved from far
    /// away (the origin, hundreds of NM back across the route's bends) it renders as a
    /// runaway line drawn past the weather. Solving it from a modest lead just before the
    /// storm — where the route is locally straight — keeps the geometry tight to the storm,
    /// the same reason the old draw gate held the line until the aircraft was within ~75 NM.
    private let deviationSolveLeadNM: Double = 70
    /// The most weather systems to draw faint preview mint lines for down the route, so
    /// the strategic preview can never run away (one detection per system).
    private let maxWeatherPreviewSystems = 6
    /// How far (NM) the strategic preview jumps down the route when a detection window
    /// turns up no system, so it scans the whole route past clear gaps rather than
    /// stopping at the first one. A little under the detector's lookahead so windows
    /// overlap and a system straddling a boundary isn't skipped.
    private let previewScanStepNM: Double = 150
    /// The minimum distance (NM) before the airport that every weather-deviation mint line
    /// must rejoin the filed route. A deviation around weather sitting on or near the field
    /// then terminates on the flight path well short of the airport, rather than drawing a
    /// line that ends right on top of it. Enforced through the rejoin cap
    /// (`weatherRejoinCap`), so it bounds every drawn line — the live reroute, the locked
    /// previews, and the re-vector while deviating.
    private let weatherRejoinAirportMarginNM: Double = 20
    /// Timestamp of the last aviation-weather refresh, for the diagnostics panel.
    private var lastAviationWeatherUpdate: Date?

    // MARK: - ATIS state
    //
    // Real-world FAA D-ATIS for the origin (while pre-departure at the gate) and the
    // destination (within 100 NM on arrival). Everything is best-effort: when the feed
    // returns nothing for a field, the report stays nil and the whole feature vanishes
    // for that field — no button, and no information code appended to any call.

    /// Latest D-ATIS report for the departure field (nil = none published / not fetched).
    @Published var departureATIS: AirportATIS?
    /// Latest D-ATIS report for the destination field (fetched within 100 NM).
    @Published var arrivalATIS: AirportATIS?
    /// Read-only ATIS status for the Diagnostics tab.
    @Published var atisDiagnostics = ATISDiagnostics()

    /// The information code letter the pilot has actually received by **tuning** ATIS
    /// for the departure / arrival. This is what gets appended ("…information Alpha") to
    /// the taxi request and the approach check-in. Nil until the pilot tunes ATIS for
    /// that phase, so a call never claims information the pilot never received.
    private var reportedDepartureInfo: String?
    private var reportedArrivalInfo: String?
    /// One-shot guards so the information code is reported to ATC only on the *initial*
    /// contact — the taxi request, and the first Approach check-in — not on every
    /// re-tap of the same button.
    private var departureInfoAppended = false
    private var arrivalInfoAppended = false
    /// Throttle for the opportunistic arrival-ATIS fetch driven from the telemetry loop.
    private var lastArrivalATISAttempt: Date?

    /// Whether the pilot has already copied (and moved on from) the ATIS during the
    /// departure / arrival phase — set once they tune a controller / ramp frequency. The
    /// ATIS button then hides for that phase: you don't keep re-listening after you've
    /// copied the information. Tracked per phase so the arrival ATIS button still reappears
    /// within 100 NM of the destination even though the departure ATIS button was dismissed.
    private var departureATISDismissed = false
    private var arrivalATISDismissed = false

    private var lastATCTransmission: ATCTransmission?
    private var cancellables = Set<AnyCancellable>()
    private var started = false

    /// Whether the pilot has begun communicating with ATC this flight — the first controller
    /// or pilot call has been posted. The ambient background chatter is held silent until then
    /// (you don't hear other traffic on frequency before you've checked in) and stops again when
    /// the flight ends or is reset. Not persisted directly — re-derived from the transcript on a
    /// reconnect so a resumed flight keeps the chatter going.
    private var atcCommunicationStarted = false

    /// Persists the in-progress ATC session so a disconnect/reconnect (or app
    /// relaunch) resumes where the flight left off instead of re-deriving the
    /// conversation from raw telemetry — which would jump a parked aircraft to cruise.
    private let sessionStore = SessionStateStore()
    /// Signature of the last persisted state, so we only write when something
    /// meaningful changed (or the heartbeat below is due).
    private var lastPersistSignature: String?
    /// When the snapshot was last written, used to re-stamp it periodically while
    /// connected so a long, quiet cruise doesn't age the snapshot out of restore.
    private var lastPersistedAt: Date?
    /// Re-save at least this often while the session is active, even if nothing
    /// changed, so `savedAt` stays fresh through long level phases.
    private let persistHeartbeat: TimeInterval = 120

    /// Set when a restored session was mid-taxi (the app was swiped away / relaunched
    /// during the ground taxi). The taxi map is re-established on the first real telemetry
    /// fix — deferred so the route starts from the aircraft's actual position rather than
    /// the airport reference (which is all that's known before telemetry resumes).
    /// `.none` when there is nothing to restore.
    private var pendingTaxiMapRestore: TaxiKind = .none

    /// Once airborne, automatic telemetry-driven controller calls run. Before the
    /// first departure the pre-departure ground flow (clearance → pushback →
    /// engine start → taxi → ready) is always pilot-driven via the response
    /// buttons; the only controller call issued automatically on the ground is the
    /// takeoff clearance, once the aircraft is lined up on the runway.
    private var hasDeparted = false

    /// True once the app has auto-started its first feed at launch. The "before the
    /// first call" rating prompt is suppressed for that initial cold-launch start —
    /// Apple: never ask right when the app opens — and is only considered when the
    /// pilot starts a *subsequent* fresh session (reconnect, mode switch, next flight).
    private var didAutostartInitialFeed = false

    /// Guards the one-time arrival courtesy call at the gate.
    private var arrivalAnnounced = false

    /// True once Ground has automatically handed a departing aircraft to Tower to
    /// *monitor* ("monitor Tower on …"), as it approaches the departure runway. After
    /// this no check-in is required — the takeoff clearance still fires automatically
    /// once the aircraft is lined up — but if the pilot does check in, Tower replies
    /// "number one for departure". Reset for each new flight; persisted across a
    /// reconnect so the hand-off isn't re-issued mid-taxi.
    private var monitoringTower = false

    /// True between contacting arrival Ramp (taxi-to-gate cleared) and the aircraft
    /// actually stopping at the gate with the parking brake set. While true, the
    /// telemetry loop watches for the gate stop and only then announces the block-in
    /// / "flight complete" — so the arrival never claims "parked" mid-taxi.
    private var awaitingGateArrival = false

    /// The gate coordinate captured from the taxi map when the arrival Ramp call was made.
    /// The block-in only fires once the aircraft is parked within `gateArrivalRadiusMeters`
    /// of it, so a parking brake set out on a taxiway does not end the flight. Nil when the
    /// taxi map couldn't resolve a gate, in which case a plain full stop completes.
    private var arrivalGatePosition: CLLocationCoordinate2D?

    /// True once the pilot starts changing controllers with the frequency-tune
    /// buttons. While set, the airborne conversation advances only when the pilot
    /// tunes a frequency — automatic, telemetry-driven controller calls are
    /// suppressed so messages never play one after another without waiting.
    @Published private(set) var manualTuning = false

    /// The facility the pilot has tuned to but not yet checked in with. Switching
    /// frequency no longer auto-checks-in, so this keeps the header and the active
    /// button highlight on the tuned controller until the next check-in or request
    /// advances the conversation. Cleared once the state machine advances.
    private var tunedFacility: ATCFacility?

    /// While the pilot is tuning frequencies by hand in *live* mode, the controller's
    /// position-based calls and facility hand-offs still fire automatically from
    /// telemetry — but once a hand-off ("contact Approach on …") is issued, the new
    /// controller's instruction waits until the pilot tunes that frequency and checks
    /// in. This holds the facility the pilot has been handed to but not yet checked in
    /// with, so the automatic flow pauses there instead of talking for a controller
    /// the pilot hasn't switched to. Nil when no hand-off is pending.
    private var pendingCheckInFacility: ATCFacility?

    /// True from the moment the pilot taps Go Around until they re-establish with
    /// Approach on the missed-approach leg. While set, the automatic flow holds (the
    /// conversation is deliberately being flown back around the pattern) and the
    /// Approach check-in issues the "maintain <alt>, continue inbound" call instead of
    /// a plain radar-contact re-check-in. Cleared in `resumeApproachAfterGoAround`.
    private var goAroundInProgress = false

    /// Live-mode countdown that issues the takeoff clearance a few seconds after the
    /// aircraft is detected lined up and stopped on the departure runway, so Tower
    /// does not clear the takeoff the instant the nose swings onto the centerline.
    var takeoffClearanceDelay: TimeInterval = 5
    private var takeoffClearanceTimer: Task<Void, Never>?

    /// Aircraft altitude (ft MSL) captured when the takeoff clearance is issued, so
    /// the Tower→Departure hand-off can be held until the aircraft has actually
    /// climbed out (~2,000 ft above the field) even when Infinite Flight does not
    /// expose AGL. Without this the departure call could fire the instant after the
    /// takeoff clearance — "one right after the other" on the runway.
    private var liftoffAltitudeMSL: Double = 0

    /// Departure field elevation (ft MSL), estimated from on-ground telemetry
    /// (ground ≈ MSL − AGL) and kept fresh until the aircraft departs. Lets the
    /// initial-climb altitude be expressed above the field at high-elevation
    /// airports without an onboard airport database. 0 until first captured.
    private var departureFieldElevationMSL: Double = 0

    /// True once the arrival "monitor ramp to the gate" call has been issued, so the
    /// staged arrival (proceed-to-gate → monitor → flight complete) never collapses
    /// into a single burst when the aircraft is already stopped at the gate.
    private var gateMonitored = false

    // MARK: - Readback gate
    //
    // Real controllers wait for the pilot to read an instruction back before
    // issuing the next one. The automatic (telemetry-driven) flow mirrors that:
    // after a controller call that expects a readback, the conversation holds —
    // no further automatic call is issued — until the pilot reads back (any pilot
    // transmission clears the gate). If the pilot is idle, the controller repeats
    // the call and asks "how do you read?" every `readbackRepeatInterval` seconds.
    // This is what stops calls from firing back-to-back near the runway.

    /// True while waiting for the pilot to acknowledge the last automatic call.
    @Published private(set) var awaitingReadback = false
    /// The controller call to repeat if the pilot stays idle.
    private var pendingReadbackTx: ATCTransmission?
    /// Drives the idle re-prompt while `awaitingReadback`.
    private var readbackTimer: Task<Void, Never>?
    /// How many times the idle re-prompt has fired for the current call.
    private var readbackPrompts = 0

    /// Seconds of pilot silence before the controller repeats the call.
    var readbackRepeatInterval: TimeInterval = 30
    /// How many times to repeat before giving up (the gate stays closed so the
    /// flow does not run away; the pilot can still act via the buttons/PTT).
    var readbackMaxPrompts = 3

    init() {
        let settings = AppSettings()
        self.settings = settings
        let diagnostics = DiagnosticsStore()
        self.diagnostics = diagnostics
        self.speech = SpeechService()
        self.connect = IFConnectManager()
        self.mock = MockSimulatorFeed()
        let profiles = PhraseologyProfileStore()
        self.phraseologyProfiles = profiles
        self.speechRecognizer = SpeechRecognitionService()
        self.entitlements = EntitlementManager()
        self.weatherService = AviationWeatherService(baseURL: settings.weatherBaseURL)

        var engine = PhraseologyEngine(digitStyle: settings.digitStyle, mode: settings.phraseologyMode)
        engine.profile = profiles.activeProfile
        self.engine = engine
        self.stateMachine = ATCStateMachine(engine: engine)
        self.pilotEngine = PilotResponseEngine(engine: engine)
        self.rampEngine = RampPhraseologyEngine(engine: engine)
        self.rideEngine = RideReportEngine(engine: engine)
        self.deviationEngine = WeatherDeviationEngine(phraseology: WeatherDeviationPhraseology(engine: engine))
    }

    /// Apply the current settings + active profile to the engine and rebuild the
    /// engine-dependent objects.
    private func applyEngineConfig() {
        engine.digitStyle = settings.digitStyle
        engine.mode = settings.phraseologyMode
        engine.profile = phraseologyProfiles.activeProfile
        stateMachine = ATCStateMachine(engine: engine)
        pilotEngine = PilotResponseEngine(engine: engine)
        rampEngine = RampPhraseologyEngine(engine: engine)
        rideEngine = RideReportEngine(engine: engine)
        deviationEngine = WeatherDeviationEngine(phraseology: WeatherDeviationPhraseology(engine: engine))
        airportSurface.updateEngine(engine)
    }

    // MARK: - Airport surface (OpenStreetMap taxi mapping)

    /// Wire the airport-surface coordinator into the transcript/speech pipeline and
    /// react to facility / ATC-state changes so the taxi map appears and disappears at
    /// the right lifecycle points.
    private func configureAirportSurface() {
        airportSurface.configure(diagnostics: diagnostics, engine: engine,
                                 emit: { [weak self] tx in self?.post(tx, speak: true) },
                                 callsign: { [weak self] in self?.currentCallsign()
                                     ?? PhraseologyEngine.Callsign(display: "Aircraft", spoken: "aircraft") })
        // The departure taxi map is deliberately NOT hidden when the radio tunes to Tower.
        // It stays up through the Ground→Tower "monitor" hand-off and the final roll to the
        // runway so (a) the pilot can still see the route to the hold-short, and (b) the
        // surface keeps tracking the aircraft — the automatic "line up and wait" cue
        // (`approachingRunwayLineup`, latched ~270 m out) depends on that continued tracking,
        // which `hideTaxiMap` would stop (it clears the route and resets the cue). The map is
        // retired instead when the pilot reads back the line-up-and-wait or the takeoff
        // clearance (`retireDepartureTaxiMapAfterReadback`), or as a backstop once airborne.
        // Pre-load the arrival surface as the aircraft rolls out; hide the map once parked.
        // The taxi-in clearance itself (and the map reveal) is driven by
        // `issueArrivalTaxiClearance` when Ground gives the taxi-to-gate.
        $atcState
            .removeDuplicates()
            .sink { [weak self] state in
                guard let self else { return }
                switch state {
                case .runwayExit:
                    // Warm the destination surface while the aircraft is still rolling out,
                    // so the taxi-in clearance can route to the gate rather than fall back to
                    // a generic "taxi to parking" (the surface is otherwise loaded only at the
                    // taxi-in itself — too late to route the very first clearance). Live +
                    // entered gate only: mock builds the surface synchronously, and with no
                    // gate there is nothing to route to. The map is not revealed here; only
                    // the surface is loaded.
                    if !self.settings.mockMode, self.hasArrivalGate {
                        self.prepareArrivalTaxi()
                    }
                case .parked:
                    self.airportSurface.hideTaxiMap()
                    // Flight complete at the gate — end the ambient background chatter.
                    self.updateChatterRunState()
                default:
                    break
                }
            }
            .store(in: &cancellables)
    }

    /// The callsign as the phraseology engine renders it right now.
    private func currentCallsign() -> PhraseologyEngine.Callsign {
        engine.callsign(airline: flightPlan.airline, flightNumber: flightPlan.flightNumber,
                        fallback: flightPlan.callsign)
    }

    /// Pre-cache the departure and arrival airport surfaces at flight load (live mode
    /// only) so the taxi clearance and taxi map are ready without a network wait at taxi
    /// time. Mock airports build synthetic surfaces synchronously, so there's nothing to
    /// pre-cache. Safe to call repeatedly — the coordinator/provider dedupe by ICAO and
    /// never disturb an active taxi.
    private func prefetchAirportSurfaces() {
        guard !flightPlan.departure.isEmpty || !flightPlan.destination.isEmpty else { return }
        if settings.mockMode {
            // Pre-cache the whole origin and destination airports so the mock demo taxis the
            // real fields (with a synthetic fallback when offline / no OSM data).
            airportSurface.prepareSimulatedSurfaces([
                (flightPlan.departure, resolvedDepartureCoordinate()),
                (flightPlan.destination, resolvedDestinationCoordinate())
            ])
            return
        }
        airportSurface.prefetchFlightSurfaces(
            departure: flightPlan.departure,
            departureReference: resolvedDepartureCoordinate(),
            arrival: flightPlan.destination,
            arrivalReference: resolvedDestinationCoordinate())
    }

    /// Begin the OSM departure taxi (loads/normalizes/routes; async when uncached).
    private func prepareDepartureTaxi() {
        let icao = flightPlan.departure
        guard !icao.isEmpty else { return }
        let ref = resolvedDepartureCoordinate() ?? aircraftState.coordinate
        guard let ref, ref.isValid else { return }
        let ctx = buildContext(for: .groundTaxi)
        airportSurface.beginDeparture(icao: icao, reference: ref,
                                      aircraftName: aircraftState.aircraftName,
                                      runway: ctx.runway, gate: flightPlan.departureGate,
                                      startCoordinate: aircraftState.coordinate ?? ref,
                                      mock: settings.mockMode)
    }

    /// Begin the OSM arrival taxi-to-gate (Ground issues taxi-in after landing).
    private func prepareArrivalTaxi() {
        let icao = flightPlan.destination
        guard !icao.isEmpty else { return }
        let ref = resolvedDestinationCoordinate() ?? aircraftState.coordinate
        guard let ref, ref.isValid else { return }
        // The arrival runway lets the simulated demo start the rollout at the runway exit on
        // a real surface. Derived the same way the taxi-in clearance resolves it.
        let arrivalRunway = buildContext(for: .groundArrival, arrivalOverride: true).runway
        airportSurface.beginArrival(icao: icao, reference: ref,
                                    aircraftName: aircraftState.aircraftName,
                                    gate: flightPlan.arrivalGate,
                                    startCoordinate: aircraftState.coordinate ?? ref,
                                    mock: settings.mockMode,
                                    arrivalRunway: arrivalRunway)
    }

    /// Whether the pilot filed an arrival gate to taxi to. Drives the arrival taxi map and
    /// the gate-routed taxi-in clearance; with none there is nothing to route to, so Ground
    /// gives a plain "taxi to parking" and no map is shown.
    private var hasArrivalGate: Bool {
        !flightPlan.arrivalGate.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// When the aircraft first reached the arrival taxi-in and Ground's clearance is being
    /// held for the destination surface to load, so the wait can be bounded.
    private var arrivalRouteWaitStartedAt: Date?

    /// Parameters for an arrival taxi clearance Ground withheld while the destination surface
    /// was still loading. Issued once the load resolves (`issueDeferredArrivalTaxiClearanceIfLoaded`).
    private struct PendingArrivalTaxi { let announceHandoff: Bool; let automatic: Bool }
    private var pendingArrivalTaxiClearance: PendingArrivalTaxi?
    /// Safety backstop (seconds) on how long Ground withholds the automatic arrival taxi-in
    /// clearance waiting for the destination surface to load. The wait normally ends the
    /// moment the load resolves — Ground then routes to the gate, or falls back to a generic
    /// clearance only if the loaded data can't be routed. This cap only covers a load that
    /// never resolves at all (it sits above the ~35 s surface-fetch timeout), so Ground is
    /// never left silent indefinitely.
    private let arrivalRouteWaitTimeout: TimeInterval = 40

    /// Whether to keep withholding the automatic arrival taxi-in clearance while the
    /// destination surface is still loading — a controller who simply hasn't gotten back to
    /// you yet. Returns false (proceed and issue the clearance) when there is no gate to
    /// route to, in Mock Mode, once a routed clearance is available, once the surface load
    /// has fully resolved (so Ground can route to the gate, or fall back to a generic call
    /// only if the data can't be routed), or if the safety backstop elapses. This gates the
    /// automatic (telemetry-driven) path; the pilot check-in path withholds the same way and
    /// is issued by the telemetry-pumped `issueDeferredArrivalTaxiClearanceIfLoaded`.
    private func shouldWaitForArrivalRoute() -> Bool {
        guard hasArrivalGate, !settings.mockMode else { arrivalRouteWaitStartedAt = nil; return false }
        // Make sure the destination surface is loading (normally started at the runway exit).
        if airportSurface.kind != .arrival { prepareArrivalTaxi() }
        // A routed clearance is ready — stop waiting and issue it.
        if airportSurface.taxiClearance(callsign: buildContext(for: .groundArrival).callsign) != nil {
            arrivalRouteWaitStartedAt = nil
            return false
        }
        // Still fetching/normalizing the surface — keep withholding the clearance until the
        // data has fully loaded, up to the safety backstop. Ground only falls back to a
        // generic clearance once the load has resolved and still can't be routed.
        let now = Date()
        if airportSurface.surfaceLoadInProgress {
            let startedAt = arrivalRouteWaitStartedAt ?? now
            arrivalRouteWaitStartedAt = startedAt
            if now.timeIntervalSince(startedAt) < arrivalRouteWaitTimeout { return true }
        }
        arrivalRouteWaitStartedAt = nil
        return false
    }

    /// Issue Ground's arrival taxi-in clearance, routing to the entered gate once the
    /// destination surface has loaded. The surface is pre-loaded when the aircraft exits the
    /// runway (`prepareArrivalTaxi` off `.runwayExit`). Ground withholds the clearance while
    /// the surface is still loading — on both the automatic and the pilot check-in path, a
    /// controller who simply hasn't gotten back to you yet — and replies once the load
    /// resolves: routed to the gate when the data supports it, or a generic clearance only if
    /// the fully-loaded data can't be routed (`issueDeferredArrivalTaxiClearanceIfLoaded`,
    /// pumped from the telemetry loop, issues it once the surface resolves). With no entered
    /// gate there is nothing to route to, so a plain "taxi to parking" stands alone at once
    /// (no map, no waiting).
    private func issueArrivalTaxiClearance(announceHandoff: Bool, automatic: Bool) {
        let c = buildContext(for: .groundArrival)
        guard settings.mockMode || hasArrivalGate else {
            // No gate to route to: keep the plain generic clearance, no taxi map, no waiting.
            pendingArrivalTaxiClearance = nil
            advanceAndPost(to: .groundArrival, context: c,
                           announceHandoff: announceHandoff, automatic: automatic)
            holdGroundArrivalForReadbackIfPilotDriven(automatic: automatic)
            return
        }
        // Make sure the destination surface load is under way (normally started at the runway
        // exit). Guarded so a repeated call — the automatic path re-checks each tick — never
        // restarts an in-flight or already-failed fetch.
        if airportSurface.kind != .arrival { prepareArrivalTaxi() }
        // Anchor the route at the aircraft's position at the moment taxi is requested. The
        // surface (and its route) is warmed earlier, at the runway exit, before the aircraft
        // taxied clear — so re-anchor to where it actually is now, otherwise the drawn route
        // starts back at the runway exit rather than under the aircraft.
        if !settings.mockMode, let here = aircraftState.coordinate {
            airportSurface.updateTaxiStart(coordinate: here)
        }
        // Pilot check-in path: withhold the clearance while the surface is still loading; it
        // is issued once the load resolves (via the telemetry-pumped deferred issuer). The
        // pilot's check-in has already been posted, so Ground simply replies when ready. The
        // automatic (telemetry) path does its waiting in `shouldWaitForArrivalRoute` and only
        // reaches here once it has decided to issue, so it never defers here.
        if !automatic, !settings.mockMode, airportSurface.surfaceLoadInProgress {
            pendingArrivalTaxiClearance = PendingArrivalTaxi(announceHandoff: announceHandoff,
                                                            automatic: automatic)
            return
        }
        postArrivalTaxiClearance(announceHandoff: announceHandoff, automatic: automatic)
    }

    /// Post Ground's arrival taxi clearance now — the detailed OSM route when the destination
    /// surface routed to the gate, otherwise a generic clearance. Reveals the map on the
    /// pilot's read-back. A check-in-driven (non-automatic) call doesn't auto-close the
    /// read-back gate, so it holds `.groundArrival` open until the pilot reads it back.
    private func postArrivalTaxiClearance(announceHandoff: Bool, automatic: Bool) {
        pendingArrivalTaxiClearance = nil
        let c = buildContext(for: .groundArrival)
        let osm = hasArrivalGate ? airportSurface.taxiClearance(callsign: c.callsign) : nil
        advanceAndPost(to: .groundArrival, context: c, announceHandoff: announceHandoff,
                       automatic: automatic, overrideTransmission: osm)
        // Reveal the map on read-back; if the loaded data couldn't be routed yet, the detailed
        // route still supersedes the generic call once it becomes computable (see the
        // coordinator's live re-route).
        airportSurface.taxiClearanceIssued(supersedeWhenRouteReady: hasArrivalGate && osm == nil)
        holdGroundArrivalForReadbackIfPilotDriven(automatic: automatic)
    }

    /// A check-in-driven (non-automatic) arrival taxi call doesn't auto-close the read-back
    /// gate, so hold `.groundArrival` open until the pilot reads it back. Without this a
    /// "parked" telemetry reading on the very next tick — a short taxi-in that ends right at
    /// the gate — would race the flow straight to "flight complete", eating the taxi
    /// read-back and closing the arrival Ramp (taxi-to-gate) window before the pilot could
    /// use it. Automatic calls already close the gate in `advanceAndPost`.
    private func holdGroundArrivalForReadbackIfPilotDriven(automatic: Bool) {
        guard !automatic, stateMachine.current == .groundArrival,
              let taxiCall = lastATCTransmission else { return }
        engageReadbackGate(taxiCall)
    }

    /// Issue an arrival taxi clearance Ground withheld while the destination surface was still
    /// loading, once the load has resolved (ready or unavailable). No-op while nothing is
    /// pending or the load is still in progress. Pumped from the telemetry loop, so it reaches
    /// the pilot within a tick of the surface resolving, in every tuning mode.
    private func issueDeferredArrivalTaxiClearanceIfLoaded() {
        guard let pending = pendingArrivalTaxiClearance,
              !airportSurface.surfaceLoadInProgress else { return }
        postArrivalTaxiClearance(announceHandoff: pending.announceHandoff, automatic: pending.automatic)
    }

    // MARK: - Lifecycle

    func onAppear() {
        guard !started else { return }
        started = true

        startNetworkMonitor()
        diagnostics.verbose = settings.debugLogging
        applyKeepScreenAwake()
        speech.configure(settings: settings)
        configureLiveActivity()
        configureChatter()
        connect.configure(diagnostics: diagnostics)
        Task { await weatherService.configure(baseURL: settings.weatherBaseURL, diagnostics: diagnostics) }
        Task { await atisService.configure(diagnostics: diagnostics) }
        precipService.configure(diagnostics: diagnostics)
        // An async OPERA/ORD overlay render finished — nudge the map to re-request the
        // now-cached image (touch the published overlay model without recomputing).
        precipService.onOverlayUpdated = { [weak self] in self?.radarOverlay.lastUpdated = Date() }
        applyRadarProvider()

        // Route state from whichever feed is active.
        mock.onState = { [weak self] state in self?.handle(state: state) }
        connect.onState = { [weak self] state in self?.handle(state: state) }

        // Read the flight plan from Infinite Flight when it changes.
        connect.onFlightPlan = { [weak self] plan in self?.mergeLiveFlightPlan(plan) }

        // Route recognized push-to-talk speech to the deterministic intent handler.
        speechRecognizer.onResult = { [weak self] text in self?.handleSpokenInput(text) }

        observeSettings()
        observeEntitlements()
        configureAirportSurface()
        syncFlightPlanFromSettings()

        // Without an active subscription the app is locked to Mock Mode. Settle
        // the mode before starting a feed so a lapsed subscriber never resumes in
        // Live Connected Mode. The async entitlement refresh below re-checks once
        // StoreKit responds and re-locks if needed.
        if !entitlements.hasLiveAccess { settings.mockMode = true }

        diagnostics.log(.app, "IFATC Companion ready. Mock mode: \(settings.mockMode).")

        if settings.mockMode {
            startMock()
        } else {
            startLive()
        }
        // From here on, a fresh session start is pilot-initiated (reconnect, mode
        // switch, next flight) rather than the cold-launch auto-start, so the
        // "before the first call" rating prompt may be considered.
        didAutostartInitialFeed = true

        // Bring up StoreKit: listen for transaction updates, then load products
        // and re-evaluate entitlements. The observer below enforces the lock.
        Task {
            await entitlements.startListeningForTransactions()
            await entitlements.refresh()
        }
    }

    /// Keep the active mode in sync with the subscription state: whenever Live
    /// access is lost (expired, revoked, never purchased) force Mock Mode on, and
    /// whenever it is gained (subscription confirmed at launch, purchased, or
    /// restored) default into Live Connected Mode. At startup the app is pinned to
    /// Mock Mode until the async entitlement refresh completes; this promotes a
    /// subscribed user to Live once that check confirms their access.
    private func observeEntitlements() {
        entitlements.$hasLiveAccess
            .sink { [weak self] hasAccess in self?.applyEntitlement(hasLiveAccess: hasAccess) }
            .store(in: &cancellables)
    }

    /// React to a change in Live-access entitlement, switching the active mode to
    /// match. Driven by the `hasLiveAccess` value the publisher hands us — **not**
    /// by re-reading `entitlements.hasLiveAccess` (nor via `toggleMockMode`, whose
    /// guard does). `@Published` emits from `willSet`, so while this runs the
    /// property still returns the *previous* value; routing the "access gained"
    /// case through `toggleMockMode(false)` would let its guard read that stale
    /// `false`, refuse the switch, and bounce a just-confirmed subscriber straight
    /// back into Mock Mode — leaving the mock toggle stuck on after the entitlement
    /// check passes.
    func applyEntitlement(hasLiveAccess: Bool) {
        if !hasLiveAccess && !settings.mockMode {
            diagnostics.log(.app, "Live subscription not active — locking to Mock Mode.")
            settings.mockMode = true
            startMock()
        } else if hasLiveAccess && settings.mockMode {
            diagnostics.log(.app, "Live subscription active — switching to Live Connected Mode.")
            settings.mockMode = false
            startLive()
        }
    }

    /// Disable the iOS idle timer so the screen stays on while the app is open.
    /// Infinite Flight closes the Connect socket when the companion device sleeps,
    /// so keeping the screen awake prevents the connection from dropping.
    private func applyKeepScreenAwake() {
        #if canImport(UIKit)
        UIApplication.shared.isIdleTimerDisabled = settings.keepScreenAwake
        #endif
    }

    // MARK: - Background chatter & Live Activity

    /// One-time wiring: give the chatter service its live context and hook the pilot's
    /// transmissions up to the mic-key static effect. Called from `onAppear`.
    private func configureChatter() {
        chatter.configure(settings: settings)
        chatter.bindContext(facility: { [weak self] in self?.currentFacility ?? .center },
                            runways: { [weak self] in self?.chatterRunwayContext() ?? ChatterRunwayContext() })
        // When the pilot switches frequencies, end any chatter mid-exchange on the old
        // frequency (and its pending read-back) and start chatter for the new one. Pass the
        // emitted value: `@Published` fires in `willSet`, so `currentFacility` still holds the
        // old value while this runs.
        $currentFacility
            .removeDuplicates()
            .sink { [weak self] facility in self?.chatter.facilityDidChange(to: facility) }
            .store(in: &cancellables)
        // Pilot transmissions get a mic-key/un-key static burst via the radio engine.
        speech.micKey = { [weak self] event in self?.chatter.micKey(event) }
        applyChatterSettings()
    }

    /// The runways the background chatter should reference right now, for the airport in play —
    /// the origin field while operating pre-departure/climbing, the destination once descending
    /// or arriving. When the field's ATIS is available its active departure/arrival runways are
    /// used (reconciled against the OSM map when loaded); otherwise the field's full OSM runway
    /// set; and with neither, all-empty so the chatter falls back to a plausible random runway.
    private func chatterRunwayContext() -> ChatterRunwayContext {
        let icao = chatterAirportICAO
        guard !icao.isEmpty else { return ChatterRunwayContext() }
        let field = airportSurface.cachedRunwayIdents(icao: icao)
        guard let atis = atisReport(forICAO: icao) else {
            return ChatterRunwayContext(all: field)
        }
        let active = ATISRunwayParser.activeRunways(atis)
        let departures = reconcileRunways(active.departures, field: field)
        let arrivals = reconcileRunways(active.arrivals, field: field)
        let union = orderedRunwayUnion(departures, arrivals)
        return ChatterRunwayContext(all: union.isEmpty ? field : union,
                                    departures: departures, arrivals: arrivals)
    }

    /// The ATIS report for a field, matched by ICAO: the departure ATIS covers the origin, the
    /// arrival ATIS the destination. Nil when we hold no ATIS for that field.
    private func atisReport(forICAO icao: String) -> AirportATIS? {
        let key = icao.uppercased().trimmingCharacters(in: .whitespaces)
        guard !key.isEmpty else { return nil }
        if let departureATIS, departureATIS.airport.uppercased() == key { return departureATIS }
        if let arrivalATIS, arrivalATIS.airport.uppercased() == key { return arrivalATIS }
        return nil
    }

    /// Keep only the ATIS runways the field's OSM map confirms — guarding the map's "never name
    /// a runway that isn't there" guarantee against a parse slip. With no map loaded yet the
    /// ATIS runways are trusted as-is (they're real); an empty result means "use the field set".
    private func reconcileRunways(_ atisRunways: [String], field: [String]) -> [String] {
        guard !atisRunways.isEmpty else { return [] }
        guard !field.isEmpty else { return atisRunways }
        let fieldSet = Set(field.map(ATISRunwayParser.canonical))
        return atisRunways.filter { fieldSet.contains(ATISRunwayParser.canonical($0)) }
    }

    /// Departure and arrival runways combined, de-duplicated in first-seen order.
    private func orderedRunwayUnion(_ a: [String], _ b: [String]) -> [String] {
        var seen = Set<String>()
        return (a + b).filter { seen.insert(ATISRunwayParser.canonical($0)).inserted }
    }

    /// Which field's runways the chatter simulates: the destination while on approach or once
    /// the flight has passed top-of-descent, otherwise the departure/origin field.
    private var chatterAirportICAO: String {
        if currentFacility == .approach { return flightPlan.destination }
        switch phase {
        case .descent, .approach, .landing, .taxiIn, .parked:
            return flightPlan.destination
        default:
            return flightPlan.departure
        }
    }

    /// Apply the current chatter/Live-Activity settings and start or stop the ambient
    /// chatter (the background-audio anchor) accordingly.
    private func applyChatterSettings() {
        chatter.refreshConfig()
        // Audible background audio needs the `.playback` category regardless of the
        // silent-switch preference.
        speech.forcePlaybackForBackground = settings.backgroundChatterEnabled || settings.liveActivityEnabled
        updateChatterRunState()
        updateLiveActivityRunState()
    }

    /// Run the continuous chatter while it's enabled, the flight is under way (the pilot has
    /// made their first ATC call), and it hasn't ended; otherwise stop it (the transient
    /// mic-key bursts still work on demand).
    private func updateChatterRunState() {
        guard started else { return }
        if shouldRunAmbientChatter {
            chatter.start()
        } else {
            chatter.stop()
        }
    }

    /// Whether the ambient chatter should be playing right now: enabled in Settings, the flight
    /// has begun communicating with ATC, and it hasn't ended (parked at the destination gate).
    var shouldRunAmbientChatter: Bool {
        settings.backgroundChatterEnabled && atcCommunicationStarted && !flightHasEnded
    }

    /// Whether the flight is over — arrived and parked at the destination gate. The state
    /// machine's cursor reaches `.parked` only at the end of the flight (never at the gate
    /// before pushback), and it is updated synchronously before `atcState`, so this reads
    /// correctly even from the `$atcState` observer.
    private var flightHasEnded: Bool { stateMachine.current == .parked }

    /// One-time wiring: route the Live Activity's Read Back / Check In buttons back into
    /// the same actions the on-screen buttons drive.
    private func configureLiveActivity() {
        CompanionActionCenter.shared.handler = { [weak self] action in
            guard let self else { return }
            switch action {
            case .readBack: self.readBack()
            case .checkIn: self.requestHandoff()
            case .refresh: break   // no ATC side effect — the forced push below re-sends current telemetry
            }
            // Every button re-pushes the current state. The 1 Hz poll keeps `aircraftState`
            // fresh even while backgrounded, so this sends up-to-the-second telemetry — and
            // because it runs from a user tap it lands immediately, unlike a throttled
            // background push.
            self.refreshLiveActivity(force: true)
        }
    }

    /// Start or end the live flight notification to match the setting.
    private func updateLiveActivityRunState() {
        guard started else { return }
        if settings.liveActivityEnabled {
            liveActivity.start(buildLiveActivityState())
        } else {
            liveActivity.end()
        }
    }

    /// Push the current flight state to the notification (throttled unless `force`).
    private func refreshLiveActivity(force: Bool = false) {
        guard settings.liveActivityEnabled, liveActivity.isActive else { return }
        liveActivity.update(buildLiveActivityState(), force: force)
    }

    private func buildLiveActivityState() -> CompanionActivityAttributes.ContentState {
        let alt = Int((aircraftState.altitudeMSL ?? 0).rounded())
        let hdg = ((Int((aircraftState.heading ?? 0).rounded()) % 360) + 360) % 360
        let spd = Int((aircraftState.groundSpeed ?? 0).rounded())
        let route = [flightPlan.departure, flightPlan.destination]
            .filter { !$0.isEmpty }
            .joined(separator: " → ")
        return CompanionActivityAttributes.ContentState(
            phase: phase.title,
            facility: currentFacility.title,
            facilitySymbol: currentFacility.symbol,
            altitude: alt,
            heading: hdg,
            speed: spd,
            callsign: notificationCallsign(),
            route: route,
            nextFacility: pendingCheckInFacility?.title,
            weatherAlert: activeWeatherConflict != nil ? "Weather ahead on route" : nil,
            canReadBack: lastATCTransmission != nil && !companionStandby,
            canCheckIn: availableActions.contains(.checkIn),
            standby: companionStandby)
    }

    private func notificationCallsign() -> String {
        if !settings.callsign.isEmpty { return settings.callsign }
        let combined = settings.airline + settings.flightNumber
        return combined.isEmpty ? "IFATC" : combined
    }

    private func observeSettings() {
        // Keep phraseology engines and dependent objects in sync with settings.
        settings.$digitStyle
            .combineLatest(settings.$phraseologyMode)
            .sink { [weak self] _, _ in self?.applyEngineConfig() }
            .store(in: &cancellables)

        // Rebuild engines when the active phraseology profile changes.
        phraseologyProfiles.$activeProfileID
            .dropFirst()
            .sink { [weak self] _ in self?.applyEngineConfig() }
            .store(in: &cancellables)

        phraseologyProfiles.$profiles
            .dropFirst()
            .sink { [weak self] _ in self?.applyEngineConfig() }
            .store(in: &cancellables)

        settings.$debugLogging
            .sink { [weak self] on in self?.diagnostics.verbose = on }
            .store(in: &cancellables)

        settings.$keepScreenAwake
            .sink { [weak self] _ in self?.applyKeepScreenAwake() }
            .store(in: &cancellables)

        // Background chatter / Live Activity: react to the toggles and tuning knobs.
        settings.$backgroundChatterEnabled
            .combineLatest(settings.$liveActivityEnabled)
            .sink { [weak self] _, _ in self?.applyChatterSettings() }
            .store(in: &cancellables)
        settings.$chatterVolume
            .combineLatest(settings.$chatterDensity, settings.$transmissionStaticEnabled)
            .sink { [weak self] _, _, _ in self?.applyChatterSettings() }
            .store(in: &cancellables)

        // Duck the ambient chatter whenever a real ATC/ATIS/pilot call is speaking.
        speech.$isSpeaking
            .removeDuplicates()
            .sink { [weak self] speaking in self?.chatter.setDucked(speaking) }
            .store(in: &cancellables)

        // Pause the chatter around push-to-talk so it never bleeds into the mic.
        speechRecognizer.$isListening
            .removeDuplicates()
            .sink { [weak self] listening in
                guard let self else { return }
                if listening { self.chatter.pauseForPTT() } else { self.chatter.resumeAfterPTT() }
            }
            .store(in: &cancellables)

        // Track live ATC-staffing status from the Connect link (live mode only).
        connect.$liveATC
            .sink { [weak self] status in
                guard let self, !self.settings.mockMode else { return }
                self.applyLiveATC(status)
            }
            .store(in: &cancellables)

        // In mock mode, derive staffing from the demo toggle.
        $simulateStaffedATC
            .sink { [weak self] on in
                guard let self, self.settings.mockMode else { return }
                self.applyLiveATC(on ? self.mockStaffedStatus() : .none)
            }
            .store(in: &cancellables)
    }

    /// A simulated staffing snapshot for the Diagnostics demo toggle in mock mode:
    /// pretend the pilot is tuned to a staffed controller matching the current facility.
    private func mockStaffedStatus() -> LiveATCStatus {
        LiveATCStatus(multiplayerOnline: true,
                      serverName: "Expert",
                      humanControllerActive: true,
                      controllerName: "Demo Controller",
                      tunedFrequencyName: currentFacility.isFAAATC ? currentFacility.title : nil)
    }

    /// Apply a new live-ATC status and log human-ATC transitions. Logs both the
    /// detection of a human controller in the session and the per-frequency standby
    /// state (which only engages while the pilot is tuned to that controller).
    private func applyLiveATC(_ status: LiveATCStatus) {
        let wasHuman = liveATC.humanControllerActive
        let wasStandby = liveATC.companionShouldStandBy
        liveATC = status
        if status.humanControllerActive != wasHuman {
            diagnostics.log(.atc, status.humanControllerActive
                ? "Human ATC online in session\(status.controllerName.map { " (\($0))" } ?? "")."
                : "Human ATC no longer present in session.")
        }
        if status.companionShouldStandBy != wasStandby {
            if status.companionShouldStandBy {
                let f = status.tunedFacility?.title ?? status.tunedFrequencyName ?? "a controller"
                diagnostics.log(.atc, "Tuned to human ATC (\(f)) — companion standing by on this frequency.")
            } else {
                diagnostics.log(.atc, "Off the human-controlled frequency — companion resuming.")
            }
        }
    }

    // MARK: - Source selection

    func startMock() {
        connect.disconnect()
        manualTuning = false
        tunedFacility = nil
        pendingCheckInFacility = nil
        goAroundInProgress = false
        clearReadbackGate()
        cancelTakeoffClearanceTimer()
        stateMachine.reset()
        hasDeparted = false
        arrivalAnnounced = false
        awaitingGateArrival = false
        monitoringTower = false
        gateMonitored = false
        // Fresh flight: hold the ambient chatter until the pilot's first ATC call.
        atcCommunicationStarted = false
        updateChatterRunState()
        atcState = .connectedIdle
        currentFacility = .clearance
        stateMachine.setConnected()
        applyLiveATC(simulateStaffedATC ? mockStaffedStatus() : .none)
        applyRadarProvider()
        resetWeatherDeviation()
        resetATISState(clearReported: true)
        mock.start()
        diagnostics.log(.app, "Mock simulator feed started.")
        Task { await refreshWeather() }
        armWeatherRefreshTimer()
        // Fresh session, connected and idle before the first ATC call — one of the two
        // calm windows in which a rating prompt is allowed. Skipped on the cold-launch
        // auto-start (Apple: never ask right at launch). Self-limited by `review`.
        if didAutostartInitialFeed { review.requestReviewIfAppropriate(.beforeFirstCall) }
    }

    func stopMock() {
        mock.stop()
        cancelWeatherRefreshTimer()
    }

    func startLive() {
        mock.stop()
        cancelWeatherRefreshTimer()
        tunedFacility = nil
        pendingCheckInFacility = nil
        goAroundInProgress = false
        clearReadbackGate()
        cancelTakeoffClearanceTimer()
        applyRadarProvider()
        resetWeatherDeviation()
        // Reset ATIS visibility but keep any already-fetched reports and received codes:
        // a reconnect (returning from another app) continues the same flight, so blanking
        // them here just made the ATIS line flap "received → not available → received"
        // until the connect-time refresh below re-populated it. The fresh-flight branch
        // clears them outright when there's no session to resume.
        resetATISState(clearReported: false)
        // Resume a recent in-progress session if one was saved (reconnect / relaunch);
        // otherwise start the conversation fresh. Restoring is what keeps a parked
        // aircraft from being re-derived straight to cruise after a dropped link.
        if !restoreSession() {
            manualTuning = false
            pendingTaxiMapRestore = .none
            stateMachine.reset()
            hasDeparted = false
            arrivalAnnounced = false
            awaitingGateArrival = false
            monitoringTower = false
            gateMonitored = false
            // Fresh flight: hold the ambient chatter until the pilot's first ATC call.
            atcCommunicationStarted = false
            currentFacility = .clearance
            // A genuinely fresh flight: no ATIS has been fetched or received yet.
            departureATIS = nil
            arrivalATIS = nil
            lastArrivalATISAttempt = nil
            reportedDepartureInfo = nil
            reportedArrivalInfo = nil
            departureInfoAppended = false
            arrivalInfoAppended = false
            updateATISDiagnostics()
            // Genuinely fresh flight (nothing to restore), connected and idle before
            // the first ATC call — a calm window in which a rating prompt is allowed.
            // A restored/mid-flight session skips this branch, so it never fires in
            // flight; the cold-launch auto-start is skipped too (Apple: never ask right
            // at launch). Self-limited by `review`.
            if didAutostartInitialFeed { review.requestReviewIfAppropriate(.beforeFirstCall) }
        }
        // Reflect the new/restored flight in the chatter: stopped for a fresh flight (awaiting
        // the first ATC call), resumed when a mid-flight session was restored.
        updateChatterRunState()
        if settings.autoDiscover && settings.host.isEmpty {
            connect.startAutoDiscover { [weak self] device in
                guard let self else { return }
                self.settings.host = device.address
                self.settings.port = device.port
                self.connect.connect(host: device.address, port: device.port)
                self.afterConnect()
            }
        } else if !settings.host.isEmpty {
            connect.connect(host: settings.host, port: settings.port)
            afterConnect()
        } else {
            diagnostics.log(.app, "No host set and auto-discover off — staying idle. Enter an IP in Settings.")
        }
    }

    private func afterConnect() {
        // Load weather once the connection is established, then keep it fresh on a timer
        // (the first periodic tick lands a full interval after this initial load).
        Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            await self.refreshWeather()
            self.armWeatherRefreshTimer()
        }
    }

    func toggleMockMode(_ on: Bool) {
        // Live Connected Mode requires an active subscription. Without one, keep
        // the app pinned to Mock Mode regardless of the requested value.
        guard on || entitlements.hasLiveAccess else {
            settings.mockMode = true
            if !mock.running { startMock() }
            return
        }
        settings.mockMode = on
        airportSurface.clear()
        if on {
            // Rebuild the plan from the mock route so its realistic default gates apply and
            // both airports are pre-cached for a realistic taxi demo.
            syncFlightPlanFromSettings()
            startMock()
        } else {
            startLive()
        }
    }

    func reconnect() {
        if settings.mockMode { startMock() } else { connect.disconnect(); startLive() }
    }

    /// True after the app has been sent to the background, so the next return to the
    /// foreground forces a fresh Connect link.
    private var wasBackgrounded = false

    /// Whether the app is currently in the foreground. Gates network polling (radar
    /// resampling) so we don't fetch from public weather services while backgrounded.
    private var appActive = true

    /// Position and wall-clock of the last processed live fix. Together they detect a
    /// telemetry *discontinuity* — a fix that jumped farther than the elapsed time can
    /// account for. The Connect socket freezes while the app is backgrounded (audio
    /// keeps the poll loop alive, but Infinite Flight stops feeding it), so the first
    /// fresh fix on return snaps forward to the live position. `AircraftState.lastUpdate`
    /// can't see this — it's stamped at read time, so a frozen fix always looks current.
    private var lastTelemetryCoordinate: CLLocationCoordinate2D?
    private var lastTelemetryAt: Date?
    /// Set when the app returns to the foreground and forces a reconnect, so the first
    /// fresh fix after the gap is always treated as a resync (the socket was frozen while
    /// away). Consumed by `detectTelemetryDiscontinuity`.
    private var telemetryResyncPending = false
    /// True only for the current `recomputeWeatherHazards` pass when it is running on a
    /// jumped (post-background / post-stall) fix, so position-driven weather decisions —
    /// issuing turns, auto-advisories, tearing down the lifecycle — stand down for that
    /// tick rather than acting on a discontinuous position.
    private var telemetryDiscontinuity = false

    /// Watches the network path so the "Reduce cellular data" setting can suppress the
    /// megabyte-scale OPERA composite downloads on a cellular / expensive connection.
    /// Dormant while OPERA is disabled (no megabyte downloads to throttle), but kept
    /// running so `isExpensiveNetwork` is ready when OPERA is re-enabled.
    private let networkMonitor = NWPathMonitor()
    /// True on a cellular / personal-hotspot / Low-Data-Mode connection.
    private(set) var isExpensiveNetwork = false

    /// Record that the app went to the background (the OS may have torn down or
    /// silently stalled the Infinite Flight TCP link while we were away).
    func markBackgrounded() { wasBackgrounded = true; appActive = false }

    /// When the app returns to the foreground after being backgrounded, force a
    /// reconnect so live flight details resume updating immediately. (Infinite Flight
    /// can leave the socket looking "connected" while no new telemetry flows, which
    /// otherwise required a manual Reconnect.) The in-progress session is restored on
    /// reconnect, so the conversation picks up where it left off. No-op in Mock Mode.
    func handleReturnToForeground() {
        appActive = true
        guard started, wasBackgrounded else { return }
        wasBackgrounded = false
        guard !settings.mockMode else { return }
        // The socket was (likely) frozen while away, so the first fix after the reconnect
        // has jumped to the live position. Mark a resync so the ATC/weather flow doesn't
        // treat that jump as continuous flight and replay the turns flown during the gap.
        telemetryResyncPending = true
        diagnostics.log(.app, "Returned to foreground — forcing an Infinite Flight reconnect.")
        connect.disconnect()
        startLive()
    }

    /// Whether the current live fix is discontinuous with the previous one — either the
    /// first fix after a forced foreground reconnect, or (live mode) a jump too large for
    /// the elapsed time to be continuous flight. Updates the last-fix trackers as a side
    /// effect, so it must be called once per usable telemetry tick.
    private func detectTelemetryDiscontinuity(for state: AircraftState) -> Bool {
        let now = state.coordinate
        defer {
            if let now, now.isValid { lastTelemetryCoordinate = now }
            lastTelemetryAt = Date()
        }
        // The definitive signal: the app just returned from the background and forced a
        // reconnect, so this first fresh fix has snapped to the live position.
        if telemetryResyncPending {
            telemetryResyncPending = false
            return true
        }
        // Belt-and-suspenders for a mid-session live stall (the Connect socket can freeze
        // without a full background cycle): a fix that moved farther than the elapsed time
        // could plausibly carry it is a frozen fix snapping forward, not real flight. Gated
        // to live mode so the mock tests, which teleport the aircraft between fixes on
        // purpose, are unaffected.
        guard !settings.mockMode,
              let prev = lastTelemetryCoordinate, prev.isValid,
              let prevAt = lastTelemetryAt, let now, now.isValid else { return false }
        let elapsed = Date().timeIntervalSince(prevAt)
        guard elapsed > 0 else { return false }
        // Plausible-travel bound: groundspeed × elapsed, tripled for slack (wind, a burst
        // of catch-up ticks) plus a floor so a near-stationary fix tolerates GPS jitter.
        let gsKnots = max(state.groundSpeed ?? 0, 0)
        let maxNM = max(2.0, (gsKnots / 3600.0) * elapsed * 3.0 + 1.0)
        return Geo.distanceNM(from: prev, to: now) > maxNM
    }

    /// Start watching the network path so `isExpensiveNetwork` reflects a cellular /
    /// hotspot / Low-Data-Mode connection (drives the "Reduce cellular data" setting).
    private func startNetworkMonitor() {
        networkMonitor.pathUpdateHandler = { [weak self] path in
            let expensive = path.isExpensive || path.isConstrained
            Task { @MainActor in self?.isExpensiveNetwork = expensive }
        }
        networkMonitor.start(queue: DispatchQueue(label: "ifatc.network.monitor"))
    }

    // MARK: - State handling

    #if DEBUG
    /// Test hook: feed a single aircraft-state snapshot through the same pipeline the
    /// live/mock feeds use (phase detection → state machine → transcript). Lets tests
    /// drive a full mock scenario without starting timers, networking, or audio.
    func ingestStateForTesting(_ state: AircraftState) { handle(state: state) }

    /// Test hook: capture the current session as a snapshot (as persistence would).
    func snapshotForTesting() -> SessionSnapshot { currentSnapshot() }

    /// Test hook: restore from a snapshot the same way a reconnect would, so a test
    /// can verify the conversation resumes where it left off.
    func applySnapshotForTesting(_ snapshot: SessionSnapshot) { apply(snapshot: snapshot) }

    /// Test hook: arm the taxi-map restore from a snapshot (the half of `restoreSession`
    /// that decides whether a swiped-away taxi should be re-established on the next fix).
    func armTaxiMapRestoreForTesting(from snapshot: SessionSnapshot) { armTaxiMapRestore(from: snapshot) }

    /// Test hook: the pending taxi-map restore kind (`.none` when nothing is queued).
    var pendingTaxiMapRestoreForTesting: TaxiKind { pendingTaxiMapRestore }

    /// Test hook: evaluate the gate-arrival completion gate with an injected gate position
    /// and Ramp-tuned state (the taxi map's real gate coordinate is unavailable offline).
    /// Passing `gate: nil` exercises the "gate unknown → Ramp + parking-brake" fallback;
    /// `tunedToRamp: false` exercises the "not on Ramp → never completes" gate.
    func isParkedAtGateForTesting(_ state: AircraftState, gate: CLLocationCoordinate2D?,
                                  tunedToRamp: Bool = true) -> Bool {
        arrivalGatePosition = gate
        currentFacility = tunedToRamp ? .ramp : .ground
        return isParkedAtGate(state)
    }

    /// Test hook: force the confirm-clear window to have elapsed, so the next recompute
    /// drops a held (no-longer-detected) conflict instead of waiting out the real
    /// hysteresis window. Only affects a conflict no longer present in the locked set.
    func expireWeatherClearWindowForTesting() {
        lastConflictSeenAt = .distantPast
    }

    /// Test hook: return the weather-deviation lifecycle to the un-engaged state (as if the
    /// pilot never tapped the banner), while marking the one-shot mock demo advisory as
    /// already spent — so a test can exercise the near-turn auto-call in isolation.
    func markWeatherUnengagedForTesting() {
        weatherDeviation.reset()
        weatherDeviation.state = .none
        weatherHandled = false
        mockWeatherAdvisoryIssued = true
    }

    /// Test hook: the route the detector treats as "ahead" of a position, so a test can
    /// verify it follows the filed route past a bend rather than a straight-line
    /// distance-from-departure test that drops upcoming fixes once telemetry is live.
    func upcomingRouteCoordinatesForTesting(from pos: CLLocationCoordinate2D) -> [CLLocationCoordinate2D] {
        upcomingRouteCoordinates(from: pos)
    }

    /// Test hook: the rejoin cap the mint line is clamped to, so a test can verify it sits
    /// at least the airport margin before the field (and short of the approach fix).
    func weatherRejoinCapForTesting() -> CLLocationCoordinate2D? {
        weatherRejoinCap()
    }

    /// Test hook: whether a drawn deviation ends on the filed route — the invariant that
    /// separates a deviation from a diversion, and the one a re-plan can lose because the
    /// detector only guarantees it against the polyline it was handed.
    func deviationEndsOnFlightPathForTesting(_ path: [CLLocationCoordinate2D]) -> Bool {
        guard let pos = aircraftState.coordinate, pos.isValid else { return true }
        return deviationEndsOnFlightPath(path, from: pos)
    }

    /// Test hook: the gradual return to course for an explicit path — the rejoin moved to a fix
    /// farther down the route when the final turn is too steep — so a test can drive the fix
    /// selection directly instead of coaxing the solver into a sharp intercept.
    func gentleRejoinForTesting(path: [CLLocationCoordinate2D]) -> (path: [CLLocationCoordinate2D], fix: Waypoint)? {
        gentleRejoin(for: path, cap: weatherRejoinCap(), limitAlong: .greatestFiniteMagnitude)
    }

    /// Test hook: arm the telemetry resync the way returning from the background does, so
    /// the next ingested fix is treated as a post-gap jump — exercising the discontinuity
    /// handling (turn-burst collapse, lifecycle preservation) without a live socket.
    func markTelemetryResyncPendingForTesting() { telemetryResyncPending = true }

    /// Test hook: merge a flight plan the way a live read from Infinite Flight does, so a
    /// test can verify a route edited mid-flight reaches the app (and that manual endpoint
    /// overrides never pin the fix list).
    func mergeLiveFlightPlanForTesting(_ live: FlightPlan) {
        mergeLiveFlightPlan(live)
    }

    /// Test hook: the context the ATC calls are built from, so a test can read a derived
    /// value (the initial departure heading, say) without driving the whole gate-to-gate
    /// flow to the point where it would be spoken.
    func contextForTesting(_ state: ATCState) -> ATCContext {
        buildContext(for: state)
    }
    #endif

    private func handle(state: AircraftState) {
        aircraftState = state
        stateMachine.setConnected()
        // Persist the session on the way out of every path so a drop after this tick
        // resumes from here, and keep the live notification current (throttled).
        defer { persistSession(); refreshLiveActivity() }

        // Ignore an empty telemetry snapshot — Infinite Flight returns one during the
        // reconnect handshake, and it says nothing about the aircraft at all. Hold the
        // current (restored) ATC state until real telemetry arrives. (Partial telemetry
        // — e.g. altitude or ground state without a position fix — is still processed;
        // a partial snapshot with no ground reference is handled where it matters, by
        // PhaseDetector holding the phase rather than reading the missing flag as
        // airborne and defaulting to "climb".)
        guard state.hasUsableTelemetry else {
            atcState = stateMachine.current
            currentFacility = tunedFacility ?? controller(for: stateMachine.current)
            return
        }

        // Keep the magnetic-variation and wind estimates current. Assigned weather
        // headings are derived from true-bearing geometry, and both corrections that
        // carry them into the pilot's frame come from telemetry.
        updateHeadingCorrections(from: state)

        // Detect a telemetry discontinuity — the first fresh fix after returning from the
        // background (the Connect socket freezes while away), or a mid-session live stall
        // that snaps the position forward. Position-based weather decisions must not treat
        // a jumped fix as continuous flight (see recomputeWeatherHazards).
        let telemetryJumped = detectTelemetryDiscontinuity(for: state)

        let result = phaseDetector.detect(state: state, plan: flightPlan,
                                          airports: airports, previous: phase)
        phase = result.phase
        phaseDebug = result.debug

        // Re-evaluate route-weather conflicts / radar coverage as the aircraft
        // moves. Pure and cheap; never advances the ATC state machine, so it can't
        // interfere with the gate-to-gate flow or the readback gate.
        recomputeWeatherHazards(telemetryJumped: telemetryJumped)
        // Keep the sampled precipitation current as the aircraft flies (throttled),
        // so the deviation line tracks the weather ahead instead of going stale and
        // dropping between manual refreshes.
        maybeResamplePrecipitation()
        // Pull the destination ATIS the moment the aircraft comes within 100 NM, so the
        // arrival ATIS button appears promptly rather than waiting for the ~5-min timer.
        maybeFetchArrivalATIS()
        // Re-establish a taxi map that was interrupted by a swipe-away / relaunch, now
        // that a real position fix is in hand to route from. No-op unless one is pending.
        performPendingTaxiMapRestore(for: state)
        // Track the aircraft along the taxi route and run the runway-crossing workflow.
        // Pure/cheap; no-op unless a live taxi map is active (mock is self-driven).
        airportSurface.updateLive(coordinate: state.coordinate,
                                  heading: state.trueHeading ?? state.heading,
                                  onGround: state.onGround,
                                  groundSpeed: state.groundSpeed)

        if !phase.isGround {
            hasDeparted = true
            // Backstop: if the departure taxi map is somehow still up once the aircraft is
            // airborne (the pilot never read back the line-up-and-wait / takeoff clearance
            // that normally retires it), retire it now — it has no purpose in the air.
            if airportSurface.kind == .departure, airportSurface.taxiMapVisible {
                airportSurface.hideTaxiMap()
            }
        }

        // Capture the departure field elevation from on-ground telemetry (ground ≈
        // MSL − AGL) before the aircraft departs, so initial-climb altitudes can be
        // raised above the field at high-elevation airports. Kept fresh while on the
        // ground so the last value before takeoff is the field elevation.
        //
        // Only ever from a snapshot that *reports* being on the ground. Falling back to
        // the detected phase looks equivalent but isn't: a half-read snapshot carries no
        // ground reference, so `PhaseDetector` holds the phase where it is — on the ground
        // for a departure — and this would then take the raw MSL, with no AGL to subtract,
        // as the field elevation. One such snapshot in the seconds after rotation would
        // put the field hundreds of feet up and raise every initial-climb altitude
        // derived from it.
        let reportedOnGround = state.onGround ?? state.altitudeAGL.map { $0 < 10 }
        if !hasDeparted, reportedOnGround == true, let msl = state.altitudeMSL {
            departureFieldElevationMSL = max(0, msl - (state.altitudeAGL ?? 0))
        }

        let mapped = stateMachine.mappedState(for: phase)

        // Defer to a human controller on the tuned frequency: track state for
        // display, but do not generate or speak controller calls. Honor a manual
        // tune so the guard stays pinned to the frequency the pilot is actually on
        // (rather than snapping to the phase-derived controller, which would fight
        // the per-frequency guard as the pilot tunes on and off the staffed sector).
        if companionStandby {
            atcState = mapped
            currentFacility = tunedFacility ?? controller(for: mapped)
            return
        }

        // Issue any arrival taxi clearance Ground withheld while the destination surface was
        // still loading, the moment the load resolves — before the mode-specific flow below,
        // so it fires the same way on the automatic and the manual (semi-auto) tuning path.
        issueDeferredArrivalTaxiClearanceIfLoaded()

        // After contacting arrival Ramp, walk the arrival in to the gate in stages so
        // the ramp calls never all fire at once: first "monitor ramp to the gate" as
        // the aircraft slows toward a stop, then — a tick later — the block-in /
        // "flight complete" once it is actually parked with the parking brake set.
        // This runs even while manually tuned, since the pilot drove the Ramp call.
        if awaitingGateArrival {
            if !gateMonitored, isSlowingAtGate(state) {
                gateMonitored = true
                let c = buildContext(for: .groundArrival, arrivalOverride: true)
                post(rampEngine.monitorRampToGate(cs: c.callsign), speak: true)
            } else if gateMonitored, isParkedAtGate(state) {
                completeGateArrival()
            }
            atcState = stateMachine.current
            // Keep the radio on Ramp while taxiing in to the gate (the pilot contacted Ramp
            // for the gate) — this is the "tuned to Ramp" the completion gate checks. Once
            // parked, the arrival is over and control returns to the ground/parked facility.
            currentFacility = awaitingGateArrival ? .ramp : controller(for: stateMachine.current)
            return
        }

        // --- Pre-departure (on the ground, before the first takeoff) ---
        // The pilot drives their own calls (clearance → pushback → engine start →
        // taxi → ready) with the response buttons. The only controller call issued
        // automatically here is the takeoff clearance, once the aircraft is lined
        // up on the runway — Tower does not wait for a pilot prompt for that.
        if !hasDeparted {
            // Approaching the departure runway, Ground hands the pilot to Tower to
            // *monitor* ("monitor Tower on …"). One-shot; sets `monitoringTower`.
            maybeMonitorTowerHandoff()
            if readbackGateClosed {
                // Hold: the controller is waiting for the pilot to read back the
                // last call (or to answer the "how do you read?" prompt) before
                // issuing anything further.
            } else if stateMachine.current == .lineUpWait, !settings.mockMode {
                // Tower clears the takeoff automatically once the aircraft is on the
                // runway — immediately if it's already rolling, otherwise a few
                // seconds after it settles lined up and stopped. This fires even while
                // the pilot is tuning frequencies by hand (they tuned Tower for it).
                autoAdvanceTakeoffClearance(state: state)
            } else if monitoringTower, !settings.mockMode {
                // Pilot is monitoring Tower (Ground already handed them off) — no "ready"
                // report or check-in needed. As the aircraft reaches the runway
                // hold-short, Tower issues "line up and wait"; the takeoff clearance then
                // fires automatically once the aircraft is lined up on the runway.
                autoAdvanceMonitoringTower(state: state)
            } else if !manualTuning, !mapped.isManualGroundFlow, isForward(mapped) {
                // Telemetry already shows the takeoff roll (the pilot rolled without
                // using the buttons) — advance so the flow is never stuck on ground.
                advanceAndPost(to: mapped, context: buildContext(for: mapped), automatic: true)
            }
            // Otherwise hold: the pilot-driven ground flow advances via the buttons.
            atcState = stateMachine.current
            currentFacility = tunedFacility ?? controller(for: stateMachine.current)
            return
        }

        // --- Airborne / arrival ---
        // When the pilot is changing controllers manually with the frequency buttons:
        //  • In Mock Mode there is no live telemetry, so the conversation advances only
        //    on a button press (keep the display fresh and return).
        //  • In live mode the controller's position-based calls and facility hand-offs
        //    still fire automatically from telemetry — but each hand-off only prompts
        //    "contact <next> on …" and then waits for the pilot to tune that frequency
        //    and check in before the new controller gives its instruction.
        if manualTuning {
            if !settings.mockMode { advanceSemiAutomatic(mapped: mapped, state: state) }
            atcState = stateMachine.current
            // The dialed frequency stays on the controller the pilot is actually tuned to
            // — it does not jump to a pending hand-off target the moment ATC issues it.
            // Reading the hand-off back (or tuning by hand) is what moves it; the pending
            // controller is surfaced separately via `workingFacility` / the header.
            currentFacility = tunedFacility ?? controller(for: stateMachine.current)
            return
        }

        // Hold the automatic flow until the pilot has acknowledged the last call.
        // Without this, telemetry polling re-issues calls every tick and they pile
        // up "one after the next" near the runway.
        if readbackGateClosed {
            atcState = stateMachine.current
            currentFacility = controller(for: stateMachine.current)
            return
        }

        // A go-around just reset the conversation for another approach: hold the
        // automatic flow until the pilot re-establishes with Approach (checks in), so
        // the missed-approach climb isn't immediately re-advanced back through the
        // cleared-approach call while the aircraft is still flying the pattern.
        if goAroundInProgress {
            atcState = stateMachine.current
            currentFacility = tunedFacility ?? controller(for: stateMachine.current)
            return
        }

        // Otherwise controller callouts (hand-offs, climb/descent, approach,
        // landing, taxi-in) are issued automatically as the aircraft reaches each
        // position. The flow only ever moves forward (never bounces back to an
        // earlier state while the phase detector flickers). Pilot read-backs and
        // check-ins stay manual.
        let previousState = stateMachine.current
        let target = adjustedAirborneTarget(mapped: mapped, state: state)
        if isForward(target) {
            if target == .groundArrival, previousState != .groundArrival {
                // Arrival taxi-in: withhold Ground's clearance until the destination surface
                // has fully loaded, so it routes to the gate rather than giving a generic
                // "taxi to parking" it would then supersede. Both airports are pre-cached at
                // flight load and the surface is loaded at the runway exit, so this is normally
                // ready at once; an uncached/slow field simply holds (re-checking each
                // telemetry tick) until the load resolves, only then falling back to a generic
                // call if the data still can't be routed.
                if shouldWaitForArrivalRoute() {
                    atcState = stateMachine.current
                    currentFacility = controller(for: stateMachine.current)
                    return
                }
                issueArrivalTaxiClearance(announceHandoff: true, automatic: true)
            } else {
                advanceAndPost(to: target, context: buildContext(for: target), automatic: true)
                // Once the approach is cleared and the aircraft is established, Approach
                // hands the pilot to Tower (instruction first, then the hand-off — the
                // reverse of the usual "contact … then instruction" order, so it is
                // posted explicitly here rather than via the generic facility-change
                // hand-off, which the .final → .landing step then suppresses).
                if previousState != .final, stateMachine.current == .final {
                    announceApproachToTowerHandoff()
                }
            }
        }

        // One-time arrival courtesy once stopped at the gate.
        if stateMachine.current == .parked, !arrivalAnnounced {
            announceArrival()
            arrivalAnnounced = true
        }

        atcState = stateMachine.current
        currentFacility = controller(for: stateMachine.current)
    }

    // MARK: - Automatic flow

    /// The controller (facility) actually working a given ATC state. Used to drive
    /// realistic "contact …" handoffs whenever control passes between facilities,
    /// and to label the current facility in the UI.
    private func controller(for state: ATCState) -> ATCFacility {
        switch state {
        case .clearance: return .clearance
        case .pushback, .engineStart: return .ramp
        case .pushbackTaxi, .groundTaxi, .runwayCrossing,
             .holdingShort, .groundArrival, .parked: return .ground
        case .lineUpWait, .towerDeparture, .landing, .runwayExit: return .tower
        case .initialClimb, .departure: return .departure
        case .climb, .center, .cruise, .topOfDescent, .descent: return .center
        case .approach, .final: return .approach
        case .notConnected, .connectedIdle, .abnormal: return currentFacility
        }
    }

    /// Frequency the given facility is reached on, from the current context.
    private func frequency(for facility: ATCFacility, context c: ATCContext) -> Double {
        switch facility {
        case .ramp: return c.rampFrequency
        case .clearance, .ground: return c.groundFrequency
        case .tower: return c.towerFrequency
        case .departure: return c.departureFrequency
        case .center: return c.centerFrequency
        case .approach: return c.approachFrequency
        }
    }

    /// Advance the state machine and post the resulting controller call, preceding
    /// it with a "contact …" handoff whenever the controlling facility changes.
    private func advanceAndPost(to target: ATCState, context c: ATCContext,
                                speak: Bool = true, announceHandoff: Bool = true,
                                automatic: Bool = false,
                                overrideTransmission: ATCTransmission? = nil) {
        // The arrival only completes once parked at the gate — stopped with the parking
        // brake set AND tuned to the Ramp frequency (see isParkedAtGate). Never advance to
        // .parked otherwise, so a stop out on a taxiway (or before the pilot contacts Ramp)
        // can't end the flight, with or without accurate taxi-map data. This is the single
        // choke point for every telemetry- and check-in-driven path to .parked; the staged
        // Ramp block-in (awaitingGateArrival) reaches it only once isParkedAtGate is true.
        if target == .parked, !isParkedAtGate(aircraftState) {
            atcState = stateMachine.current
            currentFacility = tunedFacility ?? controller(for: stateMachine.current)
            return
        }
        let previous = stateMachine.current
        // The conversation is advancing, so any pending manual tune has now been
        // acted on — the controller working the new state speaks for itself. Capture
        // which facility the pilot had tuned first: if they already switched to the
        // controller now taking over, that controller must not tell them to "contact"
        // it (they're already there).
        let wasTuned = tunedFacility
        tunedFacility = nil
        guard let stateTx = stateMachine.advance(to: target, context: c) else {
            atcState = stateMachine.current
            currentFacility = controller(for: stateMachine.current)
            return
        }
        // Callers can substitute the controller's transmission (e.g. the OSM
        // route-based Ground taxi clearance) while keeping the state advance,
        // hand-off, and read-back-gate behavior identical.
        let tx = overrideTransmission ?? stateTx
        let fromFacility = controller(for: previous)
        let toFacility = controller(for: target)
        // Announce a handoff only between two established controllers (not the very
        // first contact, and not Clearance which is the initial call-up). The
        // runway-exit call already tells the pilot to contact Ground, so suppress
        // the duplicate hand-off when leaving that state. The Ramp hand-off for
        // pushback is already issued at the end of the IFR clearance, so don't
        // repeat it here. When the pilot tuned the frequency themselves, the
        // controller does not say "contact …" either.
        let firstContact = previous == .notConnected || previous == .connectedIdle
        // The Approach → Tower hand-off is issued explicitly when the approach is
        // cleared (established), so don't repeat it when .final advances to .landing.
        if announceHandoff, !firstContact, previous != .runwayExit, previous != .final,
           fromFacility != toFacility, toFacility != .clearance, toFacility != .ramp,
           wasTuned != toFacility, lastATCTransmission != nil {
            post(engine.handoff(cs: c.callsign, from: fromFacility, to: toFacility,
                                frequency: frequency(for: toFacility, context: c)), speak: speak)
        }
        // Capture the field altitude at takeoff so the climb-out hand-off can be
        // held until the aircraft is well above the runway (see adjustedAirborneTarget).
        if target == .towerDeparture { liftoffAltitudeMSL = aircraftState.altitudeMSL ?? 0 }
        updateAssignedAltitude(for: target, context: c)
        post(tx, speak: speak)
        // An automatic call that carries a read-back instruction closes the gate:
        // the flow holds here until the pilot reads back (or the idle prompt loop
        // runs its course). Pilot-driven advances never close the gate — the pilot
        // is already driving the conversation. The takeoff clearance holds the gate
        // too (so the Departure hand-off can't stack on it), but must NOT arm the
        // idle "how do you read?" nag — a controller does not radio-check a pilot it
        // just cleared for takeoff, and the nag was firing before the pilot could
        // even read the clearance back.
        if automatic, target.expectsReadback {
            engageReadbackGate(tx, promptIfIdle: target != .towerDeparture)
        }
        atcState = stateMachine.current
        currentFacility = controller(for: stateMachine.current)
    }

    /// Whether the aircraft is positioned for the automatic takeoff clearance:
    /// lined up on the departure runway, already rolling, or telemetry reports the
    /// takeoff phase. Used in live mode once the pilot has reported ready (the
    /// state machine is at line-up-and-wait).
    private func isReadyForTakeoffClearance(state: AircraftState) -> Bool {
        let runway = buildContext(for: stateMachine.current).runway
        return lineupDetector.isLinedUp(state: state, runway: runway)
            || lineupDetector.isDepartingRoll(state: state, runway: runway)
            || phase == .takeoff
    }

    /// Tower automatically clears the aircraft for takeoff (no pilot prompt needed).
    private func autoIssueTakeoffClearance() {
        cancelTakeoffClearanceTimer()
        // The aircraft is departing — no pre-departure check-in remains pending (a
        // "monitor Tower" hand-off never requires one).
        pendingCheckInFacility = nil
        // When the pilot was already handed to Tower to monitor, Ground's "monitor Tower"
        // call was the hand-off — so the takeoff clearance must not re-announce a
        // "contact Tower" (it advances straight from the taxi state to the clearance).
        advanceAndPost(to: .towerDeparture, context: buildContext(for: .towerDeparture),
                       announceHandoff: !monitoringTower, automatic: true)
    }

    /// While monitoring Tower before departure (Ground already handed the pilot off),
    /// drive the automatic Tower calls from telemetry. As the aircraft *nears* the
    /// departure-runway hold-short — at the same lead distance the automatic
    /// runway-crossing clearance uses (`approachingRunwayLineup`) — Tower proactively
    /// issues "line up and wait" while the aircraft is still rolling up, so it can make a
    /// rolling line-up rather than stopping short first. The takeoff clearance then follows
    /// automatically once the aircraft is lined up on the runway. If the aircraft is
    /// already on the runway (it taxied straight on), the line-up-and-wait is skipped and
    /// the takeoff is cleared directly. Fires only in live mode (the `monitoringTower`
    /// branch is gated on it).
    private func autoAdvanceMonitoringTower(state: AircraftState) {
        let runway = buildContext(for: stateMachine.current).runway
        let onRunway = lineupDetector.isLinedUp(state: state, runway: runway)
            || lineupDetector.isDepartingRoll(state: state, runway: runway)
            || phase == .takeoff
        if !onRunway, airportSurface.approachingRunwayLineup {
            // Nearing the departure runway — Tower issues "line up and wait" so the aircraft
            // can roll straight onto the runway and line up. Non-gating (automatic: false):
            // the pilot is monitoring and needn't read it back for the flow to proceed, and
            // the takeoff clearance still fires automatically once the aircraft lines up.
            // The hand-off is suppressed — the pilot is already on Tower (the monitor-Tower
            // call moved them there).
            advanceAndPost(to: .lineUpWait, context: buildContext(for: .lineUpWait),
                           announceHandoff: false, automatic: false)
        } else {
            // Already on the runway (or not yet within line-up range): let the
            // takeoff-clearance logic clear it once lined up.
            autoAdvanceTakeoffClearance(state: state)
        }
    }

    /// As the aircraft nears the departure runway, Ground hands it to Tower to *monitor*
    /// ("monitor Tower on …", the red sign short of the runway). Fires once per departure
    /// taxi, the moment the surface coordinator flags the aircraft approaching the runway
    /// hold-short. No check-in is required afterwards — the read-back tunes the radio to
    /// Tower (when auto-tune is on) and the takeoff clearance still plays automatically
    /// once the aircraft is lined up. Works in live and mock alike.
    private func maybeMonitorTowerHandoff() {
        guard !monitoringTower, !hasDeparted, !companionStandby,
              airportSurface.kind == .departure,
              airportSurface.approachingRunwayHandoff,
              stateMachine.current == .groundTaxi,
              currentFacility == .ground,
              !readbackGateClosed,
              !airportSurface.awaitingCrossingReadback,
              !airportSurface.awaitingTaxiReadback else { return }
        monitoringTower = true
        let c = buildContext(for: .lineUpWait)
        post(engine.monitorTower(cs: c.callsign, frequency: c.towerFrequency), speak: true)
        persistSession()
    }

    /// Drive the automatic takeoff clearance from telemetry while at line-up-and-wait
    /// (live mode). If the aircraft is already rolling, clear it immediately; if it is
    /// lined up and stopped, arm a short countdown and clear it once that elapses; if
    /// it is neither (still maneuvering onto the runway), stand down the countdown.
    private func autoAdvanceTakeoffClearance(state: AircraftState) {
        let runway = buildContext(for: stateMachine.current).runway
        if lineupDetector.isDepartingRoll(state: state, runway: runway) || phase == .takeoff {
            autoIssueTakeoffClearance()
        } else if isLinedUpAndStopped(state, runway: runway) {
            armTakeoffClearance()
        } else {
            cancelTakeoffClearanceTimer()
        }
    }

    /// Whether the aircraft is established and stopped on the runway centerline,
    /// ready for the takeoff clearance after the brief realism delay.
    private func isLinedUpAndStopped(_ s: AircraftState, runway: String) -> Bool {
        lineupDetector.isLinedUp(state: s, runway: runway)
            && (s.onGround ?? true) && (s.groundSpeed ?? 0) < 5
    }

    /// Start the takeoff-clearance countdown if one isn't already running. When it
    /// elapses, re-check the aircraft is still lined up and waiting, then clear it.
    private func armTakeoffClearance() {
        guard takeoffClearanceTimer == nil else { return }
        takeoffClearanceTimer = Task { @MainActor [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: UInt64(max(0, self.takeoffClearanceDelay) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self.takeoffClearanceTimer = nil
            // Stand by while tuned to a human controller — Tower's automatic takeoff
            // clearance must not fire over a live controller. The next telemetry tick
            // re-arms it once the pilot leaves the human frequency.
            guard !self.hasDeparted,
                  self.stateMachine.current == .lineUpWait || self.monitoringTower,
                  !self.settings.mockMode, !self.readbackGateClosed, !self.companionStandby,
                  self.isReadyForTakeoffClearance(state: self.aircraftState) else { return }
            self.autoIssueTakeoffClearance()
        }
    }

    private func cancelTakeoffClearanceTimer() {
        takeoffClearanceTimer?.cancel()
        takeoffClearanceTimer = nil
    }

    /// Whether the aircraft is slow enough on the ground to be settling onto the gate
    /// (used to stage the arrival "monitor ramp to the gate" call before the block-in).
    private func isSlowingAtGate(_ s: AircraftState) -> Bool {
        (s.onGround ?? true) && (s.groundSpeed ?? 0) < 8
    }

    // MARK: - Semi-automatic airborne flow (live + manual tuning)

    /// In live mode, while the pilot tunes frequencies by hand, keep issuing the
    /// controller's automatic, position-based calls and facility hand-offs — but
    /// withhold a new controller's instruction until the pilot tunes that frequency
    /// and checks in. Same-facility continuations (descend-via-STAR, cleared-approach,
    /// exit-the-runway) play on their own; a change of controller only prompts the
    /// "contact <next> on …" hand-off and then waits.
    private func advanceSemiAutomatic(mapped: ATCState, state: AircraftState) {
        // Hold while the last instruction is unacknowledged, while we're waiting for
        // the pilot to check in on a frequency they were just handed to, or while a
        // go-around is being flown back around the pattern (the pilot drives the
        // re-establishing Approach check-in, which then replays the approach).
        guard !readbackGateClosed, pendingCheckInFacility == nil, !goAroundInProgress else { return }
        // Also hold while Ground is withholding an arrival taxi clearance for the destination
        // surface to load — otherwise this path would advance to `.groundArrival` and issue a
        // generic clearance, defeating the wait. `issueDeferredArrivalTaxiClearanceIfLoaded`
        // (pumped above) issues it and advances the flow once the surface resolves.
        guard pendingArrivalTaxiClearance == nil else { return }

        let target = adjustedAirborneTarget(mapped: mapped, state: state)
        guard isForward(target), target != stateMachine.current else { return }

        let fromFacility = controller(for: stateMachine.current)
        let toFacility = controller(for: target)

        if toFacility == fromFacility {
            // The same controller keeps working the aircraft — issue the call now.
            let previousState = stateMachine.current
            advanceAndPost(to: target, context: buildContext(for: target),
                           announceHandoff: false, automatic: true)
            if previousState != .final, stateMachine.current == .final {
                // The cleared-approach call hands the pilot to Tower; wait for them to
                // tune Tower and check in for the landing clearance.
                announceApproachToTowerHandoff()
                pendingCheckInFacility = .tower
            } else if stateMachine.current == .runwayExit {
                // The exit-the-runway call already tells the pilot to contact Ground;
                // wait for them to tune Ground and check in for the taxi-in.
                pendingCheckInFacility = .ground
            }
        } else {
            // Control passes to a new facility: prompt the hand-off and wait for the
            // pilot to switch frequency and check in before that controller speaks.
            issueAutoHandoff(from: fromFacility, to: toFacility)
            pendingCheckInFacility = toFacility
        }
    }

    /// Issue a stand-alone "contact <next> on …" hand-off (no state-machine advance):
    /// the new controller's instruction follows once the pilot checks in.
    private func issueAutoHandoff(from: ATCFacility, to: ATCFacility) {
        let c = buildContext(for: stateMachine.current)
        post(engine.handoff(cs: c.callsign, from: from, to: to,
                            frequency: frequency(for: to, context: c)), speak: true)
    }

    // MARK: - Readback gate

    /// Whether the automatic flow is currently holding for a pilot read-back. Only
    /// engaged in live mode — the deterministic mock/demo and the unit tests drive
    /// read-backs synchronously and never need the controller to wait.
    private var readbackGateClosed: Bool { awaitingReadback && !settings.mockMode }

    /// Index of a state in the canonical gate-to-gate flow order, used to keep the
    /// automatic flow moving forward only (never bouncing back to an earlier call
    /// while the phase detector flickers near the ground).
    private func flowIndex(of state: ATCState) -> Int? {
        Self.flowOrder.firstIndex(of: state)
    }

    /// Whether advancing to `target` would move the conversation forward (or stay
    /// put). States outside the gate-to-gate order are treated as allowed.
    private func isForward(_ target: ATCState) -> Bool {
        guard let ti = flowIndex(of: target),
              let ci = flowIndex(of: stateMachine.current) else { return true }
        return ti >= ci
    }

    /// Close the gate after an automatic instruction so the next call waits for the
    /// pilot's read-back. When `promptIfIdle` is true the idle re-prompt loop is armed
    /// so an unanswered call is repeated with "how do you read?"; the takeoff
    /// clearance passes false so it holds the gate silently (no runway radio-check).
    private func engageReadbackGate(_ tx: ATCTransmission, promptIfIdle: Bool = true) {
        guard !settings.mockMode else { return }
        awaitingReadback = true
        pendingReadbackTx = tx
        readbackPrompts = 0
        if promptIfIdle { armReadbackTimer() }
    }

    /// Schedule the next idle re-prompt. If the pilot stays silent the controller
    /// repeats the call and asks "how do you read?", up to `readbackMaxPrompts`
    /// times, after which the gate stays closed (the flow does not run away) until
    /// the pilot acts.
    private func armReadbackTimer() {
        readbackTimer?.cancel()
        guard awaitingReadback else { return }
        readbackTimer = Task { @MainActor [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: UInt64(self.readbackRepeatInterval * 1_000_000_000))
            guard !Task.isCancelled, self.awaitingReadback,
                  let tx = self.pendingReadbackTx,
                  self.readbackPrompts < self.readbackMaxPrompts else { return }
            // Stand by while tuned to a human controller: don't nag "how do you read?"
            // over a live controller, but keep the timer alive (re-arm without counting
            // a prompt) so the reminder resumes if the pilot leaves the human frequency
            // before reading back.
            if self.companionStandby {
                self.armReadbackTimer()
                return
            }
            self.readbackPrompts += 1
            self.repeatPendingCall(tx)
            self.armReadbackTimer()
        }
    }

    /// Re-issue the pending controller call with a "how do you read?" tag.
    private func repeatPendingCall(_ tx: ATCTransmission) {
        let cs = engine.callsign(airline: flightPlan.airline,
                                 flightNumber: flightPlan.flightNumber,
                                 fallback: flightPlan.callsign)
        let prefix = "\(cs.display), "
        let display = tx.displayText.hasPrefix(prefix)
            ? "\(tx.displayText) How do you read?"
            : "\(tx.displayText) \(cs.display), how do you read?"
        let spoken = "\(tx.spokenText) How do you read?"
        post(ATCTransmission(sender: .atc, facility: tx.facility,
                             displayText: display, spokenText: spoken), speak: true)
    }

    /// Re-point a closed read-back gate at a freshly posted hand-off so the pilot's
    /// read-back echoes "contacting <next>" (the genuine last message) — and cancel
    /// the idle re-prompt, since a courtesy hand-off must never trigger a "how do you
    /// read?" the way a missed instruction does. Used when an instruction is
    /// immediately followed by a hand-off (e.g. "cleared the ILS … contact Tower").
    private func softenReadbackGate(to tx: ATCTransmission) {
        guard awaitingReadback else { return }
        pendingReadbackTx = tx
        readbackTimer?.cancel()
        readbackTimer = nil
    }

    /// Open the gate once the pilot has responded (any pilot transmission counts as
    /// an acknowledgement) so the automatic flow can resume.
    private func clearReadbackGate() {
        guard awaitingReadback else { return }
        awaitingReadback = false
        pendingReadbackTx = nil
        readbackPrompts = 0
        readbackTimer?.cancel()
        readbackTimer = nil
    }

    // MARK: - Manual frequency tuning

    /// Facilities the pilot can tune to with a button, in the order they're worked
    /// across a normal flight. The same Ground/Tower button serves both the
    /// departure and the arrival visit — checking in advances to whichever call
    /// lies ahead of the current state for that facility.
    static let tunableFacilities: [ATCFacility] =
        [.clearance, .ground, .tower, .departure, .center, .approach]

    /// Canonical gate-to-gate order of ATC states, used to pick the next call a
    /// manually-tuned facility should issue.
    private static let flowOrder: [ATCState] = [
        .clearance, .pushback, .engineStart, .groundTaxi, .lineUpWait,
        .towerDeparture, .initialClimb, .departure, .climb, .cruise, .descent,
        .approach, .final, .landing, .runwayExit, .groundArrival, .parked
    ]

    /// The frequency (MHz) a facility is reached on, for display on its button.
    func frequency(for facility: ATCFacility) -> Double {
        frequency(for: facility, context: buildContext(for: stateMachine.current))
    }

    /// Formatted frequency for a facility's tune button, e.g. "118.300".
    func frequencyText(for facility: ATCFacility) -> String {
        String(format: "%.3f", frequency(for: facility))
    }

    /// Whether the given facility still has a call ahead in the flight, so its
    /// button is live (e.g. Departure is dimmed once enroute with Center).
    func canTune(_ facility: ATCFacility) -> Bool {
        guard let next = nextState(workedBy: facility, after: stateMachine.current) else { return false }
        return next != stateMachine.current
    }

    /// The next state worked by `facility`, searching forward from the current
    /// state. When nothing lies ahead, returns the last state this facility works
    /// (e.g. Center for a further descent) so a re-tap still issues a sensible call.
    private func nextState(workedBy facility: ATCFacility, after current: ATCState) -> ATCState? {
        let order = Self.flowOrder
        let start = order.firstIndex(of: current).map { $0 + 1 } ?? 0
        if let ahead = order[start...].first(where: { controller(for: $0) == facility }) {
            return ahead
        }
        return order.last { controller(for: $0) == facility }
    }

    /// The next controller (different from the one currently tuned) the flight will
    /// be handed to, searching forward through the gate-to-gate order from `current`.
    /// Nil when no later state is worked by a different facility. Used to decide which
    /// frequency buttons are worth surfacing now.
    private func nextDistinctFacility(after current: ATCState) -> ATCFacility? {
        let order = Self.flowOrder
        guard let idx = order.firstIndex(of: current) else { return nil }
        for state in order[(idx + 1)...] {
            let facility = controller(for: state)
            if facility != currentFacility { return facility }
        }
        return nil
    }

    /// Manually tune the radio to a facility. Switching frequency does **not**
    /// check in or advance the conversation on its own: it only moves the radio to
    /// the new controller. From here the pilot either taps Check In to call up the
    /// controller (and get the next instruction) or makes a specific request — e.g.
    /// requesting pushback after Clearance hands you to Ground. This is how the
    /// pilot drives the flight forward without calls auto-playing back-to-back.
    func tuneTo(_ facility: ATCFacility) {
        // Tuning is always allowed — even while standing by for a human controller.
        // Moving the radio is how the pilot leaves a staffed frequency to lift the
        // guard (and how they tune onto one to re-engage it); it never transmits.
        // Moving to a controller/ramp frequency also leaves the ATIS frequency, so the
        // ATIS tune button drops out of the grid for this phase.
        leaveATISFrequency()
        manualTuning = true
        tunedFacility = facility
        currentFacility = facility
        // No transcript, no state change, and no mock-phase change: tuning only
        // moves the radio. The mock display advances with the conversation when the
        // pilot checks in or makes a request.
        persistSession()
    }

    /// Make the Ramp call once tuned to the Ramp frequency. Context-aware: before
    /// departure it contacts Ramp for the pushback (Ramp approves the push; Ground
    /// then handles the taxi). On arrival it contacts Ramp for the taxi-in to the
    /// gate. Reached from the "To Gate" response button on arrival (pre-departure the
    /// pilot uses the Pushback button); tuning to Ramp itself never transmits. It is
    /// never the arrival routine during departure (which previously jumped straight to
    /// "parked, flight complete").
    func contactRamp() {
        guard !companionStandby else { return }
        if isArrivalRamp {
            arriveAtGate()
        } else if isPreDeparture,
                  [.notConnected, .connectedIdle, .clearance, .pushback].contains(stateMachine.current) {
            // Only at/around the pushback point — a late tap (after engine start/taxi)
            // must not rewind the flow; the pilot uses Ground for the taxi from there.
            requestPushback()
        }
    }

    /// Arrival Ramp hand-off: the aircraft is clear of the runway and taxiing in, so
    /// Ramp routes it to the gate. This does NOT announce the block-in — the arrival
    /// is only declared complete once the aircraft actually stops at the gate with
    /// the parking brake set (watched in `handle(state:)`).
    func arriveAtGate() {
        guard !companionStandby else { return }
        guard !arrivalAnnounced else { return }
        manualTuning = true
        hasDeparted = true
        currentFacility = .ramp
        let c = buildContext(for: .groundArrival, arrivalOverride: true)
        // Simulated arrival Ramp (local/company, non-FAA): the pilot checks in
        // inbound and Ramp gives a non-movement-area routing to the gate.
        if c.rampProfile.rampType != .none {
            postPilot(rampEngine.arrivalInbound(cs: c.callsign, gate: c.gate))
            post(rampEngine.proceedToGate(cs: c.callsign, gate: c.gate,
                                          via: c.rampProfile.arrivalRampEntryPhrase.contains("inner")
                                               ? "the inner alley" : "the ramp"), speak: true)
        }
        // Now monitor for the actual gate stop; the staged "monitor ramp" then the
        // block-in fire from telemetry as the aircraft slows and parks, not here.
        gateMonitored = false
        awaitingGateArrival = true
        atcState = .groundArrival
        // Remember where the gate is (from the taxi map), so the block-in only fires once the
        // aircraft is actually parked at the gate — a parking brake set out on a taxiway must
        // not end the flight.
        arrivalGatePosition = airportSurface.arrivalGateCoordinate
        // The taxi map is deliberately kept visible after the Ramp-frequency switch so the
        // pilot can still see the route in to the gate. It is retired at the detected end of
        // flight instead — the `$atcState` sink hides it when the aircraft reaches `.parked`
        // (completeGateArrival, once actually stopped at the gate with the brake set).
        // In mock mode advance the scripted aircraft to the gate so the monitored
        // block-in plays out (the brake is set in the parked mock state).
        if settings.mockMode { mock.setPhase(.parked) }
    }

    /// Whether the pilot has the Ramp frequency selected — their deliberate "I'm at the
    /// gate" signal. Combined with a full stop and the parking brake, this is what ends the
    /// flight, and it works with or without accurate taxi-map data.
    private var isTunedToRamp: Bool {
        currentFacility == .ramp || tunedFacility == .ramp
    }

    /// Whether the aircraft is genuinely parked at the gate, ready to complete the flight:
    /// stopped on the ground with the parking brake set (falling back to a full ground stop
    /// when the sim does not expose the brake) **and** tuned to the Ramp frequency. The
    /// Ramp requirement is the map-independent gate: a stop out on a taxiway — before the
    /// pilot has contacted Ramp for the gate — never ends the flight, whether or not the
    /// taxi map has usable data. When the taxi map additionally resolved the gate position
    /// (live), the aircraft must also be within `gateArrivalRadiusMeters` of it; otherwise
    /// the Ramp-frequency + parking-brake check stands. Mock Mode is a scripted demo, so it
    /// isn't gated on the pilot tuning Ramp.
    private func isParkedAtGate(_ s: AircraftState) -> Bool {
        let stopped = (s.onGround ?? true) && (s.groundSpeed ?? 0) < 1
        let parked = s.parkingBrakeSet.map { stopped && $0 } ?? stopped
        guard parked else { return false }
        guard settings.mockMode || isTunedToRamp else { return false }
        if let gate = arrivalGatePosition, let pos = s.coordinate {
            return SurfaceGeometry.distanceMeters(pos, gate) <= Self.gateArrivalRadiusMeters
        }
        return true
    }

    /// How close (meters) to the gate the aircraft must be — with the parking brake set —
    /// before the arrival is declared complete. Generous enough to absorb the offset
    /// between the OSM stand and the Infinite Flight scenery, tight enough to exclude a
    /// parking-brake stop out on an active taxiway.
    private static let gateArrivalRadiusMeters = 80.0

    /// Finish the monitored arrival once stopped at the gate: block-in on Ramp and
    /// the "flight complete" advisory, then settle into the parked state.
    private func completeGateArrival() {
        awaitingGateArrival = false
        arrivalGatePosition = nil
        let c = buildContext(for: .parked, arrivalOverride: true)
        advanceAndPost(to: .parked, context: c, announceHandoff: false)
        if !arrivalAnnounced { announceArrival(); arrivalAnnounced = true }
    }

    /// Representative physical phase for an ATC state, so the mock telemetry display
    /// (altitude, position, on-ground) stays believable while tuning manually.
    private func mockPhase(for state: ATCState) -> FlightPhase {
        switch state {
        case .clearance, .pushback, .engineStart: return .preflight
        case .pushbackTaxi, .groundTaxi, .runwayCrossing, .holdingShort, .lineUpWait: return .taxiOut
        case .towerDeparture: return .takeoff
        case .initialClimb, .departure: return .initialClimb
        case .climb: return .climb
        case .center, .cruise: return .cruise
        case .topOfDescent, .descent: return .descent
        case .approach, .final: return .approach
        case .landing, .runwayExit: return .landing
        case .groundArrival: return .taxiIn
        case .parked: return .parked
        case .notConnected, .connectedIdle, .abnormal: return phase
        }
    }

    /// Adjust the telemetry-mapped airborne state for real-ATC realism: keep
    /// Departure working the climb until the TRACON ceiling, and insert the
    /// cleared-approach / cleared-to-land steps when established on final.
    private func adjustedAirborneTarget(mapped: ATCState, state: AircraftState) -> ATCState {
        let alt = state.altitudeMSL ?? 0
        let ceiling = Double(currentTraconCeiling)
        let onGround = state.onGround ?? false
        let runway = buildContext(for: stateMachine.current).runway

        // Hold Tower → Departure until the aircraft is through ~2,000 ft AGL. Handing
        // off the instant the wheels leave the ground clears the pilot "direct" to the
        // first filed fix while it's still the runway-end waypoint just ahead, and
        // stacks the departure call right on top of the takeoff clearance — so wait for
        // the climb to carry past it first. When Infinite Flight does not expose AGL,
        // fall back to the altitude gained since the takeoff clearance was issued.
        if stateMachine.current == .towerDeparture, mapped == .initialClimb || mapped == .climb {
            // Prefer live AGL; otherwise estimate it against the field elevation
            // captured on the ground (falling back to the liftoff altitude), so the
            // ~2,000 ft trigger is height above the field, not above sea level.
            let groundRef = departureFieldElevationMSL > 0 ? departureFieldElevationMSL : liftoffAltitudeMSL
            let agl = state.altitudeAGL ?? max(0, (state.altitudeMSL ?? 0) - groundRef)
            if agl < 2000 { return .towerDeparture }
        }

        // Departure hands off to Center 1,000 ft below the TRACON ceiling (17,000 ft
        // for a FL180 ceiling) rather than right at it. That buffer gives the pilot
        // time to check in with Center and be cleared to the next altitude before the
        // climb reaches the ceiling, so it continues past FL180 without pausing.
        if mapped == .climb, alt < ceiling - 1000,
           [.towerDeparture, .initialClimb, .departure].contains(stateMachine.current) {
            return .initialClimb
        }

        // Top of descent: leaving cruise, Center issues the descend-via-STAR
        // (or plain descend) first, before any Approach hand-off.
        if [.cruise, .center, .topOfDescent].contains(stateMachine.current),
           mapped == .descent || mapped == .approach {
            return .descent
        }

        // Descending through the TRACON ceiling (FL180), or arriving in the
        // terminal area, Center hands the aircraft to Approach.
        if stateMachine.current == .descent, mapped == .descent || mapped == .approach {
            return (mapped == .approach || alt < ceiling - 200) ? .approach : .descent
        }

        // Approach clears the approach once the aircraft is established — autopilot
        // approach mode (APPR) engaged or lined up on final and wings level — before
        // the Tower hand-off. This must follow the "descend, expect approach" call.
        if stateMachine.current == .approach, isEstablishedOnApproach(state, runway: runway) {
            return .final
        }
        // Cleared to land (Tower) on short final or at touchdown.
        if stateMachine.current == .final, onGround || isOnShortFinal(state) {
            return .landing
        }
        // After touchdown, Tower instructs to exit the runway and contact Ground.
        if stateMachine.current == .landing, onGround {
            return .runwayExit
        }
        // Once clear of the runway / at taxi speed, switch to Ground and taxi in.
        if stateMachine.current == .runwayExit {
            let gs = state.groundSpeed ?? 0
            return (onGround && gs < 40) ? .groundArrival : .runwayExit
        }
        return mapped
    }

    /// TRACON ceiling in feet (where Departure hands off to Center).
    private var currentTraconCeiling: Int {
        settings.traconCeilingFL > 0 ? settings.traconCeilingFL * 100 : 18000
    }

    /// Whether the aircraft is established on the approach: the autopilot approach
    /// mode (APPR) is engaged, or it is lined up on final with the runway. Read from
    /// Infinite Flight telemetry (mock feed simulates APPR on the approach phase).
    private func isEstablishedOnApproach(_ s: AircraftState, runway: String) -> Bool {
        guard !(s.onGround ?? false) else { return false }
        if s.approachModeEngaged == true { return true }
        guard lineupDetector.isOnFinalApproach(state: s, runway: runway) else { return false }
        // Lined up with the runway and not still turning onto final (wings roughly
        // level). Bank reaches here in signed degrees whichever units the sim reports it
        // in — `IFConnectStateReader` settles that with the rest of the snapshot's angles.
        if let bank = s.bankAngle, abs(bank) > 6 { return false }
        return true
    }

    /// Whether the aircraft is on short final (airborne, low, descending).
    private func isOnShortFinal(_ s: AircraftState) -> Bool {
        guard !(s.onGround ?? false) else { return false }
        let agl = s.altitudeAGL ?? (s.altitudeMSL ?? 0)
        let vs = s.verticalSpeed ?? 0
        return agl < 1500 && vs < -100
    }

    /// After the approach is cleared (aircraft established), Approach hands the
    /// pilot off to Tower for the landing clearance.
    private func announceApproachToTowerHandoff() {
        let c = buildContext(for: .final)
        let tx = engine.handoff(cs: c.callsign, from: .approach, to: .tower,
                                frequency: c.towerFrequency)
        post(tx, speak: true)
        // This hand-off follows the cleared-approach call that just closed the gate.
        // Re-aim the gate at the hand-off so the pilot reads back "contacting Tower"
        // (the last message) and the controller does not nag "how do you read?".
        softenReadbackGate(to: tx)
    }

    /// Arrival block-in once stopped at the gate. Emits the simulated Ramp
    /// (local/company, non-FAA) block-in call followed by a System "flight
    /// complete" advisory.
    private func announceArrival() {
        let c = buildContext(for: .parked, arrivalOverride: true)
        let display = "\(c.callsign.display) parked\(c.gate.isEmpty ? "" : " at \(c.gate)"). Flight complete."
        let spoken = "\(c.callsign.spoken) parked. Flight complete."
        post(ATCTransmission(sender: .system, facility: .ramp, displayText: display, spokenText: spoken), speak: false)
        // The flight is over (arrived and parked) — a calm, positive moment. Count it
        // toward the engagement gate and ask for an App Store rating if appropriate.
        // `announceArrival` fires exactly once per completed flight (guarded by
        // `arrivalAnnounced`), so this neither double-counts nor prompts mid-flight.
        review.recordFlightCompleted()
        review.requestReviewIfAppropriate(.afterFlightComplete)
    }

    // MARK: - Live flight plan

    /// Apply the manually-entered callsign to the active flight plan without
    /// rebuilding it from settings (so a live-read route is preserved). Infinite
    /// Flight's Connect API exposes no callsign for the user's own aircraft, so the
    /// pilot sets it here and it becomes the source of truth.
    func applyManualCallsign() {
        let trimmed = settings.callsign.trimmingCharacters(in: .whitespacesAndNewlines)
        flightPlan.callsign = trimmed

        // An empty field clears only the callsign — leave any airline/flight number
        // already supplied by the mock feed, a live read, or the Airline/Flight #
        // overrides untouched, so blurring an empty field never wipes them.
        guard !trimmed.isEmpty else {
            diagnostics.log(.app, "Callsign cleared.")
            return
        }
        // Derive an airline/flight number from the callsign only when the pilot has
        // not pinned those separately. Resolve an airline prefix (e.g. "UA598" /
        // "UAL598" -> United 598) so the companion uses the proper telephony name
        // instead of spelling it out.
        guard settings.airline.isEmpty, settings.flightNumber.isEmpty else { return }
        if let parsed = AirlineDatabase.parse(trimmed) {
            flightPlan.airline = parsed.telephony
            flightPlan.flightNumber = parsed.flightNumber
            diagnostics.log(.app, "Callsign \(trimmed) resolved to \(parsed.telephony) \(parsed.flightNumber).")
        } else {
            flightPlan.airline = ""
            flightPlan.flightNumber = ""
            diagnostics.log(.app, "Callsign set to \(trimmed).")
        }
    }

    /// Push the departure/arrival gate fields (edited on the ATC card or in the
    /// Flight overrides) into the active plan. Infinite Flight never supplies gates,
    /// so this simply mirrors the settings values — like `applyManualCallsign`, it
    /// avoids a full re-sync so nothing else in the plan is disturbed.
    func applyManualGates() {
        flightPlan.departureGate = settings.departureGate.trimmingCharacters(in: .whitespacesAndNewlines)
        flightPlan.arrivalGate = settings.arrivalGate.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Swap the departure and arrival gate fields — handy when the pilot is flying
    /// the return leg and the two gates simply trade places.
    func swapManualGates() {
        let dep = settings.departureGate
        settings.departureGate = settings.arrivalGate
        settings.arrivalGate = dep
        applyManualGates()
    }

    /// Merge a flight plan read from Infinite Flight into the active plan. Manual
    /// overrides win for the fields the pilot can actually type; otherwise empty fields
    /// are filled from the live plan. The waypoint list is not one of those fields, so it
    /// always follows Infinite Flight.
    private func mergeLiveFlightPlan(_ live: FlightPlan) {
        var plan = flightPlan
        let manual = plan.manualOverride
        let before = (plan.departure, plan.destination)
        let beforeWaypoints = plan.waypoints.map(\.name)

        // For each field: take the live value when the pilot has not pinned it with
        // a manual override, or when the field is still empty.
        func fill(_ keep: inout String, _ value: String) {
            guard !value.isEmpty else { return }
            if !manual || keep.isEmpty { keep = value }
        }

        fill(&plan.departure, live.departure)
        fill(&plan.destination, live.destination)
        fill(&plan.departureRunway, live.departureRunway)
        fill(&plan.arrivalRunway, live.arrivalRunway)
        fill(&plan.sid, live.sid)
        fill(&plan.star, live.star)
        fill(&plan.approach, live.approach)
        // The route itself is never a manual override — there is no field to type a fix
        // list into, so `manualOverride` (which pins the endpoints/procedures the pilot
        // *did* type) must not freeze it. Pinning it used to mean a route edited in
        // Infinite Flight mid-flight — adding the approach after receiving the ATIS is
        // the usual case — never reached the app, and "Refresh from Infinite Flight"
        // couldn't dislodge it either. The live fix list always wins.
        if !live.waypoints.isEmpty {
            // A momentarily degraded read (e.g. the detailed `full_info` state missing on
            // one poll, so only the coordinate-less route string parses) must not strip the
            // positions off a route the app already has: carry the known coordinate for any
            // same-named fix the live list didn't locate. A fix never moves, so this can
            // only ever restore the right position.
            var incoming = live.waypoints
            if incoming.contains(where: { $0.coordinate == nil }) {
                var known: [String: CLLocationCoordinate2D] = [:]
                for wp in plan.waypoints {
                    if let coord = wp.coordinate, known[wp.name] == nil { known[wp.name] = coord }
                }
                for i in incoming.indices where incoming[i].coordinate == nil {
                    if let coord = known[incoming[i].name] {
                        incoming[i].latitude = coord.latitude
                        incoming[i].longitude = coord.longitude
                    }
                }
            }
            plan.waypoints = incoming
            // The SID's fix structure belongs to the same live parse as the waypoints,
            // so it travels with them — the initial departure heading targets the SID's
            // own first fix rather than a buffer fix filed ahead of it.
            plan.sidFixNames = live.sidFixNames
            // So does the approach's first fix: it names an entry in the very list just
            // adopted, and is what caps how deep a weather deviation may rejoin the route.
            // Left unmerged it stayed empty forever and the cap never applied.
            plan.approachStartFixName = live.approachStartFixName
        }
        // Carry the endpoint coordinates Infinite Flight reports for the fields (they
        // place the departure/destination markers on the real field even when it's
        // outside the built-in coordinate database). Only sync when the endpoint ICAO
        // matches the live plan — so a manual override to a different field never
        // inherits the wrong position. Prefer a live value; keep a previously captured
        // one when a live read momentarily lacks it (so the marker doesn't blink to the
        // enroute-fix fallback), but clear it when the field itself changed.
        if plan.departure == live.departure {
            if let lat = live.departureLatitude, let lon = live.departureLongitude {
                plan.departureLatitude = lat
                plan.departureLongitude = lon
            } else if plan.departure != before.0 {
                plan.departureLatitude = nil
                plan.departureLongitude = nil
            }
        }
        // The departure *runway's* own coordinate travels the same way, and matters more than
        // the field's: it is the origin the initial departure heading is measured from, so a
        // stale one points the takeoff vector off a runway the aircraft isn't on. Kept through
        // a momentarily degraded read (a route-string-only parse carries no coordinates at
        // all), but dropped the moment the plan names a different departure runway or field —
        // there is no such thing as "roughly the right runway" at a hub with five of them.
        if plan.departure == live.departure {
            if let lat = live.departureRunwayLatitude, let lon = live.departureRunwayLongitude {
                plan.departureRunwayLatitude = lat
                plan.departureRunwayLongitude = lon
            } else if (!live.departureRunway.isEmpty && plan.departureRunway != live.departureRunway)
                        || plan.departure != before.0 {
                plan.departureRunwayLatitude = nil
                plan.departureRunwayLongitude = nil
            }
        } else {
            plan.departureRunwayLatitude = nil
            plan.departureRunwayLongitude = nil
        }
        if plan.destination == live.destination {
            if let lat = live.destinationLatitude, let lon = live.destinationLongitude {
                plan.destinationLatitude = lat
                plan.destinationLongitude = lon
            } else if plan.destination != before.1 {
                plan.destinationLatitude = nil
                plan.destinationLongitude = nil
            }
        }
        // Cruise altitude (from the plan's TOC / highest planned level).
        if (!manual || plan.cruiseAltitude <= 0), live.cruiseAltitude > 0 {
            plan.cruiseAltitude = live.cruiseAltitude
        }
        // Approach intercept altitude (first altitude in the approach section).
        if (!manual || plan.approachInterceptAltitude <= 0), live.approachInterceptAltitude > 0 {
            plan.approachInterceptAltitude = live.approachInterceptAltitude
        }
        flightPlan = plan

        // Refresh weather (re-fetch + re-sample the corridor) when the endpoints changed.
        // When only the *route* (waypoints) changed, the endpoints-triggered refresh
        // doesn't fire, so force the radar to re-sample the new corridor on the next tick
        // — otherwise the mint-line geometry re-runs against cells still covering the old
        // route. Detection itself re-runs every telemetry tick, so no explicit recompute
        // is needed here.
        // Drop ATIS state for an endpoint that changed, so a new origin/destination
        // never inherits the previous field's ATIS or reported information code. The
        // weather refresh below re-fetches ATIS for the new endpoints.
        if before.0 != plan.departure { departureATIS = nil; reportedDepartureInfo = nil }
        if before.1 != plan.destination {
            arrivalATIS = nil; reportedArrivalInfo = nil; lastArrivalATISAttempt = nil
        }
        if before != (plan.departure, plan.destination) {
            // The corridor moved: the current cells are stale, so hold the deviation lock
            // until the fresh sample lands rather than re-locking against the old route.
            livePrecipCellsReady = false
            Task { await refreshWeather() }
            // New endpoints from Infinite Flight — cache both airports' surfaces now.
            prefetchAirportSurfaces()
        } else if plan.waypoints.map(\.name) != beforeWaypoints {
            lastPrecipSampleAt = nil
            livePrecipCellsReady = false
        }
        // Record the route the *app* ended up with whenever it changes. Paired with the
        // "Flight plan fixes" line the reader logs for the parse, this pins down which
        // side dropped a fix when a route in the app doesn't match the one in the sim.
        if plan.waypoints.map(\.name) != beforeWaypoints {
            diagnostics.log(.app, "Active route now \(plan.waypoints.count) fixes: "
                + plan.waypoints.map(\.name).joined(separator: "→"))
        }
    }

    /// Round an altitude up to the next whole thousand feet (e.g. 8,434 → 9,000).
    /// Exact multiples are left unchanged.
    nonisolated static func roundedUpToThousand(_ feet: Int) -> Int {
        guard feet > 0 else { return 0 }
        return ((feet + 999) / 1000) * 1000
    }

    /// Best live estimate of the field elevation (ft MSL) beneath the aircraft:
    /// ground ≈ current MSL − current AGL. Used near the destination to make the
    /// approach altitude elevation-aware. nil when telemetry lacks either value.
    private func liveFieldElevationMSL() -> Int? {
        guard let msl = aircraftState.altitudeMSL, let agl = aircraftState.altitudeAGL else { return nil }
        return max(0, Int((msl - agl).rounded()))
    }

    private func updateAssignedAltitude(for state: ATCState, context c: ATCContext) {
        switch state {
        case .clearance: assignedAltitude = c.initialClimbAltitude
        case .towerDeparture: assignedAltitude = c.initialClimbAltitude
        case .initialClimb, .departure:
            assignedAltitude = c.traconCeiling > 0 ? c.traconCeiling : max(c.assignedAltitude, c.initialClimbAltitude)
        case .climb, .cruise: assignedAltitude = c.cruiseAltitude
        case .descent: assignedAltitude = ATCStateMachine.descentTargetAltitude(context: c)
        case .approach: assignedAltitude = c.approachInterceptAltitude > 0 ? c.approachInterceptAltitude : c.approachDefaultAltitude
        default: break
        }
    }

    // MARK: - Transcript

    private func post(_ tx: ATCTransmission, speak: Bool) {
        transcript.append(tx)
        if transcript.count > 200 { transcript.removeFirst(transcript.count - 200) }
        if tx.sender == .atc {
            latestTransmission = tx
            lastATCTransmission = tx
        }
        // The first real controller/pilot exchange starts the ambient chatter — background
        // traffic only comes up once the pilot is working ATC (an ATIS broadcast never counts).
        if !atcCommunicationStarted, tx.isControllerExchange {
            atcCommunicationStarted = true
            updateChatterRunState()
        }
        diagnostics.log(.atc, "[\(tx.sender.rawValue.uppercased())] \(tx.displayText)")
        if speak { speech.speak(tx) }
        // A new transcript line is a meaningful change — capture it immediately so a
        // button-driven call (which doesn't go through the telemetry loop) is saved.
        persistSession()
        // A new call (or read-back) is a meaningful change — push it to the notification.
        refreshLiveActivity(force: tx.sender == .atc)
    }

    /// Post a pilot transmission, speaking it aloud when appropriate. The pilot
    /// voice is heard for button/text-driven calls; push-to-talk input is never
    /// re-spoken because the user already said it.
    private func postPilot(_ tx: ATCTransmission) {
        // Any pilot transmission (read-back, request, check-in, PTT) acknowledges
        // the controller and releases the read-back gate so the flow can resume.
        clearReadbackGate()
        post(tx, speak: shouldSpeakPilot)
    }

    /// Whether the pilot's next transmission should be spoken aloud.
    private var shouldSpeakPilot: Bool { settings.speakPilot && !pilotInputViaVoice }

    /// True while handling a push-to-talk utterance, so the resulting pilot
    /// transmission is not spoken back over the user.
    private var pilotInputViaVoice = false

    // MARK: - Session persistence

    /// Build a snapshot of the live ATC session for persistence.
    private func currentSnapshot() -> SessionSnapshot {
        SessionSnapshot(
            atcState: atcState,
            stateMachineCurrent: stateMachine.current,
            currentFacility: currentFacility,
            phase: phase,
            assignedAltitude: assignedAltitude,
            hasDeparted: hasDeparted,
            arrivalAnnounced: arrivalAnnounced,
            awaitingGateArrival: awaitingGateArrival,
            manualTuning: manualTuning,
            monitoringTower: monitoringTower,
            weatherDeviation: weatherDeviation,
            reportedDepartureInfo: reportedDepartureInfo,
            reportedArrivalInfo: reportedArrivalInfo,
            departureInfoAppended: departureInfoAppended,
            arrivalInfoAppended: arrivalInfoAppended,
            departureATISDismissed: departureATISDismissed,
            arrivalATISDismissed: arrivalATISDismissed,
            transcript: transcript,
            departure: flightPlan.departure,
            destination: flightPlan.destination,
            mockMode: settings.mockMode,
            savedAt: Date())
    }

    /// Persist the current session when something meaningful changed (or the
    /// heartbeat is due, so `savedAt` stays fresh through long quiet phases). Only
    /// live sessions are saved — the mock feed is a deterministic demo that always
    /// starts fresh.
    private func persistSession() {
        guard !settings.mockMode else { return }
        let sig = [stateMachine.current.rawValue, atcState.rawValue, currentFacility.rawValue,
                   phase.rawValue, String(assignedAltitude), String(hasDeparted),
                   String(arrivalAnnounced), String(awaitingGateArrival), String(manualTuning),
                   String(monitoringTower),
                   weatherDeviation.state.rawValue, String(transcript.count),
                   reportedDepartureInfo ?? "-", reportedArrivalInfo ?? "-"].joined(separator: "|")
        let now = Date()
        let heartbeatDue = lastPersistedAt.map { now.timeIntervalSince($0) >= persistHeartbeat } ?? true
        guard sig != lastPersistSignature || heartbeatDue else { return }
        lastPersistSignature = sig
        lastPersistedAt = now
        sessionStore.save(currentSnapshot())
    }

    /// Restore a recent in-progress session, if one was saved, so a reconnect or
    /// relaunch resumes where the flight left off. Returns whether a session was
    /// restored. Never restores in mock mode or from a mock snapshot.
    @discardableResult
    private func restoreSession() -> Bool {
        guard !settings.mockMode,
              let snap = sessionStore.loadResumable(),
              !snap.mockMode else { return false }
        // Decide the taxi-map restore before applying the snapshot: `apply` re-fires the
        // arrival-taxi setup via the `$atcState` observer, which would flip the
        // coordinator off `.none` and defeat the "genuine relaunch only" guard.
        armTaxiMapRestore(from: snap)
        apply(snapshot: snap)
        diagnostics.log(.app, "Resumed session at \(snap.atcState.title) "
            + "(\(snap.transcript.count) messages) — picking up where the flight left off.")
        return true
    }

    /// Apply a snapshot's state to the live model so the conversation resumes from it.
    private func apply(snapshot snap: SessionSnapshot) {
        stateMachine.restore(to: snap.stateMachineCurrent)
        atcState = snap.atcState
        currentFacility = snap.currentFacility
        phase = snap.phase
        assignedAltitude = snap.assignedAltitude
        hasDeparted = snap.hasDeparted
        arrivalAnnounced = snap.arrivalAnnounced
        awaitingGateArrival = snap.awaitingGateArrival
        manualTuning = snap.manualTuning
        monitoringTower = snap.monitoringTower ?? false
        // Resume an in-progress weather diversion so the deviation card (and its
        // "clear of weather" button) survives the reconnect. A subsequent telemetry
        // tick may still clear a non-committed lifecycle if the weather is gone, but
        // a committed diversion stays put until the pilot reports clear of weather.
        if let deviation = snap.weatherDeviation { weatherDeviation = deviation }
        // Restore the ATIS information the pilot had already received so a reconnect
        // keeps appending "information X" to the taxi request / approach check-in.
        reportedDepartureInfo = snap.reportedDepartureInfo
        reportedArrivalInfo = snap.reportedArrivalInfo
        departureInfoAppended = snap.departureInfoAppended ?? false
        arrivalInfoAppended = snap.arrivalInfoAppended ?? false
        departureATISDismissed = snap.departureATISDismissed ?? false
        arrivalATISDismissed = snap.arrivalATISDismissed ?? false
        if !snap.transcript.isEmpty {
            transcript = snap.transcript
            let lastATC = snap.transcript.last { $0.sender == .atc }
            latestTransmission = lastATC
            lastATCTransmission = lastATC
        }
        // A resumed flight has already been talking to ATC if its transcript holds any
        // controller/pilot exchange — keep the ambient chatter going rather than waiting for
        // the next call.
        atcCommunicationStarted = transcript.contains { $0.isControllerExchange }
    }

    /// If the restored session was mid-taxi, arm the taxi-map restore. The map itself is
    /// re-established on the first real telemetry fix (`performPendingTaxiMapRestore`), not
    /// here, because the aircraft's live position isn't known yet — restoring now would
    /// route from the airport reference and immediately read as "off route". Departure taxi
    /// runs from the taxi read-back until the Ground→Tower hand-off (`.groundTaxi`);
    /// arrival taxi-to-gate runs from the taxi-in read-back until parked (`.groundArrival`).
    private func armTaxiMapRestore(from snap: SessionSnapshot) {
        guard !settings.mockMode else { return }
        // Only a genuine relaunch needs restoring. On a background→foreground reconnect the
        // app was never torn down, so the coordinator still holds the live taxi (and its
        // crossing progress) — re-beginning it would needlessly reset that. A fresh launch
        // has an idle coordinator (`kind == .none`).
        guard airportSurface.kind == .none else { pendingTaxiMapRestore = .none; return }
        if snap.stateMachineCurrent == .groundTaxi, !snap.hasDeparted {
            pendingTaxiMapRestore = .departure
        } else if snap.atcState == .groundArrival || snap.stateMachineCurrent == .groundArrival {
            // Only the gate-bound arrival taxi drives a map; a generic "taxi to parking"
            // with no entered gate has nothing to route to (mirrors the arrival-taxi gate
            // check on the normal path).
            pendingTaxiMapRestore = hasArrivalGate ? .arrival : .none
        } else {
            pendingTaxiMapRestore = .none
        }
    }

    /// Re-establish the taxi map after a relaunch, once a real telemetry fix is in hand so
    /// the route starts from the aircraft's actual position. Re-runs the same taxi setup
    /// the pilot's request/hand-off would, then reveals the map without waiting for a fresh
    /// read-back (the clearance was already read back before the app was closed).
    private func performPendingTaxiMapRestore(for state: AircraftState) {
        let kind = pendingTaxiMapRestore
        guard kind != .none else { return }
        // Give up if the aircraft is clearly no longer taxiing (e.g. it departed while the
        // app was away) so a stale snapshot can't resurrect the map in the air.
        guard state.onGround != false else { pendingTaxiMapRestore = .none; return }
        guard let coord = state.coordinate, coord.isValid else { return }
        pendingTaxiMapRestore = .none
        switch kind {
        case .departure: prepareDepartureTaxi()
        case .arrival:   prepareArrivalTaxi()
        case .none:      return
        }
        airportSurface.resumeTaxiAfterRelaunch()
        diagnostics.log(.app, "Restored the \(kind == .departure ? "departure" : "arrival") taxi map after relaunch.")
    }

    /// Forget any saved session so the next connect starts fresh (used when the
    /// pilot deliberately clears the flight or resets app data).
    private func clearSavedSession() {
        sessionStore.clear()
        lastPersistSignature = nil
        lastPersistedAt = nil
    }

    // MARK: - Context

    private func buildContext(for state: ATCState, arrivalOverride: Bool? = nil) -> ATCContext {
        let cs = engine.callsign(airline: flightPlan.airline,
                                 flightNumber: flightPlan.flightNumber,
                                 fallback: flightPlan.callsign)
        // Requesting an approach / vectors is inherently an arrival action, so callers
        // can force the arrival side even if the conversational state hasn't caught up.
        let arrival = arrivalOverride
            ?? [.descent, .approach, .final, .landing, .runwayExit, .groundArrival].contains(state)
        let metar = arrival ? destinationMETAR : departureMETAR
        let windDir = metar?.windDirection ?? 270
        let windSpeed = metar?.windSpeed ?? 8
        let cruise = flightPlan.cruiseAltitude > 0 ? flightPlan.cruiseAltitude : 37000

        // Parse any entered published procedures (deterministic, best-effort).
        let sidProc = flightPlan.sid.isEmpty ? nil
            : ProcedureParser.parseSID(flightPlan.sid, icao: flightPlan.departure)
        let starProc = flightPlan.star.isEmpty ? nil
            : ProcedureParser.parseSTAR(flightPlan.star, icao: flightPlan.destination)
        let approachProc = flightPlan.approach.isEmpty ? nil
            : ProcedureParser.parseApproach(flightPlan.approach)

        let resolved = resolvedRunway(windDir: windDir, windSpeed: windSpeed, arrival: arrival,
                                      approach: approachProc)
        let runway = resolved.ident
        let taxiPlan = taxiPlanner.plan(airport: arrival ? flightPlan.destination : flightPlan.departure,
                                        runway: runway, arrival: arrival)
        let approachName = approachProc?.displayName
            ?? (flightPlan.approach.isEmpty ? "the ILS" : flightPlan.approach)

        // Initial departure heading: the bearing the aircraft must fly off the runway to reach
        // the first fix of the departure. Measured, in order of preference, from
        //
        //  1. **the departure runway itself** — the `DPT RW26L` marker SimBrief files and
        //     Infinite Flight locates at the runway end. This is the point the departure leg is
        //     actually flown from, and it is what the aircraft's own FMS measures the leg from,
        //     so it is the number the pilot can check the clearance against.
        //  2. the live on-ground position, when the plan names no located departure runway.
        //  3. the departure field reference, with no telemetry at all.
        //
        // The three used to be assumed interchangeable — "for a fix a few miles out the two
        // agree to within a degree". They don't at a hub. Holding short of 26L at KATL puts the
        // aircraft over a mile from both the field reference and the far threshold, and a mile
        // of offset against a departure fix eight miles out is ~10° of bearing — enough for the
        // assigned vector to disagree with the FMS by more than the whole magnetic-variation
        // correction, and for the "within 10° of runway heading" test to flip and say "fly
        // runway heading" when it shouldn't (or fail to when it should).
        let depCoord = resolvedDepartureCoordinate()
        let onRunwayPosition = (aircraftState.onGround == true) ? aircraftState.coordinate : nil
        // A plan can carry a runway marker with a null island coordinate; that must not
        // outrank real telemetry, so it has to be valid to win.
        let departureRunwayCoord = flightPlan.departureRunwayCoordinate.flatMap { $0.isValid ? $0 : nil }
        let headingOrigin = departureRunwayCoord ?? onRunwayPosition ?? depCoord
        let firstWaypoint = flightPlan.waypoints.first
        // The "resume own navigation, direct …" fix in the departure climb: once
        // airborne, the next fix *ahead* of the aircraft (not the runway-end fix the
        // aircraft has already passed); on the ground, simply the first filed fix.
        let directFix: Waypoint?
        if hasDeparted, let pos = aircraftState.coordinate {
            directFix = flightPlan.nextUnpassedWaypoint(from: pos, origin: depCoord)
        } else {
            directFix = firstWaypoint
        }
        // Initial departure heading intercepts the first fix off the runway: the
        // SID's first published fix when a SID is filed, otherwise the next filed fix
        // after the runway (see `initialDepartureFix`). Airport-agnostic — it never
        // depends on the field being in a built-in table. When no located fix can be
        // found the heading is left unknown (0) so the takeoff clearance says "fly
        // runway heading"; it is deliberately *not* the bearing toward the
        // destination, which for a northern departure to a southern destination would
        // point ~180° the wrong way.
        let interceptFix = flightPlan.initialDepartureFix(sidFixes: sidProc?.fixes ?? [],
                                                          origin: headingOrigin)
        // The bearing to that fix is a **true** course (great-circle geometry), while the
        // pilot flies a magnetic heading bug — and `clearedForTakeoff` compares this
        // number against the runway's magnetic heading to decide whether to say "fly
        // runway heading" at all, so leaving it in the true frame got both the assignment
        // and that decision wrong by the local declination. Carried into the pilot's
        // frame like every other assigned heading (see `HeadingSolver`). The wind half of
        // that correction is inert here — the clearance is issued on the ground, where
        // the wind triangle isn't solved — so this is a variation conversion in practice.
        let depHeading: Int
        var trueCourse: Double?
        if let headingOrigin, let intercept = interceptFix?.coordinate,
           Geo.distanceNM(from: headingOrigin, to: intercept) >= 0.5 {
            let course = Geo.bearing(from: headingOrigin, to: intercept)
            trueCourse = course
            depHeading = assignedHeading(forTrueCourse: course)
        } else {
            depHeading = 0
        }
        // Record how that number was arrived at, for the Diagnostics row. Every ingredient
        // here has been the culprit at least once — the origin, the fix it targeted, the
        // true→magnetic step, and the runway the "fly runway heading" test measures against
        // (which is a guess from the wind whenever nothing named a runway) — and none of
        // them were visible anywhere, so a clearance that came out "fly runway heading" when
        // it should have carried a turn could only be argued about.
        if !arrival {
            let origin: String
            if departureRunwayCoord != nil { origin = "runway marker" }
            else if onRunwayPosition != nil { origin = "aircraft" }
            else if depCoord != nil { origin = "field" }
            else { origin = "none" }
            let fix: String
            if let interceptFix {
                fix = interceptFix.coordinate == nil ? "\(interceptFix.name) (unlocated)" : interceptFix.name
            } else {
                fix = "none"
            }
            var parts = ["rwy \(runway)\(resolved.isKnown ? "" : " (wind guess)")",
                         "from \(origin)", "fix \(fix)"]
            if let trueCourse {
                parts.append(String(format: "true %03.0f° → %03d°", trueCourse, depHeading))
            } else {
                parts.append("no bearing → runway heading")
            }
            lastDepartureHeadingSummary = parts.joined(separator: " · ")
        }
        // The configured initial climb is a height above the departure field. Add it
        // to the field elevation and round up to the next thousand so the callout is a
        // valid MSL altitude at high-elevation airports. At sea level the field is 0
        // and the value is unchanged. Prefer the value captured on the ground before
        // departure; fall back to the current on-ground estimate if it hasn't run yet.
        let climbAboveField = settings.initialClimbAltitudeFt > 0 ? settings.initialClimbAltitudeFt : 5000
        let departureFieldElev: Int
        if departureFieldElevationMSL > 0 {
            departureFieldElev = Int(departureFieldElevationMSL.rounded())
        } else if (aircraftState.onGround ?? false), let msl = aircraftState.altitudeMSL {
            departureFieldElev = max(0, Int((msl - (aircraftState.altitudeAGL ?? 0)).rounded()))
        } else {
            departureFieldElev = 0
        }
        let initialClimb = Self.roundedUpToThousand(departureFieldElev + climbAboveField)
        // Terminal approach fallback (used when the flight plan supplies no intercept
        // altitude): 3,000 ft above the destination field — estimated live from the
        // aircraft's MSL − AGL near the field — rounded up to the next thousand.
        // Falls back to 3,000 ft when AGL is unavailable.
        let approachDefault = Self.roundedUpToThousand((liveFieldElevationMSL() ?? 0) + 3000)

        // Ramp (simulated local/company procedure). Falls back to the generic
        // airline ramp profile when no airport-specific profile is known.
        let rampAirport = arrival ? flightPlan.destination : flightPlan.departure
        let rampProfile = RampProfile.profile(for: rampAirport)
        // Push direction is only spoken when the profile supplies one; otherwise
        // the ramp call falls back to "push approved, advise ready to taxi".
        let pushDirection = rampProfile.defaultPushDirections.first ?? ""
        let rampSpot = rampProfile.usesSpots ? (rampProfile.defaultSpotNames.first ?? "") : ""

        return ATCContext(
            callsign: cs,
            plan: flightPlan,
            assignedAltitude: assignedAltitude,
            cruiseAltitude: cruise,
            initialClimbAltitude: initialClimb,
            windDirection: windDir,
            windSpeed: windSpeed,
            squawk: deterministicSquawk(),
            runway: runway,
            taxiway: taxiPlan.taxiwaysText,
            crossingRunway: taxiPlan.crossingRunway,
            parkingTaxiway: taxiPlan.parkingTaxiway,
            approachName: approachName,
            departureFrequency: 124.300,
            centerFrequency: 132.450,
            approachFrequency: 119.700,
            towerFrequency: 118.300,
            groundFrequency: 121.800,
            rampFrequency: rampProfile.rampFrequency,
            rampProfile: rampProfile,
            pushDirection: pushDirection,
            rampSpot: rampSpot,
            gate: arrival ? flightPlan.arrivalGate : flightPlan.departureGate,
            departureHeading: ((depHeading % 360) + 360) % 360,
            runwayIsKnown: resolved.isKnown,
            firstFixName: directFix?.name ?? "",
            traconCeiling: currentTraconCeiling,
            approachInterceptAltitude: flightPlan.approachInterceptAltitude,
            approachDefaultAltitude: approachDefault,
            sidProcedure: sidProc,
            starProcedure: starProc,
            approachProcedure: approachProc)
    }

    private func deterministicSquawk() -> String {
        let n = Int(flightPlan.flightNumber.filter { $0.isNumber }) ?? 4271
        return String(format: "%04o", (abs(n) * 7 + 1) % 4096)
    }

    /// The runway to work with, and whether it is a runway the app actually *knows* the
    /// field has — filed, typed by the pilot, or looked up in the field's runway inventory —
    /// rather than a number invented from the wind.
    ///
    /// The distinction matters wherever the runway ident is used as a *heading*. The last
    /// resort below rounds the wind direction to the nearest ten and calls it a runway, which
    /// is a reasonable thing to say out loud at an unknown field and a terrible thing to
    /// measure against: the takeoff clearance compares the initial departure heading to the
    /// runway's heading (ident × 10) to decide whether to say "fly runway heading" at all, and
    /// against a wind-derived number that test asks "is the departure vector near the wind?" —
    /// which throws away a real turn whenever it happens to be.
    private func resolvedRunway(windDir: Int, windSpeed: Int, arrival: Bool,
                                approach: Procedure? = nil) -> (ident: String, isKnown: Bool) {
        // On arrival a parsed approach's runway wins, then the flight plan's arrival
        // runway; on departure the flight plan's departure runway wins. Either way a
        // manual override is honored next, then the real active runway for the field
        // from the live wind (ATIS-style), and finally a wind-derived guess.
        if arrival {
            if let rwy = approach?.runway, !rwy.isEmpty { return (rwy, true) }
            if !flightPlan.arrivalRunway.isEmpty { return (flightPlan.arrivalRunway, true) }
        } else if !flightPlan.departureRunway.isEmpty {
            return (flightPlan.departureRunway, true)
        }
        if !flightPlan.runway.isEmpty { return (flightPlan.runway, true) }
        let icao = arrival ? flightPlan.destination : flightPlan.departure
        if let real = runways.activeRunway(for: icao, windDirection: windDir, windSpeed: windSpeed) {
            return (real, true)
        }
        // The field's own runway-end idents, parsed from its loaded airport surface. The
        // curated table above covers a few dozen fields; this covers every field whose
        // surface the taxi map has already fetched — which, at the departure airport by the
        // time a takeoff clearance is due, is the field the aircraft is sitting on. Picking
        // the into-wind runway *from the field's real runways* is what the wind is actually
        // good for; the fallthrough below is what it is not.
        let surfaceRunways = airportSurface.cachedRunwayIdents(icao: icao)
        if let real = runways.activeRunway(among: surfaceRunways,
                                           windDirection: windDir, windSpeed: windSpeed) {
            return (real, true)
        }
        // Last resort, and only a *name*: the wind direction rounded to the nearest ten,
        // which at least sounds like a runway at a field nothing else knows anything about.
        // It is returned as not-known so no caller reads it back as a heading.
        let dir = windDir == 0 ? 270 : windDir
        var num = Int((Double(dir) / 10).rounded())
        if num <= 0 { num = 36 }
        if num > 36 { num -= 36 }
        return (String(format: "%02d", num), false)
    }

    // MARK: - Flight plan

    func syncFlightPlanFromSettings() {
        var plan = FlightPlan()
        plan.callsign = settings.callsign
        plan.airline = settings.airline.isEmpty && settings.mockMode ? "United" : settings.airline
        plan.flightNumber = settings.flightNumber.isEmpty && settings.mockMode ? "598" : settings.flightNumber
        // Resolve an airline prefix typed into the Callsign field (e.g. "UAL598")
        // into a telephony name + flight number when not otherwise specified.
        if plan.airline.isEmpty, plan.flightNumber.isEmpty,
           let parsed = AirlineDatabase.parse(plan.callsign) {
            plan.airline = parsed.telephony
            plan.flightNumber = parsed.flightNumber
        }
        plan.departure = settings.departure.isEmpty && settings.mockMode ? mock.route.departure : settings.departure
        plan.destination = settings.destination.isEmpty && settings.mockMode ? mock.route.destination : settings.destination
        plan.alternate = settings.alternate
        plan.cruiseAltitude = settings.cruiseAltitude > 0 ? settings.cruiseAltitude
            : (settings.mockMode ? mock.route.cruiseAltitude : 0)
        plan.runway = settings.runway
        plan.sid = settings.sid
        plan.star = settings.star
        plan.approach = settings.approach
        // In Mock Mode default to the route's realistic United gate so the demo taxis
        // from/to a plausible gate; any gate the pilot enters wins.
        plan.departureGate = settings.departureGate.isEmpty && settings.mockMode
            ? mock.route.departureGate : settings.departureGate
        plan.arrivalGate = settings.arrivalGate.isEmpty && settings.mockMode
            ? mock.route.arrivalGate : settings.arrivalGate
        plan.manualOverride = !settings.departure.isEmpty || !settings.destination.isEmpty
        if settings.mockMode { plan.waypoints = mock.route.waypoints }
        let routeChanged = plan.departure != flightPlan.departure
            || plan.destination != flightPlan.destination
            || plan.waypoints.map(\.name) != flightPlan.waypoints.map(\.name)
        flightPlan = plan
        // A manual plan edit must re-evaluate the weather deviation immediately: the
        // telemetry tick also recomputes, but an edit made while disconnected / paused (or
        // before connecting) would otherwise leave a stale mint line and previews. When the
        // route corridor changed, also invalidate the radar sample so the next sample
        // covers the new corridor rather than the old one.
        if routeChanged { lastPrecipSampleAt = nil }
        recomputeWeatherHazards()
        // Cache both airports' surfaces now (on load), not lazily right before taxi.
        prefetchAirportSurfaces()
    }

    func applyManualOverrides() {
        syncFlightPlanFromSettings()
        flightPlan.manualOverride = true
        diagnostics.log(.app, "Manual flight-plan overrides applied.")
    }

    /// Clear every manually-entered override field and revert to the live (or mock)
    /// flight plan. In live mode this forces an immediate re-read so the Infinite
    /// Flight plan repopulates the fields that were being overridden.
    func clearManualOverrides() {
        settings.callsign = ""
        settings.airline = ""
        settings.flightNumber = ""
        settings.departure = ""
        settings.destination = ""
        settings.alternate = ""
        settings.cruiseAltitude = 0
        settings.runway = ""
        settings.sid = ""
        settings.star = ""
        settings.approach = ""
        settings.departureGate = ""
        settings.arrivalGate = ""
        // Re-sync drops manualOverride (both endpoints are now empty) so Connect data
        // is no longer pinned back by the override flag.
        syncFlightPlanFromSettings()
        diagnostics.log(.app, "Manual flight-plan overrides cleared.")
        if !settings.mockMode { Task { await connect.refreshFlightPlan() } }
    }

    /// Re-read the flight plan from the active source. In live mode this forces an
    /// immediate re-read from Infinite Flight (use after editing the plan mid-flight);
    /// in mock mode it rebuilds from the current settings/mock route.
    func refreshFlightPlan() {
        if settings.mockMode {
            syncFlightPlanFromSettings()
            diagnostics.log(.app, "Flight plan refreshed from mock route.")
        } else {
            Task { await connect.refreshFlightPlan() }
            diagnostics.log(.app, "Requested flight-plan refresh from Infinite Flight.")
        }
    }

    // MARK: - Push-to-talk

    /// Last recognized spoken phrase and the intent it mapped to (for the UI).
    @Published var lastSpokenText: String = ""
    @Published var lastSpokenIntent: PilotIntent?

    /// Parse a recognized utterance into a pilot intent and perform it. Returns
    /// the recognized intent. Deterministic — no AI in the mapping.
    @discardableResult
    func handleSpokenInput(_ text: String) -> PilotIntent {
        let intent = intentParser.parse(text)
        lastSpokenText = text
        lastSpokenIntent = intent
        diagnostics.log(.atc, "PTT heard: \"\(text)\" -> \(intent.title)")
        // The user already spoke this; don't re-speak the pilot transmission.
        pilotInputViaVoice = true
        perform(intent)
        pilotInputViaVoice = false
        return intent
    }

    /// Dispatch a recognized intent to the matching pilot action.
    func perform(_ intent: PilotIntent) {
        switch intent {
        case .readback: readBack()
        case .sayAgain: sayAgain()
        case .unable: unable()
        case .wilco: wilco()
        case .requestClearance: requestClearance()
        case .requestPushback: requestPushback()
        case .requestEngineStart: requestEngineStart()
        case .requestTaxi: requestTaxi()
        case .readyForDeparture: reportReadyForDeparture()
        case .requestTakeoff: requestTakeoff()
        case .requestHigher: requestHigher()
        case .requestLower: requestLower()
        case .requestVectors: requestVectors()
        case .requestApproach: requestApproach()
        case .rideReport: requestRideReport()
        case .destinationWeather: requestDestinationWeather()
        case .checkIn: requestHandoff()
        case .unknown: break
        }
    }

    // MARK: - Pilot button actions

    func readBack() {
        // A pending simulated runway-crossing clearance: reading it back authorizes the
        // crossing (never before). The crossing clearance carries its own read-back.
        if airportSurface.awaitingCrossingReadback {
            if let rb = lastATCTransmission?.readback {
                postPilot(ATCTransmission(sender: .pilot, facility: rb.facility,
                                          displayText: rb.displayText, spokenText: rb.spokenText))
            }
            airportSurface.crossingReadbackReceived()
            return
        }
        // Prefer the read-back the controller composed for its *last* call (frequency
        // hand-offs, vectors, altitude changes) so the Read Back button always echoes
        // the message actually on the radio — not a read-back re-derived from the
        // conversational state, which lags behind double calls like "cleared the ILS,
        // contact Tower".
        if let rb = lastATCTransmission?.readback {
            postPilot(ATCTransmission(sender: .pilot, facility: rb.facility,
                                      displayText: rb.displayText, spokenText: rb.spokenText))
            // A frequency hand-off: only after reading it back do we move the radio to
            // the next controller (switching the active frequency button) — never the
            // moment the controller issues the hand-off. With auto-tune off, the radio
            // never moves on its own: the pilot taps the next controller's tune button by
            // hand, so the read-back leaves the frequency where it is.
            if let tune = rb.tuneTo, settings.autoTuneOnHandoff {
                tunedFacility = tune
                currentFacility = tune
                // While the pilot is tuning by hand, hold the new controller's
                // instruction until they check in on the freq they just acknowledged.
                if manualTuning { pendingCheckInFacility = tune }
                persistSession()
            }
            // Reading back the Ground taxi clearance reveals the temporary taxi map.
            if airportSurface.awaitingTaxiReadback { airportSurface.taxiReadBackComplete() }
            retireDepartureTaxiMapAfterReadback()
            return
        }
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.readback(for: atcState, context: c))
        if airportSurface.awaitingTaxiReadback { airportSurface.taxiReadBackComplete() }
        retireDepartureTaxiMapAfterReadback()
    }

    /// Retire the departure taxi map once the pilot reads back the line-up-and-wait or the
    /// takeoff clearance — whichever comes first. The map is kept visible through the
    /// Ground→Tower "monitor" hand-off and the final roll to the runway so the pilot can see
    /// the route to the hold-short; reading back either of these calls means the aircraft is
    /// at (or rolling onto) the runway, so the map has served its purpose. No-op for anything
    /// else (arrival maps, or a read-back of an earlier departure call such as the taxi
    /// clearance or the "monitor Tower" hand-off), so the surface keeps tracking up to the
    /// line-up cue until then.
    private func retireDepartureTaxiMapAfterReadback() {
        guard airportSurface.kind == .departure, airportSurface.taxiMapVisible,
              stateMachine.current == .lineUpWait || stateMachine.current == .towerDeparture
        else { return }
        airportSurface.hideTaxiMap()
    }

    func wilco() {
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.wilco(context: c, facility: currentFacility))
    }

    func sayAgain() {
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.sayAgain(context: c, facility: currentFacility))
        if let last = lastATCTransmission {
            let repeated = ATCTransmission(sender: .atc, facility: last.facility,
                                           displayText: last.displayText, spokenText: last.spokenText,
                                           readback: last.readback)
            post(repeated, speak: true)
        }
    }

    func unable() {
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.unable(context: c, facility: currentFacility))
        // Deterministic alternate controller response.
        let target = max(assignedAltitude, c.initialClimbAltitude)
        let alt = engine.formatAltDisplay(target)
        var tx = ATCTransmission(sender: .atc, facility: currentFacility,
                                 displayText: "\(c.callsign.display), roger, maintain \(alt), advise able to comply.",
                                 spokenText: "\(c.callsign.spoken), roger, maintain \(Phonetic.altitude(target)), advise able to comply.")
        tx.readback = ATCTransmission.Readback(
            displayText: "Maintain \(alt), \(c.callsign.display).",
            spokenText: "Maintain \(Phonetic.altitude(target)), \(c.callsign.spoken).",
            facility: currentFacility)
        post(tx, speak: true)
    }

    func requestHigher() {
        let c = buildContext(for: atcState)
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: true)
        postPilot(pilotEngine.requestHigher(context: c, target: target))
        let blocked = rideReportItems.contains { $0.severity >= .moderate && ($0.altitudeBand?.contains(target) ?? false) }
        if blocked {
            post(deny(c, reason: "unable higher, traffic and reported turbulence at that level"), speak: true)
        } else {
            assignedAltitude = target
            var tx = engine.climbMaintain(cs: c.callsign, altitude: target)
            tx.readback = altitudeReadback("Climb", altitude: target, callsign: c.callsign, facility: currentFacility)
            post(tx, speak: true)
        }
        suggestedSmootherAltitude = nil   // one-shot hint from the last ride report
    }

    func requestLower() {
        let c = buildContext(for: atcState)
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: false)
        postPilot(pilotEngine.requestLower(context: c, target: target))
        var tx = engine.descendPilotsDiscretion(cs: c.callsign, altitude: target)
        tx.readback = altitudeReadback("Descend", altitude: target, callsign: c.callsign, facility: currentFacility)
        post(tx, speak: true)
        assignedAltitude = target
        suggestedSmootherAltitude = nil   // one-shot hint from the last ride report
    }

    /// Accept the smoother altitude the last ride report suggested — a direct climb or
    /// descent to that specific level (the suggestion is already a smooth/lighter level in
    /// the cruise band, so no traffic/turbulence block applies). Clears the suggestion.
    func acceptSmootherAltitude() {
        guard let s = suggestedSmootherAltitude else { return }
        let c = buildContext(for: atcState)
        let target = s.altitudeFt
        postPilot(s.higher ? pilotEngine.requestHigher(context: c, target: target)
                           : pilotEngine.requestLower(context: c, target: target))
        var tx = s.higher ? engine.climbMaintain(cs: c.callsign, altitude: target)
                          : engine.descendPilotsDiscretion(cs: c.callsign, altitude: target)
        tx.readback = altitudeReadback(s.higher ? "Climb" : "Descend", altitude: target,
                                       callsign: c.callsign, facility: currentFacility)
        post(tx, speak: true)
        assignedAltitude = target
        suggestedSmootherAltitude = nil
    }

    /// A pilot read-back of an assigned altitude, attached to a controller call so the
    /// Read Back button echoes "<verb> and maintain <alt>" instead of a read-back
    /// re-derived from the (now stale) conversational state.
    private func altitudeReadback(_ verb: String, altitude: Int,
                                  callsign cs: PhraseologyEngine.Callsign,
                                  facility: ATCFacility) -> ATCTransmission.Readback {
        ATCTransmission.Readback(
            displayText: "\(verb) and maintain \(engine.formatAltDisplay(altitude)), \(cs.display).",
            spokenText: "\(verb) and maintain \(Phonetic.altitude(altitude)), \(cs.spoken).",
            facility: facility)
    }

    func requestVectors() {
        let c = buildContext(for: atcState, arrivalOverride: true)
        postPilot(pilotEngine.requestVectors(context: c))
        // Name the approach as "<type> runway <rwy>" (e.g. "ILS runway 01R") rather
        // than a display name that already embeds the runway, to avoid "… ILS RWY
        // 01R runway 01R approach".
        let rwy = c.approachProcedure?.runway ?? c.runway
        // A real 30° intercept to the final approach course, turning toward the
        // extended centerline from whichever side the aircraft is on — rather than
        // just handing back the current heading.
        let hdg = approachInterceptHeading(runway: rwy)
        let typeD = c.approachProcedure?.approachType?.display ?? (c.approachName.isEmpty ? "ILS" : c.approachName)
        let typeS = c.approachProcedure?.approachType?.spoken ?? (c.approachName.isEmpty ? "I L S" : c.approachName)
        var tx = ATCTransmission(sender: .atc, facility: .approach,
                                 displayText: "\(c.callsign.display), fly heading \(String(format: "%03d", hdg)), vectors for the \(typeD) runway \(rwy) approach.",
                                 spokenText: "\(c.callsign.spoken), fly heading \(Phonetic.heading(hdg)), vectors for the \(typeS) runway \(Phonetic.runway(rwy)) approach.")
        // Read back the heading (the safety-critical element), not a state-derived call.
        tx.readback = ATCTransmission.Readback(
            displayText: "Heading \(String(format: "%03d", hdg)), \(c.callsign.display).",
            spokenText: "Heading \(Phonetic.heading(hdg)), \(c.callsign.spoken).",
            facility: .approach)
        post(tx, speak: true)
    }

    /// The heading to fly for approach vectors: a 30° intercept to the landing
    /// runway's final approach course, turning toward the extended centerline from
    /// whichever side the aircraft is on. Falls back to the current heading when
    /// the runway or position data is unavailable (e.g. manual practice with no
    /// telemetry, or an airport not in the coordinate database).
    private func approachInterceptHeading(runway: String) -> Int {
        let fallback = ApproachIntercept.normalizedHeading(aircraftState.heading ?? 270)
        guard let finalCourse = RunwayDatabase.heading(forRunway: runway),
              let aircraft = aircraftState.coordinate, aircraft.isValid,
              let airport = resolvedDestinationCoordinate(), airport.isValid else {
            return fallback
        }
        return ApproachIntercept.heading(finalCourse: finalCourse,
                                         aircraft: aircraft,
                                         runwayReference: airport,
                                         variationDegreesEast: lastKnownVariationEast ?? 0)
    }

    func requestApproach() {
        let c = buildContext(for: atcState, arrivalOverride: true)
        postPilot(pilotEngine.requestApproach(context: c))
        // Prefer the procedure-aware clearance (names the runway once) when the
        // approach parsed; otherwise fall back to the plain string form. Either way,
        // carry the matching read-back so Read Back echoes the approach clearance.
        var tx: ATCTransmission
        if let approach = c.approachProcedure {
            tx = engine.clearedApproach(cs: c.callsign, procedure: approach, runway: c.runway)
        } else {
            tx = engine.clearedApproach(cs: c.callsign, approach: flightPlan.approach, runway: c.runway)
        }
        tx.readback = pilotEngine.readback(for: .final, context: c).asReadback(facility: .approach)
        post(tx, speak: true)
    }

    // MARK: - Go around / missed approach

    /// The pilot breaks off the approach. The pilot issues the go-around call to
    /// Tower; Tower turns the aircraft onto a 90° crosswind leg, climbs it to the
    /// pattern altitude (3,000 ft above the field, elevation-aware), assigns
    /// left/right traffic for the *same* runway, and hands it back to Approach. On the
    /// pilot's next Approach check-in (`resumeApproachAfterGoAround`), Approach holds
    /// that altitude and clears them to continue inbound — after which the whole
    /// cleared-approach → Tower → cleared-to-land sequence replays exactly as the
    /// first time, driven by the same "established on final / APPR engaged" detection.
    func goAround() {
        guard !companionStandby else { return }
        let c = buildContext(for: .approach, arrivalOverride: true)
        // The go-around supersedes any read-back still pending on the landing clearance.
        clearReadbackGate()
        // Pilot's go-around / missed-approach call to Tower.
        postPilot(pilotEngine.goAround(context: c))
        // Pattern altitude: 3,000 ft above the field, expressed in MSL and rounded up to
        // the next thousand (elevation-aware, so it clears the ground at a high field).
        // This is exactly the terminal approach fallback the context already computes.
        let patternAlt = c.approachDefaultAltitude
        // Standard left-hand traffic pattern: a 90° left crosswind vector off the runway.
        let leftTraffic = true
        let rwy = c.approachProcedure?.runway ?? c.runway
        let hdg = goAroundCrosswindHeading(runway: rwy, leftTraffic: leftTraffic)
        post(engine.goAround(cs: c.callsign, runway: rwy, leftTraffic: leftTraffic,
                             crosswindHeading: hdg, patternAltitude: patternAlt,
                             approachFrequency: c.approachFrequency), speak: true)
        // The aircraft is climbing the pattern to the assigned altitude. Hold the
        // automatic flow (goAroundInProgress) until the pilot re-establishes with
        // Approach; the go-around read-back tunes the radio there. The state machine is
        // left where it was (Tower) and reset to `.approach` on that check-in, so the
        // radio stays on Tower until the pilot reads back and switches.
        assignedAltitude = patternAlt
        goAroundInProgress = true
        persistSession()
    }

    /// Pilot checks back in with Approach on the missed-approach leg. Approach holds
    /// the pattern altitude Tower assigned and clears the aircraft to continue inbound;
    /// the state machine is reset to `.approach` so the cleared-approach → Tower
    /// hand-off replays once the aircraft re-establishes on final (or re-engages APPR),
    /// exactly as on the first approach.
    private func resumeApproachAfterGoAround() {
        goAroundInProgress = false
        pendingCheckInFacility = nil
        let c = buildContext(for: .approach, arrivalOverride: true)
        // Pilot checks in with Approach at (or climbing to) the pattern altitude.
        postPilot(pilotEngine.requestHandoff(context: c, facility: .approach,
                                             currentAltitude: checkInAltitude(),
                                             targetAltitude: assignedAltitude,
                                             onGround: aircraftState.onGround ?? false))
        // Approach: maintain the pattern altitude, continue inbound for another approach.
        post(engine.continueInbound(cs: c.callsign, altitude: assignedAltitude,
                                    procedure: c.approachProcedure,
                                    approach: c.approachName, runway: c.runway), speak: true)
        // Rewind the conversation to the approach so the sequence replays from here.
        stateMachine.restore(to: .approach)
        atcState = .approach
        currentFacility = .approach
        tunedFacility = nil
        persistSession()
    }

    /// The crosswind-leg heading for a go-around: 90° off the landing runway heading,
    /// turning in the traffic-pattern direction (left traffic = runway − 90°, right =
    /// runway + 90°). Falls back to due north when the runway carries no usable number.
    private func goAroundCrosswindHeading(runway: String, leftTraffic: Bool) -> Int {
        let base = Int((RunwayDatabase.heading(forRunway: runway) ?? 360).rounded())
        return AppModel.crosswindHeading(runwayHeading: base, leftTraffic: leftTraffic)
    }

    /// A 90° crosswind heading off a runway heading, in the traffic pattern's turn
    /// direction (left = −90°, right = +90°). Pure/testable; normalized to 0…359.
    nonisolated static func crosswindHeading(runwayHeading: Int, leftTraffic: Bool) -> Int {
        let delta = leftTraffic ? -90 : 90
        return ((runwayHeading + delta) % 360 + 360) % 360
    }

    /// Check in on the currently tuned frequency. Posts the pilot's call-up and the
    /// controller's next instruction for the facility we're tuned to. This is the
    /// deliberate "check in" the pilot makes after switching frequency (tuning no
    /// longer checks in automatically).
    /// Live aircraft altitude rounded to the nearest 100 ft for a check-in report,
    /// or nil when no usable altitude telemetry is available (e.g. manual practice).
    private func checkInAltitude() -> Int? {
        guard let msl = aircraftState.altitudeMSL, msl > 0 else { return nil }
        return Int((msl / 100).rounded()) * 100
    }

    func requestHandoff() {
        guard !companionStandby else { return }
        // The controller the pilot is checking in with: the pending hand-off target if
        // one was prompted, otherwise the frequency they're tuned to. Captured up front
        // because clearing `pendingCheckInFacility` below would otherwise change it.
        let facility = workingFacility
        // Re-establishing with Approach after a go-around: Approach holds the pattern
        // altitude and clears the aircraft to continue inbound, then the normal
        // cleared-approach → Tower sequence replays. Handled before the generic
        // check-in so it isn't collapsed into a plain radar-contact re-check-in.
        if goAroundInProgress, facility == .approach {
            resumeApproachAfterGoAround()
            return
        }
        // Monitoring Tower before departure: a check-in isn't required, but if the pilot
        // does call up — typically well before the runway — Tower replies ONLY with
        // "you're number one for departure" and issues no takeoff clearance. The state
        // machine is left at the taxi state, so the takeoff clearance still comes only once
        // the aircraft is lined up on the runway (automatically, or via the Takeoff button).
        if monitoringTower, !hasDeparted, facility == .tower {
            pendingCheckInFacility = nil
            let c = buildContext(for: .lineUpWait)
            postPilot(pilotEngine.requestHandoff(context: c, facility: .tower,
                                                 currentAltitude: checkInAltitude(),
                                                 targetAltitude: assignedAltitude,
                                                 onGround: aircraftState.onGround ?? true))
            post(engine.numberOneForTakeoff(cs: c.callsign, runway: c.runway), speak: true)
            return
        }
        // Checking in satisfies any pending hand-off the controller prompted: the new
        // controller now speaks for itself, so the semi-automatic flow resumes.
        pendingCheckInFacility = nil
        // On arrival, the pilot reports the destination ATIS code when first checking in
        // with Approach ("…with you at seven thousand, information Bravo"). Only Approach
        // gets it, only when the arrival ATIS has been received, and only once.
        let atisWord = (facility == .approach) ? consumeATISInfoWord(arrival: true) : nil
        guard let target = nextState(workedBy: facility, after: stateMachine.current),
              target != stateMachine.current else {
            // Nothing new ahead for this controller — a plain check-in / radar-contact
            // exchange (e.g. a same-sector Center re-check-in).
            let c = buildContext(for: atcState)
            postPilot(appendingATISInfo(pilotEngine.requestHandoff(context: c, facility: facility,
                                                 currentAltitude: checkInAltitude(),
                                                 targetAltitude: assignedAltitude,
                                                 onGround: aircraftState.onGround ?? false),
                                        word: atisWord))
            post(engine.radarContact(cs: c.callsign, facility: facility), speak: true)
            return
        }
        if !target.isManualGroundFlow { hasDeparted = true }
        let c = buildContext(for: target)
        // The pilot checks in on the tuned frequency. Because the pilot initiated the
        // switch, the controller does not precede its reply with a "contact …"
        // hand-off (announceHandoff: false). The pilot reports altitude relative to
        // the currently assigned altitude (still the previous controller's assignment
        // here — advanceAndPost updates it afterwards).
        postPilot(appendingATISInfo(pilotEngine.requestHandoff(context: c, facility: facility,
                                             currentAltitude: checkInAltitude(),
                                             targetAltitude: assignedAltitude,
                                             onGround: aircraftState.onGround ?? false),
                                    word: atisWord))
        if target == .groundArrival {
            // Arrival taxi-in: Ground withholds the clearance until the destination surface
            // has fully loaded (pre-loaded at the runway exit), then replies — routed to the
            // gate, or generic only if the loaded data can't be routed. The read-back gate
            // that holds `.groundArrival` open is engaged when the clearance is actually
            // posted (`postArrivalTaxiClearance`), not while it is still being withheld.
            issueArrivalTaxiClearance(announceHandoff: false, automatic: false)
        } else {
            advanceAndPost(to: target, context: c, announceHandoff: false)
        }
        // Announce the block-in only if the advance to .parked actually happened — the
        // completion gate in `advanceAndPost` holds it back until the aircraft is parked at
        // the gate and tuned to Ramp, so check the state machine, not the requested target.
        if stateMachine.current == .parked, !arrivalAnnounced {
            announceArrival()
            arrivalAnnounced = true
        }
        if settings.mockMode { mock.setPhase(mockPhase(for: target)) }
    }

    // MARK: - Departure ground flow (pilot-driven)

    /// Post the pilot's ground request, advance the state machine to `state`, and
    /// post the controller's reply. Keeps the pre-departure sequence in order so no
    /// phase (pushback, engine start, taxi, …) is skipped.
    private func groundFlow(_ pilotRequest: ATCTransmission, to state: ATCState) {
        postPilot(pilotRequest)
        advanceAndPost(to: state, context: buildContext(for: state))
    }

    func requestClearance() {
        groundFlow(pilotEngine.requestClearance(context: buildContext(for: .clearance)), to: .clearance)
    }

    func requestPushback() {
        groundFlow(pilotEngine.requestPushback(context: buildContext(for: .pushback)), to: .pushback)
    }

    func requestEngineStart() {
        groundFlow(pilotEngine.requestEngineStart(context: buildContext(for: .engineStart)), to: .engineStart)
    }

    func requestTaxi() {
        // While still on the departure Ramp (pushed back / engines started), Ramp
        // does not issue the taxi clearance — it only hands the pilot to Ground.
        // The pilot then requests taxi again on Ground for the actual clearance.
        if onDepartureRampPreTaxi {
            handOffDepartureRampToGround()
            return
        }
        // Kick off the OpenStreetMap departure taxi (surface load + route calc). When a
        // credible route is ready its Ground clearance replaces the generic one; the
        // taxi map then animates in after the pilot reads back.
        prepareDepartureTaxi()
        // Real-world phraseology: the pilot reports the ATIS code on the initial taxi
        // request ("…request taxi, information Alpha"). Appended only when the pilot has
        // actually received the departure ATIS; otherwise nothing is added.
        let request = appendingATISInfo(pilotEngine.requestTaxi(context: buildContext(for: .groundTaxi)),
                                        word: consumeATISInfoWord(arrival: false))
        postPilot(request)
        let ctx = buildContext(for: .groundTaxi)
        let osmClearance = airportSurface.taxiClearance(callsign: ctx.callsign)
        advanceAndPost(to: .groundTaxi, context: ctx, overrideTransmission: osmClearance)
        // Reveal the taxi map on the pilot's read-back (whether the OSM route or the
        // generic clearance was issued; the map appears once a route is available).
        // When `osmClearance` is nil the live surface was still loading, so a generic
        // clearance went out — have the coordinator supersede it with the detailed OSM
        // route clearance as soon as the asynchronous Overpass fetch resolves.
        airportSurface.taxiClearanceIssued(supersedeWhenRouteReady: osmClearance == nil)
    }

    /// True when the pilot is still on the departure Ramp frequency (after pushback /
    /// engine start) and Ramp has not yet handed them to Ground. While true, a taxi
    /// request makes Ramp hand off to Ground only; Ground issues the taxi clearance
    /// once the pilot re-requests taxi there. No-ramp airports (pilot already on
    /// Ground for the push) taxi in a single step.
    private var onDepartureRampPreTaxi: Bool {
        guard !hasDeparted, currentFacility == .ramp,
              [.pushback, .engineStart].contains(stateMachine.current) else { return false }
        return buildContext(for: .pushback).rampProfile.rampType != .none
    }

    /// Ramp hands the pilot to Ground for taxi. Posts the pilot's "ready to taxi" and
    /// Ramp's "contact Ground" — but does NOT advance the flow or issue a taxi
    /// clearance. Marks Ground as the tuned facility so the next taxi request (and the
    /// response buttons) are on Ground, where the actual taxi clearance is given.
    private func handOffDepartureRampToGround() {
        let c = buildContext(for: .engineStart)
        postPilot(rampEngine.pushComplete(cs: c.callsign))
        post(rampEngine.contactGround(cs: c.callsign, groundFrequency: c.groundFrequency,
                                      spot: c.rampSpot), speak: true)
        tunedFacility = .ground
        currentFacility = .ground
        persistSession()
    }

    /// Report ready for departure. Tower responds "line up and wait"; the Takeoff
    /// button (or tuning Tower and checking in) then yields the takeoff clearance,
    /// and the pilot drives every controller change from there with the frequency
    /// buttons.
    func reportReadyForDeparture() {
        // The taxi map stays up as Tower issues "line up and wait" so the pilot can still see
        // the route to the runway; it is retired when they read the line-up-and-wait back
        // (retireDepartureTaxiMapAfterReadback), matching the automatic monitor-Tower flow.
        groundFlow(pilotEngine.readyForDeparture(context: buildContext(for: .lineUpWait)), to: .lineUpWait)
    }

    func requestTakeoff() {
        // The aircraft is departing — no pre-departure check-in remains pending (a
        // "monitor Tower" hand-off never requires one), so it can't stall the airborne flow.
        pendingCheckInFacility = nil
        groundFlow(pilotEngine.requestTakeoff(context: buildContext(for: .towerDeparture)), to: .towerDeparture)
        // Cleared for takeoff — the pilot has departed, so switch the response
        // buttons from the ground flow to the enroute set (which includes Check In
        // for calling up Departure and the rest of the airborne controllers).
        hasDeparted = true
    }

    // MARK: - Weather button actions

    func requestRideReport() {
        let c = buildContext(for: atcState)
        let facility = currentFacility
        postPilot(pilotEngine.requestRideReports(context: c))
        Task {
            await refreshWeather()
            recomputeRideItems()
            let refAlt = rideReferenceAltitudeFt()
            let smoother = computeSmootherAltitude(referenceAltFt: refAlt)
            // Remember the suggestion so the accept button appears and the next
            // higher/lower request targets it.
            suggestedSmootherAltitude = smoother
            var report = rideEngine.rideReport(assessment: rideAssessment, items: rideReportItems,
                                               referenceAltitudeFt: refAlt, smoother: smoother,
                                               callsign: c.callsign)
            // Don't auto-acknowledge. The pilot answers on their own terms — accepting a
            // suggested smoother altitude (the "next response" for the new-altitude logic),
            // or tapping Read Back for the courtesy "Roger" attached here so that button
            // acknowledges the report rather than re-deriving a stale state read-back.
            report.readback = pilotEngine.roger(context: c, facility: facility).asReadback(facility: facility)
            post(report, speak: true)
        }
    }

    /// The altitude (ft) ride reports are evaluated against: the filed cruise level, or
    /// the live altitude before one is set (matching `recomputeRideItems`).
    private func rideReferenceAltitudeFt() -> Int {
        flightPlan.cruiseAltitude > 0 ? flightPlan.cruiseAltitude : aircraftAltInt()
    }

    /// A data-backed smoother altitude from PIREPs at *other* levels along the route,
    /// bounded to the commercial cruise band. Nil when nothing supports one.
    private func computeSmootherAltitude(referenceAltFt: Int) -> SmootherAltitude? {
        let liveAircraft = aircraftState.coordinate
        guard let pos = liveAircraft ?? resolvedDepartureCoordinate() else { return nil }
        let end = resolvedDestinationCoordinate()
            ?? flightPlan.nextWaypoint(from: pos)?.coordinate
        // All route-corridor PIREPs regardless of altitude — the ±band filter would hide
        // the very levels a smoother-ride suggestion is drawn from. This path reads only
        // each item's altitude/severity, so no `routeFixes` labeling is needed.
        let allItems = routeAnalyzer.relevantReports(pireps: pireps, position: pos, routeEnd: end,
                                                     altitudeFt: Double(referenceAltFt),
                                                     ignoreAltitudeBand: true,
                                                     positionIsLiveAircraft: liveAircraft != nil)
        let currentSeverity = rideReportItems.map { $0.severity }.max() ?? .smooth
        return routeAnalyzer.smootherAltitude(items: allItems, referenceAltFt: referenceAltFt,
                                              currentSeverity: currentSeverity)
    }

    func requestDestinationWeather() {
        let c = buildContext(for: atcState)
        let facility = currentFacility
        let dest = flightPlan.destination
        postPilot(pilotEngine.requestWeather(context: c, airport: dest.isEmpty ? "destination" : dest))
        Task {
            await refreshWeather()
            var wx = rideEngine.destinationWeather(metar: destinationMETAR, callsign: c.callsign, icao: dest)
            // Don't auto-acknowledge — attach the courtesy "Roger" as the read-back so the
            // pilot acknowledges the read-out explicitly with Read Back.
            wx.readback = pilotEngine.roger(context: c, facility: facility).asReadback(facility: facility)
            post(wx, speak: true)
        }
    }

    func requestLowerDueRide() {
        let c = buildContext(for: atcState)
        recomputeRideItems()
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: false)
        postPilot(pilotEngine.requestLower(context: c, target: target))
        var tx = ATCTransmission(sender: .atc, facility: .center,
                                 displayText: "\(c.callsign.display), descend and maintain \(engine.formatAltDisplay(target)) for a smoother ride, report conditions.",
                                 spokenText: "\(c.callsign.spoken), descend and maintain \(Phonetic.altitude(target)) for a smoother ride, report conditions.")
        tx.readback = ATCTransmission.Readback(
            displayText: "Descend and maintain \(engine.formatAltDisplay(target)), \(c.callsign.display).",
            spokenText: "Descend and maintain \(Phonetic.altitude(target)), \(c.callsign.spoken).",
            facility: .center)
        post(tx, speak: true)
        assignedAltitude = target
    }

    private func deny(_ c: ATCContext, reason: String) -> ATCTransmission {
        ATCTransmission(sender: .atc, facility: currentFacility,
                        displayText: "\(c.callsign.display), \(reason).",
                        spokenText: "\(c.callsign.spoken), \(reason).")
    }

    private func nextAltitude(from current: Int, up: Bool) -> Int {
        // Prefer a data-backed smoother level from the last ride report when it lies in
        // the requested direction (it is already bounded to the cruise band).
        if let s = suggestedSmootherAltitude?.altitudeFt, (up && s > current) || (!up && s < current) {
            return s
        }
        let step = 2000
        let base = current <= 0 ? (flightPlan.cruiseAltitude > 0 ? flightPlan.cruiseAltitude : 35000) : current
        let target = up ? base + step : base - step
        return max(4000, target)
    }

    private func aircraftAltInt() -> Int { Int(aircraftState.altitudeMSL ?? 0) }

    /// A `minLat,minLon,maxLat,maxLon` box enclosing the aircraft and the route
    /// airports, padded so PIREPs just off the route are still returned (then narrowed
    /// to the corridor by `relevantReports`). The AWC pirep endpoint requires a box;
    /// nil when no position/route is known yet, in which case the caller skips the query.
    private func pirepBoundingBox(padDegrees: Double = 2.0) -> String? {
        var coords: [CLLocationCoordinate2D] = []
        if let c = aircraftState.coordinate, c.isValid { coords.append(c) }
        // Endpoints resolve via Infinite Flight first (covers the whole world); only the
        // alternate falls back to the built-in hub table, since IF doesn't report it.
        for c in [resolvedDepartureCoordinate(), resolvedDestinationCoordinate(),
                  airports.coordinate(for: flightPlan.alternate)] {
            if let c, c.isValid { coords.append(c) }
        }
        if let near = aircraftState.nearestAirport, let c = airports.coordinate(for: near), c.isValid {
            coords.append(c)
        }
        guard !coords.isEmpty else { return nil }
        let lats = coords.map { $0.latitude }, lons = coords.map { $0.longitude }
        let minLat = max(-90, (lats.min() ?? 0) - padDegrees)
        let maxLat = min(90, (lats.max() ?? 0) + padDegrees)
        let minLon = max(-180, (lons.min() ?? 0) - padDegrees)
        let maxLon = min(180, (lons.max() ?? 0) + padDegrees)
        return String(format: "%.3f,%.3f,%.3f,%.3f", minLat, minLon, maxLat, maxLon)
    }

    // MARK: - Weather refresh

    /// Interval between automatic aviation-weather refreshes while a feed is active. Set
    /// to the weather service's cache TTL so each tick actually revalidates rather than
    /// re-serving the same cached payload.
    var weatherRefreshInterval: TimeInterval = 300
    /// Drives the periodic refresh. Cancelled when the source stops / a new one starts.
    private var weatherRefreshTimer: Task<Void, Never>?

    /// Re-fetch aviation weather (METARs, PIREPs, SIGMETs) on a fixed cadence while a feed
    /// is active. The fetch is otherwise event-driven (connect, route change, ride-report
    /// tap, pull-to-refresh), so PIREPs would freeze at the connect-time snapshot and the
    /// ride-report pool would empty out late in a flight as reports fall behind the
    /// aircraft. Each tick honors the service's TTL cache, so the network is not hit more
    /// often than the data updates. Re-arming cancels any prior timer, so it never doubles.
    ///
    /// The same tick also **auto-refreshes the deviation set** (`autoRefreshDeviationsUnlessDeviating`),
    /// right after the weather sample lands, so every deviation across the plan is re-solved
    /// against fresh radar every ~5 min — a manual pull-to-refresh, run automatically — unless
    /// the pilot is committed to and flying a deviation.
    private func armWeatherRefreshTimer() {
        weatherRefreshTimer?.cancel()
        weatherRefreshTimer = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let interval = self?.weatherRefreshInterval else { return }
                try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
                guard !Task.isCancelled, let self else { return }
                await self.refreshWeather()                    // fresh radar + aviation weather
                self.autoRefreshDeviationsUnlessDeviating()    // re-lock against those fresh cells
            }
        }
    }

    /// The periodic (≈5-min) automatic deviation refresh, driven by the weather-refresh timer on
    /// the same cadence, immediately after `refreshWeather` samples fresh radar. Drops the
    /// deviation lock and re-solves the whole plan against the just-sampled cells — **unless**
    /// the pilot is committed to and flying a deviation, whose mint line is locked to the path
    /// being flown and must never be re-proposed under them. This is the automatic counterpart
    /// to the manual pull-to-refresh, which always refreshes; this one steps aside while a
    /// deviation is being worked. The unlock and re-lock happen together (no await
    /// between), so a telemetry tick can't slip in and re-lock the stale set first. Returns
    /// whether it refreshed, so it reads as "auto-refreshed" vs "skipped, deviation in progress".
    @discardableResult
    func autoRefreshDeviationsUnlessDeviating() -> Bool {
        guard !weatherDeviation.state.isCommittedDeviation else { return false }
        refreshDeviationsFromCurrentRadar()
        return true
    }

    private func cancelWeatherRefreshTimer() {
        weatherRefreshTimer?.cancel()
        weatherRefreshTimer = nil
    }

    func refreshWeather() async {
        if settings.mockMode {
            let metars = mock.sampleMETARs()
            departureMETAR = metars.first { $0.icao == flightPlan.departure } ?? metars.first
            destinationMETAR = metars.first { $0.icao == flightPlan.destination } ?? metars.dropFirst().first
            destinationTAF = mock.sampleTAF()
            pireps = mock.samplePIREPs()
            // Mock precipitation cell (crosses the route ~40 NM ahead) drives the
            // offline weather-deviation demo. Live radar sampling is Mock-Mode-off.
            radarOverlay.mockCells = mock.sampleRadarCells()
            radarOverlay.sampledCells = []
            lastAviationWeatherUpdate = Date()
            recomputeRideItems()
            recomputeWeatherHazards()
            weatherStatus = "Mock weather loaded (\(metars.count) METARs, \(pireps.count) PIREPs)."
            diagnostics.weatherEndpointStatus = "Mock mode — sample data"
            await refreshATIS()
            return
        }

        // Live mode: no vector precipitation cells (radar is the NOAA image overlay).
        radarOverlay.mockCells = []

        do {
            var ids = [flightPlan.departure, flightPlan.destination, flightPlan.alternate]
                .filter { !$0.isEmpty }
            if let nearest = aircraftState.nearestAirport, !nearest.isEmpty { ids.append(nearest) }
            let metars = try await weatherService.metars(for: ids)
            departureMETAR = metars.first { $0.icao == flightPlan.departure }
            destinationMETAR = metars.first { $0.icao == flightPlan.destination }
            alternateMETAR = metars.first { $0.icao == flightPlan.alternate }
            destinationTAF = try? await weatherService.taf(for: flightPlan.destination)
            // The AWC pirep endpoint requires a bounding box (else HTTP 400), so query the
            // route/area box and let `relevantReports` narrow it to the corridor afterwards.
            if let bbox = pirepBoundingBox() {
                pireps = (try? await weatherService.pireps(bbox: bbox)) ?? []
            } else {
                pireps = []
            }
            sigmets = (try? await weatherService.airSigmets()) ?? []
            await sampleLivePrecipitation()
            lastAviationWeatherUpdate = Date()
            recomputeRideItems()
            recomputeWeatherHazards()
            weatherStatus = "Loaded \(metars.count) METARs, \(pireps.count) PIREPs, \(sigmets.count) SIGMETs."
        } catch {
            let msg = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            weatherStatus = "Weather unavailable: \(msg)"
            diagnostics.log(.weather, "Weather fetch failed: \(msg)")
            recomputeWeatherHazards()
        }
        // ATIS is independent of the weather fetch — refresh it on the same cadence
        // (connect, route change, pull-to-refresh, and the periodic timer) regardless
        // of whether weather succeeded.
        await refreshATIS()
    }

    func recomputeRideItems() {
        routeAnalyzer.config.corridorNM = settings.routeCorridorNM
        routeAnalyzer.config.altitudeBandFt = settings.altitudeBandFt
        // Distance ahead must be measured from the live aircraft fix. Fall back to the
        // departure only so PIREPs can still be filtered to the route corridor — in that
        // case the distance would be origin-relative, so it is flagged and not shown.
        let liveAircraft = aircraftState.coordinate
        guard let pos = liveAircraft ?? resolvedDepartureCoordinate() else {
            rideReportItems = []
            routeSigmets = []
            return
        }
        let end = resolvedDestinationCoordinate()
            ?? flightPlan.nextWaypoint(from: pos)?.coordinate
        let liveAlt = aircraftState.altitudeMSL ?? Double(flightPlan.cruiseAltitude)
        // Ride reports describe the cruise portion of the route ahead, so evaluate a
        // PIREP's altitude relevance against the planned cruise level (within the
        // ±tolerance band) rather than the live altitude — otherwise en-route
        // turbulence at cruise is filtered out while the aircraft is still climbing.
        // Fall back to the live altitude before a cruise level is set.
        let referenceAlt = flightPlan.cruiseAltitude > 0 ? Double(flightPlan.cruiseAltitude) : liveAlt
        rideReportItems = routeAnalyzer.relevantReports(pireps: pireps, position: pos,
                                                        routeEnd: end, altitudeFt: referenceAlt,
                                                        routeFixes: routeNamedFixes(),
                                                        positionIsLiveAircraft: liveAircraft != nil)
        let arrivalPhase = [.descent, .approach, .landing, .taxiIn, .parked].contains(phase)
        let nearMETAR = arrivalPhase ? (destinationMETAR ?? departureMETAR) : (departureMETAR ?? destinationMETAR)
        // Only SIGMETs whose area lies along the route may raise the ride index — a
        // nationwide turbulence advisory far from the route must not read as "severe".
        // Test the full route ahead (aircraft → remaining fixes → destination) so an
        // advisory on a later leg past a turn is caught, not just one on the straight
        // line to the destination.
        let routePolyline = [pos] + upcomingRouteCoordinates(from: pos)
        routeSigmets = routeAnalyzer.relevantSigmets(sigmets, routePolyline: routePolyline)
        // Wind shear is a low-level, surface-driven effect, so it keys off the live
        // altitude; the PIREP altitude band above keys off the cruise reference.
        rideAssessment = turbulenceModel.assess(items: rideReportItems, sigmets: routeSigmets,
                                                metar: nearMETAR, altitudeFt: liveAlt)
    }

    // MARK: - Radar precipitation + weather deviation
    //
    // Simulation/training/entertainment only — never real-world flight safety
    // guidance. Radar precipitation comes from the approved NOAA/NWS source where
    // NOAA provides coverage; outside coverage the overlay reports unavailable and
    // only existing aviation advisories (SIGMET etc.) drive the deviation flow.

    /// Use the mock precipitation provider in Mock Mode, or the live NOAA → OPERA →
    /// NASA selection otherwise.
    private func applyRadarProvider() {
        precipService.useMockProvider(settings.mockMode)
    }

    /// Throttle state for continuously resampling live precipitation as the aircraft
    /// moves (so the deviation line tracks the weather rather than only updating on a
    /// manual refresh). Guards against overlapping fetches.
    private var lastPrecipSampleAt: Date?
    private var lastPrecipSamplePos: CLLocationCoordinate2D?
    private var isSamplingPrecip = false
    /// Whether a live radar sample has actually **produced cells** for the current route
    /// yet — set only once `sampleLivePrecipitation` finishes decoding an image into
    /// `radarOverlay.sampledCells`, and reset whenever those cells go stale (route change,
    /// refreshed weather, satellite-setting change). This gates the one-time deviation lock.
    /// `lastPrecipSampleAt` can't: it is stamped at the *start* of a sample, before the
    /// async fetch lands, so a telemetry tick during the fetch would otherwise lock an empty
    /// set (no cells yet) and, since the set never re-locks, the mint line would never draw
    /// in live mode even though the storm is right on the route.
    /// Internal (not `private`) so tests can drive the "cells landed" transition directly.
    var livePrecipCellsReady = false
    /// Actual OPERA/CIRRUS composite bytes downloaded (latest / session total), read
    /// from the shared composite store for the Weather Diagnostics data-usage row.
    private var ordDataUsage: (last: Int, total: Int) = (0, 0)

    /// Reset the weather-deviation interaction (between flights / on mode change).
    private func resetWeatherDeviation() {
        weatherDeviation.reset()
        weatherDeviation.state = .none
        activeWeatherConflict = nil
        weatherHazards = []
        radarOverlay.sampledCells = []
        weatherHandled = false
        mockWeatherAdvisoryIssued = false
        lastConflictSeenAt = nil
        lastOffPathReplanAt = nil
        lastAutoTurnIssuedAt = nil
        weatherDeviationPreviews = []
        lockedDeviations = []
        deviationsLocked = false
        lockedRouteKey = ""
        lockedSampleStamp = nil
        emptyLockResampleRetries = 0
        lastPrecipSampleAt = nil
        lastPrecipSamplePos = nil
        livePrecipCellsReady = false
        lastTelemetryCoordinate = nil
        lastTelemetryAt = nil
        telemetryResyncPending = false
    }

    /// The precipitation overlay image URL for the map's visible region, or nil when
    /// the overlay is off / uncovered / in Mock Mode (which draws vector cells).
    func radarImageURL(region: MKCoordinateRegion, size: CGSize) -> URL? {
        guard !settings.mockMode, radarOverlay.shouldDisplay else { return nil }
        return precipService.imageURL(for: region, size: size)
    }

    /// Rebuild the precipitation overlay descriptive state, the normalized hazard
    /// list, and the active route-weather conflict. Selects a provider in
    /// NOAA → OPERA → NASA order. Pure/cheap; safe to call every tick.
    func recomputeWeatherHazards(telemetryJumped: Bool = false) {
        // Scope the discontinuity to this pass so the helpers below (conflict hysteresis,
        // turn issuers) can see it without threading it through every call. Only the live
        // telemetry path passes `true`; the manual refresh / route-change callers don't.
        telemetryDiscontinuity = telemetryJumped
        defer { telemetryDiscontinuity = false }
        let positions = [aircraftState.coordinate,
                         resolvedDepartureCoordinate(),
                         resolvedDestinationCoordinate()].compactMap { $0 }
        let enabled = settings.noaaRadarOverlay == .autoWhereAvailable
        let provider = enabled ? precipService.selectedProvider(for: positions) : nil
        let coverage = enabled && provider != nil

        radarOverlay.isEnabled = enabled
        radarOverlay.coverageAvailable = coverage
        radarOverlay.opacity = settings.radarOpacity
        if let provider {
            radarOverlay.sourceDescription = provider.displayName
            radarOverlay.attributionText = provider.attributionText
            radarOverlay.coverageLabel = provider.coverageDescription
            radarOverlay.layerType = provider.layerType
            radarOverlay.layerLabel = provider.uiLayerLabel
        }
        if coverage { radarOverlay.lastUpdated = Date() }
        weatherDeviation.radarCoverageAvailable = coverage
        weatherDeviation.radarSourceDescription = provider?.displayName ?? radarOverlay.sourceDescription

        let hazards = buildWeatherHazards(provider: provider)
        weatherHazards = hazards

        guard let pos = aircraftState.coordinate ?? resolvedDepartureCoordinate(),
              pos.isValid else {
            activeWeatherConflict = nil
            weatherDeviationPreviews = []
            updateWeatherDiagnostics(conflict: nil)
            return
        }
        // Find every deviation across the whole flight plan once and lock them in place
        // (a route change, a pull-to-refresh, or the 5-min auto-refresh recomputes them; a
        // telemetry tick never does). The mint lines are drawn from this fixed set, so they
        // stop shifting.
        ensureLockedDeviationsComputed()
        // A locked line whose entry point the aircraft has flown past (or missed) can no
        // longer be flown as drawn — redraw it ahead of the aircraft and have ATC say so,
        // before the deviation for this tick is selected.
        maybeRedrawDeviationPastEntry(from: pos)
        // The deviation the aircraft is currently working is *selected* from that locked
        // set by its position along the route — with the range flags (banner / draw / solid)
        // refreshed live — rather than re-solving the geometry every tick.
        let detected = selectActiveLockedDeviation(from: pos)
        // Apply confirm-clear hysteresis so the mint line / banner don't blink out on
        // a noisy resample that momentarily loses a storm that's really still ahead.
        let conflict = resolveConflictWithHysteresis(detected: detected)
        activeWeatherConflict = conflict

        // The weather ahead has cleared: forget the "handled" flag and roll back a
        // not-yet-committed deviation lifecycle so a new conflict prompts afresh.
        // A turbulence / icing ride advisory (no precip conflict) keeps its lifecycle,
        // so it isn't torn down on every telemetry tick while it's still along route.
        // Never tear it down on a discontinuity tick: a jumped/stale fix that momentarily
        // reads the route clear must not wipe an issued-but-unanswered advisory — that is
        // what left the response card gone after returning from the background.
        if conflict == nil, activeRideSigmet == nil, !telemetryDiscontinuity {
            weatherHandled = false
            mockWeatherAdvisoryIssued = false
            switch weatherDeviation.state {
            case .weatherAheadDetected, .advisoryIssued, .awaitingPilotIntentions, .deviationRequested:
                weatherDeviation.reset()
            default:
                break
            }
        }

        // Mock Mode auto-issues the advisory once so the offline demo plays out.
        maybeAutoIssueMockAdvisory(conflict: conflict)
        if telemetryDiscontinuity {
            // The fix just jumped (returned from the background on a frozen socket, or a
            // stale fix snapping to the live position). Re-derive the in-progress deviation
            // from the aircraft's current position instead of replaying the turns it
            // "passed" during the gap as a queue of near-identical vector calls — and don't
            // auto-issue an advisory off a jumped position (it would fire too early). If the
            // jump carried the aircraft past the entry turn, the reroute is recomputed from
            // where it actually is so it doesn't fly straight through the weather.
            resyncWeatherDeviation()
        } else {
            // Safety net (all modes): if the pilot ignored the banner and is now within
            // `deviationAutoCallNM` of the upcoming locked deviation's turn-out, ATC auto-issues
            // the advisory so a drawn-ahead deviation isn't silently flown past.
            maybeAutoIssueAdvisoryNearTurn()
            // Drive the deviation turns off the aircraft's progress, most-imminent first:
            //   1. a held beginning turn fires once the aircraft reaches the mint line's
            //      turn-out (a deviation approved while drawn ahead — "expect the turn …");
            //   2. else, while vectoring, the interior turns fire at each deviation vertex
            //      (an armed turn wins: flying wide of a vertex is what it already handles);
            //   3. else, having drifted off the line being flown re-plans it from where the
            //      aircraft actually is;
            //   4. else, reaching the rejoin end without a clear-of-weather auto-resumes.
            // At most one fires per tick, so they never race.
            if !maybeIssueDeviationStartTurn() {
                if !maybeIssueWeatherRejoinTurn() {
                    if !maybeReplanDeviationOffPath() {
                        maybeAutoResumeAtRouteIntercept()
                    }
                }
            }
        }
        // Faint lines for every locked deviation except the one currently drawn solid
        // (`weatherDeviationLine`). Derived from the locked set, so they hold steady.
        weatherDeviationPreviews = faintDeviationLines()
        updateWeatherDiagnostics(conflict: conflict)
    }

    /// Apply confirm-clear hysteresis to the raw per-tick detection so the mint line
    /// and "contact ATC" banner stay put through a noisy radar resample. Live radar
    /// sampling is noisy: a storm that is really still ahead can drop out of a single
    /// sample and return on the next, which — read straight through — blinks the mint
    /// line and banner on and off at the resample cadence. So once a conflict is
    /// shown we keep returning it until the route has tested continuously clear for
    /// `weatherClearConfirmWindow` (a *confirmed* clean route), rather than dropping
    /// it the instant one sample comes back empty. A committed deviation is never
    /// torn down here — the pilot is already flying the mint line; it settles only on
    /// clear-of-weather.
    private func resolveConflictWithHysteresis(detected: RouteWeatherConflict?) -> RouteWeatherConflict? {
        if let detected {
            lastConflictSeenAt = Date()
            return detected
        }
        // Nothing detected this tick. With no conflict currently shown, stay clear.
        guard let held = activeWeatherConflict else {
            lastConflictSeenAt = nil
            return nil
        }
        // A committed deviation keeps its conflict/mint line regardless of a momentary
        // clear — the pilot is following the approved reroute.
        if weatherDeviation.state.isCommittedDeviation { return held }
        // A discontinuity tick (a jumped/stale fix after a background gap) isn't a
        // trustworthy "clear" reading — hold the last shown conflict rather than letting
        // it, or a confirm-clear window that elapsed on wall-clock while backgrounded,
        // drop a storm that's really still ahead. Restart the confirm-clear window from
        // here too: the window is measured on wall-clock, so one left running from before
        // the gap has already expired, and the *next* tick — no longer flagged as a
        // discontinuity — would drop the conflict on its first empty sample. That is what
        // tore an issued-but-unanswered advisory down one tick after this guard saved it,
        // leaving "say intentions" in the transcript with no response card on screen.
        // Requiring a full window of continuous clear readings after the gap gives the
        // first live resample time to land.
        if telemetryDiscontinuity {
            lastConflictSeenAt = Date()
            return held
        }
        // Otherwise hold the last shown conflict until the clear is confirmed.
        if let since = lastConflictSeenAt, Date().timeIntervalSince(since) < weatherClearConfirmWindow {
            return held
        }
        lastConflictSeenAt = nil
        return nil
    }

    // MARK: - Locked whole-route deviations

    /// Recompute the locked deviation set once per route+radar. The set is found in a
    /// single pass across the whole flight plan and then held (locked in place) — a
    /// telemetry tick never re-solves the geometry, so the mint lines stop shifting,
    /// flickering, or drawing crazy triangles. It re-locks only when the route changes (new
    /// flight plan), on a pull-to-refresh (`refreshDeviationsFromCurrentRadar`), or on the
    /// 5-min auto-refresh.
    ///
    /// Crucially, only a **non-empty** result is frozen. An empty solve is left unlocked so the
    /// next recompute re-solves it — because the *first* recompute of a new flight routinely
    /// runs before the weather cells are in place: in mock mode the simulator's immediate
    /// telemetry emit fires `recomputeWeatherHazards` before the async `refreshWeather` loads
    /// the mock cells; in live mode the first radar frame can decode empty or partial (a cold
    /// fetch). Freezing that empty result is what left the mint lines missing until a manual
    /// pull-to-refresh. Leaving it unlocked lets the lines appear on their own once the cells
    /// land. In live mode the re-solve is bounded to the sample cadence (keyed on the sample
    /// stamp) so it never re-runs the whole-route search on the every-tick telemetry path; in
    /// mock mode the cells are synchronous and an empty solve is a cheap early return.
    private func ensureLockedDeviationsComputed() {
        let key = routeFingerprint()
        if key != lockedRouteKey {          // a new/edited route → discard the old lock
            lockedRouteKey = key
            deviationsLocked = false
            lockedSampleStamp = nil
            deviationWalkFloor = nil        // a fresh route walks from the departure again
            // A fresh corridor: allow a few quick resamples so a partial first radar frame
            // doesn't leave the mint lines missing until a manual refresh.
            emptyLockResampleRetries = emptyLockResampleRetryBudget
        }
        guard !deviationsLocked else { return }
        // Don't lock before there's radar data for this flight: in live mode wait for the
        // first sample to actually PRODUCE cells (not merely start — `lastPrecipSampleAt`
        // is stamped before the async fetch lands, so locking on it captures an empty set
        // mid-fetch and never re-locks). In Mock Mode the cells are set synchronously before
        // recompute runs, so no wait is needed.
        guard settings.mockMode || livePrecipCellsReady else { return }
        // Live mode: skip re-solving only when this exact sample was already solved and came
        // up empty — wait for a fresher one. (Mock cells are set synchronously, so there is no
        // in-flight sample to gate on; recomputing an empty set is a cheap early return.)
        if !settings.mockMode, lockedDeviations.isEmpty,
           lockedSampleStamp != nil, lockedSampleStamp == lastPrecipSampleAt {
            return
        }
        lockedSampleStamp = lastPrecipSampleAt
        recomputeLockedDeviations()
        // Only treat the set as locked once it actually produced lines — an empty result must
        // never freeze the mint lines off. In mock mode the first recompute runs on the
        // simulator's immediate telemetry emit, *before* `refreshWeather` has loaded the mock
        // cells; in live mode the first radar frame can decode empty. Leaving an empty result
        // unlocked lets the next recompute (once the cells are in place) fill it, so the lines
        // draw on their own instead of only after a manual pull-to-refresh. A non-empty set
        // still locks and holds (stable across telemetry ticks and transient radar clears).
        deviationsLocked = !lockedDeviations.isEmpty
    }

    /// Walk the entire filed route from the origin to the destination and, at each weather
    /// system that qualifies (a cell on the flight path per the corridor threshold), find
    /// the best deviation around it: a ~30° turn-out, a parallel leg hugging the system's
    /// edge, and a ~30° turn-back to the route (with the extra interior turns the detector
    /// adds when more weather sits before the rejoin). Once a deviation rejoins the route,
    /// the walk continues down the route to the next system, and so on to the destination —
    /// so every deviation on the whole plan is produced in one pass. The result is stored in
    /// `lockedDeviations` and held until the next refresh. Bounded so it can't run away.
    private func recomputeLockedDeviations() {
        // Belongs to the walk about to run — a stale one would explain away weather this
        // recompute never even looked at.
        discardedOnRouteExcursionNM = nil
        guard radarOverlay.isEnabled, radarOverlay.coverageAvailable, !weatherHazards.isEmpty else {
            lockedDeviations = []
            return
        }
        guard let origin = resolvedDepartureCoordinate()
                ?? flightPlan.firstWaypointCoordinate
                ?? aircraftState.coordinate, origin.isValid else {
            lockedDeviations = []
            return
        }
        // Begin the route walk at least `weatherRejoinAirportMarginNM` past the departure end of
        // the route, so the first mint line never starts within that distance of the departure
        // airport — weather on the immediate climb-out is handled by departure vectors, not a
        // drawn enroute deviation. Only when we actually have the departure end of the route
        // (not the aircraft-position fallback, where skipping ahead would drop weather right in
        // front of the aircraft). The turn-out is shaped forward from this start, so no drawn
        // line begins before it.
        var walkStart = origin
        if let departure = resolvedDepartureCoordinate() ?? flightPlan.firstWaypointCoordinate,
           departure.isValid,
           let floored = pointAlongRoute(from: departure, through: upcomingRouteCoordinates(from: departure),
                                         byNM: weatherRejoinAirportMarginNM) {
            walkStart = floored
        }
        // Never walk from behind a redraw floor. Once a drawn line's entry point has fallen
        // behind the aircraft and the deviation has been redrawn ahead of it, a later
        // recompute (the 5-min auto-refresh, a pull-to-refresh, a fresh radar sample) would
        // otherwise walk from the departure again and re-produce that same line behind the
        // aircraft.
        if let floor = deviationWalkFloor, floor.isValid,
           alongRouteNM(floor) > alongRouteNM(walkStart) {
            walkStart = floor
        }
        // Run the full optimized search for every system in one synchronous pass — gap
        // doglegs, edge-following hull hugs, return-leg repair, multi-leg wrap, and the
        // adjacent-hug fold. It is fast enough to render directly (the earlier "quick hug
        // first, then refine in the background" two-step existed only to bridge a slow solve
        // that was really the radar-polygon sampling, since fixed), so there is no longer a
        // preliminary line to paint or a background swap to reconcile.
        lockedDeviations = computeDeviations(from: walkStart, cap: weatherRejoinCap())
    }

    /// Walk the filed route from `origin` to the destination and produce the deviation around
    /// each qualifying weather system (see `recomputeLockedDeviations`) — the full optimized
    /// search for every one. Bounded so it can't run away.
    private func computeDeviations(from origin: CLLocationCoordinate2D,
                                   cap: CLLocationCoordinate2D?) -> [RouteWeatherConflict] {
        var results: [RouteWeatherConflict] = []
        var startPoint = origin
        var steps = 0
        while results.count < maxWeatherPreviewSystems, steps < maxWeatherPreviewSystems * 4 {
            steps += 1
            let aheadFromStart = upcomingRouteCoordinates(from: startPoint)
            guard !aheadFromStart.isEmpty else { break }
            // Where does the route next enter qualifying weather, measured from startPoint?
            guard let onRoute = conflictDetector.nearestRouteHazard(
                    route: aheadFromStart, from: startPoint, hazards: weatherHazards) else { break }
            // Solve the deviation from a modest lead BEFORE that entry, where the route is
            // locally straight — not from startPoint, which may be hundreds of NM back
            // across the route's bends and would render as a runaway line past the weather.
            let lead = min(onRoute.distanceNM, deviationSolveLeadNM)
            let detectAlong = max(0, onRoute.distanceNM - lead)
            let detectPos = detectAlong <= 1 ? startPoint
                : (pointAlongRoute(from: startPoint, through: aheadFromStart, byNM: detectAlong) ?? startPoint)
            let ahead = upcomingRouteCoordinates(from: detectPos)
            guard !ahead.isEmpty else { break }
            let course = ahead.first { Geo.distanceNM(from: detectPos, to: $0) > 1 }
                .map { Geo.bearing(from: detectPos, to: $0) } ?? currentCourse(from: detectPos)
            if let conflict = conflictDetector.detectConflict(
                    position: detectPos, course: course, groundspeedKnots: aircraftState.groundSpeed,
                    phase: .cruise, hazards: weatherHazards, waypoints: flightPlan.waypoints,
                    routeAhead: ahead, rejoinCap: cap),
               conflict.deviationPath.count >= 2,
               // Only keep a line that actually rounds weather — never a degenerate line
               // drawn out in clear air — so every mint line hugs a real system.
               conflictDetector.pathEngagesWeather(conflict.deviationPath, hazards: weatherHazards),
               // And its apex must sit alongside the weather, not bulge off into clear air —
               // the guard that drops a straight-corridor stub aimed across a route bend.
               conflictDetector.previewApexHugsWeather(conflict.deviationPath,
                                                       route: [detectPos] + ahead, hazards: weatherHazards),
               let end = conflict.deviationPath.last, end.isValid,
               Geo.distanceNM(from: detectPos, to: end) > 1 {
                results.append(conflict)
                startPoint = end       // jump past this system's rejoin; its cells are behind
            } else if let next = pointAlongRoute(from: startPoint, through: aheadFromStart,
                                                 byNM: onRoute.distanceNM + previewScanStepNM),
                      Geo.distanceNM(from: startPoint, to: next) > 1 {
                startPoint = next      // couldn't solve here — scan past this system
            } else {
                break                  // reached the end of the route
            }
        }
        // Fold runs of short, back-to-back deviations (each rejoin within reach of the next
        // turn-out, same side) into one continuous parallel hug down the whole system, so a
        // complex multi-cell system draws a single long parallel line instead of a string of
        // little in-and-out jogs that each rejoin inside the next cell. The route the fold may
        // slide a merged rejoin along is truncated at the cap, so that slide can't push a
        // rejoin past the airport margin either.
        let mergeRoute = routeTruncated(upcomingRouteCoordinates(from: origin), at: cap)
        let merged = conflictDetector.mergeAdjacentDeviations(
            results, hazards: weatherHazards, route: mergeRoute)
        // Finally, soften any hard turn back onto the flight plan by rejoining at a fix farther
        // down the route — bounded by the next deviation's turn-out, so a softened rejoin can
        // never run into the following reroute.
        let softened = merged.enumerated().map { index, deviation in
            let nextTurnOutAlong = index + 1 < merged.count
                ? merged[index + 1].deviationPath.first.map { alongRouteNM($0) - mergeAdjacentRejoinMarginNM }
                : nil
            return deviationWithGentleRejoin(deviation, cap: cap,
                                             limitAlong: nextTurnOutAlong ?? .greatestFiniteMagnitude)
        }
        // Measure each line's lateral excursion from the filed route now that it is finally
        // shaped — the merge and the gentle rejoin above both move vertices, so anything
        // measured earlier would describe a path that no longer exists.
        //
        // A line that never leaves the route is **discarded here**, not merely left undrawn.
        // It used to stay in the locked set on the reasoning that the weather was still real
        // and worth prompting about — but the set is what the aircraft works from, and a
        // deviation nobody can fly poisons every stage downstream of it: it is selected as the
        // active conflict (the first one whose rejoin is still ahead), so it raises the banner,
        // issues the advisory, and puts up the response card for a maneuver with no line to
        // turn onto — and, being selected, it stands in front of the *next* deviation, which
        // may be a perfectly good line 20 NM further on that the pilot can see drawn faint and
        // cannot commit to. Throwing it out lets the walk's next system become the active one
        // and be flown. The weather itself is not forgotten: it is still sampled, still on the
        // route, and Diagnostics still reports it (as monitored, with the discarded line's
        // excursion), so nothing about it goes silent — only the un-flyable maneuver does.
        let filedRoute = ([origin] + upcomingRouteCoordinates(from: origin)).filter { $0.isValid }
        var discarded: Double?
        let measured = softened.compactMap { deviation -> RouteWeatherConflict? in
            var deviation = deviation
            deviation.maxRouteExcursionNM = conflictDetector.routeExcursionNM(deviation.deviationPath,
                                                                              route: filedRoute)
            guard deviation.maxRouteExcursionNM >= conflictDetector.config.minRouteExcursionNM else {
                if discarded == nil { discarded = deviation.maxRouteExcursionNM }
                return nil
            }
            return deviation
        }
        discardedOnRouteExcursionNM = discarded
        return measured
    }

    /// Re-run the whole-route deviation search now and re-lock the result. Samples fresh
    /// radar first (live mode), then re-solves every deviation across the plan. Everything
    /// else (ATC calls, the auto-called turns, timings) is unchanged.
    func refreshDeviations() async {
        if !settings.mockMode {
            await sampleLivePrecipitation()
        }
        refreshDeviationsFromCurrentRadar()
    }

    /// Re-solve every deviation across the whole plan against the **current** radar sample
    /// and re-lock the result — the shared core of the manual pull-to-refresh, the automatic
    /// 5-min refresh, and `refreshDeviations`. It does not itself fetch radar; the caller
    /// decides whether to sample fresh cells first (pull-to-refresh does, via `refreshWeather`).
    func refreshDeviationsFromCurrentRadar() {
        deviationsLocked = false           // force a fresh lock…
        // …a clean slate, so a stale confirm-clear hold can't keep a pre-refresh conflict the
        // re-locked set no longer contains…
        lastConflictSeenAt = nil
        recomputeWeatherHazards()          // …then rebuild hazards, re-lock, re-select, redraw.
    }

    /// Select the deviation the aircraft is currently working from the locked set: the
    /// next one whose rejoin still lies ahead along the route. Its range flags (banner /
    /// draw / solid) are refreshed from the live distance to its turn-out, so the ATC
    /// advisory and the faint→solid transition fire at the right time even though the
    /// geometry itself is locked. Nil once every deviation has been flown past.
    private func selectActiveLockedDeviation(from pos: CLLocationCoordinate2D) -> RouteWeatherConflict? {
        guard !lockedDeviations.isEmpty else { return nil }
        let aircraftAlong = alongRouteNM(pos)
        var best: RouteWeatherConflict?
        var bestAlong = Double.greatestFiniteMagnitude
        for dev in lockedDeviations {
            guard let end = dev.deviationPath.last, end.isValid else { continue }
            let endAlong = alongRouteNM(end)
            if endAlong <= aircraftAlong - 2 { continue }   // already flown past its rejoin
            if endAlong < bestAlong { bestAlong = endAlong; best = dev }
        }
        guard var dev = best, let start = dev.deviationPath.first else { return nil }
        // Refresh proximity from the live aircraft position: distance to the turn-out point
        // (the start of the drawn maneuver, a short lead just before the weather).
        let d = Geo.distanceNM(from: pos, to: start)
        dev.distanceAheadNM = d
        dev.withinTacticalRange = d <= conflictDetector.config.deviationTriggerNM
        dev.withinDrawRange = d <= conflictDetector.config.mintLineDrawNM
        dev.shouldPrompt = dev.withinTacticalRange && (dev.isConvectiveSigmet || dev.severity >= .moderate)
        return dev
    }

    // MARK: - Entry point behind the aircraft → redraw ahead

    /// Whether a drawn deviation's **entry point** — the turn-out at the start of the mint
    /// line — now lies behind the aircraft, so the line can no longer be flown as drawn.
    ///
    /// Measured along the filed route (the same projection that orders the locked set and
    /// tells which deviations have been flown past), so it reads correctly whether the
    /// aircraft is tracking the course or sitting off to one side of it — the "missed the
    /// entry point" case as much as the "flew straight past it" one.
    private func deviationEntryIsBehind(_ deviation: RouteWeatherConflict,
                                        position pos: CLLocationCoordinate2D) -> Bool {
        guard let entry = deviation.deviationPath.first, entry.isValid else { return false }
        return alongRouteNM(entry) < alongRouteNM(pos) - deviationEntryPassedNM
    }

    /// Redraw the mint line when its entry point has fallen behind the aircraft.
    ///
    /// The locked deviations are solved for the whole route and then held, so the aircraft can
    /// end up past the turn-out at the start of one — the pilot ignored the banner and flew by
    /// it, or a position jump carried the aircraft beyond it — leaving the reroute drawn
    /// *behind* the aircraft, where it is no longer flyable. When that happens the deviations
    /// are re-solved starting `deviationRedrawAheadNM` (20 NM) in front of the aircraft, so the
    /// new turn-out sits ahead with room to work it, and the controller advises the revised
    /// deviation.
    ///
    /// A committed deviation is never redrawn: the pilot is already flying that frozen line,
    /// whose start is legitimately behind them once the turn is made (and a held beginning
    /// turn fires as the aircraft passes abeam its turn-out — see
    /// `maybeIssueDeviationStartTurn`).
    private func maybeRedrawDeviationPastEntry(from pos: CLLocationCoordinate2D) {
        guard weatherFlowAllowed, !weatherDeviation.state.isCommittedDeviation,
              let stale = selectActiveLockedDeviation(from: pos),
              deviationEntryIsBehind(stale, position: pos) else { return }
        redrawDeviationsAhead(of: pos)
    }

    /// Re-solve the whole-route deviation walk starting `deviationRedrawAheadNM` ahead of the
    /// aircraft and notify the pilot of the revised deviation. The redraw point becomes the
    /// walk floor, so later recomputes can't step back behind the aircraft and re-produce the
    /// stale line. When nothing solves from there (the weather is now abeam or behind, or the
    /// route ends within the redraw distance) the stale line is simply dropped — better no
    /// line than one drawn behind the aircraft — and no call is made.
    private func redrawDeviationsAhead(of pos: CLLocationCoordinate2D) {
        let ahead = upcomingRouteCoordinates(from: pos)
        // The point 20 NM along the route from here; if the route ends first, floor at its end
        // so the walk simply finds nothing rather than re-solving the stale line every tick.
        deviationWalkFloor = pointAlongRoute(from: pos, through: ahead, byNM: deviationRedrawAheadNM)
            ?? ahead.last(where: { $0.isValid })
        recomputeLockedDeviations()
        deviationsLocked = !lockedDeviations.isEmpty
        // Re-select against the fresh set; only a line that now genuinely sits ahead is
        // announced (a degenerate re-solve is dropped silently rather than announced).
        guard let fresh = selectActiveLockedDeviation(from: pos),
              !deviationEntryIsBehind(fresh, position: pos),
              let entry = fresh.deviationPath.first, entry.isValid else {
            // Nothing solves ahead any more. Release the confirm-clear hold as well, or the
            // hysteresis would keep drawing the stale line behind the aircraft for the
            // length of the confirm window.
            activeWeatherConflict = nil
            lastConflictSeenAt = nil
            return
        }
        // A re-solve that deviates nowhere is not announced: there is no revised line to be
        // turned onto, and the call would promise one the map isn't drawing. The conflict
        // stays selected, so the weather is still worked through the normal advisory.
        guard deviationLeavesRoute(fresh) else { return }
        announceRedrawnDeviation(conflict: fresh, distanceNM: Geo.distanceNM(from: pos, to: entry))
    }

    /// The controller advises that the weather deviation has been redrawn ahead of the
    /// aircraft, with the distance to the new turn — and, unless the pilot is already deciding
    /// or already flying an approved deviation, opens the decision so the response card and its
    /// buttons come up with the call and the revised deviation can be activated on the spot.
    /// A pilot who is already deciding keeps their pending decision (and the near-turn advisory
    /// still to auto-issue for the redrawn line) exactly as it was.
    private func announceRedrawnDeviation(conflict: RouteWeatherConflict?, distanceNM: Double) {
        guard settings.weatherDeviationAlerts.alertsEnabled, !establishedOnFinal else { return }
        let result = deviationEngine.advisePathRedrawn(
            cs: callsignNow(), distanceNM: max(0, Int(distanceNM.rounded())), conflict: conflict,
            context: weatherDeviation, facility: weatherFacility)
        // Posted directly rather than through `applyDeviationResult`: ATC volunteering an
        // update is not the pilot engaging the advisory, so the "engaged" flag
        // (`weatherHandled`) is left exactly as it was. The lifecycle the engine returns is
        // what decides whether the near-turn advisory still has anything to add. The call is
        // controller-initiated, so a repeat of one the pilot already acknowledged is held.
        for tx in result.atc where !isAcknowledgedRepeat(tx) { post(tx, speak: true) }
        weatherDeviation = result.context
        updateWeatherDiagnostics(conflict: activeWeatherConflict)
    }

    /// The faint mint lines: every upcoming locked deviation except the one currently drawn
    /// solid (`weatherDeviationLine`, matched by its turn-out so it is never double-drawn).
    /// Deviations the aircraft has already flown past (rejoin behind it) are dropped, so a
    /// storm left behind stops showing a line.
    private func faintDeviationLines() -> [[CLLocationCoordinate2D]] {
        let solidStart = weatherDeviationLine?.first
        let aircraftAlong = aircraftState.coordinate.map { alongRouteNM($0) }
        return lockedDeviations.compactMap { dev in
            let path = dev.deviationPath
            guard path.count >= 2, let p0 = path.first else { return nil }
            guard deviationLeavesRoute(dev) else { return nil }                         // deviates nowhere
            if let s = solidStart, Geo.distanceNM(from: s, to: p0) < 2 { return nil }   // drawn solid
            if let ac = aircraftAlong, let end = path.last, end.isValid,
               alongRouteNM(end) <= ac - 2 { return nil }                                // already passed
            return path
        }
    }

    // MARK: - Gradual return to course (rejoin at a fix farther down)

    /// Soften a hard turn back onto the flight plan.
    ///
    /// The closing leg intercepts the route wherever the geometry happens to put it, which can
    /// leave the aircraft turning sharply onto course at the rejoin — and the controller calling
    /// that sharp turn. When the final turn of the drawn line exceeds `maxRejoinTurnDegrees`
    /// (60°), the rejoin is moved to the **next fix down the route**: the closing leg gets
    /// longer, so the intercept — and the turn called at the last vertex — is gradual, and the
    /// aircraft simply proceeds direct that fix, which is how the return to course is flown for
    /// real. Fixes are tried in order; the first gentle-enough one whose closing leg stays clear
    /// of the weather wins, else the shallowest clear one, else the line is left as it was.
    ///
    /// The rejoin never moves past `cap` (the airport margin / approach fix) or past
    /// `limitAlong` (the next deviation's turn-out), and the named rejoin fix is retagged to
    /// whatever the line now ends on, so "rejoin course direct …" names the fix actually flown to.
    private func deviationWithGentleRejoin(_ conflict: RouteWeatherConflict,
                                           cap: CLLocationCoordinate2D?,
                                           limitAlong: Double) -> RouteWeatherConflict {
        guard let softened = gentleRejoin(for: conflict.deviationPath, cap: cap,
                                          limitAlong: limitAlong) else { return conflict }
        var conflict = conflict
        conflict.deviationPath = softened.path
        conflict.rejoinFix = softened.fix
        return conflict
    }

    /// The path/fix pair a gradual return to course would use, or nil when the final turn is
    /// already gentle enough (or no reachable fix improves it). See `deviationWithGentleRejoin`.
    private func gentleRejoin(for path: [CLLocationCoordinate2D], cap: CLLocationCoordinate2D?,
                              limitAlong: Double) -> (path: [CLLocationCoordinate2D], fix: Waypoint)? {
        let pts = path.filter { $0.isValid }
        // A 2-point line has no turn onto its closing leg to soften.
        guard pts.count >= 3, let rejoin = pts.last else { return nil }
        let turnVertex = pts[pts.count - 2]
        let inbound = Geo.bearing(from: pts[pts.count - 3], to: turnVertex)
        func turnDegrees(to end: CLLocationCoordinate2D) -> Double {
            courseChangeDegrees(inbound: inbound, outbound: Geo.bearing(from: turnVertex, to: end))
        }
        let currentTurn = turnDegrees(to: rejoin)
        guard currentTurn > maxRejoinTurnDegrees else { return nil }

        // The fixes still down-route of the current rejoin, in order, inside both bounds.
        var capAlong = limitAlong
        if let cap, cap.isValid { capAlong = min(capAlong, alongRouteNM(cap)) }
        let rejoinAlong = alongRouteNM(rejoin)
        let candidates = flightPlan.waypoints
            .compactMap { wp -> (fix: Waypoint, along: Double, coordinate: CLLocationCoordinate2D)? in
                guard let c = wp.coordinate, c.isValid else { return nil }
                let along = alongRouteNM(c)
                guard along > rejoinAlong + 1, along <= capAlong else { return nil }
                return (wp, along, c)
            }
            .sorted { $0.along < $1.along }

        var best: (fix: Waypoint, coordinate: CLLocationCoordinate2D, turn: Double)?
        for candidate in candidates {
            let softened = Array(pts.dropLast()) + [candidate.coordinate]
            // Reaching farther down the route must not take the closing leg back through the
            // weather the deviation exists to avoid.
            guard conflictDetector.committedPathStillClear(softened, hazards: weatherHazards) else { continue }
            let turn = turnDegrees(to: candidate.coordinate)
            if turn <= maxRejoinTurnDegrees {
                best = (candidate.fix, candidate.coordinate, turn)
                break                        // the nearest fix that is gradual enough
            }
            if best == nil || turn < best!.turn { best = (candidate.fix, candidate.coordinate, turn) }
        }
        guard let chosen = best, chosen.turn < currentTurn else { return nil }
        return (Array(pts.dropLast()) + [chosen.coordinate], chosen.fix)
    }

    /// A fingerprint of the filed route, so a new/edited flight plan discards the old
    /// locked deviations and computes a fresh set.
    private func routeFingerprint() -> String {
        var parts = [flightPlan.departure, flightPlan.destination]
        parts.append(contentsOf: flightPlan.waypoints.map { wp in
            guard let c = wp.coordinate else { return wp.name }
            return "\(wp.name):\(Int(c.latitude * 100)):\(Int(c.longitude * 100))"
        })
        return parts.joined(separator: "|")
    }

    /// The departure field's coordinate, **Infinite Flight's reported position first**
    /// (the source of truth for what the sim is actually flying), then the built-in hub
    /// table only as a last resort for a manually-entered ICAO that IF isn't reporting.
    /// `AirportDatabase` is 21 US hubs, so for essentially every live flight this resolves
    /// via IF — and where the DB *does* have the field, IF's position still wins, so the
    /// drawn route, the weather corridor, and the deviation math all agree on one location.
    private func resolvedDepartureCoordinate() -> CLLocationCoordinate2D? {
        flightPlan.departureCoordinate ?? airports.coordinate(for: flightPlan.departure)
    }

    /// The destination field's coordinate, Infinite Flight's reported position first, then
    /// the built-in hub table as a last resort. See `resolvedDepartureCoordinate`.
    private func resolvedDestinationCoordinate() -> CLLocationCoordinate2D? {
        flightPlan.destinationCoordinate ?? airports.coordinate(for: flightPlan.destination)
    }

    /// The full filed-route polyline: departure, located enroute fixes, destination.
    private func fullRoutePolyline() -> [CLLocationCoordinate2D] {
        var full: [CLLocationCoordinate2D] = []
        if let dep = resolvedDepartureCoordinate() ?? flightPlan.firstWaypointCoordinate,
           dep.isValid { full.append(dep) }
        full.append(contentsOf: flightPlan.waypoints.compactMap { $0.coordinate }.filter { $0.isValid })
        if let dest = resolvedDestinationCoordinate() ?? flightPlan.lastWaypointCoordinate,
           dest.isValid { full.append(dest) }
        return full
    }

    /// The along-route distance (NM from the origin) of the point on the filed-route
    /// polyline nearest `coord` — used to order the locked deviations and tell which the
    /// aircraft has flown past. Planar projection, consistent with the detector's geometry.
    private func alongRouteNM(_ coord: CLLocationCoordinate2D) -> Double {
        let route = fullRoutePolyline()
        guard route.count >= 2 else { return 0 }
        var cumulative = 0.0
        var bestAlong = 0.0
        var bestDist = Double.greatestFiniteMagnitude
        for i in 0..<(route.count - 1) {
            let a = route[i], b = route[i + 1]
            let segLen = Geo.distanceNM(from: a, to: b)
            let latScale = 60.0
            let lonScale = 60.0 * cos(coord.latitude * .pi / 180)
            let px = coord.longitude * lonScale, py = coord.latitude * latScale
            let ax = a.longitude * lonScale, ay = a.latitude * latScale
            let bx = b.longitude * lonScale, by = b.latitude * latScale
            let dx = bx - ax, dy = by - ay
            let lenSq = dx * dx + dy * dy
            let t = lenSq <= 0 ? 0 : max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq))
            let cx = ax + t * dx, cy = ay + t * dy
            let d = hypot(px - cx, py - cy)
            if d < bestDist { bestDist = d; bestAlong = cumulative + segLen * t }
            cumulative += segLen
        }
        return bestAlong
    }

    /// Normalize the current weather into the hazards the conflict detector routes
    /// around. The **only** driver of the weather-deviation flow (the mint line) is
    /// moderate-or-greater precipitation: the hand-authored cells in Mock Mode, or
    /// the cells sampled from the live NOAA/OPERA radar image otherwise. The
    /// detector then threads the widest clear gap between those cells.
    ///
    /// SIGMET/AIRMET advisories are intentionally **not** fed here. A SIGMET polygon
    /// is a coarse, often huge advisory box, not a precipitation shape, so routing
    /// around it produces reroutes that ignore where the storms actually are.
    /// SIGMETs still shade the route map, populate the SIGMET card, and raise the
    /// composite ride index (`recomputeRideItems` / `routeSigmets`) — they just
    /// don't steer the deviation.
    private func buildWeatherHazards(provider: RadarPrecipitationProvider?) -> [WeatherHazard] {
        guard radarOverlay.isEnabled, radarOverlay.coverageAvailable else { return [] }
        let providerConfidence = provider?.confidence ?? .high
        let providerLabel = provider?.uiLayerLabel ?? "Radar precipitation"
        // Tag satellite-estimate cells as their own source (not radar) so diagnostics /
        // phraseology never present the estimate as radar-grade.
        let isSatelliteEstimate = provider.map { $0.layerType == .satelliteEstimate } ?? false
        let hazardSource: WeatherHazardSource = isSatelliteEstimate ? .satelliteEstimate : .noaaRadar
        let precipCells = settings.mockMode ? radarOverlay.mockCells : radarOverlay.sampledCells
        return precipCells.compactMap { cell in
            guard cell.intensity >= .moderate else { return nil }
            return WeatherHazard(
                source: hazardSource, providerID: provider?.id,
                phenomenon: .precipitation, intensity: cell.intensity,
                geometry: .polygon(cell.polygon), confidence: providerConfidence,
                movementDirectionDegrees: cell.movementDirectionDegrees,
                movementSpeedKnots: cell.movementSpeedKnots,
                notes: providerLabel)
        }
    }

    /// Re-evaluate live precipitation after the satellite-estimate deviation setting is
    /// toggled. Bypasses the staleness throttle and forces a fresh sample so the mint
    /// line appears (turned on) or clears (turned off) at once, correctly *keeping* NOAA
    /// radar cells either way — the resample re-populates them and only the NASA cells
    /// come or go. In Mock Mode there is nothing live to sample, so it just recomputes.
    func applySatelliteDeviationSettingChange() {
        guard !settings.mockMode else { recomputeWeatherHazards(); return }
        lastPrecipSampleAt = nil
        lastPrecipSamplePos = nil
        // The cell set changes with this toggle, so hold the lock until the forced sample
        // below has re-populated the cells rather than re-locking against the pre-toggle set.
        livePrecipCellsReady = false
        Task { @MainActor in
            await sampleLivePrecipitation()
            recomputeWeatherHazards()
        }
    }

    /// Resample live precipitation so the deviation lines track evolving weather instead
    /// of only updating on a manual refresh. Single-flighted so fetches never overlap.
    /// Call it from the telemetry loop; it re-runs the conflict detection once fresh
    /// cells land. The sampled region is the whole route (not a window ahead of the
    /// aircraft), so it barely changes as the aircraft flies — resampling is driven
    /// mainly by staleness, with a large movement threshold as a backstop, to avoid
    /// re-fetching the bigger whole-route image on every mile flown.
    private func maybeResamplePrecipitation() {
        guard appActive, !settings.mockMode, settings.noaaRadarOverlay == .autoWhereAvailable,
              !isSamplingPrecip, let pos = aircraftState.coordinate, pos.isValid else { return }
        let now = Date()
        let movedFar = lastPrecipSamplePos.map { Geo.distanceNM(from: $0, to: pos) > 100 } ?? true
        // Right after a corridor change the deviation set hasn't locked any lines yet, and the
        // first radar frame can come back partial — so retry on a short interval (spending the
        // quick-retry budget) until a complete frame locks the mint lines, then fall back to the
        // normal cadence. This is what makes the lines appear moments after the radar loads
        // instead of only on a manual pull-to-refresh. (The active providers return small
        // server-cropped PNGs, so the extra fetches are cheap.)
        let quickRetry = !deviationsLocked && emptyLockResampleRetries > 0
        let staleInterval = quickRetry ? emptyLockResampleInterval : 60
        let stale = lastPrecipSampleAt.map { now.timeIntervalSince($0) > staleInterval } ?? true
        guard movedFar || stale else { return }
        if quickRetry { emptyLockResampleRetries -= 1 }
        isSamplingPrecip = true
        Task { @MainActor in
            await sampleLivePrecipitation()
            isSamplingPrecip = false
            // Record real ORD composite data usage for the diagnostics row.
            ordDataUsage = await OPERACompositeStore.shared.dataUsage()
            // Re-run detection against the fresh cells so the mint line updates now.
            recomputeWeatherHazards()
        }
    }

    /// Sample the live radar image into moderate-or-greater precipitation cells over the
    /// **whole flight-plan corridor** (the aircraft and every fix ahead through the
    /// destination — the entire route from the gate, the remaining route in flight —
    /// widened laterally). These cells are the sole input to the weather-
    /// deviation flow and the strategic preview, which thread the widest clear gap
    /// between them (the "raster → cell" step in `docs/Weather.md`). Sampling the entire
    /// route — not just a window ahead — is what lets every system's reroute be seen at
    /// once, including from the gate before takeoff.
    ///
    /// Three properties keep it usable rather than coarse or flickery:
    /// 1. The sample **resolution scales with the corridor's size** (`RadarImageSampler
    ///    .sampleGrid`, ~2 NM/pixel, capped), so a long route still resolves individual
    ///    storms near the aircraft instead of the old fixed-grid whole-route sample that
    ///    under-resolved them and "cleared" weather that was still dead ahead.
    /// 2. On a fetch/decode failure it **keeps the last good cells** instead of wiping
    ///    them, so a transient network hiccup doesn't drop the reroute. Cells are
    ///    replaced only on a successful sample (which may legitimately be empty when the
    ///    route really is clear).
    /// 3. The region is route-relative (not aircraft-relative), so it barely changes as
    ///    the aircraft flies — resampling is driven mainly by staleness, not movement.
    ///
    /// Best-effort. Radar (NOAA/OPERA) always samples; the NASA global satellite
    /// estimate samples only when the user opts in via `satelliteDeviationsEnabled` —
    /// then decoded with the IMERG rate palette and tagged low-confidence, so the mint
    /// line is drawn around satellite-estimated precipitation but never presented as
    /// radar. With neither, no cells. Simulation/training only.
    private func sampleLivePrecipitation() async {
        guard settings.noaaRadarOverlay == .autoWhereAvailable else {
            radarOverlay.sampledCells = []
            return
        }
        guard let pos = aircraftState.coordinate ?? resolvedDepartureCoordinate(),
              pos.isValid else { return }   // no position — keep the last good cells

        // The whole flight plan ahead: the aircraft plus every located fix still in front
        // of it, through the destination. At the gate this is the entire route; in flight
        // it's the remaining route (so resolution isn't spent on the leg already flown).
        // `region(enclosing:)` boxes it; the box is widened below so the corridor — not
        // just the route centerline — is captured.
        var focus: [CLLocationCoordinate2D] = [pos]
        focus.append(contentsOf: upcomingRouteCoordinates(from: pos).filter { $0.isValid })

        lastPrecipSampleAt = Date()
        lastPrecipSamplePos = pos

        guard var region = PrecipitationOverlayService.region(enclosing: focus) else {
            radarOverlay.sampledCells = []
            return
        }
        // Widen the box by ~`corridorPadNM` on every side so weather whose body sits off
        // the centerline (but whose edge crosses the route) is still captured.
        let corridorPadNM = 60.0
        let padLat = corridorPadNM / 60.0
        let padLon = corridorPadNM / (60.0 * max(0.2, cos(region.center.latitude * .pi / 180)))
        region.span.latitudeDelta += 2 * padLat
        region.span.longitudeDelta += 2 * padLon

        guard let provider = precipService.selectedProvider(for: region) else {
            radarOverlay.sampledCells = []
            return
        }
        // Radar (NOAA/OPERA) always samples. The NASA satellite estimate samples only
        // when the user opts in (`satelliteDeviationsEnabled`); otherwise satellite
        // coverage shows the overlay image but never drives a deviation.
        let isSatelliteEstimate = provider.layerType == .satelliteEstimate
        guard provider.supportsTrueRadar || (isSatelliteEstimate && settings.satelliteDeviationsEnabled) else {
            radarOverlay.sampledCells = []
            return
        }

        // "Reduce cellular data": on a cellular / expensive link, skip the background
        // download of the megabyte-scale EUMETNET OPERA composite that this sampling
        // triggers. The map overlay still loads when the user opens the Weather view
        // (user-initiated); NOAA/NASA (small server-cropped PNGs) keep sampling. Keep
        // the last good cells so the reroute doesn't blink out.
        // Dormant while OPERA is disabled: OPERA is never the selected provider, so this
        // guard never trips today — kept for when OPERA (and its toggle) are re-enabled.
        if settings.reduceCellularData, isExpensiveNetwork, provider.id == "eumetnet-opera-radar" {
            return
        }

        let bbox = RadarBoundingBox(region: region)
        // Size the sample image to the corridor bbox's exact Web-Mercator aspect ratio so the
        // NOAA/NASA (EPSG:3857) render comes back registered to `bbox`. A mismatched aspect
        // makes the source adjust the returned extent (ArcGIS ImageServer) or stretch the
        // render (WMS), which drifts every sampled cell — pulled toward the corridor's centre
        // — off the displayed radar. NM/pixel stays roughly constant on any route length.
        let grid = RadarImageSampler.mercatorSampleSize(bbox: bbox)
        let frames = (try? await provider.availableFrames(for: region)) ?? []
        let frame = frames.first ?? RadarFrame(id: "sample", timestamp: Date(), label: "Current")
        // `exportImage` itself returns an optional Data, so flatten the `try?`
        // double-optional before decoding.
        let fetched = try? await provider.exportImage(
            for: bbox, size: CGSize(width: grid.columns, height: grid.rows), frame: frame)
        guard let data = fetched ?? nil,
              // The overlays are rendered in Web Mercator (EPSG:3857, `imageSR`/`SRS`), so
              // the sampler must invert Mercator when mapping pixel rows back to latitude —
              // otherwise the cells drift (tens of NM) from the displayed radar and an
              // on-route core can fall outside the deviation corridor.
              let cells = RadarImageSampler.cells(fromPNG: data, columns: grid.columns, rows: grid.rows, bbox: bbox,
                                                  palette: isSatelliteEstimate ? .imergRate : .reflectivity,
                                                  projection: .webMercator) else {
            // Fetch or decode failed — keep the last good cells so the deviation line
            // doesn't blink out on a transient error.
            return
        }
        radarOverlay.sampledCells = cells
        // Cells now reflect a real decode of the current route's radar — the deviation lock
        // may proceed (it was held off until this landed so it never locks an empty set
        // mid-fetch and then never re-locks).
        livePrecipCellsReady = true
    }

    /// The filed route's located fixes as named candidates for labeling a PIREP with the
    /// nearest fix to *its own* position (not the aircraft's). Only waypoints carrying both
    /// a name and a valid coordinate qualify.
    private func routeNamedFixes() -> [WeatherRouteAnalyzer.NamedFix] {
        flightPlan.waypoints.compactMap { wp in
            guard let coord = wp.coordinate, coord.isValid,
                  !wp.name.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            return WeatherRouteAnalyzer.NamedFix(name: wp.name, coordinate: coord)
        }
    }

    /// The full filed route polyline, in order: departure, located enroute fixes,
    /// destination — exactly the line drawn on the map. The shared base for the
    /// aircraft-relative `upcomingRouteCoordinates` and the airport-margin rejoin cap.
    private func fullFiledRoutePolyline() -> [CLLocationCoordinate2D] {
        var full: [CLLocationCoordinate2D] = []
        if let dep = resolvedDepartureCoordinate() ?? flightPlan.firstWaypointCoordinate,
           dep.isValid { full.append(dep) }
        full.append(contentsOf: flightPlan.waypoints.compactMap { $0.coordinate }.filter { $0.isValid })
        if let dest = resolvedDestinationCoordinate() ?? flightPlan.lastWaypointCoordinate,
           dest.isValid { full.append(dest) }
        return full
    }

    /// The upcoming route as ordered coordinates — the located fixes still ahead of
    /// the aircraft, then the destination — so the conflict detector can follow the
    /// route's bends into the weather rather than a straight bearing to the next fix.
    /// "Ahead" is determined by **projecting the aircraft onto the filed route polyline**
    /// and returning the remainder, so it always tracks the drawn route — not by a
    /// straight-line distance-from-departure test, which drops an upcoming fix wherever
    /// the route jogs (see below).
    private func upcomingRouteCoordinates(from pos: CLLocationCoordinate2D) -> [CLLocationCoordinate2D] {
        // The full filed route polyline, in order: departure, located enroute fixes,
        // destination — exactly the line drawn on the map.
        let full = fullFiledRoutePolyline()
        guard full.count >= 2 else { return full }

        // The route *ahead* of the aircraft, found by projecting it onto the polyline and
        // returning the vertices past the segment it sits abeam. Selecting "ahead" by
        // straight-line distance from the departure instead (the old heuristic) silently
        // drops an upcoming fix on any route that jogs — where a later fix isn't strictly
        // farther from the departure than the aircraft — which reshapes the detection
        // corridor away from the drawn route the moment telemetry arrives, so on-route
        // storms stop being detected (the reroute lines vanish as the aircraft icon
        // appears). Projection tracks the drawn route exactly. At the gate (pos is the
        // departure) the nearest point is the first vertex, so the whole route is returned
        // — unchanged from before.
        var bestSeg = 0
        var bestDist = Double.greatestFiniteMagnitude
        for i in 0..<(full.count - 1) {
            let d = distanceToSegmentNM(pos, full[i], full[i + 1])
            if d < bestDist { bestDist = d; bestSeg = i }
        }
        let ahead = Array(full[(bestSeg + 1)...])
        return ahead.isEmpty ? [full[full.count - 1]] : ahead
    }

    /// Distance (NM) from a point to a segment in a local equirectangular NM plane —
    /// used to project the aircraft onto the filed route polyline. Consistent with the
    /// planar geometry the conflict detector uses at this scale.
    private func distanceToSegmentNM(_ p: CLLocationCoordinate2D,
                                     _ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        let latScale = 60.0
        let lonScale = 60.0 * cos(p.latitude * .pi / 180)
        let px = p.longitude * lonScale, py = p.latitude * latScale
        let ax = a.longitude * lonScale, ay = a.latitude * latScale
        let bx = b.longitude * lonScale, by = b.latitude * latScale
        let dx = bx - ax, dy = by - ay
        let lenSq = dx * dx + dy * dy
        let t = lenSq <= 0 ? 0 : max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq))
        let cx = ax + t * dx, cy = ay + t * dy
        return hypot(px - cx, py - cy)
    }

    /// The course to fly for the corridor: bearing to the next un-passed fix, else
    /// to the destination, else the aircraft heading.
    private func currentCourse(from pos: CLLocationCoordinate2D) -> Double {
        let origin = resolvedDepartureCoordinate()
        if let next = flightPlan.nextUnpassedWaypoint(from: pos, origin: origin)?.coordinate {
            return Geo.bearing(from: pos, to: next)
        }
        if let dest = resolvedDestinationCoordinate() ?? flightPlan.lastWaypointCoordinate {
            return Geo.bearing(from: pos, to: dest)
        }
        return aircraftState.heading ?? 0
    }

    /// A one-line summary of the **sampled radar cells** — the moderate-or-greater clusters
    /// the live sampler derived from the radar image (light returns are dropped; they don't
    /// drive deviations) — for the Diagnostics readout: the total count and a per-intensity
    /// breakdown. "None" when the sampler produced no cells, which, with a storm plainly on
    /// the route, points at the sampling step rather than the deviation geometry.
    var sampledRadarCellSummary: String {
        let cells = radarOverlay.sampledCells
        guard !cells.isEmpty else { return "None" }
        let parts = [WeatherIntensity.extreme, .heavy, .moderate, .light].compactMap { level -> String? in
            let n = cells.filter { $0.intensity == level }.count
            return n > 0 ? "\(n) \(level.displayLabel.lowercased())" : nil
        }
        return parts.isEmpty ? "\(cells.count)" : "\(cells.count) (\(parts.joined(separator: ", ")))"
    }

    private func updateWeatherDiagnostics(conflict: RouteWeatherConflict?) {
        var d = WeatherProviderDiagnostics()
        // Show the active provider + layer type ("radar" vs "satellite estimate").
        d.radarSource = radarOverlay.coverageAvailable
            ? "\(radarOverlay.sourceDescription) (\(radarOverlay.layerLabel))"
            : "None"
        d.radarCoverageAvailable = radarOverlay.coverageAvailable
        d.lastRadarUpdate = radarOverlay.lastUpdated
        d.lastAviationUpdate = lastAviationWeatherUpdate
        d.hazardCount = weatherHazards.count
        if let c = conflict {
            // Distinguish an on-path conflict being monitored ahead (the reroute may be
            // drawn once within draw range, but the banner has not yet been raised) from
            // one close enough to be worked now.
            let stage = c.withinTacticalRange ? "" : " — monitoring"
            // Say so when the solved reroute never leaves the route and so isn't drawn —
            // otherwise Diagnostics reports a conflict the map shows no mint line for, and
            // the missing line reads as a bug rather than a suppressed line.
            //
            // Say it *accurately*. This branch has exactly one meaning: a reroute was
            // solved and came out inside the excursion floor. It never means no lateral
            // way around exists — which is what the old wording ("no lateral deviation
            // available") claimed, and it sent the first read of a live report of this
            // message hunting through the width of the search rather than the width of the
            // line it settled on. So name the measurement and the floor it missed: a small
            // number is a line solved along the flight path (the route skirting a line's
            // edge), 0.0 is the degenerate fallback taken when no candidate was built.
            let excursion = c.maxRouteExcursionNM
            let floor = conflictDetector.config.minRouteExcursionNM
            let measured = excursion < .greatestFiniteMagnitude
                ? String(format: " (solved %.1f NM off it, floor %.0f NM)", excursion, floor) : ""
            let noLine = deviationLeavesRoute(c) ? "" : " — reroute lies on the flight path, not drawn\(measured)"
            d.routeConflictStatus = "\(c.severity.displayLabel) \(c.hazard.source.label), \(Int(c.distanceAheadNM.rounded())) NM\(stage)\(noLine)"
        } else if let pos = aircraftState.coordinate ?? resolvedDepartureCoordinate(),
                  let onRoute = conflictDetector.nearestRouteHazard(
                    route: upcomingRouteCoordinates(from: pos), from: pos, hazards: weatherHazards) {
            // No active deviation selected, but qualifying radar still sits on the route
            // somewhere ahead (e.g. a system with no drawable reroute, or one being flown
            // past). Report it as monitored rather than falsely claiming "No conflict".
            //
            // When the walk solved a reroute here and threw it out for lying on the flight
            // path, say so with the excursion that failed the floor: that weather is
            // deliberately not being prompted, and this is the only place that records why.
            let floor = conflictDetector.config.minRouteExcursionNM
            let discarded = discardedOnRouteExcursionNM.map {
                String(format: " (reroute solved %.1f NM off the route, floor %.0f NM — discarded)", $0, floor)
            } ?? ""
            d.routeConflictStatus = "\(onRoute.hazard.intensity.displayLabel) \(onRoute.hazard.source.label) on route, \(Int(onRoute.distanceNM.rounded())) NM — monitoring\(discarded)"
        } else {
            d.routeConflictStatus = "No conflict"
        }
        d.selectedRejoinFix = conflict?.rejoinFix?.name ?? weatherDeviation.rejoinFix
        d.lastDeviationState = weatherDeviation.state
        d.solvedWindFromDegrees = lastSolvedWind?.fromDegrees
        d.solvedWindKnots = lastSolvedWind?.speedKnots
        d.reportedWindDirectionTrue = aircraftState.reportedWindDirectionTrue
        d.reportedWindKnots = aircraftState.reportedWindSpeedKnots
        d.windSourceIsSimReported = windSourceIsSimReported
        d.magneticVariationEast = lastKnownVariationEast
        d.lastAssignedTrueCourse = lastAssignedTrueCourse
        d.lastAssignedHeading = lastAssignedVectorHeading
        d.departureHeadingSummary = lastDepartureHeadingSummary
        d.providerError = precipService.lastError
        d.coverageMessage = radarOverlay.coverageAvailable ? nil : radarOverlay.unavailableMessage
        d.radarLastBytes = ordDataUsage.last
        d.radarSessionBytes = ordDataUsage.total
        weatherDiagnostics = d
    }

    // MARK: - Weather deviation — UI gating

    /// Whether the weather-deviation flow may run right now (airborne, enroute or
    /// arrival, not on the ground / takeoff / landing / standby).
    private var weatherFlowAllowed: Bool {
        guard hasDeparted, !companionStandby else { return false }
        switch atcState {
        case .notConnected, .connectedIdle, .clearance, .pushback, .engineStart,
             .pushbackTaxi, .groundTaxi, .runwayCrossing, .holdingShort, .lineUpWait,
             .towerDeparture, .landing, .runwayExit, .groundArrival, .parked:
            return false
        default:
            return true
        }
    }

    /// Whether the pilot is established on final (weather-near-final advisory only).
    private var establishedOnFinal: Bool {
        atcState == .final || atcState == .landing
    }

    /// The active precipitation conflict **the pilot can actually act on**: one whose solved
    /// line leaves the flight path and is therefore drawn. A conflict whose line lies on the
    /// route has nothing to turn onto, so prompting for it offers a deviation that cannot be
    /// flown — the banner, the advisory, and the response card all key off this rather than
    /// `activeWeatherConflict` directly.
    ///
    /// The walk already discards such a deviation before it can be selected
    /// (`computeDeviations`), so in the normal path this is simply the active conflict. It is
    /// the guard for every *other* way one is set — the re-plan while already deviating
    /// (`recomputeConflictFrom`) chief among them — so no route into the flow can announce a
    /// maneuver the map is not drawing.
    private var flyableWeatherConflict: RouteWeatherConflict? {
        guard let c = activeWeatherConflict, deviationLeavesRoute(c) else { return nil }
        return c
    }

    /// The most significant turbulence / icing SIGMET along the route, when there is
    /// no precipitation conflict to thread. A turbulence or icing SIGMET has nothing
    /// to laterally route around, so — as ATC does — it drives an *altitude-change*
    /// advisory (smoother air / out of the icing) rather than a lateral reroute.
    /// Precipitation (the mint line) always takes precedence when both are present.
    var activeRideSigmet: SIGMET? {
        guard activeWeatherConflict == nil else { return nil }
        return routeSigmets
            .filter { $0.category == .turbulence || $0.category == .icingOrMountainWave }
            .max { $0.turbulenceSeverity < $1.turbulenceSeverity }
    }

    /// A short word for the active ride advisory's hazard, for the banner / status.
    var rideAdvisoryWord: String {
        guard let ride = activeRideSigmet else { return "Weather" }
        let text = (ride.hazard ?? ride.raw).uppercased()
        if text.contains("ICE") { return "Icing" }
        if text.contains("MTW") { return "Mountain wave" }
        return "Turbulence"
    }

    /// Whether the advisory currently in play is resolved with an altitude change
    /// (turbulence / icing) rather than a lateral deviation (precipitation). Derived
    /// live from the current weather so the response buttons always match reality.
    private var currentAdvisoryIsAltitude: Bool {
        activeWeatherConflict == nil && activeRideSigmet != nil
    }

    /// Whether the "Weather ahead — contact ATC" banner should be shown in ATCView.
    ///
    /// The banner stays up for as long as weather is detected ahead and no
    /// deviation interaction is currently in progress — even after the pilot has
    /// already contacted ATC and elected to continue on course. That way a pilot
    /// who continues now but decides to reroute later still has the banner to tap
    /// to re-open the weather-deviation flow. While a deviation is actively being
    /// worked, the deviation card (not the banner) carries the controls.
    var weatherBannerVisible: Bool {
        guard settings.weatherDeviationAlerts.alertsEnabled, weatherFlowAllowed else { return false }
        let hasPrecip = flyableWeatherConflict?.shouldPrompt ?? false
        guard hasPrecip || activeRideSigmet != nil else { return false }
        return weatherDeviation.state == .none || weatherDeviation.state == .weatherAheadDetected
    }

    /// The banner text (advisory-only near final, else the contact-ATC prompt). Names
    /// the hazard for a turbulence / icing ride advisory.
    var weatherBannerText: String {
        if establishedOnFinal { return "Weather near final — advisory only" }
        if currentAdvisoryIsAltitude { return "\(rideAdvisoryWord) advisory — contact ATC" }
        return "Weather ahead — contact ATC"
    }

    /// Whether the weather-deviation response card should be shown in ATCView.
    var weatherDeviationCardVisible: Bool {
        switch weatherDeviation.state {
        case .none, .resumedOwnNavigation, .radarUnavailableForRegion:
            return false
        default:
            return weatherFlowAllowed
        }
    }

    /// The current simulated weather-deviation state.
    var weatherDeviationState: WeatherDeviationState { weatherDeviation.state }

    /// The mint deviation line to draw on the route map: the frozen path the pilot
    /// has committed to fly once a vector/deviation is approved, else the live
    /// recommended reroute from the current conflict. Nil when there's nothing to
    /// draw. Freezing after commit is what stops the line shifting/blinking while the
    /// pilot is following it — only a fresh reroute request or clear-of-weather
    /// resumes live proposals.
    var weatherDeviationLine: [CLLocationCoordinate2D]? {
        if let frozen = weatherDeviation.committedDeviationPath, frozen.count >= 2 {
            return frozen.map { $0.coordinate }
        }
        // Only draw the live recommendation once the weather is close enough to be a
        // real tactical deviation (`withinDrawRange`). Far on-path weather is still
        // detected and monitored, but its straight-corridor reroute — aimed across the
        // route's bends at distant weather — would render as a runaway "crazy" line, so
        // the line is held until the aircraft closes in.
        guard let conflict = activeWeatherConflict, conflict.withinDrawRange,
              conflict.deviationPath.count >= 2 else { return nil }
        // A locked deviation was already validated to hug real weather when it was found,
        // and is held in place, so it is not re-checked against the live hazards — which can
        // transiently empty on a noisy radar resample and would otherwise blink the solid
        // line out. Only a conflict that isn't part of the locked set (e.g. one injected
        // directly) is re-validated live, so a degenerate line in clear air is still
        // suppressed rather than drawn as a mint line with no weather near it.
        let isLocked = lockedDeviations.contains { $0.id == conflict.id }
        if !isLocked,
           !conflictDetector.pathEngagesWeather(conflict.deviationPath, hazards: weatherHazards) {
            return nil
        }
        // And it must actually go somewhere. A reroute whose whole length lies on the filed
        // route draws a mint line on top of the magenta one — telling the pilot to deviate
        // onto the course being flown. Withhold the line; the conflict still raises the
        // banner and the advisory, so the pilot can work it with ATC.
        guard deviationLeavesRoute(conflict) else { return nil }
        return conflict.deviationPath
    }

    /// Whether a deviation's drawn line leaves the filed route far enough to be worth
    /// drawing at all (`RouteWeatherConflictDetector.pathLeavesRoute`, measured for the
    /// locked set in `computeDeviations`). A conflict whose excursion was never measured —
    /// one constructed directly rather than solved — is drawn, as it was before.
    private func deviationLeavesRoute(_ deviation: RouteWeatherConflict) -> Bool {
        deviation.maxRouteExcursionNM >= conflictDetector.config.minRouteExcursionNM
    }

    /// The point `target` NM along the route polyline (`start` then `ahead`) from
    /// `start`, or nil when the route ends before reaching it.
    private func pointAlongRoute(from start: CLLocationCoordinate2D,
                                 through ahead: [CLLocationCoordinate2D], byNM target: Double) -> CLLocationCoordinate2D? {
        var prev = start
        var accumulated = 0.0
        for c in ahead where c.isValid {
            let seg = Geo.distanceNM(from: prev, to: c)
            if accumulated + seg >= target {
                let remaining = target - accumulated
                return Geo.destination(from: prev, bearingDegrees: Geo.bearing(from: prev, to: c), distanceNM: remaining)
            }
            accumulated += seg
            prev = c
        }
        return nil
    }

    /// The rejoin fix marker for the mint line: its name and coordinate. Uses the
    /// live conflict's rejoin fix when available, else the end of the frozen
    /// committed path (labeled with the recorded rejoin fix name) so the marker stays
    /// put with the locked line even after the conflict itself settles.
    var weatherRejoinMarker: (name: String, coordinate: CLLocationCoordinate2D)? {
        // Mirror `weatherDeviationLine`: only show the live rejoin marker while the mint
        // line itself is drawn (within draw range, and actually leaving the route), so a
        // lone marker never appears for far weather whose reroute is still being held, or
        // for a reroute that was withheld because it deviates nowhere.
        if let conflict = activeWeatherConflict, conflict.withinDrawRange,
           deviationLeavesRoute(conflict),
           let fix = conflict.rejoinFix, let c = fix.coordinate, c.isValid {
            return (fix.name.isEmpty ? "Rejoin" : fix.name, c)
        }
        if let frozen = weatherDeviation.committedDeviationPath, let last = frozen.last {
            let c = last.coordinate
            if c.isValid { return (weatherDeviation.rejoinFix ?? "Rejoin", c) }
        }
        return nil
    }

    /// The weather response-buttons to surface, keyed off the deviation state. A
    /// turbulence / icing advisory offers only altitude changes (there is nothing to
    /// laterally route around); a precipitation advisory offers the full set.
    var weatherActions: [WeatherDeviationAction] {
        switch weatherDeviation.state {
        case .advisoryIssued, .awaitingPilotIntentions, .deviationRequested:
            if establishedOnFinal { return [.sayAgain] }
            if currentAdvisoryIsAltitude {
                return [.requestHigher, .requestLower, .continueOnCourse, .sayAgain]
            }
            return [.requestRightDeviation, .requestLeftDeviation, .requestVector,
                    .requestHigher, .requestLower, .continueOnCourse, .sayAgain]
        case .deviationApproved, .vectoringAroundWeather, .deviatingAroundWeather, .clearOfWeather:
            if establishedOnFinal { return [.clearOfWeather, .sayAgain] }
            // Keep Vectors available while flying a lateral deviation so the pilot can
            // re-plan around NEW weather that pops up ahead — the fresh vector is
            // computed from the current position, treating the committed mint line as
            // the route. An altitude-change ride advisory has nothing lateral to
            // re-vector around, so it only offers clear-of-weather.
            let flyingLateralDeviation = weatherDeviation.committedDeviationPath != nil || activeWeatherConflict != nil
            if currentAdvisoryIsAltitude || !flyingLateralDeviation { return [.clearOfWeather, .sayAgain] }
            return [.requestVector, .clearOfWeather, .sayAgain]
        default:
            return []
        }
    }

    // MARK: - Weather deviation — pilot actions

    /// The controller working the weather deviation. Uses whatever radar controller
    /// is currently tuned — Departure on climb, Approach on arrival, Center enroute —
    /// so the weather calls and read-backs address the active facility. Falls back to
    /// the phase of flight when the tuned facility is not an enroute/radar position.
    private var weatherFacility: ATCFacility {
        switch currentFacility {
        case .departure, .center, .approach:
            return currentFacility
        default:
            switch phase {
            case .approach, .descent: return .approach
            case .initialClimb, .climb: return .departure
            default: return .center
            }
        }
    }

    private var isOnSTAR: Bool {
        guard !flightPlan.star.isEmpty else { return false }
        return phase == .descent || phase == .approach
            || atcState == .descent || atcState == .approach || atcState == .final
    }

    private func starDisplaySpoken() -> (display: String, spoken: String) {
        guard !flightPlan.star.isEmpty else { return ("", "") }
        if let star = ProcedureParser.parseSTAR(flightPlan.star, icao: flightPlan.destination) {
            return (star.displayName, star.spokenName(icao: engine.icao))
        }
        return (flightPlan.star, Phonetic.spellToken(flightPlan.star, icao: engine.icao))
    }

    /// The altitude ATC assigns as "maintain" during a deviation.
    private func weatherMaintainAltitude() -> Int {
        if assignedAltitude > 0 { return assignedAltitude }
        return flightPlan.cruiseAltitude > 0 ? flightPlan.cruiseAltitude : 37000
    }

    /// The heading to fly for a vector around weather.
    ///
    /// Prefer the bearing from the current position to the first turn vertex of the
    /// recommended deviation path (the mint line drawn on the map — `[position, …,
    /// rejoin]`, whose second point is the initial turn-out). Deriving the vector from
    /// that path keeps the assigned heading consistent with the map and anchored to
    /// where the aircraft *is now* — so a second vector requested while already
    /// deviated turns toward the suggested reroute rather than stacking another offset
    /// on top of the current heading.
    ///
    /// Falls back to offsetting the current heading (or the filed course) by the
    /// recommended amount when no usable deviation path is available.
    private func weatherDeviationHeading(direction: DeviationDirection) -> Int {
        let pos = aircraftState.coordinate ?? resolvedDepartureCoordinate()
        if let pos, pos.isValid,
           let apex = activeWeatherConflict?.deviationPath.dropFirst().first, apex.isValid {
            // A true course off the drawn line — carried into the pilot's frame, like
            // every other heading issued along the mint line.
            return assignedHeading(forTrueCourse: Geo.bearing(from: pos, to: apex))
        }
        let base = aircraftState.heading ?? pos.map { currentCourse(from: $0) } ?? 0
        let degrees = activeWeatherConflict?.recommendedDeviationDegrees ?? 20
        let signed = Int(base.rounded()) + (direction == .right ? degrees : -degrees)
        return ((signed % 360) + 360) % 360
    }

    private func deviationInputs(direction: DeviationDirection, unableSide: Bool = false) -> WeatherDeviationEngine.Inputs {
        let star = starDisplaySpoken()
        return WeatherDeviationEngine.Inputs(
            maintainAltitude: weatherMaintainAltitude(),
            heading: weatherDeviationHeading(direction: direction),
            onSTAR: isOnSTAR,
            starDisplay: star.display,
            starSpoken: star.spoken,
            nearRoute: false,
            unableRequestedSide: unableSide)
    }

    /// Whether a controller call would only repeat the last one the pilot has already
    /// acknowledged, so there is nothing to gain by saying it again.
    private func isAcknowledgedRepeat(_ tx: ATCTransmission) -> Bool {
        ATCTransmission.isAcknowledgedRepeat(tx, in: transcript)
    }

    /// Post the engine's result and adopt the updated deviation context.
    ///
    /// `controllerInitiated` marks a call the controller makes on its own — an auto-issued
    /// advisory, a turn fired by the aircraft's own progress, an auto-resume — with no pilot
    /// request waiting on an answer. Those are the calls that can come out verbatim-identical
    /// back to back, so one that would only repeat an acknowledged call is **not transmitted**.
    /// The context is adopted either way, so the lifecycle advances exactly as if it had been
    /// said: the pilot is already flying the instruction they read back. A reply to a pilot
    /// request is always transmitted — a request left unanswered reads as a dropped call.
    private func applyDeviationResult(_ result: WeatherDeviationEngine.Result,
                                      controllerInitiated: Bool = false) {
        weatherHandled = true
        if let pilot = result.pilot { postPilot(pilot) }
        for tx in result.atc {
            if controllerInitiated, isAcknowledgedRepeat(tx) {
                diagnostics.log(.atc, "Held repeat of an acknowledged call: \(tx.displayText)")
                continue
            }
            post(tx, speak: true)
        }
        weatherDeviation = result.context
        updateWeatherDiagnostics(conflict: activeWeatherConflict)
    }

    /// The weather situation to advise on, or nil when nothing significant applies.
    private func currentWeatherSituation() -> WeatherDeviationEngine.Situation? {
        if let conflict = flyableWeatherConflict {
            switch conflict.source {
            case .sigmet:
                return .sigmet(label: conflict.hazard.notes ?? "significant weather",
                               convective: conflict.isConvectiveSigmet)
            default:
                return .radarConflict(conflict)
            }
        }
        // No precipitation to route around: a turbulence / icing SIGMET along the
        // route is handled with an altitude change, not a lateral deviation.
        if let ride = activeRideSigmet {
            return rideSigmetSituation(ride)
        }
        if !radarOverlay.coverageAvailable, routeSigmets.isEmpty {
            return .noRadarNoAdvisory
        }
        return nil
    }

    /// Map a turbulence / icing SIGMET to its altitude-oriented advisory. Icing gets
    /// the "exit the icing" framing; mountain-wave and turbulence get "smoother air".
    private func rideSigmetSituation(_ s: SIGMET) -> WeatherDeviationEngine.Situation {
        let text = (s.hazard ?? s.raw).uppercased()
        if text.contains("ICE") { return .rideSigmet(label: "icing", icing: true) }
        if text.contains("MTW") { return .rideSigmet(label: "mountain wave turbulence", icing: false) }
        let label = s.turbulenceSeverity == .severe ? "severe turbulence" : "turbulence"
        return .rideSigmet(label: label, icing: false)
    }

    /// Auto-issue the advisory once for a fresh conflict in Mock Mode (the demo).
    private func maybeAutoIssueMockAdvisory(conflict: RouteWeatherConflict?) {
        guard settings.mockMode, let conflict, conflict.shouldPrompt,
              deviationLeavesRoute(conflict),
              settings.weatherDeviationAlerts.alertsEnabled, weatherFlowAllowed,
              !companionStandby, !mockWeatherAdvisoryIssued,
              weatherDeviation.state == .none,
              let situation = currentWeatherSituation() else { return }
        mockWeatherAdvisoryIssued = true
        weatherHandled = true
        applyDeviationResult(
            deviationEngine.issueAdvisory(cs: callsignNow(), situation: situation,
                                          context: weatherDeviation, facility: weatherFacility),
            controllerInitiated: true)
    }

    /// Safety net for the locked mint lines: if the pilot never engaged the advisory (never
    /// tapped the "contact ATC" banner, never continued or deviated) and the aircraft closes
    /// to within `deviationAutoCallNM` of the upcoming deviation's first turn (its turn-out),
    /// the controller **auto-issues** the advisory on its own — so a drawn-ahead deviation
    /// can't be silently flown past with no ATC call. Fires once per un-engaged conflict, in
    /// every mode; a pilot who already tapped the banner (state ≠ none) or elected to
    /// continue (`weatherHandled`) is left alone. Mirrors `askCenterAboutWeather`, but with
    /// no pilot query since ATC is the one initiating.
    private func maybeAutoIssueAdvisoryNearTurn() {
        guard settings.weatherDeviationAlerts.alertsEnabled, weatherFlowAllowed,
              !companionStandby, !establishedOnFinal,
              weatherDeviation.state == .none, !weatherHandled,
              let conflict = flyableWeatherConflict, conflict.shouldPrompt,
              let turnOut = conflict.deviationPath.first, turnOut.isValid,
              let pos = aircraftState.coordinate, pos.isValid else { return }
        // Measured as a distance *ahead*, so a turn-out the aircraft has already passed abeam
        // — still within 15 NM as the crow flies — doesn't raise a "weather ahead" advisory
        // for a turn that is in fact behind the aircraft.
        let ahead = turnOutAheadNM(turnOut, from: pos)
        guard ahead > 0, ahead <= deviationAutoCallNM,
              let situation = currentWeatherSituation() else { return }
        weatherHandled = true
        mockWeatherAdvisoryIssued = true   // the one-shot advisory has now been issued
        applyDeviationResult(
            deviationEngine.issueAdvisory(cs: callsignNow(), situation: situation,
                                          context: weatherDeviation, facility: weatherFacility),
            controllerInitiated: true)
    }

    private func callsignNow() -> PhraseologyEngine.Callsign {
        engine.callsign(airline: flightPlan.airline, flightNumber: flightPlan.flightNumber,
                        fallback: flightPlan.callsign)
    }

    /// Pilot taps "Contact ATC": the controller volunteers the weather advisory.
    func askCenterAboutWeather() {
        guard !companionStandby else { return }
        weatherHandled = true
        let cs = callsignNow()
        let facility = weatherFacility
        if let situation = currentWeatherSituation() {
            // A brief pilot query, then the controller's advisory.
            postPilot(ATCTransmission(sender: .pilot, facility: facility,
                displayText: "\(facility.spokenName), \(cs.display), weather ahead, requesting advisory.",
                spokenText: "\(facility.spokenName), \(cs.spoken), weather ahead, requesting advisory."))
            applyDeviationResult(deviationEngine.issueAdvisory(cs: cs, situation: situation,
                                                              context: weatherDeviation,
                                                              facility: facility))
        } else {
            // Coverage but nothing significant along the route.
            post(ATCTransmission(sender: .atc, facility: facility,
                displayText: "\(cs.display), no significant precipitation along your route at this time.",
                spokenText: "\(cs.spoken), no significant precipitation along your route at this time."),
                speak: true)
        }
    }

    /// A drawn mint line whose turn-out the aircraft has already passed can no longer be flown
    /// as drawn — the maneuver begins behind the aircraft. Acting on a request against it would
    /// approve a deviation nobody can fly, so re-plan it from the current position first and let
    /// the controller approve *that*. Leaves the drawn line alone when its turn-out is still
    /// ahead, and when nothing solves from here (the request then works the line as before,
    /// rather than being silently swallowed).
    private func reanchorDeviationIfTurnOutPassed() {
        guard let pos = aircraftState.coordinate, pos.isValid,
              let v0 = activeWeatherConflict?.deviationPath.first, v0.isValid,
              turnOutAheadNM(v0, from: pos) <= 0 else { return }
        recomputeConflictFrom(pos)
    }

    /// Pilot requests a left/right weather deviation; the controller approves.
    ///
    /// When the reroute is still drawn ahead — the aircraft has not yet reached the
    /// turn-out point at the start of the mint line — the controller approves the
    /// deviation but **holds the turn**: the pilot continues on course and is told to
    /// expect the turn in X miles. The beginning turn is issued automatically once the
    /// aircraft reaches the turn-out (`maybeIssueDeviationStartTurn`). Close aboard, the
    /// turn is worked immediately, as before.
    func requestWeatherDeviation(_ direction: DeviationDirection) {
        guard !companionStandby, weatherFlowAllowed, !establishedOnFinal else { return }
        let cs = callsignNow()
        reanchorDeviationIfTurnOutPassed()
        if let ahead = deviationTurnOutAhead() {
            applyDeviationResult(deviationEngine.deferDeviation(
                cs: cs, conflict: activeWeatherConflict, direction: direction, distanceNM: ahead.distanceNM,
                inputs: deviationInputs(direction: direction), context: weatherDeviation,
                facility: weatherFacility))
            freezeCommittedDeviationPath()
            armDeviationStart()
            return
        }
        applyDeviationResult(deviationEngine.requestDeviation(
            cs: cs, conflict: activeWeatherConflict, direction: direction,
            inputs: deviationInputs(direction: direction), context: weatherDeviation,
            facility: weatherFacility))
        freezeCommittedDeviationPath()
    }

    /// Pilot requests a vector around the weather; the controller assigns a heading.
    ///
    /// When the pilot is **already** flying a committed deviation and taps Vectors again,
    /// it means there may be new weather on the reroute they're on. The controller
    /// re-evaluates the committed line ahead of the aircraft:
    ///   • if new precipitation now sits on it, the deviation is **recalculated from the
    ///     aircraft's current position** and fresh vectors are issued (the new heading,
    ///     mint line and rejoin turn rejoin the line the aircraft was already following,
    ///     not the original filed course); but
    ///   • if the reroute ahead is **still clear**, the controller has the pilot
    ///     **continue on the current deviation** rather than re-vectoring needlessly.
    func requestVectorAroundWeather() {
        guard !companionStandby, weatherFlowAllowed, !establishedOnFinal else { return }
        // A deviation whose turn is still held (drawn ahead) is committed but not yet
        // being flown, so a fresh request re-holds rather than re-vectoring in place.
        let held = weatherDeviation.deviationStartLatitude != nil
        let alreadyCommitted = weatherDeviation.state.isCommittedDeviation && !held

        // Already flying a deviation and the pilot taps Vectors again: re-evaluate the
        // reroute they're on.
        if alreadyCommitted, let pos = aircraftState.coordinate, pos.isValid {
            let tail = committedTailAhead(from: pos)
            let stillClear = tail.count < 2
                || conflictDetector.committedPathStillClear([pos] + tail, hazards: weatherHazards)
            if stillClear {
                // The reroute ahead is still clear — continue on the current deviation.
                continueOnCurrentDeviation()
                return
            }
            // New weather sits on the committed line — recompute it from here and
            // re-vector, treating the reroute (plus the filed route past it) as the route.
            // `recomputeConflictFrom` also carries the fresh line down the rest of the
            // committed deviation to the filed route, so this second deviation ends on the
            // flight path rather than mid-air on the first deviation it just replaced.
            recomputeConflictFrom(pos)
            let cs = callsignNow()
            let side = activeWeatherConflict?.recommendedDirection ?? .right
            applyDeviationResult(deviationEngine.requestVectors(
                cs: cs, inputs: deviationInputs(direction: side), context: weatherDeviation,
                facility: weatherFacility))
            freezeCommittedDeviationPath()
            captureWeatherRejoinTurn()
            return
        }

        let cs = callsignNow()
        reanchorDeviationIfTurnOutPassed()
        let side = activeWeatherConflict?.recommendedDirection ?? .right
        // A fresh request with the reroute still drawn ahead holds the turn, exactly like
        // a lateral deviation request — continue on course, expect the turn in X miles —
        // then vectors onto the reroute at the turn-out.
        if let ahead = deviationTurnOutAhead() {
            applyDeviationResult(deviationEngine.deferDeviation(
                cs: cs, conflict: activeWeatherConflict, direction: side, distanceNM: ahead.distanceNM,
                inputs: deviationInputs(direction: side), context: weatherDeviation,
                facility: weatherFacility))
            freezeCommittedDeviationPath()
            armDeviationStart()
            return
        }
        applyDeviationResult(deviationEngine.requestVectors(
            cs: cs, inputs: deviationInputs(direction: side), context: weatherDeviation,
            facility: weatherFacility))
        freezeCommittedDeviationPath()
        captureWeatherRejoinTurn()
    }

    /// The pilot taps Vectors while already flying a deviation, but the reroute ahead is
    /// still clear of weather — the controller has them continue on the current
    /// deviation. The committed line and its armed turns are left untouched (the engine
    /// changes no deviation state).
    private func continueOnCurrentDeviation() {
        applyDeviationResult(deviationEngine.continueCurrentDeviation(
            cs: callsignNow(), context: weatherDeviation, facility: weatherFacility))
    }

    /// Detect a route-weather conflict along an explicit route polyline from the
    /// current position — used to re-plan a vector while already deviating, where the
    /// route to protect is the committed mint line (plus the filed route past it)
    /// rather than the straight filed course. Reuses the current normalized hazards.
    private func detectConflictAlong(route: [CLLocationCoordinate2D]) -> RouteWeatherConflict? {
        guard let pos = aircraftState.coordinate, pos.isValid else { return nil }
        // Aim the corridor at the first route point meaningfully ahead of the aircraft
        // (the route may start at the current position), else the filed course.
        let course = route.first(where: { $0.isValid && Geo.distanceNM(from: pos, to: $0) > 1 })
            .map { Geo.bearing(from: pos, to: $0) } ?? currentCourse(from: pos)
        return conflictDetector.detectConflict(position: pos, course: course,
                                               groundspeedKnots: aircraftState.groundSpeed,
                                               phase: phase, hazards: weatherHazards,
                                               waypoints: flightPlan.waypoints, routeAhead: route,
                                               rejoinCap: weatherRejoinCap())
    }

    /// The deepest point a weather deviation may rejoin the route — the rejoin cap the
    /// detector clamps every drawn line to. Two rules, whichever is farther from the field:
    ///   • **At least `weatherRejoinAirportMarginNM` before the airport**, measured along
    ///     the filed route, so a mint line always terminates on the flight path short of the
    ///     field instead of ending right on top of it (even with weather sitting on the
    ///     destination); and
    ///   • **never past the first fix of the ILS/approach** when the plan names one farther
    ///     out than that margin, so the reroute never routes into the approach.
    /// Falls back to the old point cap (approach fix → destination → last fix) when the route
    /// polyline is too sparse to walk.
    private func weatherRejoinCap() -> CLLocationCoordinate2D? {
        let route = fullFiledRoutePolyline()
        guard route.count >= 2, let airport = route.last else {
            return flightPlan.approachStartCoordinate
                ?? resolvedDestinationCoordinate() ?? flightPlan.lastWaypointCoordinate
        }
        // Hold the rejoin at least the airport margin before the field; if the plan's approach
        // fix sits farther out, hold it there instead (the more restrictive of the two).
        var marginNM = weatherRejoinAirportMarginNM
        if let approachFix = flightPlan.approachStartCoordinate, approachFix.isValid {
            marginNM = max(marginNM, alongRouteDistanceFromEnd(route, to: approachFix))
        }
        return pointBeforeEndAlongRoute(route, byNM: marginNM) ?? airport
    }

    /// The point on a route polyline a given distance back from its final vertex (the
    /// airport), walking the legs in reverse and clamped to the route start. Used to hold a
    /// weather-deviation rejoin a fixed margin short of the field, on the flight path.
    private func pointBeforeEndAlongRoute(_ route: [CLLocationCoordinate2D],
                                          byNM target: Double) -> CLLocationCoordinate2D? {
        let pts = route.filter { $0.isValid }
        guard let end = pts.last else { return nil }
        guard pts.count >= 2, target > 0 else { return end }
        var remaining = target
        for i in stride(from: pts.count - 1, to: 0, by: -1) {
            let a = pts[i], b = pts[i - 1]
            let seg = Geo.distanceNM(from: a, to: b)
            if seg >= remaining {
                return Geo.destination(from: a, bearingDegrees: Geo.bearing(from: a, to: b),
                                       distanceNM: remaining)
            }
            remaining -= seg
        }
        return pts.first
    }

    /// A route polyline truncated at `cap`: the vertices up to the leg the cap projects
    /// onto, then the cap itself as the final point. Used to bound the merged-deviation
    /// rejoin slide, so it can never step a rejoin past the airport-margin cap. Returns the
    /// route unchanged when there is no cap or it can't be placed on the polyline.
    private func routeTruncated(_ route: [CLLocationCoordinate2D],
                                at cap: CLLocationCoordinate2D?) -> [CLLocationCoordinate2D] {
        guard let cap, cap.isValid, route.count >= 2 else { return route }
        var bestSeg = 0
        var bestDist = Double.greatestFiniteMagnitude
        for i in 0..<(route.count - 1) {
            let d = distanceToSegmentNM(cap, route[i], route[i + 1])
            if d < bestDist { bestDist = d; bestSeg = i }
        }
        return Array(route[0...bestSeg]) + [cap]
    }

    /// The along-route distance (NM) from the route's final vertex (the airport) back to the
    /// point on the route nearest `target` — how far before the field a fix sits. Projects
    /// `target` onto the nearest leg, then sums the route length from that projection to the
    /// end, so an approach fix off the drawn polyline still measures sensibly.
    private func alongRouteDistanceFromEnd(_ route: [CLLocationCoordinate2D],
                                           to target: CLLocationCoordinate2D) -> Double {
        let pts = route.filter { $0.isValid }
        guard pts.count >= 2, target.isValid else { return 0 }
        var bestSeg = 0
        var bestDist = Double.greatestFiniteMagnitude
        for i in 0..<(pts.count - 1) {
            let d = distanceToSegmentNM(target, pts[i], pts[i + 1])
            if d < bestDist { bestDist = d; bestSeg = i }
        }
        let proj = closestPointOnSegmentNM(target, pts[bestSeg], pts[bestSeg + 1])
        var total = Geo.distanceNM(from: proj, to: pts[bestSeg + 1])
        if bestSeg + 1 < pts.count - 1 {
            for i in (bestSeg + 1)..<(pts.count - 1) {
                total += Geo.distanceNM(from: pts[i], to: pts[i + 1])
            }
        }
        return total
    }

    /// The point on segment a→b nearest `p`, in the same local NM plane as
    /// `distanceToSegmentNM` (which returns only the distance) — used to project a fix onto
    /// the filed route when measuring its distance before the field.
    private func closestPointOnSegmentNM(_ p: CLLocationCoordinate2D,
                                         _ a: CLLocationCoordinate2D,
                                         _ b: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        let latScale = 60.0
        let lonScale = 60.0 * cos(p.latitude * .pi / 180)
        let px = p.longitude * lonScale, py = p.latitude * latScale
        let ax = a.longitude * lonScale, ay = a.latitude * latScale
        let bx = b.longitude * lonScale, by = b.latitude * latScale
        let dx = bx - ax, dy = by - ay
        let lenSq = dx * dx + dy * dy
        let t = lenSq <= 0 ? 0 : max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq))
        return CLLocationCoordinate2D(latitude: (ay + t * dy) / latScale,
                                      longitude: (ax + t * dx) / lonScale)
    }

    /// The committed mint line from the vertex nearest the aircraft to its end — the
    /// portion of the reroute still ahead of the aircraft (the part it's still flying).
    /// Empty when nothing is committed or the frozen path is degenerate.
    private func committedTailAhead(from pos: CLLocationCoordinate2D) -> [CLLocationCoordinate2D] {
        guard let frozen = weatherDeviation.committedDeviationPath else { return [] }
        let committed = frozen.map { $0.coordinate }.filter { $0.isValid }
        guard committed.count >= 2 else { return [] }
        // Only the part still **ahead** of the aircraft, by the same rule the re-anchoring uses.
        // Taking the vertex merely *nearest* it (the old rule) hands back a tail that starts
        // behind the aircraft once it has flown past or away from the line — and a re-plan
        // aimed along that tail points backwards. With nothing ahead the tail is empty, and the
        // callers fall back to the filed route ahead (or treat the reroute as flown out)
        // instead of chasing geometry behind them.
        let anchored = pathAnchoredAtAircraft(committed, from: pos,
                                              track: aircraftTrackDegrees(at: pos))
        return anchored.count >= 2 ? Array(anchored.dropFirst()) : []
    }

    /// The route ahead to protect on a re-vector: the committed mint line still ahead
    /// (`committedTailAhead`), then the filed route beyond the committed line's rejoin.
    /// Falls back to the plain filed route ahead when nothing is committed.
    private func revectorRouteAhead(from pos: CLLocationCoordinate2D) -> [CLLocationCoordinate2D] {
        let tail = committedTailAhead(from: pos)
        guard tail.count >= 2 else { return upcomingRouteCoordinates(from: pos) }
        let beyond = tail.last.map { upcomingRouteCoordinates(from: $0) } ?? []
        return tail + beyond
    }

    /// Freeze the currently recommended mint deviation line as the path the pilot has
    /// now committed to fly, so it stops being re-proposed (and stops shifting with
    /// each radar resample) while the deviation is flown. Once frozen, the map draws
    /// this fixed line until the pilot reports clear of weather or requests another
    /// reroute (which re-freezes it to the fresh recommendation).
    /// A reroute that was never drawn (it deviates nowhere — `deviationLeavesRoute`) is
    /// never frozen either: committing it would put the withheld line back on the map as a
    /// frozen path, which is drawn ahead of every guard. The deviation is still approved —
    /// it simply has no drawn line for the turn machinery to follow.
    private func freezeCommittedDeviationPath() {
        guard let conflict = activeWeatherConflict, conflict.deviationPath.count >= 2,
              deviationLeavesRoute(conflict) else {
            weatherDeviation.committedDeviationPath = nil
            return
        }
        weatherDeviation.committedDeviationPath = conflict.deviationPath.map(WeatherDeviationContext.PathPoint.init)
    }

    /// The committed mint line the aircraft is flying, as coordinates. Prefers the
    /// frozen committed path (stable across radar resamples) and falls back to the
    /// live conflict's path, so the turn tracking keys off the exact line drawn.
    private func committedMintLineCoordinates() -> [CLLocationCoordinate2D] {
        if let frozen = weatherDeviation.committedDeviationPath {
            return frozen.map { $0.coordinate }
        }
        // Only a line the map actually drew can be tracked along: an undrawn reroute has no
        // turns for the pilot to be vectored onto, so the turn issuers get nothing rather
        // than a set of vectors down a line that deviates nowhere.
        guard let conflict = activeWeatherConflict, deviationLeavesRoute(conflict) else { return [] }
        return conflict.deviationPath
    }

    /// Clear any armed auto-turn (no turn pending).
    private func clearPendingRejoinTurn() {
        weatherDeviation.pendingTurnIndex = nil
        weatherDeviation.pendingRejoinHeading = nil
        weatherDeviation.vectorApexLatitude = nil
        weatherDeviation.vectorApexLongitude = nil
        weatherDeviation.vectorLegBearing = nil
    }

    // MARK: - Weather deviation — turn anticipation (call the turn early)

    /// The nominal turn radius (NM) at the aircraft's current groundspeed, assuming a
    /// typical ~25° bank. Faster aircraft carve a wider arc, so their turns must be called
    /// proportionally earlier. R = V² / (g · tan bank).
    private func turnRadiusNM(groundspeedKnots gs: Double) -> Double {
        let v = max(60, gs)                        // guard a zero / missing telemetry speed
        let vFPS = v * 1.68781                      // knots → ft/s
        let g = 32.174                              // ft/s²
        let radiusFT = (vFPS * vFPS) / (g * tan(25.0 * .pi / 180))
        return radiusFT / 6076.12                   // ft → NM
    }

    /// How far (NM) ahead of a turn vertex the turn should be issued so the aircraft rolls
    /// out on the next leg *through* the vertex instead of overshooting the mint line: the
    /// standard turn-anticipation distance (turn radius × tan(½ course change)) plus a
    /// short lead for the pilot to hear the call and roll in. Sharper turns and faster
    /// aircraft anticipate more.
    ///
    /// The full geometric + reaction lead called the turn a touch too early, so we split
    /// the difference back toward the prior timing: keep the full geometric anticipation
    /// (the distance actually needed to roll out on the next leg through the vertex) but
    /// halve the reaction lead.
    private func turnAnticipationNM(turnDegrees: Double) -> Double {
        let gs = aircraftState.groundSpeed ?? 300
        let half = (min(abs(turnDegrees), 170) / 2) * .pi / 180
        let geometric = turnRadiusNM(groundspeedKnots: gs) * tan(half)
        let reaction = max(0.5, gs / 360)           // ~10 s to react and roll in
        return geometric + reaction * 0.5           // split the difference: half the reaction lead
    }

    /// The angular course change (0…180°) from an inbound leg bearing to an outbound
    /// heading.
    private func courseChangeDegrees(inbound: Double, outbound: Double) -> Double {
        var d = abs(outbound - inbound).truncatingRemainder(dividingBy: 360)
        if d > 180 { d = 360 - d }
        return d
    }

    /// The distance (NM) ahead of a turn vertex at which to issue the turn: the base
    /// capture reach (~30 s of travel, min 2 NM — the prior behaviour) plus the
    /// turn-anticipation lead, so the turn is called early enough that the aircraft rolls
    /// out on the next leg rather than overshooting. When the vertex is reached down a
    /// mint-line leg (`inboundLegNM`), the added lead is capped so the total never exceeds
    /// 60% of that leg — a turn is never called while the aircraft is still most of a leg
    /// away (e.g. sitting at the previous vertex), preserving the "don't fire before the
    /// apex" behaviour. The beginning turn (`inboundLegNM == nil`, flown in from the filed
    /// course rather than a prior vertex) applies the full lead uncapped.
    private func turnLeadNM(turnDegrees: Double, inboundLegNM: Double?) -> Double {
        let base = max(2.0, (aircraftState.groundSpeed ?? 300) / 120)
        let anticipation = turnAnticipationNM(turnDegrees: turnDegrees)
        guard let leg = inboundLegNM else { return base + anticipation }
        let room = max(0, leg * 0.6 - base)
        return base + min(anticipation, room)
    }

    // MARK: - Weather deviation — held beginning turn (reroute drawn ahead)

    /// The turn-out point at the start of the drawn mint line and its distance ahead
    /// (NM, rounded to 5), when it sits meaningfully ahead of the aircraft — i.e. the
    /// reroute is drawn ahead and the beginning turn should be held until the aircraft
    /// reaches it. Nil when the aircraft is already at/through/past the turn-out, so the
    /// deviation is worked immediately instead.
    ///
    /// Measured as a distance **ahead** (`turnOutAheadNM`), not as a straight-line distance.
    /// A turn-out the aircraft has already passed abeam is still some distance away as the
    /// crow flies: read straight, the request would be held — the pilot told to expect a turn
    /// that is in fact behind them — and the beginning turn would then never fire, because a
    /// turn-out behind the aircraft can never satisfy the reach test. So a turn-out that is
    /// not genuinely ahead reports nil and the turn is worked now.
    ///
    /// The distance is rounded to 5 NM here and **only** here: the phraseology speaks this
    /// number as given rather than re-rounding it to tens, so the pilot hears the distance
    /// that was actually measured.
    private func deviationTurnOutAhead() -> (start: CLLocationCoordinate2D, distanceNM: Int)? {
        guard let pos = aircraftState.coordinate, pos.isValid,
              let path = activeWeatherConflict?.deviationPath, path.count >= 2,
              let v0 = path.first, v0.isValid, path[1].isValid else { return nil }
        let ahead = turnOutAheadNM(v0, from: pos)
        guard ahead > deviationTurnHoldNM else { return nil }
        return (v0, max(5, Int((ahead / 5).rounded()) * 5))
    }

    // MARK: - True course → assigned heading

    /// Refresh the corrections that carry a mint-line leg into the pilot's frame.
    ///
    /// **Variation** is a difference between two headings Connect serves in separate
    /// round-trips, so it is sampled only near wings-level: a roll smears the difference, and
    /// a stale sample beats a smeared one — variation changes over hundreds of miles, not over
    /// the seconds a turn takes. A tick with nothing usable leaves the last estimate standing.
    ///
    /// **Wind** prefers the sim's own `environment/wind_*` states over the inferred triangle.
    /// Read directly it is exact, so it needs neither the smoothing (which exists purely to
    /// absorb the noise of differencing two ~450 kt vectors) nor the bank guard — and that
    /// second point matters: the triangle has to stand down through a turn, which is exactly
    /// when the *next* leg's crab is computed off it. The reported wind is right throughout.
    /// Older Infinite Flight versions don't expose the states; there the triangle carries on
    /// unchanged.
    private func updateHeadingCorrections(from state: AircraftState) {
        let nearLevel = abs(state.bankAngle ?? 0) <= HeadingSolver.maxSampleBankDegrees
        if nearLevel, let sample = HeadingSolver.variationDegreesEast(from: state) {
            // Corroborated rather than latched: the variation goes straight into the initial
            // departure vector, where one torn pair of headings is the difference between a
            // turn and "fly runway heading". See `HeadingSolver.VariationEstimate`.
            variationEstimate.note(sample)
        }
        let solved = nearLevel ? HeadingSolver.wind(from: state) : nil
        // The triangle's estimate is kept on its own, never blended with a reported sample,
        // so it stays an *independent* second opinion: it is what the cross-check below is
        // checked against and what Weather Diagnostics prints beside the sim's wind. Folding
        // the reported wind into it would make that comparison compare a number with itself.
        if let solved {
            lastSolvedWind = HeadingSolver.blended(lastSolvedWind, sample: solved)
        }
        // Cross-checked against the *fresh* sample, not the running estimate: with no sample
        // this tick the triangle has stood down (a turn, too slow, no TAS) and has no opinion
        // to overrule the sim with.
        if let reported = HeadingSolver.reportedWind(from: state),
           trustReportedWind(reported, against: solved) {
            lastKnownWind = reported
            windSourceIsSimReported = true
            return
        }
        if solved != nil, let triangle = lastSolvedWind {
            lastKnownWind = triangle
            windSourceIsSimReported = false
        }
    }

    /// Whether the sim's reported wind may be steered by. It is used as the direction the wind
    /// blows **from**, pinned against Infinite Flight's own PFD wind readout — but that is one
    /// observation of one build, and getting it backwards would put every weather vector on the
    /// wrong side of course. So when the triangle has independently solved a wind worth
    /// comparing against, a disagreement past a right angle **at the same speed** is treated as
    /// "this isn't the convention we think it is" and the inferred wind — whose convention is
    /// fixed by the arithmetic that produced it — is used instead.
    ///
    /// The speed clause is what keeps the check from backfiring. Direction alone hands the
    /// decision to whichever source disagrees loudest, and the triangle is by far the louder:
    /// it differences two ~450 kt vectors read in separate round-trips, so one smeared sample
    /// invents a wind of its own and shouts down an exact reading. (Seen in the field: 12 kt
    /// reported, 84 kt solved, 118° apart — and every weather vector crabbed for the gale.)
    ///
    /// The comparison is only made on a wind strong enough for the triangle's own direction to
    /// mean anything; in light air the two can differ wildly while both are effectively calm,
    /// and disagreeing about the direction of a 3 kt wind decides nothing.
    private func trustReportedWind(_ reported: HeadingSolver.Wind,
                                   against solved: HeadingSolver.Wind?) -> Bool {
        guard let solved,
              reported.speedKnots >= windCrossCheckMinKnots,
              solved.speedKnots >= windCrossCheckMinKnots else { return true }
        let disagreement = HeadingSolver.directionDisagreementDegrees(reported, solved)
        guard disagreement > windCrossCheckMaxDisagreementDegrees else { return true }
        // A convention mismatch reverses a wind without changing its strength, so it is only
        // that if the two agree about the speed. When they don't, the disagreement is the
        // triangle's own — a smeared sample differencing two ~450 kt vectors — and the sim's
        // exact reading must not be thrown out on its word.
        guard HeadingSolver.speedsCorroborate(reported, solved) else {
            diagnostics.log(.app, String(format: "Wind: the solved wind (%03.0f° / %.0f kt) disagrees with the sim's own (%03.0f° / %.0f kt) about the speed as well as the direction — the triangle is unreliable here, keeping the sim-reported wind.",
                                         solved.fromDegrees, solved.speedKnots,
                                         reported.fromDegrees, reported.speedKnots))
            return true
        }
        diagnostics.log(.app, String(format: "Wind: the sim-reported direction (%03.0f°) disagrees with the solved wind (%03.0f°) by %.0f° at the same speed — using the solved wind.",
                                     reported.fromDegrees, solved.fromDegrees, disagreement))
        return false
    }

    /// Wind speed (knots) below which the reported/solved cross-check is not worth making —
    /// the direction of a wind this light barely moves the crab either way.
    private let windCrossCheckMinKnots: Double = 10
    /// How far the two winds' directions may disagree before the reported one is distrusted.
    private let windCrossCheckMaxDisagreementDegrees: Double = 90

    /// The heading to assign so the aircraft **tracks** `trueCourse` rather than merely
    /// pointing along it: crabbed into the live wind, then converted out of the true
    /// frame the geometry is computed in and into the magnetic frame the sim's heading
    /// bug reads.
    ///
    /// Every assigned heading the app derives from great-circle geometry goes through
    /// here — the weather-deviation vectors along the mint line, and the initial
    /// departure heading to the first fix off the runway. (Runway-derived headings —
    /// the approach intercept, the go-around crosswind leg — are already magnetic by
    /// definition and need no conversion.)
    ///
    /// Applied only where a heading is handed to the pilot. The stored turn geometry
    /// (`deviationStartHeading`, `pendingRejoinHeading`, the leg bearings) stays in true
    /// degrees, so the comparisons built on it — turn size, the never-reverse guard —
    /// keep matching the drawn line rather than mixing frames.
    private func assignedHeading(forTrueCourse trueCourse: Double) -> Int {
        let assigned = HeadingSolver.assignedHeading(forTrueCourse: trueCourse,
                                                     wind: lastKnownWind,
                                                     trueAirspeed: aircraftState.trueAirspeed,
                                                     variationDegreesEast: lastKnownVariationEast)
        lastAssignedTrueCourse = trueCourse
        lastAssignedVectorHeading = assigned
        return assigned
    }

    /// Arm the held beginning turn at the mint line's turn-out point: store the turn-out
    /// (start of the committed line), the course out of it onto the reroute, and the
    /// bearing of the leg into it (to detect the aircraft passing abeam), so the
    /// telemetry loop can issue the turn once the aircraft reaches the turn-out.
    ///
    /// The stored course is **true**, matching the drawn line it is measured off, so the
    /// turn-size and never-reverse checks compare like with like. It becomes a magnetic,
    /// wind-corrected heading only where it is spoken (`assignedHeading(forTrueCourse:)`).
    private func armDeviationStart() {
        let path = committedMintLineCoordinates()
        guard path.count >= 2, let v0 = path.first, v0.isValid,
              let v1 = path.dropFirst().first, v1.isValid,
              let pos = aircraftState.coordinate, pos.isValid else { clearDeviationStart(); return }
        weatherDeviation.deviationStartLatitude = v0.latitude
        weatherDeviation.deviationStartLongitude = v0.longitude
        weatherDeviation.deviationStartHeading = ApproachIntercept.normalizedHeading(Geo.bearing(from: v0, to: v1))
        weatherDeviation.deviationStartLegBearing = Geo.bearing(from: pos, to: v0)
    }

    /// Clear the held beginning turn (no deviation-start pending).
    private func clearDeviationStart() {
        weatherDeviation.deviationStartLatitude = nil
        weatherDeviation.deviationStartLongitude = nil
        weatherDeviation.deviationStartHeading = nil
        weatherDeviation.deviationStartLegBearing = nil
    }

    /// While a deviation is approved with its turn held (reroute drawn ahead), issue the
    /// beginning turn once the aircraft reaches the turn-out point — near it, or once it
    /// passes abeam/beyond along the leg into it. Vectors the aircraft onto the reroute
    /// and arms the interior turns. Returns whether it issued the turn this tick, so the
    /// caller can skip the other turn checks (they'd race on the same geometry).
    @discardableResult
    private func maybeIssueDeviationStartTurn() -> Bool {
        guard !companionStandby, weatherFlowAllowed,
              weatherDeviation.state == .deviationApproved,
              let sLat = weatherDeviation.deviationStartLatitude,
              let sLon = weatherDeviation.deviationStartLongitude,
              let heading = weatherDeviation.deviationStartHeading,
              let pos = aircraftState.coordinate, pos.isValid else { return false }
        let v0 = CLLocationCoordinate2D(latitude: sLat, longitude: sLon)
        // Call the beginning turn a lead distance before the turn-out so the aircraft
        // rolls onto the reroute *through* it rather than overshooting. The turn-out is
        // flown in from the filed course (no prior mint-line vertex), so the full
        // turn-anticipation lead applies uncapped.
        let turnDegrees = weatherDeviation.deviationStartLegBearing
            .map { courseChangeDegrees(inbound: $0, outbound: Double(heading)) } ?? 30
        let leadNM = turnLeadNM(turnDegrees: turnDegrees, inboundLegNM: nil)
        let dist = Geo.distanceNM(from: pos, to: v0)
        let reached: Bool
        if dist <= leadNM {
            reached = true
        } else if let leg = weatherDeviation.deviationStartLegBearing {
            let v0ToAircraft = Geo.bearing(from: v0, to: pos)
            reached = dist * cos((v0ToAircraft - leg) * .pi / 180) >= -leadNM
        } else {
            reached = false
        }
        guard reached else {
            // Not reached — but a turn-out the aircraft has flown past can never *become*
            // reached, so waiting for it silently strands the approved deviation. Recover it.
            return releaseStaleDeviationHoldIfPassed(v0, at: pos)
        }
        // Never turn the aircraft around. The turn-out is shaped forward from the detection
        // position, so a beginning turn this far off the current track means the aircraft is no
        // longer where the held turn assumes — re-plan from where it actually is rather than
        // reversing it (and rather than holding a turn that will never be issued).
        guard !vectorWouldReverseAircraft(heading, at: pos) else {
            return replanHeldDeviation(from: pos)
        }
        lastAutoTurnIssuedAt = Date()   // let it roll out before drift is judged
        applyDeviationResult(
            deviationEngine.beginDeviationTurn(cs: callsignNow(),
                                               heading: assignedHeading(forTrueCourse: Double(heading)),
                                               maintainAltitude: weatherMaintainAltitude(),
                                               context: weatherDeviation, facility: weatherFacility),
            controllerInitiated: true)
        // Now vectoring onto the reroute — arm the interior turns of the committed line.
        captureWeatherRejoinTurn()
        return true
    }

    /// A held beginning turn is pinned to a fixed turn-out. If the aircraft passes that point
    /// without the turn firing — a late request, a re-locked line, a fix that jumped — the hold
    /// is dead: `maybeIssueDeviationStartTurn`'s reach test only grows more negative as the
    /// aircraft flies away, so it would wait for the rest of the flight while the pilot, told
    /// to "expect the turn in X miles", flies straight on through the weather.
    ///
    /// So once the turn-out is genuinely behind the aircraft, re-plan from where it actually
    /// is. Returns whether a turn was issued this tick.
    private func releaseStaleDeviationHoldIfPassed(_ turnOut: CLLocationCoordinate2D,
                                                   at pos: CLLocationCoordinate2D) -> Bool {
        // A margin past abeam, so a turn-out momentarily reading behind on a noisy fix while
        // the aircraft is still closing on it doesn't tear down a perfectly good hold.
        guard turnOutAheadNM(turnOut, from: pos) < -deviationTurnHoldNM else { return false }
        return replanHeldDeviation(from: pos)
    }

    /// Re-plan a held-turn deviation from the aircraft's current position and pick the hold up
    /// where it now belongs: re-held at a fresh turn-out still ahead (with the revised distance
    /// announced, since the pilot was given the old one), or vectored onto the reroute now when
    /// the aircraft is already at/past it. When nothing solves from here there is nothing left
    /// to turn for, so the lifecycle is ended rather than left approved-but-unarmed.
    ///
    /// `trustedFix` is false on a telemetry discontinuity (the jumped fix on return from the
    /// background). A deviation the pilot has already accepted is never cancelled off a fix
    /// that cannot be trusted — the same reading `resolveConflictWithHysteresis` refuses to
    /// believe a "clear route" from. The approved deviation and its held turn are left exactly
    /// as they stand, and the next continuous tick re-decides on a fix that can be trusted.
    /// Returns whether a turn was issued this tick.
    @discardableResult
    private func replanHeldDeviation(from pos: CLLocationCoordinate2D, trustedFix: Bool = true) -> Bool {
        // Re-solve *before* dropping the held turn, so a re-plan that finds nothing can leave
        // the accepted deviation untouched rather than having already disarmed it.
        guard recomputeConflictFrom(pos) else {
            guard trustedFix else { return false }
            clearDeviationStart()
            endHeldDeviationWithNothingAhead()
            return false
        }
        clearDeviationStart()
        freezeCommittedDeviationPath()
        if let ahead = deviationTurnOutAhead() {
            armDeviationStart()
            announceRedrawnDeviation(conflict: activeWeatherConflict,
                                     distanceNM: Double(ahead.distanceNM))
            return false
        }
        lastAutoTurnIssuedAt = Date()
        issueResyncVector(from: pos)
        guard weatherDeviation.state == .vectoringAroundWeather else {
            // The only vector available out of here would have turned the aircraft around, so
            // `issueResyncVector` declined it. There is no flyable deviation left from this
            // position — end the lifecycle rather than leave it approved with nothing armed.
            endHeldDeviationWithNothingAhead()
            return false
        }
        return true
    }

    /// End a held-turn deviation that has nothing left to turn for — the turn-out is gone and
    /// no fresh line solves from here.
    ///
    /// The controller **says so**: the pilot is holding a clearance to continue on course and
    /// expect a turn, so withdrawing it silently leaves them flying toward a turn that will
    /// never be called. Then the lifecycle returns to idle, not merely the armed turn dropped.
    /// `.deviationApproved` counts as `isCommittedDeviation`, so the per-tick rollback in
    /// `updateWeatherConflict` deliberately leaves it alone; a context left in that state with
    /// no armed turn is a dead end — no turn can fire, and no later conflict can prompt afresh
    /// — for the rest of the flight.
    ///
    /// `weatherHandled` is deliberately **left set**. It marks the weather ahead as already
    /// worked, and the pilot just worked it — they asked for a deviation and were approved.
    /// Clearing it here re-arms `maybeAutoIssueAdvisoryNearTurn` against the very conflict the
    /// deviation was for, so the controller re-opens with "…say intentions" seconds after
    /// cancelling the clearance for it. The per-tick rollback in `recomputeWeatherHazards`
    /// clears the flag once the route genuinely reads clear, so a later, fresh conflict still
    /// prompts on its own; until then the banner (state is idle again) is the way back in.
    private func endHeldDeviationWithNothingAhead() {
        applyDeviationResult(
            deviationEngine.cancelHeldDeviation(cs: callsignNow(), context: weatherDeviation,
                                                facility: weatherFacility),
            controllerInitiated: true)
        weatherDeviation.reset()
    }

    // MARK: - Weather deviation — drifted off the line being flown

    /// The perpendicular distance (NM) from the aircraft to the mint line it is flying, or nil
    /// when the aircraft is not *on* the line — short of its start (the maneuver hasn't begun)
    /// or past its end (the rejoin, which the auto-resume handles). Nil in both cases so a
    /// deviation drawn ahead of the aircraft, or one already flown out, is never read as drift.
    private func offPathDistanceNM(_ pos: CLLocationCoordinate2D,
                                   path: [CLLocationCoordinate2D]) -> Double? {
        let pts = path.filter { $0.isValid }
        guard pts.count >= 2 else { return nil }
        var bestSeg = -1
        var bestT = 0.0
        var bestDist = Double.greatestFiniteMagnitude
        let latScale = 60.0
        let lonScale = 60.0 * cos(pos.latitude * .pi / 180)
        let px = pos.longitude * lonScale, py = pos.latitude * latScale
        for i in 0..<(pts.count - 1) {
            let a = pts[i], b = pts[i + 1]
            let ax = a.longitude * lonScale, ay = a.latitude * latScale
            let bx = b.longitude * lonScale, by = b.latitude * latScale
            let dx = bx - ax, dy = by - ay
            let lenSq = dx * dx + dy * dy
            let raw = lenSq <= 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / lenSq
            let t = max(0, min(1, raw))
            let d = hypot(px - (ax + t * dx), py - (ay + t * dy))
            if d < bestDist { bestDist = d; bestSeg = i; bestT = raw }
        }
        guard bestSeg >= 0 else { return nil }
        if bestSeg == 0, bestT <= 0 { return nil }                     // still short of the line
        if bestSeg == pts.count - 2, bestT >= 1 { return nil }         // past the rejoin end
        return bestDist
    }

    /// The aircraft's current direction of travel, for judging what lies ahead of it. Its track
    /// first (true, matching the drawn geometry), then its true heading, then the magnetic
    /// heading — off by the local declination, but that is far inside the bound this feeds —
    /// and only as a last resort the filed course, which is where the aircraft is *meant* to be
    /// going rather than where it is actually going.
    private func aircraftTrackDegrees(at pos: CLLocationCoordinate2D) -> Double {
        if let track = aircraftState.track { return track }
        if let trueHeading = aircraftState.trueHeading { return trueHeading }
        if let heading = aircraftState.heading { return heading }
        return currentCourse(from: pos)
    }

    /// Whether `coord` lies **ahead** of the aircraft: reaching it is a turn of no more than
    /// `maxWeatherVectorTurnDegrees` off the current track. Anything past that bound sits behind
    /// the aircraft — flying to it would mean turning around, never vectoring around weather.
    private func isAheadOfAircraft(_ coord: CLLocationCoordinate2D,
                                   from pos: CLLocationCoordinate2D, track: Double) -> Bool {
        guard coord.isValid, Geo.distanceNM(from: pos, to: coord) > 0.1 else { return false }
        return courseChangeDegrees(inbound: track,
                                   outbound: Geo.bearing(from: pos, to: coord)) <= maxWeatherVectorTurnDegrees
    }

    /// Whether an automatically-issued weather vector onto `heading` would turn the aircraft
    /// around rather than vector it around weather. See `maxWeatherVectorTurnDegrees`.
    private func vectorWouldReverseAircraft(_ heading: Int, at pos: CLLocationCoordinate2D) -> Bool {
        courseChangeDegrees(inbound: aircraftTrackDegrees(at: pos),
                            outbound: Double(heading)) > maxWeatherVectorTurnDegrees
    }

    /// How far ahead (NM) `coord` lies **along the aircraft's current track** — negative once
    /// the aircraft has passed abeam it.
    ///
    /// This is the distance that matters for a turn the aircraft is flying toward, and it is
    /// not the same as the straight-line distance. A turn-out the aircraft has already flown
    /// past is still 13 NM away as the crow flies; read as a distance it looks like a turn
    /// comfortably ahead, and holding the beginning turn for it promises a turn that can
    /// never be issued (the reach test in `maybeIssueDeviationStartTurn` only ever grows more
    /// negative as the aircraft flies away from it).
    private func aheadAlongTrackNM(_ coord: CLLocationCoordinate2D,
                                   from pos: CLLocationCoordinate2D) -> Double {
        guard coord.isValid, pos.isValid else { return -.greatestFiniteMagnitude }
        let track = aircraftTrackDegrees(at: pos)
        let bearing = Geo.bearing(from: pos, to: coord)
        return Geo.distanceNM(from: pos, to: coord) * cos((bearing - track) * .pi / 180)
    }

    /// How far ahead (NM) a deviation's turn-out lies — the distance that decides whether the
    /// beginning turn may be *held* for it, and whether a hold on it is still live.
    ///
    /// Taken as the better of two measures, because either alone misreads a real case. Along
    /// the aircraft's **track** is right when the aircraft is off course, but a route bend can
    /// momentarily swing the instantaneous track well off a turn-out that is genuinely ahead.
    /// Along the filed **route** is right on course — it is the same projection that orders the
    /// locked set and tells which deviations have been flown past (`deviationEntryIsBehind`) —
    /// but it says little once the aircraft is well off course. A turn-out is only really
    /// behind the aircraft when it is behind by *both*.
    private func turnOutAheadNM(_ turnOut: CLLocationCoordinate2D,
                                from pos: CLLocationCoordinate2D) -> Double {
        max(aheadAlongTrackNM(turnOut, from: pos), alongRouteNM(turnOut) - alongRouteNM(pos))
    }

    /// The along-track distance (NM) of `p` from `a` measured in the direction of the leg
    /// a→b — positive once `p` is past `a` on that leg.
    private func alongLegNM(_ p: CLLocationCoordinate2D,
                            from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D) -> Double {
        let leg = Geo.bearing(from: a, to: b)
        let toP = Geo.bearing(from: a, to: p)
        return Geo.distanceNM(from: a, to: p) * cos((toP - leg) * .pi / 180)
    }

    /// Re-anchor a reroute to the aircraft's current position, keeping **only the part of it
    /// that is still in front of the aircraft**, so the line starts at the aircraft and its
    /// first leg is the intercept forward onto what remains of the reroute. Two things have to
    /// be true of every vertex kept: the aircraft has not already flown past it *along the
    /// line*, and it is not *behind the aircraft* (`isAheadOfAircraft`).
    ///
    /// Forward-only is the whole point. Keeping a trailing vertex "so the line still ends on
    /// the route" — even one the aircraft had flown well past and away from — is what let a
    /// re-plan aim at geometry behind the aircraft and command a near-reciprocal turn
    /// (216° → 015°). When nothing is left ahead this returns an empty path and the caller
    /// leaves the aircraft alone rather than turning it around to pick the line back up.
    private func pathAnchoredAtAircraft(_ path: [CLLocationCoordinate2D],
                                        from pos: CLLocationCoordinate2D,
                                        track: Double) -> [CLLocationCoordinate2D] {
        let valid = path.filter { $0.isValid }
        guard valid.count >= 2 else { return [] }
        // The first vertex the aircraft has not yet flown past on the drawn line.
        var startIdx = 0
        while startIdx < valid.count - 1,
              alongLegNM(pos, from: valid[startIdx], to: valid[startIdx + 1]) > 0 {
            startIdx += 1
        }
        // …and of what remains, only what is genuinely ahead of where the aircraft is going.
        var pts = valid[startIdx...].filter { isAheadOfAircraft($0, from: pos, track: track) }
        guard let first = pts.first else { return [] }
        if Geo.distanceNM(from: pos, to: first) < 1 { pts[0] = pos } else { pts.insert(pos, at: 0) }
        return pts
    }

    /// While the aircraft is being vectored around weather, watch how far it actually is from
    /// the mint line it was cleared to fly. Wind, a late roll-in, or a wide turn can leave it
    /// well off the drawn line — at which point the line no longer describes the reroute being
    /// flown, and the armed turns point at geometry the aircraft will never reach.
    ///
    /// Past `deviationOffPathToleranceNM` (5 NM either side) the deviation is re-planned **from
    /// the aircraft's current position**: fresh geometry when new weather now sits on the path
    /// from here, else the committed reroute re-anchored to the aircraft. The re-anchored line
    /// is re-frozen (so the map draws it from the aircraft), the controller re-vectors onto it,
    /// and the interior turns are re-armed against the new line so the upcoming turn calls
    /// match what is drawn. The rejoin on the filed route is preserved either way.
    ///
    /// Returns whether it re-planned this tick, so the caller skips the other turn checks —
    /// they would otherwise fire against the geometry just replaced.
    @discardableResult
    private func maybeReplanDeviationOffPath() -> Bool {
        guard !companionStandby, weatherFlowAllowed, !establishedOnFinal,
              weatherDeviation.state == .vectoringAroundWeather,
              let pos = aircraftState.coordinate, pos.isValid else { return false }
        let path = committedMintLineCoordinates()
        guard path.count >= 2, let end = path.last, end.isValid,
              // Nearly at the rejoin: let the aircraft finish rather than re-vectoring onto a
              // fresh line it would fly for a mile.
              Geo.distanceNM(from: pos, to: end) > autoResumeInterceptNM,
              let off = offPathDistanceNM(pos, path: path),
              off > deviationOffPathToleranceNM else { return false }
        // Just turned: the aircraft is off the line while it rolls out — that is the armed
        // turn working, not drift.
        if let turned = lastAutoTurnIssuedAt,
           Date().timeIntervalSince(turned) < turnComplyWindow { return false }
        if let last = lastOffPathReplanAt,
           Date().timeIntervalSince(last) < offPathReplanInterval { return false }
        lastOffPathReplanAt = Date()

        // Re-plan from here. `recomputeConflictFrom` protects the committed reroute ahead plus
        // the filed route past it, so a fresh line is produced only when weather now sits on
        // the path from this position; otherwise the reroute is still good and only needs
        // re-anchoring to where the aircraft is.
        let track = aircraftTrackDegrees(at: pos)
        let previousConflict = activeWeatherConflict
        var replanned = path
        var usedFreshSolve = false
        if recomputeConflictFrom(pos), let fresh = activeWeatherConflict?.deviationPath,
           fresh.count >= 2 {
            replanned = fresh
            usedFreshSolve = true
        }
        // Keep only what is still ahead of the aircraft, anchored at it.
        var anchored = pathAnchoredAtAircraft(replanned, from: pos, track: track)
        if anchored.count < 2, usedFreshSolve {
            // A fresh solve with nothing ahead is unusable — try the committed reroute before
            // giving up (the aircraft may still be short of part of the line it was flying).
            anchored = pathAnchoredAtAircraft(path, from: pos, track: track)
        }
        guard anchored.count >= 2, let next = anchored.dropFirst().first, next.isValid,
              isAheadOfAircraft(next, from: pos, track: track) else {
            // Nothing of the reroute lies ahead any more. Leave the aircraft on its heading and
            // the drawn line alone: vectoring it back around to geometry behind it is never the
            // answer. Clear-of-weather, or the rejoin auto-resume, still ends the deviation.
            activeWeatherConflict = previousConflict   // don't adopt a re-solve we didn't take
            diagnostics.log(.app, "Weather deviation: off the line, but no part of the reroute is ahead — no vector issued.")
            return false
        }

        // Keep the live conflict, the frozen line, the assigned heading and the armed turns all
        // keyed to the same geometry.
        if var conflict = activeWeatherConflict {
            conflict.deviationPath = anchored
            activeWeatherConflict = conflict
        }
        weatherDeviation.committedDeviationPath = anchored.map(WeatherDeviationContext.PathPoint.init)
        applyDeviationResult(
            deviationEngine.revectorOffPath(
                cs: callsignNow(),
                heading: assignedHeading(forTrueCourse: Geo.bearing(from: pos, to: next)),
                maintainAltitude: weatherMaintainAltitude(),
                context: weatherDeviation, facility: weatherFacility),
            controllerInitiated: true)
        captureWeatherRejoinTurn()
        return true
    }

    /// Arm the interior turn at `index` in the mint line: store the turn vertex, the
    /// bearing of the leg leading into it (to detect the aircraft passing abeam), and
    /// the heading to fly out of it toward the next vertex — so the telemetry loop can
    /// auto-issue the turn once the aircraft reaches it. Only interior vertices
    /// (1…count-2) are turns; the endpoints are the start and the rejoin. Like the
    /// beginning turn, the armed course is **true** — it is corrected into the pilot's
    /// frame at the moment the turn is spoken, not here.
    private func armRejoinTurn(at index: Int, path: [CLLocationCoordinate2D]) {
        guard index >= 1, index <= path.count - 2,
              path[index - 1].isValid, path[index].isValid, path[index + 1].isValid else {
            clearPendingRejoinTurn()
            return
        }
        let apex = path[index]
        weatherDeviation.pendingTurnIndex = index
        weatherDeviation.vectorApexLatitude = apex.latitude
        weatherDeviation.vectorApexLongitude = apex.longitude
        weatherDeviation.vectorLegBearing = Geo.bearing(from: path[index - 1], to: apex)
        weatherDeviation.pendingRejoinHeading =
            ApproachIntercept.normalizedHeading(Geo.bearing(from: apex, to: path[index + 1]))
    }

    /// Capture the turn points of the committed mint line and arm the **first**
    /// interior turn, so the telemetry loop can auto-issue a turn call at each vertex
    /// as the aircraft reaches it. A single dogleg (`[position, apex, rejoin]`) has one
    /// turn; a side-hug (`[position, turnOut, turnBack, rejoin]`) has two — out onto the
    /// parallel leg, then back down to the route — and each firing arms the next.
    private func captureWeatherRejoinTurn() {
        clearPendingRejoinTurn()
        guard weatherDeviation.state == .vectoringAroundWeather else { return }
        let path = committedMintLineCoordinates()
        // Need at least one interior turn vertex: [start, v1, …, rejoin].
        guard path.count >= 3, path.allSatisfy({ $0.isValid }) else { return }
        armRejoinTurn(at: 1, path: path)
    }

    /// Re-derive an in-progress weather deviation from the aircraft's *current* position
    /// after a telemetry discontinuity (returning from the background on a frozen Connect
    /// socket, where the first fresh fix has jumped ahead of where the frozen maneuver
    /// assumes the aircraft is). Rather than replay the turns flown during the gap or fire
    /// a stale entry turn late, decide from where the aircraft actually is:
    ///
    ///  • **Vectoring** — if the aircraft is at/past the reroute's end, drop the armed turn
    ///    (the intercept auto-resume ends it next tick); if the committed reroute ahead is
    ///    still clear and it's on the line, re-arm the nearest upcoming turn (no new call);
    ///    if the jump carried it off/through the line with weather still on the path from
    ///    here, recompute a fresh reroute from the current position and re-vector.
    ///  • **Deviation approved (turn held ahead)** — the held beginning turn is pinned to a
    ///    fixed turn-out the jump may have put behind the aircraft. Recompute from the
    ///    current position: if a fresh turn-out still sits ahead, re-hold there; if the
    ///    aircraft is now at/past it, vector onto the reroute now so it doesn't fly straight
    ///    through the weather on the stale entry heading.
    ///
    /// At most one ATC call is issued (the single re-vector), never a queue.
    private func resyncWeatherDeviation() {
        guard weatherFlowAllowed, !establishedOnFinal,
              let pos = aircraftState.coordinate, pos.isValid else { return }
        switch weatherDeviation.state {
        case .vectoringAroundWeather:
            let tail = committedTailAhead(from: pos)
            if tail.count < 2 {
                // At/past the reroute end — drop the armed turn; the auto-resume at the
                // intercept (next continuous tick) ends the deviation.
                clearPendingRejoinTurn()
            } else if conflictDetector.committedPathStillClear([pos] + tail, hazards: weatherHazards) {
                // Still correctly positioned to fly the remaining reroute — re-arm the
                // nearest upcoming turn to the current position, no new call.
                resyncWeatherRejoinTurn()
            } else if recomputeConflictFrom(pos) {
                // Jumped off/through the reroute with weather still on the path from here —
                // re-vector onto a fresh line anchored to the current position.
                issueResyncVector(from: pos)
            } else {
                clearPendingRejoinTurn()
            }
        case .deviationApproved:
            // The held beginning turn is pinned to a fixed turn-out the jump may have put
            // behind the aircraft — recompute from the current position, re-hold at a fresh
            // turn-out still ahead, or vector onto the reroute now rather than firing the
            // stale entry heading late (which could turn into/through the weather). A jumped
            // fix is not trusted to *cancel* the deviation, though: an accepted clearance is
            // never withdrawn off a reading the hysteresis wouldn't believe a clear route
            // from. It stands until a continuous tick can decide, so the pilot never comes
            // back from the background to find their approved deviation forgotten — and
            // re-advised from scratch as if they had never asked for it.
            replanHeldDeviation(from: pos, trustedFix: false)
        default:
            break
        }
    }

    /// Re-detect the route-weather conflict from the current position (protecting the
    /// committed reroute tail, then the filed route beyond it) and adopt it as the active
    /// conflict. Returns whether a conflict was found, so the caller can distinguish
    /// "weather still ahead — re-vector" from "clear now — settle".
    @discardableResult
    private func recomputeConflictFrom(_ pos: CLLocationCoordinate2D) -> Bool {
        guard var fresh = detectConflictAlong(route: revectorRouteAhead(from: pos)) else { return false }
        // The fresh line was solved against the committed deviation the aircraft is already
        // flying (plus the filed route beyond it). When it rejoins that committed line rather
        // than the filed route past it, carry it on down the rest of the committed line to the
        // flight path — otherwise, once this fresh line replaces the committed one, its rejoin
        // would sit mid-air on the now-erased deviation and the aircraft would resume own
        // navigation short of the route. Every deviation must end on the flight path.
        let tail = committedTailAhead(from: pos)
        if tail.count >= 2 {
            fresh = deviationExtendedToFlightPath(fresh, committedTail: tail)
        }
        // A re-planned line gets the same gradual return to course as a drawn one.
        fresh = deviationWithGentleRejoin(fresh, cap: weatherRejoinCap(),
                                          limitAlong: .greatestFiniteMagnitude)
        // …and the result is checked against the **filed route**, because nothing above has
        // been. The detector's "end the line where it first re-crosses the route" guarantee
        // only ever applies to the polyline it was handed, and here that polyline is the
        // committed reroute plus the route beyond it — so a fresh line is guaranteed to end on
        // the *old mint line*, which this one is about to erase. The splice above is what
        // carries it down to the flight path, and it is conditional (the end has to land within
        // a few miles of the committed tail, with enough of that tail left to be worth adding)
        // — so when it doesn't apply, the re-planned line ends in mid-air, which is exactly the
        // "recalculated route that doesn't end on the flight path" seen on the map.
        //
        // Rather than bend the geometry by hand, re-solve against the plain filed route ahead:
        // that puts the detector's own truncation/snapping guarantee back on the flight path.
        // The composite solve still stands if the plain one finds nothing (weather that only
        // sits on the reroute, not on the filed course), and the miss is logged either way.
        if !deviationEndsOnFlightPath(fresh.deviationPath, from: pos) {
            if var direct = detectConflictAlong(route: upcomingRouteCoordinates(from: pos)),
               deviationEndsOnFlightPath(direct.deviationPath, from: pos) {
                direct = deviationWithGentleRejoin(direct, cap: weatherRejoinCap(),
                                                   limitAlong: .greatestFiniteMagnitude)
                fresh = direct
                diagnostics.log(.app, "Weather deviation: the re-planned line ended off the flight path — re-solved against the filed route so it rejoins it.")
            } else {
                diagnostics.log(.app, "Weather deviation: the re-planned line ends off the flight path and no line against the filed route solves from here.")
            }
        }
        activeWeatherConflict = fresh
        lastConflictSeenAt = Date()
        return true
    }

    /// How far (NM) a deviation's rejoin may sit from the filed route and still count as
    /// ending on it. Wide enough to absorb the planar/great-circle rounding the deviation
    /// geometry works in, narrow enough that a line ending in mid-air is caught.
    private let deviationRejoinOnRouteToleranceNM: Double = 3

    /// Whether a drawn deviation actually ends **on the flight path** — the property that makes
    /// it a deviation rather than a diversion, and the one thing no single step in the re-plan
    /// chain checks end-to-end.
    private func deviationEndsOnFlightPath(_ path: [CLLocationCoordinate2D],
                                           from pos: CLLocationCoordinate2D) -> Bool {
        guard path.count >= 2, let end = path.last, end.isValid else { return true }
        let route = ([pos] + upcomingRouteCoordinates(from: pos)).filter { $0.isValid }
        guard route.count >= 2, let off = distanceToPolylineNM(end, route) else { return true }
        return off <= deviationRejoinOnRouteToleranceNM
    }

    /// Shortest distance (NM) from a point to a polyline, measured to the nearest **segment**
    /// rather than the nearest vertex — a rejoin that lands mid-segment between two fixes is
    /// on the route, and measuring to the fixes alone would call it tens of miles off.
    /// Local-plane approximation, like the rest of the deviation geometry.
    private func distanceToPolylineNM(_ p: CLLocationCoordinate2D,
                                      _ line: [CLLocationCoordinate2D]) -> Double? {
        guard line.count >= 2 else { return nil }
        let latScale = 60.0
        let lonScale = 60.0 * cos(p.latitude * .pi / 180)
        let px = p.longitude * lonScale, py = p.latitude * latScale
        var best = Double.greatestFiniteMagnitude
        for i in 0..<(line.count - 1) {
            let a = line[i], b = line[i + 1]
            let ax = a.longitude * lonScale, ay = a.latitude * latScale
            let bx = b.longitude * lonScale, by = b.latitude * latScale
            let dx = bx - ax, dy = by - ay
            let lenSq = dx * dx + dy * dy
            let t = lenSq <= 0 ? 0 : max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq))
            best = min(best, hypot(px - (ax + t * dx), py - (ay + t * dy)))
        }
        return best.isFinite ? best : nil
    }

    /// When a re-vector's fresh line rejoins the committed deviation the aircraft is already
    /// flying (the first deviation) rather than the filed route beyond it, splice the
    /// remainder of that committed line on so the new deviation still runs all the way to the
    /// flight-plan intercept.
    ///
    /// A re-vector solves the fresh line against the committed tail plus the filed route past
    /// it (`revectorRouteAhead`), and the detector ends the drawn line at the **first** place it
    /// re-crosses that polyline — which, for a small new cell, is back on the committed tail,
    /// well short of the route. Because freezing the fresh line **replaces** the committed one,
    /// its rejoin would then sit mid-air on the now-erased first deviation, and the aircraft
    /// would "resume own navigation" in the middle of nowhere. Appending the committed tail from
    /// that rejoin onward carries the new line down the old one to where it meets the filed
    /// route, so every deviation still ends on the flight path.
    private func deviationExtendedToFlightPath(_ conflict: RouteWeatherConflict,
                                               committedTail tail: [CLLocationCoordinate2D]) -> RouteWeatherConflict {
        var conflict = conflict
        let path = conflict.deviationPath.filter { $0.isValid }
        let tailPts = tail.filter { $0.isValid }
        guard path.count >= 2, tailPts.count >= 2,
              let end = path.last, let rejoin = tailPts.last, rejoin.isValid else { return conflict }

        // How close the fresh line's end must sit to the committed line to count as "on it",
        // and how much of that line must still remain past it to be worth splicing. A genuine
        // rejoin lands exactly on the committed line (the detector ends it at the intersection
        // point), so the tolerance only absorbs planar/great-circle rounding.
        let onLineTolNM = 5.0
        let minRemainderNM = 1.0

        // Locate the fresh line's end on the committed tail: nearest segment, its projection,
        // and the along-tail distance to that projection.
        var bestSeg = -1
        var bestDist = Double.greatestFiniteMagnitude
        var bestProj = end
        var alongToProj = 0.0
        var cumulative = 0.0
        for i in 0..<(tailPts.count - 1) {
            let proj = closestPointOnSegmentNM(end, tailPts[i], tailPts[i + 1])
            let d = Geo.distanceNM(from: end, to: proj)
            if d < bestDist {
                bestDist = d
                bestSeg = i
                bestProj = proj
                alongToProj = cumulative + Geo.distanceNM(from: tailPts[i], to: proj)
            }
            cumulative += Geo.distanceNM(from: tailPts[i], to: tailPts[i + 1])
        }
        // Only splice when the fresh line genuinely ends **on** the committed tail with a
        // meaningful remainder still to fly. If its end already sits on the filed route beyond
        // the old line (projection at/near the old line's end), it ends on the flight path
        // already — leave it alone.
        guard bestSeg >= 0, bestDist <= onLineTolNM,
              cumulative - alongToProj > minRemainderNM else { return conflict }

        // Carry the fresh line down the committed tail to its rejoin on the filed route,
        // collapsing any zero-length seam so no leg has an undefined bearing.
        var extended = Array(path.dropLast())
        for point in [bestProj] + Array(tailPts[(bestSeg + 1)...]) {
            if let prev = extended.last, Geo.distanceNM(from: prev, to: point) < 0.05 { continue }
            extended.append(point)
        }
        guard extended.count >= 2 else { return conflict }
        conflict.deviationPath = extended
        // The true rejoin is now the committed line's end on the filed route — retag the rejoin
        // fix so the map marker, the final turn and the auto-resume all key off the flight path,
        // not the mid-air point where the new detour met the old line.
        let rejoinName = weatherDeviation.rejoinFix ?? conflict.rejoinFix?.name ?? ""
        conflict.rejoinFix = Waypoint(name: rejoinName, latitude: rejoin.latitude, longitude: rejoin.longitude)
        return conflict
    }

    /// Issue a single ATC vector onto the freshly-recomputed reroute from the current
    /// position — controller-initiated (no pilot line), since it's a resync after the
    /// position jumped, not a pilot request. Freezes the new line and arms its turns.
    /// `activeWeatherConflict` must already reflect the recompute (`recomputeConflictFrom`).
    private func issueResyncVector(from pos: CLLocationCoordinate2D) {
        let side = activeWeatherConflict?.recommendedDirection ?? .right
        let heading = weatherDeviationHeading(direction: side)
        // A jumped fix can leave the freshly-solved line pointing behind the aircraft; vector it
        // around weather, never around the compass.
        guard !vectorWouldReverseAircraft(heading, at: pos) else {
            clearPendingRejoinTurn()
            return
        }
        applyDeviationResult(
            deviationEngine.beginDeviationTurn(cs: callsignNow(), heading: heading,
                                               maintainAltitude: weatherMaintainAltitude(),
                                               context: weatherDeviation, facility: weatherFacility),
            controllerInitiated: true)
        freezeCommittedDeviationPath()
        captureWeatherRejoinTurn()
    }

    /// Re-arm the nearest not-yet-reached interior turn on the committed vectoring line to
    /// the aircraft's current position (or clear the armed turn if it is past them all), so
    /// a small drift after a resync picks up at the right turn without replaying passed
    /// ones. No ATC call — the genuinely-next turn fires normally as the aircraft reaches
    /// it. Used by `resyncWeatherDeviation` when the committed reroute ahead is still clear.
    private func resyncWeatherRejoinTurn() {
        guard weatherDeviation.state == .vectoringAroundWeather,
              let pos = aircraftState.coordinate, pos.isValid else { return }
        let path = committedMintLineCoordinates()
        guard path.count >= 3, path.allSatisfy({ $0.isValid }) else { return }
        for index in 1...(path.count - 2) {
            let apex = path[index]
            let legBearing = Geo.bearing(from: path[index - 1], to: apex)
            let apexToAircraft = Geo.bearing(from: apex, to: pos)
            // Along-track distance from the vertex in the inbound-leg direction; < 0 means
            // the aircraft has not yet reached this vertex's abeam line (still ahead of it).
            let alongNM = Geo.distanceNM(from: pos, to: apex) * cos((apexToAircraft - legBearing) * .pi / 180)
            if alongNM < 0 {
                armRejoinTurn(at: index, path: path)
                return
            }
        }
        // Past every interior turn — drop the armed turn; the rejoin/auto-resume handles
        // the tail on a subsequent continuous tick.
        clearPendingRejoinTurn()
    }

    /// While vectoring around weather, once the aircraft reaches the next turn in the
    /// mint line, the controller automatically issues the turn onto the following leg
    /// — an intermediate turn (onto the parallel leg) keeps vectoring, the final turn
    /// rejoins the filed route. After a non-final turn it arms the next interior turn,
    /// so a side-hug line gets both its turns called. Called each telemetry tick.
    /// Returns whether it issued a turn this tick, so the caller can skip the
    /// auto-resume check on the same tick (they both key off the mint line's geometry).
    @discardableResult
    private func maybeIssueWeatherRejoinTurn() -> Bool {
        guard !companionStandby, weatherFlowAllowed,
              weatherDeviation.state == .vectoringAroundWeather,
              let index = weatherDeviation.pendingTurnIndex,
              let heading = weatherDeviation.pendingRejoinHeading,
              let apexLat = weatherDeviation.vectorApexLatitude,
              let apexLon = weatherDeviation.vectorApexLongitude,
              let pos = aircraftState.coordinate, pos.isValid else { return false }
        let apex = CLLocationCoordinate2D(latitude: apexLat, longitude: apexLon)
        let distance = Geo.distanceNM(from: pos, to: apex)
        let path = committedMintLineCoordinates()
        // Call the turn a lead distance *before* the vertex so the aircraft rolls out on
        // the next leg through it rather than overshooting the mint line. The lead is the
        // base capture reach (~30 s of travel) plus the turn-anticipation distance for
        // this vertex's course change, bounded so a turn is never called while still most
        // of a leg away.
        let inboundLeg: Double? = (index >= 1 && index < path.count && path[index - 1].isValid)
            ? Geo.distanceNM(from: path[index - 1], to: apex) : nil
        let turnDegrees = weatherDeviation.vectorLegBearing
            .map { courseChangeDegrees(inbound: $0, outbound: Double(heading)) } ?? 30
        let leadNM = turnLeadNM(turnDegrees: turnDegrees, inboundLegNM: inboundLeg)
        // Fire when within the lead of the turn vertex, or once the aircraft has passed
        // abeam/beyond it along the leg into it (so flying wide of it still triggers the
        // turn), pre-tripped by the same lead so a wide pass also anticipates.
        let reached: Bool
        if distance <= leadNM {
            reached = true
        } else if let legBearing = weatherDeviation.vectorLegBearing {
            // Along-track distance from the vertex in the inbound leg direction; ≥ −lead
            // means the aircraft is within the anticipation lead of the vertex's abeam line.
            let apexToAircraft = Geo.bearing(from: apex, to: pos)
            let alongNM = distance * cos((apexToAircraft - legBearing) * .pi / 180)
            reached = alongNM >= -leadNM
        } else {
            reached = false
        }
        guard reached else { return false }
        // Never turn the aircraft around: every drawn leg is bounded to 100° off course, so a
        // turn this far off the current track means the aircraft has left the drawn geometry
        // behind. Skip it — the off-path re-plan or the auto-resume takes it from here.
        guard !vectorWouldReverseAircraft(heading, at: pos) else { return false }
        // The turn onto the last leg (toward the rejoin, the final point) is the final
        // turn; earlier interior vertices are intermediate turns that keep vectoring.
        let isFinalTurn = index >= path.count - 2
        lastAutoTurnIssuedAt = Date()   // let it roll out before drift is judged
        applyDeviationResult(
            deviationEngine.rejoinTurn(cs: callsignNow(),
                                       heading: assignedHeading(forTrueCourse: Double(heading)),
                                       rejoinFix: weatherDeviation.rejoinFix,
                                       finalTurn: isFinalTurn, context: weatherDeviation,
                                       facility: weatherFacility),
            controllerInitiated: true)
        // Arm the next interior turn if the mint line has one (a side-hug has two).
        // The engine cleared the fired turn, so this re-arms on the fresh context.
        if !isFinalTurn {
            armRejoinTurn(at: index + 1, path: path)
        }
        return true
    }

    /// Once the aircraft reaches the flight-plan intercept at the end of the mint line
    /// without the pilot reporting clear of weather, the controller automatically
    /// resumes own navigation and ends the deviation. Guarded to the final leg (at or
    /// beyond the last turn) and within `autoResumeInterceptNM` of the intercept, so it
    /// can't trip during the outbound or parallel legs.
    private func maybeAutoResumeAtRouteIntercept() {
        guard !companionStandby, weatherFlowAllowed else { return }
        switch weatherDeviation.state {
        case .deviationApproved, .vectoringAroundWeather: break
        default: return
        }
        guard let pos = aircraftState.coordinate, pos.isValid,
              let line = weatherDeviationLine, line.count >= 2,
              let end = line.last, end.isValid else { return }
        let lastTurn = line[line.count - 2]
        guard lastTurn.isValid else { return }
        // On the final leg to the intercept: at or beyond the last turn along that leg.
        let legBearing = Geo.bearing(from: lastTurn, to: end)
        let turnToAircraft = Geo.bearing(from: lastTurn, to: pos)
        let alongNM = Geo.distanceNM(from: lastTurn, to: pos) * cos((turnToAircraft - legBearing) * .pi / 180)
        guard alongNM >= 0, Geo.distanceNM(from: pos, to: end) <= autoResumeInterceptNM else { return }
        autoResumeOwnNavigation()
    }

    /// End a lateral weather deviation by resuming own navigation, with no pilot
    /// clear-of-weather call — the aircraft flew the mint line all the way to the
    /// flight-plan intercept on its own. Mirrors the clear-of-weather cleanup.
    private func autoResumeOwnNavigation() {
        applyDeviationResult(
            deviationEngine.autoResumeOwnNavigation(cs: callsignNow(), context: weatherDeviation,
                                                    facility: weatherFacility),
            controllerInitiated: true)
        if settings.mockMode { radarOverlay.mockCells = [] }
        activeWeatherConflict = nil
        weatherDeviation.reset()
        weatherDeviation.state = .none
        weatherHandled = false
        mockWeatherAdvisoryIssued = false
        lastConflictSeenAt = nil
        lastOffPathReplanAt = nil
        lastAutoTurnIssuedAt = nil
    }

    func requestHigherForWeather() {
        guard !companionStandby, weatherFlowAllowed else { return }
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: true)
        applyDeviationResult(deviationEngine.requestAltitude(
            cs: callsignNow(), higher: true, targetAltitude: target,
            context: weatherDeviation, facility: weatherFacility))
        assignedAltitude = target
    }

    func requestLowerForWeather() {
        guard !companionStandby, weatherFlowAllowed else { return }
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: false)
        applyDeviationResult(deviationEngine.requestAltitude(
            cs: callsignNow(), higher: false, targetAltitude: target,
            context: weatherDeviation, facility: weatherFacility))
        assignedAltitude = target
    }

    /// Pilot reports clear of weather; the controller clears back to the route.
    func reportClearOfWeather() {
        guard !companionStandby else { return }
        let cs = callsignNow()
        applyDeviationResult(deviationEngine.reportClearOfWeather(
            cs: cs, inputs: deviationInputs(direction: weatherDeviation.requestedDeviationDirection ?? .right),
            context: weatherDeviation, facility: weatherFacility))
        // Clear the conflict so the flow settles. In Mock Mode remove the demo cell
        // so the aircraft is genuinely "past" the weather.
        if settings.mockMode { radarOverlay.mockCells = [] }
        activeWeatherConflict = nil
        weatherDeviation.reset()
        weatherDeviation.state = .none
        weatherHandled = false
        mockWeatherAdvisoryIssued = false
        lastConflictSeenAt = nil
        lastOffPathReplanAt = nil
        lastAutoTurnIssuedAt = nil
    }

    /// Pilot elects to continue on course through the advisory.
    func continueThroughWeather() {
        guard !companionStandby else { return }
        weatherHandled = true
        let cs = callsignNow()
        postPilot(ATCTransmission(sender: .pilot, facility: weatherFacility,
            displayText: "\(cs.display), continuing on course.",
            spokenText: "\(cs.spoken), continuing on course."))
        post(ATCTransmission(sender: .atc, facility: weatherFacility,
            displayText: "\(cs.display), roger, advise if you need to deviate.",
            spokenText: "\(cs.spoken), roger, advise if you need to deviate."), speak: true)
        weatherDeviation.reset()
        weatherDeviation.state = .none
        // Continuing resolves the prompt: drop the confirm-clear hold so a genuinely
        // clear route removes the banner promptly. Weather still ahead re-arms the
        // hold on the next detected tick (the banner stays for a possible reroute).
        lastConflictSeenAt = nil
    }

    /// Re-issue the last weather advisory/instruction ("say again").
    func sayAgainWeather() {
        guard !companionStandby else { return }
        let cs = callsignNow()
        postPilot(ATCTransmission(sender: .pilot, facility: weatherFacility,
            displayText: "Say again for \(cs.display).",
            spokenText: "Say again for \(cs.spoken)."))
        if let last = lastATCTransmission {
            post(ATCTransmission(sender: .atc, facility: last.facility,
                                 displayText: last.displayText, spokenText: last.spokenText), speak: true)
        }
    }

    // MARK: - Diagnostics helpers

    func advanceMockPhase() {
        mock.advancePhase()
    }

    func resetAppData() {
        transcript.removeAll()
        latestTransmission = nil
        atcCommunicationStarted = false
        clearSavedSession()
        resetWeatherDeviation()
        radarOverlay.mockCells = []
        radarOverlay.sampledCells = []
        airportSurface.clear()
        pendingTaxiMapRestore = .none
        settings.resetAll()
        syncFlightPlanFromSettings()
        diagnostics.log(.app, "App data reset.")
    }

    /// Clear the current flight and start a fresh one from the gate. Wipes the
    /// conversation and ATC/phase state but keeps the user's settings and flight
    /// plan, then restarts the active feed. Use this between flights so the new
    /// flight does not inherit the previous chat history.
    func clearFlight() {
        manualTuning = false
        tunedFacility = nil
        pendingCheckInFacility = nil
        goAroundInProgress = false
        clearReadbackGate()
        cancelTakeoffClearanceTimer()
        clearSavedSession()
        resetWeatherDeviation()
        resetATISState(clearReported: true)
        airportSurface.clear()
        pendingTaxiMapRestore = .none
        speech.stop()
        // End the ambient chatter with the flight; it restarts on the new flight's first ATC
        // call (the mock feed below re-begins the conversation).
        atcCommunicationStarted = false
        updateChatterRunState()
        transcript.removeAll()
        latestTransmission = nil
        lastATCTransmission = nil
        assignedAltitude = 0
        hasDeparted = false
        arrivalAnnounced = false
        awaitingGateArrival = false
        monitoringTower = false
        arrivalGatePosition = nil
        arrivalRouteWaitStartedAt = nil
        pendingArrivalTaxiClearance = nil
        gateMonitored = false
        lastSpokenText = ""
        lastSpokenIntent = nil
        phase = .preflight
        phaseDetector = PhaseDetector()
        stateMachine.reset()
        atcState = .connectedIdle
        currentFacility = .clearance
        if settings.mockMode {
            // Restart the mock feed from the gate.
            mock.stop()
            mock.setPhase(.preflight)
            startMock()
        } else {
            // Keep the live IF connection; the conversation rebuilds from the next
            // telemetry update.
            stateMachine.setConnected()
        }
        diagnostics.log(.app, "Flight cleared — ready for a new flight.")
    }
}

// MARK: - ATIS

extension AppModel {

    // MARK: Availability

    /// Whether the aircraft is within the arrival-ATIS range: departed, position and
    /// destination both known, and within 100 NM of the destination field.
    var withinArrivalATISRange: Bool {
        guard hasDeparted,
              let pos = aircraftState.coordinate, pos.isValid,
              let dest = resolvedDestinationCoordinate(), dest.isValid else { return false }
        return Geo.distanceNM(from: pos, to: dest) <= 100
    }

    /// Whether the **departure** ATIS tune button should be offered: pre-departure (at
    /// the gate / on the ground before the first takeoff) and ATIS data is available.
    var departureATISAvailable: Bool { isPreDeparture && departureATIS != nil }

    /// Whether the **arrival** ATIS tune button should be offered: airborne/arriving,
    /// within 100 NM of the destination, not yet parked, and ATIS data is available.
    var arrivalATISAvailable: Bool {
        hasDeparted && atcState != .parked && withinArrivalATISRange && arrivalATIS != nil
    }

    /// The ATIS report relevant to the current phase (arrival preferred once in range,
    /// else the departure ATIS), or nil when no ATIS applies right now.
    var currentATIS: AirportATIS? {
        if arrivalATISAvailable { return arrivalATIS }
        if departureATISAvailable { return departureATIS }
        return nil
    }

    /// Whether the currently-relevant ATIS is the arrival (destination) ATIS.
    var currentATISIsArrival: Bool { arrivalATISAvailable }

    /// Whether the ATIS tune button should be shown in the frequency grid right now:
    /// ATIS data is available for the current phase **and** the pilot hasn't yet tuned
    /// away from it. Tuning any controller/ramp frequency dismisses it for the phase
    /// (departure ATIS at the gate, arrival ATIS within 100 NM), so it behaves like a
    /// real radio — you copy the broadcast, then move on to your next frequency.
    var atisButtonVisible: Bool {
        guard currentATIS != nil else { return false }
        return currentATISIsArrival ? !arrivalATISDismissed : !departureATISDismissed
    }

    /// The ATIS button never reads as the active (tuned) frequency: tapping it plays the
    /// latest broadcast and captures the information code, but the radio stays on the
    /// controller the pilot is already on. The active highlight belongs to that controller.
    var atisButtonActive: Bool { false }

    /// The ICAO whose ATIS is relevant right now (destination on arrival, else origin).
    var atisAirport: String {
        currentATISIsArrival ? flightPlan.destination : flightPlan.departure
    }

    /// The information code letter carried by the currently-relevant ATIS report
    /// ("A"), or nil when none is known.
    var currentATISCode: String? {
        currentATIS?.letter(arrival: currentATISIsArrival)
    }

    /// Secondary label for the ATIS tune button: the current info code when known
    /// ("Info B"), else a prompt to listen.
    var atisButtonSubtitle: String {
        if let code = currentATISCode { return "Info \(code)" }
        return "Listen"
    }

    /// A one-line receipt summary shown under the ATIS button once the pilot has tuned
    /// in and captured the information code, e.g. "KLAX arrival information Bravo —
    /// added to your check-in." Nil until the pilot has tuned ATIS for this phase.
    var atisReceiptSummary: String? {
        let arrival = currentATISIsArrival
        guard let letter = arrival ? reportedArrivalInfo : reportedDepartureInfo else { return nil }
        let word = ATISPhraseology.phoneticLetter(letter)
        let field = arrival ? flightPlan.destination : flightPlan.departure
        let kind = arrival ? "arrival" : "departure"
        let where_ = arrival ? "check-in" : "taxi request"
        return "\(field) \(kind) information \(word) — added to your \(where_)."
    }

    // MARK: Fetching

    /// Refresh ATIS on the same cadence as weather (connect, route change,
    /// pull-to-refresh, and the periodic timer): the departure ATIS while pre-departure,
    /// the arrival ATIS once within 100 NM of the destination.
    ///
    /// ATIS is a real-world, live-data feature keyed to your actual flight, so it only
    /// fetches in **live mode** — Mock Mode stays a fully offline demo (and unit tests
    /// that drive the mock feed never touch the network). Availability, tuning, and the
    /// append logic remain mode-agnostic so injected reports can still be exercised.
    func refreshATIS() async {
        guard !settings.mockMode else { updateATISDiagnostics(); return }
        if isPreDeparture, !flightPlan.departure.isEmpty {
            await fetchDepartureATIS(force: false)
        }
        if hasDeparted, atcState != .parked, withinArrivalATISRange, !flightPlan.destination.isEmpty {
            await fetchArrivalATIS(force: false)
        }
        updateATISDiagnostics()
    }

    /// Opportunistic destination-ATIS fetch from the telemetry loop, so the arrival
    /// button appears within seconds of crossing 100 NM rather than at the next timer
    /// tick. Throttled to at most once a minute and skipped once we already have it.
    func maybeFetchArrivalATIS() {
        guard !settings.mockMode else { return }   // live-data feature; see refreshATIS
        guard hasDeparted, atcState != .parked, arrivalATIS == nil,
              withinArrivalATISRange, !flightPlan.destination.isEmpty else { return }
        let now = Date()
        if let last = lastArrivalATISAttempt, now.timeIntervalSince(last) < 60 { return }
        lastArrivalATISAttempt = now
        Task { await fetchArrivalATIS(force: false) }
    }

    private func fetchDepartureATIS(force: Bool) async {
        let icao = flightPlan.departure
        guard !icao.isEmpty else { departureATIS = nil; return }
        do {
            let atis = try await atisService.atis(for: icao, forceRefresh: force)
            // Guard against a late response after the origin changed.
            if icao == flightPlan.departure {
                // A routine background refresh that momentarily comes back empty (a
                // transient 200 with no parseable body) must not blank an ATIS we already
                // have — that produced the "received → not available → received" flapping.
                // Keep the last good report; only a forced pull (a tune) is authoritative
                // enough to clear it.
                if let atis { departureATIS = atis } else if force { departureATIS = nil }
            }
        } catch {
            diagnostics.log(.atis, "Departure ATIS \(icao) unavailable: \(error.localizedDescription)")
        }
        updateATISDiagnostics()
    }

    private func fetchArrivalATIS(force: Bool) async {
        let icao = flightPlan.destination
        guard !icao.isEmpty else { arrivalATIS = nil; return }
        do {
            let atis = try await atisService.atis(for: icao, forceRefresh: force)
            if icao == flightPlan.destination {
                // See fetchDepartureATIS: don't let a transient empty refresh blank a
                // report we already hold.
                if let atis { arrivalATIS = atis } else if force { arrivalATIS = nil }
            }
        } catch {
            diagnostics.log(.atis, "Arrival ATIS \(icao) unavailable: \(error.localizedDescription)")
        }
        updateATISDiagnostics()
    }

    // MARK: Tuning

    /// Play the ATIS broadcast for the current phase (origin pre-departure, or the
    /// destination within 100 NM). Immediately pulls the **latest** ATIS, plays it on
    /// the ATIS voice, and stores the information code so it is appended to the taxi
    /// request (departure) / approach check-in (arrival). Like listening in on a second
    /// radio, this **leaves the pilot tuned to their current frequency** — it does not
    /// make ATIS the active frequency. No controller replies — ATIS is a one-way
    /// broadcast — so this never advances the state machine or the read-back gate.
    func tuneATIS() {
        guard atisButtonVisible else { return }
        let arrival = currentATISIsArrival
        let icao = arrival ? flightPlan.destination : flightPlan.departure
        guard !icao.isEmpty else { return }
        diagnostics.log(.atis, "Playing \(icao) ATIS — pulling latest.")
        // Mock mode is a fully offline demo (and the unit-test path): replay the already
        // injected/cached report rather than hitting the live feed.
        if settings.mockMode {
            applyTunedATIS(arrival ? arrivalATIS : departureATIS, arrival: arrival, icao: icao)
            return
        }
        Task { @MainActor in
            do {
                let atis = try await atisService.atis(for: icao, forceRefresh: true)
                applyTunedATIS(atis, arrival: arrival, icao: icao)
            } catch {
                diagnostics.log(.atis, "ATIS tune \(icao) failed: \(error.localizedDescription)")
                updateATISDiagnostics()
            }
        }
    }

    private func applyTunedATIS(_ atis: AirportATIS?, arrival: Bool, icao: String) {
        guard let atis, let part = atis.part(arrival: arrival) else {
            // ATIS disappeared since the button was shown — drop it so the button hides.
            if arrival { arrivalATIS = nil } else { departureATIS = nil }
            diagnostics.log(.atis, "\(icao): no ATIS available on tune.")
            updateATISDiagnostics()
            return
        }
        if arrival { arrivalATIS = atis } else { departureATIS = atis }
        // Store the information code the pilot now "has" — this is what gets reported.
        let letter = atis.letter(arrival: arrival)
        if arrival { reportedArrivalInfo = letter } else { reportedDepartureInfo = letter }

        // Post the ATIS broadcast to the transcript on the ATIS voice. It is not a
        // controller call: no read-back, no hand-off bookkeeping, and it does not become
        // `latestTransmission`/`lastATCTransmission`.
        let tx = atisTransmission(for: part)
        transcript.append(tx)
        if transcript.count > 200 { transcript.removeFirst(transcript.count - 200) }
        diagnostics.log(.atis, "[ATIS] \(icao) information \(letter ?? part.letter): received.")
        speech.speak(tx)
        updateATISDiagnostics()
        persistSession()
    }

    /// Build the one-way ATIS transcript line: the verbatim text for display, and the
    /// abbreviation-expanded, digit-by-digit reading for speech.
    private func atisTransmission(for part: AirportATIS.Part) -> ATCTransmission {
        ATCTransmission(sender: .system,
                        facility: .ground,   // label overridden to "ATIS" in the transcript row
                        displayText: ATISPhraseology.displayText(part.text),
                        spokenText: ATISPhraseology.spokenText(part.text, icao: engineUsesICAO),
                        isATIS: true)
    }

    // MARK: Appending the information code

    /// The phonetic information word ("Alpha") the pilot reports for the given phase —
    /// **once**. Returns nil when no ATIS was received or the code has already been
    /// reported, so it is never repeated on a re-tap. Marks the phase reported.
    private func consumeATISInfoWord(arrival: Bool) -> String? {
        if arrival {
            guard !arrivalInfoAppended, let letter = reportedArrivalInfo else { return nil }
            arrivalInfoAppended = true
            return ATISPhraseology.phoneticLetter(letter)
        } else {
            guard !departureInfoAppended, let letter = reportedDepartureInfo else { return nil }
            departureInfoAppended = true
            return ATISPhraseology.phoneticLetter(letter)
        }
    }

    /// Append ", information <word>" before the trailing period of a pilot transmission
    /// (both display and spoken forms). A nil/empty word returns the transmission
    /// unchanged, so nothing is ever appended when no ATIS was received.
    func appendingATISInfo(_ tx: ATCTransmission, word: String?) -> ATCTransmission {
        guard let word = word?.trimmingCharacters(in: .whitespaces), !word.isEmpty else { return tx }
        func withInfo(_ s: String) -> String {
            if s.hasSuffix(".") { return String(s.dropLast()) + ", information \(word)." }
            return s + ", information \(word)"
        }
        var out = tx
        out.displayText = withInfo(tx.displayText)
        out.spokenText = withInfo(tx.spokenText)
        return out
    }

    /// The pilot tuned a controller / ramp, so they've moved on from the ATIS for this
    /// phase: if an ATIS was actually available, dismiss its button so it drops out of the
    /// frequency grid (you don't keep re-listening after you've copied it). Guarding on
    /// availability means an early tune before the feed arrives never permanently hides a
    /// later-arriving ATIS. Called from `tuneTo`.
    func leaveATISFrequency() {
        guard currentATIS != nil else { return }
        if currentATISIsArrival { arrivalATISDismissed = true } else { departureATISDismissed = true }
    }

    // MARK: State + diagnostics

    /// Whether the phraseology engine is on the ICAO pack (drives ATIS digit words).
    private var engineUsesICAO: Bool { settings.phraseologyMode == .icao }

    /// Reset per-flight ATIS visibility, and — only for a genuinely fresh flight —
    /// clear the fetched reports and the received information codes.
    ///
    /// `clearReported` distinguishes a **fresh flight** (new flight / mock start /
    /// Clear Flight → `true`) from a **reconnect or resume of the same flight**
    /// (`false`, e.g. returning from another app, which runs `disconnect()` +
    /// `startLive()`). On a reconnect the already-fetched `departureATIS`/`arrivalATIS`
    /// are kept in memory rather than blanked: nulling them made the Diagnostics ATIS
    /// line (and the tune button) flap "received → not available → received" on every
    /// app switch, because the connect-time refresh only re-populated them a beat later.
    /// The refresh now updates them in place, and an endpoint change still drops the
    /// stale report via `updateFlightPlan`.
    func resetATISState(clearReported: Bool) {
        // Dismissal state is per-flight visibility: always reset it so a fresh
        // (or re-derived) flight shows the ATIS button again. A resume restores the
        // dismissal flags afterwards from the snapshot.
        departureATISDismissed = false
        arrivalATISDismissed = false
        if clearReported {
            departureATIS = nil
            arrivalATIS = nil
            lastArrivalATISAttempt = nil
            reportedDepartureInfo = nil
            reportedArrivalInfo = nil
            departureInfoAppended = false
            arrivalInfoAppended = false
        }
        updateATISDiagnostics()
    }

    #if DEBUG
    /// Test hook: set the information codes the pilot has "received", so the taxi
    /// request / approach check-in append path can be exercised without the network.
    func setReportedATISForTesting(departure: String?, arrival: String?) {
        reportedDepartureInfo = departure
        reportedArrivalInfo = arrival
        updateATISDiagnostics()
    }

    /// Test hook: inject ATIS reports directly (bypassing the network) so availability
    /// and tune behavior can be exercised deterministically.
    func setATISReportsForTesting(departure: AirportATIS?, arrival: AirportATIS?) {
        departureATIS = departure
        arrivalATIS = arrival
        updateATISDiagnostics()
    }
    #endif

    /// Refresh the read-only ATIS diagnostics snapshot shown on the Diagnostics tab.
    func updateATISDiagnostics() {
        var d = atisDiagnostics
        d.departureAirport = flightPlan.departure
        d.departureReceived = departureATIS != nil
        d.departureLetter = departureATIS?.letter(arrival: false)
        d.arrivalAirport = flightPlan.destination
        d.arrivalReceived = arrivalATIS != nil
        d.arrivalLetter = arrivalATIS?.letter(arrival: true)
        d.withinArrivalRange = withinArrivalATISRange
        d.reportedDeparture = reportedDepartureInfo
        d.reportedArrival = reportedArrivalInfo
        atisDiagnostics = d
    }
}
