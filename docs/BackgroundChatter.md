# Background radio chatter, background audio & the live notification

Two opt-in Settings toggles under **Background Radio & Notification**:

1. **Background radio chatter** — plays quiet, static-wrapped, randomly-generated ATC
   traffic on the frequency you're tuned to, and (crucially) keeps the app running in the
   background so live Infinite Flight callbacks no longer stall when you leave the app.
2. **Live flight notification** — a live-updating Lock Screen / Dynamic Island card with
   Read Back / Check In buttons. It **requires** background chatter, because the chatter is
   what supplies the continuous audio that keeps the app (and the notification's updates)
   alive in the background.

A third, independent toggle — **Static on my transmissions** — brackets your own
read-backs with a mic-key/un-key static burst.

## Why chatter is the background anchor

iOS suspends a normal app within seconds of backgrounding. The only practical way to keep
the Infinite Flight poll loop (`IFConnectManager`, 1 Hz), phase detection and the ATC
state machine running while backgrounded is the **`audio` background mode** (declared in
`Info.plist`) combined with an app that is *actually producing audio*. A continuous ambient
chatter bed satisfies both — and, unlike playing silent audio to stay alive (which App
Store review guideline 2.5.4 prohibits), it is genuine, purposeful, audible content.

While the chatter runs it holds a `.playback` audio session active; that is also what lets
TTS callouts play in the background and what keeps the Live Activity updating.

## Components

```
AmbientChatterService (orchestrator, @MainActor)
  ├─ ChatterScriptGenerator   — random, frequency-bounded phraseology (reuses Phonetic + AirlineDatabase)
  ├─ VoiceCatalog             — a curated set of natural English voices (Karen/Daniel/Moira/Rishi/Samantha)
  ├─ AVSpeechSynthesizer.write — renders a chatter line to PCM buffers
  └─ RadioAudioEngine (AVAudioEngine graph)
        ├─ bedSource (generated static)   ─► bedMixer ────────────┐
        ├─ speechPlayer ► EQ(bandpass) ► distortion(radio) ► speechMixer ─► mainMixer ─► out
        └─ squelchPlayer (mic-key bursts) ─► squelchMixer ────────┘
```

- **`ChatterScriptGenerator`** is bounded to the tuned facility (`AppModel.currentFacility`):
  Center works ride reports / hand-offs / descend-via-STAR / en-route climbs-descents;
  Ground works taxi and hand-offs; Tower works takeoff / landing / line-up-and-wait;
  Approach works vectors, approaches, speed and the Tower hand-off; Departure works radar
  contact, climbs, headings and the Center hand-off; Clearance reads IFR clearances; Ramp
  works pushback/start/monitor. It is generic over `RandomNumberGenerator` for
  deterministic tests.
- **`VoiceCatalog`** limits the chatter to a curated set of natural English voices —
  **Karen, Daniel, Moira, Rishi, Samantha** (a good AU/GB/IE/IN/US spread) — using whichever
  are installed, preferring the enhanced/premium variant of each. If none are installed it
  falls back to the general English-human filter (English, non-novelty, non-Personal-Voice,
  non-Eloquence). Each facility keeps a stable voice, and the pilot side uses a distinct one.
  The chatter speaks at a fixed rate (0.55) independent of the user's main voice-rate setting.
- **`RadioAudioEngine`** generates the static bed (a filtered-noise `AVAudioSourceNode` — no
  bundled asset), runs the chatter voice through a band-pass EQ + the `.speechRadioTower`
  distortion preset so it sounds like a real, barely-readable transmission, and fires
  mic-key bursts shaped as a click with a soft tail (~110 ms, band-limited so it reads as
  radio noise). The bed behaves like a real **squelch**: it is kept well
  below the voice and only opens up (`setTransmitting`) while a call is playing, falling to
  near-silent between calls.

## Interaction with the rest of the app

- **Ducking:** `AppModel` observes `SpeechService.isSpeaking` and ducks the chatter (voice
  to silence, bed to a faint hiss) whenever a real ATC/ATIS/pilot call plays, so the real
  calls stay clear.
- **Push-to-talk:** `AppModel` observes `SpeechRecognitionService.isListening` and pauses
  the chatter (and its engine) around PTT so it never bleeds into the microphone.
- **Silent switch:** background chatter overrides the silent switch (it forces `.playback`
  via `SpeechService.forcePlaybackForBackground`), because audible background audio is the
  whole point.
- **Transmission static:** `SpeechService` brackets pilot transmissions with a squelch
  burst (`transmissionStatic`), which works even when the continuous chatter is off (the
  engine is started transiently for the burst).

## Live notification

`LiveActivityController` starts/updates/ends an ActivityKit `Activity`. The content
(`CompanionActivityAttributes.ContentState`) carries phase, tuned controller, altitude,
heading, ground speed, callsign, route, the next controller, a weather advisory flag, and
which of the Read Back / Check In buttons apply. Updates are throttled (2 s) and pushed
from the telemetry loop and on every new call. The buttons are `LiveActivityIntent`s that
run in the app process and call the same actions as the on-screen buttons.

The widget UI lives in a separate extension target — see
[LiveActivitySetup.md](LiveActivitySetup.md).

## Costs & caveats

- Background audio + 1 Hz polling uses meaningfully more battery — hence opt-in.
- `AVSpeechSynthesizer.write(toBufferCallback:)` doesn't work with every voice; the service
  falls back to a quiet direct utterance for any voice that can't render to buffers.
- Background Wi-Fi to Infinite Flight is less reliable with the screen **locked** than with
  another app foregrounded; active audio helps keep the radio up, but verify on-device.
- All chatter is generated locally — no AI, no network, no bundled audio.
