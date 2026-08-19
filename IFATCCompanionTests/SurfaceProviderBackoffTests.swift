import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Overpass backoff is tracked **per airport**, not per provider.
///
/// It used to be one counter for the whole provider: one field failing all its endpoints put
/// every *other* uncached airport into a 60–900 s backoff, so a slow monster on one end of the
/// flight (KLAX answers with ~540 stands plus every taxiway and terminal building in the box)
/// could deny the other end its data entirely — and anything reading the extract, including the
/// automatic gate assignment, silently got nothing.
final class SurfaceProviderBackoffTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 29.9844, longitude: -95.3414)

    /// A provider that can never reach the network — no endpoints, an isolated empty cache — so
    /// every fetch fails fast and deterministically without touching a real Overpass server.
    private func makeOfflineProvider(_ name: String) -> AirportSurfaceProvider {
        AirportSurfaceProvider(cache: AirportSurfaceCache(directoryName: name), endpoints: [])
    }

    private func readError(_ provider: AirportSurfaceProvider,
                          _ icao: String) async -> AirportSurfaceProvider.SurfaceError? {
        do {
            _ = try await provider.surface(for: icao, reference: ref)
            return nil
        } catch let error as AirportSurfaceProvider.SurfaceError {
            return error
        } catch {
            return nil
        }
    }

    func testOneAirportsFailureDoesNotBackOffAnother() async {
        let provider = makeOfflineProvider("test-backoff-per-airport-\(UUID().uuidString)")

        // First read for KIAH is attempted and fails on its own merits.
        guard case .unreachable = await readError(provider, "KIAH") else {
            return XCTFail("an endpoint-less first read should report the airport unreachable")
        }
        // Read it again straight away: now KIAH is backing off, so it isn't even attempted.
        guard case .throttled = await readError(provider, "KIAH") else {
            return XCTFail("a repeat read of the failed airport should be throttled")
        }
        // The point of the change: a *different* airport is still attempted rather than
        // inheriting KIAH's backoff.
        guard case .unreachable = await readError(provider, "KMSP") else {
            return XCTFail("another airport must still be attempted, not throttled by KIAH")
        }
    }

    func testDeletingAnAirportsCacheLetsItBeAttemptedAgain() async {
        let provider = makeOfflineProvider("test-backoff-delete-\(UUID().uuidString)")

        guard case .unreachable = await readError(provider, "KIAH") else {
            return XCTFail("the first read fails")
        }
        guard case .throttled = await readError(provider, "KIAH") else {
            return XCTFail("the second is throttled")
        }
        // Deleting the cache is the pilot deliberately asking for a re-fetch, so it must not
        // keep serving a throttled error from a failure that predates the request.
        await provider.deleteCache(icao: "KIAH")
        guard case .unreachable = await readError(provider, "KIAH") else {
            return XCTFail("after a cache delete the airport is attempted again, not throttled")
        }
    }
}
