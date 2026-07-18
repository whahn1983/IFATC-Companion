import Foundation
import CoreLocation

/// A complete, hand-built airport surface for Mock Mode — no OpenStreetMap network
/// access required. It models a small field with named taxiways, one runway the taxi
/// route crosses, and a primary runway the departure route ends at (and the arrival
/// route exits from), plus mapped holding positions and a gate. The geometry is
/// synthetic (a demo scenario), laid out around a supplied reference so it renders near
/// the flight's field, and its primary runway / gate are labeled to match the active
/// flight so the demo stays coherent.
///
/// It is deliberately well-formed so it grades High confidence and exercises the full
/// automatic runway-crossing workflow offline.
enum MockAirportSurface {

    static let defaultRunwayIdent = "36"
    static let defaultGateName = "A1"

    /// Build the mock surface labeled with the given ICAO, primary runway, and gate,
    /// laid out around `reference`.
    static func model(icao: String, reference: CLLocationCoordinate2D,
                      primaryRunwayIdent: String, gate: String) -> AirportSurfaceModel {
        func g(_ dLat: Double, _ dLon: Double) -> GeoCoordinate {
            GeoCoordinate(latitude: reference.latitude + dLat, longitude: reference.longitude + dLon)
        }

        let primary = primaryRunwayIdent.isEmpty ? defaultRunwayIdent : primaryRunwayIdent.uppercased()
        let primaryRecip = reciprocal(primary)
        let crossing = crossingIdent(forPrimary: primary)
        let crossingRecip = reciprocal(crossing)
        let gateName = gate.trimmingCharacters(in: .whitespaces).isEmpty ? defaultGateName : gate

        // Crossing runway (east–west, through the reference latitude).
        let rwyCross = SurfaceRunway(
            osmID: "way/mock-rwy-cross",
            tags: ["aeroway": "runway", "ref": "\(crossing)/\(crossingRecip)", "surface": "asphalt"],
            idents: [crossing, crossingRecip],
            centerline: [g(0.0000, -0.0050), g(0.0000, 0.0050)],
            widthMeters: 45, widthInferred: false)

        // Primary runway (north–south, east side). The departure route ends holding
        // short of this runway; the arrival route exits from it.
        let rwyPrimary = SurfaceRunway(
            osmID: "way/mock-rwy-primary",
            tags: ["aeroway": "runway", "ref": "\(primary)/\(primaryRecip)", "surface": "asphalt"],
            idents: [primary, primaryRecip],
            centerline: [g(-0.0035, 0.0070), g(0.0025, 0.0070)],
            widthMeters: 45, widthInferred: false)

        let runways = [rwyCross, rwyPrimary]
        let runwayEnds = makeEnds(rwyCross) + makeEnds(rwyPrimary)

        // Taxiway A (north–south) from the gate area, crossing the crossing runway.
        let twyA = SurfaceTaxiway(
            osmID: "way/mock-twy-A",
            tags: ["aeroway": "taxiway", "ref": "A"],
            isTaxilane: false, name: "A",
            geometry: [g(0.0035, 0.0030), g(0.0000, 0.0030), g(-0.0032, 0.0030)],
            oneway: false, access: nil, widthMeters: nil)

        // Taxiway C (east–west, south) from taxiway A to the primary runway hold.
        let twyC = SurfaceTaxiway(
            osmID: "way/mock-twy-C",
            tags: ["aeroway": "taxiway", "ref": "C"],
            isTaxilane: false, name: "C",
            geometry: [g(-0.0032, 0.0030), g(-0.0032, 0.0062)],
            oneway: false, access: nil, widthMeters: nil)

        let taxiways = [twyA, twyC]

        // Mapped holding positions: one protecting the crossing, one at the primary runway.
        let holds = [
            SurfaceHoldingPosition(osmID: "node/mock-hold-cross",
                                   tags: ["aeroway": "holding_position", "ref": crossing],
                                   coordinate: g(0.0002, 0.0030), runwayRef: crossing, inferred: false),
            SurfaceHoldingPosition(osmID: "node/mock-hold-primary",
                                   tags: ["aeroway": "holding_position", "ref": primary],
                                   coordinate: g(-0.0032, 0.0062), runwayRef: primary, inferred: false)
        ]

        // Gate.
        let parking = [
            SurfaceParking(osmID: "node/mock-gate",
                           tags: ["aeroway": "gate", "ref": gateName],
                           kind: .gate, name: gateName, coordinate: g(0.0040, 0.0030))
        ]

        let bbox = OSMBoundingBox(center: reference, halfSpanDegrees: OSMSurface.bboxHalfSpanDegrees)
        let provenance = SurfaceProvenance(endpoint: "Bundled sample (offline mock)",
                                           fetchDate: Date(),
                                           boundingBox: bbox,
                                           rawElementCount: runways.count + taxiways.count + holds.count + parking.count)

        var model = AirportSurfaceModel(icao: icao.uppercased(),
                                        reference: GeoCoordinate(reference),
                                        runways: runways,
                                        runwayEnds: runwayEnds,
                                        taxiways: taxiways,
                                        holdingPositions: holds,
                                        parkingPositions: parking,
                                        aprons: [],
                                        source: provenance,
                                        confidence: .high)
        model.confidence = OSMSurfaceNormalizer.preliminaryConfidence(model)
        return model
    }

    /// Coordinate of the gate (departure taxi start).
    static func gateCoordinate(reference: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: reference.latitude + 0.0040, longitude: reference.longitude + 0.0030)
    }

    /// Coordinate of the primary-runway exit / arrival taxi start.
    static func runwayExitCoordinate(reference: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: reference.latitude - 0.0032, longitude: reference.longitude + 0.0062)
    }

    /// A crossing-runway ident chosen ~90° from the primary so it never collides with
    /// the primary's two ends.
    static func crossingIdent(forPrimary primary: String) -> String {
        let n = number(primary) ?? 18
        let cross = ((n + 9 - 1) % 36) + 1   // 1…36
        return String(format: "%02d", cross)
    }

    /// The reciprocal runway ident (e.g. "26L" → "08R").
    static func reciprocal(_ ident: String) -> String {
        let n = number(ident) ?? 18
        let r = ((n + 18 - 1) % 36) + 1
        let suffix = ident.uppercased().drop { $0.isNumber }
        let recipSuffix: String
        switch suffix {
        case "L": recipSuffix = "R"
        case "R": recipSuffix = "L"
        default: recipSuffix = String(suffix)
        }
        return String(format: "%02d", r) + recipSuffix
    }

    private static func number(_ ident: String) -> Int? {
        Int(ident.prefix { $0.isNumber })
    }

    private static func makeEnds(_ r: SurfaceRunway) -> [SurfaceRunwayEnd] {
        guard let first = r.centerline.first?.clLocation, let last = r.centerline.last?.clLocation else { return [] }
        var ends: [SurfaceRunwayEnd] = []
        for ident in r.idents {
            let heading = OSMSurfaceNormalizer.runwayHeading(ident) ?? Geo.bearing(from: first, to: last)
            let bFL = Geo.bearing(from: first, to: last)
            let bLF = Geo.bearing(from: last, to: first)
            let threshold: CLLocationCoordinate2D
            let opposite: CLLocationCoordinate2D
            if Geo.headingDifference(bFL, heading) <= Geo.headingDifference(bLF, heading) {
                threshold = first; opposite = last
            } else {
                threshold = last; opposite = first
            }
            ends.append(SurfaceRunwayEnd(ident: ident, threshold: GeoCoordinate(threshold),
                                         oppositeThreshold: GeoCoordinate(opposite),
                                         headingDegrees: heading, runwayOSMID: r.osmID,
                                         widthMeters: r.widthMeters))
        }
        return ends
    }
}
