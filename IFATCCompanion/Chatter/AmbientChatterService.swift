import Foundation
import AVFoundation
import Combine

/// A push-to-talk transition on the pilot's radio: keying the mic (a dull contact thump)
/// or un-keying it (the receiver-return squelch tail).
enum MicKeyEvent {
    case keyUp   // pilot presses PTT — key-down thump
    case keyDown // pilot releases PTT — release squelch tail
}

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
/// also provides the mic-key thump and release squelch tail that bracket the pilot's own
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

    /// Fixed speech rate for the background chatter (AVSpeechUtterance scale, where 0.5
    /// is the natural default), independent of the user's main voice-rate setting.
    private static let chatterRate: Float = 0.55

    /// The frequency the pilot is tuned to right now — supplied by `AppModel` so the
    /// chatter always matches the position.
    private var facilityProvider: () -> ATCFacility = { .center }

    /// The runways for the airport the chatter should reference right now — the origin field
    /// pre-departure/climb, the destination once descending/arriving — supplied by `AppModel`
    /// from the field's ATIS (active departure/arrival runways) and the loaded OSM surface.
    /// All-empty when nothing is loaded yet, which lets the generator fall back to random.
    private var runwaysProvider: () -> ChatterRunwayContext = { ChatterRunwayContext() }

    private var voicePool: [AVSpeechSynthesisVoice] = []
    private var loopTask: Task<Void, Never>?
    private var idleStopTask: Task<Void, Never>?

    private var ducked = false
    private var pausedForPTT = false
    private var sessionConfigured = false

    /// The facility the exchange currently being spoken is for; `nil` in the gap between
    /// exchanges. Lets a mid-exchange frequency switch be detected so the current call — and
    /// any read-back tied to it — is dropped in favour of chatter for the new frequency.
    private var activeFacility: ATCFacility?
    /// Set when a mid-exchange frequency switch means the rest of the current exchange (its
    /// pending read-back) must be abandoned and a fresh exchange started for the new facility.
    private var exchangeInterrupted = false
    /// The resume handle for the in-flight `speak()`, so an interruption can cut the call
    /// immediately rather than waiting out its playback (or the 25 s safety timeout).
    private var activeSpeechResume: ResumeOnce?

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

    /// Supply the live context (called once from `AppModel`): the tuned facility and the
    /// runways of the airport the chatter is currently simulating.
    func bindContext(facility: @escaping () -> ATCFacility,
                     runways: @escaping () -> ChatterRunwayContext = { ChatterRunwayContext() }) {
        self.facilityProvider = facility
        self.runwaysProvider = runways
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
        voicePool = VoiceCatalog.chatterVoicePool()
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
        activeFacility = nil
        // The player is stopped, so its completion may never fire — release the awaiting
        // `speak()` now rather than leaving it on the 25 s safety timeout.
        activeSpeechResume?.resume()
    }

    /// Duck (or restore) the chatter under a real ATC call.
    func setDucked(_ ducked: Bool) {
        self.ducked = ducked
        radio.setDucked(ducked)
    }

    /// Called by `AppModel` whenever the tuned facility changes, with the **new** facility. If
    /// the chatter is mid-exchange on the previous frequency, end that call immediately (cutting
    /// its audio and dropping any pending read-back) so the loop can start chatter appropriate
    /// for the newly-tuned frequency. A switch during the gap between exchanges needs no action —
    /// the next cycle already reads the current facility.
    ///
    /// The new facility is passed in rather than re-read from `facilityProvider()`: `AppModel`
    /// drives this from a Combine `@Published` observer, which fires in `willSet` (before the
    /// stored `currentFacility` is updated), so re-reading it here would still see the old value.
    func facilityDidChange(to facility: ATCFacility) {
        guard isRunning, !pausedForPTT else { return }
        guard Self.shouldAbandonExchange(active: activeFacility, current: facility) else { return }
        abandonCurrentExchange()
    }

    /// Whether an exchange being spoken for `active` should be abandoned because the pilot has
    /// tuned to `current`. Only a mid-exchange (`active` non-nil) switch to a *different*
    /// facility interrupts; a switch in the gap (`active` nil) or back to the same facility does
    /// not. Extracted as a pure decision for testing.
    nonisolated static func shouldAbandonExchange(active: ATCFacility?, current: ATCFacility) -> Bool {
        guard let active else { return false }
        return active != current
    }

    /// End the exchange currently on the air: mark it interrupted so the loop drops any pending
    /// read-back, stop synthesis, cut the playing call's audio, and unblock the awaiting
    /// `speak()` so the loop can immediately start fresh chatter for the new frequency.
    private func abandonCurrentExchange() {
        exchangeInterrupted = true
        chatterSynth.stopSpeaking(at: .immediate)
        // The buffer-render fallback speaks straight to the session via `fallbackSynth`; stop
        // it too so a switch during that path also silences the call on the air.
        fallbackSynth.stopSpeaking(at: .immediate)
        radio.stopSpeech()
        activeSpeechResume?.resume()
    }

    /// Pause/resume around push-to-talk so the chatter never bleeds into the mic and the
    /// recording session can take over the audio route.
    func pauseForPTT() {
        pausedForPTT = true
        chatterSynth.stopSpeaking(at: .immediate)
        radio.stop()
        activeSpeechResume?.resume()
    }

    func resumeAfterPTT() {
        pausedForPTT = false
        guard isRunning else { return }
        activateSession()
        radio.start()
        radio.setDucked(ducked)
    }

    // MARK: - Transmission static (mic key / un-key)

    /// Play the mic key-up click or the un-key squelch tail to bracket the pilot's own
    /// transmission. Works even when the continuous chatter is off — the engine is started
    /// transiently and stopped again after a short idle window.
    func micKey(_ event: MicKeyEvent) {
        if isRunning {
            fire(event)
            return
        }
        guard !pausedForPTT else { return }
        activateSession()
        if !radio.isRunning { radio.start() }
        fire(event)
        armIdleStop()
    }

    private func fire(_ event: MicKeyEvent) {
        switch event {
        case .keyUp: radio.playKeyClick()
        case .keyDown: radio.playSquelchTail()
        }
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
            exchangeInterrupted = false
            activeFacility = facility
            // Refresh the runway pools each cycle so they track the airport in play (origin on
            // departure, destination on arrival) and its current ATIS as the flight progresses.
            let runways = runwaysProvider()
            generator.runwayIdents = runways.all
            generator.departureRunwayIdents = runways.departures
            generator.arrivalRunwayIdents = runways.arrivals
            var rng = SystemRandomNumberGenerator()
            let lines = generator.exchange(for: facility, using: &rng)
            for line in lines {
                if Task.isCancelled || !isRunning || pausedForPTT || exchangeInterrupted { break }
                await speak(line, facility: facility)
                if Task.isCancelled || exchangeInterrupted { break }
                try? await Task.sleep(nanoseconds: UInt64(Double.random(in: 0.3...1.1) * 1_000_000_000))
            }
            activeFacility = nil
            if exchangeInterrupted {
                // A mid-exchange frequency switch cut this exchange short: settle briefly, then
                // the next iteration starts fresh chatter for the newly-tuned facility rather
                // than waiting out the full inter-exchange gap.
                try? await Task.sleep(nanoseconds: UInt64(Double.random(in: 0.4...0.8) * 1_000_000_000))
                continue
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
        // Open the static bed for the duration of the transmission (squelch), then let
        // it fall back to near-silent in the gap.
        radio.setTransmitting(true)
        defer { radio.setTransmitting(false); activeSpeechResume = nil }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            // Resume on playback-complete, but never hang the loop: a stopped player
            // (PTT / interruption / a mid-exchange frequency switch) may drop its
            // completion, so a timeout also resumes. Holding the box lets
            // `abandonCurrentExchange()` cut the call immediately on a frequency switch.
            let box = ResumeOnce(continuation)
            activeSpeechResume = box
            radio.scheduleSpeech(buffers) { box.resume() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 25) { box.resume() }
        }
    }

    // MARK: - Voice selection

    private func voice(for line: ChatterLine, facility: ATCFacility) -> AVSpeechSynthesisVoice? {
        // Background pilots are other aircraft, each a different station: pick a fresh random
        // voice from the curated chatter pool per transmission so consecutive read-backs don't
        // all sound like the same pilot.
        if line.isPilot { return voicePool.randomElement() }
        // Controller lines use the same per-facility voice the pilot hears from the real
        // controllers (from Settings), so the background <facility> matches the <facility> in
        // use — Ground sounds like Ground, Tower like Tower.
        return controllerVoice(for: facility)
    }

    /// The controller voice for a facility, mirroring `SpeechService`: the configured
    /// per-facility voice, then the default controller voice, then a system English voice.
    private func controllerVoice(for facility: ATCFacility) -> AVSpeechSynthesisVoice? {
        guard let settings else { return voicePool.first }
        let id = settings.controllerVoiceID(for: facility)
        if !id.isEmpty, let v = AVSpeechSynthesisVoice(identifier: id) { return v }
        if !settings.defaultVoiceID.isEmpty,
           let v = AVSpeechSynthesisVoice(identifier: settings.defaultVoiceID) { return v }
        return AVSpeechSynthesisVoice(language: "en-US")
    }

    // MARK: - Synthesis

    private func synthesize(_ text: String, voice: AVSpeechSynthesisVoice?) async -> [AVAudioPCMBuffer] {
        await withCheckedContinuation { (continuation: CheckedContinuation<[AVAudioPCMBuffer], Never>) in
            let utterance = AVSpeechUtterance(string: text)
            utterance.voice = voice
            utterance.rate = Self.chatterRate
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
        utterance.rate = Self.chatterRate
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
