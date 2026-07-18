import SwiftUI

struct DiagnosticsView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var settings: AppSettings
    @EnvironmentObject var diagnostics: DiagnosticsStore
    @EnvironmentObject var connect: IFConnectManager
    @EnvironmentObject var mock: MockSimulatorFeed

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    mockCard
                    liveATCCard
                    phaseCard
                    atisCard
                    weatherStatusCard
                    weatherDiagnosticsCard
                    manifestCard
                    rawCard
                    logCard
                }
                .padding(16)
            }
            .navigationTitle("Diagnostics")
            .screenBackground()
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    ShareLink(item: diagnostics.exportText()) {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
            }
        }
    }

    private var mockCard: some View {
        Card(title: "Mock Mode", systemImage: "ladybug") {
            VStack(spacing: 10) {
                Toggle("Mock simulator feed", isOn: Binding(
                    get: { settings.mockMode },
                    set: { model.toggleMockMode($0) }))
                if settings.mockMode {
                    HStack {
                        StatusPill(text: "Phase: \(mock.phase.title)", level: .amber, systemImage: "flag")
                        Spacer()
                        Button {
                            model.advanceMockPhase()
                        } label: { Label("Advance Phase", systemImage: "forward.fill") }
                            .buttonStyle(.borderedProminent)
                    }
                    Text("Route: \(mock.route.departure) → \(mock.route.destination)")
                        .font(.caption).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private var liveATCCard: some View {
        Card(title: "Multiplayer / ATC Staffing", systemImage: "person.2.wave.2") {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    StatusPill(text: model.liveATC.humanControllerActive ? "Human ATC" : "No human ATC",
                               level: model.liveATC.humanControllerActive ? .amber : .green,
                               systemImage: "headphones")
                    Spacer()
                    StatusPill(text: model.liveATC.multiplayerOnline ? "Online" : "Solo",
                               level: model.liveATC.multiplayerOnline ? .green : .neutral,
                               systemImage: "dot.radiowaves.up.forward")
                }
                Text(model.liveATC.summary).font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if settings.mockMode {
                    Toggle("Simulate staffed ATC (demo)", isOn: $model.simulateStaffedATC)
                    Text("Staffing detection runs automatically in live mode using the Connect manifest.")
                        .font(.caption2).foregroundStyle(.tertiary)
                }
            }
        }
    }

    private var phaseCard: some View {
        Card(title: "Phase Detection", systemImage: "function") {
            VStack(spacing: 6) {
                DataRow(label: "Detected Phase", value: model.phase.title)
                DataRow(label: "On Ground", value: model.phaseDebug.onGround ? "Yes" : "No")
                DataRow(label: "Groundspeed", value: "\(Int(model.phaseDebug.groundSpeed)) kt")
                DataRow(label: "Altitude", value: "\(Int(model.phaseDebug.altitudeMSL)) ft")
                DataRow(label: "Vertical Speed", value: "\(Int(model.phaseDebug.verticalSpeed)) fpm")
                if let d = model.phaseDebug.distanceToDestNM {
                    DataRow(label: "Dist to Dest", value: "\(Int(d)) NM")
                }
                if !model.phaseDebug.notes.isEmpty {
                    Text(model.phaseDebug.notes.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private var atisCard: some View {
        let d = model.atisDiagnostics
        func receipt(_ received: Bool, _ letter: String?) -> String {
            guard received else { return "Not available" }
            return "Received (\(letter.map { "info \($0)" } ?? "no code"))"
        }
        return Card(title: "ATIS", systemImage: "antenna.radiowaves.left.and.right") {
            VStack(alignment: .leading, spacing: 6) {
                DataRow(label: "Endpoint", value: diagnostics.atisEndpointStatus)
                DataRow(label: "Departure airport", value: d.departureAirport.isEmpty ? "—" : d.departureAirport)
                DataRow(label: "Departure ATIS", value: receipt(d.departureReceived, d.departureLetter))
                DataRow(label: "Reported (dep)", value: d.reportedDeparture.map { "Information \($0)" } ?? "—")
                DataRow(label: "Arrival airport", value: d.arrivalAirport.isEmpty ? "—" : d.arrivalAirport)
                DataRow(label: "Within 100 NM", value: d.withinArrivalRange ? "Yes" : "No")
                DataRow(label: "Arrival ATIS", value: receipt(d.arrivalReceived, d.arrivalLetter))
                DataRow(label: "Reported (arr)", value: d.reportedArrival.map { "Information \($0)" } ?? "—")
                Text("Real-world FAA D-ATIS (US airports, via datis.clowd.io). When a field has no D-ATIS, the ATIS button and information code simply don't appear — nothing is fabricated.")
                    .font(.caption2).foregroundStyle(.tertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var weatherStatusCard: some View {
        Card(title: "Weather Endpoint", systemImage: "cloud") {
            VStack(alignment: .leading, spacing: 4) {
                Text(diagnostics.weatherEndpointStatus).font(.subheadline)
                Text(model.weatherStatus).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var weatherDiagnosticsCard: some View {
        let d = model.weatherDiagnostics
        return Card(title: "Weather Diagnostics", systemImage: "cloud.rain") {
            VStack(alignment: .leading, spacing: 6) {
                DataRow(label: "Precip source", value: d.radarSource)
                DataRow(label: "Overlay coverage", value: d.coverageText)
                DataRow(label: "Last radar update", value: timeText(d.lastRadarUpdate))
                if let usage = d.radarDataUsageText {
                    DataRow(label: "Radar data (OPERA)", value: usage)
                }
                DataRow(label: "Last aviation wx update", value: timeText(d.lastAviationUpdate))
                DataRow(label: "Hazards detected", value: "\(d.hazardCount)")
                DataRow(label: "Sampled radar cells", value: model.sampledRadarCellSummary)
                DataRow(label: "Route conflict", value: d.routeConflictStatus)
                DataRow(label: "Rejoin fix", value: d.selectedRejoinFix ?? "—")
                DataRow(label: "Deviation state", value: d.lastDeviationState.rawValue)
                if let err = d.providerError {
                    Text("Provider error: \(err)").font(.caption).foregroundStyle(.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if let msg = d.coverageMessage {
                    Text(msg).font(.caption2).foregroundStyle(.tertiary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Divider().padding(.vertical, 2)
                Toggle("Show sampled cells on map", isOn: $model.showSampledRadarCells)
                    .font(.subheadline)
                Text("Draws the sampler's moderate-or-greater radar clusters as colored polygons on the Weather map, so you can check they line up with the radar returns. Clearest with the radar overlay turned off, where they sit on the plain map.")
                    .font(.caption2).foregroundStyle(.tertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func timeText(_ date: Date?) -> String {
        guard let date else { return "—" }
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f.string(from: date)
    }

    private var manifestCard: some View {
        Card(title: "Discovered States (\(diagnostics.discoveredStates.count))", systemImage: "list.bullet.rectangle") {
            if diagnostics.discoveredStates.isEmpty {
                Text(settings.mockMode ? "Manifest discovery runs in live mode." : "No manifest yet.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Resolved mappings: \(connect.mappingStore.resolved.count)")
                        .font(.caption).foregroundStyle(.secondary)
                    ForEach(diagnostics.discoveredStates.prefix(40)) { s in
                        HStack {
                            Text("[\(s.id)]").font(.caption.monospaced()).foregroundStyle(.secondary)
                            Text(s.name).font(.caption.monospaced()).lineLimit(1)
                            Spacer()
                            Text(s.type.shortName).font(.caption2).foregroundStyle(.tertiary)
                        }
                    }
                    if diagnostics.discoveredStates.count > 40 {
                        Text("…and \(diagnostics.discoveredStates.count - 40) more").font(.caption2).foregroundStyle(.tertiary)
                    }
                }
            }
        }
    }

    private var rawCard: some View {
        Card(title: "Last Raw Message (sanitized)", systemImage: "doc.plaintext") {
            Text(diagnostics.lastRawMessage.isEmpty ? "(none)" : diagnostics.lastRawMessage)
                .font(.caption.monospaced()).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var logCard: some View {
        Card(title: "Log (\(diagnostics.entries.count))", systemImage: "terminal") {
            VStack(spacing: 8) {
                HStack {
                    Spacer()
                    Button(role: .destructive) { diagnostics.clear() } label: {
                        Label("Clear", systemImage: "trash").font(.caption)
                    }
                    .buttonStyle(.bordered)
                }
                VStack(alignment: .leading, spacing: 3) {
                    ForEach(diagnostics.entries.suffix(60).reversed()) { e in
                        HStack(alignment: .top, spacing: 6) {
                            Text(e.category.rawValue)
                                .font(.caption2.monospaced().weight(.bold))
                                .foregroundStyle(.secondary)
                                .frame(width: 72, alignment: .leading)
                            Text(e.message).font(.caption2.monospaced())
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}
