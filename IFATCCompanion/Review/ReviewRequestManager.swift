import Foundation
import SwiftUI
import StoreKit

/// Coordinates App Store rating & review prompts, following Apple's guidance for
/// StoreKit's `requestReview` action:
///
/// * The system displays the prompt **at most three times per 365 days**, and may
///   decline to show it at all — a call is a *request*, not a command.
/// * Apple recommends asking only after the user has engaged with the app and
///   completed something meaningful, never immediately on launch, and never in the
///   middle of a task.
///
/// On top of the system limit this manager adds a conservative app-side gate so a
/// prompt only ever appears at the two calm, positive moments the product allows —
/// **before the pilot's first ATC call of a session**, or **after a completed
/// flight** — never during a flight, never on a brand-new install, and no more
/// often than once every few months.
///
/// The manager only *decides* when to ask; presenting the sheet is a SwiftUI
/// view-environment action (`\.requestReview`), so a view observes `promptToken`
/// and performs the actual request (see `View.reviewPrompt(_:)`).
@MainActor
final class ReviewRequestManager: ObservableObject {

    /// The two low-workload windows in which a prompt is permitted. Mirrors the
    /// product rule: never mid-flight.
    enum Trigger {
        /// Connected and idle at the gate, before the first ATC call of the session.
        case beforeFirstCall
        /// Arrived and parked — the flight is complete.
        case afterFlightComplete
    }

    /// Incremented whenever an eligible moment asks for the prompt. A view bound to
    /// this value calls the StoreKit `requestReview` action in response.
    @Published private(set) var promptToken = 0

    // MARK: - Tuning

    /// Completed flights required before the app ever asks — keeps brand-new users
    /// and first-launch sessions out of the prompt entirely.
    private let minimumCompletedFlights = 3
    /// Minimum age of the install before the first ask. Defensive (a fresh install
    /// also has zero completed flights), and honours Apple's "not on first launch".
    private let minimumInstallAge: TimeInterval = 3 * 86_400
    /// Minimum spacing between two prompts. ~120 days keeps us well inside Apple's
    /// three-per-year system limit and avoids nagging.
    private let minimumInterval: TimeInterval = 120 * 86_400
    /// App-side mirror of Apple's own hard cap (three per rolling 365 days), so a
    /// run of tightly spaced eligible moments can never overshoot it.
    private let maximumPromptsPerYear = 3
    private let oneYear: TimeInterval = 365 * 86_400

    // MARK: - Dependencies

    private let defaults: UserDefaults
    private let now: () -> Date

    init(defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init) {
        self.defaults = defaults
        self.now = now
        // Stamp the install date the first time the manager ever runs, so the
        // install-age gate has a baseline.
        if installDate == nil { installDate = now() }
    }

    // MARK: - Recording engagement

    /// Record a fully completed flight (arrived and parked). Drives the engagement
    /// gate; call exactly once per completed flight.
    func recordFlightCompleted() {
        completedFlights += 1
    }

    // MARK: - Requesting

    /// Ask the system to present the rating prompt if — and only if — every app-side
    /// gate passes. Safe to call at either allowed moment; it self-limits and is a
    /// no-op when not eligible.
    func requestReviewIfAppropriate(_ trigger: Trigger) {
        guard isEligible else { return }
        recordPromptRequested()
        promptToken &+= 1
    }

    // MARK: - Eligibility

    /// Whether all app-side gates currently permit a prompt. The system applies its
    /// own three-per-year limit on top of this.
    var isEligible: Bool {
        guard completedFlights >= minimumCompletedFlights else { return false }
        if let installed = installDate,
           now().timeIntervalSince(installed) < minimumInstallAge { return false }
        if let last = lastPromptDate,
           now().timeIntervalSince(last) < minimumInterval { return false }
        if promptsInLastYear >= maximumPromptsPerYear { return false }
        return true
    }

    private func recordPromptRequested() {
        let t = now()
        lastPromptDate = t
        recentPromptDates = recentPromptDates.filter { t.timeIntervalSince($0) < oneYear } + [t]
    }

    private var promptsInLastYear: Int {
        let t = now()
        return recentPromptDates.filter { t.timeIntervalSince($0) < oneYear }.count
    }

    // MARK: - Persistence (UserDefaults)

    private enum Key {
        static let installDate = "review.installDate"
        static let completedFlights = "review.completedFlights"
        static let lastPromptDate = "review.lastPromptDate"
        static let recentPromptDates = "review.recentPromptDates"
    }

    private(set) var installDate: Date? {
        get { defaults.object(forKey: Key.installDate) as? Date }
        set { defaults.set(newValue, forKey: Key.installDate) }
    }

    private(set) var completedFlights: Int {
        get { defaults.integer(forKey: Key.completedFlights) }
        set { defaults.set(newValue, forKey: Key.completedFlights) }
    }

    private var lastPromptDate: Date? {
        get { defaults.object(forKey: Key.lastPromptDate) as? Date }
        set { defaults.set(newValue, forKey: Key.lastPromptDate) }
    }

    private var recentPromptDates: [Date] {
        get { (defaults.array(forKey: Key.recentPromptDates) as? [Date]) ?? [] }
        set { defaults.set(newValue, forKey: Key.recentPromptDates) }
    }
}

// MARK: - View integration

/// Presents the system App Store review prompt whenever the manager signals an
/// eligible moment. `requestReview` is a view-environment action, so the trigger
/// has to live in the view layer; the manager only decides *when*.
private struct ReviewPromptModifier: ViewModifier {
    @ObservedObject var manager: ReviewRequestManager
    @Environment(\.requestReview) private var requestReview

    func body(content: Content) -> some View {
        content.onChange(of: manager.promptToken) { _, token in
            guard token > 0 else { return }
            requestReview()
        }
    }
}

extension View {
    /// Present the system App Store review prompt whenever `manager` signals an
    /// eligible moment. Attach once, high in the view tree.
    func reviewPrompt(_ manager: ReviewRequestManager) -> some View {
        modifier(ReviewPromptModifier(manager: manager))
    }
}
