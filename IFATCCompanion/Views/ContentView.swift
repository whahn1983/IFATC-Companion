import SwiftUI

struct ContentView: View {
    var body: some View {
        TabView {
            ATCView()
                .tabItem { Label("ATC", systemImage: "antenna.radiowaves.left.and.right") }
            FlightView()
                .tabItem { Label("Flight", systemImage: "airplane") }
            WeatherView()
                .tabItem { Label("Weather", systemImage: "cloud.sun") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
            DiagnosticsView()
                .tabItem { Label("Diagnostics", systemImage: "stethoscope") }
        }
    }
}

#Preview {
    let model = AppModel()
    return ContentView()
        .environmentObject(model)
        .environmentObject(model.settings)
        .environmentObject(model.diagnostics)
        .environmentObject(model.speech)
        .environmentObject(model.connect)
        .environmentObject(model.mock)
        .environmentObject(model.phraseologyProfiles)
        .environmentObject(model.speechRecognizer)
        .preferredColorScheme(.dark)
}
