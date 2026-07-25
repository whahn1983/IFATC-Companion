import SwiftUI
import WebKit

/// Full-screen embedded browser for SimBrief dispatch, presented from the Flight view.
///
/// Uses the shared persistent `WKWebsiteDataStore` so cookies and cache are written to
/// disk: the pilot signs in to SimBrief once and stays logged in across app relaunches.
/// The intent is that a pilot builds their SimBrief flight plan, loads it straight into
/// Infinite Flight from here, and then returns to refresh from Infinite Flight.
struct SimBriefBrowserView: View {
    /// SimBrief dispatch entry point.
    private let url = URL(string: "https://dispatch.simbrief.com")!

    var onDone: () -> Void

    @StateObject private var web = SimBriefWebModel()

    var body: some View {
        NavigationStack {
            SimBriefWebView(url: url, model: web)
                // Let WKWebView own its keyboard insets. Without this, tapping a page
                // text field (e.g. SimBrief's Depart/Arrive boxes) makes SwiftUI's
                // automatic keyboard avoidance shrink/shift the full-screen web view at
                // the same time WKWebView scrolls the focused field above the keyboard.
                // The two layout passes fight and leave the web view's touch hit-testing
                // misaligned, so it appears frozen until another focus change re-runs the
                // layout. Ignoring the keyboard safe area hands keyboard handling to
                // WKWebView alone (native Safari-style behavior) and removes the freeze.
                .ignoresSafeArea(.keyboard, edges: .bottom)
                .navigationTitle("SimBrief")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Done") { onDone() }
                    }
                    // Navigation controls live in the top bar so nothing overlaps the
                    // bottom of the page — SimBrief's "Add to Home Screen" banner and
                    // its "Don't show this again" link stay reachable and tappable.
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button { web.goBack() } label: {
                            Image(systemName: "chevron.left")
                        }
                        .disabled(!web.canGoBack)
                        Button { web.goForward() } label: {
                            Image(systemName: "chevron.right")
                        }
                        .disabled(!web.canGoForward)
                        Button { web.reload() } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
                .overlay(alignment: .top) {
                    if web.isLoading {
                        ProgressView()
                            .progressViewStyle(.linear)
                            .frame(maxWidth: .infinity)
                    }
                }
        }
    }
}

/// Bridges `WKWebView` navigation state to the SwiftUI toolbar and exposes the
/// back/forward/reload actions the toolbar buttons trigger.
final class SimBriefWebModel: ObservableObject {
    @Published var canGoBack = false
    @Published var canGoForward = false
    @Published var isLoading = false

    fileprivate weak var webView: WKWebView?

    func goBack() { webView?.goBack() }
    func goForward() { webView?.goForward() }
    func reload() { webView?.reload() }
}

/// A `WKWebView` wrapper backed by the persistent default data store so the SimBrief
/// session survives relaunches. New-window navigations (used by the SimBrief/Navigraph
/// sign-in flow) are kept inside this same web view instead of being dropped.
struct SimBriefWebView: UIViewRepresentable {
    let url: URL
    @ObservedObject var model: SimBriefWebModel

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        // Persistent (disk-backed) store: cookies + cache are retained between launches,
        // so the pilot's SimBrief login stays valid. `.default()` is already persistent;
        // set explicitly to make that guarantee obvious.
        config.websiteDataStore = .default()

        // When the on-screen keyboard opens for a page text field and then closes, WebKit
        // can leave the page's visual viewport and its `position:fixed` elements (SimBrief's
        // sticky header sits directly above the Flight Info fields) shifted down by roughly
        // the keyboard height, and it does not always snap back. Once that happens, the spot
        // a field is *drawn* no longer matches the spot that receives the tap: tapping
        // "Flight Number" lands on the offset header instead, so the field shows a tap
        // highlight but never actually focuses and the previously-entered "Depart" box stays
        // active. Force WebKit to recompute layout whenever a field loses focus (which is
        // exactly the moment the keyboard is dismissed or focus moves to another field) with
        // a zero-delta scroll — a no-op for the user that reliably re-syncs the viewport.
        let reflowSource = """
        (function() {
            var resync = function() {
                requestAnimationFrame(function() {
                    window.scrollTo(window.scrollX, window.scrollY);
                });
            };
            document.addEventListener('focusout', resync, true);
        })();
        """
        let reflowScript = WKUserScript(source: reflowSource,
                                        injectionTime: .atDocumentEnd,
                                        forMainFrameOnly: true)
        config.userContentController.addUserScript(reflowScript)

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        // Let the pilot dismiss the keyboard by dragging the page, so leaving a focused
        // Depart/Arrive field never depends on finding somewhere else to tap.
        webView.scrollView.keyboardDismissMode = .interactive
        // The web view is already framed correctly below the navigation bar, so let WebKit
        // manage its own keyboard scrolling instead of layering an automatic content inset
        // on top. The automatic inset is the piece that gets stuck after the keyboard hides
        // and drags the page's touch coordinates out of alignment with what's drawn.
        webView.scrollView.contentInsetAdjustmentBehavior = .never

        model.webView = webView
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let model: SimBriefWebModel

        init(model: SimBriefWebModel) { self.model = model }

        private func sync(_ webView: WKWebView) {
            model.canGoBack = webView.canGoBack
            model.canGoForward = webView.canGoForward
            model.isLoading = webView.isLoading
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            sync(webView)
        }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            sync(webView)
        }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            sync(webView)
        }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            sync(webView)
        }

        /// Keep `target="_blank"` / popup navigations (SimBrief + Navigraph auth) in the
        /// same web view rather than silently discarding them.
        func webView(_ webView: WKWebView,
                     createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView? {
            if navigationAction.targetFrame == nil {
                webView.load(navigationAction.request)
            }
            return nil
        }
    }
}
