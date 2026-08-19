import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Stands a field maps **on the concourse itself**, and the same stand mapped twice.
///
/// KIAD tags both: `gate` C24 is a node that is literally a *vertex of the Concourse C/D
/// outline* (`way/43194367`), while `parking_position` C24 is the aircraft stand 75 m south
/// of it on the apron. Nine of the C-row gate nodes are members of that way. Two things went
/// wrong there. Whichever feature the extract listed first became the taxi target, so a route
/// to C24 could end at a point inside the terminal; and from a stand sitting *on* a footprint
/// every candidate lead-in touches that footprint, so the flat concourse-crossing penalty
/// applied to all of them equally and the attachment fell back to nearest-overall — which
/// across a 33 m concourse is as easily the far side as the stand's own.
final class StandOnConcourseTests: XCTestCase {

    // Real KIAD geometry, so the numbers here are the ones the field actually has.
    private let gateOnOutline = CLLocationCoordinate2D(latitude: 38.9452671, longitude: -77.4464376)
    private let standOnApron = CLLocationCoordinate2D(latitude: 38.9445917, longitude: -77.4465230)
    private let southWall = 38.9452671   // the even-numbered gate nodes sit on it
    private let northWall = 38.9455500
    private let lonWest = -77.4480, lonEast = -77.4450

    private func c(_ lat: Double, _ lon: Double) -> GeoCoordinate {
        GeoCoordinate(latitude: lat, longitude: lon)
    }

    private func concourse() -> SurfaceBuilding {
        SurfaceBuilding(osmID: "way/43194367",
                        tags: ["building": "airport_terminal", "aeroway": "terminal",
                               "name": "Concourses C & D"],
                        polygon: [c(southWall, lonWest), c(northWall, lonWest),
                                  c(northWall, lonEast), c(southWall, lonEast)])
    }

    /// An apron taxilane running E–W at `lat`, noded every ~17 m.
    private func lane(_ osmID: String, lat: Double) -> SurfaceTaxiway {
        SurfaceTaxiway(osmID: osmID, tags: ["aeroway": "taxilane"], isTaxilane: true, name: "",
                       geometry: (0..<15).map { c(lat, lonWest + 0.0002 * Double($0)) },
                       oneway: false, access: nil, widthMeters: nil)
    }

    private func runway() -> SurfaceRunway {
        SurfaceRunway(osmID: "way/rwy", tags: ["aeroway": "runway", "ref": "01C/19C"],
                      idents: ["01C", "19C"],
                      centerline: [c(38.9200, -77.4600), c(38.9300, -77.4600)],
                      widthMeters: 45, widthInferred: false)
    }

    private func field(stands: [SurfaceParking], northLaneLatitude: Double = 38.9465) -> AirportSurfaceModel {
        let r = runway()
        let bbox = OSMBoundingBox(center: gateOnOutline, halfSpanDegrees: 0.04)
        return AirportSurfaceModel(icao: "KIAD", reference: GeoCoordinate(gateOnOutline),
                                   runways: [r], runwayEnds: makeEnds(r),
                                   taxiways: [lane("way/north", lat: northLaneLatitude),
                                              lane("way/south", lat: 38.9441500)],
                                   holdingPositions: [], parkingPositions: stands, aprons: [],
                                   buildings: [concourse()],
                                   source: SurfaceProvenance(endpoint: "t", fetchDate: Date(),
                                                             boundingBox: bbox, rawElementCount: 5),
                                   confidence: .low)
    }

    private func gateC24() -> SurfaceParking {
        SurfaceParking(osmID: "node/3413155764", tags: ["aeroway": "gate", "ref": "C24"],
                       kind: .gate, name: "C24", coordinate: GeoCoordinate(gateOnOutline))
    }

    private func standC24() -> SurfaceParking {
        SurfaceParking(osmID: "way/1008778924", tags: ["aeroway": "parking_position", "ref": "C24"],
                       kind: .parkingPosition, name: "C24", coordinate: GeoCoordinate(standOnApron))
    }

    private func standNodes(_ graph: SurfaceGraph, named name: String) -> [SurfaceNode] {
        graph.nodes.filter { ($0.kind == .gate || $0.kind == .parking) && $0.name == name }
    }

    private func connector(_ graph: SurfaceGraph, from stand: SurfaceNode) -> (edge: SurfaceEdge, other: SurfaceNode)? {
        guard let e = graph.edges.first(where: {
            $0.inferred && ($0.from == stand.id || $0.to == stand.id) }) else { return nil }
        return (e, graph.nodes[e.from == stand.id ? e.to : e.from])
    }

    // MARK: - One stand mapped twice

    func testTheParkingPositionIsTheStandNodeNotTheGateOnTheConcourse() {
        // Gate listed first, as an extract sorted nodes-before-ways delivers it.
        let graph = SurfaceGraphBuilder.build(from: field(stands: [gateC24(), standC24()]))
        let nodes = standNodes(graph, named: "C24")
        XCTAssertEqual(nodes.count, 1, "one physical stand contributes one routable node")
        guard let node = nodes.first else { return XCTFail("expected a C24 stand node") }
        XCTAssertEqual(node.kind, .parking, "the aircraft parks on the parking_position")
        XCTAssertEqual(node.osmID, "way/1008778924")
        XCTAssertEqual(node.coordinate.latitude, standOnApron.latitude, accuracy: 1e-6,
                       "the route must end on the apron, not on the terminal outline")
    }

    func testTheOrderTheExtractListsThemInDoesNotDecide() {
        let gateFirst = SurfaceGraphBuilder.build(from: field(stands: [gateC24(), standC24()]))
        let standFirst = SurfaceGraphBuilder.build(from: field(stands: [standC24(), gateC24()]))
        XCTAssertEqual(standNodes(gateFirst, named: "C24").first?.osmID,
                       standNodes(standFirst, named: "C24").first?.osmID,
                       "whichever way round the extract lists them, the stand is the target")
    }

    func testBothFeaturesStayInTheModel() {
        let model = field(stands: [gateC24(), standC24()])
        XCTAssertEqual(model.parkingPositions.count, 2, "no OSM feature is discarded")
        XCTAssertEqual(model.routableStands.count, 1, "but only one of them is a taxi target")
        XCTAssertEqual(model.parking(named: "C24")?.osmID, "way/1008778924",
                       "the lookup resolves to the stand an aircraft can park on")
    }

    func testAGateIsOnlySupersededByAStandCloseEnoughToBeTheSameOne() {
        // A same-named stand right across the field is a different stand, not this one.
        let distant = SurfaceParking(osmID: "way/elsewhere",
                                     tags: ["aeroway": "parking_position", "ref": "C24"],
                                     kind: .parkingPosition, name: "C24",
                                     coordinate: c(38.9600, -77.4464376))
        let model = field(stands: [gateC24(), distant])
        XCTAssertEqual(model.routableStands.count, 2,
                       "a stand \(Int(SurfaceGeometry.distanceMeters(gateOnOutline, distant.coordinate.clLocation))) m away is not the same stand")
    }

    func testAFieldMappingOnlyGateNodesIsUnchanged() {
        let model = field(stands: [gateC24()])
        XCTAssertEqual(model.routableStands.map(\.osmID), ["node/3413155764"],
                       "with no parking_position to supersede it the gate is still the stand")
    }

    // MARK: - Lead-ins from a stand on the outline

    func testALeadInFromAStandOnTheOutlineTakesTheShallowestCrossing() {
        // Gate only, so the target really is the node on the concourse. The north lane is
        // nearer, but reaching it means crossing the building; the south lane does not.
        let graph = SurfaceGraphBuilder.build(from: field(stands: [gateC24()],
                                                          northLaneLatitude: 38.9456500))
        guard let stand = standNodes(graph, named: "C24").first,
              let (edge, other) = connector(graph, from: stand) else {
            return XCTFail("expected a C24 stand node with an inferred connector")
        }
        XCTAssertLessThan(other.coordinate.latitude, stand.coordinate.latitude,
                          "the lead-in leaves the concourse on the stand's own side")
        XCTAssertFalse(edge.crossesBuilding,
                       "a lead-in that only starts on the outline is not cutting through it")
    }

    func testTheAttachmentDoesNotFlipWhenTheFarLaneEdgesCloser() {
        // Before the intrusion-aware penalty, every candidate scored alike and a few metres
        // of taxilane geometry decided the side. The stand's own side must win either way.
        for northLane in [38.9465000, 38.9463800, 38.9458000] {
            let graph = SurfaceGraphBuilder.build(from: field(stands: [gateC24(), standC24()],
                                                              northLaneLatitude: northLane))
            guard let stand = standNodes(graph, named: "C24").first,
                  let (_, other) = connector(graph, from: stand) else {
                return XCTFail("expected a connector with the north lane at \(northLane)")
            }
            XCTAssertLessThan(other.coordinate.latitude, southWall,
                              "north lane at \(northLane): attached across the concourse")
        }
    }

    // MARK: - The geometry primitive

    func testIntrusionIsZeroForALeadInLeavingTheBuilding() {
        let poly = concourse().polygon.clLocations
        let outward = CLLocationCoordinate2D(latitude: southWall - 0.0010, longitude: -77.4464376)
        XCTAssertEqual(SurfaceGeometry.segmentIntrusionMeters(gateOnOutline, outward, poly), 0,
                       accuracy: 0.5, "a segment starting on the boundary and heading away is outside")
        XCTAssertTrue(SurfaceGeometry.segmentIntersectsPolygon(gateOnOutline, outward, poly),
                      "the boolean test cannot tell that apart — which is the bug it caused")
    }

    func testIntrusionMeasuresTheSpanInsideForACrossing() {
        let poly = concourse().polygon.clLocations
        let across = CLLocationCoordinate2D(latitude: northWall + 0.0010, longitude: -77.4464376)
        let width = SurfaceGeometry.distanceMeters(
            CLLocationCoordinate2D(latitude: southWall, longitude: -77.4464376),
            CLLocationCoordinate2D(latitude: northWall, longitude: -77.4464376))
        XCTAssertEqual(SurfaceGeometry.segmentIntrusionMeters(gateOnOutline, across, poly),
                       width, accuracy: 1.0, "a crossing is charged the concourse's full width")
    }

    func testIntrusionCountsOnlyTheInsidePortion() {
        let poly = concourse().polygon.clLocations
        // Starts south of the building, ends north of it: only the middle span is inside.
        let from = CLLocationCoordinate2D(latitude: southWall - 0.0020, longitude: -77.4464376)
        let to = CLLocationCoordinate2D(latitude: northWall + 0.0020, longitude: -77.4464376)
        let total = SurfaceGeometry.distanceMeters(from, to)
        let inside = SurfaceGeometry.segmentIntrusionMeters(from, to, poly)
        XCTAssertLessThan(inside, total / 2, "the approach and departure legs are outside")
        XCTAssertGreaterThan(inside, 25, "but the concourse's own width is inside")
    }

    // MARK: - Helpers

    private func makeEnds(_ r: SurfaceRunway) -> [SurfaceRunwayEnd] {
        guard let first = r.centerline.first?.clLocation, let last = r.centerline.last?.clLocation else { return [] }
        return r.idents.map { ident in
            let heading = OSMSurfaceNormalizer.runwayHeading(ident) ?? Geo.bearing(from: first, to: last)
            let bFL = Geo.bearing(from: first, to: last)
            let bLF = Geo.bearing(from: last, to: first)
            let near = Geo.headingDifference(bFL, heading) <= Geo.headingDifference(bLF, heading)
            return SurfaceRunwayEnd(ident: ident,
                                    threshold: GeoCoordinate(near ? first : last),
                                    oppositeThreshold: GeoCoordinate(near ? last : first),
                                    headingDegrees: heading, runwayOSMID: r.osmID, widthMeters: r.widthMeters)
        }
    }
}
