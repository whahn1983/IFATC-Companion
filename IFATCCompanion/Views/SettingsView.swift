import SwiftUI
import AVFoundation

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var settings: AppSettings
    @EnvironmentObject var connect: IFConnectManager
    @EnvironmentObject var profiles: PhraseologyProfileStore
    @State private var showResetConfirm = false

    private let voices = SpeechService.availableVoices()

    var body: some View {
        NavigationStack {
            Form {
                connectionSection
                voiceSection
                facilityVoiceSection
                pilotVoiceSection
                phraseologySection
                automationSection
                weatherSection
                etiquetteSection
                advancedSection
            }
            .navigationTitle("Settings")
            .scrollContentBackground(.visible)
        }
    }

    // MARK: - Connection

    private var connectionSection: some View {
        Section("Infinite Flight Connection") {
            Toggle("Mock Mode (no Infinite Flight needed)", isOn: Binding(
                get: { settings.mockMode },
                set: { model.toggleMockMode($0) }))
            HStack {
                Text("Host / IP")
                Spacer()
                TextField("192.168.1.20", text: $settings.host)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .keyboardType(.numbersAndPunctuation)
                    .frame(maxWidth: 180)
            }
            HStack {
                Text("Port")
                Spacer()
                TextField("10112", value: $settings.port, format: .number.grouping(.never))
                    .multilineTextAlignment(.trailing)
                    .keyboardType(.numberPad)
                    .frame(maxWidth: 100)
            }
            Toggle("Auto-discover on local network", isOn: $settings.autoDiscover)
            Toggle("Keep screen awake", isOn: $settings.keepScreenAwake)
            if settings.keepScreenAwake {
                Text("Prevents the screen from locking, which would drop the Infinite Flight connection.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if !settings.mockMode {
                Button {
                    model.reconnect()
                } label: {
                    Label(connect.connectionState.isActive ? "Reconnect" : "Connect", systemImage: "antenna.radiowaves.left.and.right")
                }
                Text(connect.connectionState.title).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Voice

    private var voiceSection: some View {
        Section("Voice") {
            Toggle("Voice enabled", isOn: $settings.voiceEnabled)
            voicePicker("Default voice", selection: $settings.defaultVoiceID)
            VStack(alignment: .leading) {
                Text("Volume: \(Int(settings.voiceVolume * 100))%")
                Slider(value: $settings.voiceVolume, in: 0...1)
            }
            VStack(alignment: .leading) {
                Text("Speech rate: \(String(format: "%.2f", settings.speechRate))")
                Slider(value: $settings.speechRate,
                       in: Double(AVSpeechUtteranceMinimumSpeechRate)...Double(AVSpeechUtteranceMaximumSpeechRate))
            }
            VStack(alignment: .leading) {
                Text("Pitch: \(String(format: "%.2f", settings.speechPitch))")
                Slider(value: $settings.speechPitch, in: 0.5...2.0)
            }
            Toggle("Respect silent switch", isOn: $settings.respectSilentSwitch)
        }
    }

    private var facilityVoiceSection: some View {
        Section("Controller Voice per Facility") {
            voicePicker("Ground", selection: $settings.voiceGround)
            voicePicker("Tower", selection: $settings.voiceTower)
            voicePicker("Departure", selection: $settings.voiceDeparture)
            voicePicker("Center", selection: $settings.voiceCenter)
            voicePicker("Approach", selection: $settings.voiceApproach)
        }
    }

    private var pilotVoiceSection: some View {
        Section {
            Toggle("Speak pilot readbacks", isOn: $settings.speakPilot)
            voicePicker("Pilot voice", selection: $settings.voicePilot)
        } header: {
            Text("Pilot Voice")
        } footer: {
            Text("Speaks your readbacks and requests aloud when you use the buttons. Push-to-talk input is never repeated.")
        }
    }

    private func voicePicker(_ label: String, selection: Binding<String>) -> some View {
        Picker(label, selection: selection) {
            Text("System default").tag("")
            ForEach(voices, id: \.identifier) { v in
                Text("\(v.name) (\(v.language))").tag(v.identifier)
            }
        }
    }

    // MARK: - Phraseology

    private var phraseologySection: some View {
        Section {
            Picker("Mode", selection: $settings.phraseologyMode) {
                ForEach(PhraseologyMode.allCases) { Text($0.title).tag($0) }
            }
            Text(settings.phraseologyMode.detail)
                .font(.caption).foregroundStyle(.secondary)
            Picker("Flight number style", selection: $settings.digitStyle) {
                ForEach(CallsignDigitStyle.allCases) { Text($0.title).tag($0) }
            }
            NavigationLink {
                PhraseologyProfilesView()
            } label: {
                Label("Custom Profiles\(activeProfileSuffix)", systemImage: "text.badge.star")
            }
        } header: {
            Text("Phraseology")
        }
    }

    private var activeProfileSuffix: String {
        profiles.activeProfile.map { " — \($0.name)" } ?? ""
    }

    // MARK: - ATC Automation

    private var automationSection: some View {
        Section {
            Stepper(value: $settings.initialClimbAltitudeFt, in: 2000...10000, step: 1000) {
                Text("Initial climb: \(settings.initialClimbAltitudeFt) ft")
            }
            Stepper(value: $settings.traconCeilingFL, in: 80...240, step: 10) {
                Text("Departure → Center at FL\(settings.traconCeilingFL)")
            }
        } header: {
            Text("ATC Automation")
        } footer: {
            Text("You drive your own calls with the buttons — clearance, pushback, engine start, taxi and ready. The controller's position-based calls play automatically: takeoff clearance once you line up, the hand-off to Departure after you're airborne, and the en-route and arrival sequence. Read backs and check-ins stay manual.")
        }
    }

    // MARK: - Weather

    private var weatherSection: some View {
        Section("Weather") {
            VStack(alignment: .leading) {
                Text("Route corridor: \(Int(settings.routeCorridorNM)) NM")
                Slider(value: $settings.routeCorridorNM, in: 25...250, step: 25)
            }
            VStack(alignment: .leading) {
                Text("Altitude band: ±\(Int(settings.altitudeBandFt)) ft")
                Slider(value: $settings.altitudeBandFt, in: 1000...10000, step: 1000)
            }
            HStack {
                Text("Endpoint")
                Spacer()
                TextField("base URL", text: $settings.weatherBaseURL)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .font(.caption)
            }
        }
    }

    // MARK: - Etiquette

    private var etiquetteSection: some View {
        Section("Multiplayer Etiquette") {
            Label {
                Text("IFATC Companion is **not** staffed ATC and must not impersonate live controllers. Always yield to real controllers when a frequency is staffed.")
                    .font(.footnote)
            } icon: {
                Image(systemName: "exclamationmark.shield").foregroundStyle(.orange)
            }
        }
    }

    // MARK: - Advanced

    private var advancedSection: some View {
        Section {
            Toggle("Debug logging", isOn: $settings.debugLogging)
            Text("Units: feet / nautical miles / knots")
                .font(.caption).foregroundStyle(.secondary)
            Button(role: .destructive) {
                showResetConfirm = true
            } label: {
                Label("Reset App Data", systemImage: "trash")
            }
            .confirmationDialog("Reset all settings and transcript?", isPresented: $showResetConfirm, titleVisibility: .visible) {
                Button("Reset", role: .destructive) { model.resetAppData() }
                Button("Cancel", role: .cancel) {}
            }
        } header: {
            Text("Advanced")
        } footer: {
            Text("IFATC Companion v1.0 — local-only, no accounts, no analytics, no AI.\n© 2026 H3 Consulting Partners LLC.")
        }
    }
}
