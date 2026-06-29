import Foundation
import AVFoundation
import Combine

/// Wraps `AVSpeechSynthesizer` for offline ATC text-to-speech.
/// Per-facility voice selection, adjustable rate/pitch, and graceful audio-session
/// handling. Requires no network.
@MainActor
final class SpeechService: NSObject, ObservableObject {

    @Published private(set) var isSpeaking = false

    private let synthesizer = AVSpeechSynthesizer()
    private weak var settings: AppSettings?
    private var sessionConfigured = false

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func configure(settings: AppSettings) {
        self.settings = settings
    }

    /// Available installed voices (English first), for the Settings picker.
    nonisolated static func availableVoices() -> [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices()
            .sorted { lhs, rhs in
                if lhs.language == rhs.language { return lhs.name < rhs.name }
                let lEn = lhs.language.hasPrefix("en")
                let rEn = rhs.language.hasPrefix("en")
                if lEn != rEn { return lEn }
                return lhs.language < rhs.language
            }
    }

    func speak(_ transmission: ATCTransmission) {
        guard let settings, settings.voiceEnabled else { return }
        guard !transmission.spokenText.isEmpty else { return }
        configureAudioSessionIfNeeded()

        let isPilot = transmission.sender == .pilot
        let utterance = AVSpeechUtterance(string: transmission.spokenText)
        utterance.voice = isPilot ? pilotVoice() : voice(for: transmission.facility)
        // Map our 0...1 setting onto AVSpeechUtterance's rate range.
        let rate = Float(settings.speechRate)
        utterance.rate = min(max(rate, AVSpeechUtteranceMinimumSpeechRate),
                             AVSpeechUtteranceMaximumSpeechRate)
        // Give the pilot a subtly distinct pitch so own-ship calls are easy to
        // tell apart from the controller even when they share a system voice.
        let basePitch = Float(min(max(settings.speechPitch, 0.5), 2.0))
        utterance.pitchMultiplier = isPilot ? min(max(basePitch * 0.92, 0.5), 2.0) : basePitch
        utterance.preUtteranceDelay = 0.05
        utterance.postUtteranceDelay = 0.1

        synthesizer.speak(utterance)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        isSpeaking = false
    }

    func pause() {
        synthesizer.pauseSpeaking(at: .word)
    }

    func resume() {
        synthesizer.continueSpeaking()
    }

    // MARK: - Voice selection

    private func voice(for facility: ATCFacility) -> AVSpeechSynthesisVoice? {
        guard let settings else { return nil }
        let id: String
        switch facility {
        case .ground: id = settings.voiceGround
        case .tower: id = settings.voiceTower
        case .departure: id = settings.voiceDeparture
        case .center: id = settings.voiceCenter
        case .approach: id = settings.voiceApproach
        case .clearance, .unicom: id = settings.defaultVoiceID
        }
        if !id.isEmpty, let v = AVSpeechSynthesisVoice(identifier: id) { return v }
        if !settings.defaultVoiceID.isEmpty,
           let v = AVSpeechSynthesisVoice(identifier: settings.defaultVoiceID) { return v }
        return AVSpeechSynthesisVoice(language: "en-US")
    }

    /// Voice for the pilot's own transmissions. Falls back to a different system
    /// voice than the default controller voice so the two are distinguishable.
    private func pilotVoice() -> AVSpeechSynthesisVoice? {
        guard let settings else { return nil }
        if !settings.voicePilot.isEmpty,
           let v = AVSpeechSynthesisVoice(identifier: settings.voicePilot) { return v }
        if !settings.defaultVoiceID.isEmpty,
           let v = AVSpeechSynthesisVoice(identifier: settings.defaultVoiceID) { return v }
        return AVSpeechSynthesisVoice(language: "en-US")
    }

    // MARK: - Audio session

    private func configureAudioSessionIfNeeded() {
        guard !sessionConfigured else { return }
        sessionConfigured = true
        #if canImport(UIKit)
        let session = AVAudioSession.sharedInstance()
        do {
            let respectSilent = settings?.respectSilentSwitch ?? false
            // .playback ignores the silent switch; .ambient respects it.
            let category: AVAudioSession.Category = respectSilent ? .ambient : .playback
            try session.setCategory(category, mode: .spokenAudio, options: [.duckOthers])
            try session.setActive(true)
        } catch {
            // Non-fatal: speech may still work; surface nothing to the user.
            sessionConfigured = false
        }
        #endif
    }
}

extension SpeechService: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        Task { @MainActor in self.isSpeaking = true }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.isSpeaking = false }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.isSpeaking = false }
    }
}
