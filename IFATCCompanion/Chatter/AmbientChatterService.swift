import Foundation
import AVFoundation
import Combine

/// Drives the ambient background radio chatter: it decides *when* to transmit (paced by
/// the chosen density), asks `ChatterScriptGenerator` for a frequency-appropriate
/// exchange, synthesizes each line to audio with a natural English voice, and plays it
/// through `RadioAudioEngine`'s radio-effect chain.
///
/// It is also the app's **background-audio anchor**: while it is running it keeps a
/// `.playback` session active and a continuous static bed hissing, so iOS keeps the
/// process alive — which is what lets the Infinite Flight poll loop and the Live
/// Activity keep updating while the app is backgrounded.
///
/// The service ducks the chatter under real ATC calls, pauses for push-to-talk, and
/// also provides the short mic-key/un-key static bursts that bracket the pilot's own
/// transmissions.
@MainActor
final class AmbientChatterService: ObservableObject {

    @Published private(set) var isRunning = false

    private let radio = RadioAudioEngine()
    private let chatterSynth = AVSpeechSynthesizer()
    /// A separate synthesizer used only for the direct fallback path (a voice that can't
    /// render to buffers), spoken quietly straight to the session.
    private let fallbackSynth = AVSpeechSynthesizer()

    private weak var settings: AppSettings?
    private var generator = ChatterScriptGenerator()

    /// The frequency the pilot is tuned to right now — supplied by `AppModel` so the
    /// chatter always matches the position.
    private var facilityProvider: () -> ATCFacility = { .center }
    /// User-chosen voice for a given controller position (empty when unset).
    private var facilityVoiceID: (ATCFacility) -> String? = { _ in nil }
    private var pilotVoiceID: () -> String? = { nil }

    private var voicePool: [AVSpeechSynthesisVoice] = []
    private var loopTask: Task<Void, Never>?
    private var idleStopTask: Task<Void, Never>?

    private var ducked = false
    private var pausedForPTT = false
    private var sessionConfigured = false

    // MARK: - Setup

    func configure(settings: AppSettings) {
        self.settings = settings
        refreshConfig()
        // AVFoundation posts these on arbitrary threads; deliver on the main queue and
        // hop onto the main actor before touching any engine/published state.
        NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                .flatMap(AVAudioSession.InterruptionType.init(rawValue:))
            Task { @MainActor in self?.onInterruption(type) }
        }
        NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.onConfigChange() }
        }
    }

    /// Supply the live context providers (called once from `AppModel`).
    func bindContext(facility: @escaping () -> ATCFacility,
                     facilityVoiceID: @escaping (ATCFacility) -> String?,
                     pilotVoiceID: @escaping () -> String?) {
        self.facilityProvider = facility
        self.facilityVoiceID = facilityVoiceID
        self.pilotVoiceID = pilotVoiceID
    }

    /// Pull volume/density/phraseology/voice-pool from settings. Call when they change.
    func refreshConfig() {
        guard let settings else { return }
        generator.mode = settings.phraseologyMode
        generator.digitStyle = settings.digitStyle
        radio.setChatterLevel(Float(settings.chatterVolume))
        rebuildVoicePool()
    }

    private func rebuildVoicePool() {
        guard let settings else { voicePool = VoiceCatalog.englishHumanVoices(); return }
        let chosen = [settings.voiceGround, settings.voiceTower, settings.voiceDeparture,
                      settings.voiceCenter, settings.voiceApproach, settings.voicePilot,
                      settings.defaultVoiceID]
        voicePool = VoiceCatalog.chatterVoicePool(userChosenIDs: chosen)
    }

    // MARK: - Lifecycle

    /// Start the continuous chatter (the background anchor). Idempotent.
    func start() {
        guard !isRunning else { return }
        idleStopTask?.cancel(); idleStopTask = nil
        activateSession()
        refreshConfig()
        radio.start()
        radio.setDucked(ducked)
        isRunning = true
        loopTask?.cancel()
        loopTask = Task { [weak self] in await self?.runLoop() }
    }

    func stop() {
        loopTask?.cancel(); loopTask = nil
        idleStopTask?.cancel(); idleStopTask = nil
        chatterSynth.stopSpeaking(at: .immediate)
        radio.stop()
        isRunning = false
    }

    /// Duck (or restore) the chatter under a real ATC call.
    func setDucked(_ ducked: Bool) {
        self.ducked = ducked
        radio.setDucked(ducked)
    }

    /// Pause/resume around push-to-talk so the chatter never bleeds into the mic and the
    /// recording session can take over the audio route.
    func pauseForPTT() {
        pausedForPTT = true
        chatterSynth.stopSpeaking(at: .immediate)
        radio.stop()
    }

    func resumeAfterPTT() {
        pausedForPTT = false
        guard isRunning else { return }
        activateSession()
        radio.start()
        radio.setDucked(ducked)
    }

    // MARK: - Transmission static (mic key / un-key)

    /// Play one short static burst to bracket the pilot's own transmission. Works even
    /// when the continuous chatter is off — the engine is started transiently and stopped
    /// again after a short idle window.
    func transmissionStaticBurst() {
        if isRunning {
            radio.playSquelch()
            return
        }
        guard !pausedForPTT else { return }
        activateSession()
        if !radio.isRunning { radio.start() }
        radio.playSquelch()
        armIdleStop()
    }

    private func armIdleStop() {
        idleStopTask?.cancel()
        idleStopTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard let self, !Task.isCancelled, !self.isRunning else { return }
            self.radio.stop()
        }
    }

    // MARK: - Scheduling loop

    private func runLoop() async {
        while !Task.isCancelled, isRunning {
            if pausedForPTT {
                try? await Task.sleep(nanoseconds: 400_000_000)
                continue
            }
            let facility = facilityProvider()
            var rng = SystemRandomNumberGenerator()
            let lines = generator.exchange(for: facility, using: &rng)
            for line in lines {
                if Task.isCancelled || !isRunning || pausedForPTT { break }
                await speak(line, facility: facility)
                if Task.isCancelled { break }
                try? await Task.sleep(nanoseconds: UInt64(Double.random(in: 0.3...1.1) * 1_000_000_000))
            }
            let gap = settings?.chatterDensity.gapRange ?? (5...14)
            try? await Task.sleep(nanoseconds: UInt64(Double.random(in: gap) * 1_000_000_000))
        }
    }

    private func speak(_ line: ChatterLine, facility: ATCFacility) async {
        let voice = voice(for: line, facility: facility)
        let buffers = await synthesize(line.spokenText, voice: voice)
        guard isRunning, !pausedForPTT else { return }
        if buffers.isEmpty {
            // Voice couldn't render to buffers — fall back to a quiet direct utterance,
            // and pace off a fixed delay since we get no playback-complete callback.
            speakFallback(line.spokenText, voice: voice)
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            return
        }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            // Resume on playback-complete, but never hang the loop: a stopped player
            // (PTT / interruption) may drop its completion, so a timeout also resumes.
            let box = ResumeOnce(continuation)
            radio.scheduleSpeech(buffers) { box.resume() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 25) { box.resume() }
        }
    }

    // MARK: - Voice selection

    private func voice(for line: ChatterLine, facility: ATCFacility) -> AVSpeechSynthesisVoice? {
        if line.isPilot {
            if let id = pilotVoiceID(), let v = AVSpeechSynthesisVoice(identifier: id) { return v }
            return voicePool.last ?? voicePool.first
        }
        // Controller line: prefer the user's chosen voice for this position so a given
        // frequency keeps a consistent "controller"; otherwise a stable pool voice.
        if let id = facilityVoiceID(facility), let v = AVSpeechSynthesisVoice(identifier: id) { return v }
        guard !voicePool.isEmpty else { return nil }
        // Stable index per facility (avoid abs(Int.min); normalise a possibly-negative
        // remainder into range) so a frequency keeps a consistent "controller" voice.
        let count = voicePool.count
        let index = ((facility.rawValue.hashValue % count) + count) % count
        return voicePool[index]
    }

    // MARK: - Synthesis

    private func synthesize(_ text: String, voice: AVSpeechSynthesisVoice?) async -> [AVAudioPCMBuffer] {
        await withCheckedContinuation { (continuation: CheckedContinuation<[AVAudioPCMBuffer], Never>) in
            let utterance = AVSpeechUtterance(string: text)
            utterance.voice = voice
            utterance.rate = min(max(Float(settings?.speechRate ?? 0.5), AVSpeechUtteranceMinimumSpeechRate),
                                 AVSpeechUtteranceMaximumSpeechRate)
            let lock = NSLock()
            var collected: [AVAudioPCMBuffer] = []
            var resumed = false
            // Resume exactly once, snapshotting the buffers under the lock. The write
            // callback runs on the synthesizer's queue; the timeout on the main queue.
            func finish() {
                lock.lock()
                if resumed { lock.unlock(); return }
                resumed = true
                let snapshot = collected
                lock.unlock()
                continuation.resume(returning: snapshot)
            }
            chatterSynth.write(utterance) { buffer in
                guard let pcm = buffer as? AVAudioPCMBuffer, pcm.frameLength > 0 else {
                    finish()   // terminating empty buffer (or an unexpected type)
                    return
                }
                lock.lock(); collected.append(pcm); lock.unlock()
            }
            // Belt-and-suspenders: never leave the continuation dangling if a voice
            // fails to deliver its terminating buffer.
            DispatchQueue.main.asyncAfter(deadline: .now() + 15) { finish() }
        }
    }

    private func speakFallback(_ text: String, voice: AVSpeechSynthesisVoice?) {
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        utterance.volume = Float(min(max((settings?.chatterVolume ?? 0.16) * 1.4, 0), 1))
        utterance.rate = min(max(Float(settings?.speechRate ?? 0.5), AVSpeechUtteranceMinimumSpeechRate),
                             AVSpeechUtteranceMaximumSpeechRate)
        fallbackSynth.speak(utterance)
    }

    // MARK: - Session & interruptions

    private func activateSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            // `.playback` (not `.ambient`) is required for background audio; the chatter is
            // intentionally audible, so it overrides the silent switch while enabled.
            try session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
            try session.setActive(true)
            sessionConfigured = true
        } catch {
            sessionConfigured = false
        }
    }

    private func onInterruption(_ type: AVAudioSession.InterruptionType?) {
        switch type {
        case .began:
            radio.stop()
        case .ended:
            guard isRunning, !pausedForPTT else { return }
            activateSession()
            radio.start()
            radio.setDucked(ducked)
        default:
            break
        }
    }

    private func onConfigChange() {
        // The audio route changed (headphones, etc.); bounce the engine so the graph
        // re-forms against the new hardware format.
        guard isRunning, !pausedForPTT else { return }
        radio.stop()
        radio.start()
        radio.setDucked(ducked)
    }
}

/// Guarantees a `CheckedContinuation` is resumed exactly once, from any thread.
private final class ResumeOnce {
    private let lock = NSLock()
    private var resumed = false
    private let continuation: CheckedContinuation<Void, Never>

    init(_ continuation: CheckedContinuation<Void, Never>) {
        self.continuation = continuation
    }

    func resume() {
        lock.lock()
        let shouldResume = !resumed
        resumed = true
        lock.unlock()
        if shouldResume { continuation.resume() }
    }
}
