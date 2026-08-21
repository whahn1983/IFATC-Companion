# Background execution on Android

## What has to keep running

An IFATC Companion flight is a long-lived session. While it is active the app must keep
polling Infinite Flight over TCP at 1 Hz, running phase detection and the ATC state
machine, speaking controller calls and pilot read-backs, optionally playing ambient
chatter, and keeping the live flight notification current — for the length of a flight,
with the screen off and the app not on top.

## How iOS does it, and why Android is different

iOS suspends a normal app within seconds of backgrounding. The only practical way to
keep the poll loop alive is the `audio` background mode plus an app that is *actually
producing audio*. That is why the iOS build ties background operation to the
**background radio chatter** toggle: the chatter is the audio that keeps the process
alive, and the live flight notification therefore *requires* chatter to be on.

Android has no such requirement. A foreground service keeps a process running because it
is a foreground service, not because it is making noise. So on Android:

> **Background operation is not tied to background chatter.** A flight keeps running in
> the background whenever the pilot has started one — with chatter on or off — and the
> Live Flight Update works on its own.

That is a deliberate improvement in the user experience rather than a divergence in
features, and it is recorded in `Docs/ANDROID_PARITY_MATRIX.md`. Critically, it also
means the app **never plays silent audio to stay alive** — a hack that Google Play
policy and Apple's guideline 2.5.4 both rightly prohibit, and which the brief forbids.

## The service

`app/service/ActiveFlightService.kt`, a `LifecycleService`.

**Lifecycle**

- **Started** only from a visible Activity, as part of the pilot's own Connect / Start
  Flight action. It is never started from a broadcast, a job, or process start, so it
  cannot trip Android 12+'s background-start restrictions.
- **Stopped** when the pilot disconnects, when the flight session ends (parked at the
  arrival gate), or when Live Connected Mode is stopped. The service observes
  `ActiveFlightController.isSessionActive` and stops itself the moment it goes false.
- `START_NOT_STICKY`: a session is started deliberately by a pilot. A silently
  resurrected flight with no connection and no context would be worse than none.

**Foreground service type: `mediaPlayback`**

Declared in the manifest with `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`.

The justification is that the service genuinely plays audio for the whole session: every
controller call and every pilot read-back is spoken, the mic-key thump and squelch tail
bracket the pilot's transmissions, and the optional chatter is a continuous radio bed.
The service holds audio focus for its lifetime to back that claim. Silence between
transmissions is what a radio sounds like — it is not silence played to stay alive.

The alternatives were considered and rejected:

| Type | Why not |
| --- | --- |
| `dataSync` | Android 15 caps it at roughly six hours a day and is steering apps away from it. Long-haul flights exceed that. |
| `connectedDevice` | Scoped to Bluetooth/USB/companion-device interaction. A TCP socket to another device on the same Wi-Fi is not that. |
| `location` | The app requests no location permission and needs none. |
| `specialUse` | Available as a fallback (see below), but it requires a Play review justification, so it is not the first choice for a service that really is playing audio. |

**If Play ever objects to `mediaPlayback`**, the documented fallback is `specialUse`:

```xml
<service android:name=".service.ActiveFlightService"
    android:foregroundServiceType="specialUse">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Maintains a local-network connection to a flight simulator and
                       speaks simulated air-traffic-control audio for the duration of a
                       user-started flight." />
</service>
```

with `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` in place of the media-playback
permission. Nothing else changes.

## The Live Flight Update

Android's counterpart to the iOS Live Activity. Same content, different presentation —
and it is **never called a "Live Activity"** in Android UI, because that is Apple's term.

| Android version | Presentation |
| --- | --- |
| **16 (API 36) and above** | The ongoing notification requests promotion (`setRequestPromotedOngoing(true)`) and supplies short critical text, so the flight surfaces prominently on the lock screen and in the status bar chip. |
| **10–15 (API 29–35)** | A well-formed ongoing foreground-service notification carrying the same fields and the same actions. |

**Content** (`core/liveupdate/LiveFlightUpdate.kt`), a faithful port of the iOS
`ContentState`: flight phase, controller and its icon, altitude / heading / speed,
callsign, route, the next controller when a hand-off is pending, a weather advisory when
one is active, whether Read Back and Check In are available, standby state, and when the
snapshot was taken.

**Actions**: **Read Back** and **Check In**, shown only when the session says they apply.
They are broadcasts handled by `FlightNotificationActionReceiver` and forwarded to the
service, which owns the session — so they work with no Activity alive.

**One deliberate difference.** The iOS card also carries a **Refresh** button, because
ActivityKit throttles a backgrounded app's routine pushes and the numbers freeze even
while the app is perfectly connected. Android has no such throttle: the foreground
service keeps running and re-posts the notification as the state moves, so the numbers
stay live on their own. Offering a Refresh button that refreshed nothing would be worse
than not offering one.

## Notification channel

One channel, `active_flight`, at `IMPORTANCE_LOW` with sound and vibration off. The
app's own spoken ATC is the alert; a chime on every telemetry update across a whole
flight would be intolerable. The notification is silent and `setOnlyAlertOnce`, so
updating it never re-alerts.

## Permissions

`POST_NOTIFICATIONS` is requested at runtime the first time a flight is started, with a
plain-language rationale. **A denial is not fatal**: the session still runs, and the app
says the live update is unavailable rather than refusing to fly. On Android 12 and above
a foreground service can still run without notification permission — the notification is
simply not shown.

## Battery

- The Infinite Flight poll stays at the iOS cadence (1 Hz) and is not increased.
- Weather, ATIS and OSM refreshes keep their iOS intervals and caches; nothing is
  re-fetched because the app moved to the background.
- The audio render loop is paced by a blocking `AudioTrack` write, so it generates
  exactly as much audio as is consumed and never spins.
- No wake lock is taken beyond what the foreground service and the audio session imply.
- Nothing polls when no flight session is running — the service is not alive then.
