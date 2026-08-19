import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Overpass reports overload **with HTTP 200**: the body is its own HTML error page
/// ("the server is probably too busy…", "runtime error: Query timed out…") rather than the
/// JSON extract. Both large fields in one recent sample (KATL, EHAM) answered exactly that
/// way.
///
/// That page fails JSON decoding, and the fetch used to fall through to the "empty extract"
/// path on the strength of the 200 alone — telling the pilot there are *no airport surface
/// features for this area*, which sends them hunting a data problem at their airport when a
/// shared public server was simply busy.
final class OverpassBusyResponseTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 33.6407, longitude: -84.4277)

    /// The shape of the page overpass-api.de serves when it is shedding load.
    private static let busyPage = """
    <?xml version="1.0" encoding="UTF-8" ?>
    <html><head><title>OSM3S Response</title></head><body>
    <p>The data included in this document is from www.openstreetmap.org.</p>
    <p><strong style="color:#FF0000">Error</strong>: runtime error: open64: 0 Success /osm3s_v0.7.57_osm_base
    Dispatcher_Client::request_read_and_idx::rate_limited. The server is probably too busy to handle your request.</p>
    </body></html>
    """

    private static let timedOutPage = """
    <html><body><p><strong style="color:#FF0000">Error</strong>: runtime error:
    Query timed out in "query" at line 3 after 90 seconds.</p></body></html>
    """

    // MARK: - Recognizing the page

    func testABusyServerPageIsRecognizedAndNamed() {
        let page = OverpassErrorPage.detect(in: Data(Self.busyPage.utf8))
        XCTAssertEqual(page?.reason, "the server is too busy")
        XCTAssertTrue(page?.summary.contains("too busy") ?? false, "the log line carries the page text")
        XCTAssertFalse(page?.summary.contains("<") ?? true, "with its markup stripped")
    }

    func testAQueryTimeoutPageIsRecognizedSeparately() {
        XCTAssertEqual(OverpassErrorPage.detect(in: Data(Self.timedOutPage.utf8))?.reason,
                       "the query outran the server's time budget")
    }

    func testAnUnclassifiablePageIsStillAnErrorPage() {
        let page = OverpassErrorPage.detect(in: Data("<html><body>Bad Gateway</body></html>".utf8))
        XCTAssertNotNil(page, "any body that isn't JSON is the server talking, not airport data")
        XCTAssertNil(page?.reason, "but nothing worth naming to the pilot")
    }

    func testARealExtractIsNotMistakenForAnErrorPage() {
        XCTAssertNil(OverpassErrorPage.detect(in: Data("\n  {\"elements\":[]}".utf8)))
        XCTAssertNil(OverpassErrorPage.detect(in: Data("[]".utf8)))
    }

    // MARK: - What the pilot is told

    func testABusyServerIsReportedAsBusyAndNotAsAnEmptyAirport() async {
        let provider = makeProvider(serving: Data(Self.busyPage.utf8))
        guard case .serverBusy(let reason)? = await readError(provider, "KATL") else {
            return XCTFail("a busy Overpass server must not be reported as an empty extract")
        }
        XCTAssertEqual(reason, "the server is too busy")
        let message = AirportSurfaceProvider.SurfaceError.serverBusy(reason).errorDescription ?? ""
        XCTAssertTrue(message.contains("Overpass"), "the message names the server, not the airport")
        XCTAssertFalse(message.contains("no airport surface features"))
    }

    func testAnAirportThatGenuinelyHasNoFeaturesIsStillReportedAsEmpty() async {
        let provider = makeProvider(serving: Data("{\"version\":0.6,\"elements\":[]}".utf8))
        guard case .emptyExtract? = await readError(provider, "KATL") else {
            return XCTFail("a real empty answer from a working server is still an empty extract")
        }
    }

    func testARateLimitedRequestIsReportedAsBusyToo() async {
        let provider = makeProvider(serving: Data("Too Many Requests".utf8), status: 429)
        guard case .serverBusy? = await readError(provider, "KATL") else {
            return XCTFail("HTTP 429 is the same \"we are busy\" answer, said with a status code")
        }
    }

    // MARK: - Harness

    private func makeProvider(serving body: Data, status: Int = 200) -> AirportSurfaceProvider {
        StubOverpassProtocol.body = body
        StubOverpassProtocol.status = status
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubOverpassProtocol.self]
        return AirportSurfaceProvider(cache: AirportSurfaceCache(directoryName: "test-overpass-\(UUID().uuidString)"),
                                      endpoints: ["https://overpass.test/api/interpreter"],
                                      session: URLSession(configuration: config))
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
}

/// Serves one canned response to every request, so the provider's own behavior — not a
/// public Overpass server — is what the tests above measure.
private final class StubOverpassProtocol: URLProtocol {
    nonisolated(unsafe) static var body = Data()
    nonisolated(unsafe) static var status = 200

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let response = HTTPURLResponse(url: request.url!,
                                       statusCode: Self.status,
                                       httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "text/html"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.body)
        client?.urlProtocolDidFinishLoading(self)
    }
}
