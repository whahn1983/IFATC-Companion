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

    /// When true, always use the `.playback` category even if the user asked to respect
    /// the silent switch. Set while background chatter / Live Activity is enabled, where
    /// audible background audio is the whole point (and `.ambient` cannot play in the
    /// background). Owned by `AppModel`.
    var forcePlaybackForBackground = false

    /// Fires one short mic-key/un-key static burst. Wired to the radio engine so the
    /// pilot's own transmissions are bracketed with radio static. No-op when unset.
    var transmissionStatic: (() -> Void)?
    /// Whether to bracket pilot transmissions with `transmissionStatic`.
    var transmissionStaticEnabled = false

    /// Identities of in-flight pilot utterances, so the un-key burst fires when the
    /// matching utterance finishes.
    private var pilotUtterances = Set<ObjectIdentifier>()

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
        // Re-assert our playback session every time. Push-to-talk capture and system
        // sounds reconfigure the shared audio session (record category, ducking),
        // which otherwise leaves the synthesizer playing back at a reduced volume —
        // this keeps the spoken volume consistent across those interruptions.
        activatePlaybackSession()

        let isPilot = transmission.sender == .pilot
        let utterance = AVSpeechUtterance(string: transmission.spokenText)
        // ATIS is a one-way broadcast on its own configurable voice; otherwise the
        // pilot voice for own-ship calls, or the per-facility controller voice.
        if transmission.isATISLine {
            utterance.voice = atisVoice()
        } else {
            utterance.voice = isPilot ? pilotVoice() : voice(for: transmission.facility)
        }
        // Map our 0...1 setting onto AVSpeechUtterance's rate range.
        let rate = Float(settings.speechRate)
        utterance.rate = min(max(rate, AVSpeechUtteranceMinimumSpeechRate),
                             AVSpeechUtteranceMaximumSpeechRate)
        // Give the pilot a subtly distinct pitch so own-ship calls are easy to
        // tell apart from the controller even when they share a system voice.
        let basePitch = Float(min(max(settings.speechPitch, 0.5), 2.0))
        utterance.pitchMultiplier = isPilot ? min(max(basePitch * 0.92, 0.5), 2.0) : basePitch
        // Hold the spoken volume at the user's setting so it never drifts quiet.
        utterance.volume = Float(min(max(settings.voiceVolume, 0), 1))
        utterance.preUtteranceDelay = 0.05
        utterance.postUtteranceDelay = 0.1

        // Bracket the pilot's own transmissions with mic-key static: the key-up burst
        // fires from `didStart` (not here), so if the pilot readback is queued behind a
        // still-playing ATC call it plays when the pilot's voice actually begins — not
        // over the controller. The un-key burst fires from `didFinish`/`didCancel`.
        if isPilot, transmissionStaticEnabled, transmissionStatic != nil {
            pilotUtterances.insert(ObjectIdentifier(utterance))
        }

        synthesizer.speak(utterance)
    }

    /// Sample line spoken when auditioning a voice from Settings.
    static let voiceSampleLine =
        "Companion one, radar contact, climb and maintain flight level two four zero."

    /// Speak a short sample line in a specific voice so the user can audition it while
    /// picking a controller/pilot voice in Settings. Unlike `speak`, this is an
    /// explicit audition tap, so it plays even when `voiceEnabled` is off — but it
    /// still honours the configured volume, rate, pitch and silent-switch behaviour.
    /// An empty `identifier` previews whatever the default/system voice resolves to.
    func previewVoice(identifier: String, sample: String? = nil) {
        activatePlaybackSession()
        // Cut off any in-flight preview so rapid taps audition the latest pick
        // immediately instead of queueing up behind earlier ones.
        synthesizer.stopSpeaking(at: .immediate)

        let utterance = AVSpeechUtterance(string: sample ?? Self.voiceSampleLine)
        if !identifier.isEmpty, let v = AVSpeechSynthesisVoice(identifier: identifier) {
            utterance.voice = v
        } else if let id = settings?.defaultVoiceID, !id.isEmpty,
                  let v = AVSpeechSynthesisVoice(identifier: id) {
            utterance.voice = v
        } else {
            utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
        }
        let rate = Float(settings?.speechRate ?? Double(AVSpeechUtteranceDefaultSpeechRate))
        utterance.rate = min(max(rate, AVSpeechUtteranceMinimumSpeechRate),
                             AVSpeechUtteranceMaximumSpeechRate)
        utterance.pitchMultiplier = Float(min(max(settings?.speechPitch ?? 1.0, 0.5), 2.0))
        utterance.volume = Float(min(max(settings?.voiceVolume ?? 1.0, 0), 1))
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
        // Ramp shares the Ground voice (both work the surface); Clearance uses the
        // default controller voice.
        case .ramp: id = settings.voiceGround
        case .clearance: id = settings.defaultVoiceID
        }
        if !id.isEmpty, let v = AVSpeechSynthesisVoice(identifier: id) { return v }
        if !settings.defaultVoiceID.isEmpty,
           let v = AVSpeechSynthesisVoice(identifier: settings.defaultVoiceID) { return v }
        return AVSpeechSynthesisVoice(language: "en-US")
    }

    /// Voice for the one-way ATIS broadcast. Uses the dedicated ATIS voice setting,
    /// falling back to the default controller voice, then a system English voice.
    private func atisVoice() -> AVSpeechSynthesisVoice? {
        guard let settings else { return nil }
        if !settings.voiceATIS.isEmpty,
           let v = AVSpeechSynthesisVoice(identifier: settings.voiceATIS) { return v }
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

    /// Put the shared audio session back into the spoken-playback configuration and
    /// activate it. Called before every utterance so a prior push-to-talk recording
    /// session or a system sound can't leave playback ducked/quiet.
    private func activatePlaybackSession() {
        #if canImport(UIKit)
        let session = AVAudioSession.sharedInstance()
        do {
            // .playback ignores the silent switch; .ambient respects it. Background
            // chatter / Live Activity force .playback (audible background audio).
            let respectSilent = (settings?.respectSilentSwitch ?? false) && !forcePlaybackForBackground
            let category: AVAudioSession.Category = respectSilent ? .ambient : .playback
            try session.setCategory(category, mode: .spokenAudio, options: [.duckOthers])
            try session.setActive(true)
        } catch {
            // Non-fatal: speech may still work; surface nothing to the user.
        }
        #endif
    }
}

extension SpeechService: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        Task { @MainActor in self.startPilotTransmission(utterance); self.isSpeaking = true }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.finishPilotTransmission(utterance); self.isSpeaking = false }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.finishPilotTransmission(utterance); self.isSpeaking = false }
    }
}

private extension SpeechService {
    /// When a bracketed pilot transmission actually begins speaking (after any queued
    /// controller call has finished), fire the key-up static burst — so it lands at the
    /// start of the pilot's call rather than over the ATC call it was queued behind. The
    /// utterance stays tracked so the un-key burst still fires on finish.
    func startPilotTransmission(_ utterance: AVSpeechUtterance) {
        guard pilotUtterances.contains(ObjectIdentifier(utterance)) else { return }
        transmissionStatic?()
    }

    /// If the finished utterance was a bracketed pilot transmission, fire the un-key
    /// static burst and stop tracking it.
    func finishPilotTransmission(_ utterance: AVSpeechUtterance) {
        guard pilotUtterances.remove(ObjectIdentifier(utterance)) != nil else { return }
        transmissionStatic?()
    }
}
