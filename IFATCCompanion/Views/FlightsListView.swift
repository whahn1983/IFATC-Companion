import SwiftUI

/// The pilot's saved flights: start a new one, save the one in progress, or pick a
/// previous flight to carry on exactly where it was left.
///
/// Pushed from the ATC screen, so the system back button sits at the top left and the
/// New Flight button sits beside it.
struct FlightsListView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var settings: AppSettings
    @ObservedObject var store: SavedFlightStore
    @Environment(\.dismiss) private var dismiss

    /// A confirmation the pilot has yet to answer. Both destinations replace the
    /// session in progress, so both ask first.
    private enum PendingAction: Equatable {
        case newFlight
        case load(SavedFlight.ID)
    }
    @State private var pending: PendingAction?

    var body: some View {
        List {
            Section {
                if store.flights.isEmpty {
                    emptyState
                } else {
                    ForEach(store.flights) { flight in
                        Button { pending = .load(flight.id) } label: { row(for: flight) }
                            .buttonStyle(.plain)
                            // Mock Mode drives its own scripted feed, so a saved flight has
                            // nowhere to load into; the rows stay visible (and deletable).
                            .disabled(settings.mockMode)
                    }
                    .onDelete(perform: delete)
                }
            } footer: {
                if settings.mockMode {
                    Text("Saved flights are a Live Connected Mode feature — Mock Mode always starts a fresh demo flight from the gate.")
                }
            }
        }
        .navigationTitle("Flights")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Sits immediately to the right of the system back button.
            ToolbarItem(placement: .topBarLeading) {
                Button { pending = .newFlight } label: {
                    Image(systemName: "plus.circle")
                }
                .accessibilityLabel("New Flight")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Save") { model.saveCurrentFlight() }
                    .fontWeight(.semibold)
                    .disabled(!model.canSaveCurrentFlight)
            }
        }
        .confirmationDialog(dialogTitle,
                            isPresented: dialogPresented,
                            titleVisibility: .visible,
                            presenting: pending) { action in
            dialogButtons(for: action)
        } message: { action in
            Text(dialogMessage(for: action))
        }
    }

    // MARK: - Rows

    private func row(for flight: SavedFlight) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(flight.name)
                        .font(.body.weight(.semibold))
                    if flight.id == store.activeFlightID { flyingBadge }
                }
                Text("\(flight.stateTitle) · \(flight.facilityTitle)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Text(flight.savedAt.formatted(.relative(presentation: .named)))
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }

    /// Marks the flight the live session is currently flying — the one the auto-save
    /// keeps up to date.
    private var flyingBadge: some View {
        Text("Flying")
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Capsule().fill(Color.accentColor.opacity(0.22)))
            .foregroundStyle(Color.accentColor)
    }

    private var emptyState: some View {
        Text("No saved flights yet. Tap Save to put the flight you're on now into this list — load it back later and the app returns exactly as you left it: transcript, frequency, clearances and plan.")
            .font(.footnote)
            .foregroundStyle(.secondary)
    }

    private func delete(at offsets: IndexSet) {
        // Resolve to flights first: each delete mutates the array the offsets index into.
        let doomed = offsets.compactMap { store.flights.indices.contains($0) ? store.flights[$0] : nil }
        for flight in doomed { model.deleteSavedFlight(flight) }
    }

    // MARK: - Confirmations

    private var dialogPresented: Binding<Bool> {
        Binding(get: { pending != nil }, set: { if !$0 { pending = nil } })
    }

    private var dialogTitle: String {
        guard let pending else { return "Start a new flight?" }
        switch pending {
        case .newFlight: return "Start a new flight?"
        case .load: return "Load this flight?"
        }
    }

    private func flight(for action: PendingAction) -> SavedFlight? {
        guard case let .load(id) = action else { return nil }
        return store.flight(id: id)
    }

    /// Whether starting a new flight would retire the finished one from the list, and
    /// under what name.
    private var retiredByNewFlight: String? { model.savedFlightRetiredByClearing }

    @ViewBuilder
    private func dialogButtons(for action: PendingAction) -> some View {
        // Saving first is offered — and listed first — whenever the session in progress
        // would otherwise be lost, so the safe choice is the easy one. Not offered when
        // starting a new flight after a finished one: that retires it either way.
        if model.hasUnsavedFlight, model.canSaveCurrentFlight,
           !(action == .newFlight && model.flightIsComplete) {
            Button(action == .newFlight ? "Save & Start New" : "Save & Load") {
                model.saveCurrentFlight()
                perform(action)
            }
        }
        switch action {
        case .newFlight:
            Button("Start New Flight", role: .destructive) { perform(action) }
        case .load:
            Button("Load Flight") { perform(action) }
        }
        Button("Cancel", role: .cancel) {}
    }

    private func dialogMessage(for action: PendingAction) -> String {
        var parts: [String] = []
        // The endpoint mismatch leads: it is the one thing the pilot may not have noticed.
        if let flight = flight(for: action), let mismatch = model.endpointMismatch(with: flight) {
            parts.append(mismatch)
        }
        if action == .newFlight, let retired = retiredByNewFlight {
            parts.append("The flight you're on is complete, so “\(retired)” will be removed from your saved flights.")
        } else if model.hasUnsavedFlight {
            parts.append("The flight you're on now hasn't been saved and will be lost.")
        }
        switch action {
        case .newFlight:
            parts.append("Starting a new flight clears the conversation and begins again from the gate. Your settings and flight plan are kept.")
        case .load:
            parts.append("Loading brings back that flight's transcript, clearances, frequency and plan. Your position and the weather update from Infinite Flight on the next reading.")
        }
        return parts.joined(separator: " ")
    }

    private func perform(_ action: PendingAction) {
        switch action {
        case .newFlight:
            model.clearFlight()
        case .load:
            guard let flight = flight(for: action) else { return }
            model.loadSavedFlight(flight)
        }
        pending = nil
        dismiss()
    }
}
