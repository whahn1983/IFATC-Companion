package com.h3consultingpartners.ifatccompanion.ui

import com.h3consultingpartners.ifatccompanion.core.atis.ATISPhraseology
import com.h3consultingpartners.ifatccompanion.core.airports.AirportDatabase
import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.billing.EntitlementState
import com.h3consultingpartners.ifatccompanion.core.connect.IFConnectState
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticRecord
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionState
import com.h3consultingpartners.ifatccompanion.core.session.PilotActionPresentation
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceSessionState
import com.h3consultingpartners.ifatccompanion.core.weather.WeatherSessionState
import com.h3consultingpartners.ifatccompanion.ui.map.RouteMapModel
import com.h3consultingpartners.ifatccompanion.ui.map.TaxiMapModel
import com.h3consultingpartners.ifatccompanion.ui.screens.AtcScreenActions
import com.h3consultingpartners.ifatccompanion.ui.screens.AtcScreenModel
import com.h3consultingpartners.ifatccompanion.ui.screens.DiagnosticsScreenActions
import com.h3consultingpartners.ifatccompanion.ui.screens.DiagnosticsScreenModel
import com.h3consultingpartners.ifatccompanion.ui.screens.FlightScreenActions
import com.h3consultingpartners.ifatccompanion.ui.screens.FlightScreenModel
import com.h3consultingpartners.ifatccompanion.ui.screens.PhraseologyProfilesActions
import com.h3consultingpartners.ifatccompanion.ui.screens.PhraseologyProfilesModel
import com.h3consultingpartners.ifatccompanion.ui.screens.SettingsScreenActions
import com.h3consultingpartners.ifatccompanion.ui.screens.SettingsScreenModel
import com.h3consultingpartners.ifatccompanion.ui.screens.SubscriptionScreenActions
import com.h3consultingpartners.ifatccompanion.ui.screens.WeatherScreenActions
import com.h3consultingpartners.ifatccompanion.ui.screens.WeatherScreenModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The adapter layer between the engine and the screens.
 *
 * Every screen takes a plain data model and a bag of callbacks and knows nothing about
 * the ViewModel, the coordinator, or Android — that is what lets `:uicheck` type-check
 * all of them against desktop Compose without an Android SDK. These functions are the one
 * place that translation happens, so a screen never reaches into engine state itself.
 *
 * Nothing here decides anything. Formatting is presentation; every rule lives in `:core`.
 */

// region ATC

fun FlightViewModel.atcModel(
    session: FlightSessionState,
    settings: AppSettings,
    weather: WeatherSessionState,
    ui: FlightViewModel.UiState,
): AtcScreenModel {
    val plan = session.flightPlan
    val arrivalPhaseAtis = session.hasDeparted
    val atis = if (arrivalPhaseAtis) weather.arrivalAtis else weather.departureAtis
    val atisAirport = if (arrivalPhaseAtis) plan.destination else plan.departure

    return AtcScreenModel(
        session = session,
        callsign = ui.draftCallsign,
        callsignPlaceholder = plan.callsign.ifEmpty { "e.g. UAL123" },
        departureGate = ui.draftDepartureGate,
        arrivalGate = ui.draftArrivalGate,
        nearestAirport = session.aircraftState.nearestAirport.orEmpty(),
        assignedAltitudeText = formatAltitude(session.assignedAltitude),
        facilityLabel = session.currentFacility.title,
        connectionText = session.connectionState.detailedTitle,
        standbyText = if (session.companionStandby) PilotActionPresentation.STANDBY_HINT else null,
        weatherBannerText = weatherBannerText(weather),
        frequencyText = { facility -> formatFrequency(frequencyForFacility(facility)) },
        canTune = { facility -> facility in session.relevantFacilities },
        tunableFacilities = ATCFacility.entries.filter { it in session.relevantFacilities },
        atisButtonVisible = atis != null,
        atisButtonSubtitle = atis?.letter(arrivalPhaseAtis)
            ?.let { "Information ${ATISPhraseology.phoneticLetter(it)}" }
            .orEmpty(),
        atisButtonActive = session.currentFacility == ATCFacility.ATIS,
        atisAirport = atisAirport,
        atisIsArrival = arrivalPhaseAtis,
        atisReceiptSummary = atisReceiptSummary(weather, arrivalPhaseAtis),
        holdToTalkEnabled = settings.holdToTalkEnabled,
        isListening = ui.isListening,
        partialSpeech = ui.speechPartial,
        lastSpokenText = ui.lastSpokenText,
        lastSpokenIntentTitle = ui.lastSpokenIntentTitle,
        microphoneDenied = ui.microphoneDenied,
        smootherAltitudeTitle = smootherAltitudeTitle(weather),
        smootherAltitudeIsHigher = weather.suggestedSmootherAltitude?.higher ?: true,
    )
}

fun FlightViewModel.atcActions(onRequestMicrophone: () -> Boolean) = AtcScreenActions(
    onCallsignChange = ::onCallsignChange,
    onCallsignCommit = ::onCallsignCommit,
    onDepartureGateChange = ::onDepartureGateChange,
    onArrivalGateChange = ::onArrivalGateChange,
    onGatesCommit = ::onGatesCommit,
    onSwapGates = ::onSwapGates,
    onTune = ::onTune,
    onTuneAtis = ::onTuneAtis,
    onPilotAction = ::onPilotAction,
    onAcknowledgement = ::onAcknowledgement,
    onReplay = ::onReplayLastCall,
    onSubscribe = ::onSubscribe,
    onContactAtcAboutWeather = ::onContactAtcAboutWeather,
    onPushToTalkStart = { if (onRequestMicrophone()) onPushToTalkStart() },
    onPushToTalkEnd = ::onPushToTalkEnd,
)

/**
 * The receipt line under the ATIS button: what the pilot will actually report to ATC.
 * Empty until they have tuned it — the app never claims the pilot has information it only
 * fetched in the background.
 */
private fun atisReceiptSummary(weather: WeatherSessionState, arrival: Boolean): String? {
    val reported = if (arrival) {
        weather.atisDiagnostics.reportedArrival
    } else {
        weather.atisDiagnostics.reportedDeparture
    } ?: return null
    return "Reporting information ${ATISPhraseology.phoneticLetter(reported)}"
}

private fun smootherAltitudeTitle(weather: WeatherSessionState): String? {
    val suggestion = weather.suggestedSmootherAltitude ?: return null
    val verb = if (suggestion.higher) "Climb" else "Descend"
    return "$verb ${formatAltitude(suggestion.altitudeFt)}"
}

private fun weatherBannerText(weather: WeatherSessionState): String? {
    if (weather.routeSigmets.isEmpty()) return null
    val hazard = weather.routeSigmets.first().hazard ?: "Advisory"
    return "$hazard along your route"
}

// endregion

// region Flight

fun FlightViewModel.flightModel(
    session: FlightSessionState,
    settings: AppSettings,
    ui: FlightViewModel.UiState,
) = FlightScreenModel(
    aircraftState = session.aircraftState,
    flightPlan = session.flightPlan,
    phase = session.phase,
    activeRunway = session.flightPlan.runway,
    distanceToDestination = session.aircraftState.nearestAirportDistanceNM
        ?.let { "${it.toInt()} NM" } ?: EM_DASH,
    nextWaypoint = session.flightPlan
        .nextWaypoint(session.aircraftState.coordinate)?.name ?: EM_DASH,
    airportProximity = session.aircraftState.nearestAirport ?: EM_DASH,
    cruiseAltitudeText = formatAltitude(session.flightPlan.cruiseAltitude),
    overrides = ui.overrides,
    mockMode = settings.mockMode,
)

fun FlightViewModel.flightActions() = FlightScreenActions(
    onOverrideChange = ::onOverridesChange,
    onApplyOverrides = ::onApplyOverrides,
    onClearOverrides = ::onClearOverrides,
    onRefreshFlightPlan = ::onRefreshFlightPlan,
    onOpenSimBrief = ::onOpenSimBrief,
)

// endregion

// region Weather

fun FlightViewModel.weatherModel(
    session: FlightSessionState,
    settings: AppSettings,
    weather: WeatherSessionState,
): WeatherScreenModel {
    return WeatherScreenModel(
        radarOverlay = weather.radarOverlay,
        radarOverlayEnabled = weather.radarOverlay.isEnabled,
        radarOpacity = settings.radarOpacity.toFloat(),
        showWeatherDataSourceLabels = settings.showWeatherDataSourceLabels,
        showWeatherCoverageWarnings = settings.showWeatherCoverageWarnings,
        lastRadarUpdatedText = formatClockTime(weather.radarOverlay.lastUpdatedMillis),
        weatherStatus = weather.status,
        departureIcao = session.flightPlan.departure,
        destinationIcao = session.flightPlan.destination,
        alternateIcao = session.flightPlan.alternate,
        departureMetar = weather.departureMetar,
        destinationMetar = weather.destinationMetar,
        alternateMetar = weather.alternateMetar,
        destinationTaf = weather.destinationTaf,
        rideAssessment = weather.rideAssessment,
        rideReports = weather.rideReportItems,
        routeSigmets = weather.routeSigmets,
        totalSigmetCount = weather.sigmets.size,
    )
}

fun FlightViewModel.weatherActions() = WeatherScreenActions(
    onToggleRadarOverlay = ::onToggleRadarOverlay,
    onRadarOpacityChange = ::onRadarOpacityChange,
    onRefresh = ::onRefreshWeather,
)

// endregion

// region Settings

fun FlightViewModel.settingsModel(
    session: FlightSessionState,
    settings: AppSettings,
    entitlements: EntitlementState,
    surface: SurfaceSessionState,
) = SettingsScreenModel(
    settings = settings,
    hasLiveAccess = session.hasLiveAccess,
    entitlementStatusText = entitlements.statusText,
    voices = availableVoices(),
    workingSectorText = session.centerSectorName,
    overpassEndpoint = AppConfig.Endpoints.OVERPASS_ENDPOINTS.firstOrNull().orEmpty(),
    surfaceCacheSummary = surface.cacheSummary,
)

fun FlightViewModel.settingsActions(
    onOpenSubscription: () -> Unit,
    onOpenPhraseologyProfiles: () -> Unit,
) = SettingsScreenActions(
    onSettingsChange = ::updateSettings,
    onOpenSubscription = onOpenSubscription,
    onOpenPhraseologyProfiles = onOpenPhraseologyProfiles,
    onPreviewVoice = ::onPreviewVoice,
    onRefreshAirportData = ::onRefreshAirportData,
    onClearAirportCache = ::onClearAirportCache,
    onResetAppData = ::onResetAppData,
    onOpenLink = ::onOpenLink,
)

// endregion

// region Subscription

fun FlightViewModel.subscriptionActions(onClose: () -> Unit) = SubscriptionScreenActions(
    onPurchase = ::onPurchase,
    onRestore = ::onRestorePurchases,
    // Android has no in-app "manage subscription" sheet: Play Store's own subscriptions
    // page is the sanctioned destination, and Apple's URL would be wrong here.
    onManage = { onOpenLink(AppConfig.Links.MANAGE_SUBSCRIPTIONS_URL); onClose() },
    onOpenTerms = { onOpenLink(AppConfig.Links.GOOGLE_PLAY_TERMS) },
    onOpenPrivacy = { onOpenLink(AppConfig.Links.PRIVACY_POLICY) },
)

// endregion

// region Phraseology profiles

fun FlightViewModel.phraseologyProfilesModel(ui: FlightViewModel.UiState): PhraseologyProfilesModel {
    val profiles = phraseologyProfilesState.value
    return PhraseologyProfilesModel(
        profiles = profiles.profiles,
        activeProfileId = profiles.activeProfileID,
        editing = ui.editingProfile,
        importFailed = ui.profileImportFailed,
    )
}

fun FlightViewModel.phraseologyProfilesActions(onCloseEditor: () -> Unit) = PhraseologyProfilesActions(
    onSelectActive = ::onSelectActiveProfile,
    onEdit = ::onEditProfile,
    onCloseEditor = onCloseEditor,
    onSaveDraft = ::onSaveProfileDraft,
    onDelete = ::onDeleteProfile,
    onCreateNew = ::onCreateProfile,
    onAddExample = ::onAddExampleProfile,
    onImportJson = ::onImportProfileJson,
    onShareJson = ::onShareProfile,
    onDismissImportError = ::onDismissProfileImportError,
)

// endregion

// region Diagnostics

fun FlightViewModel.diagnosticsModel(
    session: FlightSessionState,
    settings: AppSettings,
    weather: WeatherSessionState,
    ui: FlightViewModel.UiState,
    diagnosticsLog: List<DiagnosticRecord>,
    connect: IFConnectState,
    surface: SurfaceSessionState,
): DiagnosticsScreenModel {
    return DiagnosticsScreenModel(
        mockMode = settings.mockMode,
        mockRouteText = mockRouteText(),
        mockPhaseText = session.phase.title,
        simulateStaffedATC = session.simulateStaffedATC,
        liveATCSummary = session.liveATC.summary,
        phaseDebug = session.phaseDebug,
        atisSummary = atisDiagnosticsSummary(weather),
        weatherEndpointText = weather.status,
        weatherDiagnostics = weatherDiagnosticRows(weather),
        showSampledRadarCells = ui.showSampledRadarCells,
        discoveredStateCount = connect.manifestEntries.size,
        resolvedMappingCount = resolvedMappingCount(),
        // The manifest is the field's own vocabulary, and a name that does not appear is
        // the usual reason a reading is missing — so the list is shown verbatim rather than
        // summarised.
        discoveredStates = connect.manifestEntries.map { "${it.id}  ${it.name}" },
        lastRawMessage = connect.lastError ?: connect.liveFlightPlanRaw,
        surfaceDiagnostics = surfaceDiagnosticRows(),
        surfaceError = surface.lastError,
        log = diagnosticsLog,
    )
}

fun FlightViewModel.diagnosticsActions() = DiagnosticsScreenActions(
    onToggleMockMode = ::onToggleMockMode,
    onAdvanceMockPhase = ::onAdvanceMockPhase,
    onToggleSimulateStaffedATC = ::onToggleSimulateStaffedATC,
    onToggleSampledRadarCells = ::onToggleSampledRadarCells,
    onClearLog = ::onClearDiagnosticsLog,
    onExportSurfaceDiagnostics = ::onExportSurfaceDiagnostics,
)

private fun atisDiagnosticsSummary(weather: WeatherSessionState): String? {
    val d = weather.atisDiagnostics
    if (d.departureAirport.isEmpty() && d.arrivalAirport.isEmpty()) return null
    fun leg(airport: String, received: Boolean, letter: String?): String = when {
        airport.isEmpty() -> ""
        !received -> "$airport: none published"
        letter == null -> "$airport: received"
        else -> "$airport: information $letter"
    }
    return listOf(
        leg(d.departureAirport, d.departureReceived, d.departureLetter),
        leg(d.arrivalAirport, d.arrivalReceived, d.arrivalLetter),
    ).filter { it.isNotEmpty() }.joinToString(" · ")
}

private fun weatherDiagnosticRows(weather: WeatherSessionState): List<Pair<String, String>> = listOf(
    "METARs" to listOfNotNull(
        weather.departureMetar, weather.destinationMetar, weather.alternateMetar,
    ).size.toString(),
    "PIREPs" to weather.pireps.size.toString(),
    "SIGMETs" to "${weather.routeSigmets.size} on route / ${weather.sigmets.size} total",
    "Ride index" to "${(weather.rideAssessment.index * 100).toInt()}%",
    "Overlay layer" to weather.radarOverlay.layerLabel,
    "Overlay source" to weather.radarOverlay.sourceDescription,
    "Sampled cells" to weather.radarOverlay.sampledCells.size.toString(),
    "Mock cells" to weather.radarOverlay.mockCells.size.toString(),
)

// endregion

// region Formatting

internal const val EM_DASH = "—"

/** Display form of an altitude, matching the phraseology engine: "FL370" or "5,000". */
internal fun formatAltitude(feet: Int): String = when {
    feet <= 0 -> EM_DASH
    feet >= FLIGHT_LEVEL_FLOOR_FT -> "FL%03d".format(Locale.US, feet / 100)
    else -> "%,d".format(Locale.US, feet)
}

internal fun formatFrequency(mhz: Double): String =
    if (mhz <= 0) EM_DASH else "%.3f".format(Locale.US, mhz)

/** iOS renders the radar timestamp as a bare wall-clock time. */
internal fun formatClockTime(millis: Long?): String {
    if (millis == null) return EM_DASH
    return CLOCK_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

private val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

private const val FLIGHT_LEVEL_FLOOR_FT = 18000

// endregion

// region Maps

/**
 * The route map's model. Coordinates only — the layer draws, it does not resolve.
 *
 * PIREPs without a position and advisories without geometry are dropped here rather than
 * in the layer, because "we don't know where this is" is a data question, not a drawing
 * one, and a marker at 0,0 off West Africa is worse than no marker.
 */
fun FlightViewModel.routeMapModel(
    session: FlightSessionState,
    weather: WeatherSessionState,
    ui: FlightViewModel.UiState,
): RouteMapModel {
    val plan = session.flightPlan
    val routeFixes = plan.waypoints.mapNotNull { it.coordinate?.takeIf(Coordinate::isValid) }
    val departure = AirportDatabase.coordinate(plan.departure)
    val destination = AirportDatabase.coordinate(plan.destination)
    val route = buildList {
        departure?.let(::add)
        addAll(routeFixes)
        destination?.let(::add)
    }
    val nextWaypoint = plan.nextWaypoint(session.aircraftState.coordinate)
    return RouteMapModel(
        route = route,
        departure = departure,
        destination = destination,
        nextWaypoint = nextWaypoint?.coordinate?.takeIf(Coordinate::isValid),
        nextWaypointName = nextWaypoint?.name.orEmpty(),
        aircraft = session.aircraftState,
        pireps = weather.pireps.filter { it.coordinate?.isValid == true },
        routeSigmets = weather.routeSigmets.filter { it.area.size >= 3 },
        radarCells = weather.radarOverlay.mockCells,
        sampledCells = weather.radarOverlay.sampledCells,
    )
}

/**
 * The taxi map's model. Only the runways the route touches are included — see the note on
 * [TaxiMap] for why that restriction exists rather than drawing the whole field.
 */
fun FlightViewModel.taxiMapModel(session: FlightSessionState, surface: SurfaceSessionState): TaxiMapModel {
    val arriving = session.hasDeparted
    val model = if (arriving) surface.arrival else surface.departure ?: return TaxiMapModel()
    val plan = session.flightPlan
    val gate = if (arriving) plan.arrivalGate else plan.departureGate
    val aircraft = session.aircraftState
    return TaxiMapModel(
        relevantRunways = model?.runways.orEmpty().map { runway ->
            runway.centerline.map { Coordinate(it.latitude, it.longitude) }
        },
        isDeparture = !arriving,
        departureGate = standPosition(surface, arriving = false, gate = plan.departureGate),
        destination = standPosition(surface, arriving = true, gate = plan.arrivalGate),
        destinationLabel = gate,
        aircraft = aircraft.coordinate,
        aircraftHeadingDegrees = aircraft.trueHeading ?: aircraft.heading ?: 0.0,
    )
}

private fun standPosition(surface: SurfaceSessionState, arriving: Boolean, gate: String): Coordinate? {
    if (gate.isBlank()) return null
    val model = if (arriving) surface.arrival else surface.departure ?: return null
    return model?.parkingPositions
        ?.firstOrNull { it.matches(gate) }
        ?.coordinate
        ?.let { Coordinate(it.latitude, it.longitude) }
}

// endregion
