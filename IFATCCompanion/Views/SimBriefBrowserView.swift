import SwiftUI
import SafariServices

/// Full-screen SimBrief dispatch, presented from the Flight view inside an in-app Safari
/// view (`SFSafariViewController`).
///
/// We deliberately use real Safari here instead of an embedded `WKWebView`. SimBrief's
/// page text fields — most visibly the Depart/Arrive airport-search boxes — only behave
/// correctly under Safari's own keyboard and focus handling. Embedding the page in a
/// `WKWebView` produced WebKit keyboard/focus races that could not be fixed from the app
/// side: on a direct tap from one field into another the keyboard would hop down and back,
/// and on the search fields it would drop and never return, leaving the page unusable. The
/// only in-page cure is JavaScript injected into SimBrief's page, which we do not do out of
/// respect for the site. `SFSafariViewController` sidesteps all of it — it is the same
/// engine and behavior the pilot gets opening SimBrief in Safari directly, and no script of
/// ours ever runs in the page.
///
/// Trade-off: unlike the previous embedded view (which kept the SimBrief session in the
/// app's own persistent data store), `SFSafariViewController` manages its own storage.
/// Persistent SimBrief login cookies still survive — and are shared with Safari — but a
/// session-only login may need to be re-entered a little more often. The pilot still builds
/// their flight plan here, loads it into Infinite Flight, and returns to refresh.
struct SimBriefBrowserView: UIViewControllerRepresentable {
    /// SimBrief dispatch entry point.
    private let url = URL(string: "https://dispatch.simbrief.com")!

    var onDone: () -> Void

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let controller = SFSafariViewController(url: url)
        controller.delegate = context.coordinator
        // Match the previous "Done" affordance for leaving the browser.
        controller.dismissButtonStyle = .done
        return controller
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    /// Bridges the Safari view's Done button back to the SwiftUI presenter so the
    /// full-screen cover dismisses.
    final class Coordinator: NSObject, SFSafariViewControllerDelegate {
        private let onDone: () -> Void

        init(onDone: @escaping () -> Void) { self.onDone = onDone }

        func safariViewControllerDidFinish(_ controller: SFSafariViewController) {
            onDone()
        }
    }
}
