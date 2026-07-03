import SwiftUI
import MapKit
import CoreLocation

/// A ForeFlight-style route + weather overlay: the planned route line, departure
/// and destination markers, the live aircraft position, and PIREP turbulence
/// reports color-coded by severity. Read-only and offline-friendly (uses the
/// built-in airport coordinates and whatever weather is already loaded).
struct RouteMapView: View {
    @EnvironmentObject var model: AppModel
    @State private var position: MapCameraPosition = .automatic

    private let airports = AirportDatabase.shared

    // Fall back to the first/last located flight-plan fix when the airport itself
    // isn't in the built-in coordinate database, so the route still draws for
    // fields outside the small built-in list.
    private var depCoord: CLLocationCoordinate2D? {
        airports.coordinate(for: model.flightPlan.departure)
            ?? model.flightPlan.firstWaypointCoordinate
    }
    private var destCoord: CLLocationCoordinate2D? {
        airports.coordinate(for: model.flightPlan.destination)
            ?? model.flightPlan.lastWaypointCoordinate
    }

    /// Ordered route coordinates: departure, located waypoints, destination.
    private var routeCoordinates: [CLLocationCoordinate2D] {
        var coords: [CLLocationCoordinate2D] = []
        if let depCoord { coords.append(depCoord) }
        coords.append(contentsOf: model.flightPlan.waypoints.compactMap { $0.coordinate })
        if let destCoord { coords.append(destCoord) }
        return coords.filter { $0.isValid }
    }

    private var locatedPireps: [PIREP] {
        model.pireps.filter { ($0.coordinate?.isValid ?? false) && ($0.turbulence ?? .smooth) > .smooth }
    }

    /// Route-relevant SIGMET/AIRMET advisories that have a drawable area. These are
    /// the same advisories that raise the composite ride index, so the map and the
    /// ride assessment always agree.
    private var routeSigmetAreas: [SIGMET] {
        model.routeSigmets.filter { $0.turbulenceSeverity > .smooth && $0.drawableArea != nil }
    }

    private var hasContent: Bool {
        routeCoordinates.count >= 2 || model.aircraftState.coordinate != nil
    }

    /// A short signature of the route so the camera refits when the plan changes
    /// (e.g. after a refresh pulls in the full set of fixes).
    private var routeSignature: String {
        "\(model.flightPlan.departure)|\(model.flightPlan.destination)|\(routeCoordinates.count)"
    }

    /// A region that frames the whole route (departure → all fixes → destination)
    /// with a little padding, so the entire plan is visible rather than a clipped
    /// straight line. Returns nil when there isn't enough located geometry.
    private var routeRegion: MKCoordinateRegion? {
        let coords = routeCoordinates
        guard coords.count >= 2 else { return nil }
        let lats = coords.map(\.latitude), lons = coords.map(\.longitude)
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max() else { return nil }
        let center = CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2,
                                            longitude: (minLon + maxLon) / 2)
        let span = MKCoordinateSpan(latitudeDelta: max((maxLat - minLat) * 1.4, 0.4),
                                    longitudeDelta: max((maxLon - minLon) * 1.4, 0.4))
        return MKCoordinateRegion(center: center, span: span)
    }

    private func fitRoute() {
        if let region = routeRegion {
            position = .region(region)
        } else {
            position = .automatic
        }
    }

    var body: some View {
        Group {
            if hasContent {
                map
            } else {
                Text("Enter a departure and destination (with known coordinates) to see the route overlay.")
                    .font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 80)
            }
        }
    }

    private var map: some View {
        Map(position: $position) {
            // Advisory areas first, so route line, PIREPs and markers draw on top.
            ForEach(routeSigmetAreas) { sigmet in
                MapPolygon(coordinates: sigmet.drawableArea ?? [])
                    .foregroundStyle(sigmetColor(sigmet).opacity(0.20))
                    .stroke(sigmetColor(sigmet), lineWidth: 2)
            }

            if routeCoordinates.count >= 2 {
                MapPolyline(coordinates: routeCoordinates)
                    .stroke(.cyan, style: StrokeStyle(lineWidth: 3, lineCap: .round, dash: [7, 4]))
            }

            if let depCoord {
                Marker(model.flightPlan.departure.isEmpty ? "Dep" : model.flightPlan.departure,
                       systemImage: "airplane.departure", coordinate: depCoord)
                    .tint(.green)
            }
            if let destCoord {
                Marker(model.flightPlan.destination.isEmpty ? "Dest" : model.flightPlan.destination,
                       systemImage: "airplane.arrival", coordinate: destCoord)
                    .tint(.red)
            }

            ForEach(locatedPireps) { pirep in
                Annotation(pirep.turbulence?.title ?? "PIREP", coordinate: pirep.coordinate!) {
                    Circle()
                        .fill(color(for: pirep.turbulence ?? .smooth))
                        .frame(width: 14, height: 14)
                        .overlay(Circle().stroke(.white, lineWidth: 1.5))
                }
            }

            if let aircraft = model.aircraftState.coordinate {
                Annotation("Aircraft", coordinate: aircraft) {
                    Image(systemName: "airplane")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(.white)
                        .rotationEffect(.degrees((model.aircraftState.heading ?? 0) - 90))
                        .padding(6)
                        .background(Circle().fill(.blue))
                }
            }
        }
        .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
        .frame(height: 280)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .onAppear { fitRoute() }
        .onChange(of: routeSignature) { _, _ in fitRoute() }
    }

    private func color(for severity: TurbulenceSeverity) -> Color {
        switch severity {
        case .smooth, .lightChop: return .green
        case .light: return .yellow
        case .moderate: return .orange
        case .severe: return .red
        }
    }

    /// Color for a SIGMET area, matching the severity that raises the ride index.
    private func sigmetColor(_ sigmet: SIGMET) -> Color {
        color(for: sigmet.turbulenceSeverity)
    }
}
