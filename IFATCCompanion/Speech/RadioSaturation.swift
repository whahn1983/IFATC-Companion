import Foundation
import AVFoundation

/// A tube-style **soft-clip** (tanh waveshaper) applied in place to a PCM buffer — the
/// driven "grit" of a real radio transmitter.
///
/// This replaces `AVAudioUnitDistortion`'s "speech" factory presets (`.speechRadioTower`
/// et al.), which are **ring-modulator / decimator** effects — Apple's own docs describe
/// them as "robotic," and ring modulation produces a metallic robot voice. Transmitter/tube
/// saturation instead adds harmonic content and a driven quality.
///
/// The amount of grit is set almost entirely by `drive`: `tanh` is near-linear (so nearly
/// inaudible) at low drive, and only generates real harmonics once the signal is pushed
/// into its saturating region — so meaningful grit needs `drive` well above 1 (roughly
/// 4–10 for speech). The saturated copy is blended with the clean signal (`mix`) so
/// consonants stay intelligible, and `outputGain` tames the loudness that heavy drive adds
/// (heavy drive is also a compressor — it lifts the quiet parts).
///
/// The blend is bounded to the input's range, so with `outputGain <= 1` it can't push the
/// following band-pass EQ into clipping. Runs on the buffer off the realtime thread.
func applyRadioSaturation(to buffer: AVAudioPCMBuffer, drive: Float, mix: Float, outputGain: Float = 1) {
    guard let channels = buffer.floatChannelData else { return }
    let frameCount = Int(buffer.frameLength)
    let channelCount = Int(buffer.format.channelCount)
    let d = max(0.001, drive)
    let m = max(0, min(1, mix))
    let g = outputGain
    for channel in 0..<channelCount {
        let samples = channels[channel]
        for i in 0..<frameCount {
            let x = samples[i]
            let shaped = tanhf(x * d)
            samples[i] = ((1 - m) * x + m * shaped) * g
        }
    }
}
