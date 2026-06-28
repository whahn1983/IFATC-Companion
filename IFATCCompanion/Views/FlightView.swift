import SwiftUI

struct FlightView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var settings: AppSettings

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    liveStateCard
                    flightPlanCard
                    manualOverrideCard
                }
                .padding(16)
            }
            .navigationTitle("Flight")
            .screenBackground()
        }
    }

    private var s: AircraftState { model.aircraftState }

    private var liveStateCard: some View {
        Card(title: "Live Aircraft State", systemImage: "airplane.circle") {
            VStack(spacing: 8) {
                DataRow(label: "Latitude", value: fmt(s.latitude, "%.4f"), systemImage: "location")
                DataRow(label: "Longitude", value: fmt(s.longitude, "%.4f"), systemImage: "location")
                DataRow(label: "Altitude MSL", value: fmtInt(s.altitudeMSL, "ft"), systemImage: "arrow.up")
                DataRow(label: "Groundspeed", value: fmtInt(s.groundSpeed, "kt"), systemImage: "speedometer")
                DataRow(label: "Airspeed (IAS)", value: fmtInt(s.indicatedAirspeed, "kt"), systemImage: "gauge")
                DataRow(label: "Heading", value: fmtHeading(s.heading), systemImage: "safari")
                DataRow(label: "Track", value: fmtHeading(s.track), systemImage: "arrow.up.right")
                DataRow(label: "Vertical Speed", value: fmtInt(s.verticalSpeed, "fpm"), systemImage: "arrow.up.arrow.down")
                DataRow(label: "On Ground", value: s.onGround.map { $0 ? "Yes" : "No" } ?? "—", systemImage: "parkingsign")
                DataRow(label: "Distance to Dest", value: distanceToDest, systemImage: "flag.checkered")
                DataRow(label: "Next Waypoint", value: nextWaypoint, systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                DataRow(label: "Flight Phase", value: model.phase.title, systemImage: "list.bullet")
                DataRow(label: "Airport Proximity", value: proximity, systemImage: "mappin.and.ellipse")
                DataRow(label: "Runway", value: model.flightPlan.runway.isEmpty ? "auto" : model.flightPlan.runway, systemImage: "road.lanes")
            }
        }
    }

    private var flightPlanCard: some View {
        Card(title: "Flight Plan", systemImage: "map") {
            VStack(spacing: 8) {
                DataRow(label: "Departure", value: orDash(model.flightPlan.departure))
                DataRow(label: "Destination", value: orDash(model.flightPlan.destination))
                DataRow(label: "Alternate", value: orDash(model.flightPlan.alternate))
                DataRow(label: "Cruise", value: model.flightPlan.cruiseAltitude > 0 ? PhraseologyEngine().formatAltDisplay(model.flightPlan.cruiseAltitude) : "—")
                DataRow(label: "SID", value: orDash(model.flightPlan.sid))
                DataRow(label: "STAR", value: orDash(model.flightPlan.star))
                DataRow(label: "Approach", value: orDash(model.flightPlan.approach))
                if !model.flightPlan.waypoints.isEmpty {
                    Divider()
                    Text("Waypoints").font(.caption).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(model.flightPlan.waypoints.map { $0.name }.joined(separator: "  →  "))
                        .font(.callout)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private var manualOverrideCard: some View {
        Card(title: "Manual Overrides", systemImage: "pencil") {
            VStack(spacing: 10) {
                Text("Connect/Live coverage varies — enter any values manually.")
                    .font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                overrideField("Callsign", text: $settings.callsign, placeholder: "e.g. UAL598 or N123AB")
                HStack {
                    overrideField("Airline", text: $settings.airline, placeholder: "United")
                    overrideField("Flight #", text: $settings.flightNumber, placeholder: "598")
                }
                HStack {
                    overrideField("Departure", text: $settings.departure, placeholder: "KIAH")
                    overrideField("Destination", text: $settings.destination, placeholder: "KMSP")
                }
                HStack {
                    overrideField("Alternate", text: $settings.alternate, placeholder: "KDSM")
                    cruiseField
                }
                HStack {
                    overrideField("Runway", text: $settings.runway, placeholder: "17R")
                    overrideField("Approach", text: $settings.approach, placeholder: "ILS 30L")
                }
                HStack {
                    overrideField("SID", text: $settings.sid, placeholder: "WAGmm")
                    overrideField("STAR", text: $settings.star, placeholder: "KKILR")
                }
                Button {
                    model.applyManualOverrides()
                } label: {
                    Label("Apply Overrides", systemImage: "checkmark.circle")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }

    private func overrideField(_ label: String, text: Binding<String>, placeholder: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            TextField(placeholder, text: text)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)
        }
        .frame(maxWidth: .infinity)
    }

    private var cruiseField: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("Cruise (ft)").font(.caption).foregroundStyle(.secondary)
            TextField("37000", value: $settings.cruiseAltitude, format: .number)
                .keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Formatting

    private func fmt(_ v: Double?, _ format: String) -> String {
        v.map { String(format: format, $0) } ?? "—"
    }
    private func fmtInt(_ v: Double?, _ unit: String) -> String {
        v.map { "\(Int($0.rounded())) \(unit)" } ?? "—"
    }
    private func fmtHeading(_ v: Double?) -> String {
        v.map { String(format: "%03d°", Int($0.rounded()) % 360) } ?? "—"
    }
    private func orDash(_ s: String) -> String { s.isEmpty ? "—" : s }

    private var distanceToDest: String {
        guard let pos = s.coordinate,
              let dest = AirportDatabase.shared.coordinate(for: model.flightPlan.destination) else { return "—" }
        return "\(Int(Geo.distanceNM(from: pos, to: dest).rounded())) NM"
    }

    private var nextWaypoint: String {
        model.flightPlan.nextWaypoint(from: s.coordinate)?.name ?? "—"
    }

    private var proximity: String {
        guard let near = s.nearestAirport, !near.isEmpty else { return "—" }
        if let d = s.nearestAirportDistanceNM { return "\(near) (\(Int(d.rounded())) NM)" }
        return near
    }
}
