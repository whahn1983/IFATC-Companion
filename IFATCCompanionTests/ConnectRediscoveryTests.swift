import XCTest
@testable import IFATCCompanion

/// The stored Host/IP is a starting point, not a fact: the iPad's address moves with
/// the Wi-Fi it joins, so an address auto-discovery wrote on one network points at
/// nothing on the next. These cover the rule that decides when that address is
/// re-searched and replaced — and, just as importantly, when it is left alone.
@MainActor
final class ConnectRediscoveryTests: XCTestCase {

    // MARK: - Which failures mean "wrong address"

    /// Nothing answered at the address. These are the failures that justify searching
    /// the network for Infinite Flight's current address.
    func testUnreachableFailuresTriggerRediscovery() {
        XCTAssertTrue(IFConnectManager.isUnreachable(IFConnectError.timeout))
        XCTAssertTrue(IFConnectManager.isUnreachable(IFConnectError.connectionFailed("Connection refused")))
        XCTAssertTrue(IFConnectManager.isUnreachable(IFConnectError.invalidHost))
        XCTAssertTrue(IFConnectManager.isUnreachable(IFConnectError.notConnected))
    }

    /// Infinite Flight *did* answer — it just answered badly. The address is right, so
    /// searching for another one would only find the same device again; the existing
    /// retry-the-handshake path is what fixes these.
    func testAnsweredButFaultyFailuresDoNotTriggerRediscovery() {
        XCTAssertFalse(IFConnectManager.isUnreachable(IFConnectError.manifestUnavailable))
        XCTAssertFalse(IFConnectManager.isUnreachable(IFConnectError.decodingFailed))
        XCTAssertFalse(IFConnectManager.isUnreachable(IFConnectError.unknownState))
        XCTAssertFalse(IFConnectManager.isUnreachable(IFConnectError.cancelled))
        XCTAssertFalse(IFConnectManager.isUnreachable(URLError(.notConnectedToInternet)))
    }

    // MARK: - The fallback itself

    /// An address nothing answers at sends the link into a fresh search rather than
    /// straight to a failure the pilot has to clear by hand.
    func testUnreachableHostFallsBackToSearching() async {
        let manager = IFConnectManager()
        manager.connectMaxAttempts = 1
        // Keep the search itself short — the test only cares that one starts.
        manager.discoveryTimeout = 1
        defer { manager.stopAutoDiscover() }

        // An empty host fails as `.invalidHost` immediately — the same "nothing there"
        // class as a stale IP, without a six-second socket timeout in the test.
        manager.connect(host: "", port: 10112, rediscoverOnFailure: true)

        let searching = await eventually { if case .discovering = manager.connectionState { return true }; return false }
        XCTAssertTrue(searching, "an unreachable address must start a new search, not just fail")
    }

    /// Without the fallback the same failure is surfaced as-is — a manually entered
    /// address is the pilot's own and is never second-guessed or overwritten.
    func testUnreachableHostWithoutFallbackFails() async {
        let manager = IFConnectManager()
        manager.connectMaxAttempts = 1

        manager.connect(host: "", port: 10112)

        let failed = await eventually { if case .failed = manager.connectionState { return true }; return false }
        XCTAssertTrue(failed, "with rediscovery off the connect failure must stand")
    }

    /// Poll `condition` until it holds or the timeout expires.
    private func eventually(timeout: TimeInterval = 5, _ condition: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return condition()
    }
}
