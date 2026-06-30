import Foundation
import Speech
import AVFoundation

/// On-device push-to-talk speech recognition using Apple's `Speech` framework.
/// This is NOT an LLM and performs no network calls when on-device recognition is
/// available — audio is transcribed locally and discarded. The resulting text is
/// handed to the deterministic `PilotIntentParser`.
///
/// Usage: hold to talk — call `startListening()` on press and `stopListening()`
/// on release; the latest transcription is delivered via `onResult`.
final class SpeechRecognitionService: NSObject, ObservableObject {

    @Published private(set) var isListening = false
    @Published private(set) var partialText = ""
    @Published private(set) var authorization: SFSpeechRecognizerAuthorizationStatus = .notDetermined
    @Published private(set) var available = true
    @Published private(set) var lastError: String?

    /// Called with the final recognized utterance (on the main thread).
    var onResult: ((String) -> Void)?

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private let audioEngine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    override init() {
        super.init()
        available = (recognizer?.isAvailable ?? false)
    }

    /// Whether the OS has granted speech-recognition permission.
    var isAuthorized: Bool { authorization == .authorized }

    /// Prompt for speech-recognition permission (microphone is requested by the
    /// audio session on first capture).
    func requestAuthorization(_ completion: ((Bool) -> Void)? = nil) {
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async {
                self.authorization = status
                completion?(status == .authorized)
            }
        }
    }

    func startListening() {
        guard !isListening else { return }
        guard authorization == .authorized else {
            requestAuthorization { ok in if ok { self.startListening() } }
            return
        }
        guard let recognizer, recognizer.isAvailable else {
            available = false
            lastError = "Speech recognition is unavailable."
            return
        }

        do {
            #if canImport(UIKit)
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .measurement,
                                    options: [.duckOthers, .defaultToSpeaker])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            #endif

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            if recognizer.supportsOnDeviceRecognition {
                request.requiresOnDeviceRecognition = true
            }
            self.request = request

            let inputNode = audioEngine.inputNode
            let format = inputNode.outputFormat(forBus: 0)
            inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
                request.append(buffer)
            }
            audioEngine.prepare()
            try audioEngine.start()

            partialText = ""
            lastError = nil
            isListening = true

            task = recognizer.recognitionTask(with: request) { [weak self] result, error in
                guard let self else { return }
                if let result {
                    let text = result.bestTranscription.formattedString
                    DispatchQueue.main.async { self.partialText = text }
                }
                if error != nil {
                    DispatchQueue.main.async { self.teardown() }
                }
            }
        } catch {
            lastError = error.localizedDescription
            teardown()
        }
    }

    /// Stop capturing and deliver whatever was recognized as the final utterance.
    func stopListening() {
        guard isListening else { return }
        let text = partialText.trimmingCharacters(in: .whitespacesAndNewlines)
        teardown()
        if !text.isEmpty { onResult?(text) }
    }

    /// Cancel without delivering a result.
    func cancel() {
        teardown()
        partialText = ""
    }

    private func teardown() {
        if audioEngine.isRunning {
            audioEngine.stop()
            audioEngine.inputNode.removeTap(onBus: 0)
        }
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil
        isListening = false
        // Release the record session and let others (our playback session) resume, so
        // the spoken voice doesn't come back quiet after a push-to-talk capture.
        #if canImport(UIKit)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }
}
