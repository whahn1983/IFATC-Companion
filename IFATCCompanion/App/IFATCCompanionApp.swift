import SwiftUI

@main
struct IFATCCompanionApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .environmentObject(model.settings)
                .environmentObject(model.diagnostics)
                .environmentObject(model.speech)
                .environmentObject(model.connect)
                .environmentObject(model.mock)
                .environmentObject(model.phraseologyProfiles)
                .environmentObject(model.speechRecognizer)
                .environmentObject(model.entitlements)
                .preferredColorScheme(.dark)
                .tint(Color("AccentColor"))
                // Presents the system App Store rating prompt when `model.review`
                // signals an eligible moment (before the first call / after a clear).
                .reviewPrompt(model.review)
                .onAppear {
                    model.onAppear()
                    #if canImport(UIKit)
                    DispatchQueue.main.async {
                        UIApplication.shared.installKeyboardDismissTapGesture()
                    }
                    #endif
                }
                .onChange(of: scenePhase) { _, newPhase in
                    // Force a reconnect whenever the app returns from the background so
                    // live flight details resume updating without a manual Reconnect.
                    switch newPhase {
                    case .background: model.markBackgrounded()
                    case .active: model.handleReturnToForeground()
                    default: break
                    }
                }
        }
    }
}
