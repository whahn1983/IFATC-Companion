import Foundation
import AVFoundation

/// A gentle tube-style **soft-clip** (tanh waveshaper) applied in place to a PCM buffer.
///
/// This replaces `AVAudioUnitDistortion`'s "speech" factory presets (`.speechRadioTower`
/// et al.), which are **ring-modulator / decimator** effects — Apple's own docs describe
/// them as "robotic," and ring modulation produces the metallic, robotic artifact we were
/// hearing. A real radio's grit instead comes from mild transmitter/tube **saturation**:
/// a soft clip that adds harmonic content and a driven quality while leaving the voice
/// clearly intelligible.
///
/// The saturated copy is blended with the clean signal (`mix`) so consonants stay crisp,
/// and `drive` is kept modest. Applied *before* the band-pass EQ so the low-pass cleans up
/// the harmonics the saturation adds (the recommended processing order for radio voices).
/// No make-up gain is applied — the soft clip only rounds the peaks (leaving headroom) and
/// mildly thickens the body, so it can't push the following EQ into clipping.
///
/// Runs on the buffer off the realtime thread (before the buffer is scheduled), so the
/// `tanhf` cost is fine.
func applyRadioSaturation(to buffer: AVAudioPCMBuffer, drive: Float, mix: Float) {
    guard let channels = buffer.floatChannelData else { return }
    let frameCount = Int(buffer.frameLength)
    let channelCount = Int(buffer.format.channelCount)
    let d = max(0.001, drive)
    let m = max(0, min(1, mix))
    for channel in 0..<channelCount {
        let samples = channels[channel]
        for i in 0..<frameCount {
            let x = samples[i]
            let shaped = tanhf(x * d)
            samples[i] = (1 - m) * x + m * shaped
        }
    }
}
