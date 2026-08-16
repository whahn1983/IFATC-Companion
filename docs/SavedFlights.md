# Saved flights

Infinite Flight lets several flights be in progress at once, and a flight can be put
down and picked up hours later. IFATC Companion follows: a flight can be **saved** by
name, and loaded back so the whole app returns exactly as it was — transcript,
clearances, tuned frequency, plan, gates and all.

This sits alongside — not instead of — the automatic resume described in
[Architecture.md](../Architecture.md#session-persistence-reconnect-resume). Both use the
same `SessionSnapshot`, so the two never drift apart:

| | Auto-resume | Saved flights |
|---|---|---|
| Question it answers | "the link dropped / the app was killed — put me back" | "I have three flights on the go — give me that one" |
| Storage | one snapshot in `UserDefaults` (`SessionStateStore`) | a named library in Application Support (`SavedFlightStore`) |
| Lifetime | six hours, in-progress flights only | until the pilot deletes it |
| Chosen by | the app, on `startLive()` | the pilot, from the Flights list |

## The Flights screen

The round **list** button at the top right of the ATC tab pushes the Flights screen.

- **New Flight** (circle-plus, beside the back button) does what Clear Flight does —
  wipes the conversation and starts again from the gate, keeping settings and the flight
  plan — and additionally unbinds the saved flight so the fresh session cannot
  auto-save over it. A flight still in progress stays in the list; a finished one is
  retired from it (see below). It asks first, offering to save the flight in progress.
- **Save** (top right) puts the session in the list. A flight already in the list is
  updated in place, so tapping Save twice never leaves `KIAH-KORD` beside
  `KIAH-KORD-1`. Disabled for a flight that has already finished at the gate.
- **Rows** are named for the route (`KIAH-KORD`), with `-1`, `-2` … for repeats of the
  same route. Each shows where the flight had got to and when it was last saved, and the
  one being flown is badged **Flying**. Tap to load, swipe to delete.

Saving is a **Live Connected Mode** feature. Mock Mode is a scripted demo that always
starts from the gate, so there is nothing in one worth resuming.

## What a saved flight carries

Everything that describes the *flight*:

- the conversational cursor — `atcState`, the state-machine position, the controller
  being worked, the detected phase and assigned altitude;
- the gate-to-gate flags — `hasDeparted`, `arrivalAnnounced`, `awaitingGateArrival`,
  `manualTuning`, `monitoringTower`, `goAroundInProgress`;
- the full transcript;
- the **radio**: the frequency actually tuned (`tunedFacility`) and any controller tuned
  but not yet checked in with;
- the **read-back gate** — if a controller was waiting on the pilot, it is still waiting,
  and its "how do you read?" re-prompt is re-armed;
- the **flight plan** as it stood, including the route, its fixes and endpoint
  coordinates, so the map is populated the moment the flight loads;
- the pilot's **manual overrides** — callsign, airline/flight number, endpoints,
  alternate, cruise altitude, runway, SID/STAR/approach and both gates;
- ATIS state — the reports fetched, the information codes reported, whether each was
  already appended to a call, and whether the button was dismissed for that phase;
- the in-progress **weather diversion**, so the deviation card and its "clear of weather"
  button come back mid-diversion;
- arrival-to-gate staging — whether "monitor ramp to the gate" was issued and where the
  gate is — plus the field-elevation and liftoff references later altitudes are measured
  against;
- the **Diagnostics log** — attached to the saved copy only (`snapshotForSaving()`), never
  to the `UserDefaults`-backed auto-resume snapshot, which a reconnect doesn't need since
  it keeps the log already in memory.

## What it deliberately doesn't

Anything that describes the *world* rather than the flight, because it would be stale:

- **Telemetry.** Position, altitude and ground state come from the next Infinite Flight
  reading, a second or so after loading. The last fix is cleared on load so nothing —
  arrival-ATIS range, the weather corridor, which airport surface to pre-cache — is
  measured from the previous flight's position.
- **Weather observations.** METARs, the TAF, PIREPs, SIGMETs and the radar sample are
  re-fetched on load. Restoring hours-old cells would draw a deviation around weather
  that has since moved. The *interaction* state (what the pilot has already dealt with)
  is saved; the observations are not.
- **The taxi map.** Re-established from the saved state on the first real fix, so the
  route starts from where the aircraft actually is (the same `pendingTaxiMapRestore`
  path a relaunch mid-taxi uses).
- **Device preferences.** Voices, volumes, chatter, radar options and the Infinite Flight
  host/port are yours, not the flight's — loading a flight from last week never changes
  your audio setup or connection.
- **The connection manifest.** Republished by Infinite Flight on every connect.

## Auto-save

**Settings → Saved Flights → "Keep saved flights up to date"** (on by default) keeps the
loaded flight current as it is flown, so switching A → B → A resumes each exactly where
it was left. It writes on meaningful change only, not on the auto-resume heartbeat — a
saved flight has no expiry to keep fresh, so pumping the library to disk every couple of
minutes would buy nothing.

Switched off, a saved flight is a fixed point in time that changes only when Save is
tapped — and the app then treats the session as unsaved, so New Flight and Load offer to
save it first.

The binding is released whenever the session stops being that flight: Clear Flight, New
Flight, Reset App Data, deleting the flight, or a `startLive()` that finds nothing to
resume (for instance the pilot was away past the six-hour auto-resume window). That last
one matters — without it a fresh empty session would auto-save straight over the saved
flight.

## Clearing keeps a flight in progress, and retires a finished one

Clear Flight and New Flight are how the pilot switches to another flight, so a flight
**still under way stays in the list** — only the binding is released. Nothing about
clearing the screen means the leg is over.

A flight that has **blocked in at the destination gate** is the opposite case: it is
finished, there is nothing to come back to, and clearing removes it from the list along
with the session. "Finished" is `flightIsComplete` — parked with the arrival announced —
deliberately the same rule as `SessionSnapshot.isCompleted`, so what the library calls a
finished flight and what the session calls one can never disagree.

Both confirmations say which of the two is about to happen. **Save is disabled once a
flight is finished** — `canSaveCurrentFlight` refuses it and so does `saveCurrentFlight()`
itself, so the rule holds whoever calls it — and the "save it first" option disappears
from both dialogs with it. A finished flight also stops counting as unsaved, since
warning that it will be lost would offer a rescue that isn't there. A flight already in
the list still tracks itself to the gate through the auto-save; it is simply retired when
the pilot clears.

Nothing else deletes a flight: loading a different one, Reset App Data and the auto-save
all leave the list as it is. Deleting by hand is always available with a swipe.

## Loading is a reset, then a restore

`loadSavedFlight` resets the session to a clean pre-departure state and *then* applies
the snapshot. This is not a detail. Applying a snapshot on top of the live session is
what makes a flight swap dangerous: loading a flight parked at the gate onto a session on
approach would leave `hasDeparted` true — the pre-departure ground flow is skipped for
good — while the forward-only flow guard refuses to walk the state machine back from
`.approach` to `.clearance`. Reset-then-apply makes a backwards swap as safe as a forwards
one.

Two warnings guard the load, both advisory — the pilot can always continue:

- **Endpoint mismatch.** The saved flight's route is compared with what Infinite Flight
  is currently reporting; a disagreement is spelled out ("Infinite Flight is reporting
  EGLL-KBOS, but this saved flight is KIAH-KORD").
- **Unsaved work.** If the session in progress isn't in the list (or auto-save is off),
  the dialog says so and offers **Save & Load** first.

## Background, foreground and the notification

- **Foreground reconnect** is unchanged. Returning from the background still forces a
  reconnect and restores from the auto-resume snapshot, which the auto-save keeps
  pointing at the flight actually being flown — including one just loaded, which is
  persisted immediately rather than at the next state change.
- **The Live Activity** is force-refreshed on load, so the Lock Screen / Dynamic Island
  card shows the loaded flight's callsign and route straight away instead of the previous
  flight's until the next throttled push.
- **Ambient chatter** follows the restored transcript: a flight that was already talking
  to ATC keeps its background traffic rather than waiting for the next call.
