import SwiftUI
import Speech

struct ATCView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var settings: AppSettings
    @EnvironmentObject var connect: IFConnectManager
    @EnvironmentObject var unicom: UNICOMAutomationService
    @EnvironmentObject var speech: SpeechService
    @EnvironmentObject var recognizer: SpeechRecognitionService

    @State private var showClearFlightConfirm = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    statusHeader
                    if model.liveATC.humanControllerActive { standbyBanner }
                    currentTransmissionCard
                    frequencyCard
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
                ToolbarItem(placement: .topBarLeading) {
                    Button(role: .destructive) {
                        showClearFlightConfirm = true
                    } label: {
                        Label("Clear Flight", systemImage: "arrow.counterclockwise")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if speech.isSpeaking {
                        Button { speech.stop() } label: { Image(systemName: "stop.circle.fill") }
                    }
                }
            }
            .confirmationDialog("Clear this flight?",
                                isPresented: $showClearFlightConfirm,
                                titleVisibility: .visible) {
                Button("Clear Flight", role: .destructive) { model.clearFlight() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Resets the conversation and starts a new flight from the gate. Your settings and flight plan are kept.")
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

    // MARK: - Human ATC standby

    private var standbyBanner: some View {
        Card {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "person.fill.checkmark")
                    .font(.title3)
                    .foregroundStyle(.orange)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Human ATC Active")
                        .font(.headline)
                    Text("\(model.liveATC.summary) Follow the live controller.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
        }
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
                        Text("Infinite Flight has no Connect send command for this call — tap it in Infinite Flight's UNICOM menu.")
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

    // MARK: - Frequency tuning

    /// Manual frequency-change buttons: the pilot taps a controller to switch to
    /// its frequency. Tuning no longer checks in automatically — afterwards the
    /// pilot taps Check In to call the controller, or makes a specific request.
    /// Tapping any of these hands control of the flight's progression to the pilot,
    /// so calls no longer auto-play one after another.
    private var frequencyCard: some View {
        Card(title: "Tune Frequency", systemImage: "dial.medium") {
            VStack(alignment: .leading, spacing: 10) {
                LazyVGrid(columns: gridColumns, spacing: 10) {
                    ForEach(AppModel.tunableFacilities) { facility in
                        FrequencyButton(title: facility.title,
                                        systemImage: facility.symbol,
                                        frequency: model.frequencyText(for: facility),
                                        active: model.currentFacility == facility,
                                        enabled: model.canTune(facility)) {
                            model.tuneTo(facility)
                        }
                    }
                    FrequencyButton(title: "Ramp",
                                    systemImage: "parkingsign",
                                    frequency: "Parking",
                                    active: model.atcState == .parked,
                                    enabled: model.atcState != .parked) {
                        model.arriveAtGate()
                    }
                }
                Text("Tap a controller to change frequency. Then tap Check In to call them, or make a request. You drive every frequency change.")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
        }
    }

    // MARK: - Response buttons

    private var responseButtons: some View {
        Card(title: "Responses", systemImage: "hand.tap") {
            VStack(spacing: 10) {
                if model.isPreDeparture {
                    departureGroundGrid
                } else {
                    enrouteGrid
                }
                acknowledgementGrid
                pttPlaceholder
            }
        }
    }

    private var gridColumns: [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: 10), count: 3)
    }

    /// Pilot-driven pre-departure flow, in order, so no phase is skipped.
    private var departureGroundGrid: some View {
        LazyVGrid(columns: gridColumns, spacing: 10) {
            ActionButton(title: "Clearance", systemImage: "doc.text") { model.requestClearance() }
            ActionButton(title: "Pushback", systemImage: "arrow.left.to.line") { model.requestPushback() }
            ActionButton(title: "Engine Start", systemImage: "powerplug") { model.requestEngineStart() }
            ActionButton(title: "Taxi", systemImage: "car") { model.requestTaxi() }
            ActionButton(title: "Ready", systemImage: "flag.checkered") { model.reportReadyForDeparture() }
            ActionButton(title: "Takeoff", systemImage: "airplane.departure", tint: .green) { model.requestTakeoff() }
        }
    }

    /// Enroute / arrival requests.
    private var enrouteGrid: some View {
        LazyVGrid(columns: gridColumns, spacing: 10) {
            ActionButton(title: "Request Higher", systemImage: "arrow.up") { model.requestHigher() }
            ActionButton(title: "Request Lower", systemImage: "arrow.down") { model.requestLower() }
            ActionButton(title: "Vectors", systemImage: "arrow.triangle.turn.up.right.diamond") { model.requestVectors() }
            ActionButton(title: "Approach", systemImage: "airplane.arrival") { model.requestApproach() }
            ActionButton(title: "Ride Report", systemImage: "wind") { model.requestRideReport() }
            ActionButton(title: "Dest Wx", systemImage: "cloud.sun") { model.requestDestinationWeather() }
            ActionButton(title: "Check In", systemImage: "person.wave.2") { model.requestHandoff() }
        }
    }

    /// Always-available acknowledgements.
    private var acknowledgementGrid: some View {
        LazyVGrid(columns: gridColumns, spacing: 10) {
            ActionButton(title: "Read Back", systemImage: "checkmark.circle", tint: .green) { model.readBack() }
            ActionButton(title: "Say Again", systemImage: "arrow.uturn.left") { model.sayAgain() }
            ActionButton(title: "Unable", systemImage: "xmark.octagon", tint: .red) { model.unable() }
        }
    }

    @ViewBuilder
    private var pttPlaceholder: some View {
        VStack(spacing: 6) {
            pushToTalkButton
            if recognizer.isListening {
                Text(recognizer.partialText.isEmpty ? "Listening…" : recognizer.partialText)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if !model.lastSpokenText.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: "quote.bubble").font(.caption)
                    Text("\"\(model.lastSpokenText)\"")
                        .lineLimit(1)
                    if let intent = model.lastSpokenIntent {
                        Spacer()
                        Text(intent.title).font(.caption.weight(.semibold))
                            .foregroundStyle(intent == .unknown ? .orange : .green)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            if recognizer.authorization == .denied || recognizer.authorization == .restricted {
                Text("Enable Speech Recognition & Microphone in Settings to use push-to-talk.")
                    .font(.caption2).foregroundStyle(.orange)
            } else if let err = recognizer.lastError {
                Text(err).font(.caption2).foregroundStyle(.orange)
            }
        }
    }

    private var pushToTalkButton: some View {
        Label(recognizer.isListening ? "Listening — release to send" : "Hold to Talk",
              systemImage: recognizer.isListening ? "mic.fill" : "mic")
            .frame(maxWidth: .infinity, minHeight: 48)
            .contentShape(Rectangle())
            .padding(.vertical, 4)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill((recognizer.isListening ? Color.red : Color.accentColor).opacity(0.18)))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke((recognizer.isListening ? Color.red : Color.accentColor).opacity(0.5), lineWidth: 1))
            .foregroundStyle(recognizer.isListening ? Color.red : Color.accentColor)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in if !recognizer.isListening { recognizer.startListening() } }
                    .onEnded { _ in recognizer.stopListening() }
            )
            .accessibilityHint("Press and hold to speak a readback or request.")
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
