package com.h3consultingpartners.ifatccompanion.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The five tabs, in the iOS order: ATC, Flight, Weather, Settings, Diagnostics.
 *
 * The information architecture is carried across unchanged from
 * `IFATCCompanion/Views/ContentView.swift` — same tabs, same titles, same order. What
 * changes is the furniture: a Material 3 `NavigationBar` rather than a UIKit tab bar,
 * because that is what an Android user's thumb expects to find and how the system
 * handles insets, predictive back and large-screen layouts.
 */
enum class AppTab(val title: String, val icon: ImageVector) {
    ATC("ATC", Icons.Filled.Sensors),
    FLIGHT("Flight", Icons.Filled.AirplanemodeActive),
    WEATHER("Weather", Icons.Filled.Cloud),
    SETTINGS("Settings", Icons.Filled.Settings),
    DIAGNOSTICS("Diagnostics", Icons.Filled.MonitorHeart),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    topBarActions: @Composable () -> Unit = {},
    /**
     * The top-left control. Empty on a tab root; a back arrow on a screen pushed over one.
     *
     * Android's system back gesture handles this too, but a visible affordance is what tells
     * the pilot they are one level down rather than on a tab that has changed shape.
     */
    navigationIcon: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { navigationIcon() },
                actions = { topBarActions() },
            )
        },
        bottomBar = {
            NavigationBar {
                for (tab in AppTab.entries) {
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { onSelectTab(tab) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            content(Modifier.fillMaxSize())
        }
    }
}

/**
 * Where the ATC tab is: its own screen, or the saved-flights list pushed over it.
 *
 * iOS pushes `FlightsListView` onto the ATC navigation stack, so the flights live under ATC
 * rather than in a tab of their own. Kept the same here: a pilot looking for their flights
 * should find them where they found them on the other device.
 */
enum class AtcDestination { ROOT, FLIGHTS }
