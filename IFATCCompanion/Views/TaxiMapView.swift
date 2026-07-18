import SwiftUI
import MapKit
import CoreLocation

/// Observes the coordinator and animates the temporary taxi map into / out of the ATC
/// view. A self-contained `@ObservedObject` wrapper so the ATC view doesn't need the
/// coordinator in its environment.
struct TaxiMapSlot: View {
    @ObservedObject var surface: AirportSurfaceCoordinator

    var body: some View {
        Group {
            if surface.taxiMapVisible {
                TaxiMapCard(surface: surface)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.35), value: surface.taxiMapVisible)
    }
}

/// The dedicated, temporary MapKit taxi map shown in the ATC view after the pilot reads
/// back a taxi clearance. Renders OpenStreetMap-derived runways, taxiways, the assigned
/// route, aircraft, gates, holding positions, and runway crossings as custom overlays.
/// Distinct from the Weather map. Visible OpenStreetMap attribution is always shown and
/// is tappable.
struct TaxiMapCard: View {
    @ObservedObject var surface: AirportSurfaceCoordinator
    @State private var showExpanded = false

    var body: some View {
        Card(title: "Taxi Map (Simulated)", systemImage: "map") {
            VStack(alignment: .leading, spacing: 10) {
                TaxiMapHeader(surface: surface)
                if surface.offRoute { offRouteBanner }
                TaxiMapCanvas(surface: surface, expanded: false)
                    .frame(height: 230)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                nextInstructionRow
                controlsRow
                TaxiMapActionsRow(surface: surface)
                TaxiMapFooter()
            }
        }
        .fullScreenCover(isPresented: $showExpanded) {
            ExpandedTaxiMap(surface: surface) { showExpanded = false }
        }
        .onChange(of: surface.mapExpanded) { _, expanded in
            if expanded { showExpanded = true }
        }
    }

    private var offRouteBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text("Off assigned taxi route")
                .font(.subheadline.weight(.semibold))
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.orange.opacity(0.18)))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Color.orange.opacity(0.55), lineWidth: 1))
        .foregroundStyle(.orange)
    }

    private var nextInstructionRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "arrow.turn.up.right").foregroundStyle(.cyan)
            Text(surface.nextInstruction.isEmpty ? "Follow the assigned taxi route." : surface.nextInstruction)
                .font(.subheadline.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var controlsRow: some View {
        HStack(spacing: 10) {
            Button { surface.mapExpanded = true } label: {
                Label("Expand", systemImage: "arrow.up.left.and.arrow.down.right")
            }
            .buttonStyle(.bordered)
            Button { surface.recalculateRoute() } label: {
                Label("Recalculate", systemImage: "arrow.triangle.2.circlepath")
            }
            .buttonStyle(.bordered)
            Spacer(minLength: 0)
        }
        .font(.caption)
    }
}

/// Header chips: assigned runway/gate, taxiway sequence, crossings, and confidence.
struct TaxiMapHeader: View {
    @ObservedObject var surface: AirportSurfaceCoordinator

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                StatusPill(text: destinationText, level: .neutral, systemImage: destinationIcon)
                Spacer()
                StatusPill(text: "\(surface.routeConfidence.title) confidence",
                           level: confidenceLevel(surface.routeConfidence), systemImage: "gauge.with.dots.needle.33percent")
            }
            HStack {
                Label(taxiwayText, systemImage: "arrow.triangle.turn.up.right.diamond")
                    .font(.caption).foregroundStyle(.secondary)
                    .lineLimit(1).minimumScaleFactor(0.7)
                Spacer()
                if crossingCount > 0 {
                    Label("\(crossingCount) crossing\(crossingCount == 1 ? "" : "s")", systemImage: "arrow.left.and.right")
                        .font(.caption).foregroundStyle(.orange)
                }
            }
        }
    }

    private var crossingCount: Int { surface.route?.crossings.count ?? 0 }
    private var taxiwayText: String { surface.route.map { "Via \($0.taxiwaysText)" } ?? "Route pending" }
    private var destinationText: String {
        if let r = surface.route { return r.destinationLabel.capitalizedFirst }
        return surface.kind == .arrival ? "To gate" : "To runway"
    }
    private var destinationIcon: String { surface.kind == .arrival ? "parkingsign" : "airplane.departure" }
}

/// The runway-crossing / off-route response buttons for the taxi map.
struct TaxiMapActionsRow: View {
    @ObservedObject var surface: AirportSurfaceCoordinator

    var body: some View {
        let actions = surface.offRoute ? surface.offRouteActions : surface.crossingActions
        if !actions.isEmpty {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                ForEach(actions) { action in
                    ActionButton(title: action.title, systemImage: action.systemImage, tint: tint(action)) {
                        perform(action)
                    }
                }
            }
        }
    }

    private func tint(_ a: TaxiMapAction) -> Color {
        switch a {
        case .requestCrossing: return .green
        case .holdPosition: return .orange
        default: return .accentColor
        }
    }

    private func perform(_ a: TaxiMapAction) {
        switch a {
        case .holdPosition: surface.holdPosition()
        case .requestCrossing: surface.requestCrossing()
        case .requestAlternateRoute: surface.requestAlternateRoute()
        case .recalculate: surface.recalculateRoute()
        case .continueOriginalRoute: surface.continueOriginalRoute()
        case .requestNewTaxi: surface.requestNewTaxiInstructions()
        }
    }
}

/// Attribution + simulation disclaimer footer shown under the map (always visible).
struct TaxiMapFooter: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Link(destination: OSMSurface.copyrightURL) {
                Text(OSMSurface.attributionText)
                    .font(.caption2.weight(.semibold))
                    .underline()
            }
            .foregroundStyle(.secondary)
            Text("Simulation only — not for real-world aviation. OSM data may not match Infinite Flight scenery.")
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Full-screen expanded taxi map.
struct ExpandedTaxiMap: View {
    @ObservedObject var surface: AirportSurfaceCoordinator
    var dismiss: () -> Void

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottomLeading) {
                TaxiMapCanvas(surface: surface, expanded: true)
                    .ignoresSafeArea(edges: .bottom)
                // Attribution stays visible directly on the expanded map.
                Link(destination: OSMSurface.copyrightURL) {
                    Text(OSMSurface.attributionText)
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Capsule().fill(.ultraThinMaterial))
                }
                .padding(12)
            }
            .navigationTitle(surface.nextInstruction.isEmpty ? "Taxi Map" : surface.nextInstruction)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    StatusPill(text: "\(surface.routeConfidence.title)", level: confidenceLevel(surface.routeConfidence), systemImage: "gauge.with.dots.needle.33percent")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { surface.mapExpanded = false; dismiss() }
                }
                ToolbarItem(placement: .bottomBar) {
                    TaxiMapActionsRow(surface: surface)
                }
            }
        }
    }
}

/// The MapKit canvas that draws all OSM-derived airport geometry + the route.
struct TaxiMapCanvas: View {
    @ObservedObject var surface: AirportSurfaceCoordinator
    var expanded: Bool
    @State private var position: MapCameraPosition = .automatic

    var body: some View {
        Map(position: $position) {
            if let model = surface.surface {
                // Runways (thick).
                ForEach(model.runways) { rwy in
                    MapPolyline(coordinates: rwy.centerline.clLocations)
                        .stroke(.gray, style: StrokeStyle(lineWidth: expanded ? 10 : 6, lineCap: .round))
                }
                // Taxiways + taxilanes.
                ForEach(model.taxiways) { twy in
                    MapPolyline(coordinates: twy.geometry.clLocations)
                        .stroke(taxiwayColor(twy),
                                style: StrokeStyle(lineWidth: twy.isTaxilane ? 1.5 : 2.5,
                                                   lineCap: .round,
                                                   dash: (twy.isClosed || twy.isTaxilane) ? [4, 4] : []))
                }
            }

            // Assigned route (emphasized).
            if let route = surface.route, route.geometry.count >= 2 {
                MapPolyline(coordinates: route.clGeometry)
                    .stroke(.cyan, style: StrokeStyle(lineWidth: expanded ? 7 : 5, lineCap: .round,
                                                      dash: route.confidence <= .low ? [8, 5] : []))
            }

            // Departure gate — the only stand that matters on taxi out (the one you're
            // leaving). On arrival the gate you're heading to is the route destination,
            // drawn as the marker below, so no other stands are shown. Drawing a large
            // field's full set of OSM stands as individual annotations overwhelmed MapKit
            // for SwiftUI and crashed the app.
            if let gate = departureGate {
                Marker(gate.name.isEmpty ? "Gate" : gate.name,
                       systemImage: "parkingsign",
                       coordinate: gate.coordinate.clLocation)
                    .tint(.mint)
            }
            // Holding positions (bounded to those nearest the route so a dense field never
            // floods MapKit with annotation host views).
            ForEach(visibleHolds) { hold in
                Annotation("", coordinate: hold.coordinate.clLocation) {
                    Image(systemName: "minus")
                        .font(.system(size: 12, weight: .heavy))
                        .foregroundStyle(.yellow)
                        .rotationEffect(.degrees(90))
                }
            }

            // Runway crossings on the route.
            if let route = surface.route {
                ForEach(route.crossings) { crossing in
                    Annotation(expanded ? "RWY \(crossing.runwayIdent)" : "", coordinate: crossing.point.clLocation) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.body)
                            .foregroundStyle(isActive(crossing) ? .red : .orange)
                            .background(Circle().fill(.white.opacity(0.6)))
                    }
                }
                // Destination marker.
                Marker(route.destinationLabel.capitalizedFirst,
                       systemImage: route.isDeparture ? "airplane.departure" : "parkingsign",
                       coordinate: route.endCoordinate.clLocation)
                    .tint(route.isDeparture ? .green : .mint)
            }

            // Aircraft.
            if let ac = surface.displayAircraft {
                Annotation("Aircraft", coordinate: ac.coordinate.clLocation) {
                    Image(systemName: "airplane")
                        .font(.system(size: expanded ? 22 : 16, weight: .bold))
                        .foregroundStyle(.white)
                        .rotationEffect(.degrees(ac.headingDegrees - 90))
                        .padding(5)
                        .background(Circle().fill(.blue))
                }
            }
        }
        .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
        .onAppear { fit() }
        .onChange(of: routeSignature) { _, _ in fit() }
    }

    private func isActive(_ c: RouteCrossing) -> Bool {
        surface.activeCrossing?.index == c.index
    }

    private var routeSignature: String {
        let n = surface.route?.geometry.count ?? 0
        return "\(surface.route?.destinationLabel ?? "")|\(n)|\(surface.surface?.icao ?? "")"
    }

    private func taxiwayColor(_ t: SurfaceTaxiway) -> Color {
        if t.isClosed { return .orange.opacity(0.7) }
        if t.isTaxilane { return .gray.opacity(0.6) }
        if !t.hasName { return .gray.opacity(0.55) }
        return Color(red: 0.55, green: 0.75, blue: 0.9)
    }

    // MARK: On-map content bounding
    //
    // Only the gate that matters is drawn — the departure gate on taxi out; the arrival
    // gate is the route destination marker — and holding positions are capped to those
    // nearest the route, so a dense field never floods MapKit with annotation host views.

    private var holdLimit: Int { expanded ? 30 : 16 }
    private var routePath: [CLLocationCoordinate2D] { surface.route?.clGeometry ?? [] }

    /// The gate to highlight on a departure taxi: the stand nearest the route start (the
    /// one you're leaving). Nil on arrival — that gate is already the destination marker.
    private var departureGate: SurfaceParking? {
        guard let route = surface.route, route.isDeparture, let model = surface.surface else { return nil }
        return model.nearestParking(to: route.startCoordinate.clLocation, within: 150)
    }

    /// Holding positions to draw for context: the nearest to the route, capped.
    private var visibleHolds: [SurfaceHoldingPosition] {
        guard let all = surface.surface?.holdingPositions else { return [] }
        return TaxiMapContent.nearestToRoute(all, route: routePath, limit: holdLimit) { $0.coordinate.clLocation }
    }

    /// Fit the camera to the route (falling back to the airport geometry).
    private func fit() {
        if let region = routeRegion() {
            position = .region(region)
        } else {
            position = .automatic
        }
    }

    private func routeRegion() -> MKCoordinateRegion? {
        var coords: [CLLocationCoordinate2D] = surface.route?.clGeometry ?? []
        if coords.count < 2, let model = surface.surface {
            coords = model.runways.flatMap { $0.centerline.clLocations }
                + model.taxiways.flatMap { $0.geometry.clLocations }
        }
        guard coords.count >= 2 else { return nil }
        let lats = coords.map(\.latitude), lons = coords.map(\.longitude)
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max() else { return nil }
        let center = CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2, longitude: (minLon + maxLon) / 2)
        let span = MKCoordinateSpan(latitudeDelta: max((maxLat - minLat) * 1.4, 0.004),
                                    longitudeDelta: max((maxLon - minLon) * 1.4, 0.004))
        return MKCoordinateRegion(center: center, span: span)
    }
}

// MARK: - Shared helpers

/// Pure geometry helpers for the taxi map's on-map content. Kept out of the SwiftUI
/// view so the annotation-bounding rule can be unit-tested directly.
enum TaxiMapContent {
    /// Select the features nearest the route, capped to `limit`.
    ///
    /// A large airport has hundreds of OSM stands and dozens of hold points. MapKit for
    /// SwiftUI creates a hosting view for every `Annotation`, so drawing the full set at
    /// the fit-to-route zoom overwhelms the map content builder and crashes the app. Only
    /// the closest `limit` features to the assigned route are drawn for context; the route,
    /// runways, taxiways, crossings, destination, and aircraft are always shown in full.
    ///
    /// Returns the input unchanged when it already fits under `limit`; with no usable route
    /// the first `limit` features are kept (still bounded).
    static func nearestToRoute<T>(_ features: [T],
                                  route: [CLLocationCoordinate2D],
                                  limit: Int,
                                  coordinate: (T) -> CLLocationCoordinate2D) -> [T] {
        guard limit > 0 else { return [] }
        guard features.count > limit else { return features }
        guard route.count >= 2 else { return Array(features.prefix(limit)) }
        return features
            .map { (feature: $0, distance: SurfaceGeometry.nearestPointOnPath(coordinate($0), route)?.distanceMeters ?? .infinity) }
            .sorted { $0.distance < $1.distance }
            .prefix(limit)
            .map { $0.feature }
    }
}

func confidenceLevel(_ c: SurfaceConfidence) -> StatusLevel {
    switch c {
    case .high: return .green
    case .medium: return .amber
    case .low: return .amber
    case .unavailable: return .red
    }
}

private extension String {
    var capitalizedFirst: String {
        guard let first else { return self }
        return String(first).uppercased() + dropFirst()
    }
}
