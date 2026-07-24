import SwiftUI
import AVFoundation

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var settings: AppSettings
    @EnvironmentObject var connect: IFConnectManager
    @EnvironmentObject var profiles: PhraseologyProfileStore
    @EnvironmentObject var entitlements: EntitlementManager
    @EnvironmentObject var speech: SpeechService
    @State private var showResetConfirm = false
    @State private var showSubscription = false
    @State private var osmCacheICAOs: [String] = []
    @State private var osmCacheBytes = 0

    private let voices = SpeechService.availableVoices()

    var body: some View {
        NavigationStack {
            Form {
                subscriptionSection
                connectionSection
                voiceSection
                facilityVoiceSection
                pilotVoiceSection
                phraseologySection
                automationSection
                sigmetPirepSection
                weatherDataSection
                dataSourcesSection
                aboutLegalSection
                etiquetteSection
                advancedSection
            }
            .navigationTitle("Settings")
            .scrollContentBackground(.visible)
            .task { await loadOSMCacheInfo() }
            .sheet(isPresented: $showSubscription) {
                SubscriptionView().environmentObject(entitlements)
            }
        }
    }

    // MARK: - Subscription

    private var subscriptionSection: some View {
        Section {
            Button {
                showSubscription = true
            } label: {
                HStack {
                    Label("Manage Subscription", systemImage: "crown")
                    Spacer()
                    Text(entitlements.statusText)
                        .font(.caption)
                        .foregroundStyle(entitlements.hasLiveAccess ? Color.green : .secondary)
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .tint(.primary)
            Button {
                Task { await entitlements.restorePurchases() }
            } label: {
                Label("Restore Purchases", systemImage: "arrow.clockwise")
            }
        } header: {
            Text("Subscription")
        } footer: {
            Text(entitlements.hasLiveAccess
                 ? "Live Connected Mode is active. Manage or cancel anytime in your Apple Account settings."
                 : "Mock Mode is free forever. Subscribe to unlock Live Connected Mode.")
        }
    }

    // MARK: - Connection

    private var liveLocked: Bool { !entitlements.hasLiveAccess }

    private var connectionSection: some View {
        Section("Infinite Flight Connection") {
            Toggle("Mock Mode (no Infinite Flight needed)", isOn: Binding(
                get: { settings.mockMode },
                set: { model.toggleMockMode($0) }))
                .disabled(liveLocked)
            if liveLocked {
                Text("Live Connected Mode requires an active subscription.")
                    .font(.caption).foregroundStyle(.secondary)
            }
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
            .disabled(liveLocked)
            HStack {
                Text("Port")
                Spacer()
                TextField("10112", value: $settings.port, format: .number.grouping(.never))
                    .multilineTextAlignment(.trailing)
                    .keyboardType(.numberPad)
                    .frame(maxWidth: 100)
            }
            .disabled(liveLocked)
            Toggle("Auto-discover on local network", isOn: $settings.autoDiscover)
                .disabled(liveLocked)
            Toggle("Keep screen awake", isOn: $settings.keepScreenAwake)
            if settings.keepScreenAwake {
                Text("Prevents the screen from locking, which would drop the Infinite Flight connection.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if !settings.mockMode && entitlements.hasLiveAccess {
                Button {
                    model.reconnect()
                } label: {
                    Label(connect.connectionState.isActive ? "Reconnect" : "Connect", systemImage: "antenna.radiowaves.left.and.right")
                }
                Text(connect.connectionState.detailedTitle).font(.caption).foregroundStyle(.secondary)
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
            voicePicker("ATIS", selection: $settings.voiceATIS)
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

    /// A voice row that pushes a dedicated, freely-scrollable picker screen. The
    /// stock `Picker`'s list style snaps the scroll back to the checked row on every
    /// re-render (auditioning a voice re-renders Settings), which made the list
    /// impossible to scroll; a plain list of buttons has no selection auto-scroll, so
    /// it scrolls normally and lets us audition the voice on tap.
    private func voicePicker(_ label: String, selection: Binding<String>) -> some View {
        NavigationLink {
            VoicePickerView(title: label, selection: selection, voices: voices) { id in
                speech.previewVoice(identifier: id)
            }
        } label: {
            HStack {
                Text(label)
                Spacer()
                Text(voiceDisplayName(for: selection.wrappedValue))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }

    /// Short name shown on the collapsed settings row for a selected voice id.
    private func voiceDisplayName(for id: String) -> String {
        guard !id.isEmpty, let v = voices.first(where: { $0.identifier == id }) else {
            return "System default"
        }
        return v.name
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
                Text("Initial climb: \(settings.initialClimbAltitudeFt) ft above field")
            }
            Stepper(value: $settings.traconCeilingFL, in: 80...240, step: 10) {
                Text("Departure → Center at FL\(settings.traconCeilingFL)")
            }
        } header: {
            Text("ATC Automation")
        } footer: {
            Text("You drive your own calls with the buttons — clearance, pushback, engine start, taxi and ready. The controller's position-based calls play automatically: takeoff clearance once you line up, the hand-off to Departure after you're airborne, and the en-route and arrival sequence. Read backs and check-ins stay manual. Initial climb is set above the field, so at high-elevation airports the callouts are raised to a valid MSL altitude (e.g. Denver's initial climb and the approach descent clear the ground).")
        }
    }

    // MARK: - SIGMET / PIREP

    private var sigmetPirepSection: some View {
        Section {
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
        } header: {
            Text("SIGMET / PIREP")
        } footer: {
            Text("Route corridor and altitude band set how close to your route — laterally and by altitude — a PIREP must be to count toward the ride report. SIGMETs are matched to your route by their area, so they aren't affected by these sliders. Neither affects the radar precipitation reroute, which uses its own corridor. Endpoint is the aviation weather data source (METAR, TAF, PIREP, SIGMET).")
        }
    }

    // MARK: - Weather Data (radar + simulated deviation)

    private var weatherDataSection: some View {
        Section {
            Picker("NOAA Radar Overlay", selection: Binding(
                get: { settings.noaaRadarOverlay },
                set: { settings.noaaRadarOverlay = $0; model.recomputeWeatherHazards() })) {
                ForEach(NOAARadarOverlayMode.allCases) { Text($0.title).tag($0) }
            }
            VStack(alignment: .leading) {
                Text("Radar opacity: \(Int((settings.radarOpacity * 100).rounded()))%")
                Slider(value: $settings.radarOpacity, in: 0.1...1.0)
            }
            Picker("Weather deviation alerts", selection: $settings.weatherDeviationAlerts) {
                ForEach(WeatherDeviationAlertMode.allCases) { Text($0.title).tag($0) }
            }
            Toggle(isOn: Binding(
                get: { settings.satelliteDeviationsEnabled },
                set: { settings.satelliteDeviationsEnabled = $0; model.applySatelliteDeviationSettingChange() })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Deviations from satellite estimate")
                    Text("Draw reroute lines around NASA global satellite precipitation where there's no radar (e.g. oceans, much of the world). Lower confidence, coarser and more latent than radar, and severity can't be graded reliably — always labeled “satellite estimate, not radar.”")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            Toggle("Show data-source labels", isOn: $settings.showWeatherDataSourceLabels)
            Toggle("Show coverage warnings", isOn: $settings.showWeatherCoverageWarnings)
            // The "Reduce cellular data" toggle is hidden while OPERA is disabled: its
            // only purpose was to suppress the megabyte-scale EUMETNET OPERA composite
            // downloads, and the remaining sources (NOAA/NASA) are small server-cropped
            // PNGs. Restore it alongside re-enabling OPERA in PrecipitationOverlayService.
            Label {
                Text("Precipitation overlay uses free sources: NOAA/NWS radar (U.S.), then a NASA global satellite estimate everywhere else — including Europe — which is not radar. No global radar coverage is implied. Simulation only — not for real-world aviation. No paid subscription, API key, or account required.")
                    .font(.footnote)
            } icon: {
                Image(systemName: "cloud.rain").foregroundStyle(.blue)
            }
        } header: {
            Text("Weather Data")
        }
    }

    // MARK: - Data Sources (OpenStreetMap airport surface)

    private var osmCrossingBinding: Binding<Bool> {
        Binding(get: { model.airportSurface.autoCrossingCalls },
                set: { model.airportSurface.autoCrossingCalls = $0 })
    }
    private var osmRecalcBinding: Binding<Bool> {
        Binding(get: { model.airportSurface.autoRecalculate },
                set: { model.airportSurface.autoRecalculate = $0 })
    }
    private var osmCacheSuffix: String {
        osmCacheICAOs.isEmpty ? "" : " (\(osmCacheICAOs.count), \(osmCacheBytes / 1024) KB)"
    }

    @MainActor private func loadOSMCacheInfo() async {
        let info = await model.airportSurface.cacheInfo()
        osmCacheICAOs = info.icaos
        osmCacheBytes = info.bytes
    }

    private var dataSourcesSection: some View {
        Section {
            HStack {
                Text("Airport surface")
                Spacer()
                Text("OpenStreetMap").foregroundStyle(.secondary)
            }
            HStack {
                Text("License")
                Spacer()
                Text(OSMSurface.licenseShortName).foregroundStyle(.secondary)
            }
            Link(destination: OSMSurface.copyrightURL) {
                Label(OSMSurface.attributionText, systemImage: "link")
            }
            HStack {
                Text("Overpass endpoint")
                Spacer()
                Text(OSMSurface.primaryOverpassEndpoint)
                    .font(.caption2).foregroundStyle(.secondary)
                    .lineLimit(1).truncationMode(.middle)
            }
            Toggle("Automatic runway-crossing calls", isOn: osmCrossingBinding)
            Toggle("Auto-recalculate when off route", isOn: osmRecalcBinding)
            Button {
                model.airportSurface.refreshData()
            } label: {
                Label("Refresh current airport data", systemImage: "arrow.clockwise")
            }
            Button(role: .destructive) {
                model.airportSurface.clearCache()
                Task { await loadOSMCacheInfo() }
            } label: {
                Label("Clear cached airport data\(osmCacheSuffix)", systemImage: "trash")
            }
        } header: {
            Text("Data Sources")
        } footer: {
            Text("Airport surface geometry and taxi routes are derived from OpenStreetMap (\(OSMSurface.licenseName)) via a public Overpass API. Community-sourced and best-effort — cached locally (~75 days) and refreshed on demand. Not authoritative and not guaranteed to match Infinite Flight scenery. Simulation only.")
        }
    }

    // MARK: - About / Legal

    private var aboutLegalSection: some View {
        Section {
            Link(destination: OSMSurface.copyrightURL) {
                Label(OSMSurface.attributionText, systemImage: "map")
            }
            Link(destination: OSMSurface.odblLicenseURL) {
                Label("Open Database License (ODbL) 1.0", systemImage: "doc.text")
            }
            Link(destination: OSMSurface.publicDocumentationURL) {
                Label("Airport data & licensing documentation", systemImage: "book")
            }
        } header: {
            Text("About & Legal")
        } footer: {
            Text("\(OSMSurface.simulationDisclaimer)\n\nOpenStreetMap® is open data licensed under the ODbL by the OpenStreetMap Foundation. IFATC Companion is not endorsed by or affiliated with OpenStreetMap or any Overpass operator.")
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
            Text("© 2026 H3 Consulting Partners LLC.")
        }
    }
}

// MARK: - Voice picker screen

/// A full-screen, freely-scrollable voice list. Each row selects the voice and
/// auditions it with a sample line so the user can hear the switch. Built from plain
/// buttons rather than a `Picker` so the scroll never snaps back to the checked row.
private struct VoicePickerView: View {
    let title: String
    @Binding var selection: String
    let voices: [AVSpeechSynthesisVoice]
    let onPreview: (String) -> Void

    var body: some View {
        List {
            Section {
                row(label: "System default", id: "")
            } footer: {
                Text("Tap a voice to select it and hear a sample line.")
            }
            Section("Installed voices") {
                ForEach(voices, id: \.identifier) { v in
                    row(label: "\(v.name) (\(v.language))", id: v.identifier)
                }
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func row(label: String, id: String) -> some View {
        Button {
            selection = id
            onPreview(id)
        } label: {
            HStack {
                Text(label)
                    .foregroundStyle(.primary)
                Spacer()
                if id == selection {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.tint)
                        .fontWeight(.semibold)
                }
            }
            .contentShape(Rectangle())
        }
    }
}
