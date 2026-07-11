import XCTest
@testable import IFATCCompanion

/// The pure HTTP-behavior helpers shared by the app's direct-to-public-service
/// clients (User-Agent, Retry-After parsing, exponential backoff, retryable status).
final class AppHTTPTests: XCTestCase {

    func testUserAgentIsDescriptiveWithContact() {
        let ua = AppHTTP.userAgent
        XCTAssertTrue(ua.hasPrefix("IFATCCompanion/"))
        XCTAssertTrue(ua.contains("github.com/whahn1983/IFATC-Companion"))
    }

    func testParseRetryAfterSeconds() {
        XCTAssertEqual(AppHTTP.parseRetryAfter("120"), 120)
        XCTAssertEqual(AppHTTP.parseRetryAfter("0"), 0)
        XCTAssertEqual(AppHTTP.parseRetryAfter("  30 "), 30)
        XCTAssertNil(AppHTTP.parseRetryAfter(nil))
        XCTAssertNil(AppHTTP.parseRetryAfter(""))
        XCTAssertNil(AppHTTP.parseRetryAfter("not-a-number-or-date"))
    }

    func testParseRetryAfterHTTPDate() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.timeZone = TimeZone(identifier: "GMT")
        fmt.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        // A date 90s in the future → ~90s delay.
        let future = fmt.string(from: now.addingTimeInterval(90))
        XCTAssertEqual(AppHTTP.parseRetryAfter(future, now: now) ?? -1, 90, accuracy: 1)
        // A past date clamps to 0 (never negative).
        let past = fmt.string(from: now.addingTimeInterval(-120))
        XCTAssertEqual(AppHTTP.parseRetryAfter(past, now: now) ?? -1, 0, accuracy: 1)
    }

    func testBackoffDelayIsExponentialAndCapped() {
        XCTAssertEqual(AppHTTP.backoffDelay(failureCount: 0), 0)
        XCTAssertEqual(AppHTTP.backoffDelay(failureCount: 1), 30)
        XCTAssertEqual(AppHTTP.backoffDelay(failureCount: 2), 60)
        XCTAssertEqual(AppHTTP.backoffDelay(failureCount: 3), 120)
        // Caps out and never overflows for very large counts.
        XCTAssertEqual(AppHTTP.backoffDelay(failureCount: 100), 900)
        // Custom base/cap.
        XCTAssertEqual(AppHTTP.backoffDelay(failureCount: 1, base: 15, cap: 600), 15)
        XCTAssertEqual(AppHTTP.backoffDelay(failureCount: 5, base: 15, cap: 600), 240)
        XCTAssertEqual(AppHTTP.backoffDelay(failureCount: 10, base: 15, cap: 600), 600)
    }

    func testRetryableStatus() {
        for code in [429, 502, 503, 504] { XCTAssertTrue(AppHTTP.isRetryableStatus(code)) }
        for code in [200, 204, 304, 400, 401, 403, 404] { XCTAssertFalse(AppHTTP.isRetryableStatus(code)) }
    }
}
