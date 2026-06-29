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

    private var hasContent: Bool {
        routeCoordinates.count >= 2 || model.aircraftState.coordinate != nil
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
    }

    private func color(for severity: TurbulenceSeverity) -> Color {
        switch severity {
        case .smooth, .lightChop: return .green
        case .light: return .yellow
        case .moderate: return .orange
        case .severe: return .red
        }
    }
}
