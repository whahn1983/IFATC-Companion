import Foundation
import AVFoundation

/// Chooses realistic **English human** voices for the background radio chatter.
///
/// The system voice list (`AVSpeechSynthesisVoice.speechVoices()`) mixes in a lot of
/// things we never want spilling onto a simulated ATC frequency: novelty/robot voices
/// ("Trinoids", "Zarvox", "Bells"…), the synthetic Eloquence compact voices, the
/// user's Personal Voice, and voices in other languages. This catalog filters those
/// out and ranks what's left by audio quality (premium → enhanced → default) so the
/// chatter is spoken by natural-sounding English voices only.
///
/// If the device somehow exposes no acceptable voice, callers fall back to the voices
/// the user already picked for the real controllers/pilot in Settings — those are, by
/// definition, English voices the user is happy with.
enum VoiceCatalog {

    /// English, non-novelty, non-personal system voices, best quality first.
    /// Computed on demand (cheap) so newly-downloaded voices are picked up.
    static func englishHumanVoices() -> [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices()
            .filter(isEnglishHumanVoice)
            .sorted { qualityRank($0) > qualityRank($1) }
    }

    /// True when a voice is a natural English voice suitable for spoken radio calls —
    /// i.e. an English language, not a novelty voice, not a Personal Voice, and not one
    /// of the robotic Eloquence compact voices.
    static func isEnglishHumanVoice(_ voice: AVSpeechSynthesisVoice) -> Bool {
        guard voice.language.hasPrefix("en") else { return false }
        // Eloquence voices are unmistakably synthetic; their identifiers carry the tag.
        if voice.identifier.lowercased().contains("eloquence") { return false }
        // Apple classifies the joke voices as novelty, and marks user-recorded voices as
        // personal — exclude both. (Deployment target is iOS 17, where `voiceTraits`
        // is always available.)
        let traits = voice.voiceTraits
        if traits.contains(.isNoveltyVoice) { return false }
        if traits.contains(.isPersonalVoice) { return false }
        return true
    }

    /// premium = 3, enhanced = 2, default/compact = 1. Higher is more natural.
    static func qualityRank(_ voice: AVSpeechSynthesisVoice) -> Int {
        switch voice.quality {
        case .premium: return 3
        case .enhanced: return 2
        default: return 1
        }
    }

    /// The specific system voices the background chatter is limited to — a curated set of
    /// natural English voices with a good regional spread (AU, GB, IE, IN, US). The exact
    /// set installed varies by device; whichever of these are present are used.
    static let allowedChatterVoiceNames = ["Karen", "Daniel", "Moira", "Rishi", "Samantha"]

    /// The pool of voices the chatter draws from: the installed subset of
    /// `allowedChatterVoiceNames`, one entry per name (best quality when a name has both a
    /// compact and enhanced/premium variant). Falls back to the general English-human set
    /// only if none of the named voices are installed, so the chatter is never silent.
    static func chatterVoicePool() -> [AVSpeechSynthesisVoice] {
        let all = AVSpeechSynthesisVoice.speechVoices()
        var pool: [AVSpeechSynthesisVoice] = []
        for name in allowedChatterVoiceNames {
            let matches = all.filter { $0.name == name && $0.language.hasPrefix("en") }
            if let best = matches.max(by: { qualityRank($0) < qualityRank($1) }) {
                pool.append(best)
            }
        }
        return pool.isEmpty ? englishHumanVoices() : pool
    }
}
