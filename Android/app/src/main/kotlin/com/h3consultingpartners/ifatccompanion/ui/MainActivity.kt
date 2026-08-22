package com.h3consultingpartners.ifatccompanion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.h3consultingpartners.ifatccompanion.AppGraph
import com.h3consultingpartners.ifatccompanion.R
import com.h3consultingpartners.ifatccompanion.ui.screens.AppShell
import com.h3consultingpartners.ifatccompanion.ui.screens.AppTab
import com.h3consultingpartners.ifatccompanion.ui.screens.AtcDestination
import com.h3consultingpartners.ifatccompanion.ui.screens.ClearFlightConfirmation
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCCompanionTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

    /**
     * Permission rationales, held on the Activity rather than inside the composition
     * because [ensureMicrophonePermission] is called from a plain callback handed down to
     * the screens, not from a composable.
     */
    private var explainNotifications by mutableStateOf(false)
    private var explainMicrophone by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep the screen on while the app is open, when the pilot asks for it. The setting
        // defaults on precisely because Infinite Flight drops the Connect link when the
        // companion device locks — and the toggle shipped in Settings with no reader at all.
        lifecycleScope.launch {
            graph.settingsRepository.state
                .map { it.keepScreenAwake }
                .distinctUntilChanged()
                .collect { awake ->
                    if (awake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
        }

        setContent {
            val viewModel: FlightViewModel = viewModel(factory = FlightViewModel.factory(graph))
            viewModelRef = viewModel

            IFATCCompanionTheme(darkTheme = isSystemInDarkTheme()) {
                var tab by remember { mutableStateOf(AppTab.ATC) }
                val session by viewModel.session.collectAsStateWithLifecycle()

                if (explainNotifications) {
                    AlertDialog(
                        onDismissRequest = {
                            // Dismissing is a refusal, and is remembered as one. Re-asking
                            // on the next launch is how an app spends its second and last
                            // prompt on someone who has already said no.
                            explainNotifications = false
                            declineNotifications()
                        },
                        title = { Text(stringResource(R.string.permission_notifications_title)) },
                        text = { Text(stringResource(R.string.permission_notifications_rationale)) },
                        confirmButton = {
                            TextButton(onClick = {
                                explainNotifications = false
                                askForNotifications()
                            }) { Text(stringResource(R.string.permission_continue)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                explainNotifications = false
                                declineNotifications()
                            }) { Text(stringResource(R.string.permission_not_now)) }
                        },
                    )
                }

                if (explainMicrophone) {
                    AlertDialog(
                        onDismissRequest = { explainMicrophone = false },
                        title = { Text(stringResource(R.string.permission_microphone_title)) },
                        text = { Text(stringResource(R.string.permission_microphone_rationale)) },
                        confirmButton = {
                            TextButton(onClick = {
                                explainMicrophone = false
                                requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }) { Text(stringResource(R.string.permission_continue)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { explainMicrophone = false }) {
                                Text(stringResource(R.string.permission_not_now))
                            }
                        },
                    )
                }

                // Where the ATC tab is. Hoisted here rather than kept inside AppNavHost
                // because the top bar is drawn by the shell *around* the nav host, and the
                // Flights entry, the back arrow and the title all have to agree with it.
                var atcDestination by rememberSaveable { mutableStateOf(AtcDestination.ROOT) }
                var confirmClearFlight by rememberSaveable { mutableStateOf(false) }
                val onAtc = tab == AppTab.ATC

                if (confirmClearFlight) {
                    ClearFlightConfirmation(
                        hasUnsavedFlight = session.hasUnsavedFlight,
                        canSaveCurrentFlight = session.canSaveCurrentFlight,
                        retiredName = session.savedFlightRetiredByClearing,
                        onSaveAndClear = {
                            viewModel.onSaveCurrentFlight()
                            viewModel.onStartNewFlight()
                            confirmClearFlight = false
                        },
                        onClear = {
                            viewModel.onStartNewFlight()
                            confirmClearFlight = false
                        },
                        onDismiss = { confirmClearFlight = false },
                    )
                }

                AppShell(
                    selectedTab = tab,
                    onSelectTab = {
                        // Leaving the tab closes what was pushed over it, so coming back
                        // lands on ATC rather than on a list the pilot had finished with.
                        if (it != AppTab.ATC) atcDestination = AtcDestination.ROOT
                        tab = it
                    },
                    title = if (onAtc && atcDestination == AtcDestination.FLIGHTS) {
                        "Flights"
                    } else {
                        titleFor(tab)
                    },
                    navigationIcon = {
                        if (onAtc && atcDestination == AtcDestination.FLIGHTS) {
                            IconButton(onClick = { atcDestination = AtcDestination.ROOT }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    topBarActions = {
                        if (onAtc && atcDestination == AtcDestination.ROOT) {
                            IconButton(onClick = { confirmClearFlight = true }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Clear Flight")
                            }
                            IconButton(onClick = { atcDestination = AtcDestination.FLIGHTS }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Flights")
                            }
                        }
                    },
                ) { modifier ->
                    AppNavHost(
                        tab = tab,
                        viewModel = viewModel,
                        session = session,
                        modifier = modifier,
                        onRequestMicrophone = ::ensureMicrophonePermission,
                        onSelectTab = { tab = it },
                        atcDestination = atcDestination,
                        onAtcDestination = { atcDestination = it },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Raises the explanation, never the system prompt itself.
        //
        // onStart runs on every return to the foreground — back from a SimBrief link, a
        // legal link, Manage subscription, the share sheet. This used to launch the
        // permission request from here, and Android denies a permission permanently after
        // the second refusal, so both of the pilot's chances were spent on resumes that
        // had nothing to do with notifications; after that the Live Flight Update could
        // never be shown again. shouldExplainNotifications() consults a persisted record,
        // so the ask now happens at most once per install and the pilot is told what it is
        // for before the system asks.
        if (shouldExplainNotifications()) explainNotifications = true
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
     * Whether to put the notification rationale on screen: only on Android 13+, only when
     * the permission is not already granted, and only if we have never asked before.
     *
     * Android permanently denies a runtime permission after the second refusal, so the app
     * gets exactly one good attempt. Spending it silently, before the pilot has any idea
     * what the Live Flight Update is, is how that attempt gets wasted — so the explanation
     * comes first and the system prompt only follows if they agree to it.
     */
    private fun shouldExplainNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return false
        return graph.settingsStore.getBoolean(KEY_ASKED_FOR_NOTIFICATIONS) != true
    }

    /** Record the ask and show the system prompt. Called only from the rationale dialog. */
    private fun askForNotifications() {
        graph.settingsStore.putBoolean(KEY_ASKED_FOR_NOTIFICATIONS, true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Remember the refusal too, so declining the explanation is not re-asked either. */
    private fun declineNotifications() {
        graph.settingsStore.putBoolean(KEY_ASKED_FOR_NOTIFICATIONS, true)
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
        if (granted) return true

        // The first ask needs no preamble: the pilot has just pressed the push-to-talk
        // button, so what it is for could not be clearer. shouldShowRequestPermissionRationale
        // becomes true only after a refusal — and the next refusal is final, so that is
        // exactly when to say what the microphone is for and that nothing is uploaded.
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            explainMicrophone = true
        } else {
            requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        return false
    }

    companion object {
        /**
         * Persisted, not in-memory: the whole point is that the ask survives a relaunch.
         * Kept in the settings store rather than a fresh DataStore because it is a
         * user-facing preference in everything but name.
         */
        private const val KEY_ASKED_FOR_NOTIFICATIONS = "askedForNotificationPermission"
    }

    private fun titleFor(tab: AppTab): String = when (tab) {
        // The ATC tab's title is the app's own, matching the iOS navigation title.
        AppTab.ATC -> "ATC Companion"
        else -> tab.title
    }
}
