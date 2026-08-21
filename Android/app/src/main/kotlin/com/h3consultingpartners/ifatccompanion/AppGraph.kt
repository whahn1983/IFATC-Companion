package com.h3consultingpartners.ifatccompanion

import android.content.Context
import com.h3consultingpartners.ifatccompanion.core.diagnostics.DiagnosticsStore
import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.OkHttpFetcher
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.FileStore
import com.h3consultingpartners.ifatccompanion.data.AndroidFileStore
import com.h3consultingpartners.ifatccompanion.data.DataStoreKeyValueStore
import com.h3consultingpartners.ifatccompanion.service.ActiveFlightController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * The application's object graph, wired by hand.
 *
 * A dependency-injection framework would earn its keep in a multi-team app with many
 * build variants; here it would add a compiler plugin, build time and indirection to
 * assemble one long-lived graph with no variants. Constructing it explicitly keeps the
 * wiring readable and, more usefully, keeps every engine's dependencies visible as
 * constructor parameters — which is what makes them testable on a plain JVM.
 *
 * Held as a process singleton because the flight session must outlive any Activity: the
 * foreground service, the notification actions and the UI all address the same session.
 */
class AppGraph private constructor(
    private val context: Context,
) {

    /** Survives configuration changes and Activity death; cancelled only with the process. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val clock: Clock = Clock.system

    val diagnostics: DiagnosticsStore = DiagnosticsStore(clock)

    val settingsStore: DataStoreKeyValueStore =
        DataStoreKeyValueStore.settings(context, applicationScope)

    val entitlementStore: DataStoreKeyValueStore =
        DataStoreKeyValueStore.entitlement(context, applicationScope)

    val fileStore: FileStore = AndroidFileStore(File(context.filesDir, "ifatc"))

    val http: HttpFetching = OkHttpFetcher(
        cacheDirectory = File(context.cacheDir, AppHttp.DEFAULT_CACHE_NAME),
    )

    /**
     * The live flight session. Set once the session holder is constructed; the
     * foreground service reads it through [AppGraph.instanceOrNull] so it can address
     * the session without an Activity.
     */
    @Volatile
    var activeFlightController: ActiveFlightController? = null

    /** Load persisted settings into memory before the first screen reads one. */
    suspend fun warmUp() {
        settingsStore.load()
        entitlementStore.load()
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun create(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }

        /** The graph, or null before [create] has run (a receiver woken with no process). */
        fun instanceOrNull(): AppGraph? = instance

        fun require(): AppGraph =
            instance ?: error("AppGraph accessed before Application.onCreate")
    }
}
