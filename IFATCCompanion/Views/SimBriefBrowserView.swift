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

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true

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
