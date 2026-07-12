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
        // Europe: OPERA covers it but its ORD render is disabled in shipping builds,
        // so selection falls through to the NASA satellite estimate.
        XCTAssertEqual(service.selectedProvider(for: region(48.85, 2.35))?.id, "nasa-gibs-imerg")
        // Elsewhere within ±60° → NASA satellite estimate.
        XCTAssertEqual(service.selectedProvider(for: region(0, -30))?.id, "nasa-gibs-imerg")
        // High latitude outside all coverage → none.
        XCTAssertNil(service.selectedProvider(for: region(75, 100)))
    }

    func testSelectedProviderLayerLabels() {
        let service = PrecipitationOverlayService()
        XCTAssertEqual(service.selectedProvider(for: region(40, -95))?.uiLayerLabel, "Radar precipitation")
        // OPERA disabled → Europe shows the satellite estimate label, never "radar".
        XCTAssertEqual(service.selectedProvider(for: region(48.85, 2.35))?.uiLayerLabel,
                       "Satellite precipitation estimate")
        XCTAssertEqual(service.selectedProvider(for: region(0, -30))?.uiLayerLabel, "Satellite precipitation estimate")
    }

    func testMockModeSelectsMockProvider() {
        let service = PrecipitationOverlayService()
        service.useMockProvider(true)
        XCTAssertEqual(service.selectedProvider(for: region(48.85, 2.35))?.id, "mock-radar")
    }

    func testEuropeSelectsOPERAWhenExplicitlyEnabled() {
        // The selection logic still prefers OPERA over NASA in Europe when OPERA has a
        // working source — this guards the re-enable path (flip `useORD: true`, or wire
        // a WMS endpoint, and OPERA wins again). The shipping default keeps it disabled.
        let service = PrecipitationOverlayService(providers: [
            NOAARadarPrecipitationProvider(),
            EUMETNETOPERARadarProvider(useORD: true),   // explicitly enabled
            NASAGIBSPrecipitationProvider()
        ])
        XCTAssertEqual(service.selectedProvider(for: region(48.85, 2.35))?.id, "eumetnet-opera-radar")
    }

    func testShippingDefaultDisablesOPERAInEurope() {
        // Regression guard for the shipping decision: OPERA's ORD render is disabled by
        // default, so Europe resolves to the NASA satellite estimate, not OPERA.
        let service = PrecipitationOverlayService()
        XCTAssertEqual(service.selectedProvider(for: region(48.85, 2.35))?.id, "nasa-gibs-imerg")
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

/// The visible-region → overlay bounding box must register to what MapKit draws.
/// MapKit projects in Web Mercator (EPSG:3857) with `region.center` at the view's
/// centre, so the box's north/south edges have to be symmetric about the centre
/// *in Mercator*, not in raw degrees. Getting this wrong leaves the 3857 NASA
/// GIBS / OPERA WMS overlay off-centre by an amount that grows with the span — so
/// it appears to *move* (not just scale) as the map is zoomed.
final class RadarBoundingBoxMercatorTests: XCTestCase {

    /// Normalized (Earth-radius-free) Web-Mercator y, matching `RadarBoundingBox`.
    private func mercatorY(_ lat: Double) -> Double {
        let clamped = min(85.05112878, max(-85.05112878, lat))
        return log(tan(.pi / 4 + clamped * .pi / 180 / 2))
    }

    func testRegionBoxIsMercatorSymmetricAboutCenter() {
        // A mid-latitude region where Mercator's latitude non-linearity is pronounced.
        let center = CLLocationCoordinate2D(latitude: 55, longitude: 10)
        let region = MKCoordinateRegion(center: center,
                                        span: MKCoordinateSpan(latitudeDelta: 20, longitudeDelta: 20))
        let box = RadarBoundingBox(region: region)

        let yCenter = mercatorY(center.latitude)
        let northHalf = mercatorY(box.maxLatitude) - yCenter
        let southHalf = yCenter - mercatorY(box.minLatitude)
        // North and south Mercator half-spans match → the box is centred on the map centre.
        XCTAssertEqual(northHalf, southHalf, accuracy: 1e-9)
        // The degree edges are therefore *asymmetric* about the centre (that is correct):
        // the northern edge is nearer the centre in degrees than the southern one.
        XCTAssertLessThan(box.maxLatitude - center.latitude, center.latitude - box.minLatitude)
        // Longitude is linear in Mercator, so it stays a plain symmetric ± half-span.
        XCTAssertEqual(box.minLongitude, 0, accuracy: 1e-9)
        XCTAssertEqual(box.maxLongitude, 20, accuracy: 1e-9)
    }

    func testOverlayCentreStaysPinnedAcrossZoomLevels() {
        // Same centre, several zoom levels: a correctly-registered overlay keeps its
        // Mercator centre pinned to the map centre at every zoom — it scales, never moves.
        let center = CLLocationCoordinate2D(latitude: 45, longitude: -100)
        let yCenter = mercatorY(center.latitude)
        for delta in [1.0, 8.0, 30.0, 60.0] {
            let region = MKCoordinateRegion(center: center,
                span: MKCoordinateSpan(latitudeDelta: delta, longitudeDelta: delta))
            let box = RadarBoundingBox(region: region)
            let boxYCenter = (mercatorY(box.minLatitude) + mercatorY(box.maxLatitude)) / 2
            XCTAssertEqual(boxYCenter, yCenter, accuracy: 1e-9,
                           "overlay centre drifted from the map centre at zoom span \(delta)")
        }
    }

    func testMercatorBBoxStringCentresOnRegionCenter() {
        // End-to-end for the NASA/OPERA WMS request: the exported 3857 BBOX must be
        // vertically centred on the region centre so GIBS returns the on-screen extent.
        let center = CLLocationCoordinate2D(latitude: 30, longitude: 0)
        let region = MKCoordinateRegion(center: center,
            span: MKCoordinateSpan(latitudeDelta: 40, longitudeDelta: 40))
        let box = RadarBoundingBox(region: region)
        let parts = box.mercatorBBoxString.split(separator: ",").compactMap { Double($0) }
        XCTAssertEqual(parts.count, 4)
        let yMin = parts[1], yMax = parts[3]
        // `mercatorBBoxString` applies the Earth radius; fold it in for the comparison.
        let yCenterMeters = 6_378_137.0 * mercatorY(center.latitude)
        XCTAssertEqual((yMin + yMax) / 2, yCenterMeters, accuracy: 1e-3)
    }
}
