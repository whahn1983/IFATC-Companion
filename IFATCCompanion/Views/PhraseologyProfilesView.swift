import SwiftUI

/// Lists user-created phraseology profiles and lets the pilot create, edit,
/// activate, import, and export them. Fully local; profiles share as JSON text.
struct PhraseologyProfilesView: View {
    @EnvironmentObject var profiles: PhraseologyProfileStore
    @State private var importText = ""
    @State private var showImport = false
    @State private var importError = false

    var body: some View {
        Form {
            Section {
                Picker("Active profile", selection: Binding(
                    get: { profiles.activeProfileID },
                    set: { profiles.activeProfileID = $0 })) {
                    Text("Built-in (none)").tag(UUID?.none)
                    ForEach(profiles.profiles) { p in
                        Text(p.name).tag(UUID?.some(p.id))
                    }
                }
            } footer: {
                Text("The active profile overrides matching controller calls and airline radio names. Built-in uses the selected FAA/ICAO pack only.")
            }

            Section("Profiles") {
                if profiles.profiles.isEmpty {
                    Text("No custom profiles yet.").foregroundStyle(.secondary)
                }
                ForEach(profiles.profiles) { profile in
                    NavigationLink {
                        PhraseologyProfileEditor(profile: profile)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(profile.name)
                            Text("\(profile.templates.count) template(s), \(profile.airlineCallSets.count) airline(s)")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .onDelete { indexSet in
                    indexSet.map { profiles.profiles[$0] }.forEach { profiles.delete($0) }
                }
            }

            Section {
                Button {
                    let p = profiles.createNew()
                    profiles.activeProfileID = p.id
                } label: { Label("New Profile", systemImage: "plus.circle") }

                Button {
                    example()
                } label: { Label("Add Example Profile", systemImage: "wand.and.stars") }

                Button {
                    showImport = true
                } label: { Label("Import from JSON", systemImage: "square.and.arrow.down") }
            }
        }
        .navigationTitle("Phraseology Profiles")
        .toolbar { EditButton() }
        .alert("Import Profile", isPresented: $showImport) {
            TextField("Paste profile JSON", text: $importText)
            Button("Import") {
                if profiles.importJSON(importText) == nil { importError = true }
                importText = ""
            }
            Button("Cancel", role: .cancel) { importText = "" }
        } message: {
            Text("Paste a profile's exported JSON to add it.")
        }
        .alert("Import failed", isPresented: $importError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("That text could not be read as a valid phraseology profile.")
        }
    }

    private func example() {
        let p = PhraseologyProfile.example()
        profiles.add(p)
    }
}

/// Edits a single profile: its name, per-call templates, and airline call set.
struct PhraseologyProfileEditor: View {
    @EnvironmentObject var profiles: PhraseologyProfileStore
    @State private var draft: PhraseologyProfile
    @State private var newAirlineKey = ""
    @State private var newAirlineName = ""

    init(profile: PhraseologyProfile) {
        _draft = State(initialValue: profile)
    }

    var body: some View {
        Form {
            Section("Name") {
                TextField("Profile name", text: $draft.name)
            }

            Section {
                ForEach(PhraseologyTemplateKey.allCases) { key in
                    templateRow(for: key)
                }
            } header: {
                Text("Call Templates")
            } footer: {
                Text("Toggle a call on to override it. Use {placeholder} tokens shown under each field.")
            }

            airlineSection
        }
        .navigationTitle("Edit Profile")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ShareLink(item: profiles.exportJSON(draft)) {
                    Image(systemName: "square.and.arrow.up")
                }
            }
        }
        .onDisappear { profiles.update(draft) }
    }

    private func templateRow(for key: PhraseologyTemplateKey) -> some View {
        let isOn = draft.templates[key.rawValue] != nil
        return DisclosureGroup {
            if let template = draft.templates[key.rawValue] {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Placeholders: \(key.placeholders.map { "{\($0)}" }.joined(separator: " "))")
                        .font(.caption2).foregroundStyle(.secondary)
                    Text("Transcript text").font(.caption).foregroundStyle(.secondary)
                    TextField("Display", text: Binding(
                        get: { template.display },
                        set: { draft.templates[key.rawValue]?.display = $0 }), axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                    Text("Spoken text").font(.caption).foregroundStyle(.secondary)
                    TextField("Spoken", text: Binding(
                        get: { template.spoken },
                        set: { draft.templates[key.rawValue]?.spoken = $0 }), axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                }
            }
        } label: {
            Toggle(key.title, isOn: Binding(
                get: { isOn },
                set: { on in
                    if on { draft.templates[key.rawValue] = key.defaultTemplate }
                    else { draft.templates[key.rawValue] = nil }
                }))
        }
    }

    private var airlineSection: some View {
        Section {
            ForEach(draft.airlineCallSets.sorted(by: { $0.key < $1.key }), id: \.key) { pair in
                HStack {
                    Text(pair.key).font(.body.monospaced())
                    Spacer()
                    Text(pair.value).foregroundStyle(.secondary)
                }
            }
            .onDelete { indexSet in
                let keys = draft.airlineCallSets.sorted(by: { $0.key < $1.key }).map { $0.key }
                indexSet.forEach { draft.airlineCallSets[keys[$0]] = nil }
            }
            HStack {
                TextField("Code", text: $newAirlineKey)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .frame(maxWidth: 90)
                TextField("Spoken name", text: $newAirlineName)
                Button {
                    let key = newAirlineKey.uppercased().trimmingCharacters(in: .whitespaces)
                    let name = newAirlineName.trimmingCharacters(in: .whitespaces)
                    guard !key.isEmpty, !name.isEmpty else { return }
                    draft.airlineCallSets[key] = name
                    newAirlineKey = ""; newAirlineName = ""
                } label: { Image(systemName: "plus.circle.fill") }
                    .buttonStyle(.borderless)
            }
        } header: {
            Text("Airline Call Set")
        } footer: {
            Text("Map an airline code (used in your flight plan) to its spoken radio name, e.g. DLH → Lufthansa.")
        }
    }
}
