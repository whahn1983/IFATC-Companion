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

    // MARK: Radio voice effect
    //
    // When `AppSettings.transmissionStaticEnabled` is on, the main calls are routed
    // through `RadioVoiceProcessor` (band-pass + gentle soft-clip) so they sound like
    // radio transmissions, and the pilot's own calls are still bracketed with mic-key
    // static. This is the SAME toggle as the transmission static. When it's off, the
    // original clean-voice path (`synthesizer.speak`) is used, entirely unchanged.

    /// Renders/plays the radio-effected voice. Only active while the effect is enabled.
    private let radioVoice = RadioVoiceProcessor()
    /// A second synthesizer used only to render utterances to PCM buffers for the effect
    /// path (kept separate from `synthesizer`, which handles direct playback/fallback).
    private let writeSynth = AVSpeechSynthesizer()

    private struct ProcessedItem { let utterance: AVSpeechUtterance; let isPilot: Bool }
    /// Serial queue of calls awaiting effect processing, so they play in order and each
    /// pilot call's mic-key static lands exactly at its start.
    private var processedQueue: [ProcessedItem] = []
    private var pumpTask: Task<Void, Never>?
    /// True while the effect pump owns `isSpeaking` (so the delegate doesn't fight it).
    private var pumpActive = false
    /// Continuations for utterances the pump is playing via the *fallback* (unprocessed)
    /// path, resolved from the synthesizer delegate on finish/cancel.
    private var fallbackContinuations: [ObjectIdentifier: CheckedContinuation<Void, Never>] = [:]

    /// Whether the radio voice effect (and pilot mic-key static) is enabled right now.
    private var radioEffectEnabled: Bool { settings?.transmissionStaticEnabled ?? false }

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

        // With the radio effect enabled, route the call through the processor so it
        // sounds like a radio transmission; the pump also brackets the pilot's own calls
        // with mic-key static at the right moment. Otherwise use the original clean path.
        // The toggle is read live so turning it off takes effect on the next call.
        if radioEffectEnabled {
            enqueueProcessed(ProcessedItem(utterance: utterance, isPilot: isPilot))
        } else {
            synthesizer.speak(utterance)
        }
    }

    // MARK: - Radio-effect pump

    private func enqueueProcessed(_ item: ProcessedItem) {
        processedQueue.append(item)
        isSpeaking = true
        if pumpTask == nil {
            pumpTask = Task { [weak self] in await self?.runProcessedPump() }
        }
    }

    /// Play queued calls one at a time through the radio effect (falling back to the
    /// plain synthesizer for any voice that can't render to buffers, or if the effect
    /// engine won't start), bracketing pilot calls with mic-key static.
    private func runProcessedPump() async {
        pumpActive = true
        activatePlaybackSession()
        radioVoice.start()
        let effectAvailable = radioVoice.isRunning

        while !processedQueue.isEmpty {
            let item = processedQueue.removeFirst()

            // (The mic-key/un-key static bursts that used to bracket pilot calls here are
            // disabled for now — the toggle is just the radio voice grit.)
            var buffers: [AVAudioPCMBuffer] = []
            if effectAvailable { buffers = await renderToBuffers(item.utterance) }

            if !buffers.isEmpty {
                await radioVoice.play(buffers, volume: item.utterance.volume)
            } else {
                // Voice couldn't render to buffers (or no engine) — speak it plainly so
                // the call is never silent.
                await speakUnprocessedAndWait(item.utterance)
            }
        }

        pumpActive = false
        pumpTask = nil
        isSpeaking = false
        radioVoice.stop()
    }

    /// Render an utterance to PCM buffers on the dedicated write synthesizer. Returns an
    /// empty array if the voice can't be rendered (caller falls back to plain playback).
    ///
    /// A *fresh* utterance is rendered so the original stays pristine for the fallback
    /// path (an `AVSpeechUtterance` can't be handed to both `write` and `speak`), and it
    /// renders at full volume — the final level is set by the processor's mixer, so the
    /// voice-volume setting isn't applied twice.
    private func renderToBuffers(_ source: AVSpeechUtterance) async -> [AVAudioPCMBuffer] {
        let utterance = AVSpeechUtterance(string: source.speechString)
        utterance.voice = source.voice
        utterance.rate = source.rate
        utterance.pitchMultiplier = source.pitchMultiplier
        utterance.preUtteranceDelay = source.preUtteranceDelay
        utterance.postUtteranceDelay = source.postUtteranceDelay
        utterance.volume = 1

        return await withCheckedContinuation { (continuation: CheckedContinuation<[AVAudioPCMBuffer], Never>) in
            let lock = NSLock()
            var collected: [AVAudioPCMBuffer] = []
            var resumed = false
            func finish() {
                lock.lock()
                if resumed { lock.unlock(); return }
                resumed = true
                let snapshot = collected
                lock.unlock()
                continuation.resume(returning: snapshot)
            }
            writeSynth.write(utterance) { buffer in
                guard let pcm = buffer as? AVAudioPCMBuffer, pcm.frameLength > 0 else {
                    finish()
                    return
                }
                lock.lock(); collected.append(pcm); lock.unlock()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 15) { finish() }
        }
    }

    /// Speak an utterance on the main synthesizer and resolve when it finishes — the
    /// fallback playback path when the effect can't render a voice.
    private func speakUnprocessedAndWait(_ utterance: AVSpeechUtterance) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            fallbackContinuations[ObjectIdentifier(utterance)] = continuation
            synthesizer.speak(utterance)
        }
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
        writeSynth.stopSpeaking(at: .immediate)
        // Drop any queued effect work and unblock the pump so it can unwind.
        processedQueue.removeAll()
        radioVoice.stop()
        for (_, continuation) in fallbackContinuations { continuation.resume() }
        fallbackContinuations.removeAll()
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
        // While the effect pump is running it owns `isSpeaking`; don't fight it.
        Task { @MainActor in if !self.pumpActive { self.isSpeaking = true } }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.synthesizerFinished(utterance) }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.synthesizerFinished(utterance) }
    }
}

private extension SpeechService {
    /// A direct-synthesizer utterance finished (or was cancelled): resume the pump if it
    /// was a fallback playback, and clear `isSpeaking` when the pump isn't in control.
    func synthesizerFinished(_ utterance: AVSpeechUtterance) {
        if let continuation = fallbackContinuations.removeValue(forKey: ObjectIdentifier(utterance)) {
            continuation.resume()
        }
        if !pumpActive { isSpeaking = false }
    }
}
