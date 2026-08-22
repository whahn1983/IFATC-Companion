# What is left to finish the Android port

The parity matrix says what every capability's status *is*. The release checklist says
what must be true before submitting. Neither answers "what work is left", so this does —
in the order it is worth doing, with honest sizes.

Sizes are the real cost including tests, not the size of the diff. "Needs a device" means
the work cannot be trusted without running it, and this repository's CI has no device or
emulator: it compiles `:app`, gates Android Lint, and runs R8, and that is all it can do.

Status at the time of writing: `:core` is **1058 tests / 93 classes / 0 failures**; `:app`
compiles, lints clean under `abortOnError = true`, and produces a minified release bundle.

---

## 0. Before anything else — the gate everything is behind

- [ ] **Run a full gate-to-gate flight on a device, and listen to it.** Nothing in this
      port has ever run on hardware and nothing has been heard. The radio effect chain, the
      TTS voices, the chatter mix and the squelch bursts are ported maths that has never
      reached a speaker. **Needs a device.**
- [ ] **Run the same flight on the minified build.** R8 building a bundle is not the app
      surviving R8: a stripped `kotlinx.serialization` serializer fails on first
      deserialization, not at build time. Exercise settings, saved flights, phraseology
      profiles, the surface cache and Play Billing on the release build.
      **Needs a device.**

Everything below is smaller than these two. A feature that has never run is not finished,
however many tests it has — this port has already found subsystems with passing tests that
did nothing at all in the app, which is what the parity matrix's 🔌 status now records.

---

## 1. Features iOS has that Android does not

**100, listed in `ANDROID_PARITY_GAPS.md`.** An adversarial sweep of the whole iOS source
found them after this section had been declared empty — which it was, of the five gaps
anybody had noticed. Those five were real and are closed; they were not the list.

**47 of the 100 were "ported, not wired":** the code was in `:core`, its tests passed, and
nothing in `:app` ever constructed it. Nothing there broke a build or failed a test. That
is precisely why it survived this long, and why the 🔌 status exists.

37 were rated high — a pilot hits them in a normal flight. Among them: **a check-in gets no
controller reply on any frequency**, **Say Again never makes the controller repeat**, and
**Unable is answered with silence**.

**Status: 99 of the 100 are closed. The one remainder is partly closed; nothing is open.**
It is the Weather Diagnostics card, listed in `ANDROID_PARITY_GAPS.md` with the rows still
out and why: the wind-triangle rows need a wind triangle Android does not solve, and the
OPERA byte counters would read zero because OPERA rendering is off on both platforms by
decision.

The deliberate differences that remain are recorded in `ANDROID_PARITY_MATRIX.md` (🔵) and
in section 4 below. None of the 100 is one of those.

---

## 2. Cross-cutting gaps

| # | Work | Size | Notes |
| --- | --- | --- | --- |
| 2.1 | **Cover the 22 🟡 rows with tests** | Ongoing | Ported and compiling, no test of their own. Worth attacking by risk rather than by count. |
| 2.2 | **Instrumented tests** | Medium | There are none. Even a handful over the flight session, the foreground service and the billing flow would catch the class of defect that static review and unit tests both miss. **Needs a device or emulator.** |

---

## 3. Release mechanics

Tracked in full in `GOOGLE_PLAY_RELEASE_CHECKLIST.md` — 47 open items. The ones with lead
time, so they are worth starting early:

- [ ] Create the upload keystore, store it outside the repository, enrol in Play App Signing.
- [ ] Create the three products in Play Console and confirm a signed `bundleRelease`.
- [ ] Store listing: screenshots at phone, 7-inch and 10-inch, feature graphic, descriptions.
- [ ] Data safety form, content rating, target audience declarations.
- [ ] Licence testers, then exercise purchase, cancel, restore and a pending purchase.

---

## 4. Deliberately not doing

Recorded so nobody re-opens them as oversights:

- **A hosted map provider.** Every SDK considered failed the no-key / no-recurring-bill bar
  or the OSM tile usage policy. The app renders its own maps on a Compose canvas.
  `ANDROID_MAPPING.md` records each rejection.
- **Silent-audio background keep-alive.** The foreground service is the sanctioned
  mechanism and needs no audio. This is why the Live Flight Update and background chatter
  are independent settings on Android where iOS interlocks them.
- **Bulk dependency upgrades.** Thirty lint warnings say newer versions exist. On a branch
  that cannot be run on a device, bumping them is the riskier move, not the safer one.
- **Apple's Terms and subscription-management links.** Replaced; the iOS renewal wording
  would be false on Android.
- **Localization.** The app ships **English only**, by decision. That makes the Compose
  screens' hardcoded strings a non-issue rather than a gap: they are compiled by `:uicheck`
  without Android resources so they cannot call `stringResource`, and with one language
  there is nothing that arrangement costs.

  Worth separating from accessibility, which the same strings looked like they affected and
  do not. Screen-reader labelling lives in the code and is in good shape: icon-only controls
  carry real `contentDescription`s, decorative icons inside labelled buttons correctly pass
  `null` so TalkBack does not announce them twice, and the transcript and settings rows use
  `semantics(mergeDescendants = true)`. Verifying it with TalkBack switched on is still a
  device task and stays on the release checklist.
