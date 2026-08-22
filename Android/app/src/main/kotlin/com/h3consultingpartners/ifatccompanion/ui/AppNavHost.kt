package com.h3consultingpartners.ifatccompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionState
import com.h3consultingpartners.ifatccompanion.ui.map.RouteMap
import com.h3consultingpartners.ifatccompanion.ui.map.TaxiMap
import com.h3consultingpartners.ifatccompanion.ui.screens.AppTab
import com.h3consultingpartners.ifatccompanion.ui.screens.AtcDestination
import com.h3consultingpartners.ifatccompanion.ui.screens.AtcScreen
import com.h3consultingpartners.ifatccompanion.ui.screens.DiagnosticsScreen
import com.h3consultingpartners.ifatccompanion.ui.screens.FlightScreen
import com.h3consultingpartners.ifatccompanion.ui.screens.FlightsScreen
import com.h3consultingpartners.ifatccompanion.ui.screens.PhraseologyProfilesScreen
import com.h3consultingpartners.ifatccompanion.ui.screens.SettingsScreen
import com.h3consultingpartners.ifatccompanion.ui.screens.SubscriptionScreen
import com.h3consultingpartners.ifatccompanion.ui.screens.WeatherDeviationCard
import com.h3consultingpartners.ifatccompanion.ui.screens.WeatherScreen

/**
 * Which screen a tab is showing.
 *
 * Two tabs have a child screen rather than a single page — Settings can push the
 * subscription paywall or the phraseology-profile editor, and iOS presents both the same
 * way (a sheet and a `NavigationLink`). Rather than pull in a navigation library for two
 * destinations, the destination is state: [AppNavHost] owns it, the shell keeps rendering,
 * and system back pops it. Adding a third destination would be the moment to reach for
 * `androidx.navigation` instead.
 */
enum class SettingsDestination { ROOT, SUBSCRIPTION, PHRASEOLOGY_PROFILES }

/**
 * Routes the selected tab to its screen, and hands each one the slice of session state it
 * renders plus the actions it can perform.
 *
 * This is the seam between Compose and the rest of the app: every screen below here takes
 * a plain model and a bag of callbacks, and knows nothing about the ViewModel, the
 * coordinator, or Android. That is what lets `:uicheck` type-check all of them against
 * desktop Compose without an Android SDK — see settings-uicheck.gradle.kts.
 */
@Composable
fun AppNavHost(
    tab: AppTab,
    viewModel: FlightViewModel,
    session: FlightSessionState,
    modifier: Modifier = Modifier,
    onRequestMicrophone: () -> Boolean = { false },
    onSelectTab: (AppTab) -> Unit = {},
    /** Where the ATC tab is. Owned by the shell, which draws the top bar that must agree. */
    atcDestination: AtcDestination = AtcDestination.ROOT,
    onAtcDestination: (AtcDestination) -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val weather by viewModel.weatherState.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val diagnosticsLog by viewModel.diagnosticsLog.collectAsStateWithLifecycle()
    val connect by viewModel.connectState.collectAsStateWithLifecycle()
    val entitlements by viewModel.entitlements.collectAsStateWithLifecycle()
    val surface by viewModel.surfaceState.collectAsStateWithLifecycle()
    val profiles by viewModel.phraseologyProfilesState.collectAsStateWithLifecycle()
    val voices by viewModel.availableVoices.collectAsStateWithLifecycle()
    val routing by viewModel.surfaceRouting.collectAsStateWithLifecycle()
    val baseMap by viewModel.baseMap.collectAsStateWithLifecycle()
    val radarRaster by viewModel.radarRaster.collectAsStateWithLifecycle()
    val savedFlights by viewModel.savedFlights.collectAsStateWithLifecycle()
    val activeSavedFlightID by viewModel.activeSavedFlightID.collectAsStateWithLifecycle()
    val deviation by viewModel.weatherDeviation.collectAsStateWithLifecycle()
    var settingsDestination by rememberSaveable { mutableStateOf(SettingsDestination.ROOT) }

    // The subscribe banner on the ATC tab cannot navigate itself — this file owns the
    // destination — so it raises a request that is consumed here exactly once.
    LaunchedEffect(ui.subscriptionRequested) {
        if (!ui.subscriptionRequested) return@LaunchedEffect
        settingsDestination = SettingsDestination.SUBSCRIPTION
        onSelectTab(AppTab.SETTINGS)
        viewModel.onSubscriptionRequestHandled()
    }

    // [Back] System back pops the Settings sub-stack instead of leaving the app. Enabled
    // only while a sub-screen is showing, so back from a root tab behaves normally.
    BackHandler(enabled = tab == AppTab.SETTINGS && settingsDestination != SettingsDestination.ROOT) {
        if (ui.editingProfile != null) {
            viewModel.onCloseProfileEditor()
        } else {
            settingsDestination = SettingsDestination.ROOT
        }
    }

    // System back leaves the flights list before it leaves the app.
    BackHandler(enabled = tab == AppTab.ATC && atcDestination == AtcDestination.FLIGHTS) {
        onAtcDestination(AtcDestination.ROOT)
    }

    when (tab) {
        AppTab.ATC -> if (atcDestination == AtcDestination.FLIGHTS) {
            FlightsScreen(
                model = viewModel.flightsModel(
                    session = session,
                    settings = settings,
                    flights = savedFlights,
                    activeFlightID = activeSavedFlightID,
                    nowMillis = viewModel.nowMillis(),
                ),
                actions = viewModel.flightsActions(),
                modifier = modifier,
            )
        } else {
            AtcScreen(
            model = viewModel.atcModel(session, settings, weather, ui, deviation),
            actions = viewModel.atcActions(onRequestMicrophone),
            modifier = modifier,
            taxiMap = {
                TaxiMap(
                    model = viewModel.taxiMapModel(session, surface, routing),
                    // Both action lists come straight off the coordinator's state. The
                    // crossing one is what releases AWAITING_PILOT_READBACK, so without it
                    // a taxi that reaches a runway never continues.
                    actions = routing.crossingActions + routing.offRouteActions,
                    awaitingCrossingReadback = routing.awaitingCrossingReadback,
                    nextInstruction = routing.nextInstruction,
                    onAction = viewModel::onTaxiAction,
                    onCrossingReadback = viewModel::onCrossingReadback,
                )
            },
            weatherDeviationCard = {
                WeatherDeviationCard(
                    statusLine = deviation.statusLine,
                    actions = deviation.actions,
                    onAction = viewModel::onWeatherDeviationAction,
                )
            },
            )
        }

        AppTab.FLIGHT -> FlightScreen(
            model = viewModel.flightModel(session, settings, ui),
            actions = viewModel.flightActions(),
            modifier = modifier,
        )

        AppTab.WEATHER -> WeatherScreen(
            model = viewModel.weatherModel(session, settings, weather),
            actions = viewModel.weatherActions(),
            modifier = modifier,
            routeMap = {
                RouteMap(
                    model = viewModel.routeMapModel(session, weather, ui, radarRaster, deviation),
                    showSampledCells = ui.showSampledRadarCells,
                    // Coastlines and the graticule are always on; the imagery half is
                    // whatever has been fetched, which is nothing until it arrives and
                    // nothing at all with no connection.
                    baseMap = baseMap,
                    // What the pilot is looking at decides what precipitation is fetched,
                    // the way iOS tracks its visible region.
                    onRegionSettled = viewModel::onRouteMapRegionSettled,
                )
            },
        )

        AppTab.SETTINGS -> when (settingsDestination) {
            SettingsDestination.ROOT -> SettingsScreen(
                model = viewModel.settingsModel(session, settings, entitlements, surface, voices),
                actions = viewModel.settingsActions(
                    onOpenSubscription = { settingsDestination = SettingsDestination.SUBSCRIPTION },
                    onOpenPhraseologyProfiles = {
                        settingsDestination = SettingsDestination.PHRASEOLOGY_PROFILES
                    },
                ),
                modifier = modifier,
            )

            SettingsDestination.SUBSCRIPTION -> {
                // Entitlements load once per process, in Application.onCreate. If Play was
                // not bindable then, the product list is empty and every Buy button is
                // dead — and nothing else ever retries. Keyed on Unit so it runs once per
                // visit to the paywall, not once per recomposition.
                LaunchedEffect(Unit) { viewModel.onSubscriptionScreenShown() }
                SubscriptionScreen(
                    state = entitlements,
                    actions = viewModel.subscriptionActions(
                        onClose = { settingsDestination = SettingsDestination.ROOT },
                    ),
                    modifier = modifier,
                )
            }

            SettingsDestination.PHRASEOLOGY_PROFILES -> PhraseologyProfilesScreen(
                model = viewModel.phraseologyProfilesModel(ui, profiles),
                actions = viewModel.phraseologyProfilesActions(
                    onCloseEditor = {
                        // Back from the editor returns to the list; back from the list
                        // returns to Settings, which is the SwiftUI stack's behaviour.
                        if (ui.editingProfile != null) {
                            viewModel.onCloseProfileEditor()
                        } else {
                            settingsDestination = SettingsDestination.ROOT
                        }
                    },
                ),
                modifier = modifier,
            )
        }

        AppTab.DIAGNOSTICS -> DiagnosticsScreen(
            model = viewModel.diagnosticsModel(
                session, settings, weather, ui, diagnosticsLog, connect, surface,
            ),
            actions = viewModel.diagnosticsActions(),
            modifier = modifier,
        )
    }
}
