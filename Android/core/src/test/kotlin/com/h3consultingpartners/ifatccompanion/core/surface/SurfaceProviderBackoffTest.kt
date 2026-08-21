package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryFileStore
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.fail

/**
 * Overpass backoff is tracked **per airport**, not per provider.
 *
 * It used to be one counter for the whole provider: one field failing all its endpoints put
 * every *other* uncached airport into a 60–900 s backoff, so a slow monster on one end of the
 * flight (KLAX answers with ~540 stands plus every taxiway and terminal building in the box)
 * could deny the other end its data entirely — and anything reading the extract, including the
 * automatic gate assignment, silently got nothing.
 *
 * Ported from `IFATCCompanionTests/SurfaceProviderBackoffTests.swift`.
 */
class SurfaceProviderBackoffTest {

    private val ref = Coordinate(29.9844, -95.3414)

    /** An HTTP port that fails the test if it is ever reached — the provider must not call out. */
    private object NeverCalledHttp : HttpFetching {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSeconds: Long) =
            fail("an endpoint-less provider must never reach the network")

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult = fail("an endpoint-less provider must never reach the network")
    }

    /**
     * A provider that can never reach the network — no endpoints, an isolated empty cache — so
     * every fetch fails fast and deterministically without touching a real Overpass server.
     */
    private fun makeOfflineProvider(name: String): AirportSurfaceProvider =
        AirportSurfaceProvider(
            http = NeverCalledHttp,
            cache = AirportSurfaceCache(InMemoryFileStore(), namespace = name),
            endpoints = emptyList(),
        )

    private suspend fun readError(
        provider: AirportSurfaceProvider,
        icao: String,
    ): AirportSurfaceProvider.SurfaceError? = try {
        provider.surface(icao = icao, reference = ref)
        null
    } catch (error: AirportSurfaceProvider.SurfaceError) {
        error
    }

    @Test
    fun oneAirportsFailureDoesNotBackOffAnother() = runTest {
        val provider = makeOfflineProvider("test-backoff-per-airport-${UUID.randomUUID()}")

        // First read for KIAH is attempted and fails on its own merits.
        if (readError(provider, "KIAH") !is AirportSurfaceProvider.SurfaceError.Unreachable) {
            fail("an endpoint-less first read should report the airport unreachable")
        }
        // Read it again straight away: now KIAH is backing off, so it isn't even attempted.
        if (readError(provider, "KIAH") !is AirportSurfaceProvider.SurfaceError.Throttled) {
            fail("a repeat read of the failed airport should be throttled")
        }
        // The point of the change: a *different* airport is still attempted rather than
        // inheriting KIAH's backoff.
        if (readError(provider, "KMSP") !is AirportSurfaceProvider.SurfaceError.Unreachable) {
            fail("another airport must still be attempted, not throttled by KIAH")
        }
    }

    @Test
    fun deletingAnAirportsCacheLetsItBeAttemptedAgain() = runTest {
        val provider = makeOfflineProvider("test-backoff-delete-${UUID.randomUUID()}")

        if (readError(provider, "KIAH") !is AirportSurfaceProvider.SurfaceError.Unreachable) {
            fail("the first read fails")
        }
        if (readError(provider, "KIAH") !is AirportSurfaceProvider.SurfaceError.Throttled) {
            fail("the second is throttled")
        }
        // Deleting the cache is the pilot deliberately asking for a re-fetch, so it must not
        // keep serving a throttled error from a failure that predates the request.
        provider.deleteCache("KIAH")
        if (readError(provider, "KIAH") !is AirportSurfaceProvider.SurfaceError.Unreachable) {
            fail("after a cache delete the airport is attempted again, not throttled")
        }
    }
}
