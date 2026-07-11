import XCTest
import CoreLocation
import MapKit
@testable import IFATCCompanion

/// Coverage, labeling, and attribution for the three precipitation providers
/// (NOAA radar, EUMETNET OPERA radar, NASA GIBS satellite estimate).
final class PrecipitationProviderMetadataTests: XCTestCase {

    func testNOAAProviderMetadata() {
        let p = NOAARadarPrecipitationProvider()
        XCTAssertTrue(p.supportsTrueRadar)
        XCTAssertEqual(p.layerType, .radar)
        XCTAssertEqual(p.uiLayerLabel, "Radar precipitation")
        XCTAssertNotNil(p.attributionText)
    }

    func testOPERAProviderMetadata() {
        let p = EUMETNETOPERARadarProvider()
        XCTAssertTrue(p.supportsTrueRadar)
        XCTAssertEqual(p.layerType, .radar)
        XCTAssertEqual(p.uiLayerLabel, "Radar precipitation")
        // CC BY 4.0 attribution is honored, and credits the CIRRUS composite.
        XCTAssertTrue(p.attributionText?.contains("CC BY 4.0") ?? false)
        XCTAssertTrue(p.attributionText?.contains("CIRRUS") ?? false)
        // Product preference order: max reflectivity → rain rate → 1h accumulation.
        XCTAssertEqual(EUMETNETOPERARadarProvider.preferredProducts.first, .maximumReflectivity)
        // Cloud-optimized GeoTIFF is preferred over HDF5.
        XCTAssertTrue(EUMETNETOPERARadarProvider.preferredFormats.first?.contains("geotiff") ?? false)
        XCTAssertTrue(EUMETNETOPERARadarProvider.preferredFormats.contains("odim-hdf5"))
    }

    func testOPERACanRenderReflectsAvailableSource() {
        let region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 48.85, longitude: 2.35),
                                        span: MKCoordinateSpan(latitudeDelta: 2, longitudeDelta: 2))
        // Default provider renders from the anonymous ORD composite.
        XCTAssertTrue(EUMETNETOPERARadarProvider().canRenderOverlay(for: region))
        // A configured WMS endpoint also counts as a renderable source.
        XCTAssertTrue(EUMETNETOPERARadarProvider(wmsBaseURL: "https://example.org/wms", useORD: false)
            .canRenderOverlay(for: region))
        // With neither ORD nor a WMS endpoint it can't render → must not claim coverage.
        XCTAssertFalse(EUMETNETOPERARadarProvider(useORD: false).canRenderOverlay(for: region))
        // Even with a source, it never claims to render outside its coverage box (U.S.).
        let kansas = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 39, longitude: -98),
                                        span: MKCoordinateSpan(latitudeDelta: 2, longitudeDelta: 2))
        XCTAssertFalse(EUMETNETOPERARadarProvider().canRenderOverlay(for: kansas))
    }

    func testOPERACoverageIsEuropeNotUS() {
        let paris = CLLocationCoordinate2D(latitude: 48.85, longitude: 2.35)
        XCTAssertTrue(EUMETNETOPERARadarProvider.covers(coordinate: paris))
        let kansas = CLLocationCoordinate2D(latitude: 39, longitude: -98)
        XCTAssertFalse(EUMETNETOPERARadarProvider.covers(coordinate: kansas),
                       "OPERA must not claim U.S. coverage")
    }

    func testOPERAHasNoSynchronousWMSURLWithoutEndpoint() {
        // The synchronous `AsyncImage` URL path is WMS-only. Without a configured WMS
        // endpoint it returns nil (the ORD composite is rendered asynchronously via
        // `exportImage` instead) — never a wrong or fabricated raster URL.
        let p = EUMETNETOPERARadarProvider()  // empty wmsBaseURL, ORD render path
        let bbox = RadarBoundingBox(minLatitude: 45, minLongitude: 0, maxLatitude: 50, maxLongitude: 8)
        XCTAssertNil(p.exportImageURL(for: bbox, size: CGSize(width: 400, height: 300), frame: nil))
    }

    func testNASAProviderIsSatelliteEstimateNeverRadar() {
        let p = NASAGIBSPrecipitationProvider()
        XCTAssertFalse(p.supportsTrueRadar, "NASA IMERG is a satellite estimate, not radar")
        XCTAssertEqual(p.layerType, .satelliteEstimate)
        XCTAssertEqual(p.uiLayerLabel, "Satellite precipitation estimate")
        XCTAssertFalse(p.uiLayerLabel.lowercased().contains("radar"))
        XCTAssertEqual(p.confidence, .low, "satellite estimate is lower confidence than radar")
        // Required NASA acknowledgement.
        XCTAssertTrue(p.attributionText?.contains("NASA Global Imagery Browse Services (GIBS)") ?? false)
        XCTAssertTrue(p.attributionText?.contains("GPM IMERG") ?? false)
    }

    func testNASACoverageIsNearGlobalNotPolar() {
        XCTAssertTrue(NASAGIBSPrecipitationProvider.coverageBox.contains(
            CLLocationCoordinate2D(latitude: 0, longitude: 0)))
        XCTAssertFalse(NASAGIBSPrecipitationProvider.coverageBox.contains(
            CLLocationCoordinate2D(latitude: 75, longitude: 100)),
            "IMERG does not cover the poles; the app never implies global radar")
    }

    func testNASAExportURLIsWellFormedAndKeyless() {
        let p = NASAGIBSPrecipitationProvider()
        let bbox = RadarBoundingBox(minLatitude: -5, minLongitude: -35, maxLatitude: 5, maxLongitude: -25)
        let url = p.exportImageURL(for: bbox, size: CGSize(width: 500, height: 500), frame: nil)
        let s = url?.absoluteString ?? ""
        XCTAssertTrue(s.contains("GetMap"))
        XCTAssertTrue(s.contains("IMERG_Precipitation_Rate"))
        XCTAssertFalse(s.lowercased().contains("apikey"))
        XCTAssertFalse(s.lowercased().contains("token"))
    }
}

/// Provider selection order (NOAA → OPERA → NASA → none).
@MainActor
final class PrecipitationProviderSelectionTests: XCTestCase {

    private func region(_ lat: Double, _ lon: Double) -> MKCoordinateRegion {
        MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: lat, longitude: lon),
                           span: MKCoordinateSpan(latitudeDelta: 2, longitudeDelta: 2))
    }

    func testSelectionOrder() {
        let service = PrecipitationOverlayService()

        // Inside NOAA coverage → NOAA.
        XCTAssertEqual(service.selectedProvider(for: region(40, -95))?.id, "noaa-nws-radar")
        // Europe (outside NOAA, inside OPERA) → OPERA.
        XCTAssertEqual(service.selectedProvider(for: region(48.85, 2.35))?.id, "eumetnet-opera-radar")
        // Elsewhere within ±60° → NASA satellite estimate.
        XCTAssertEqual(service.selectedProvider(for: region(0, -30))?.id, "nasa-gibs-imerg")
        // High latitude outside all coverage → none.
        XCTAssertNil(service.selectedProvider(for: region(75, 100)))
    }

    func testSelectedProviderLayerLabels() {
        let service = PrecipitationOverlayService()
        XCTAssertEqual(service.selectedProvider(for: region(40, -95))?.uiLayerLabel, "Radar precipitation")
        XCTAssertEqual(service.selectedProvider(for: region(48.85, 2.35))?.uiLayerLabel, "Radar precipitation")
        XCTAssertEqual(service.selectedProvider(for: region(0, -30))?.uiLayerLabel, "Satellite precipitation estimate")
    }

    func testMockModeSelectsMockProvider() {
        let service = PrecipitationOverlayService()
        service.useMockProvider(true)
        XCTAssertEqual(service.selectedProvider(for: region(48.85, 2.35))?.id, "mock-radar")
    }

    func testEuropeSelectsOPERAWhenItCanRender() {
        // Default OPERA renders from the anonymous ORD composite, so it wins in Europe.
        let service = PrecipitationOverlayService()
        XCTAssertEqual(service.selectedProvider(for: region(48.85, 2.35))?.id, "eumetnet-opera-radar")
    }

    func testEuropeFallsThroughToNASAWhenOPERACannotRender() {
        // An OPERA provider with no working source must not win selection and blank the
        // map while claiming coverage — selection falls through to the NASA estimate.
        let service = PrecipitationOverlayService(providers: [
            NOAARadarPrecipitationProvider(),
            EUMETNETOPERARadarProvider(useORD: false),   // no ORD, no WMS → can't render
            NASAGIBSPrecipitationProvider()
        ])
        XCTAssertEqual(service.selectedProvider(for: region(48.85, 2.35))?.id, "nasa-gibs-imerg")
        XCTAssertEqual(service.selectedProvider(for: region(48.85, 2.35))?.uiLayerLabel,
                       "Satellite precipitation estimate")
    }
}
