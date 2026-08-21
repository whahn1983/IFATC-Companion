package com.h3consultingpartners.ifatccompanion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.h3consultingpartners.ifatccompanion.AppGraph
import com.h3consultingpartners.ifatccompanion.ui.screens.AppShell
import com.h3consultingpartners.ifatccompanion.ui.screens.AppTab
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCCompanionTheme

/**
 * The app's single Activity.
 *
 * iOS hosts five tabs in a SwiftUI `TabView`; Android hosts the same five in one
 * Activity with a Compose navigation bar. A single Activity is the modern Android shape
 * and it matters here for a specific reason: the flight session must outlive any screen,
 * so it lives in [AppGraph] and the Activity only observes it.
 */
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // A denial is not fatal. The flight still runs; the live update simply is not
        // shown. Refusing to fly because a notification was declined would be worse.
        graph.diagnostics.log(
            com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory.SESSION,
            message = if (granted) {
                "Notification permission granted"
            } else {
                "Notification permission denied — the live flight update will not be shown"
            },
        )
    }

    private val requestMicrophonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModelRef?.onMicrophonePermissionResult(granted)
    }

    private val graph: AppGraph get() = AppGraph.require()

    private var viewModelRef: FlightViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: FlightViewModel = viewModel(factory = FlightViewModel.factory(graph))
            viewModelRef = viewModel

            IFATCCompanionTheme(darkTheme = isSystemInDarkTheme()) {
                var tab by remember { mutableStateOf(AppTab.ATC) }
                val session by viewModel.session.collectAsStateWithLifecycle()

                AppShell(
                    selectedTab = tab,
                    onSelectTab = { tab = it },
                    title = titleFor(tab),
                ) { modifier ->
                    AppNavHost(
                        tab = tab,
                        viewModel = viewModel,
                        session = session,
                        modifier = modifier,
                        onRequestMicrophone = ::ensureMicrophonePermission,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureNotificationPermission()
    }

    /**
     * Play Billing's purchase flow needs an Activity to show over, and it is the only
     * thing in the app that does. The graph holds this weakly and drops it on pause, so a
     * destroyed Activity is never kept alive by it.
     */
    override fun onResume() {
        super.onResume()
        graph.onActivityResumed(this)
    }

    override fun onPause() {
        graph.onActivityPaused(this)
        super.onPause()
    }

    /**
     * Ask for notifications once, when the app first comes to the foreground, so the
     * live flight update can be shown. Only on Android 13+, where the permission exists.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Ask for the microphone only when push-to-talk is actually used — never at launch.
     * The app has plenty to offer a pilot who never grants it.
     */
    fun ensureMicrophonePermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        return granted
    }

    private fun titleFor(tab: AppTab): String = when (tab) {
        // The ATC tab's title is the app's own, matching the iOS navigation title.
        AppTab.ATC -> "ATC Companion"
        else -> tab.title
    }
}
