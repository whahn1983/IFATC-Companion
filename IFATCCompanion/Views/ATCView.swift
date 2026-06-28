import SwiftUI

struct ATCView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var settings: AppSettings
    @EnvironmentObject var connect: IFConnectManager
    @EnvironmentObject var unicom: UNICOMAutomationService
    @EnvironmentObject var speech: SpeechService

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    statusHeader
                    currentTransmissionCard
                    unicomCard
                    responseButtons
                    transcriptCard
                }
                .padding(16)
            }
            .navigationTitle("ATC Companion")
            .navigationBarTitleDisplayMode(.inline)
            .screenBackground()
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if speech.isSpeaking {
                        Button { speech.stop() } label: { Image(systemName: "stop.circle.fill") }
                    }
                }
            }
        }
    }

    // MARK: - Header

    private var connectionLevel: StatusLevel {
        if settings.mockMode { return .amber }
        if connect.connectionState.isConnected { return .green }
        if connect.connectionState.isActive { return .amber }
        return .red
    }

    private var connectionText: String {
        settings.mockMode ? "Mock Mode" : connect.connectionState.title
    }

    private var statusHeader: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    StatusPill(text: connectionText, level: connectionLevel, systemImage: "dot.radiowaves.left.and.right")
                    Spacer()
                    StatusPill(text: model.currentFacility.title, level: .neutral, systemImage: model.currentFacility.symbol)
                }
                HStack {
                    headerStat(title: "Callsign", value: callsignDisplay, image: "airplane")
                    Divider().frame(height: 34)
                    headerStat(title: "Airport", value: nearestAirport, image: "mappin.and.ellipse")
                }
                HStack {
                    headerStat(title: "Phase", value: model.phase.title, image: "flag.checkered")
                    Divider().frame(height: 34)
                    headerStat(title: "Assigned", value: assignedAltText, image: "arrow.up.arrow.down")
                }
            }
        }
    }

    private func headerStat(title: String, value: String, image: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Label(title, systemImage: image).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.headline).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var callsignDisplay: String {
        if !settings.callsign.isEmpty { return settings.callsign }
        let cs = model.flightPlan
        if !cs.airline.isEmpty && !cs.flightNumber.isEmpty { return "\(cs.airline) \(cs.flightNumber)" }
        return connect.liveCallsign ?? "—"
    }

    private var nearestAirport: String {
        model.aircraftState.nearestAirport ?? (model.flightPlan.departure.isEmpty ? "—" : model.flightPlan.departure)
    }

    private var assignedAltText: String {
        model.assignedAltitude > 0 ? PhraseologyEngine().formatAltDisplay(model.assignedAltitude) : "—"
    }

    // MARK: - Current transmission

    private var currentTransmissionCard: some View {
        Card(title: "Current Transmission", systemImage: "waveform") {
            if let tx = model.latestTransmission {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        StatusPill(text: tx.facility.title, level: .green, systemImage: tx.facility.symbol)
                        Spacer()
                        Button { speech.speak(tx) } label: {
                            Label("Replay", systemImage: "speaker.wave.2.fill")
                        }
                        .buttonStyle(.borderless)
                    }
                    Text(tx.displayText)
                        .font(.title3.weight(.semibold))
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                Text("Awaiting first transmission…")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - UNICOM

    private var unicomCard: some View {
        Card(title: "UNICOM", systemImage: "antenna.radiowaves.left.and.right") {
            VStack(alignment: .leading, spacing: 10) {
                Text(unicom.statusText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let pending = unicom.pending {
                    Text(pending.message).font(.body.weight(.medium))
                    if !pending.isAvailable {
                        Text("Automation not available for this event — broadcast manually in Infinite Flight.")
                            .font(.caption).foregroundStyle(.orange)
                    }
                    HStack {
                        Button {
                            unicom.sendPending()
                        } label: { Label("Send", systemImage: "paperplane.fill") }
                            .buttonStyle(.borderedProminent)
                            .disabled(!pending.isAvailable)
                        Button(role: .cancel) { unicom.skipPending() } label: { Label("Skip", systemImage: "xmark") }
                            .buttonStyle(.bordered)
                    }
                }
                Text("UNICOM announces your own intentions only. This is not staffed ATC.")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
        }
    }

    // MARK: - Response buttons

    private var responseButtons: some View {
        Card(title: "Responses", systemImage: "hand.tap") {
            VStack(spacing: 10) {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                    ActionButton(title: "Read Back", systemImage: "checkmark.circle", tint: .green) { model.readBack() }
                    ActionButton(title: "Say Again", systemImage: "arrow.uturn.left") { model.sayAgain() }
                    ActionButton(title: "Unable", systemImage: "xmark.octagon", tint: .red) { model.unable() }
                    ActionButton(title: "Request Higher", systemImage: "arrow.up") { model.requestHigher() }
                    ActionButton(title: "Request Lower", systemImage: "arrow.down") { model.requestLower() }
                    ActionButton(title: "Vectors", systemImage: "arrow.triangle.turn.up.right.diamond") { model.requestVectors() }
                    ActionButton(title: "Approach", systemImage: "airplane.arrival") { model.requestApproach() }
                    ActionButton(title: "Ride Report", systemImage: "wind") { model.requestRideReport() }
                    ActionButton(title: "Dest Wx", systemImage: "cloud.sun") { model.requestDestinationWeather() }
                    ActionButton(title: "Check In", systemImage: "person.wave.2") { model.requestHandoff() }
                }
                pttPlaceholder
            }
        }
    }

    private var pttPlaceholder: some View {
        Button { } label: {
            Label("Push to Talk (coming soon)", systemImage: "mic.slash")
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .tint(.secondary)
        .disabled(true)
    }

    // MARK: - Transcript

    private var transcriptCard: some View {
        Card(title: "Transcript", systemImage: "text.bubble") {
            if model.transcript.isEmpty {
                Text("No messages yet.").foregroundStyle(.secondary)
            } else {
                VStack(spacing: 8) {
                    ForEach(model.transcript.reversed()) { tx in
                        TranscriptRow(tx: tx)
                    }
                }
            }
        }
    }
}

struct TranscriptRow: View {
    let tx: ATCTransmission

    private var isPilot: Bool { tx.sender == .pilot }

    var body: some View {
        HStack(alignment: .top) {
            if isPilot { Spacer(minLength: 40) }
            VStack(alignment: isPilot ? .trailing : .leading, spacing: 3) {
                Text(senderLabel)
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(isPilot ? Color.accentColor : .secondary)
                Text(tx.displayText)
                    .font(.callout)
                    .multilineTextAlignment(isPilot ? .trailing : .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(10)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(isPilot ? Color.accentColor.opacity(0.18) : Color(.tertiarySystemBackground))
            )
            if !isPilot { Spacer(minLength: 40) }
        }
    }

    private var senderLabel: String {
        switch tx.sender {
        case .pilot: return "PILOT"
        case .atc: return tx.facility.title.uppercased()
        case .system: return "SYSTEM"
        }
    }
}
