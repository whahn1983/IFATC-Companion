# Android architecture

## Two modules, and why

```
Android/
├── core/     pure Kotlin/JVM — no android.*, no androidx.*
└── app/      Android — Compose, service, audio, notifications, billing, resources
```

`:core` holds every piece of ported iOS logic that does not need an Android framework
class: the domain models, geodesy, the Infinite Flight Connect client, the ATC state
machine, phraseology, ATIS, weather and its deviation engine, the OpenStreetMap surface
pipeline and taxi routing, Mock Mode, persistence, and the domain side of settings and
entitlements.

Keeping it Android-free buys three things:

1. **It is testable on a plain JVM.** No emulator, no Robolectric, no device. 290+
   engine tests run in seconds, which is what made a port of this size tractable at all.
2. **It enforces the layering the iOS app keeps by convention.** The ATC engine cannot
   reach the UI because the UI does not exist in its module. Taxi routing cannot reach
   the map renderer. Weather cannot reach either. On iOS those boundaries are
   discipline; here they are a compiler error.
3. **It builds without the Android SDK.** `settings-core.gradle.kts` resolves only from
   Maven Central, so the engine compiles and tests on any machine with a JDK.

## The platform ports

Anything `:core` needs from the platform is a small interface it declares and `:app`
implements:

| Port | `:core` declares | `:app` implements with |
| --- | --- | --- |
| `Clock` | `nowMillis()` | the system clock; tests use `MutableClock` |
| `DiagnosticsSink` | a log call | `DiagnosticsStore` (a bounded ring buffer in `:core`) |
| `KeyValueStore` | synchronous get/put | `DataStoreKeyValueStore` (Jetpack DataStore) |
| `FileStore` | namespaced blobs | `AndroidFileStore` (app-private files) |
| `HttpFetching` | GET / POST | `OkHttpFetcher`; tests serve canned payloads |
| `IFConnectTransport` | a byte pipe | `TcpConnectTransport`; tests use scripted queues |

`KeyValueStore` is synchronous while DataStore is not. Rather than push `suspend` through
every settings read, the Android implementation loads a snapshot once at start-up and
writes through in the background — the same contract `UserDefaults` offers, which is what
the ported code was written against.

## How state flows

```
Infinite Flight (TCP 1 Hz)  ─┐
Mock feed                   ─┼─► IFConnectManager ─► AircraftState
                             │
                             ▼
                   FlightSessionCoordinator ──► StateFlow<FlightSessionState>
                    │  PhaseDetector                      │
                    │  ATCStateMachine                    │  collectAsStateWithLifecycle
                    │  PilotResponseEngine                ▼
                    │  ReadbackGate                  Compose screens
                    │  Weather / ATIS / Surface           │
                    │  PilotActionAvailability            │ callbacks
                    └───────────◄─────────────────────────┘
                                     ▲
                    ActiveFlightService keeps this alive off-screen
                    and mirrors it onto the Live Flight Update
```

The iOS `AppModel` publishes about forty separate `@Published` properties and the SwiftUI
views observe the object. Compose works better the other way round: **one `StateFlow` of
one immutable `FlightSessionState`**, so a screen recomposes from a single coherent
snapshot rather than from forty independently-timed emissions. That also removes a class
of bug the iOS comments keep circling — reading a property mid-update, while
`@Published`'s `willSet` has fired but the value has not landed.

## Where the iOS orchestrator went

`AppModel.swift` is 8,541 lines. Porting it as one class would have reproduced the
problem rather than the behaviour, so it is decomposed into pieces that each do one
thing and can each be tested:

| iOS `AppModel` region | Android |
| --- | --- |
| Response-button visibility | `session/PilotActionAvailability.kt` — a pure function over a value type |
| Flow ordering, facility mapping, next-facility | `session/AtcFlowOrder.kt` |
| Read-back gate and its idle re-prompt | `session/ReadbackGate.kt` |
| Published UI state | `session/FlightSessionState.kt` |
| Button labels, ordering, verbatim copy | `session/PilotActionPresentation.kt` |
| Connection lifecycle, reconnect, polling | `connect/IFConnectManager.kt` |
| Weather refresh and the deviation flow | `weather/` and `weather/deviation/` |
| Taxi map, routing, runway crossings | `surface/` and `surface/routing/` |
| ATIS availability, fetch, tuning | `atis/` |
| Session resume and saved flights | `persistence/` |
| The wiring that remains | `session/FlightSessionCoordinator.kt` |

The parts pulled out first were the ones that are easy to get subtly wrong and impossible
to test while entangled with a view model: which buttons to show, when the conversation
may advance, and when a controller repeats itself.

## The `:app` module

| Package | Holds |
| --- | --- |
| `ui/screens`, `ui/components`, `ui/theme`, `ui/map` | **Pure** composables — state in, callbacks out, no Android imports |
| `ui/` (root) | `MainActivity`, ViewModels, permission flows, resource access |
| `service/` | `ActiveFlightService` and its controller interface |
| `notification/` | The Live Flight Update and its actions |
| `audio/` | `RadioAudioEngine` (AudioTrack), `AndroidSpeechService` (TextToSpeech), WAV decoding |
| `billing/` | `PlayBillingRepository` |
| `data/` | The DataStore and file-store implementations |
| `simbrief/` | The Custom Tabs launcher |

The screens are pure on purpose. `settings-uicheck.gradle.kts` compiles them against
JetBrains Compose from Maven Central — which publishes the same `androidx.compose.*`
packages — so the UI type-checks on a machine with no Android SDK. It has already caught
an unresolved icon, a lazy-scope import that does not exist, a `KeyboardOptions`
parameter renamed between Compose versions, and a composable referenced before it was
written.

## Dependency injection

Constructed by hand, in `AppGraph`. A DI framework earns its keep in a multi-team app
with many build variants; here it would add a compiler plugin, build time and indirection
to assemble one long-lived graph with no variants. Constructing it explicitly keeps the
wiring readable and — more usefully — keeps every engine's dependencies visible as
constructor parameters, which is exactly what makes them testable.

`AppGraph` is a process singleton because the flight session must outlive any Activity:
the foreground service, the notification actions and the UI all address the same session.

## Concurrency

- Swift `actor` → a class guarded by a `kotlinx.coroutines.sync.Mutex`.
- `async`/`await` → `suspend`.
- `Task` → a coroutine on an injected `CoroutineScope`.
- Timers → `delay` in a cancellable coroutine, so tests can drive them with virtual time
  (`ReadbackGateTest` advances thirty-second intervals instantly).
- `@MainActor` → engines are main-thread-agnostic; only Compose collection is on the main
  dispatcher.

## Building

```bash
cd Android

# Engine: compiles and tests on a plain JVM, no Android SDK.
./gradlew -c settings-core.gradle.kts :core:test

# Compose screens: type-checks without the Android SDK.
./gradlew -c settings-uicheck.gradle.kts :uicheck:compileKotlin

# The real app, in Android Studio or on a machine with the SDK.
./gradlew :app:assembleDebug
./gradlew :app:bundleRelease
```
