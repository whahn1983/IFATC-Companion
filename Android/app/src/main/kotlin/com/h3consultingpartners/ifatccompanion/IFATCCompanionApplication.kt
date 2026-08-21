package com.h3consultingpartners.ifatccompanion

import android.app.Application
import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import com.h3consultingpartners.ifatccompanion.notification.FlightNotifications
import kotlinx.coroutines.runBlocking

class IFATCCompanionApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()

        // The User-Agent the app presents to every public service it uses (NOAA, NASA,
        // the D-ATIS mirror, Overpass) carries the app version and a contact URL, because
        // those operators ask clients to identify themselves.
        AppHttp.appVersion = BuildConfig.VERSION_NAME

        graph = AppGraph.create(this)

        // Settings are read synchronously all through the engine, the way UserDefaults is
        // on iOS, so the one-time load from DataStore has to finish before anything reads
        // one. It is a single small file read at process start, off the critical path of
        // anything the user can see, and blocking here is what keeps every downstream
        // read simple.
        runBlocking { graph.warmUp() }

        FlightNotifications.createChannels(this)
    }
}
