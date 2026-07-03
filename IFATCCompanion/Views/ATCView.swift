import SwiftUI
import Speech

struct ATCView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var settings: AppSettings
    @EnvironmentObject var connect: IFConnectManager
    @EnvironmentObject var speech: SpeechService
    @EnvironmentObject var recognizer: SpeechRecognitionService
    @EnvironmentObject var entitlements: EntitlementManager

    @State private var showClearFlightConfirm = false
    @State private var showSubscription = false
    @FocusState private var callsignFocused: Bool
    @FocusState private var departureGateFocused: Bool
    @FocusState private var arrivalGateFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    if !entitlements.hasLiveAccess { subscribeBanner }
                    statusHeader
                    if model.companionStandby { standbyBanner }
                    currentTransmissionCard
                    frequencyCard
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
            .sheet(isPresented: $showSubscription) {
                SubscriptionView().environmentObject(entitlements)
            }
        }
    }

    // MARK: - Subscribe banner

    /// Compact upsell shown at the top of the ATC view only while the user has no
    /// active subscription. Tapping it opens the subscription screen. Hidden
    /// entirely once Live Connected Mode is unlocked.
    private var subscribeBanner: some View {
        Button {
            showSubscription = true
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "lock.fill")
                Text("Live Mode locked — Subscribe")
                    .font(.subheadline.weight(.semibold))
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.caption)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.accentColor.opacity(0.18))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Color.accentColor.opacity(0.5), lineWidth: 1)
            )
            .foregroundStyle(Color.accentColor)
        }
        .buttonStyle(.plain)
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
                    callsignField
                    Divider().frame(height: 34)
                    headerStat(title: "Airport", value: nearestAirport, image: "mappin.and.ellipse")
                }
                HStack {
                    gateField(title: "Dep Gate", systemImage: "figure.walk.departure",
                              text: $settings.departureGate, placeholder: "C12",
                              focused: $departureGateFocused)
                    Divider().frame(height: 34)
                    gateField(title: "Arr Gate", systemImage: "figure.walk.arrival",
                              text: $settings.arrivalGate, placeholder: "B44",
                              focused: $arrivalGateFocused)
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

    /// Editable callsign entry, surfaced on the main page. Infinite Flight's Connect
    /// API exposes no callsign for the user's own aircraft, so it is entered here
    /// rather than buried in the Flight tab's overrides. Applies on submit / when
    /// editing ends so the ATC phraseology picks it up.
    private var callsignField: some View {
        VStack(alignment: .leading, spacing: 2) {
            Label("Callsign", systemImage: "airplane").font(.caption).foregroundStyle(.secondary)
            TextField(callsignPlaceholder, text: $settings.callsign)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .focused($callsignFocused)
                .onSubmit { applyCallsign() }
                .onChange(of: callsignFocused) { _, focused in
                    if !focused { model.applyManualCallsign() }
                }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Editable departure/arrival gate entry, surfaced on the main page so the pilot
    /// can set the gate without diving into the Flight tab's manual overrides.
    /// Infinite Flight exposes no gate/stand, so these are pilot-entered; they feed
    /// the pushback request and the arrival Ramp taxi-to-gate instruction. Applies on
    /// submit / when editing ends so the phraseology picks the change up immediately.
    private func gateField(title: String, systemImage: String, text: Binding<String>,
                           placeholder: String, focused: FocusState<Bool>.Binding) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Label(title, systemImage: systemImage).font(.caption).foregroundStyle(.secondary)
            TextField(placeholder, text: text)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .focused(focused)
                .onSubmit { focused.wrappedValue = false; model.applyManualGates() }
                .onChange(of: focused.wrappedValue) { _, isFocused in
                    if !isFocused { model.applyManualGates() }
                }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Placeholder shows a callsign resolved from the Airline/Flight # overrides (if
    /// any) so the field isn't blank when the pilot used those fields instead.
    private var callsignPlaceholder: String {
        let cs = model.flightPlan
        if !cs.airline.isEmpty && !cs.flightNumber.isEmpty { return "\(cs.airline) \(cs.flightNumber)" }
        return "Set callsign"
    }

    private func applyCallsign() {
        callsignFocused = false
        model.applyManualCallsign()
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
                    ForEach(tunableFacilities) { facility in
                        FrequencyButton(title: facility.title,
                                        systemImage: facility.symbol,
                                        frequency: model.frequencyText(for: facility),
                                        active: model.currentFacility == facility,
                                        enabled: model.canTune(facility)) {
                            model.tuneTo(facility)
                        }
                    }
                    if model.canContactRamp {
                        FrequencyButton(title: "Ramp",
                                        systemImage: "parkingsign",
                                        frequency: model.frequencyText(for: .ramp),
                                        active: model.currentFacility == .ramp,
                                        enabled: true) {
                            // Tuning only moves the radio — like every other frequency
                            // button. The actual call (pushback / taxi-to-gate) is made
                            // afterwards from the Responses card.
                            model.tuneTo(.ramp)
                        }
                    }
                }
                Text("Only the controllers you need now are shown. Tap one to change frequency, then tap Check In to call them or make a request. You drive every frequency change.")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
        }
    }

    /// The frequency buttons worth showing right now — the current controller plus
    /// the next one ahead — so the page isn't cluttered with every facility.
    private var tunableFacilities: [ATCFacility] {
        AppModel.tunableFacilities.filter { model.relevantFacilities.contains($0) }
    }

    // MARK: - Response buttons

    private var responseButtons: some View {
        Card(title: "Responses", systemImage: "hand.tap") {
            VStack(spacing: 10) {
                let actions = orderedActions.filter { model.availableActions.contains($0) }
                if actions.isEmpty {
                    Text(model.companionStandby
                         ? "Follow the live controller."
                         : "No requests right now — read back or wait for the next call.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    LazyVGrid(columns: gridColumns, spacing: 10) {
                        ForEach(actions, id: \.self) { actionButton(for: $0) }
                    }
                }
                acknowledgementGrid
                pttPlaceholder
            }
        }
    }

    private var gridColumns: [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: 10), count: 3)
    }

    /// Canonical display order for the response buttons (gate-to-gate, then the
    /// enroute/arrival requests). The grid renders whichever of these are currently
    /// available for the tuned controller and phase.
    private var orderedActions: [PilotAction] {
        [.clearance, .pushback, .engineStart, .taxi, .ready, .takeoff,
         .toGate, .checkIn, .requestHigher, .requestLower, .vectors, .approach,
         .rideReport, .destWx]
    }

    /// Map a pilot action to its labelled button wired to the model.
    @ViewBuilder
    private func actionButton(for action: PilotAction) -> some View {
        switch action {
        case .clearance:
            ActionButton(title: "Clearance", systemImage: "doc.text") { model.requestClearance() }
        case .pushback:
            ActionButton(title: "Pushback", systemImage: "arrow.left.to.line") { model.requestPushback() }
        case .engineStart:
            ActionButton(title: "Engine Start", systemImage: "powerplug") { model.requestEngineStart() }
        case .taxi:
            ActionButton(title: "Taxi", systemImage: "car") { model.requestTaxi() }
        case .ready:
            ActionButton(title: "Ready", systemImage: "flag.checkered") { model.reportReadyForDeparture() }
        case .takeoff:
            ActionButton(title: "Takeoff", systemImage: "airplane.departure", tint: .green) { model.requestTakeoff() }
        case .requestHigher:
            ActionButton(title: "Request Higher", systemImage: "arrow.up") { model.requestHigher() }
        case .requestLower:
            ActionButton(title: "Request Lower", systemImage: "arrow.down") { model.requestLower() }
        case .vectors:
            ActionButton(title: "Vectors", systemImage: "arrow.triangle.turn.up.right.diamond") { model.requestVectors() }
        case .approach:
            ActionButton(title: "Approach", systemImage: "airplane.arrival") { model.requestApproach() }
        case .rideReport:
            ActionButton(title: "Ride Report", systemImage: "wind") { model.requestRideReport() }
        case .destWx:
            ActionButton(title: "Dest Wx", systemImage: "cloud.sun") { model.requestDestinationWeather() }
        case .checkIn:
            ActionButton(title: "Check In", systemImage: "person.wave.2") { model.requestHandoff() }
        case .toGate:
            ActionButton(title: "To Gate", systemImage: "parkingsign") { model.contactRamp() }
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
