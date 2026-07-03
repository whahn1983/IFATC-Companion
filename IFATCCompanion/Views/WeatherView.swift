import SwiftUI

struct WeatherView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var settings: AppSettings
    @State private var refreshing = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    actionsCard
                    routeOverlayCard
                    radarCard
                    statusCard
                    metarCard(title: "Departure METAR", metar: model.departureMETAR, icao: model.flightPlan.departure)
                    metarCard(title: "Destination METAR", metar: model.destinationMETAR, icao: model.flightPlan.destination)
                    if !model.flightPlan.alternate.isEmpty {
                        metarCard(title: "Alternate METAR", metar: model.alternateMETAR, icao: model.flightPlan.alternate)
                    }
                    tafCard
                    overallRideCard
                    rideReportsCard
                    sigmetCard
                    disclaimerCard
                }
                .padding(16)
            }
            .navigationTitle("Weather")
            .screenBackground()
            .refreshable { await refresh() }
        }
    }

    private var actionsCard: some View {
        Card(title: "Ask Center", systemImage: "globe.americas") {
            VStack(spacing: 10) {
                ActionButton(title: "Ride Reports", systemImage: "wind") { model.requestRideReport() }
                ActionButton(title: "Destination Weather", systemImage: "cloud.sun.rain") { model.requestDestinationWeather() }
                ActionButton(title: "Lower / Higher Due Ride", systemImage: "arrow.up.arrow.down") { model.requestLowerDueRide() }
                Button {
                    Task { await refresh() }
                } label: {
                    Label(refreshing ? "Refreshing…" : "Refresh Weather Data", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .disabled(refreshing)
            }
        }
    }

    private var routeOverlayCard: some View {
        Card(title: "Route & Weather Overlay", systemImage: "map") {
            VStack(alignment: .leading, spacing: 8) {
                RouteMapView()
                HStack(spacing: 12) {
                    legendDot(.green, "Light/chop")
                    legendDot(.yellow, "Light")
                    legendDot(.orange, "Moderate")
                    legendDot(.red, "Severe")
                }
                .font(.caption2).foregroundStyle(.secondary)
                Text("Dots are pilot reports; shaded areas are SIGMET/AIRMET advisories and NOAA/NWS radar precipitation where available. The purple outline and mint path show a simulated weather-deviation conflict and recommended reroute.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private func legendDot(_ color: Color, _ label: String) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label)
        }
    }

    // MARK: - Radar precipitation

    /// NOAA/NWS radar precipitation overlay controls, coverage/source labels,
    /// opacity, legend, and attribution. Simulation-only, NOAA-covered regions.
    private var radarCard: some View {
        Card(title: "Radar Precipitation", systemImage: "cloud.rain") {
            VStack(alignment: .leading, spacing: 10) {
                Toggle("Radar Precipitation Overlay", isOn: Binding(
                    get: { settings.noaaRadarOverlay == .autoWhereAvailable },
                    set: { settings.noaaRadarOverlay = $0 ? .autoWhereAvailable : .off
                           model.recomputeWeatherHazards() }))

                if settings.showWeatherDataSourceLabels {
                    DataRow(label: "Source", value: "NOAA/NWS radar precipitation")
                }
                DataRow(label: "Last updated", value: lastRadarUpdated)

                if model.radarOverlay.coverageAvailable {
                    Label("Available in NOAA-covered radar regions", systemImage: "checkmark.seal")
                        .font(.caption).foregroundStyle(.green)
                } else if settings.showWeatherCoverageWarnings {
                    Label(model.radarOverlay.unavailableMessage, systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundStyle(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                }

                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("Opacity").font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Text("\(Int((settings.radarOpacity * 100).rounded()))%")
                            .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    Slider(value: $settings.radarOpacity, in: 0.1...1.0)
                        .onChange(of: settings.radarOpacity) { _, _ in model.recomputeWeatherHazards() }
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text("Legend").font(.caption).foregroundStyle(.secondary)
                    HStack(spacing: 12) {
                        legendDot(.green, "Light")
                        legendDot(.yellow, "Moderate")
                        legendDot(.orange, "Heavy")
                        legendDot(.red, "Extreme")
                    }
                    .font(.caption2).foregroundStyle(.secondary)
                    Text("precipitation")
                        .font(.caption2).foregroundStyle(.tertiary)
                }

                if settings.showWeatherDataSourceLabels, let attribution = model.radarOverlay.attributionText {
                    Text(attribution).font(.caption2).foregroundStyle(.tertiary)
                }
            }
        }
    }

    private var lastRadarUpdated: String {
        guard model.radarOverlay.coverageAvailable, let date = model.radarOverlay.lastUpdated else {
            return "—"
        }
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f.string(from: date)
    }

    // MARK: - Disclaimers

    private var disclaimerCard: some View {
        Card(title: "About This Data", systemImage: "info.circle") {
            VStack(alignment: .leading, spacing: 8) {
                Text("Radar, precipitation, and deviation logic are for simulation only and must not be used for real-world aviation.")
                    .font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Radar precipitation is available only where the app's free NOAA/NWS data source provides coverage.")
                    .font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Training and entertainment use only. No paid weather subscription, API key, or account is required.")
                    .font(.caption2).foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var statusCard: some View {
        Card(title: "Status", systemImage: "info.circle") {
            Text(model.weatherStatus).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    private func metarCard(title: String, metar: METAR?, icao: String) -> some View {
        Card(title: title, systemImage: "thermometer.medium") {
            if let m = metar {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(m.icao).font(.headline)
                        Spacer()
                        if let cat = m.flightCategory {
                            StatusPill(text: cat, level: categoryLevel(cat), systemImage: "eye")
                        }
                    }
                    if let dir = m.windDirection, let spd = m.windSpeed {
                        DataRow(label: "Wind", value: "\(String(format: "%03d", dir))° @ \(spd) kt" + (m.windGust.map { " G\($0)" } ?? ""))
                    }
                    if let vis = m.visibilitySM { DataRow(label: "Visibility", value: "\(fmtVis(vis)) SM") }
                    if let ceil = m.ceilingFt { DataRow(label: "Ceiling", value: "\(ceil) ft") }
                    if let alt = m.altimeterInHg { DataRow(label: "Altimeter", value: String(format: "%.2f inHg", alt)) }
                    if let t = m.temperatureC { DataRow(label: "Temp / Dew", value: "\(Int(t))°C / \(m.dewpointC.map { "\(Int($0))°C" } ?? "—")") }
                    if !m.raw.isEmpty {
                        Text(m.raw).font(.caption.monospaced()).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            } else {
                Text(icao.isEmpty ? "No airport set." : "No METAR for \(icao).")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var tafCard: some View {
        Card(title: "Destination TAF", systemImage: "calendar.badge.clock") {
            if let taf = model.destinationTAF, !taf.raw.isEmpty {
                Text(taf.raw).font(.caption.monospaced()).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text("No TAF loaded.").foregroundStyle(.secondary)
            }
        }
    }

    private var overallRideCard: some View {
        Card(title: "Overall Ride", systemImage: "gauge.with.dots.needle.bottom.50percent") {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    StatusPill(text: model.rideAssessment.severity.title,
                               level: severityLevel(model.rideAssessment.severity), systemImage: "wind")
                    Spacer()
                    Text("Ride index \(Int((model.rideAssessment.index * 100).rounded()))%")
                        .font(.subheadline.weight(.semibold))
                }
                ProgressView(value: model.rideAssessment.index)
                    .tint(severityLevel(model.rideAssessment.severity).color)
                if model.rideAssessment.contributors.isEmpty {
                    Text("Composite model: PIREPs, SIGMETs, and surface wind shear.")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("Factors: " + model.rideAssessment.contributors.joined(separator: ", "))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var rideReportsCard: some View {
        Card(title: "Ride Reports", systemImage: "wind") {
            if model.rideReportItems.isEmpty {
                Text("No significant ride reports along your route.").foregroundStyle(.secondary)
            } else {
                VStack(spacing: 10) {
                    ForEach(model.rideReportItems) { item in
                        HStack(alignment: .top) {
                            StatusPill(text: item.severity.title, level: severityLevel(item.severity), systemImage: "wind")
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                if let d = item.distanceAheadNM {
                                    Text("\(Int(d.rounded())) NM ahead").font(.subheadline.weight(.semibold))
                                }
                                if let band = item.altitudeBand {
                                    Text("\(band.lowerBound)–\(band.upperBound) ft").font(.caption).foregroundStyle(.secondary)
                                }
                                if let fix = item.nearFix { Text("near \(fix)").font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
            }
        }
    }

    private var sigmetCard: some View {
        Card(title: "SIGMET / AIRMET", systemImage: "exclamationmark.triangle") {
            if model.routeSigmets.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("No advisories along your route.").foregroundStyle(.secondary)
                    if !model.sigmets.isEmpty {
                        Text("\(model.sigmets.count) active elsewhere (off route).")
                            .font(.caption).foregroundStyle(.tertiary)
                    }
                }
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Along your route:").font(.caption).foregroundStyle(.secondary)
                    ForEach(model.routeSigmets.prefix(5)) { s in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.hazard ?? "Advisory").font(.subheadline.weight(.semibold))
                            if !s.raw.isEmpty {
                                Text(s.raw).font(.caption.monospaced()).foregroundStyle(.secondary)
                                    .lineLimit(3)
                            }
                        }
                    }
                }
            }
        }
    }

    private func refresh() async {
        refreshing = true
        await model.refreshWeather()
        refreshing = false
    }

    private func categoryLevel(_ cat: String) -> StatusLevel {
        switch cat.uppercased() {
        case "VFR": return .green
        case "MVFR": return .amber
        case "IFR", "LIFR": return .red
        default: return .neutral
        }
    }

    private func severityLevel(_ s: TurbulenceSeverity) -> StatusLevel {
        switch s {
        case .smooth, .lightChop: return .green
        case .light: return .amber
        case .moderate, .severe: return .red
        }
    }

    private func fmtVis(_ v: Double) -> String {
        v == v.rounded() ? String(Int(v)) : String(format: "%.1f", v)
    }
}
