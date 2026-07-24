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
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle("SimBrief")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Done") { onDone() }
                    }
                    ToolbarItemGroup(placement: .bottomBar) {
                        Button { web.goBack() } label: {
                            Image(systemName: "chevron.left")
                        }
                        .disabled(!web.canGoBack)
                        Spacer()
                        Button { web.goForward() } label: {
                            Image(systemName: "chevron.right")
                        }
                        .disabled(!web.canGoForward)
                        Spacer()
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

        // Auto-dismiss SimBrief's "Add to Home Screen" install banner. Its permanent
        // "Don't show this again" link is pinned to the bottom of the page, underneath
        // our navigation toolbar, so it can't be tapped by hand. Since the persistent
        // data store keeps SimBrief's saved preference, one automatic click stops the
        // banner from reappearing on future launches.
        let controller = WKUserContentController()
        controller.addUserScript(
            WKUserScript(source: Self.dismissInstallBannerJS,
                         injectionTime: .atDocumentEnd,
                         forMainFrameOnly: true)
        )
        config.userContentController = controller

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true

        model.webView = webView
        webView.load(URLRequest(url: url))
        return webView
    }

    /// Finds SimBrief's "Don't show this again" control and clicks it. The banner is
    /// injected after page load, so this retries briefly and also watches for late DOM
    /// insertions. Text is normalised (lowercased, whitespace and apostrophes stripped)
    /// so straight vs. curly apostrophes don't matter.
    private static let dismissInstallBannerJS = """
    (function() {
      var done = false;
      function norm(s) { return (s || '').toLowerCase().replace(/[\\s'’]+/g, ''); }
      function tryDismiss() {
        if (done) return;
        var els = document.querySelectorAll('a, button, span, p, div');
        for (var i = 0; i < els.length; i++) {
          var el = els[i];
          if (el.children.length) continue; // leaf text nodes only
          if (norm(el.textContent).indexOf('dontshowthisagain') !== -1) {
            (el.closest('a, button') || el).click();
            done = true;
            return;
          }
        }
      }
      tryDismiss();
      var n = 0;
      var timer = setInterval(function() {
        tryDismiss();
        if (done || ++n > 60) clearInterval(timer);
      }, 500);
      try {
        new MutationObserver(tryDismiss).observe(
          document.documentElement, { childList: true, subtree: true }
        );
      } catch (e) {}
    })();
    """

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
