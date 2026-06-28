import SwiftUI

@main
struct IFATCCompanionApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .environmentObject(model.settings)
                .environmentObject(model.diagnostics)
                .environmentObject(model.speech)
                .environmentObject(model.connect)
                .environmentObject(model.unicom)
                .environmentObject(model.mock)
                .environmentObject(model.phraseologyProfiles)
                .environmentObject(model.speechRecognizer)
                .preferredColorScheme(.dark)
                .tint(Color("AccentColor"))
                .onAppear { model.onAppear() }
        }
    }
}
