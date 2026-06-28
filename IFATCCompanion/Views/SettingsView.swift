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
                phraseologySection
                unicomSection
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

    // MARK: - UNICOM

    private var unicomSection: some View {
        Section("UNICOM Automation") {
            Picker("Mode", selection: Binding(
                get: { model.currentUnicomMode },
                set: { settings.unicomModeRaw = $0.rawValue })) {
                ForEach(UNICOMMode.allCases) { Text($0.title).tag($0) }
            }
            Text("Preview-then-send shows each broadcast for confirmation. Auto-send only sends routine, trusted events.")
                .font(.caption).foregroundStyle(.secondary)
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
                Text("IFATC Companion is **not** staffed ATC and must not impersonate live controllers. UNICOM actions announce your own pilot intentions only. Always yield to real controllers when a frequency is staffed.")
                    .font(.footnote)
            } icon: {
                Image(systemName: "exclamationmark.shield").foregroundStyle(.orange)
            }
        }
    }

    // MARK: - Advanced

    private var advancedSection: some View {
        Section("Advanced") {
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
        } footer: {
            Text("IFATC Companion v1.0 — local-only, no accounts, no analytics, no AI.\n© 2026 H3 Consulting Partners LLC.")
        }
    }
}
