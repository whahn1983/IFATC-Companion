import XCTest
@testable import IFATCCompanion

/// Exercises the app-side gating in `ReviewRequestManager`: it must only prompt at
/// eligible moments (enough completed flights, old enough install, spaced apart,
/// and within the yearly cap) — never on a brand-new install and never more often
/// than the product allows. The StoreKit three-per-year system limit sits on top of
/// this and is not exercised here.
@MainActor
final class ReviewRequestManagerTests: XCTestCase {

    /// A throwaway `UserDefaults` domain so tests never touch the real store or
    /// leak state between cases.
    private func makeDefaults(_ name: String = #function) -> UserDefaults {
        let suite = "ReviewRequestManagerTests.\(name)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    /// A mutable clock so age/interval gates can be tested deterministically.
    private final class Clock {
        var date: Date
        init(_ date: Date) { self.date = date }
        func advance(days: Double) { date = date.addingTimeInterval(days * 86_400) }
        var provider: () -> Date { { [unowned self] in self.date } }
    }

    private func makeManager(defaults: UserDefaults, clock: Clock) -> ReviewRequestManager {
        ReviewRequestManager(defaults: defaults, now: clock.provider)
    }

    /// Bring a manager to a fully eligible state: old enough install and enough
    /// completed flights.
    private func makeEligibleManager(defaults: UserDefaults, clock: Clock) -> ReviewRequestManager {
        let manager = makeManager(defaults: defaults, clock: clock)
        clock.advance(days: 30)                 // clear the install-age gate
        for _ in 0..<3 { manager.recordFlightCompleted() }  // clear the engagement gate
        return manager
    }

    func testDoesNotPromptOnFreshInstall() {
        let clock = Clock(Date(timeIntervalSince1970: 1_700_000_000))
        let manager = makeManager(defaults: makeDefaults(), clock: clock)
        // No flights, brand-new install.
        manager.requestReviewIfAppropriate(.beforeFirstCall)
        XCTAssertEqual(manager.promptToken, 0)
        XCTAssertFalse(manager.isEligible)
    }

    func testDoesNotPromptBeforeEnoughFlights() {
        let clock = Clock(Date(timeIntervalSince1970: 1_700_000_000))
        let manager = makeManager(defaults: makeDefaults(), clock: clock)
        clock.advance(days: 30)                 // install age satisfied
        manager.recordFlightCompleted()
        manager.recordFlightCompleted()          // only two — one short
        manager.requestReviewIfAppropriate(.afterFlightComplete)
        XCTAssertEqual(manager.promptToken, 0)
    }

    func testDoesNotPromptWhenInstallTooRecent() {
        let clock = Clock(Date(timeIntervalSince1970: 1_700_000_000))
        let manager = makeManager(defaults: makeDefaults(), clock: clock)
        for _ in 0..<5 { manager.recordFlightCompleted() }   // plenty engaged...
        // ...but the install is only a few hours old.
        clock.advance(days: 0.1)
        manager.requestReviewIfAppropriate(.afterFlightComplete)
        XCTAssertEqual(manager.promptToken, 0)
    }

    func testPromptsWhenEligible() {
        let clock = Clock(Date(timeIntervalSince1970: 1_700_000_000))
        let manager = makeEligibleManager(defaults: makeDefaults(), clock: clock)
        XCTAssertTrue(manager.isEligible)
        manager.requestReviewIfAppropriate(.afterFlightComplete)
        XCTAssertEqual(manager.promptToken, 1)
    }

    func testDoesNotPromptTwiceWithinInterval() {
        let clock = Clock(Date(timeIntervalSince1970: 1_700_000_000))
        let manager = makeEligibleManager(defaults: makeDefaults(), clock: clock)

        manager.requestReviewIfAppropriate(.afterFlightComplete)
        XCTAssertEqual(manager.promptToken, 1)

        // A month later a second eligible moment must be suppressed by the interval.
        clock.advance(days: 30)
        manager.recordFlightCompleted()
        manager.requestReviewIfAppropriate(.afterFlightComplete)
        XCTAssertEqual(manager.promptToken, 1)
    }

    func testPromptsAgainAfterInterval() {
        let clock = Clock(Date(timeIntervalSince1970: 1_700_000_000))
        let manager = makeEligibleManager(defaults: makeDefaults(), clock: clock)

        manager.requestReviewIfAppropriate(.afterFlightComplete)
        XCTAssertEqual(manager.promptToken, 1)

        // Well past the 120-day spacing — a new prompt is allowed.
        clock.advance(days: 130)
        manager.requestReviewIfAppropriate(.beforeFirstCall)
        XCTAssertEqual(manager.promptToken, 2)
    }

    func testYearlyCapNeverExceedsThree() {
        let clock = Clock(Date(timeIntervalSince1970: 1_700_000_000))
        let manager = makeEligibleManager(defaults: makeDefaults(), clock: clock)

        // Space eligible moments 121 days apart. Three prompts fit inside any
        // 365-day window; the fourth (another 121 days on, still within a year of the
        // first) must be suppressed by the yearly cap.
        manager.requestReviewIfAppropriate(.afterFlightComplete)   // day 0
        clock.advance(days: 121)
        manager.requestReviewIfAppropriate(.afterFlightComplete)   // day 121
        clock.advance(days: 121)
        manager.requestReviewIfAppropriate(.afterFlightComplete)   // day 242
        XCTAssertEqual(manager.promptToken, 3)

        clock.advance(days: 121)                                   // day 363
        manager.requestReviewIfAppropriate(.afterFlightComplete)
        XCTAssertEqual(manager.promptToken, 3, "must not exceed three prompts per rolling year")

        // Once the earliest prompt ages out of the 365-day window, a new one is allowed.
        clock.advance(days: 10)                                    // day 373
        manager.requestReviewIfAppropriate(.afterFlightComplete)
        XCTAssertEqual(manager.promptToken, 4)
    }

    func testStatePersistsAcrossManagerInstances() {
        let defaults = makeDefaults()
        let clock = Clock(Date(timeIntervalSince1970: 1_700_000_000))

        let first = makeEligibleManager(defaults: defaults, clock: clock)
        first.requestReviewIfAppropriate(.afterFlightComplete)
        XCTAssertEqual(first.promptToken, 1)

        // A brand-new manager over the same defaults (app relaunch) must remember the
        // completed flights, install date, and last-prompt time — so a moment inside
        // the interval is still suppressed.
        clock.advance(days: 20)
        let second = ReviewRequestManager(defaults: defaults, now: clock.provider)
        XCTAssertEqual(second.completedFlights, 3)
        second.requestReviewIfAppropriate(.afterFlightComplete)
        XCTAssertEqual(second.promptToken, 0, "interval carried over from the previous install session")
    }
}
